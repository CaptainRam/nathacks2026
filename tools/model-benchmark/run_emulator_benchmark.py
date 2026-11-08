"""Run the existing corpus inside an explicitly selected Android emulator.

The host supplies inputs through adb forwarding. All model inference is performed
by the Android ARM64 process. Emulated CPU/RAM results are not handset results.
Only one model is copied at a time; this script never touches physical-phone data.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import requests
import shlex
import subprocess
import threading
import time

from run_benchmark import ROOT, Server
from run_phone_benchmark import PhoneServer, adb, shell


def device_info(args):
    props = ["ro.product.model", "ro.build.version.release", "ro.build.version.sdk",
             "ro.product.cpu.abi", "ro.hardware", "ro.kernel.qemu"]
    info = {key:shell(args, "getprop " + key).strip() for key in props}
    if info["ro.kernel.qemu"] != "1":
        raise ValueError("This entry point only runs on a verified emulator")
    info.update(physical_device=False, platform="android_emulator", serial=args.serial,
                captured_at=datetime.now(timezone.utc).isoformat(),
                online_cpu_count=int(shell(args,"getconf _NPROCESSORS_ONLN").strip()),
                memory_kib={k:int(v) for k,v in re.findall(
                    r"^(MemTotal|MemAvailable|SwapTotal|SwapFree):\s+(\d+)",
                    shell(args,"cat /proc/meminfo"),re.M)},
                disk_before=shell(args,"df -k /data/local/tmp"),
                limitations="Virtual CPU/memory, CPU backend only. No physical GPU/NPU, thermal, battery, or APK lifecycle conclusions.")
    return info


def remove_model(args, model, mode):
    names=[model["file"]]
    if mode != "text" and model.get("projector"):
        names.append(model["projector"])
    directory=args.remote+"/models/"+model["id"]
    for name in names:
        shell(args,"rm -f "+shlex.quote(directory+"/"+name),check=False)
    shell(args,"rmdir "+shlex.quote(directory),check=False)


def deploy(args, model):
    directory=args.remote+"/models/"+model["id"]
    shell(args,"mkdir -p "+shlex.quote(directory))
    artifacts=json.loads((Path(args.cache)/model["id"]/"ready.json").read_text())["artifacts"]
    for artifact in artifacts:
        if args.mode=="text" and artifact["file"]==model.get("projector"):
            continue
        target=directory+"/"+artifact["file"]
        if shell(args,"stat -c %s "+shlex.quote(target),check=False).strip()!=str(artifact["bytes"]):
            print("PUSH",model["id"],artifact["file"],flush=True)
            transfer=adb(args,"push",str(Path(args.cache)/model["id"]/artifact["file"]),target,timeout=600)
            print(transfer.stdout.strip(),flush=True)
        actual=shell(args,"sha256sum "+shlex.quote(target),timeout=180).split()[0]
        if actual!=artifact["sha256"]:
            raise ValueError("Emulator artifact checksum mismatch: "+target)
    print("EMULATOR_MODEL_READY",model["id"],flush=True)


class EmulatorServer(PhoneServer):
    def __exit__(self,*exception):
        self.pre_cleanup_returncode=self.proc.poll() if getattr(self,"proc",None) else None
        self.termination_requested=self.pre_cleanup_returncode is None and bool(getattr(self,"pid",None))
        return super().__exit__(*exception)

    def infer(self,case):
        # Emulator thermal/battery sensor values are not physical measurements.
        self.deadline_exceeded=False
        def expire():
            self.deadline_exceeded=True
            if self.pid:
                command=shell(self.args,f"cat /proc/{self.pid}/cmdline",timeout=10,check=False)
                expected=self.args.remote+"/"+self.args.runtime+"/llama-server"
                if expected in command:
                    shell(self.args,f"kill -TERM {self.pid}",timeout=10,check=False)
                    # Some long image kernels defer graceful cancellation.
                    for _ in range(5):
                        time.sleep(1)
                        command=shell(self.args,f"cat /proc/{self.pid}/cmdline",timeout=10,check=False)
                        if expected not in command:return
                    shell(self.args,f"kill -KILL {self.pid}",timeout=10,check=False)
        timer=threading.Timer(self.args.case_timeout,expire)
        timer.daemon=True
        timer.start()
        start=time.monotonic()
        try:
            record=Server.infer(self,case)
            record["execution_platform"]="android_emulator"
            return record
        except Exception as error:
            timed_out=self.deadline_exceeded or isinstance(error,requests.exceptions.Timeout) or "timed out" in str(error).lower()
            label="timeout" if timed_out else "inference_error"
            return {"id":case["id"],"mode":self.mode,"status":label,"error":str(error),
                "elapsed_seconds":time.monotonic()-start,"source_kind":case["source_kind"],
                "source_group":case["source_group"]}
        finally:
            timer.cancel()

    def metrics(self):
        samples=getattr(self,"samples",[])
        def peak(key):
            found=[sample[key] for sample in samples if key in sample]
            return max(found) if found else None
        return {"command":getattr(self,"command",None),"load_seconds":getattr(self,"load_seconds",None),
            "peak_sampled_rss_bytes":peak("VmRSS"),"kernel_peak_rss_bytes":peak("VmHWM"),
            "peak_sampled_pss_bytes":peak("Pss"),"peak_sampled_swap_bytes":peak("VmSwap"),
            "minimum_system_available_bytes":min((sample["MemAvailable"] for sample in samples if "MemAvailable" in sample),default=None),
            "process_returncode":self.proc.poll() if getattr(self,"proc",None) else None,
            "exited_before_cleanup":getattr(self,"pre_cleanup_returncode",None),
            "termination_requested":getattr(self,"termination_requested",None),
            "backend":"cpu","samples":samples,"server_log":str(self.log_path),
            "memory_caveat":"Android emulator native process, /proc sampled ~1s; virtual RAM and CPU, not phone requirements; not app lifecycle/camera/GPU/NPU/thermal/power measurement."}


def run(model,args,mode,cases,output,hardware):
    destination=output/f"{model['id']}-{mode}.json"
    if destination.exists() and not args.rerun:
        print("SKIP",destination.name,flush=True)
        return
    runtime_dir=args.remote+"/"+args.runtime
    version_result=adb(args,"shell","env "+shlex.quote("LD_LIBRARY_PATH="+runtime_dir)+" "+
                  shlex.quote(runtime_dir+"/llama-server")+" --version",check=False)
    version=version_result.stdout+version_result.stderr
    report={"schema_version":1,"platform":"android_emulator","physical_device":False,
        "hardware":hardware,"model":model,"mode":mode,"scope":args.scope,
        "runtime":version,"started_at":datetime.now(timezone.utc).isoformat(),
        "settings":{"backend":"cpu","threads":args.threads,"max_output_tokens":512,
            "thinking":False,"temperature":0,"seed":0,"prompt_caching":False,
            "case_wall_timeout_seconds":args.case_timeout,"quantization":"Q4_K_M"},
        "artifacts":json.loads((Path(args.cache)/model["id"]/"ready.json").read_text()),
        "cases":[],"performance_repeats":[]}
    server=EmulatorServer(model,args,mode,output)
    print("START",model["id"],mode,args.scope,flush=True)
    try:
        with server:
            print("LOADED",model["id"],mode,round(server.load_seconds,2),flush=True)
            stopped=False
            for case in cases:
                if stopped:
                    record={"id":case["id"],"status":"not_run_after_server_failure"}
                else:
                    record=server.infer(case)
                report["cases"].append(record)
                report["server_metrics"]=server.metrics()
                destination.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
                score=record.get("score",{})
                print("CASE",model["id"],mode,case["id"],record["status"],
                      round(record.get("elapsed_seconds",0),2),"recall",score.get("recall"),
                      "extra",score.get("unexpected"),flush=True)
                stopped=stopped or record["status"] in {"timeout","inference_error"} or server.proc.poll() is not None
            if not stopped:
                for number in range(args.repeats):
                    record=server.infer(cases[0]);record["repeat"]=number+1
                    report["performance_repeats"].append(record)
                    if record["status"] in {"timeout","inference_error"}:break
    except Exception as error:
        report["server_error"]=str(error)
        print("ERROR",model["id"],mode,str(error),flush=True)
    report["server_metrics"]=server.metrics()
    report["complete"]=len(report["cases"])==len(cases) and "server_error" not in report
    report["all_cases_succeeded"]=report["complete"] and all(c["status"]=="ok" for c in report["cases"])
    report["completed_at"]=datetime.now(timezone.utc).isoformat()
    destination.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    print("DONE",model["id"],mode,"all_cases_succeeded",report["all_cases_succeeded"],flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial",default="emulator-5554")
    parser.add_argument("--adb",default="/Users/lcwhhh/Library/Android/sdk/platform-tools/adb")
    parser.add_argument("--remote",default="/data/local/tmp/allergy-emulator-benchmark")
    parser.add_argument("--runtime",default="runtime")
    parser.add_argument("--cache",default="/tmp/allergy-model-benchmark/models")
    parser.add_argument("--model")
    parser.add_argument("--mode",choices=["text","vision","all"],default="all")
    parser.add_argument("--scope",choices=["pilot","full","timing"],default="full")
    parser.add_argument("--threads",type=int,default=1)
    parser.add_argument("--repeats",type=int,default=0)
    parser.add_argument("--port",type=int,default=18776)
    parser.add_argument("--remote-port",type=int,default=18775)
    parser.add_argument("--case-timeout",type=int,default=300)
    parser.add_argument("--keep-remote-model",action="store_true")
    parser.add_argument("--rerun",action="store_true")
    parser.add_argument("--output")
    args=parser.parse_args()
    args.backend="cpu"
    hardware=device_info(args)
    output=Path(args.output) if args.output else ROOT/"results"/"emulator5554"/"cpu"/args.scope
    output.mkdir(parents=True,exist_ok=True)
    (output/"device.json").write_text(json.dumps(hardware,ensure_ascii=False,indent=2)+"\n")
    models=json.loads((ROOT/"models.json").read_text())["models"]
    corpus=json.loads((ROOT/"cases.json").read_text())["cases"]
    for model in models:
        if args.model and model["id"]!=args.model:continue
        modes=[args.mode] if args.mode!="all" else ["text"]+(["vision"] if model.get("vision") else [])
        modes=[mode for mode in modes if mode!="vision" or model.get("vision")]
        pending=[mode for mode in modes if args.rerun or not (output/f"{model['id']}-{mode}.json").exists()]
        if not pending:continue
        try:
            deploy(args,model)
            for mode in pending:
                cases=[case for case in corpus if mode=="text" or case.get("evaluate_vision")]
                if args.scope=="pilot":cases=[next(case for case in cases if case["id"]=="clean_wheat_fixture")]
                if args.scope=="timing":cases=cases[:1]
                run(model,args,mode,cases,output,hardware)
        except Exception as error:
            for mode in pending:
                destination=output/f"{model['id']}-{mode}.json"
                if destination.exists():continue
                failure={"schema_version":1,"platform":"android_emulator","physical_device":False,
                    "hardware":hardware,"model":model,"mode":mode,"scope":args.scope,
                    "deployment_error":str(error),"cases":[],"performance_repeats":[],
                    "complete":False,"all_cases_succeeded":False,
                    "completed_at":datetime.now(timezone.utc).isoformat()}
                destination.write_text(json.dumps(failure,ensure_ascii=False,indent=2)+"\n")
            print("DEPLOYMENT_ERROR",model["id"],str(error),flush=True)
        finally:
            if not args.keep_remote_model:
                remove_model(args,model,args.mode)
                print("REMOVED_EMULATOR_WEIGHTS",model["id"],flush=True)


if __name__=="__main__":main()
