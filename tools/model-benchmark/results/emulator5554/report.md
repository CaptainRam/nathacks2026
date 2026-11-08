# Android 模拟器模型测试

生成时间：2026-11-07T22:00:14.888224-06:00。主报告范围：`full`。

**这份 1 核 / 4 GiB 配置已主动结束，保留已完成的诊断数据。** 独立的 4 核 / 6 GiB 测试进度和结果见 [新配置报告](../emulator5554-4cpu6gb/report.md)。两种资源配置的性能数据分开保存。

配置变更依据见 [profile-state.json](profile-state.json)；未完成项目没有被视为准确性失败，也不再标为仍在运行。

主范围计划 50 个文字案例、12 个读图案例；当前已测得准确性分别为 30 和 6 个。4/8 条模型/模式路径全部成功。

这里实际运行 Android ARM64 原生推理程序，宿主机只通过 adb 转发提交输入。**这些是模拟器结果，不是真机性能或手机最低配置。**

环境：sdk_gphone64_arm64；Android 17 / API 37；CPU 1 核；虚拟总内存 3.82 GiB；CPU 后端。

测试沿用固定 Q4_K_M 语言模型、FP16 视觉编码器、温度0、关闭思考与提示缓存、512-token 输出上限。仅有1张真实包装照片，三份OCR及两种方向均来自它；其余为合成控制。主配料名称、括号子配料、额外内容和截断分别评分。

运行时：Android aarch64 llama.cpp build 10809；单独校验的二进制一致：True。早期JSON的runtime字符串可能为空，版本与SHA-256依据 [runtime.json](runtime.json)。

## 原横转包装图

下表对应 `real_sideways_enhanced`。text 接收同一OCR；vision 接收照片，两者分开列出。尚无这个案例的结果时不会用清晰标签pilot替代。

| 模型 | 模式 | 状态 | 必需配料/F1 | 多余主项 | 额外子项 | 完整请求s | 首字s | Mac Metal重复中位s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| qwen3.5-0.8b | text | 已完成 | 13/16；F1 76.5%；截断 | 5 | 39 | 19.71 | 6.94 | 4.22 |
| qwen3.5-0.8b | vision | 已完成 | 13/16；F1 86.7%；截断 | 1 | 0 | 109.83 | 95.80 | 6.23 |
| qwen3-1.7b | text | 已完成 | 13/16；F1 76.5% | 5 | 0 | 23.90 | 14.18 | 2.85 |
| qwen3.5-2b | text | 已完成 | 14/16；F1 66.7% | 12 | 0 | 25.73 | 13.19 | 3.65 |
| qwen3.5-2b | vision | 该配置已主动结束 | 14/16；F1 66.7% | 12 | 1 | 264.40 | 251.82 | 9.05 |
| qwen3-4b-instruct-2507 | text | 该配置结束后未运行 | 未运行 | — | — | — | — | 14.29 |
| qwen3-vl-2b-instruct | text | 该配置结束后未运行 | 未运行 | — | — | — | — | 6.74 |
| qwen3-vl-2b-instruct | vision | 该配置结束后未运行 | 未运行 | — | — | — | — | 13.27 |

Mac列只提供同一输入的另一后端参考；模拟器CPU与Mac Metal不构成手机速度对比。主配料F1忽略括号内容，不能代替完整组成准确性。

## 加载、内存与范围覆盖

内存为该Android原生进程的采样值，包含失败前捕获的样本。`—`表示未捕获；RSS/PSS不是手机RAM下限，系统最低可用内存也不是模型要求。

| 模型/模式 | 已测/计划 | 加载s | 峰值RSS MiB | 峰值PSS MiB | 峰值Swap MiB | 最低系统可用MiB | 退出码 | 原始数据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| qwen3.5-0.8b / text | 10/10 | 4.13 | 1426.5 | 1423.6 | 0.0 | 1354.5 | 0 | [qwen3.5-0.8b-text.json](cpu/full/qwen3.5-0.8b-text.json) |
| qwen3.5-0.8b / vision | 4/4 | 3.12 | 1865.8 | 1862.9 | 0.0 | 1127.1 | 0 | [qwen3.5-0.8b-vision.json](cpu/full/qwen3.5-0.8b-vision.json) |
| qwen3-1.7b / text | 10/10 | 7.77 | 2546.2 | 2534.7 | 0.0 | 714.1 | 0 | [qwen3-1.7b-text.json](cpu/full/qwen3-1.7b-text.json) |
| qwen3.5-2b / text | 10/10 | 4.13 | 2595.8 | 2593.1 | 0.0 | 1041.8 | 0 | [qwen3.5-2b-text.json](cpu/full/qwen3.5-2b-text.json) |
| qwen3.5-2b / vision | 2/4 | 16.64 | 2587.8 | 2583.5 | 1973.0 | 450.6 | — | [qwen3.5-2b-vision.json](cpu/full/qwen3.5-2b-vision.json) |
| qwen3-4b-instruct-2507 / text | 0/10 | — | — | — | — | — | — | 尚无结果 |
| qwen3-vl-2b-instruct / text | 0/10 | — | — | — | — | — | — | 尚无结果 |
| qwen3-vl-2b-instruct / vision | 0/4 | — | — | — | — | — | — | 尚无结果 |

## 合成正负例

| 模型/模式 | 正例已测/计划 | 正例宏F1（完整范围） | 负例正确/已测/计划 | 格式错误 | 操作失败/跳过 | 截断 | 重复主项 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| qwen3.5-0.8b / text | 5/5 | 79.5% | 2/2/2 | 0 | 0 | 3 | 131 |
| qwen3.5-0.8b / vision | 2/2 | 93.2% | 0/0/0 | 0 | 0 | 2 | 143 |
| qwen3-1.7b / text | 5/5 | 80.0% | 2/2/2 | 0 | 0 | 1 | 112 |
| qwen3.5-2b / text | 5/5 | 89.5% | 2/2/2 | 0 | 0 | 1 | 69 |
| qwen3.5-2b / vision | 0/2 | — | 0/0/0 | 0 | 0 | 0 | 1 |
| qwen3-4b-instruct-2507 / text | 0/5 | — | 0/0/2 | 0 | 0 | 0 | 0 |
| qwen3-vl-2b-instruct / text | 0/5 | — | 0/0/2 | 0 | 0 | 0 | 0 |
| qwen3-vl-2b-instruct / vision | 0/2 | — | 0/0/0 | 0 | 0 | 0 | 0 |

加载失败、超时、服务退出和后续未运行均没有准确性测量，不会被记为NONE或准确率0。格式错误是已生成但未满足提取接口的任务失败。

## 失败与诊断

当前已保存结果没有报告服务或推理错误；尚未运行的路径仍未验证。

退出码137或进程消失本身不能证明OOM。成功完成后测试器会主动SIGTERM关闭服务，退出码143可能只是正常清理；应结合清理前状态、请求结果、日志和内存判断。

## 逐案例耗时与原生推理计时

请求耗时含整个提取；原生prefill/decode来自llama.cpp回传。生成token数不同，不能只据tok/s排名模型。

### qwen3.5-0.8b / text

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 76.5% | 19.71 | 6.94 | 95.24 | 40.02 | 512 | length |
| real_upright_enhanced | 已输出 | 62.7% | 22.15 | 9.24 | 80.37 | 39.59 | 512 | length |
| real_sideways_unmodified | 已输出 | 73.7% | 18.74 | 6.16 | 118.64 | 40.66 | 512 | length |
| clean_wheat_fixture | 已输出 | 92.3% | 4.25 | 3.64 | 115.60 | 42.30 | 27 | stop |
| clean_granola_fixture | 已输出 | 73.7% | 4.67 | 3.55 | 122.98 | 44.48 | 51 | stop |
| synthetic_ocr_typos | 已输出 | 42.9% | 4.78 | 3.52 | 120.28 | 42.77 | 55 | stop |
| synthetic_nested | 已输出 | 88.9% | 4.01 | 3.33 | 124.67 | 42.90 | 30 | stop |
| synthetic_bilingual | 已输出 | 100.0% | 4.54 | 4.27 | 93.85 | 37.83 | 11 | stop |
| negative_nutrition_only | 已输出 | 100.0% | 3.53 | 3.49 | 120.20 | 25.90 | 2 | stop |
| negative_precaution_only | 已输出 | 100.0% | 3.24 | 3.23 | 123.42 | 29.58 | 2 | stop |

### qwen3.5-0.8b / vision

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 86.7% | 109.83 | 95.80 | 23.69 | 36.42 | 512 | length |
| real_upright_enhanced | 已输出 | 84.2% | 110.03 | 96.12 | 23.73 | 36.74 | 512 | length |
| clean_wheat_fixture | 已输出 | 92.3% | 40.54 | 39.70 | 36.19 | 34.63 | 30 | stop |
| clean_granola_fixture | 已输出 | 94.1% | 33.13 | 31.23 | 41.32 | 39.43 | 76 | stop |

### qwen3-1.7b / text

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 76.5% | 23.90 | 14.18 | 46.67 | 20.89 | 204 | stop |
| real_upright_enhanced | 已输出 | 75.0% | 22.62 | 11.52 | 56.67 | 19.46 | 217 | stop |
| real_sideways_unmodified | 已输出 | 83.3% | 39.38 | 12.98 | 53.65 | 19.35 | 512 | length |
| clean_wheat_fixture | 已输出 | 100.0% | 7.92 | 6.85 | 59.88 | 22.32 | 25 | stop |
| clean_granola_fixture | 已输出 | 100.0% | 9.15 | 7.79 | 54.30 | 22.83 | 32 | stop |
| synthetic_ocr_typos | 已输出 | 0.0% | 8.27 | 6.91 | 60.04 | 18.34 | 26 | stop |
| synthetic_nested | 已输出 | 100.0% | 8.05 | 6.83 | 59.41 | 22.25 | 28 | stop |
| synthetic_bilingual | 已输出 | 100.0% | 6.98 | 6.58 | 60.17 | 25.22 | 11 | stop |
| negative_nutrition_only | 已输出 | 100.0% | 7.88 | 7.83 | 51.94 | 18.60 | 2 | stop |
| negative_precaution_only | 已输出 | 100.0% | 7.08 | 6.99 | 55.11 | 11.33 | 2 | stop |

### qwen3.5-2b / text

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 66.7% | 25.73 | 13.19 | 50.02 | 16.35 | 206 | stop |
| real_upright_enhanced | 已输出 | 80.0% | 23.25 | 13.16 | 49.03 | 10.21 | 104 | stop |
| real_sideways_unmodified | 已输出 | 78.9% | 40.24 | 12.92 | 54.60 | 18.70 | 512 | length |
| clean_wheat_fixture | 已输出 | 100.0% | 9.27 | 8.07 | 50.73 | 19.16 | 24 | stop |
| clean_granola_fixture | 已输出 | 100.0% | 9.53 | 8.00 | 54.46 | 20.34 | 32 | stop |
| synthetic_ocr_typos | 已输出 | 75.0% | 8.47 | 7.54 | 56.07 | 19.25 | 19 | stop |
| synthetic_nested | 已输出 | 72.7% | 9.15 | 7.68 | 54.04 | 18.37 | 28 | stop |
| synthetic_bilingual | 已输出 | 100.0% | 7.60 | 7.09 | 56.49 | 19.72 | 11 | stop |
| negative_nutrition_only | 已输出 | 100.0% | 7.58 | 7.53 | 55.47 | 21.50 | 2 | stop |
| negative_precaution_only | 已输出 | 100.0% | 7.35 | 7.28 | 54.36 | 12.90 | 2 | stop |

### qwen3.5-2b / vision

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 66.7% | 264.40 | 251.82 | 9.01 | 19.28 | 244 | stop |
| real_upright_enhanced | 已输出 | 88.2% | 237.08 | 232.28 | 9.77 | 17.28 | 84 | stop |
| clean_wheat_fixture | 该配置结束后未运行 | — | — | — | — | — | — | — |
| clean_granola_fixture | 该配置结束后未运行 | — | — | — | — | — | — | — |

## 其他范围的独立运行

pilot只验证清晰标签能否加载并返回；其成绩不会补齐full范围。

| 模型/模式 | 范围 | 状态 | 已测/计划 | 案例 | 请求s | 数据 |
| --- | --- | --- | --- | --- | --- | --- |
| qwen3.5-0.8b / text | pilot | 已完成 | 1/1 | clean_wheat_fixture | 4.28 | [qwen3.5-0.8b-text.json](cpu/pilot/qwen3.5-0.8b-text.json) |

## 存储与解释边界

| 模型 | 语言模型GB | 视觉编码器GB | 合计GB |
| --- | --- | --- | --- |
| qwen3.5-0.8b | 0.533 | 0.205 | 0.738 |
| qwen3-1.7b | 1.107 | 0.000 | 1.107 |
| qwen3.5-2b | 1.281 | 0.668 | 1.949 |
| qwen3-4b-instruct-2507 | 2.497 | 0.000 | 2.497 |
| qwen3-vl-2b-instruct | 1.107 | 0.819 | 1.927 |

全部权重合计 8.22 GB；测试器按模型复制和清理，不要求同时保留全部权重。文件大小不等于运行内存。

本报告不证明实体手机的速度、发热、功耗、GPU/NPU表现或最低RAM。原生adb进程也未覆盖APK生命周期、相机、UI和后台回收。

评分输入与参考答案见 [cases.json](../../cases.json)；机器摘要见 [summary.json](summary.json)。原始回复、停止原因、命令及内存采样保留在相应JSON和服务器日志。
