# 本地配料模型对照测试

生成时间：2026-11-07T20:16:17-06:00。

测试包含1张真实包装照片的3种OCR结果、2张合成图片及5个合成文字/负例，共10个文字案例；直接读图使用4个图片案例。真实图的折边处2项配料作为可选项，另外16项参与召回率。三种真实输入来自同一张照片，不能视为独立样本或普遍准确率。

所有主配料指标按完整名称与英法别名匹配，忽略括号内容；子配料缺失、额外子配料、重复及截断另列。格式错误计为提取任务失败；超时、加载失败和未运行表示准确性未测得。

## 当前模拟器这张图

下表使用原来的横转照片OCR。P/R/F1为主配料精确率/召回率/F1；空输出的数学精确率约定不代表识别成功。

| 模型/路径 | 必需项 | P | R | F1 | 子配料R | 多余主项 | 额外子项 | 重复 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 规则提取器 | 0/16 | —（空输出） | 0.0% | 0.0% | 0.0% | 0 | 0 | 0 | 已输出 |
| Gemma（模拟器） | 0/16 | 0.0% | 0.0% | 0.0% | 0.0% | 1 | 0 | 0 | 已输出 |
| qwen3.5-0.8b | 13/16 | 72.2% | 81.2% | 76.5% | 100.0% | 5 | 39 | 0 | 截断 |
| qwen3-1.7b | 13/16 | 72.2% | 81.2% | 76.5% | 100.0% | 5 | 0 | 6 | 已输出 |
| qwen3.5-2b | 15/16 | 50.0% | 93.8% | 65.2% | 0.0% | 15 | 0 | 9 | 已输出 |
| qwen3-4b-instruct-2507 | 16/16 | 94.1% | 100.0% | 97.0% | 100.0% | 1 | 0 | 0 | 截断 |
| qwen3-vl-2b-instruct | 16/16 | 42.1% | 100.0% | 59.3% | 0.0% | 22 | 0 | 56 | 截断 |

首页实际显示的回退结果是 `BER HRE/`；按同一标准匹配0/16，多余项1，F1 0.0%。这与规则提取器本身返回空列表是两个阶段。

- **规则提取器**：漏项：Sugars (invert cane syrup, cane sugar, brown rice syrup)、Soy protein isolate、Vegetable glycerin、Palm kernel oil、Rice flour、Chicory root fibre、Natural flavour、Peanuts、Cocoa butter、Cocoa、Peanut butter、Soy protein concentrate、Salt、Rice starch、Soy lecithin、Tocopherols。多余主项：无。额外子配料0项；完整细节见原始JSON。
- **Gemma（模拟器）**：漏项：Sugars (invert cane syrup, cane sugar, brown rice syrup)、Soy protein isolate、Vegetable glycerin、Palm kernel oil、Rice flour、Chicory root fibre、Natural flavour、Peanuts、Cocoa butter、Cocoa、Peanut butter、Soy protein concentrate、Salt、Rice starch、Soy lecithin、Tocopherols。多余主项：BER HRE/ MEILLEUR AVANT。额外子配料0项；完整细节见原始JSON。
- **qwen3.5-0.8b**：漏项：Cocoa butter、Cocoa、Tocopherols。多余主项：Ber (sugar, cocoa butter, soy lecithin)；Phérols (sugar, cocoa butter, soy lecithin)；OD (sugar, cocoa butter, soy lecithin)；Caca0 (sugar, cocoa butter, soy lecithin)；Beurre d'édients (sugar, cocoa butter, soy lecithin)。额外子配料39项；完整细节见原始JSON。
- **qwen3-1.7b**：漏项：Vegetable glycerin、Cocoa butter、Cocoa。多余主项：Caca0；Beurre d'éditions；Glycérine *Biologique；Soy；May contain: Milk, Sesame, Tree nuts。额外子配料0项；完整细节见原始JSON。
- **qwen3.5-2b**：漏项：Tocopherols。多余主项：Brown rice syrup；Invert cane syrup；Cane sugar；Tree nuts；Milk；Sesame；Lait；Soja；Beurre；Phérols；O.D；BER HRE/ MEILLEUR AVANT；table oil；Organic；ARTIFIGTELS。额外子配料0项；完整细节见原始JSON。
- **qwen3-4b-instruct-2507**：漏项：无。多余主项：phérols。额外子配料0项；完整细节见原始JSON。
- **qwen3-vl-2b-instruct**：漏项：无。多余主项：Ber；Hre；Meilleur；Avant；Phérols；O；Caca0；Tale；Beurre；Edients；(sirop de canne inverti, sucre de canne, sirop de riz brun)；Biologique；Cient；Soja；Peut contenir；Lait；Sésame；Noix；table oil；Flour；Organic；Artifigte。额外子配料0项；完整细节见原始JSON。

## 同一真实照片的三种OCR输入

| 模型/路径 | real_sideways_enhanced | real_upright_enhanced | real_sideways_unmodified |
| --- | --- | --- | --- |
| 规则提取器 | 0/16；F1 0.0% | 0/16；F1 0.0% | 0/16；F1 0.0% |
| Gemma（模拟器） | 0/16；F1 0.0% | 7/16；F1 58.3% | 1/16；F1 11.1% |
| qwen3.5-0.8b | 13/16；F1 76.5%；截断 | 16/16；F1 62.7%；截断 | 16/16；F1 68.1%；截断 |
| qwen3-1.7b | 13/16；F1 76.5% | 15/16；F1 73.2% | 13/16；F1 74.3% |
| qwen3.5-2b | 15/16；F1 65.2% | 14/16；F1 71.8% | 16/16；F1 74.4% |
| qwen3-4b-instruct-2507 | 16/16；F1 97.0%；截断 | 16/16；F1 88.9% | 14/16；F1 93.3% |
| qwen3-vl-2b-instruct | 16/16；F1 59.3%；截断 | 16/16；F1 61.5% | 4/16；F1 11.6% |

## 接入当前过敏原规则后的结果

人工标签参考：花生、大豆应为“含有”；牛奶、芝麻、树坚果应为“可能含有”。这里调用项目现有Analyzer，输入模型配料列表，并继续提供同一案例的ML Kit原始OCR供警告解析。**vision行是此混合流程，未验证视觉模型独立读出警告的能力。**

状态相符不能代替配料结构准确性：一个包含大量文字的错误括号项也可能被递归匹配到花生/大豆。反之，把警告中的牛奶等写成配料会把“可能含有”升级为“含有”。

| 路径（原横转图） | 花生 | 大豆 | 牛奶 | 芝麻 | 树坚果 | 5项状态均相符 |
| --- | --- | --- | --- | --- | --- | --- |
| 规则提取器 | 未检出 | 未检出 | 可能含有 | 可能含有 | 可能含有 | 否 |
| Gemma（模拟器） | 未检出 | 未检出 | 可能含有 | 可能含有 | 可能含有 | 否 |
| qwen3.5-0.8b text | 含有 | 含有 | 含有 | 可能含有 | 可能含有 | 否 |
| qwen3.5-0.8b vision | 含有 | 含有 | 可能含有 | 可能含有 | 可能含有 | 是 |
| qwen3-1.7b text | 含有 | 含有 | 含有 | 含有 | 含有 | 否 |
| qwen3.5-2b text | 含有 | 含有 | 含有 | 含有 | 含有 | 否 |
| qwen3.5-2b vision | 含有 | 含有 | 含有 | 含有 | 含有 | 否 |
| qwen3-4b-instruct-2507 text | 含有 | 含有 | 可能含有 | 可能含有 | 可能含有 | 是 |
| qwen3-vl-2b-instruct text | 含有 | 含有 | 含有 | 含有 | 含有 | 否 |
| qwen3-vl-2b-instruct vision | 含有 | 含有 | 可能含有 | 可能含有 | 可能含有 | 是 |

逐项匹配及声明来源见 `results/downstream-allergens.json`（生成时间2026-11-08T02:16:17.686614+00:00）。该检查不覆盖未测试食物或过敏原的安全性。

## 合成案例与失败统计

合成正例共5题，负例共2题。全5题F1在有任何操作性失败或缺失时显示未完整测量；机器摘要另保存已测部分的均值与分母。截断输出即使能解析也单独计数。

| 模型/路径 | 合成正例宏F1 | 负例正确 | 正例空返回 | 格式错 | 操作失败 | 未运行 | 进行中 | 截断 | 重复 | 额外子项 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 规则提取器 | 80.0% | 2/2（已测2） | 4 | 0 | 0 | 0 | 0 | 不适用 | 0 | 0 |
| Gemma（模拟器） | 75.7% | 0/2（已测2） | 0 | 0 | 0 | 0 | 0 | 未记录 | 0 | 1 |
| qwen3.5-0.8b | 78.8% | 2/2（已测2） | 0 | 0 | 0 | 0 | 0 | 3 | 104 | 40 |
| qwen3-1.7b | 80.0% | 2/2（已测2） | 0 | 0 | 0 | 0 | 0 | 0 | 21 | 0 |
| qwen3.5-2b | 89.5% | 2/2（已测2） | 0 | 0 | 0 | 0 | 0 | 0 | 19 | 0 |
| qwen3-4b-instruct-2507 | 100.0% | 2/2（已测2） | 0 | 0 | 0 | 0 | 0 | 1 | 22 | 0 |
| qwen3-vl-2b-instruct | 100.0% | 1/2（已测2） | 0 | 0 | 0 | 0 | 0 | 1 | 77 | 0 |

Gemma模拟器接口只保存应用解析后的列表，原始生成与结束原因不可用；应用已删除完全相同的重复行，因此其重复/截断统计不能与保留原始回复的Qwen直接比较。

## 模型直接读图

此任务把图片交给模型，与给定ML Kit文字的纠错任务分开统计；耗时不包含另一次ML Kit运行。两种方向仍是同一张真实照片，另外两张是合成控制。

| 模型 | real_sideways_enhanced | real_upright_enhanced | clean_wheat_fixture | clean_granola_fixture |
| --- | --- | --- | --- | --- |
| qwen3.5-0.8b | 11/16；F1 75.9%；截断 | 16/16；F1 84.2% | 6/6；F1 92.3% | 8/8；F1 94.1% |
| qwen3.5-2b | 15/16；F1 78.9% | 15/16；F1 88.2% | 6/6；F1 92.3% | 8/8；F1 100.0% |
| qwen3-vl-2b-instruct | 1/16；F1 11.8%；截断 | 16/16；F1 86.5% | 6/6；F1 100.0% | 8/8；F1 100.0% |

## 本机性能

只记录本机macOS/ARM64结果。加载时间为启动服务器到就绪；首请求和重复请求为同一原图/原OCR的完整提取。优先使用单独的timing结果，避免并行下载/测试影响。

RSS每100ms采样，未覆盖所有GPU共享内存；它不是Android内存下限。系统换页活动也会干扰本机测量。Gemma模拟器的延迟和Mac的Qwen延迟不作横向速度比较。

| 模型 | 任务 | 加载s | 峰值RSS GiB | 首请求s | 重复完整提取中位s | 重复首字中位s | 重复截断 | 数据文件 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| qwen3.5-0.8b | text | 1.29 | 0.74 | 4.27 | 4.22 | 0.31 | 2/2 | qwen3.5-0.8b-text-timing.json |
| qwen3.5-0.8b | vision | 1.03 | 1.12 | 6.27 | 6.23 | 2.24 | 2/2 | qwen3.5-0.8b-vision-timing.json |
| qwen3-1.7b | text | 1.53 | 1.54 | 2.89 | 2.85 | 0.58 | 0/2 | qwen3-1.7b-text-timing.json |
| qwen3.5-2b | text | 1.79 | 1.43 | 3.63 | 3.65 | 0.65 | 0/2 | qwen3.5-2b-text-timing.json |
| qwen3.5-2b | vision | 1.80 | 2.24 | 8.59 | 9.05 | 5.99 | 0/2 | qwen3.5-2b-vision-timing.json |
| qwen3-4b-instruct-2507 | text | 4.39 | 2.94 | 14.23 | 14.29 | 1.47 | 2/2 | qwen3-4b-instruct-2507-text-timing.json |
| qwen3-vl-2b-instruct | text | 1.79 | 1.53 | 6.79 | 6.74 | 0.60 | 2/2 | qwen3-vl-2b-instruct-text-timing.json |
| qwen3-vl-2b-instruct | vision | 1.79 | 3.00 | 13.62 | 13.27 | 6.06 | 2/2 | qwen3-vl-2b-instruct-vision-timing.json |

### Gemma模拟器对照

环境：sdk_gphone64_arm64，总内存3.82 GiB，应用可用处理器数1。以下仅为该模拟器回调耗时，首次成功请求可能包含懒加载；不用于推算手机速度。

| 案例 | 状态 | 模拟器耗时s |
| --- | --- | --- |
| real_sideways_enhanced | 已输出 | 108.23 |
| real_upright_enhanced | 已输出 | 38.41 |
| real_sideways_unmodified | 已输出 | 12.62 |
| clean_wheat_fixture | 已输出 | 22.81 |
| clean_granola_fixture | 已输出 | 42.53 |
| synthetic_ocr_typos | 已输出 | 35.22 |
| synthetic_nested | 已输出 | 15.96 |
| synthetic_bilingual | 已输出 | 9.40 |
| negative_nutrition_only | 已输出 | 10.53 |
| negative_precaution_only | 已输出 | 10.47 |

## 模型存储与手机要求

**实体手机性能尚未测量。** 当前连接信息只确认Android模拟器；没有据此测得真实手机的速度、发热、功耗或RAM下限。

| 模型 | 语言权重GiB | 视觉编码器GiB | 合计GiB | 本机文件 | 手机总RAM起测目标（估计） |
| --- | --- | --- | --- | --- | --- |
| qwen3.5-0.8b | 0.50 | 0.19 | 0.69 | 已核对大小 | 文字6–8 GB；读图12 GB及以上 |
| qwen3-1.7b | 1.03 | 0.00 | 1.03 | 已核对大小 | 文字8–12 GB |
| qwen3.5-2b | 1.19 | 0.62 | 1.82 | 已核对大小 | 文字8–12 GB；读图12 GB及以上 |
| qwen3-4b-instruct-2507 | 2.33 | 0.00 | 2.33 | 已核对大小 | 文字12 GB及以上 |
| qwen3-vl-2b-instruct | 1.03 | 0.76 | 1.79 | 已核对大小 | 读图12 GB及以上 |

五款模型语言权重及匹配视觉编码器合计 **7.65 GiB（8.22 GB）**。以上是所下载Q4_K_M及FP16视觉编码器的大小；不含运行时、KV缓存和图片缓冲。

手机RAM档位是工程起测建议，既不是实测最低要求，也不保证特定手机成功。可用内存、SoC、驱动、量化格式、上下文与图像token预算都需要固定后实测。Android端需迁移/适配当前MediaPipe `.task` 加载层；GGUF读图还需要匹配的视觉编码器和多模态原生接口。详见[手机部署证据](phone-research.md)。

## 可复查数据

- [机器摘要](summary.json)、[案例与参考答案](cases.json)、[评分器](score.py)、[数据限制](dataset_notes.md)。
- 原始回复、每题耗时、截断标志和运行命令保留在 `results/`。权重版本及SHA-256在下载缓存的 `ready.json` 中，摘要也收录其清单。

本次结果用于选择下一轮候选。单张真实图片、固定提示词和固定量化版本的结果不能证明通用食品标签准确率，也不能证明已完成手机端部署。
