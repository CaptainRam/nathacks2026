"""Run pinned GGUF models serially against the project's small diagnostic corpus.

All inference is local. Text and image results are separate tasks. Reported RSS is
the sampled server process RSS on this Mac, not Android RAM or total Metal memory.
"""
import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import subprocess
import threading
import time

import psutil
import requests

from score import parse_reply, score_case

ROOT = Path(__file__).resolve().parent


def prompt_for(ocr_text=None):
    # Same task, constraints and example as IngredientPrompt.java; no model-specific hints.
    text = """You read text from a photo of a food label. The text came from OCR, so some words may be
misread: letters swapped, digits in place of letters, or words split apart. For example
"raregridients" or "lngredients" means "ingredients", "sug4r" means "sugar",
"sodlum acetate" means "sodium acetate", and "pea nut" means "peanut".

Task: list every ingredient from the label's ingredient list.

Rules:
- Write one ingredient per line, starting with "- ".
- Correct misread words to the real ingredient name.
- Keep the label's order.
- Keep sub-ingredients in brackets with their ingredient, for example
  "- Chocolate (sugar, cocoa butter, soy lecithin)".
- Include additives and chemicals such as sodium acetate, citric acid or E330.
- Leave out the heading, notes like "Contains 2% or less of", allergen warnings
  ("Contains: ...", "May contain ..."), nutrition facts and all other text.
- Never add an ingredient that is not in the text.
- If there is no ingredient list in the text, reply with exactly: NONE

Example
Label text: Crunchy Bar 50g RAREGRIDIENTS: Oats, sug4r, palm 0il, Contains 2% or less of:
salt, sodlum bicarbonate. May contain peanuts. Nutrition Facts Calories 210
Reply:
- Oats
- sugar
- palm oil
- salt
- sodium bicarbonate

Now do the same for this label."""
    if ocr_text is None:
        return text.replace("The text came from OCR, so some words may be", "Some words may be") + "\nRead the attached label image. Reply:"
    return text + "\nLabel text: " + ocr_text[:3000] + "\nReply:"


class Server:
    def __init__(self, model, args, mode, output):
        self.model, self.args, self.mode, self.output = model, args, mode, output
        self.peak_rss = 0
        self.min_available = psutil.virtual_memory().available
        self.stop_sampling = threading.Event()

    def __enter__(self):
        directory = Path(self.args.cache) / self.model["id"]
        ready = directory / "ready.json"
        if not ready.exists():
            raise FileNotFoundError(f"Download not ready: {ready}")
        phase = "-timing" if self.args.performance_only else ""
        self.log_path = self.output / f"{self.model['id']}-{self.mode}{phase}-server.log"
        self.log = self.log_path.open("w")
        self.command = ["llama-server", "-m", str(directory / self.model["file"]),
            "--host", "127.0.0.1", "--port", str(self.args.port),
            "--ctx-size", "8192" if self.mode == "vision" else "4096",
            "--parallel", "1", "--batch-size", "512", "--ubatch-size", "128",
            "--threads", "5", "--threads-batch", "5", "--gpu-layers", self.args.gpu_layers,
            "--flash-attn", "on", "--no-cache-prompt", "--reasoning", "off",
            "--chat-template-kwargs", '{"enable_thinking":false}']
        if self.mode == "vision":
            self.command += ["--mmproj", str(directory / self.model["projector"]),
                "--image-max-tokens", "2048"]
        self.started = time.monotonic()
        self.swap_before = psutil.swap_memory().used
        self.proc = subprocess.Popen(self.command, stdout=self.log, stderr=subprocess.STDOUT)
        self.monitor = threading.Thread(target=self._sample, daemon=True)
        self.monitor.start()
        self.url = f"http://127.0.0.1:{self.args.port}"
        try:
            while time.monotonic() - self.started < 180:
                if self.proc.poll() is not None:
                    raise RuntimeError(f"Server exited {self.proc.returncode}; see {self.log_path}")
                try:
                    if requests.get(self.url + "/health", timeout=1).status_code == 200:
                        self.load_seconds = time.monotonic() - self.started
                        self.ready_rss = psutil.Process(self.proc.pid).memory_info().rss
                        return self
                except requests.RequestException:
                    pass
                time.sleep(0.25)
            raise TimeoutError("Model did not load within 180 seconds")
        except Exception:
            self.__exit__(None, None, None)
            raise

    def _sample(self):
        while not self.stop_sampling.wait(0.1):
            try:
                self.peak_rss = max(self.peak_rss, psutil.Process(self.proc.pid).memory_info().rss)
                self.min_available = min(self.min_available, psutil.virtual_memory().available)
            except psutil.Error:
                return

    def infer(self, case):
        content = prompt_for(case["ocr_text"])
        if self.mode == "vision":
            image_path = Path(case["image_file"])
            if not image_path.is_absolute():
                image_path = ROOT / image_path
            mime = "image/png" if image_path.suffix.lower() == ".png" else "image/jpeg"
            encoded = base64.b64encode(image_path.read_bytes()).decode("ascii")
            content = [{"type":"text", "text":prompt_for()},
                {"type":"image_url", "image_url":{"url":f"data:{mime};base64,{encoded}"}}]
        payload = {"messages":[{"role":"user", "content":content}], "stream":True,
            "stream_options":{"include_usage":True}, "temperature":0,
            "top_k":1, "seed":0, "max_tokens":512, "cache_prompt":False,
            "chat_template_kwargs":{"enable_thinking":False}}
        start = time.monotonic()
        first = None
        reply, reasoning = "", ""
        usage, timings, finish = {}, {}, None
        with requests.post(self.url + "/v1/chat/completions", json=payload,
                stream=True, timeout=(5, 180)) as response:
            response.raise_for_status()
            for line in response.iter_lines(chunk_size=1):
                if not line or not line.startswith(b"data: "):
                    continue
                data = line[6:]
                if data == b"[DONE]":
                    break
                chunk = json.loads(data)
                usage = chunk.get("usage") or usage
                timings = chunk.get("timings") or timings
                for choice in chunk.get("choices", []):
                    delta = choice.get("delta", {})
                    piece = delta.get("content") or ""
                    if piece and first is None:
                        first = time.monotonic() - start
                    reply += piece
                    reasoning += delta.get("reasoning_content") or ""
                    finish = choice.get("finish_reason") or finish
        elapsed = time.monotonic() - start
        result = {"id":case["id"], "mode":self.mode, "source_kind":case["source_kind"],
            "source_group":case["source_group"], "reply":reply, "reasoning":reasoning,
            "elapsed_seconds":elapsed, "first_token_seconds":first, "usage":usage,
            "timings":timings, "finish_reason":finish}
        try:
            result["ingredients"] = parse_reply(reply)
            result["score"] = score_case(case, result["ingredients"])
            result["status"] = "ok"
        except ValueError as error:
            result["status"], result["error"] = "format_error", str(error)
        return result

    def __exit__(self, *_):
        self.stop_sampling.set()
        if getattr(self, "proc", None) and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)
        self.log.close()

    def metrics(self):
        return {"command":self.command, "load_seconds":self.load_seconds,
            "ready_rss_bytes":self.ready_rss, "peak_sampled_rss_bytes":self.peak_rss,
            "minimum_system_available_bytes":self.min_available,
            "system_swap_delta_bytes":psutil.swap_memory().used-self.swap_before,
            "memory_caveat":"Mac process RSS sampled every100ms; excludes unaccounted GPU allocations; not phone RAM.",
            "server_log":str(self.log_path)}


def run(model, args, mode, cases, output):
    suffix = "-timing" if args.performance_only else ""
    destination = output / f"{model['id']}-{mode}{suffix}.json"
    if destination.exists() and not args.rerun:
        print(f"SKIP existing {destination.name}", flush=True)
        return
    report = {"schema_version":1, "model":model, "mode":mode,
        "started_at":datetime.now(timezone.utc).isoformat(), "notes":args.note,
        "artifacts":json.loads((Path(args.cache)/model["id"]/"ready.json").read_text()),
        "hardware":{"machine":platform.machine(), "system":platform.platform(),
            "ram_bytes":psutil.virtual_memory().total, "cpu_count":psutil.cpu_count()},
        "runtime":subprocess.check_output(["llama-server", "--version"],stderr=subprocess.STDOUT,text=True).strip(),
        "settings":{"temperature":0, "seed":0, "max_output_tokens":512,
            "thinking":False, "prompt_caching":False, "quantization":"Q4_K_M"},
        "cases":[], "performance_repeats":[]}
    print(f"START {model['id']} {mode}", flush=True)
    try:
        with Server(model, args, mode, output) as server:
            print(f"LOADED {model['id']} {mode} {server.load_seconds:.2f}s", flush=True)
            for case in cases:
                try:
                    record = server.infer(case)
                except Exception as error:
                    record = {"id":case["id"], "status":"inference_error", "error":str(error)}
                report["cases"].append(record)
                destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
                score = record.get("score", {})
                print(f"CASE {model['id']} {mode} {case['id']} {record['status']} "
                    f"{record.get('elapsed_seconds',0):.2f}s recall={score.get('recall')} "
                    f"extra={score.get('unexpected')}", flush=True)
            # First suite request is cold; repeat the same real input to sample warm full-label latency.
            for repetition in range(args.repeats):
                record = server.infer(cases[0])
                record["repeat"] = repetition + 1
                report["performance_repeats"].append(record)
                print(f"REPEAT {model['id']} {mode} {repetition+1} {record['elapsed_seconds']:.2f}s", flush=True)
            report["server_metrics"] = server.metrics()
    except Exception as error:
        report["server_error"] = str(error)
        print(f"ERROR {model['id']} {mode}: {error}", flush=True)
    report["complete"] = "server_error" not in report and len(report["cases"]) == len(cases)
    report["completed_at"] = datetime.now(timezone.utc).isoformat()
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"DONE {model['id']} {mode}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model")
    parser.add_argument("--mode", choices=["text","vision","all"],default="all")
    parser.add_argument("--cache",default="/tmp/allergy-model-benchmark/models")
    parser.add_argument("--output",default=str(ROOT / "results"))
    parser.add_argument("--port",type=int,default=18765)
    parser.add_argument("--gpu-layers",default="99")
    parser.add_argument("--repeats",type=int,default=2)
    parser.add_argument("--rerun",action="store_true")
    parser.add_argument("--performance-only",action="store_true")
    parser.add_argument("--note",default="")
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True,exist_ok=True)
    corpus = json.loads((ROOT / "cases.json").read_text())
    for model in json.loads((ROOT / "models.json").read_text())["models"]:
        if args.model and model["id"] != args.model:
            continue
        if args.mode in ("text", "all"):
            cases = corpus["cases"][:1] if args.performance_only else corpus["cases"]
            run(model,args,"text",cases,output)
        if args.mode in ("vision", "all") and model.get("vision"):
            cases = [case for case in corpus["cases"] if case.get("evaluate_vision")]
            run(model,args,"vision",cases[:1] if args.performance_only else cases,output)


if __name__ == "__main__":
    main()
