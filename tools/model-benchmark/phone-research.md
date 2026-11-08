# Android deployment evidence for the five Qwen candidates

Checked 2026-11-07 (America/Edmonton). This file records source research, not measurements made on the user's phone. Links are to model publishers, runtime maintainers, or the converter's own model cards. LiteRT Community benchmark rows are firsthand community reports; they are not Qwen/Google/Samsung-validated hardware requirements.

## What the project currently supports

The app uses MediaPipe Tasks GenAI **0.10.24**, `.task` discovery, Gemma chat markers, a 2,048-token total budget and 512-token reply reserve. The proposed GGUF and `.litertlm` files require a runtime integration change; renaming a file to `.task` does not convert it. Gemma is called only by OCR Test; the home scanner uses deterministic extraction. These are repository findings, not external compatibility claims.

## Deployment paths

**llama.cpp:** The maintainers document native Android bindings, Termux execution, and NDK cross-compilation for `arm64-v8a` (example API 28). Their Android guide recommends beginning with a modest context such as 4,096 because a larger cache can exhaust memory. The project's own minimum Android API remains 34 unless intentionally changed. [Android guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)

Current upstream source contains [Qwen3 1.7B/4B execution](https://github.com/ggml-org/llama.cpp/blob/master/src/models/qwen3.cpp) and [Qwen3.5 0.8B/2B execution](https://github.com/ggml-org/llama.cpp/blob/master/src/models/qwen35.cpp). This establishes architecture support, not end-to-end success on every phone/backend/quantization.

For image inputs, llama.cpp uses `libmtmd` and a matching **language GGUF plus vision `mmproj` GGUF**, through supported CLI/server tools or a native integration. The Android text-demo binding alone is not evidence that its UI already handles image tensors. [Multimodal guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md)

The OpenCL backend primarily targets Adreno. Its verified list includes Adreno 750/830/840 (Snapdragon 8 Gen 3/8 Elite/8 Elite Gen 5) and 810 (7s Gen 3); older phone drivers can be incompatible. CPU fallback is a separate path. Do not treat an advertised GPU/NPU TOPS number as verified support for one of these Qwen architectures. [OpenCL guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md)

**LiteRT-LM:** Google's current Kotlin API supports CPU/GPU/NPU backends and image content with a compatible multimodal bundle. Its format/API differs from the project's MediaPipe reader. Available compiled artifacts and required runtime versions must be selected together. [Android API](https://developers.google.com/edge/litert-lm/android), [runtime](https://github.com/google-ai-edge/LiteRT-LM)

## Model-specific artifacts and limitations

| Candidate | Local text path | Image path / important distinction |
| --- | --- | --- |
| Qwen3-1.7B | Qwen publishes GGUF; LiteRT Community has a 932 MB dynamic INT4 `.litertlm` requiring at least LiteRT-LM 0.18. | Text model. Explicitly disable thinking when comparing the OCR extraction task; upstream enables it by default. |
| Qwen3-4B-Instruct-2507 | Qwen documents llama.cpp use; LiteRT Community offers mixed INT4, 2,535.88 MiB, 2,048 context. | Text-only Instruct checkpoint. Its native long context is not the same as the compiled artifact's context. |
| Qwen3.5-0.8B | Upstream checkpoint is multimodal; a text-only local load may omit its vision tower. LiteRT has old INT8 text (~965 MB, runtime >=0.15) and newer GPU-specific/version-specific files. | LiteRT's `Qwen3.5-0.8B-VL_int8.litertlm` is ~1.304 GB and uses a fixed 512x512 vision export. A text-only test does not evaluate its ability to read the photograph. |
| Qwen3.5-2B | LiteRT text INT8 is 2,119,744,416 bytes (~2.12 GB), runtime >=0.15. Current llama.cpp has the hybrid decoder. | LiteRT's VL INT8 file is ~3.152 GB, fixed 512x512 image. The original checkpoint's dynamic image handling is not preserved verbatim by that static export. |
| Qwen3-VL-2B-Instruct | Qwen publishes official GGUF language components in FP16/Q8_0/Q4_K_M. | Official matching vision encoders are FP16/Q8_0. Need both components for an image benchmark. No ready LiteRT Community artifact for this exact checkpoint was verified in this search; this is not proof that conversion is impossible. |

Sources: [Qwen3-1.7B upstream](https://huggingface.co/Qwen/Qwen3-1.7B), [its official GGUF](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF), [its LiteRT conversion](https://huggingface.co/litert-community/Qwen3-1.7B); [4B upstream](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507), [4B LiteRT conversion](https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507); [0.8B upstream](https://huggingface.co/Qwen/Qwen3.5-0.8B), [0.8B LiteRT conversion](https://huggingface.co/litert-community/Qwen3.5-0.8B); [2B upstream](https://huggingface.co/Qwen/Qwen3.5-2B), [2B LiteRT conversion](https://huggingface.co/litert-community/Qwen3.5-2B); [VL official GGUF](https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF).

The 0.8B catalog's recent ~791 MB artifact requires **0.20/main**; its ~888 MB GPU package needs **0.19**. Qwen3.5 cards report recurrent state leaking between independent conversations on a reused Engine in released versions through 0.19.1; their workaround is a fresh Engine per conversation.

## Physical-phone evidence: scale indicators, not our benchmark results

| Artifact / reported setup | Reported Android results | Interpretation |
| --- | --- | --- |
| Qwen3-1.7B dynamic INT4, LiteRT-LM 0.19, 1,024-token prefill | Pixel 8a: ~19.5 decode tok/s GPU vs ~6.7 CPU; Galaxy S26: ~38.8 GPU vs ~17.7 CPU. | Phone/accelerator differences materially change latency. Same card's newer INT8 CPU export had roughly 3.2–3.5 GiB process peak with a 1,355-token prompt/256 output/2,048 cache. |
| Qwen3-4B mixed INT4, Galaxy S26, LiteRT-LM 0.16, 203-token prompt | GPU ~19.9 decode tok/s, ~1.53 GB RSS, 16–21 s initialization; CPU ~8.4–9.2 tok/s, ~4.6–6.1 GB RSS. | Cold start and CPU fallback can dominate deployability even if warm GPU decoding is usable. |
| Qwen3.5-0.8B November 5 GPU-composite file, Galaxy S26, LiteRT-LM 0.19, 1,024/256 tokens | ~55.1 decode tok/s, ~1.0 GB reported peak, ~1.7–2.4 s engine creation. | These are for this exact text artifact; not the older INT8 file or the vision file. |
| Qwen3.5-2B text INT8, Galaxy S26, LiteRT-LM 0.16, 256/64 tokens | CPU ~22.9 decode tok/s, ~3.16 GB peak, ~19 s initialization; GPU ~19.2 tok/s, ~4.22 GB peak, ~93 s initialization. | GPU is not automatically the preferable backend. These older converted-file results are only approximate planning evidence. |
| Qwen3-VL-2B image extraction | No sufficiently matched primary physical-phone benchmark verified here. | Do not invent phone tok/s or RAM minima from its 2B label or from a desktop run. |

The numeric rows above come from each corresponding LiteRT conversion card linked above. Their prompt sizes, quantization, versions, memory metrics and initialization states differ; do not rank them directly. None measures this application's full OCR + extraction + UI workflow.

## Phone requirements that can honestly be stated

There is no verified universal minimum phone specification for all five. A successful Mac run proves output behavior for that artifact/backend/input, not mobile RAM, thermals, battery use or throughput. An Android emulator can check ABI/API integration and lifecycle behavior; it cannot supply realistic handset accelerator, memory-bandwidth or sustained thermal measurements.

For planning, use these **engineering starting targets, not measured minimums**: 6–8 GB total handset RAM for a quantized sub-1B text candidate; 8–12 GB for 1.7–2B text; 12 GB or more for 4B text or an image pipeline. These targets leave room for Android, camera/bitmap buffers, OCR and the inference engine, but do not guarantee success. Exact required free RAM must be measured after loading the chosen quantization, context and image budget. A phone's total RAM is not the app's available RAM; model download size is not peak resident memory.

A release decision needs a physical target device test: record SoC/GPU/RAM/OS/runtime/commit/model hash, cold initialization and first token, warm prefill/decode, complete label latency, peak process and graphics/shared memory, image dimensions/tokens, extraction accuracy, and repeated scans after thermal warm-up. Keep quantization and non-thinking/output limits explicit. If only a Mac and emulator are available, label all mobile requirements as estimates and leave phone performance unmeasured.
