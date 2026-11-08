#!/usr/bin/env python3
"""Run stored ingredient lists through the app's existing Java allergen rules.

This is a downstream integration simulation; the home UI does not currently use
the model output. Both text and vision model lists are combined with the case's
original OCR text, as Analyzer.analyse(ingredients, rawText, selected) requires.
Stored output items are passed unchanged. Empty lists intentionally exercise
the app's existing raw-text fallback. Missing/running/error cases are recorded
without a fabricated analysis. Only the five specified current-photo statuses
have a ground-truth comparison; other case analyses remain descriptive.
"""

from __future__ import annotations

import argparse
import base64
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from typing import Any


BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[1]
EXPECTED_PHOTO = {
    "PEANUTS": "CONTAINS",
    "SOY": "CONTAINS",
    "MILK": "MAY_CONTAIN",
    "SESAME": "MAY_CONTAIN",
    "TREE_NUTS": "MAY_CONTAIN",
}


def b64(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def unb64(value: str) -> str:
    return base64.b64decode(value).decode("utf-8")


def find_tool(name: str, explicit: str | None) -> str:
    if explicit:
        return explicit
    candidates = [
        Path(os.environ.get("JAVA_HOME", "/nonexistent")) / "bin" / name,
        Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin") / name,
    ]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    resolved = shutil.which(name)
    if resolved:
        return resolved
    raise RuntimeError(f"Cannot find {name}; set JAVA_HOME or pass --{name}")


def find_android_jar(explicit: str | None) -> Path | None:
    if explicit:
        jar = Path(explicit).expanduser().resolve()
        if not jar.is_file():
            raise RuntimeError(f"Android jar does not exist: {jar}")
        return jar
    sdk_roots = [
        Path(os.environ.get("ANDROID_HOME", "/nonexistent")),
        Path(os.environ.get("ANDROID_SDK_ROOT", "/nonexistent")),
        Path.home() / "Library/Android/sdk",
    ]
    local_properties = ROOT / "AllergyCheck/local.properties"
    if local_properties.is_file():
        for line in local_properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk_roots.insert(0, Path(line.split("=", 1)[1]))
    for sdk in sdk_roots:
        jars = sorted((sdk / "platforms").glob("*/android.jar"), reverse=True)
        if jars:
            return jars[0]
    return None


def java_analyses(requests: list[dict[str, Any]], args: argparse.Namespace) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    app_classes = args.app_classes.resolve()
    analyzer_class = app_classes / "com/example/allergycheck/analysis/Analyzer.class"
    if not analyzer_class.is_file():
        raise RuntimeError(f"Compiled Analyzer not found: {analyzer_class}. Build the app first.")
    java = find_tool("java", args.java)
    javac = find_tool("javac", args.javac)
    android_jar = find_android_jar(args.android_jar)
    classpath = [str(app_classes)]
    if android_jar:
        classpath.append(str(android_jar))
    wire = "".join(
        "\t".join([b64(request["key"]), b64(request["ocr_text"]), str(len(request["ingredients"])),
                   *(b64(item) for item in request["ingredients"])]) + "\n"
        for request in requests
    )
    version = subprocess.run([java, "-version"], capture_output=True, text=True, check=True)
    with tempfile.TemporaryDirectory(prefix="allergy-downstream-") as directory:
        subprocess.run([javac, "--release", "11", "-encoding", "UTF-8", "-cp", os.pathsep.join(classpath),
                        "-d", directory, str(BASE / "AnalyzeLists.java")], capture_output=True, text=True, check=True)
        execution = subprocess.run([java, "-cp", os.pathsep.join([directory, *classpath]), "AnalyzeLists"],
                                   input=wire, capture_output=True, text=True, check=True)
    analyses: dict[str, dict[str, Any]] = {}
    ended: set[str] = set()
    for line in execution.stdout.splitlines():
        fields = line.split("\t")
        kind, key = fields[0], unb64(fields[1])
        entry = analyses.setdefault(key, {"parsed_items": [], "statements": [], "allergens": []})
        if kind == "REPORT":
            entry.update(overall=fields[2], low_confidence=fields[3] == "true",
                         ingredient_heading_found=fields[4] == "true")
        elif kind == "INGREDIENT":
            entry["parsed_items"].append({"text": unb64(fields[3]), "depth": int(fields[2])})
        elif kind == "STATEMENT":
            entry["statements"].append({"kind": fields[2], "text": unb64(fields[3])})
        elif kind == "ALLERGEN":
            entry["allergens"].append({"allergen": fields[2], "status": fields[3], "findings": []})
        elif kind == "FINDING":
            allergen = next(item for item in entry["allergens"] if item["allergen"] == fields[2])
            allergen["findings"].append({"level": fields[3], "source": fields[4], "reason": fields[5],
                                        "matched_term": unb64(fields[6]), "evidence": unb64(fields[7])})
        elif kind == "ERROR":
            entry["error"] = unb64(fields[2])
        elif kind == "END":
            ended.add(key)
        else:
            raise RuntimeError(f"Unexpected Java bridge record: {kind}")
    for request in requests:
        key = request["key"]
        if key not in ended:
            raise RuntimeError(f"Java analysis did not complete: {key}")
        entry = analyses[key]
        entry["top_level_ingredients"] = [item["text"] for item in entry["parsed_items"] if item["depth"] == 0]
    metadata = {"java": java, "java_version": (version.stderr or version.stdout).strip(),
                "app_classes": str(app_classes), "android_jar": str(android_jar) if android_jar else None,
                "analyzer_class_sha256": hashlib.sha256(analyzer_class.read_bytes()).hexdigest(),
                "uses_existing_compiled_app_rules": True, "android_or_emulator_invoked": False}
    return analyses, metadata


def status_map(analysis: dict[str, Any]) -> dict[str, str]:
    return {item["allergen"]: item["status"] for item in analysis.get("allergens", [])}


def compare_photo(analysis: dict[str, Any]) -> dict[str, Any]:
    actual = status_map(analysis)
    comparisons = [{"allergen": allergen, "expected": expected, "actual": actual.get(allergen),
                    "matches": actual.get(allergen) == expected} for allergen, expected in EXPECTED_PHOTO.items()]
    return {
        "expected": EXPECTED_PHOTO,
        "comparisons": comparisons,
        "matching_statuses": sum(item["matches"] for item in comparisons),
        "compared_statuses": len(comparisons),
        "all_five_match": all(item["matches"] for item in comparisons),
        "missed_contains": [item["allergen"] for item in comparisons
                            if item["expected"] == "CONTAINS" and not item["matches"]],
        "warning_promoted_to_contains": [item["allergen"] for item in comparisons
                                         if item["expected"] == "MAY_CONTAIN" and item["actual"] == "CONTAINS"],
        "warning_not_retained": [item["allergen"] for item in comparisons
                                  if item["expected"] == "MAY_CONTAIN"
                                  and item["actual"] not in {"MAY_CONTAIN", "CONTAINS"}],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=BASE / "cases.json")
    parser.add_argument("--results-dir", type=Path, default=BASE / "results")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--app-classes", type=Path, default=ROOT / "AllergyCheck/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes")
    parser.add_argument("--java")
    parser.add_argument("--javac")
    parser.add_argument("--android-jar")
    args = parser.parse_args()
    dataset = json.loads(args.cases.read_text())
    cases = {case["id"]: case for case in dataset["cases"]}
    inputs = sorted({*args.results_dir.glob("qwen*.json"), *args.results_dir.glob("gemma*.json"),
                     *args.results_dir.glob("rules.json")})
    records: list[dict[str, Any]] = []
    requests: list[dict[str, Any]] = []
    input_snapshots: list[dict[str, Any]] = []
    for path in inputs:
        raw = path.read_bytes()
        try:
            result = json.loads(raw)
        except json.JSONDecodeError as error:
            input_snapshots.append({"file": path.name, "read_error": str(error)})
            continue
        result_cases = result.get("cases", [])
        if not result_cases:
            continue
        input_snapshots.append({"file": path.name, "sha256": hashlib.sha256(raw).hexdigest(),
                                "case_count": len(result_cases), "source_complete": result.get("complete", bool(result.get("completed_at")))})
        for index, output in enumerate(result_cases):
            case_id = output.get("id")
            case = cases.get(case_id)
            record: dict[str, Any] = {"source_file": path.name, "model": result.get("model"),
                                      "mode": result.get("mode", "rules" if path.name == "rules.json" else "text"),
                                      "id": case_id, "source_kind": output.get("source_kind"),
                                      "source_group": output.get("source_group"),
                                      "generation_status": output.get("status"),
                                      "finish_reason": output.get("finish_reason")}
            records.append(record)
            if case is None:
                record.update(analysis_status="not_analyzed", reason="No matching dataset case")
                continue
            record["source_group"] = case.get("source_group")
            record["source_kind"] = case.get("source_kind")
            ingredients = output.get("ingredients")
            if output.get("status") != "ok" or not isinstance(ingredients, list) or not all(isinstance(item, str) for item in ingredients):
                record.update(analysis_status="not_analyzed", reason="No successful stored ingredient list",
                              generation_error=output.get("error"))
                continue
            key = f"{path.name}:{index}:{case_id}"
            record["request_key"] = key
            record["input_ingredients"] = ingredients
            record["empty_list_uses_raw_text_fallback"] = not ingredients
            requests.append({"key": key, "ingredients": ingredients, "ocr_text": case["ocr_text"]})
            if path.name == "rules.json":
                record["baseline_from_emulator"] = output.get("analysis")
                record["home_shows_no_ingredients_message"] = output.get("home_shows_no_ingredients_message")
    analyses, runtime = java_analyses(requests, args)
    for record in records:
        key = record.pop("request_key", None)
        if key is None:
            continue
        analysis = analyses[key]
        record["analysis"] = analysis
        if "error" in analysis:
            record["analysis_status"] = "error"
            continue
        record["analysis_status"] = "ok"
        if record["source_group"] == "current_packaging_photo":
            record["current_photo_comparison"] = compare_photo(analysis)
        stored = record.get("baseline_from_emulator")
        if stored:
            record["baseline_statuses_reproduced"] = status_map(stored) == status_map(analysis)
            record["baseline_low_confidence_reproduced"] = stored.get("low_confidence") == analysis.get("low_confidence")
    summary = []
    for snapshot in input_snapshots:
        selected = [row for row in records if row["source_file"] == snapshot["file"]]
        scored = [row for row in selected if "current_photo_comparison" in row]
        summary.append({"source_file": snapshot["file"], "existing_cases": len(selected),
                        "analysis_status_counts": dict(Counter(row["analysis_status"] for row in selected)),
                        "current_photo_variants_compared": len(scored),
                        "all_five_match_variants": sum(row["current_photo_comparison"]["all_five_match"] for row in scored),
                        "expected_status_comparisons": len(scored) * len(EXPECTED_PHOTO),
                        "matching_expected_statuses": sum(row["current_photo_comparison"]["matching_statuses"] for row in scored),
                        "missed_contains_count": sum(len(row["current_photo_comparison"]["missed_contains"]) for row in scored),
                        "warning_promoted_to_contains_count": sum(len(row["current_photo_comparison"]["warning_promoted_to_contains"]) for row in scored),
                        "warning_not_retained_count": sum(len(row["current_photo_comparison"]["warning_not_retained"]) for row in scored),
                        "variants_missing_peanut_or_soy_contains": sum(bool(row["current_photo_comparison"]["missed_contains"]) for row in scored),
                        "variants_promoting_precaution_to_contains": sum(bool(row["current_photo_comparison"]["warning_promoted_to_contains"]) for row in scored)})
    report = {"schema_version": 1, "generated_at": datetime.now(timezone.utc).isoformat(),
              "method": "Existing compiled Analyzer.analyse(storedIngredients, datasetCase.ocr_text, EnumSet.allOf(Allergen.class))",
              "notes": ["Integration simulation only; the app home flow currently does not call the model.",
                        "Vision lists are combined with the original OCR text to preserve existing allergen statements; this is a hybrid downstream check, not standalone vision-only analysis.",
                        "Current-photo expectations cover Peanut/Soy CONTAINS and Milk/Sesame/Tree nuts MAY_CONTAIN only.",
                        "Three real-photo OCR variants are correlated views of one photograph; counts are diagnostic and are not an independent-sample accuracy estimate.",
                        "Missing, running, failed and timed-out generations are not given fabricated allergen results.",
                        "Empty successful lists exercise the app's raw-text fallback exactly as implemented.",
                        "Warning ingredients accidentally returned by a model can be promoted to CONTAINS; these errors are reported explicitly."],
              "runtime": runtime, "input_snapshots": input_snapshots, "summary": summary, "cases": records}
    output = args.output or args.results_dir / "downstream-allergens.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(output)
    print(json.dumps({"output": str(output), "analyzed_cases": len(analyses), "summary": summary}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
