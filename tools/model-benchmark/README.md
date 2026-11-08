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
python3 tools/model-benchmark/analyze_results.py
python3 tools/model-benchmark/build_report.py
```

`models.json` pins repositories, revisions and filenames. All five language components use Q4_K_M; vision components use FP16. Some conversions are from Unsloth rather than Qwen's own account. Downloaded files total 8.22 GB and default to `/tmp/allergy-model-benchmark/models`; SHA-256 digests are captured in each model's `ready.json` and the generated summary. Existing output files are skipped; use `--rerun` to deliberately replace a run. All prompts, source images and benchmark outputs remain on this computer during inference.

`--model MODEL_ID`, `--mode text|vision|all`, `--cache PATH`, `--output PATH` and `--gpu-layers N` select a subset or a different backend. CPU runs should use a different output directory and must not be described as phone timings. Accuracy and performance-only output files are distinct; the report prefers completed performance-only measurements when available.

## Existing Gemma and rule baseline

`IngredientModelBenchmarkTest` under the app's `src/androidTest` invokes the existing `LlmIngredientReader` and deterministic rules. Build/install the test APK, put `cases.json` under the target app's external-files `benchmark/` directory, and run that class with AndroidJUnitRunner. It writes `gemma-results.json` and `rules.json` incrementally to that directory. The final imported outputs here are `results/gemma-emulator.json` and `results/rules.json`.

The first long-input attempt timed out after 120 s and remains as evidence. The final run used the same 10 texts with shorter cases first, allowing 240 s for initialization and 120 s per subsequent case; its order is in `results/gemma-input-order.json`. Gemma's public API exposes parsed lists, not raw replies or stop reasons, so duplicate and truncation statistics are not directly comparable with the Qwen raw replies. Emulator elapsed times are not handset performance measurements.

## Downstream app behavior and mobile limitations

`analyze_results.py` compiles `AnalyzeLists.java` temporarily and invokes the actual compiled app `Analyzer`. It requires a JDK and the app's compiled debug Java classes; build those with Gradle if absent. It checks that the 10 deterministic baseline reports match the emulator. For model lists, it preserves the original OCR text for allergen statements, including in image experiments. This simulates a hybrid integration; it does not mean the app already uses these Qwen models or that a vision model alone read the warning correctly.

Mac RSS is sampled every 100 ms and does not account for every shared GPU allocation. File size, Mac RSS and emulator memory are not Android RAM minima. No physical phone was available. Android deployment support, source-attributed external phone measurements, and explicitly estimated starting device classes are documented in `phone-research.md` and the final report. Switching the app from its MediaPipe `.task` reader requires runtime, model-format and prompt-template integration work.

## Artifacts

- `report.md`: readable findings, per-model results and limitations.
- `summary.json`: machine-readable aggregate, model hashes and coverage.
- `results/`: raw Qwen replies, streamed timings, server logs, baseline lists and actual-rule downstream reports.
- `cases.json`, `fixtures/`, `dataset_notes.md`: inputs, reference answers, provenance and ambiguity notes.

This harness adds diagnostic tools and an opt-in instrumentation test; it does not change production scanning behavior.
