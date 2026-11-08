"""Execute the same model suite on an explicitly selected physical Android device.

The host only submits inputs and receives outputs through an adb-forwarded loopback
port. Inference and the sampled memory belong to the phone's native server process.
This is not an APK lifecycle, camera, battery-power, or NPU benchmark.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time

import requests

from run_benchmark import ROOT, Server


def adb(args, *command, timeout=60, check=True):
    return subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
        text=True, timeout=timeout, check=check)


def shell(args, command, timeout=60, check=True):
    return adb(args, "shell", command, timeout=timeout, check=check).stdout


def thermal(args):
    battery = shell(args, "dumpsys battery", timeout=15, check=False)
    service = shell(args, "dumpsys thermalservice", timeout=15, check=False)
    def number(pattern, source):
        match = re.search(pattern, source, re.M)
        return float(match.group(1)) if match else None
    temp = number(r"^\s*temperature:\s*(-?[\d.]+)", battery)
    sensors = {}
    for value, kind, name, status in re.findall(
            r"Temperature\{mValue=([^,]+), mType=([^,]+), mName=([^,]+), mStatus=([^}]+)\}", service):
        if name in {"battery", "soc_therm", "BIG", "MID", "LITTLE", "VIRTUAL-SKIN"}:
            try:
                if -20 < float(value) < 150:
                    sensors[name] = {"celsius":float(value), "status":int(status)}
            except ValueError:
                pass
    return {"battery_celsius":temp/10 if temp is not None else None,
        "thermal_status":number(r"Thermal Status:\s*(\d+)",service),
        "battery_level":number(r"^\s*level:\s*(\d+)",battery),
        "externally_powered":any(f"{kind} powered: true" in battery for kind in ["AC","USB","Wireless"]),
        "sensors":sensors}


def device_info(args):
    keys = ["ro.product.model", "ro.build.version.release", "ro.build.version.sdk",
        "ro.soc.model", "ro.product.cpu.abilist", "ro.hardware.vulkan"]
    values = shell(args, "; ".join("getprop " + key for key in keys)).splitlines()
    mem = shell(args,"cat /proc/meminfo")
    info = dict(zip(keys, values))
    info.update(physical_device=True, captured_at=datetime.now(timezone.utc).isoformat(),
        memory_kib={k:int(v) for k,v in re.findall(r"^(MemTotal|MemAvailable|SwapTotal|SwapFree):\s+(\d+)",mem,re.M)},
        initial_thermal=thermal(args), serial_sha256=hashlib.sha256(args.serial.encode()).hexdigest())
    return info


def deploy(args, model):
    local = Path(args.cache)/model["id"]
    remote = args.remote+"/models/"+model["id"]
    shell(args,"mkdir -p " + shlex.quote(remote))
    artifacts = json.loads((local/"ready.json").read_text())["artifacts"]
    for artifact in artifacts:
        name = artifact["file"]
        path = remote+"/"+name
        size = shell(args,"stat -c %s " + shlex.quote(path),check=False).strip()
        if size != str(artifact["bytes"]):
            print("PUSH",model["id"],name,flush=True)
            result=adb(args,"push",str(local/name),path,timeout=600)
            print(result.stdout.strip() or result.stderr.strip(),flush=True)
        digest = shell(args,"sha256sum " + shlex.quote(path),timeout=180).split()[0]
        if digest != artifact["sha256"]:
            raise ValueError("Phone artifact checksum mismatch: "+path)
    print("PHONE_MODEL_READY",model["id"],flush=True)


class PhoneServer(Server):
    def __init__(self, model, args, mode, output):
        self.model, self.args, self.mode, self.output = model,args,mode,output
        self.stop_sampling=threading.Event()
        self.samples=[]

    def __enter__(self):
        a=self.args
        self.log_path=self.output/f"{self.model['id']}-{self.mode}-server.log"
        self.log=self.log_path.open("w")
        runtime=a.remote+"/"+a.runtime
        self.command=[runtime+"/llama-server","-m",a.remote+"/models/"+self.model["id"]+"/"+self.model["file"],
            "--host","127.0.0.1","--port",str(a.remote_port),"--ctx-size","8192" if self.mode=="vision" else "4096",
            "--parallel","1","--batch-size","512","--ubatch-size","128",
            "--threads",str(a.threads),"--threads-batch",str(a.threads),
            "--gpu-layers","0" if a.backend=="cpu" else "99","--flash-attn","on",
            "--no-cache-prompt","--reasoning","off","--chat-template-kwargs",'{"enable_thinking":false}']
        if self.mode=="vision":
            self.command += ["--mmproj",a.remote+"/models/"+self.model["id"]+"/"+self.model["projector"],"--image-max-tokens","2048"]
        adb(a,"forward",f"tcp:{a.port}",f"tcp:{a.remote_port}")
        self.pid_path=a.remote+"/benchmark-server.pid"
        shell(a,"rm -f "+shlex.quote(self.pid_path))
        script="echo $$ > "+shlex.quote(self.pid_path)+"; echo 1000 2>/dev/null > /proc/$$/oom_score_adj || true; exec env "+shlex.quote("LD_LIBRARY_PATH="+runtime)+" "+shlex.join(self.command)
        self.started=time.monotonic()
        self.proc=subprocess.Popen([a.adb,"-s",a.serial,"shell",script],stdout=self.log,stderr=subprocess.STDOUT)
        self.url=f"http://127.0.0.1:{a.port}"
        self.pid=None
        try:
            for _ in range(20):
                value=shell(a,"cat "+shlex.quote(self.pid_path),check=False).strip()
                if value.isdigit():
                    self.pid=int(value)
                    break
                time.sleep(.2)
            self.monitor=threading.Thread(target=self._sample,daemon=True)
            self.monitor.start()
            while time.monotonic()-self.started < 240:
                if self.proc.poll() is not None:
                    raise RuntimeError(f"Phone server exited {self.proc.returncode}; see {self.log_path}")
                try:
                    if requests.get(self.url+"/health",timeout=2).status_code==200:
                        self.load_seconds=time.monotonic()-self.started
                        return self
                except requests.RequestException:
                    pass
                time.sleep(.5)
            raise TimeoutError("Phone model did not initialize within240s")
        except Exception:
            self.__exit__(None,None,None)
            raise

    def _sample(self):
        while not self.stop_sampling.is_set():
            if not self.pid:
                return
            try:
                raw=shell(self.args,f"cat /proc/{self.pid}/status; cat /proc/{self.pid}/smaps_rollup; head -5 /proc/meminfo",timeout=10,check=False)
                values={k:int(v)*1024 for k,v in re.findall(r"^(VmRSS|VmHWM|VmSwap|Pss|MemAvailable):\s+(\d+)\s+kB",raw,re.M)}
                values["elapsed_seconds"]=time.monotonic()-self.started
                self.samples.append(values)
            except (subprocess.SubprocessError,OSError):
                pass
            self.stop_sampling.wait(1)

    def infer(self,case):
        before=thermal(self.args)
        # Severe device thermal status: cool down before another measured request.
        for attempt in range(6):
            if (before.get("thermal_status") or 0)<3:
                break
            print("THERMAL_COOLDOWN",before,flush=True)
            time.sleep(20)
            before=thermal(self.args)
        if (before.get("thermal_status") or 0)>=3:
            raise RuntimeError("Device remains thermally severe; stopped this request")
        record=super().infer(case)
        record["thermal_before"]=before
        record["thermal_after"]=thermal(self.args)
        return record

    def metrics(self):
        def peak(key):return max((s.get(key,0) for s in self.samples),default=0)
        log=self.log_path.read_text(errors="replace")
        offloads=re.findall(r"offloaded\s+(\d+)\s*/\s*(\d+)\s+layers",log)
        return {"command":self.command,"load_seconds":self.load_seconds,
            "peak_sampled_rss_bytes":peak("VmRSS"),"kernel_peak_rss_bytes":peak("VmHWM"),
            "peak_sampled_pss_bytes":peak("Pss"),"peak_sampled_swap_bytes":peak("VmSwap"),
            "minimum_system_available_bytes":min((s["MemAvailable"] for s in self.samples if "MemAvailable" in s),default=None),
            "requested_backend":self.args.backend,"offloaded_layers":int(offloads[-1][0]) if offloads else 0,
            "samples":self.samples,"server_log":str(self.log_path),
            "memory_caveat":"Physical Android native process; /proc sampled about once per second. RSS/PSS exclude unaccounted device/GPU allocations. Native adb shell process is not an Android APK lifecycle measurement."}

    def __exit__(self,*_):
        self.stop_sampling.set()
        if getattr(self,"monitor",None):self.monitor.join(timeout=12)
        if self.pid:
            command=shell(self.args,f"cat /proc/{self.pid}/cmdline",check=False)
            if self.args.remote+"/"+self.args.runtime+"/llama-server" in command:
                shell(self.args,f"kill -TERM {self.pid}",check=False)
        if getattr(self,"proc",None):
            try:self.proc.wait(timeout=15)
            except subprocess.TimeoutExpired:self.proc.terminate()
        self.log.close()
        adb(self.args,"forward","--remove",f"tcp:{self.args.port}",check=False)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial",required=True)
    p.add_argument("--adb",default="/Users/lcwhhh/Library/Android/sdk/platform-tools/adb")
    p.add_argument("--remote",default="/data/local/tmp/allergy-model-benchmark")
    p.add_argument("--runtime",default="runtime")
    p.add_argument("--cache",default="/tmp/allergy-model-benchmark/models")
    p.add_argument("--backend",choices=["cpu","vulkan"],default="cpu")
    p.add_argument("--model")
    p.add_argument("--mode",choices=["text","vision","all"],default="all")
    p.add_argument("--scope",choices=["full","pilot","timing"],default="full")
    p.add_argument("--repeats",type=int,default=2)
    p.add_argument("--threads",type=int,default=6)
    p.add_argument("--port",type=int,default=18766)
    p.add_argument("--remote-port",type=int,default=18765)
    p.add_argument("--deploy-only",action="store_true")
    p.add_argument("--skip-deploy",action="store_true")
    p.add_argument("--rerun",action="store_true")
    p.add_argument("--output")
    a=p.parse_args()
    output=Path(a.output) if a.output else ROOT/"results"/"pixel10"/a.backend/a.scope
    output.mkdir(parents=True,exist_ok=True)
    models=json.loads((ROOT/"models.json").read_text())["models"]
    models=[m for m in models if not a.model or m["id"]==a.model]
    if not a.skip_deploy:
        for model in models:deploy(a,model)
    if a.deploy_only:return
    hardware=device_info(a)
    (output/"device.json").write_text(json.dumps(hardware,ensure_ascii=False,indent=2)+"\n")
    corpus=json.loads((ROOT/"cases.json").read_text())
    version=adb(a,"shell","env "+shlex.quote("LD_LIBRARY_PATH="+a.remote+"/"+a.runtime)+" "+shlex.quote(a.remote+"/"+a.runtime+"/llama-server")+" --version",check=False)
    runtime=version.stdout+version.stderr
    for model in models:
        modes=[a.mode] if a.mode!="all" else ["text"]+(["vision"] if model.get("vision") else [])
        for mode in modes:
            if mode=="vision" and not model.get("vision"):continue
            dest=output/f"{model['id']}-{mode}.json"
            if dest.exists() and not a.rerun:
                print("SKIP",dest.name,flush=True);continue
            cases=[c for c in corpus["cases"] if mode=="text" or c.get("evaluate_vision")]
            if a.scope=="pilot":cases=[next(c for c in cases if c["id"]=="clean_wheat_fixture")]
            if a.scope=="timing":cases=cases[:1]
            report={"schema_version":1,"platform":"physical_android","hardware":hardware,
                "started_at":datetime.now(timezone.utc).isoformat(),"model":model,"mode":mode,"scope":a.scope,
                "runtime":runtime,"settings":{"backend":a.backend,"threads":a.threads,"temperature":0,"seed":0,
                    "max_output_tokens":512,"thinking":False,"prompt_caching":False,"quantization":"Q4_K_M"},
                "artifacts":json.loads((Path(a.cache)/model["id"]/"ready.json").read_text()),
                "cases":[],"performance_repeats":[]}
            print("START",a.backend,model["id"],mode,flush=True)
            try:
                with PhoneServer(model,a,mode,output) as server:
                    print("LOADED",model["id"],mode,round(server.load_seconds,2),flush=True)
                    for case in cases:
                        try:record=server.infer(case)
                        except Exception as error:record={"id":case["id"],"status":"inference_error","error":str(error)}
                        report["cases"].append(record)
                        dest.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
                        s=record.get("score",{})
                        print("CASE",model["id"],mode,case["id"],record["status"],round(record.get("elapsed_seconds",0),2),
                            "recall",s.get("recall"),"extra",s.get("unexpected"),flush=True)
                    for repeat in range(a.repeats):
                        record=server.infer(cases[0]);record["repeat"]=repeat+1
                        report["performance_repeats"].append(record)
                        print("REPEAT",model["id"],mode,repeat+1,round(record["elapsed_seconds"],2),flush=True)
                    report["server_metrics"]=server.metrics()
                    if a.backend=="vulkan" and not report["server_metrics"]["offloaded_layers"]:
                        report["backend_warning"]="GPU layer offload not verified; do not label this GPU performance."
            except Exception as error:
                report["server_error"]=str(error)
                print("ERROR",model["id"],mode,str(error),flush=True)
            report["complete"]="server_error" not in report and len(report["cases"])==len(cases)
            report["completed_at"]=datetime.now(timezone.utc).isoformat()
            dest.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
            print("DONE",model["id"],mode,flush=True)


if __name__=="__main__":main()
