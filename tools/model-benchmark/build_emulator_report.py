"""Summarize emulator-only runs without inference or device access.

Operational failures have no measured extraction accuracy. A malformed completed
reply is a measured task failure. Pilot results never fill missing full-suite
cases. Native Android emulator CPU measurements are not handset measurements.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import statistics
from typing import Any
from zoneinfo import ZoneInfo

from build_report import aggregate, case_summary, read_json

ROOT = Path(__file__).resolve().parent
CURRENT = "real_sideways_enhanced"
LABELS = {
    "not_run": "未运行", "running": "进行中", "complete": "已完成",
    "completed_with_errors": "已结束，有失败", "server_error": "服务失败",
    "snapshot_error": "快照暂不可读", "invalid_platform": "平台标记不符",
    "logs_only": "尚无已保存结果", "ok": "已输出", "format_error": "格式错误",
    "timeout": "超时", "inference_error": "推理失败",
    "not_run_after_server_failure": "前序失败后未运行",
    "intentionally_stopped": "该配置已主动结束",
    "not_run_profile_stopped": "该配置结束后未运行",
}


def number(value: Any, digits: int = 2) -> str:
    return f"{value:.{digits}f}" if isinstance(value, (int, float)) else "—"


def percent(value: Any) -> str:
    return f"{value * 100:.1f}%" if isinstance(value, (int, float)) else "—"


def mib(value: Any) -> str:
    return number(value / 2**20, 1) if isinstance(value, (int, float)) else "—"


def cell(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def link(path: Path, output: Path, label: str | None = None) -> str:
    relative = os.path.relpath(path, output)
    return f"[{label or path.name}]({relative})"


def cases_for(corpus: list[dict[str, Any]], mode: str, scope: str) -> list[dict[str, Any]]:
    selected = [case for case in corpus if mode == "text" or case.get("evaluate_vision")]
    if scope == "pilot":
        return [next(case for case in selected if case["id"] == "clean_wheat_fixture")]
    return selected[:1] if scope == "timing" else selected


def brief_metrics(raw: dict[str, Any]) -> dict[str, Any]:
    source = raw.get("server_metrics") or {}
    keys = ["load_seconds", "peak_sampled_rss_bytes", "kernel_peak_rss_bytes",
            "peak_sampled_pss_bytes", "peak_sampled_swap_bytes", "minimum_system_available_bytes",
            "process_returncode", "exited_before_cleanup", "termination_requested",
            "backend", "server_log", "memory_caveat", "command"]
    metrics = {key: source.get(key) for key in keys}
    metrics["sample_count"] = len(source.get("samples") or [])
    if source.get("termination_requested") is True:
        metrics["exit_interpretation"] = "intentional_shutdown_requested"
    elif raw.get("all_cases_succeeded") and source.get("process_returncode") in (0, 143, -15):
        metrics["exit_interpretation"] = "cases_completed_before_teardown"
    else:
        metrics["exit_interpretation"] = "not_determined"
    return metrics


def diagnostics(raw: dict[str, Any], path: Path) -> dict[str, Any]:
    log_path = Path((raw.get("server_metrics") or {}).get("server_log") or path.with_name(path.stem + "-server.log"))
    selected: list[str] = []
    if log_path.is_file():
        for line in log_path.read_text(errors="replace").splitlines():
            if any(word in line.casefold() for word in (
                "out of memory", "failed to allocate", "cannot allocate", "bad_alloc",
                "killed", "signal ", "sigsegv", "sigill", "fatal", "error:", "assertion failed")):
                selected.append(line)
    return {"server_error": raw.get("server_error"), "readable_server_log": log_path.is_file(),
            "server_log": str(log_path), "relevant_log_lines": selected[-20:],
            "exit_code_alone_proves_oom": False}


def summarize_run(path: Path, corpus: list[dict[str, Any]], mode: str, scope: str) -> dict[str, Any]:
    raw, read_error = read_json(path)
    raw = raw or {}
    valid = not raw or (raw.get("platform") == "android_emulator" and raw.get("physical_device") is False)
    records = {record.get("id"): record for record in raw.get("cases", [])} if valid else {}
    planned = cases_for(corpus, mode, scope)
    by_id = {case["id"]: case_summary(case, records.get(case["id"])) for case in planned}
    all_cases_succeeded = bool(valid and raw.get("completed_at") and
        all(row["status"] == "ok" for row in by_id.values()) and len(by_id) == len(planned))
    if not valid:
        phase = "invalid_platform"
    elif read_error:
        phase = "snapshot_error"
    elif raw.get("server_error"):
        phase = "server_error"
    elif raw.get("completed_at"):
        phase = "complete" if all_cases_succeeded else "completed_with_errors"
    elif raw:
        phase = "running"
    elif path.with_name(path.stem + "-server.log").exists():
        phase = "logs_only"
    else:
        phase = "not_run"
    for case_id, row in by_id.items():
        record = records.get(case_id) or {}
        row["native_timings"] = record.get("timings") or {}
        row["usage"] = record.get("usage") or {}
    positives = [case for case in planned if case["source_kind"] == "synthetic" and not case["expected_none"]]
    negatives = [case for case in planned if case["expected_none"]]
    real = [case for case in planned if case["source_kind"] == "real"]
    return {
        "source_file": str(path), "file_present": path.exists(), "snapshot_error": read_error,
        "phase_status": phase, "mode": mode, "scope": scope, "platform": raw.get("platform"),
        "physical_device": raw.get("physical_device"), "platform_marker_valid": valid,
        "started_at": raw.get("started_at"), "completed_at": raw.get("completed_at"),
        "all_cases_succeeded": all_cases_succeeded,
        "source_reported_all_cases_succeeded": raw.get("all_cases_succeeded", False),
        "model": raw.get("model"), "hardware": raw.get("hardware"),
        "runtime": raw.get("runtime") or None, "settings": raw.get("settings"),
        "artifacts": raw.get("artifacts"), "cases": by_id,
        "all_cases": aggregate(planned, by_id), "real_cases": aggregate(real, by_id),
        "synthetic_positive_cases": aggregate(positives, by_id), "negative_cases": aggregate(negatives, by_id),
        "server_metrics": brief_metrics(raw), "diagnostics": diagnostics(raw, path),
        "performance_repeats": [
            {"id": item.get("id"), "status": item.get("status"), "elapsed_seconds": item.get("elapsed_seconds"),
             "first_token_seconds": item.get("first_token_seconds"), "timings": item.get("timings"),
             "finish_reason": item.get("finish_reason")}
            for item in raw.get("performance_repeats", [])],
    }


def mac_current(model_id: str, mode: str) -> dict[str, Any]:
    candidates = [ROOT / "results" / f"{model_id}-{mode}-timing.json", ROOT / "results" / f"{model_id}-{mode}.json"]
    for path in candidates:
        raw, _ = read_json(path)
        if not raw:
            continue
        row = next((case for case in raw.get("cases", []) if case.get("id") == CURRENT), None)
        if row:
            repeats = [case["elapsed_seconds"] for case in raw.get("performance_repeats", [])
                       if isinstance(case.get("elapsed_seconds"), (int, float))]
            return {"source_file": str(path), "first_request_seconds": row.get("elapsed_seconds"),
                    "warm_median_seconds": statistics.median(repeats) if repeats else None,
                    "backend_label": "Mac Metal（与模拟器CPU不同）"}
    return {}


def prior_profile(args: argparse.Namespace) -> dict[str, Any] | None:
    if args.results.name != "emulator5554-4cpu6gb":
        return None
    baseline = args.results.parent / "emulator5554"
    state, error = read_json(baseline / "profile-state.json")
    if not state or state.get("status") != "baseline_stopped_for_separate_profile":
        return None
    source = baseline / "cpu" / "full" / "qwen3.5-2b-vision.json"
    raw, source_error = read_json(source)
    durations = {case["id"]: case.get("elapsed_seconds") for case in (raw or {}).get("cases", [])
                 if case.get("status") == "ok" and case.get("source_kind") == "real"}
    return {"baseline_report": str(baseline / "report.md"), "profile_state_source": str(baseline / "profile-state.json"),
            "profile_state": state, "read_error": error or source_error,
            "diagnostic_source": str(source), "qwen35_2b_vision_seconds": durations,
            "qwen35_2b_vision_peak_sampled_swap_bytes": ((raw or {}).get("server_metrics") or {}).get("peak_sampled_swap_bytes"),
            "interpretation": "CPU count, RAM, cold boot and host conditions all changed; elapsed-time ratios are not a clean CPU-scaling experiment."}


def downstream_current(args: argparse.Namespace, primary_runs: list[dict[str, Any]]) -> dict[str, Any]:
    path = args.results / "downstream-allergens.json"
    raw, error = read_json(path)
    raw = raw or {}
    snapshots = {Path(item["file"]).name: item.get("sha256") for item in raw.get("input_snapshots", [])}
    records = {(Path(item.get("source_file", "")).name, item.get("id")): item for item in raw.get("cases", [])}
    rows = []
    for run in primary_runs:
        source = Path(run["source_file"])
        record = records.get((source.name, CURRENT))
        matches = None
        if source.exists() and snapshots.get(source.name):
            matches = hashlib.sha256(source.read_bytes()).hexdigest() == snapshots[source.name]
        usable = bool(record and record.get("analysis_status") == "ok" and matches is True)
        rows.append({"model_id": run["model_id"], "mode": run["mode"], "id": CURRENT,
            "analysis_status": record.get("analysis_status") if record else "not_analyzed",
            "input_snapshot_matches_current": matches, "usable": usable,
            "statuses": {item["allergen"]: item["status"] for item in ((record or {}).get("analysis") or {}).get("allergens", [])} if usable else {},
            "comparison": (record or {}).get("current_photo_comparison") if usable else None})
    return {"source_file": str(path), "file_present": path.exists(), "read_error": error,
            "generated_at": raw.get("generated_at"), "runtime": raw.get("runtime"), "rows": rows,
            "method_note": "Existing Analyzer consumes model ingredients plus the original OCR text for warning statements. Vision rows are a hybrid integration simulation, not vision-only warning recognition or an APK integration test."}


def make_summary(args: argparse.Namespace) -> dict[str, Any]:
    corpus = json.loads(args.cases.read_text())
    models = json.loads(args.models.read_text())["models"]
    profile_state, profile_read_error = read_json(args.results / "profile-state.json")
    profile_stopped = (profile_state or {}).get("status") == "baseline_stopped_for_separate_profile"
    next_profile_report = args.results.parent / "emulator5554-4cpu6gb" / "report.md" if profile_stopped else None
    rows = []
    primary_runs = []
    for model in models:
        modes = ["text"] + (["vision"] if model.get("vision") else [])
        for mode in modes:
            path = args.results / "cpu" / args.scope / f"{model['id']}-{mode}.json"
            primary = summarize_run(path, corpus["cases"], mode, args.scope)
            if profile_stopped:
                primary["configuration_intentionally_stopped"] = True
                if primary["phase_status"] in {"running", "logs_only"}:
                    primary["phase_status"] = "intentionally_stopped"
                elif primary["phase_status"] == "not_run":
                    primary["phase_status"] = "not_run_profile_stopped"
            primary["model_id"] = model["id"]
            primary["mac_current_comparison"] = mac_current(model["id"], mode)
            primary_runs.append(primary)
            extras = {}
            for scope in ("pilot", "full", "timing"):
                other = args.results / "cpu" / scope / f"{model['id']}-{mode}.json"
                if scope != args.scope and other.exists():
                    extras[scope] = summarize_run(other, corpus["cases"], mode, scope)
                    if profile_stopped and extras[scope]["phase_status"] in {"running", "logs_only"}:
                        extras[scope]["phase_status"] = "intentionally_stopped"
            rows.append({"id": model["id"], "mode": mode, "primary": primary, "other_scopes": extras})
    hardware = None
    for scope in (args.scope, "pilot", "timing"):
        device, _ = read_json(args.results / "cpu" / scope / "device.json")
        if device:
            hardware = device
            break
    planned_text = sum(run["all_cases"]["planned_cases"] for run in primary_runs if run["mode"] == "text")
    planned_vision = sum(run["all_cases"]["planned_cases"] for run in primary_runs if run["mode"] == "vision")
    runtime_verification, runtime_read_error = read_json(args.results / "runtime.json")
    restoration, _ = read_json(args.results / "restoration.json")
    return {
        "schema_version": 1, "generated_at": datetime.now(ZoneInfo("America/Edmonton")).isoformat(),
        "platform": "android_emulator", "physical_device": False, "scope": args.scope,
        "hardware": hardware, "cases_source": str(args.cases), "rows": rows,
        "runtime_verification": runtime_verification,
        "runtime_verification_source": str(args.results / "runtime.json"),
        "runtime_verification_read_error": runtime_read_error,
        "restoration": restoration,
        "configuration_intentionally_stopped": profile_stopped,
        "profile_state": profile_state, "profile_state_read_error": profile_read_error,
        "profile_state_source": str(args.results / "profile-state.json"),
        "next_profile_report": str(next_profile_report) if next_profile_report else None,
        "prior_profile": prior_profile(args),
        "downstream": downstream_current(args, primary_runs),
        "coverage": {
            "planned_model_modes": len(primary_runs),
            "saved_model_modes": sum(run["file_present"] for run in primary_runs),
            "successful_model_modes": sum(run["all_cases_succeeded"] for run in primary_runs),
            "planned_text_cases": planned_text, "planned_image_cases": planned_vision,
            "measured_text_cases": sum(run["all_cases"]["accuracy_measured_cases"] for run in primary_runs if run["mode"] == "text"),
            "measured_image_cases": sum(run["all_cases"]["accuracy_measured_cases"] for run in primary_runs if run["mode"] == "vision"),
            "unmeasured_operational_cases": sum(run["all_cases"]["operational_error_count"] for run in primary_runs),
        },
        "models": models,
        "storage": {"language_total_bytes": sum(model["bytes"] for model in models),
                    "projector_total_bytes": sum(model.get("projector_bytes", 0) for model in models),
                    "combined_total_bytes": sum(model["bytes"] + model.get("projector_bytes", 0) for model in models)},
        "limitations": [
            "Only one real packaging photo; its OCR variants and rotations are correlated.",
            "Pilot, full and timing scopes are distinct; pilot outputs do not fill full-suite gaps.",
            "Operational errors/timeouts/loading failures have no measured quality score; they are not NONE.",
            "CPU-only native Android emulator process; no physical phone/GPU/NPU/thermal/power/APK-lifecycle claims.",
            "RSS/PSS/swap sampled approximately once per second; missing samples are unknown, not zero.",
            "Exit code137 alone does not prove an out-of-memory kill;143 can be intentional SIGTERM teardown.",
            "Mac Metal timings are a different backend and cannot predict handset performance.",
        ],
    }


def score_text(row: dict[str, Any] | None) -> str:
    if not row:
        return "未运行"
    if not row.get("quality_measured"):
        return LABELS.get(row["status"], row["status"])
    score = row.get("score")
    if not score:
        return "格式失败；F1 0"
    suffix = "；截断" if row.get("length_limited") else ""
    return f"{score['matched_required']}/{score['required_total']}；F1 {percent(score['f1'])}{suffix}"


def render(summary: dict[str, Any], args: argparse.Namespace) -> str:
    out = args.output
    hardware = summary.get("hardware") or {}
    memory = hardware.get("memory_kib") or {}
    coverage = summary["coverage"]
    lines = ["# Android 模拟器模型测试", "", f"生成时间：{summary['generated_at']}。主报告范围：`{args.scope}`。", "",
        f"主范围计划 {coverage['planned_text_cases']} 个文字案例、{coverage['planned_image_cases']} 个读图案例；"
        f"当前已测得准确性分别为 {coverage['measured_text_cases']} 和 {coverage['measured_image_cases']} 个。"
        f"{coverage['successful_model_modes']}/{coverage['planned_model_modes']} 条模型/模式路径全部成功。", "",
        "这里实际运行 Android ARM64 原生推理程序，宿主机只通过 adb 转发提交输入。**这些是模拟器结果，不是真机性能或手机最低配置。**", "",
        f"环境：{hardware.get('ro.product.model', '尚无设备快照')}；Android {hardware.get('ro.build.version.release', '—')} / "
        f"API {hardware.get('ro.build.version.sdk', '—')}；CPU {hardware.get('online_cpu_count', '—')} 核；"
        f"虚拟总内存 {number(memory.get('MemTotal', 0) / 2**20) if memory.get('MemTotal') is not None else '—'} GiB；CPU 后端。", "",
        "测试沿用固定 Q4_K_M 语言模型、FP16 视觉编码器、温度0、关闭思考与提示缓存、512-token 输出上限。"
        "仅有1张真实包装照片，三份OCR及两种方向均来自它；其余为合成控制。主配料名称、括号子配料、额外内容和截断分别评分。", "",
        "## 原横转包装图", "",
        "下表对应 `real_sideways_enhanced`。text 接收同一OCR；vision 接收照片，两者分开列出。"
        "尚无这个案例的结果时不会用清晰标签pilot替代。", "",
        "| 模型 | 模式 | 状态 | 必需配料/F1 | 多余主项 | 额外子项 | 完整请求s | 首字s | Mac Metal重复中位s |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    if summary.get("configuration_intentionally_stopped"):
        profile = summary.get("profile_state") or {}
        before = profile.get("old_profile") or {}
        after = profile.get("new_profile") or {}
        notice = (
            f"**这份 {before.get('cpus', '—')} 核 / {number(before.get('ram_mb', 0) / 1024, 0)} GiB 配置已主动结束，保留已完成的诊断数据。** "
            f"独立的 {after.get('cpus', '—')} 核 / {number(after.get('ram_mb', 0) / 1024, 0)} GiB 测试进度和结果见 "
            f"{link(Path(summary['next_profile_report']), out, '新配置报告')}。两种资源配置的性能数据分开保存。"
        )
        reason = f"配置变更依据见 {link(Path(summary['profile_state_source']), out)}；未完成项目没有被视为准确性失败，也不再标为仍在运行。"
        lines[4:4] = [notice, "", reason, ""]
    history = summary.get("prior_profile")
    if history:
        state = history["profile_state"]
        old = state.get("old_profile") or {}
        new = state.get("new_profile") or {}
        notice = (f"这份完整对照使用独立的 {new.get('cpus', '—')} 核 / {number(new.get('ram_mb', 0)/1024, 0)} GiB 配置。"
            f"原 {old.get('cpus', '—')} 核 / {number(old.get('ram_mb', 0)/1024, 0)} GiB 诊断已主动结束并保留在 "
            f"{link(Path(history['baseline_report']), out, '原配置报告')}。")
        durations = history.get("qwen35_2b_vision_seconds") or {}
        swap_bytes = history.get("qwen35_2b_vision_peak_sampled_swap_bytes")
        swap_gib = swap_bytes / 2**30 if isinstance(swap_bytes, (int, float)) else None
        detail = ("原配置下 Qwen3.5-2B 读图：横转图 " + number(durations.get("real_sideways_enhanced"), 1) +
            " 秒、转正图 " + number(durations.get("real_upright_enhanced"), 1) + " 秒；"
            "原生进程采样Swap峰值 " + number(swap_gib) +
            " GiB。依据 " + link(Path(history["diagnostic_source"]), out, "保留的原始数据") + "。")
        caveat = "CPU核数、RAM、冷启动和宿主机条件都发生了变化；两份报告的耗时比值不能解释为纯粹增加CPU核数的加速比。"
        lines[4:4] = [notice, "", detail, "", caveat, ""]
    runtime = summary.get("runtime_verification") or {}
    if runtime:
        runtime_note = (f"运行时：Android aarch64 llama.cpp build {runtime.get('runtime_build', '—')}；"
            f"单独校验的二进制一致：{runtime.get('emulator_binary_matches_local', '未记录')}。"
            f"早期JSON的runtime字符串可能为空，版本与SHA-256依据 {link(Path(summary['runtime_verification_source']),out)}。")
        # Insert before the first results section so metadata is not hidden below the tables.
        index = lines.index("## 原横转包装图")
        lines[index:index] = [runtime_note, ""]
    for entry in summary["rows"]:
        run = entry["primary"]
        current = run["cases"].get(CURRENT)
        score = (current or {}).get("score") or {}
        native = (current or {}).get("native_timings") or {}
        lines.append("| " + " | ".join(map(cell, [entry["id"], entry["mode"], LABELS.get(run["phase_status"],run["phase_status"]),
            score_text(current), len(score["unexpected"]) if score else "—",
            sum(len(group["unexpected"]) for group in score.get("unexpected_subingredients",[])) if score else "—",
            number((current or {}).get("elapsed_seconds")), number((current or {}).get("first_token_seconds")),
            number(run["mac_current_comparison"].get("warm_median_seconds"))])) + " |")
    lines += ["", "Mac列只提供同一输入的另一后端参考；模拟器CPU与Mac Metal不构成手机速度对比。主配料F1忽略括号内容，不能代替完整组成准确性。", "",
        "## 加载、内存与范围覆盖", "",
        "内存为该Android原生进程的采样值，包含失败前捕获的样本。`—`表示未捕获；RSS/PSS不是手机RAM下限，系统最低可用内存也不是模型要求。", "",
        "| 模型/模式 | 已测/计划 | 加载s | 峰值RSS MiB | 峰值PSS MiB | 峰值Swap MiB | 最低系统可用MiB | 退出码 | 原始数据 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for entry in summary["rows"]:
        run = entry["primary"]
        metric = run["server_metrics"]
        lines.append("| " + " | ".join(map(cell, [entry["id"]+" / "+entry["mode"],
            f"{run['all_cases']['accuracy_measured_cases']}/{run['all_cases']['planned_cases']}",
            number(metric.get("load_seconds")),mib(metric.get("peak_sampled_rss_bytes")),mib(metric.get("peak_sampled_pss_bytes")),
            mib(metric.get("peak_sampled_swap_bytes")),mib(metric.get("minimum_system_available_bytes")),
            metric.get("process_returncode") if metric.get("process_returncode") is not None else "—",
            link(Path(run["source_file"]),out) if run["file_present"] else "尚无结果"])) + " |")
    downstream = summary["downstream"]
    if downstream["file_present"]:
        lines += ["", "## 当前图接入现有过敏原规则后的结果", "",
            "以下是模型配料列表＋同一ML Kit原始OCR交给现有Analyzer的混合检查。vision行仍使用原OCR解析警告；这不是视觉模型独立识别警告，也不表示APK已接入这些模型。",
            "参考标签：花生、大豆为“含有”；牛奶、芝麻、树坚果为“可能含有”。原始输入文件与下游快照SHA-256必须相符才显示结果。", "",
            "| 模型/模式 | 花生 | 大豆 | 牛奶 | 芝麻 | 树坚果 | 5项均相符 |", "| --- | --- | --- | --- | --- | --- | --- |"]
        names = {"CONTAINS":"含有", "MAY_CONTAIN":"可能含有", "NOT_DETECTED":"未检出", "POSSIBLE":"可能相关", "UNREADABLE":"无法读取"}
        for row in downstream["rows"]:
            values = [names.get(row["statuses"].get(key), row["statuses"].get(key, "—")) for key in ("PEANUTS","SOY","MILK","SESAME","TREE_NUTS")]
            answer = "是" if (row.get("comparison") or {}).get("all_five_match") else "否" if row["usable"] else "未分析或快照已变化"
            lines.append("| "+" | ".join(map(cell,[row["model_id"]+" / "+row["mode"],*values,answer]))+" |")
        lines += ["", "状态相符不能代替配料结构准确性；错误的长括号项也可能被规则递归命中。下游数据见 " + link(Path(downstream["source_file"]),out) + "。"]
    lines += ["", "## 合成正负例", "", "| 模型/模式 | 正例已测/计划 | 正例宏F1（完整范围） | 负例正确/已测/计划 | 格式错误 | 操作失败/跳过 | 截断 | 重复主项 |", "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for entry in summary["rows"]:
        run=entry["primary"]; pos=run["synthetic_positive_cases"]; neg=run["negative_cases"]; all_cases=run["all_cases"]
        lines.append("| " + " | ".join(map(cell,[entry["id"]+" / "+entry["mode"],
            f"{pos['accuracy_measured_cases']}/{pos['planned_cases']}",percent(pos["macro_f1_all_planned"]),
            f"{neg['negative_correct']}/{neg['negative_cases_measured']}/{neg['negative_cases_planned']}",
            all_cases["format_error_count"],all_cases["operational_error_count"],all_cases["length_limited_count"],all_cases["duplicate_count"]]))+" |")
    lines += ["", "加载失败、超时、服务退出和后续未运行均没有准确性测量，不会被记为NONE或准确率0。格式错误是已生成但未满足提取接口的任务失败。", "",
        "## 失败与诊断", ""]
    failures=[]
    for entry in summary["rows"]:
        run=entry["primary"]; diagnostic=run["diagnostics"]
        if diagnostic.get("server_error"):
            failures.append(f"- **{entry['id']} / {entry['mode']}**：{cell(diagnostic['server_error'])}")
        if run.get("snapshot_error"):
            failures.append(f"- **{entry['id']} / {entry['mode']}**：快照暂不可读：{cell(run['snapshot_error'])}")
        for case in run["cases"].values():
            if case.get("error"):
                failures.append(f"- **{entry['id']} / {entry['mode']} / {case['id']}**：{cell(case['error'])}")
        for message in diagnostic["relevant_log_lines"][-4:]:
            failures.append(f"- `{entry['id']} / {entry['mode']}` 日志：`{cell(message).replace('`','')}`")
    lines += failures or ["当前已保存结果没有报告服务或推理错误；尚未运行的路径仍未验证。"]
    lines += ["", "退出码137或进程消失本身不能证明OOM。成功完成后测试器会主动SIGTERM关闭服务，退出码143可能只是正常清理；应结合清理前状态、请求结果、日志和内存判断。", "", "## 逐案例耗时与原生推理计时", "",
        "请求耗时含整个提取；原生prefill/decode来自llama.cpp回传。生成token数不同，不能只据tok/s排名模型。"]
    for entry in summary["rows"]:
        run=entry["primary"]
        if not run["file_present"]:continue
        lines += ["",f"### {entry['id']} / {entry['mode']}","",
            "| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |",
            "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
        for row in run["cases"].values():
            timing=row.get("native_timings") or {}
            status = "该配置结束后未运行" if summary.get("configuration_intentionally_stopped") and row["status"] == "not_run" else LABELS.get(row["status"],row["status"])
            lines.append("| "+" | ".join(map(cell,[row["id"],status,percent(row["task_f1"]),
                number(row.get("elapsed_seconds")),number(row.get("first_token_seconds")),number(timing.get("prompt_per_second")),
                number(timing.get("predicted_per_second")),row.get("completion_tokens") or "—",row.get("finish_reason") or "—"]))+" |")
    secondary=[(entry,scope,run) for entry in summary["rows"] for scope,run in entry["other_scopes"].items()]
    if secondary:
        lines += ["","## 其他范围的独立运行","","pilot只验证清晰标签能否加载并返回；其成绩不会补齐full范围。","",
            "| 模型/模式 | 范围 | 状态 | 已测/计划 | 案例 | 请求s | 数据 |","| --- | --- | --- | --- | --- | --- | --- |"]
        for entry,scope,run in secondary:
            for row in run["cases"].values():
                lines.append("| "+" | ".join(map(cell,[entry["id"]+" / "+entry["mode"],scope,LABELS.get(run["phase_status"],run["phase_status"]),
                    f"{run['all_cases']['accuracy_measured_cases']}/{run['all_cases']['planned_cases']}",row["id"],number(row.get("elapsed_seconds")),link(Path(run["source_file"]),out)]))+" |")
    lines += ["","## 存储与解释边界","","| 模型 | 语言模型GB | 视觉编码器GB | 合计GB |","| --- | --- | --- | --- |"]
    for model in summary["models"]:
        lines.append(f"| {model['id']} | {model['bytes']/1e9:.3f} | {model.get('projector_bytes',0)/1e9:.3f} | {(model['bytes']+model.get('projector_bytes',0))/1e9:.3f} |")
    lines += ["",f"全部权重合计 {summary['storage']['combined_total_bytes']/1e9:.2f} GB；测试器按模型复制和清理，不要求同时保留全部权重。文件大小不等于运行内存。", "",
        "本报告不证明实体手机的速度、发热、功耗、GPU/NPU表现或最低RAM。原生adb进程也未覆盖APK生命周期、相机、UI和后台回收。", "",
        f"评分输入与参考答案见 {link(args.cases,out)}；机器摘要见 {link(args.output/'summary.json',out)}。"
        "原始回复、停止原因、命令及内存采样保留在相应JSON和服务器日志。"]
    restoration = summary.get("restoration") or {}
    if restoration.get("restored"):
        lines += ["", "## 环境恢复与校验更正", "",
            "完整测试结束后已恢复原来的1核/4096MB模拟器，测试转发已移除；三张原照片的SHA-256与测试前素材一致。",
            "首次重启前的应用偏好探测实际返回“应用包不存在”，早先保存的相同哈希是报错文本，并非XML。"
            "因此已撤销“偏好已校验保留”的结论；未重装、卸载或覆盖应用。模型测试使用独立Android原生程序。",
            f"恢复记录见 {link(args.results/'restoration.json',out)}。"]
    return "\n".join(lines)+"\n"


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results",type=Path,default=ROOT/"results"/"emulator5554")
    parser.add_argument("--output",type=Path)
    parser.add_argument("--scope",choices=["full","pilot","timing"],default="full")
    parser.add_argument("--cases",type=Path,default=ROOT/"cases.json")
    parser.add_argument("--models",type=Path,default=ROOT/"models.json")
    args=parser.parse_args()
    args.results=args.results.resolve();args.output=(args.output or args.results).resolve()
    summary=make_summary(args)
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2)+"\n")
    (args.output/"report.md").write_text(render(summary,args))
    print(json.dumps({"summary":str(args.output/"summary.json"),"report":str(args.output/"report.md"),"coverage":summary["coverage"]},ensure_ascii=False))


if __name__=="__main__":main()
