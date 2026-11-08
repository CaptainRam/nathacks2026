# Qwen ingredient extraction benchmark

This diagnostic suite tests the five proposed Qwen checkpoints against the existing ingredient-extraction task. It includes 10 text cases and 4 direct-image cases. Only one source is a real packaging photograph; its three OCR variants and two image orientations are correlated. The other cases are synthetic controls. Results are not a general food-label accuracy estimate.

The real photo has 16 required top-level ingredients and two optional ingredients partly hidden by a fold. Main-name precision/recall uses whole names and explicit English/French aliases. Bracketed composition, extra ingredients, duplicates, output truncation and negative examples are reported separately. An operational timeout is not scored as a model answering NONE.

## Reproduce on this Mac

The recorded environment is Apple M3 Pro, 18 GiB RAM, Python 3.12.7, llama.cpp 0.4.0 build10809 (`5266f24da`). Python dependency versions are in `requirements.txt`; inference uses the installed `llama-server` command and Metal. Each server runs serially, binds only to 127.0.0.1, disables thinking and prompt caching, and uses temperature 0, seed 0 and a 512-token output cap. Text contexts are 4096 tokens; image contexts 8192 with a 2048-token image budget. These settings differ from the Android Gemma reader's 2048-token context.

Run from the repository root:

```sh
python3 tools/model-benchmark/score.py --self-check
python3 tools/model-benchmark/download_models.py
python3 tools/model-benchmark/run_benchmark.py
python3 tools/model-benchmark/run_benchmark.py --performance-only --note 'No concurrent model inference'
python3 tools/model-benchmark/compile_analysis.py
python3 tools/model-benchmark/analyze_results.py --app-classes /tmp/allergy-emulator-analysis/classes
python3 tools/model-benchmark/build_report.py
```

`models.json` pins repositories, revisions and filenames. All five language components use Q4_K_M; vision components use FP16. Some conversions are from Unsloth rather than Qwen's own account. Downloaded files total 8.22 GB and default to `/tmp/allergy-model-benchmark/models`; SHA-256 digests are captured in each model's `ready.json` and the generated summary. Existing output files are skipped; use `--rerun` to deliberately replace a run. All prompts, source images and benchmark outputs remain on this computer during inference.

`--model MODEL_ID`, `--mode text|vision|all`, `--cache PATH`, `--output PATH` and `--gpu-layers N` select a subset or a different backend. CPU runs should use a different output directory and must not be described as phone timings. Accuracy and performance-only output files are distinct; the report prefers completed performance-only measurements when available.

## Existing Gemma and rule baseline

`IngredientModelBenchmarkTest` under the app's `src/androidTest` invokes the existing `LlmIngredientReader` and deterministic rules. Build/install the test APK, put `cases.json` under the target app's external-files `benchmark/` directory, and run that class with AndroidJUnitRunner. It writes `gemma-results.json` and `rules.json` incrementally to that directory. The final imported outputs here are `results/gemma-emulator.json` and `results/rules.json`.

The first long-input attempt timed out after 120 s and remains as evidence. The final run used the same 10 texts with shorter cases first, allowing 240 s for initialization and 120 s per subsequent case; its order is in `results/gemma-input-order.json`. Gemma's public API exposes parsed lists, not raw replies or stop reasons, so duplicate and truncation statistics are not directly comparable with the Qwen raw replies. Emulator elapsed times are not handset performance measurements.

## Downstream app behavior and mobile limitations

`compile_analysis.py` builds the current pure Java rule sources in an isolated temporary directory, with a source/class hash manifest. This avoids stale Gradle classes from another build without modifying app code or producing an APK. `analyze_results.py` then compiles `AnalyzeLists.java` temporarily and invokes that `Analyzer`. A JDK and Android SDK jar are required. The 10 deterministic baseline reports were checked against the emulator. For model lists, it preserves the original OCR text for allergen statements, including in image experiments. This simulates a hybrid integration; it does not mean the app already uses these Qwen models or that a vision model alone read the warning correctly.

Mac RSS is sampled every 100 ms and does not account for every shared GPU allocation. File size, Mac RSS and emulator memory are not Android RAM minima. No physical phone was available. Android deployment support, source-attributed external phone measurements, and explicitly estimated starting device classes are documented in `phone-research.md` and the final report. Switching the app from its MediaPipe `.task` reader requires runtime, model-format and prompt-template integration work.

## Artifacts

- `report.md`: readable findings, per-model results and limitations.
- `summary.json`: machine-readable aggregate, model hashes and coverage.
- `results/`: raw Qwen replies, streamed timings, server logs, baseline lists and actual-rule downstream reports.
- `cases.json`, `fixtures/`, `dataset_notes.md`: inputs, reference answers, provenance and ambiguity notes.

This harness adds diagnostic tools and an opt-in instrumentation test; it does not change production scanning behavior.

## Android emulator run

`run_emulator_benchmark.py` runs the same model inputs inside an Android ARM64 native process. It verifies `ro.kernel.qemu=1`, uses one CPU thread by default, and writes to `results/emulator5554/`. Physical-device results are separate. A matching Android `llama-server` and its shared libraries must first be copied into `/data/local/tmp/allergy-emulator-benchmark/runtime/`.

```sh
python3 tools/model-benchmark/run_emulator_benchmark.py --scope full
python3 tools/model-benchmark/build_emulator_report.py
```

Each model is deployed with checksum verification, tested serially, then its newly copied emulator weights are removed to bound disk use. Host weights remain available. A pilot can use `--model qwen3.5-0.8b --mode text --scope pilot`. Output-token limits, prompts and contexts match the Mac protocol. Default HTTP no-data timeout is 180 seconds and the request wall limit is 300 seconds; either is an operational failure, not a NONE answer. Native processes are intentionally terminated on teardown, so exit code143 alone does not establish OOM.

The emulator run measures virtual CPU and memory in the guest, including sampled RSS/PSS/swap. It does not measure handset GPU/NPU acceleration, temperature, battery life or the app's full camera/UI lifecycle. CPU and Metal numerical differences can change model outputs even with the same seed; emulator replies are scored independently. Runtime build and binary verification are recorded in `results/emulator5554/runtime.json`.

The completed comparison is under `results/emulator5554-4cpu6gb/`: 4 virtual CPUs, 6144 MB requested RAM, four native inference threads. The original 1-CPU/4096-MB diagnostic remains separately under `results/emulator5554/`. Launch the temporary resource profile first (exact launch parameters are saved in `launch.json`), copy the matching native runtime, then reproduce with:

```sh
python3 tools/model-benchmark/run_emulator_benchmark.py --scope full --threads 4 --output tools/model-benchmark/results/emulator5554-4cpu6gb/cpu/full
python3 tools/model-benchmark/compile_analysis.py
python3 tools/model-benchmark/analyze_results.py --app-classes /tmp/allergy-emulator-analysis/classes --results-dir tools/model-benchmark/results/emulator5554-4cpu6gb/cpu/full --output tools/model-benchmark/results/emulator5554-4cpu6gb/downstream-allergens.json
python3 tools/model-benchmark/build_emulator_report.py --results tools/model-benchmark/results/emulator5554-4cpu6gb
```

After this run the original 1-CPU/4096-MB live resources were restored, the three photo hashes matched, and the temporary native runtime/weights were removed from the emulator. Preference preservation was not verified: the pre-restart `run-as` probe already returned an unknown-package error, and its text had initially been hashed incorrectly. That validation was withdrawn in `restoration.json`; no app installation or removal was performed by this harness.
