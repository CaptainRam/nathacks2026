# Android 模拟器模型测试

生成时间：2026-11-07T22:02:28.612253-06:00。主报告范围：`full`。

这份完整对照使用独立的 4 核 / 6 GiB 配置。原 1 核 / 4 GiB 诊断已主动结束并保留在 [原配置报告](../emulator5554/report.md)。

原配置下 Qwen3.5-2B 读图：横转图 264.4 秒、转正图 237.1 秒；原生进程采样Swap峰值 1.93 GiB。依据 [保留的原始数据](../emulator5554/cpu/full/qwen3.5-2b-vision.json)。

CPU核数、RAM、冷启动和宿主机条件都发生了变化；两份报告的耗时比值不能解释为纯粹增加CPU核数的加速比。

主范围计划 50 个文字案例、12 个读图案例；当前已测得准确性分别为 50 和 12 个。8/8 条模型/模式路径全部成功。

这里实际运行 Android ARM64 原生推理程序，宿主机只通过 adb 转发提交输入。**这些是模拟器结果，不是真机性能或手机最低配置。**

环境：sdk_gphone64_arm64；Android 17 / API 37；CPU 4 核；虚拟总内存 5.78 GiB；CPU 后端。

测试沿用固定 Q4_K_M 语言模型、FP16 视觉编码器、温度0、关闭思考与提示缓存、512-token 输出上限。仅有1张真实包装照片，三份OCR及两种方向均来自它；其余为合成控制。主配料名称、括号子配料、额外内容和截断分别评分。

运行时：Android aarch64 llama.cpp build 10809；单独校验的二进制一致：True。早期JSON的runtime字符串可能为空，版本与SHA-256依据 [runtime.json](runtime.json)。

## 原横转包装图

下表对应 `real_sideways_enhanced`。text 接收同一OCR；vision 接收照片，两者分开列出。尚无这个案例的结果时不会用清晰标签pilot替代。

| 模型 | 模式 | 状态 | 必需配料/F1 | 多余主项 | 额外子项 | 完整请求s | 首字s | Mac Metal重复中位s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| qwen3.5-0.8b | text | 已完成 | 13/16；F1 76.5%；截断 | 5 | 39 | 6.54 | 1.53 | 4.22 |
| qwen3.5-0.8b | vision | 已完成 | 13/16；F1 86.7%；截断 | 1 | 0 | 28.98 | 23.81 | 6.23 |
| qwen3-1.7b | text | 已完成 | 14/16；F1 80.0% | 5 | 0 | 7.31 | 3.20 | 2.85 |
| qwen3.5-2b | text | 已完成 | 15/16；F1 44.1%；截断 | 37 | 0 | 13.09 | 3.53 | 3.65 |
| qwen3.5-2b | vision | 已完成 | 14/16；F1 66.7% | 12 | 1 | 65.56 | 61.08 | 9.05 |
| qwen3-4b-instruct-2507 | text | 已完成 | 16/16；F1 97.0%；截断 | 1 | 0 | 33.59 | 10.62 | 14.29 |
| qwen3-vl-2b-instruct | text | 已完成 | 16/16；F1 59.3%；截断 | 22 | 0 | 11.57 | 3.06 | 6.74 |
| qwen3-vl-2b-instruct | vision | 已完成 | 12/16；F1 63.2%；截断 | 10 | 14 | 93.02 | 77.43 | 13.27 |

Mac列只提供同一输入的另一后端参考；模拟器CPU与Mac Metal不构成手机速度对比。主配料F1忽略括号内容，不能代替完整组成准确性。

## 加载、内存与范围覆盖

内存为该Android原生进程的采样值，包含失败前捕获的样本。`—`表示未捕获；RSS/PSS不是手机RAM下限，系统最低可用内存也不是模型要求。

| 模型/模式 | 已测/计划 | 加载s | 峰值RSS MiB | 峰值PSS MiB | 峰值Swap MiB | 最低系统可用MiB | 退出码 | 原始数据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| qwen3.5-0.8b / text | 10/10 | 1.56 | 1427.4 | 1424.5 | 0.0 | 3257.2 | 0 | [qwen3.5-0.8b-text.json](cpu/full/qwen3.5-0.8b-text.json) |
| qwen3.5-0.8b / vision | 4/4 | 2.06 | 1867.5 | 1864.5 | 0.0 | 2873.3 | 0 | [qwen3.5-0.8b-vision.json](cpu/full/qwen3.5-0.8b-vision.json) |
| qwen3-1.7b / text | 10/10 | 2.58 | 2936.8 | 2933.9 | 0.0 | 2435.5 | 0 | [qwen3-1.7b-text.json](cpu/full/qwen3-1.7b-text.json) |
| qwen3.5-2b / text | 10/10 | 2.57 | 2848.1 | 2845.1 | 0.0 | 2625.0 | 0 | [qwen3.5-2b-text.json](cpu/full/qwen3.5-2b-text.json) |
| qwen3.5-2b / vision | 4/4 | 5.12 | 3788.3 | 3785.8 | 38.4 | 1857.6 | 0 | [qwen3.5-2b-vision.json](cpu/full/qwen3.5-2b-vision.json) |
| qwen3-4b-instruct-2507 / text | 10/10 | 10.42 | 4606.5 | 4603.7 | 0.0 | 1243.9 | 0 | [qwen3-4b-instruct-2507-text.json](cpu/full/qwen3-4b-instruct-2507-text.json) |
| qwen3-vl-2b-instruct / text | 10/10 | 2.58 | 2996.0 | 2993.2 | 0.0 | 2741.3 | 0 | [qwen3-vl-2b-instruct-text.json](cpu/full/qwen3-vl-2b-instruct-text.json) |
| qwen3-vl-2b-instruct / vision | 4/4 | 3.59 | 4526.5 | 4523.7 | 0.0 | 753.5 | 0 | [qwen3-vl-2b-instruct-vision.json](cpu/full/qwen3-vl-2b-instruct-vision.json) |

## 当前图接入现有过敏原规则后的结果

以下是模型配料列表＋同一ML Kit原始OCR交给现有Analyzer的混合检查。vision行仍使用原OCR解析警告；这不是视觉模型独立识别警告，也不表示APK已接入这些模型。
参考标签：花生、大豆为“含有”；牛奶、芝麻、树坚果为“可能含有”。原始输入文件与下游快照SHA-256必须相符才显示结果。

| 模型/模式 | 花生 | 大豆 | 牛奶 | 芝麻 | 树坚果 | 5项均相符 |
| --- | --- | --- | --- | --- | --- | --- |
| qwen3.5-0.8b / text | 含有 | 含有 | 含有 | 可能含有 | 可能含有 | 否 |
| qwen3.5-0.8b / vision | 含有 | 含有 | 可能含有 | 可能含有 | 可能含有 | 是 |
| qwen3-1.7b / text | 含有 | 含有 | 含有 | 含有 | 含有 | 否 |
| qwen3.5-2b / text | 含有 | 含有 | 含有 | 含有 | 含有 | 否 |
| qwen3.5-2b / vision | 含有 | 含有 | 含有 | 含有 | 含有 | 否 |
| qwen3-4b-instruct-2507 / text | 含有 | 含有 | 可能含有 | 可能含有 | 可能含有 | 是 |
| qwen3-vl-2b-instruct / text | 含有 | 含有 | 含有 | 含有 | 含有 | 否 |
| qwen3-vl-2b-instruct / vision | 含有 | 含有 | 含有 | 含有 | 含有 | 否 |

状态相符不能代替配料结构准确性；错误的长括号项也可能被规则递归命中。下游数据见 [downstream-allergens.json](downstream-allergens.json)。

## 合成正负例

| 模型/模式 | 正例已测/计划 | 正例宏F1（完整范围） | 负例正确/已测/计划 | 格式错误 | 操作失败/跳过 | 截断 | 重复主项 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| qwen3.5-0.8b / text | 5/5 | 78.8% | 2/2/2 | 0 | 0 | 3 | 130 |
| qwen3.5-0.8b / vision | 2/2 | 93.2% | 0/0/0 | 0 | 0 | 2 | 143 |
| qwen3-1.7b / text | 5/5 | 80.0% | 2/2/2 | 0 | 0 | 1 | 111 |
| qwen3.5-2b / text | 5/5 | 89.5% | 2/2/2 | 0 | 0 | 2 | 138 |
| qwen3.5-2b / vision | 2/2 | 96.2% | 0/0/0 | 0 | 0 | 0 | 1 |
| qwen3-4b-instruct-2507 / text | 5/5 | 100.0% | 2/2/2 | 0 | 0 | 3 | 15 |
| qwen3-vl-2b-instruct / text | 5/5 | 100.0% | 1/2/2 | 0 | 0 | 2 | 149 |
| qwen3-vl-2b-instruct / vision | 2/2 | 100.0% | 0/0/0 | 0 | 0 | 1 | 77 |

加载失败、超时、服务退出和后续未运行均没有准确性测量，不会被记为NONE或准确率0。格式错误是已生成但未满足提取接口的任务失败。

## 失败与诊断

当前已保存结果没有报告服务或推理错误；尚未运行的路径仍未验证。

退出码137或进程消失本身不能证明OOM。成功完成后测试器会主动SIGTERM关闭服务，退出码143可能只是正常清理；应结合清理前状态、请求结果、日志和内存判断。

## 逐案例耗时与原生推理计时

请求耗时含整个提取；原生prefill/decode来自llama.cpp回传。生成token数不同，不能只据tok/s排名模型。

### qwen3.5-0.8b / text

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 76.5% | 6.54 | 1.53 | 432.86 | 102.05 | 512 | length |
| real_upright_enhanced | 已输出 | 62.7% | 6.44 | 1.58 | 414.60 | 105.10 | 512 | length |
| real_sideways_unmodified | 已输出 | 73.7% | 6.97 | 1.89 | 376.20 | 100.56 | 512 | length |
| clean_wheat_fixture | 已输出 | 92.3% | 1.19 | 0.96 | 444.25 | 108.97 | 27 | stop |
| clean_granola_fixture | 已输出 | 70.0% | 1.39 | 0.99 | 440.82 | 107.40 | 44 | stop |
| synthetic_ocr_typos | 已输出 | 42.9% | 1.64 | 1.13 | 376.07 | 104.42 | 55 | stop |
| synthetic_nested | 已输出 | 88.9% | 1.23 | 0.94 | 442.87 | 100.77 | 30 | stop |
| synthetic_bilingual | 已输出 | 100.0% | 1.02 | 0.93 | 434.88 | 112.66 | 11 | stop |
| negative_nutrition_only | 已输出 | 100.0% | 1.00 | 0.99 | 425.07 | 104.55 | 2 | stop |
| negative_precaution_only | 已输出 | 100.0% | 0.92 | 0.91 | 437.83 | 112.17 | 2 | stop |

### qwen3.5-0.8b / vision

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 86.7% | 28.98 | 23.81 | 95.44 | 98.99 | 512 | length |
| real_upright_enhanced | 已输出 | 84.2% | 28.91 | 23.77 | 95.93 | 99.49 | 512 | length |
| clean_wheat_fixture | 已输出 | 92.3% | 9.95 | 9.67 | 149.12 | 102.13 | 30 | stop |
| clean_granola_fixture | 已输出 | 94.1% | 8.50 | 7.85 | 164.61 | 104.36 | 69 | stop |

### qwen3-1.7b / text

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 80.0% | 7.31 | 3.20 | 208.76 | 57.31 | 237 | stop |
| real_upright_enhanced | 已输出 | 76.9% | 5.66 | 2.99 | 219.56 | 61.49 | 165 | stop |
| real_sideways_unmodified | 已输出 | 83.3% | 12.92 | 3.14 | 222.94 | 52.26 | 512 | length |
| clean_wheat_fixture | 已输出 | 100.0% | 2.17 | 1.82 | 228.69 | 68.25 | 25 | stop |
| clean_granola_fixture | 已输出 | 100.0% | 2.34 | 1.86 | 228.22 | 64.68 | 32 | stop |
| synthetic_ocr_typos | 已输出 | 0.0% | 2.20 | 1.78 | 233.32 | 59.40 | 26 | stop |
| synthetic_nested | 已输出 | 100.0% | 2.42 | 1.99 | 204.66 | 63.22 | 28 | stop |
| synthetic_bilingual | 已输出 | 100.0% | 1.81 | 1.66 | 239.43 | 66.95 | 11 | stop |
| negative_nutrition_only | 已输出 | 100.0% | 1.79 | 1.78 | 230.28 | 65.83 | 2 | stop |
| negative_precaution_only | 已输出 | 100.0% | 1.80 | 1.79 | 216.34 | 56.60 | 2 | stop |

### qwen3.5-2b / text

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 44.1% | 13.09 | 3.53 | 187.39 | 53.42 | 512 | length |
| real_upright_enhanced | 已输出 | 73.7% | 4.92 | 3.22 | 201.75 | 55.82 | 96 | stop |
| real_sideways_unmodified | 已输出 | 81.1% | 12.41 | 3.39 | 209.00 | 56.68 | 512 | length |
| clean_wheat_fixture | 已输出 | 100.0% | 2.46 | 2.03 | 203.24 | 54.15 | 24 | stop |
| clean_granola_fixture | 已输出 | 100.0% | 2.79 | 2.07 | 210.72 | 43.06 | 32 | stop |
| synthetic_ocr_typos | 已输出 | 75.0% | 2.32 | 2.01 | 210.17 | 58.99 | 19 | stop |
| synthetic_nested | 已输出 | 72.7% | 2.42 | 1.96 | 212.09 | 58.07 | 28 | stop |
| synthetic_bilingual | 已输出 | 100.0% | 2.08 | 1.90 | 211.16 | 54.18 | 11 | stop |
| negative_nutrition_only | 已输出 | 100.0% | 1.97 | 1.95 | 214.04 | 60.19 | 2 | stop |
| negative_precaution_only | 已输出 | 100.0% | 1.94 | 1.92 | 206.58 | 61.29 | 2 | stop |

### qwen3.5-2b / vision

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 66.7% | 65.56 | 61.08 | 37.15 | 54.34 | 244 | stop |
| real_upright_enhanced | 已输出 | 88.2% | 61.73 | 60.17 | 37.76 | 53.32 | 84 | stop |
| clean_wheat_fixture | 已输出 | 92.3% | 24.96 | 24.44 | 58.74 | 55.65 | 30 | stop |
| clean_granola_fixture | 已输出 | 100.0% | 20.58 | 20.03 | 64.37 | 56.57 | 32 | stop |

### qwen3-4b-instruct-2507 / text

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 97.0% | 33.59 | 10.62 | 62.01 | 22.25 | 512 | length |
| real_upright_enhanced | 已输出 | 88.9% | 28.64 | 7.64 | 85.17 | 24.34 | 512 | length |
| real_sideways_unmodified | 已输出 | 89.7% | 30.96 | 10.17 | 68.26 | 24.58 | 512 | length |
| clean_wheat_fixture | 已输出 | 100.0% | 4.99 | 4.20 | 94.53 | 30.45 | 25 | stop |
| clean_granola_fixture | 已输出 | 100.0% | 5.46 | 4.43 | 94.52 | 30.26 | 32 | stop |
| synthetic_ocr_typos | 已输出 | 100.0% | 5.01 | 4.45 | 92.04 | 30.63 | 18 | stop |
| synthetic_nested | 已输出 | 100.0% | 4.98 | 4.13 | 97.42 | 31.51 | 28 | stop |
| synthetic_bilingual | 已输出 | 100.0% | 4.44 | 4.14 | 94.57 | 33.72 | 11 | stop |
| negative_nutrition_only | 已输出 | 100.0% | 4.28 | 4.25 | 94.84 | 31.76 | 2 | stop |
| negative_precaution_only | 已输出 | 100.0% | 3.94 | 3.91 | 97.31 | 33.74 | 2 | stop |

### qwen3-vl-2b-instruct / text

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 59.3% | 11.57 | 3.06 | 215.67 | 60.01 | 512 | length |
| real_upright_enhanced | 已输出 | 61.5% | 11.74 | 2.97 | 219.92 | 58.29 | 512 | length |
| real_sideways_unmodified | 已输出 | 11.6% | 9.92 | 3.10 | 225.56 | 59.87 | 409 | stop |
| clean_wheat_fixture | 已输出 | 100.0% | 2.02 | 1.67 | 242.07 | 69.03 | 25 | stop |
| clean_granola_fixture | 已输出 | 100.0% | 2.23 | 1.77 | 237.53 | 67.07 | 32 | stop |
| synthetic_ocr_typos | 已输出 | 100.0% | 1.94 | 1.71 | 240.69 | 71.10 | 18 | stop |
| synthetic_nested | 已输出 | 100.0% | 2.12 | 1.70 | 237.12 | 64.01 | 28 | stop |
| synthetic_bilingual | 已输出 | 100.0% | 1.78 | 1.62 | 241.82 | 65.95 | 11 | stop |
| negative_nutrition_only | 已输出 | 100.0% | 1.75 | 1.74 | 232.20 | 67.20 | 2 | stop |
| negative_precaution_only | 已输出 | 0.0% | 1.73 | 1.58 | 241.90 | 70.76 | 12 | stop |

### qwen3-vl-2b-instruct / vision

| 案例 | 状态 | F1 | 请求s | 首字s | Prefill tok/s | Decode tok/s | 输出tokens | 结束原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| real_sideways_enhanced | 已输出 | 63.2% | 93.02 | 77.43 | 29.13 | 32.78 | 512 | length |
| real_upright_enhanced | 已输出 | 82.1% | 66.35 | 62.52 | 36.23 | 32.13 | 124 | stop |
| clean_wheat_fixture | 已输出 | 100.0% | 27.02 | 26.56 | 53.83 | 52.14 | 25 | stop |
| clean_granola_fixture | 已输出 | 100.0% | 21.15 | 20.52 | 62.42 | 49.33 | 32 | stop |

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

## 环境恢复与校验更正

完整测试结束后已恢复原来的1核/4096MB模拟器，测试转发已移除；三张原照片的SHA-256与测试前素材一致。
首次重启前的应用偏好探测实际返回“应用包不存在”，早先保存的相同哈希是报错文本，并非XML。因此已撤销“偏好已校验保留”的结论；未重装、卸载或覆盖应用。模型测试使用独立Android原生程序。
恢复记录见 [restoration.json](restoration.json)。
