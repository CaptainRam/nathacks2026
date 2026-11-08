"""Summarize saved benchmark runs without rerunning inference.

Operational failures (load errors, timeouts, absent cases) have no measured
accuracy. Format errors are measured extraction-task failures with task F1=0.
Full-group F1 is unavailable when any planned case is operationally unmeasured;
partial averages and their denominators remain explicit in summary.json.

Usage: python3 tools/model-benchmark/build_report.py
Optional paths: --results DIR --output DIR --cache DIR --cases FILE --models FILE
Only an explicit main() call writes summary.json and report.md.
"""

from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import statistics
from typing import Any
from zoneinfo import ZoneInfo

from score import parse_reply, score_case


ROOT = Path(__file__).resolve().parent
CURRENT = "real_sideways_enhanced"
SUCCESS = {"ok", "success", "passed"}
PHONE_TARGETS = {
    "qwen3.5-0.8b": "文字6–8 GB；读图12 GB及以上",
    "qwen3-1.7b": "文字8–12 GB",
    "qwen3.5-2b": "文字8–12 GB；读图12 GB及以上",
    "qwen3-4b-instruct-2507": "文字12 GB及以上",
    "qwen3-vl-2b-instruct": "读图12 GB及以上",
}


def read_json(path: Path) -> tuple[dict[str, Any] | None, str | None]:
    if not path.exists():
        return None, None
    try:
        return json.loads(path.read_text()), None
    except (OSError, json.JSONDecodeError) as error:
        return None, str(error)


def median(values: list[Any]) -> float | None:
    numbers = [v for v in values if isinstance(v, (float, int))]
    return statistics.median(numbers) if numbers else None


def case_summary(case: dict[str, Any], record: dict[str, Any] | None) -> dict[str, Any]:
    result: dict[str, Any] = {
        "id": case["id"], "source_kind": case["source_kind"],
        "expected_none": case["expected_none"], "status": "not_run",
        "quality_measured": False, "task_f1": None, "score": None,
    }
    if record is None:
        return result
    result["status"] = record.get("status", "ok" if "ingredients" in record else "unknown")
    result["error"] = record.get("error")
    result["elapsed_seconds"] = record.get("elapsed_seconds")
    if result["elapsed_seconds"] is None and isinstance(record.get("elapsed_ms"), (float, int)):
        result["elapsed_seconds"] = record["elapsed_ms"] / 1000
    result["first_token_seconds"] = record.get("first_token_seconds")
    result["finish_reason"] = record.get("finish_reason")
    result["completion_tokens"] = record.get("usage", {}).get("completion_tokens")
    result["length_limited"] = record.get("finish_reason") == "length"
    if result["status"] == "format_error":
        result.update(quality_measured=True, task_f1=0.0)
    elif result["status"] in SUCCESS:
        try:
            items = record.get("ingredients")
            if items is None:
                items = parse_reply(record.get("reply"))
            result["ingredients"] = items
            result["score"] = score_case(case, items)
            result.update(quality_measured=True, task_f1=result["score"]["f1"])
        except (ValueError, TypeError) as error:
            result.update(status="format_error", error=str(error), quality_measured=True, task_f1=0.0)
    if isinstance(record.get("analysis", {}).get("top_level_ingredients"), list):
        result["home_display_ingredients"] = record["analysis"]["top_level_ingredients"]
        result["home_display_score"] = score_case(case, result["home_display_ingredients"])
    return result


def aggregate(cases: list[dict[str, Any]], by_id: dict[str, dict[str, Any]]) -> dict[str, Any]:
    rows = [by_id[case["id"]] for case in cases]
    measured = [row for row in rows if row["quality_measured"]]
    scores = [row["score"] for row in rows if row.get("score") is not None]
    required_total = sum(len(case["expected_ingredients"]) for case in cases)
    measured_required_total = sum(len(case["expected_ingredients"]) for case, row in zip(cases, rows) if row["quality_measured"])
    matched = sum(score["matched_required"] for score in scores)
    unexpected_count = sum(len(score["unexpected"]) for score in scores)
    measured_f1 = [row["task_f1"] for row in measured]
    negatives = [row for row in rows if row["expected_none"]]
    complete = len(measured) == len(rows)
    return {
        "planned_cases": len(rows), "accuracy_measured_cases": len(measured),
        "fully_measured": complete,
        "macro_f1_all_planned": statistics.mean(measured_f1) if complete and measured_f1 else None,
        "macro_f1_measured_only": statistics.mean(measured_f1) if measured_f1 else None,
        "required_total_planned": required_total, "required_total_measured": measured_required_total,
        "matched_required": matched,
        "required_recall_all_planned": matched / required_total if complete and required_total else None,
        "required_recall_measured_only": matched / measured_required_total if measured_required_total else None,
        "precision_successful_outputs_only": matched / (matched + unexpected_count) if matched + unexpected_count else None,
        "negative_cases_planned": len(negatives),
        "negative_cases_measured": sum(row["quality_measured"] for row in negatives),
        "negative_correct": sum(bool(row.get("score", {}).get("negative_correct")) for row in negatives if row.get("score") is not None),
        "false_none_count": sum(score["false_none"] for score in scores),
        "format_error_count": sum(row["status"] == "format_error" for row in rows),
        "operational_error_count": sum(row["status"] not in {"not_run", "running", "pending", "loading"} and not row["quality_measured"] for row in rows),
        "not_run_count": sum(row["status"] == "not_run" for row in rows),
        "pending_case_count": sum(row["status"] in {"running", "pending", "loading"} for row in rows),
        "unmeasured_case_ids": [row["id"] for row in rows if not row["quality_measured"]],
        "length_limited_count": sum(row.get("length_limited", False) for row in rows),
        "finish_reason_observed_cases": sum(row.get("finish_reason") is not None for row in rows),
        "duplicate_count": sum(score["duplicate_count"] for score in scores),
        "unexpected_ingredient_count": unexpected_count,
        "unexpected_subingredient_count": sum(len(group["unexpected"]) for score in scores for group in score["unexpected_subingredients"]),
        "unexpected_subingredient_case_ids": [row["id"] for row in rows if row.get("score") and row["score"]["unexpected_subingredients"]],
    }


def summarize_file(path: Path, cases: list[dict[str, Any]]) -> dict[str, Any]:
    raw, read_error = read_json(path)
    records = {record.get("id", record.get("case_id")): record for record in (raw or {}).get("cases", [])}
    rows = {case["id"]: case_summary(case, records.get(case["id"])) for case in cases}
    positive_synthetic = [case for case in cases if case["source_kind"] == "synthetic" and not case["expected_none"]]
    negatives = [case for case in cases if case["expected_none"]]
    return {
        "source_file": str(path), "file_present": path.exists(), "read_error": read_error,
        "run_complete": bool((raw or {}).get("complete", (raw or {}).get("completed_at"))),
        "server_error": (raw or {}).get("server_error"),
        "model": (raw or {}).get("model"), "hardware": (raw or {}).get("hardware", (raw or {}).get("environment")),
        "runtime": (raw or {}).get("runtime"), "settings": (raw or {}).get("settings"),
        "raw_response_available": (raw or {}).get("raw_response_available", any("reply" in record for record in records.values())),
        "cases": rows,
        "real_variants": {case["id"]: rows[case["id"]] for case in cases if case["source_kind"] == "real"},
        "synthetic_positives": aggregate(positive_synthetic, rows),
        "negatives": aggregate(negatives, rows), "all_cases": aggregate(cases, rows),
    }


def performance(path: Path, fallback_path: Path) -> dict[str, Any]:
    preferred, error = read_json(path)
    if preferred is not None and preferred.get("server_metrics"):
        raw, chosen = preferred, path
    else:
        raw, fallback_error = read_json(fallback_path)
        raw = raw or {}
        chosen = fallback_path
        error = error or fallback_error
    metrics = raw.get("server_metrics", {})
    records = raw.get("cases", [])
    current = next((row for row in records if row.get("id") == CURRENT), {})
    repeats = raw.get("performance_repeats", [])
    return {
        "source_file": str(chosen), "preferred_timing_file": str(path),
        "read_error": error, "run_complete": raw.get("complete"),
        "server_error": raw.get("server_error"), "settings": raw.get("settings"),
        "measurement_available": bool(metrics),
        "load_seconds": metrics.get("load_seconds"),
        "ready_rss_bytes": metrics.get("ready_rss_bytes"),
        "peak_sampled_rss_bytes": metrics.get("peak_sampled_rss_bytes"),
        "system_swap_delta_bytes": metrics.get("system_swap_delta_bytes"),
        "current_first_request_seconds": current.get("elapsed_seconds"),
        "current_first_token_seconds": current.get("first_token_seconds"),
        "warm_repetitions": len(repeats),
        "warm_full_label_median_seconds": median([row.get("elapsed_seconds") for row in repeats]),
        "warm_first_token_median_seconds": median([row.get("first_token_seconds") for row in repeats]),
        "warm_completion_tokens": [row.get("usage", {}).get("completion_tokens") for row in repeats],
        "warm_length_limited_count": sum(row.get("finish_reason") == "length" for row in repeats),
        "warm_decode_tokens_per_second_median": median([row.get("timings", {}).get("predicted_per_second") for row in repeats]),
        "memory_caveat": "Mac服务器进程RSS每100ms采样；不含未计入RSS的GPU分配，也不是手机RAM要求。",
        "note": raw.get("note"),
    }


def storage(models: list[dict[str, Any]], cache: Path) -> dict[str, Any]:
    rows = []
    for model in models:
        directory = cache / model["id"]
        artifacts = [(model["file"], model["bytes"])]
        if model.get("projector"):
            artifacts.append((model["projector"], model["projector_bytes"]))
        ready, _ = read_json(directory / "ready.json")
        rows.append({
            "id": model["id"], "repo": model["repo"], "revision": model["revision"],
            "language_bytes": model["bytes"], "projector_bytes": model.get("projector_bytes", 0),
            "total_bytes": sum(size for _, size in artifacts),
            "actual_present_bytes": sum((directory / name).stat().st_size for name, _ in artifacts if (directory / name).exists()),
            "all_files_present_with_expected_size": all((directory / name).exists() and (directory / name).stat().st_size == size for name, size in artifacts),
            "download_manifest_present": ready is not None,
            "artifacts": (ready or {}).get("artifacts", []),
        })
    return {"models": rows, "expected_total_bytes": sum(row["total_bytes"] for row in rows),
            "actual_present_bytes": sum(row["actual_present_bytes"] for row in rows),
            "units": "GB=10^9 bytes；GiB=2^30 bytes；下载大小不等于运行内存。"}


def downstream(path: Path, models: list[dict[str, Any]]) -> dict[str, Any]:
    raw, error = read_json(path)
    raw = raw or {}
    sources = [("规则提取器", "rules.json"), ("Gemma（模拟器）", "gemma-emulator.json")]
    for model in models:
        sources.append((model["id"] + " text", model["id"] + "-text.json"))
        if model.get("vision"):
            sources.append((model["id"] + " vision", model["id"] + "-vision.json"))
    index = {(Path(row["source_file"]).name, row["id"]): row for row in raw.get("cases", [])}
    source_summaries = {Path(row["source_file"]).name: row for row in raw.get("summary", [])}
    current = []
    for label, source in sources:
        row = index.get((source, CURRENT), {})
        comparison = row.get("current_photo_comparison", {})
        current.append({"label": label, "source_file": source, "analysis_status": row.get("analysis_status", "not_analyzed"),
            "generation_status": row.get("generation_status"),
            "actual": {item["allergen"]: item["status"] for item in row.get("analysis", {}).get("allergens", [])},
            "all_five_match": comparison.get("all_five_match"),
            "missed_contains": comparison.get("missed_contains"),
            "warning_promoted_to_contains": comparison.get("warning_promoted_to_contains"),
            "warning_not_retained": comparison.get("warning_not_retained"),
            "source_summary": source_summaries.get(source),
        })
    return {"source_file": str(path), "file_present": path.exists(), "read_error": error,
        "generated_at": raw.get("generated_at"), "method": raw.get("method"), "notes": raw.get("notes"),
        "current_photo": current,
        "caveat": "当前实际Analyzer接收模型列表以及同一case的MLKit OCR警告；vision行是混合检查，不是模型独立识别警告的端到端结果。过敏原状态相符也不能证明配料结构或子配料准确。"}


def pct(value: float | None) -> str:
    return "—" if value is None else f"{value:.1%}"


def number(value: float | None, digits: int = 2) -> str:
    return "—" if value is None else f"{value:.{digits}f}"


def gib(value: int | None) -> str:
    return "—" if value is None else f"{value / 2**30:.2f}"


def status(row: dict[str, Any]) -> str:
    if row["status"] == "format_error":
        return "格式失败"
    if not row["quality_measured"]:
        return {"not_run": "未运行", "running": "进行中", "pending": "等待中", "loading": "加载中", "timeout": "超时，未测得", "skipped_after_timeout": "前次超时后跳过", "inference_error": "推理失败，未测得"}.get(row["status"], f"未测得：{row['status']}")
    return "截断" if row.get("length_limited") else "已输出"


def compact_case(row: dict[str, Any]) -> str:
    score = row.get("score")
    if score:
        return f"{score['matched_required']}/{score['required_total']}；F1 {pct(score['f1'])}" + ("；截断" if row.get("length_limited") else "")
    return status(row)


def table(headers: list[str], rows: list[list[Any]]) -> list[str]:
    def cell(value: Any) -> str:
        return str(value).replace("|", "\\|").replace("\n", " ")
    return ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |",
            *["| " + " | ".join(cell(value) for value in row) + " |" for row in rows], ""]


def render(summary: dict[str, Any]) -> str:
    models = summary["models"]
    baseline_rows = [("规则提取器", summary["baselines"]["rules"]), ("Gemma（模拟器）", summary["baselines"]["gemma"])]
    all_text = baseline_rows + [(model["id"], model["text"]) for model in models]
    lines = ["# 本地配料模型对照测试", "", f"生成时间：{summary['generated_at']}。", "",
        "测试包含1张真实包装照片的3种OCR结果、2张合成图片及5个合成文字/负例，共10个文字案例；直接读图使用4个图片案例。真实图的折边处2项配料作为可选项，另外16项参与召回率。三种真实输入来自同一张照片，不能视为独立样本或普遍准确率。", "",
        "所有主配料指标按完整名称与英法别名匹配，忽略括号内容；子配料缺失、额外子配料、重复及截断另列。格式错误计为提取任务失败；超时、加载失败和未运行表示准确性未测得。", "",
        "## 当前模拟器这张图", "",
        "下表使用原来的横转照片OCR。P/R/F1为主配料精确率/召回率/F1；空输出的数学精确率约定不代表识别成功。", ""]
    rows = []
    for name, run in all_text:
        row = run["cases"][CURRENT]
        score = row.get("score") or {}
        displayed_precision = "—（空输出）" if score and not row.get("ingredients") else pct(score.get("precision"))
        rows.append([name, f"{score.get('matched_required', '—')}/16", displayed_precision, pct(score.get("recall")), pct(row.get("task_f1")),
            pct(score.get("subingredient_recall")), len(score.get("unexpected", [])) if score else "—",
            sum(len(group["unexpected"]) for group in score.get("unexpected_subingredients", [])) if score else "—",
            score.get("duplicate_count", "—"), status(row)])
    lines += table(["模型/路径", "必需项", "P", "R", "F1", "子配料R", "多余主项", "额外子项", "重复", "状态"], rows)
    home = summary["baselines"]["rules"]["cases"][CURRENT]
    if home.get("home_display_score"):
        score = home["home_display_score"]
        lines += [f"首页实际显示的回退结果是 `{', '.join(home['home_display_ingredients'])}`；按同一标准匹配{score['matched_required']}/16，多余项{len(score['unexpected'])}，F1 {pct(score['f1'])}。这与规则提取器本身返回空列表是两个阶段。", ""]
    for name, run in all_text:
        row = run["cases"][CURRENT]
        score = row.get("score")
        if score and (score["missing"] or score["unexpected"] or score["unexpected_subingredients"]):
            extras = "；".join(score["unexpected"]) or "无"
            missing = "、".join(score["missing"]) or "无"
            child_extras = sum(len(group["unexpected"]) for group in score["unexpected_subingredients"])
            lines += [f"- **{name}**：漏项：{missing}。多余主项：{extras}。额外子配料{child_extras}项；完整细节见原始JSON。"]
    lines += ["", "## 同一真实照片的三种OCR输入", ""]
    real_ids = [case["id"] for case in summary["case_metadata"] if case["source_kind"] == "real"]
    lines += table(["模型/路径", *real_ids], [[name, *[compact_case(run["cases"][case_id]) for case_id in real_ids]] for name, run in all_text])
    if summary["downstream"]["file_present"]:
        lines += ["## 接入当前过敏原规则后的结果", "",
            "人工标签参考：花生、大豆应为“含有”；牛奶、芝麻、树坚果应为“可能含有”。这里调用项目现有Analyzer，输入模型配料列表，并继续提供同一案例的ML Kit原始OCR供警告解析。**vision行是此混合流程，未验证视觉模型独立读出警告的能力。**", "",
            "状态相符不能代替配料结构准确性：一个包含大量文字的错误括号项也可能被递归匹配到花生/大豆。反之，把警告中的牛奶等写成配料会把“可能含有”升级为“含有”。", ""]
        words = {"CONTAINS": "含有", "MAY_CONTAIN": "可能含有", "POSSIBLE": "线索不确定", "NOT_DETECTED": "未检出", "UNREADABLE": "不可读"}
        allergen_keys = ["PEANUTS", "SOY", "MILK", "SESAME", "TREE_NUTS"]
        rows = []
        for row in summary["downstream"]["current_photo"]:
            available = row["analysis_status"] == "ok"
            rows.append([row["label"], *[words.get(row["actual"].get(key), row["actual"].get(key, "未分析")) for key in allergen_keys],
                ("是" if row["all_five_match"] else "否") if available else "未测得"])
        lines += table(["路径（原横转图）", "花生", "大豆", "牛奶", "芝麻", "树坚果", "5项状态均相符"], rows)
        lines += [f"逐项匹配及声明来源见 `results/downstream-allergens.json`（生成时间{summary['downstream']['generated_at']}）。该检查不覆盖未测试食物或过敏原的安全性。", ""]
    lines += ["## 合成案例与失败统计", "", "合成正例共5题，负例共2题。全5题F1在有任何操作性失败或缺失时显示未完整测量；机器摘要另保存已测部分的均值与分母。截断输出即使能解析也单独计数。", ""]
    rows = []
    for name, run in all_text:
        positive, negative, total = run["synthetic_positives"], run["negatives"], run["all_cases"]
        f1 = pct(positive["macro_f1_all_planned"]) if positive["fully_measured"] else f"未完整测量({positive['accuracy_measured_cases']}/5)"
        recorded_truncations = total["length_limited_count"] if total["finish_reason_observed_cases"] else "未记录"
        if name == "规则提取器":
            recorded_truncations = "不适用"
        rows.append([name, f1, f"{negative['negative_correct']}/2（已测{negative['accuracy_measured_cases']}）", total["false_none_count"], total["format_error_count"], total["operational_error_count"], total["not_run_count"], total["pending_case_count"], recorded_truncations, total["duplicate_count"], total["unexpected_subingredient_count"]])
    lines += table(["模型/路径", "合成正例宏F1", "负例正确", "正例空返回", "格式错", "操作失败", "未运行", "进行中", "截断", "重复", "额外子项"], rows)
    lines += ["Gemma模拟器接口只保存应用解析后的列表，原始生成与结束原因不可用；应用已删除完全相同的重复行，因此其重复/截断统计不能与保留原始回复的Qwen直接比较。", ""]
    lines += ["## 模型直接读图", "", "此任务把图片交给模型，与给定ML Kit文字的纠错任务分开统计；耗时不包含另一次ML Kit运行。两种方向仍是同一张真实照片，另外两张是合成控制。", ""]
    image_ids = [case["id"] for case in summary["case_metadata"] if case.get("evaluate_vision")]
    lines += table(["模型", *image_ids], [[model["id"], *[compact_case(model["vision"]["cases"][case_id]) for case_id in image_ids]] for model in models if model["vision"]])
    lines += ["## 本机性能", "", "只记录本机macOS/ARM64结果。加载时间为启动服务器到就绪；首请求和重复请求为同一原图/原OCR的完整提取。优先使用单独的timing结果，避免并行下载/测试影响。", "",
        "RSS每100ms采样，未覆盖所有GPU共享内存；它不是Android内存下限。系统换页活动也会干扰本机测量。Gemma模拟器的延迟和Mac的Qwen延迟不作横向速度比较。", ""]
    rows = []
    for model in models:
        for mode in ("text", "vision"):
            if mode == "vision" and model["vision"] is None:
                continue
            perf = model["performance"][mode]
            rows.append([model["id"], mode, number(perf["load_seconds"]), gib(perf["peak_sampled_rss_bytes"]), number(perf["current_first_request_seconds"]), number(perf["warm_full_label_median_seconds"]), number(perf["warm_first_token_median_seconds"]), f"{perf['warm_length_limited_count']}/{perf['warm_repetitions']}", Path(perf["source_file"]).name])
    lines += table(["模型", "任务", "加载s", "峰值RSS GiB", "首请求s", "重复完整提取中位s", "重复首字中位s", "重复截断", "数据文件"], rows)
    gemma = summary["baselines"]["gemma"]
    if gemma["file_present"]:
        environment = gemma.get("hardware") or {}
        lines += ["### Gemma模拟器对照", "",
            f"环境：{environment.get('model', '未记录')}，总内存{gib(environment.get('total_memory_bytes'))} GiB，应用可用处理器数{environment.get('available_processors', '未记录')}。以下仅为该模拟器回调耗时，首次成功请求可能包含懒加载；不用于推算手机速度。", ""]
        timing_rows = []
        for row in gemma["cases"].values():
            if row.get("elapsed_seconds") is not None:
                timing_rows.append([row["id"], status(row), number(row["elapsed_seconds"])])
        lines += table(["案例", "状态", "模拟器耗时s"], timing_rows)
    lines += ["## 模型存储与手机要求", "", "**实体手机性能尚未测量。** 当前连接信息只确认Android模拟器；没有据此测得真实手机的速度、发热、功耗或RAM下限。", ""]
    rows = []
    for model in summary["storage"]["models"]:
        rows.append([model["id"], gib(model["language_bytes"]), gib(model["projector_bytes"]), gib(model["total_bytes"]), "已核对大小" if model["all_files_present_with_expected_size"] else "文件待核对", PHONE_TARGETS[model["id"]]])
    lines += table(["模型", "语言权重GiB", "视觉编码器GiB", "合计GiB", "本机文件", "手机总RAM起测目标（估计）"], rows)
    total = summary["storage"]["expected_total_bytes"]
    lines += [f"五款模型语言权重及匹配视觉编码器合计 **{gib(total)} GiB（{total / 1e9:.2f} GB）**。以上是所下载Q4_K_M及FP16视觉编码器的大小；不含运行时、KV缓存和图片缓冲。", "",
        "手机RAM档位是工程起测建议，既不是实测最低要求，也不保证特定手机成功。可用内存、SoC、驱动、量化格式、上下文与图像token预算都需要固定后实测。Android端需迁移/适配当前MediaPipe `.task` 加载层；GGUF读图还需要匹配的视觉编码器和多模态原生接口。详见[手机部署证据](phone-research.md)。", "",
        "## 可复查数据", "", "- [机器摘要](summary.json)、[案例与参考答案](cases.json)、[评分器](score.py)、[数据限制](dataset_notes.md)。", "- 原始回复、每题耗时、截断标志和运行命令保留在 `results/`。权重版本及SHA-256在下载缓存的 `ready.json` 中，摘要也收录其清单。", "",
        "本次结果用于选择下一轮候选。单张真实图片、固定提示词和固定量化版本的结果不能证明通用食品标签准确率，也不能证明已完成手机端部署。", ""]
    failures = []
    for name, run in all_text + [(model["id"] + " vision", model["vision"]) for model in models if model["vision"]]:
        if run.get("server_error") or run.get("read_error"):
            failures.append(f"- {name}：{run.get('server_error') or run.get('read_error')}")
        for row in run["cases"].values():
            if not row["quality_measured"] and row["status"] not in {"not_run", "running", "pending", "loading"}:
                failures.append(f"- {name} / {row['id']}：{row.get('error') or row['status']}")
    if failures:
        lines += ["## 未测得项目与操作性失败", "", *failures, ""]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", type=Path, default=ROOT / "results")
    parser.add_argument("--output", type=Path, default=ROOT)
    parser.add_argument("--cache", type=Path, default=Path("/tmp/allergy-model-benchmark/models"))
    parser.add_argument("--cases", type=Path, default=ROOT / "cases.json")
    parser.add_argument("--models", type=Path, default=ROOT / "models.json")
    args = parser.parse_args()
    corpus = json.loads(args.cases.read_text())
    cases = corpus["cases"]
    models = json.loads(args.models.read_text())["models"]
    vision_cases = [case for case in cases if case.get("evaluate_vision")]
    gemma_path = args.results / "gemma-emulator.json"
    if not gemma_path.exists():
        for candidate in ("gemma-results.json", "gemma.json"):
            if (args.results / candidate).exists():
                gemma_path = args.results / candidate
                break
    summary: dict[str, Any] = {
        "schema_version": 1,
        "generated_at": datetime.now(ZoneInfo("America/Edmonton")).isoformat(timespec="seconds"),
        "scope": {"distinct_real_photos": 1, "text_cases": len(cases), "vision_cases": len(vision_cases),
            "accuracy_caveat": "相关真实图变体及合成控制，不构成普遍准确率；主配料F1忽略括号内容，须同时查看子配料与幻觉记录。",
            "operational_failure_policy": "未运行/超时/加载错误不计准确率0；完整组指标缺失，已测部分单列分母。格式错误是已测任务失败。"},
        "case_metadata": [{key: case[key] for key in ("id", "source_kind", "source_group", "expected_none", "evaluate_vision") if key in case} for case in cases],
        "baselines": {"rules": summarize_file(args.results / "rules.json", cases), "gemma": summarize_file(gemma_path, cases)},
        "models": [], "storage": storage(models, args.cache),
        "downstream": downstream(args.results / "downstream-allergens.json", models),
        "phone": {"measurement_status": "not_measured", "physical_phone_measurements": None,
            "engineering_targets_are_estimates": True, "engineering_total_ram_targets": PHONE_TARGETS,
            "evidence_file": str(ROOT / "phone-research.md"),
            "caveat": "Mac RSS/吞吐和模拟器延迟不能换算为真实手机要求。"},
    }
    for model in models:
        model_id = model["id"]
        summary["models"].append({"id": model_id,
            "text": summarize_file(args.results / f"{model_id}-text.json", cases),
            "vision": summarize_file(args.results / f"{model_id}-vision.json", vision_cases) if model.get("vision") else None,
            "performance": {mode: performance(args.results / f"{model_id}-{mode}-timing.json", args.results / f"{model_id}-{mode}.json") for mode in ("text", "vision") if mode == "text" or model.get("vision")},
        })
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    (args.output / "report.md").write_text(render(summary))
    print(json.dumps({"summary": str(args.output / "summary.json"), "report": str(args.output / "report.md"),
        "text_results_present": sum(model["text"]["file_present"] for model in summary["models"]),
        "text_runs_complete": sum(model["text"]["run_complete"] for model in summary["models"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
