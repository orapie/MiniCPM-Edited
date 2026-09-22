# 端侧大模型重复输出：手动修改与验证指南

## 1. 结论：这次问题发生在哪里

示例回答不是 Android 文本控件把内容重复显示了，而是模型在逐 token 解码时反复选择了语义相近的高概率词。现有实现会把每次采样得到的 token 立刻写入原生 KV cache，再返回 Kotlin 流式显示；因此一旦采样连续偏向“朋友、秘密、教会了我”这一局部模式，后续 token 会继续在这个模式中自我强化。

本项目当前的直接原因已经可以从代码确认：

- `app/src/main/cpp/llama_jni.cpp` 的 `new_sampler()` 显式设置 `top_k = 0`、`top_p = 1.0f`、`penalty_repeat = 1.0f`。前两项关闭候选过滤，最后一项关闭重复惩罚；实际等于只按 `temperature = 0.7` 采样。
- `app/src/main/assets/harness/characters/prompts/roleplay_system.prompt` 限制回答为一至三句、少于 120 字，但没有要求“已表达的事实不要换句式再说一遍”。
- Kotlin 的默认输出上限是 128 token。它是安全上限，不会让模型停在一个自然结尾；若模型没有输出 EOG（结束 token），会一直生成到上限。因此它会放大循环，但不是首要根因。

本指南只给出你可以亲手完成的修改。推荐顺序是：**先仅改采样器并实机验证，再改角色提示词，最后才考虑缩短输出上限。** 不要一开始同时改三处，否则无法知道哪一项真正改善了结果。

## 2. 先理解项目调用链

```text
MainActivity / HarnessFacade
        ↓  Flow<String>
LlamaEngine.sendUserPrompt(message, predictLength)
        ↓ JNI
llama_jni.cpp::processUserPrompt(..., n_predict)
        ↓
llama_jni.cpp::generateNextToken()
        ↓
common_sampler_sample(g_sampler, ...)
        ↓
llama.cpp-omni/common/sampling.cpp
        ↓
token 写入 KV cache → Kotlin 流式显示
```

文件职责如下。

| 文件 | 在项目中的职责 | 本次是否应修改 |
| --- | --- | --- |
| `app/src/main/cpp/llama_jni.cpp` | Android JNI 推理入口；创建采样器、把 prompt 送入模型、每次取一个 token | **第一步修改** |
| `llama.cpp-omni/common/common.h` | 第三方 llama.cpp 公共采样参数默认值与采样链定义 | 只阅读，不修改 |
| `llama.cpp-omni/common/sampling.cpp` | 第三方 llama.cpp 真正组装 penalties、top-k、top-p、temperature 等采样链 | 只阅读，不修改 |
| `app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt` | Kotlin 与 JNI 的桥；控制每轮最大生成 token 数、把 token 暴露为 `Flow` | 第三阶段可选修改 |
| `app/src/main/assets/harness/characters/prompts/roleplay_system.prompt` | 角色模式的 system prompt 模板；由角色编译器渲染后放进系统消息 | 第二阶段修改 |
| `app/src/main/java/.../harness/HarnessBackend.kt` | 角色/RAG 的统一后端接口；有 system prompt 时会清空上下文后再发送 | 不改；用于理解验证边界 |
| `app/src/main/java/.../harness/character/CharacterPromptCompiler.kt` | 加载 `roleplay_system.prompt`，填充角色、关系、剧情和记忆变量 | 不改；用于确认模板会生效 |
| `app/src/main/cpp/CMakeLists.txt` | 将 `llama_jni.cpp` 和 vendored `llama.cpp-omni` 编成 app 的 native library | 通常不改 |

这里的关键分层是：角色约束应留在 system prompt，RAG 证据与玩家问题留在 user prompt；不要为了解决重复而把角色事实挪到解码器，也不要改 `llama.cpp-omni` 的全局默认值。前者会破坏可维护性，后者会让第三方源码升级和问题归因都更困难。

## 3. 准备：建立可比较的基线

先不要修改代码，在**同一模型、同一角色、清空对话上下文后**连续运行下面的输入 5 次：

```text
你的社会关系如何？只说最重要的两位朋友，以及各自带给你的不同影响。
```

每次保存以下信息：完整输出、是否出现重复句/重复事实、首 token 时间、总字数、是否在自然句末结束。必须先清空上下文，是因为 KV cache 保存了之前的 token；不清空就无法区分“本轮采样问题”和“上一轮上下文诱导”。

建议在 Android Studio Logcat 中筛选 `LlamaEngine` 和 `llama_jni`。当前代码已有这些可用信号：

- `User prompt processed. prefill_ms=...`：提示词预填耗时；
- `First assistant token received. ttft_ms=...`：首 token 时间；
- `STOP: hitting stop position`：说明不是自然 EOG，而是达到 token 上限；
- `IS EOG`：说明模型自行结束。

把结果记录成最小表格。不要只看一条“看起来不错”的回复，因为当前采样不是确定性的。

| 版本 | 第几次 | 是否重复 | 是否自然结束 | TTFT | 字数 | 备注 |
| --- | ---: | --- | --- | ---: | ---: | --- |
| baseline | 1–5 |  |  |  |  |  |

## 4. 第一阶段：启用温和的重复惩罚（核心修复）

### 4.1 修改位置

打开 `app/src/main/cpp/llama_jni.cpp`，定位 `new_sampler(float temp)`，当前在约第 262 行。只替换函数中的参数赋值部分，不要改 JNI 方法名、`g_sampler` 生命周期或 `generateNextToken()`。

将当前代码：

```cpp
common_params_sampling sparams;
sparams.temp = temp;
sparams.top_k = 0;            // disabled
sparams.top_p = 1.0f;         // disabled
sparams.penalty_repeat = 1.0f; // disabled
return common_sampler_init(g_model, sparams);
```

替换为：

```cpp
common_params_sampling sparams;
sparams.temp = temp;
sparams.top_k = 40;
sparams.top_p = 0.90f;
sparams.min_p = 0.05f;

// 在最近 128 个 token 中抑制已经使用过的 token；1.0 表示完全关闭。
sparams.penalty_last_n = 128;
sparams.penalty_repeat = 1.12f;
sparams.penalty_freq = 0.05f;
sparams.penalty_present = 0.0f;
return common_sampler_init(g_model, sparams);
```

同时把该函数上方“pure temperature sampling / no repetition penalty”的旧注释改成与新参数一致的说明。例如：

```cpp
// Android conversational defaults: keep fluent Chinese while discouraging
// short-range loops. Tune only after fixed-prompt device evaluation.
```

### 4.2 每个参数的作用

| 参数 | 推荐起点 | 作用 | 调太高/太低的风险 |
| --- | ---: | --- | --- |
| `temp` | 保持 `0.7f` | 控制整体随机性 | 过低易模板化；过高会跑题或事实不稳 |
| `top_k` | `40` | 只保留概率最高的 40 个候选 token | `0` 是关闭；太小会僵硬，太大让低质量候选重新进入 |
| `top_p` | `0.90f` | 保留累计概率 90% 内的候选 | `1.0` 是关闭；太低可能截掉中文虚词后的合理续写 |
| `min_p` | `0.05f` | 去掉相对最优 token 概率过低的长尾候选 | `0.0` 是关闭；过高会让表达单一 |
| `penalty_last_n` | `128` | 只回看最近 128 个 token，界定惩罚窗口 | 太小看不到上句；`-1` 扫全上下文会误伤角色名、专有名词 |
| `penalty_repeat` | `1.12f` | 降低已出现 token 的再次被选中概率 | `1.0` 是关闭；超过约 `1.20` 容易损坏人名、术语和中文语法 |
| `penalty_freq` | `0.05f` | 一个 token 出现越多，额外惩罚越大 | 太高会强迫模型避免正常重复，例如“元府” |
| `penalty_present` | `0.0f` | token 只要出现过就固定惩罚 | 本次保持关闭，避免角色专名首次出现后就被不必要排斥 |

`common_params_sampling` 的默认 sampler 序列已经含有 `PENALTIES`；`llama.cpp-omni/common/sampling.cpp` 会据此用这四个 penalty 参数创建 `llama_sampler_init_penalties(...)`。所以不用修改 vendor 目录，也不用自己实现 token 计数。

### 4.3 为什么这是最小、可回滚的修改

`new_sampler()` 在模型加载和完整 reset 后创建 `g_sampler`；每个 token 都在 `generateNextToken()` 中通过 `common_sampler_sample(g_sampler, ...)` 经过同一个采样器。修改这一处会同时覆盖普通文本、视觉模型文本回答和角色/RAG 回答，而不会修改提示词内容、UI 或聊天记录格式。

如果效果变差，恢复第一阶段前的 7 行参数即可回滚。不要在流式 UI 层删除重复文字：那只能隐藏显示，原始重复 token 已经进入 KV cache，下一轮会被错误历史继续影响。

## 5. 编译与安装：确认改到的是实际 native library

本项目的 native 源码由 `app/src/main/cpp/CMakeLists.txt` 编译，默认使用根目录的 `llama.cpp-omni`，并打包进 `libminicpm_v_demo.so`。Kotlin/JNI 的声明在 `LlamaEngine.kt` 第 902–904 行，C++ 的符号名必须保持不变；本阶段只改参数，因此无需修改 Kotlin。

在项目根目录执行：

```bash
./gradlew :app:assembleDebug
```

安装到已连接设备或模拟器：

```bash
./gradlew :app:installDebug
```

注意：仅运行 JVM 单元测试不足以证明 C++ 参数被编进 APK；它只能验证 Kotlin 的纯逻辑。此次修改必须至少完成一次 native `assembleDebug` 和一次真机/模拟器实际推理。若 native 构建失败，先看第一个 `FAILED` 或 CMake error；不要把 Gradle 弃用警告当作根因。

## 6. 第一阶段验收与调参顺序

重新用第 3 节相同输入、相同模型、清空上下文、运行 5 次，并填一行 `sampler-v1`。验收目标不是“永不重复”，而是：

1. 5 次中不再出现连续同义复述两次以上；
2. 角色名、世界观专名仍能自然重复；
3. 不显著增加跑题、乱码、英文混入或断裂句；
4. TTFT 与 baseline 相近。采样过滤的计算量很小，明显退化通常意味着构建、模型或设备状态变化，不是这几个浮点参数本身。

只在失败方向上按下表一次调整一个参数，并每次重测 5 次。

| 现象 | 下一步，只改一项 | 不要做什么 |
| --- | --- | --- |
| 仍整段循环 | `penalty_repeat: 1.12 → 1.15` | 不要直接升到 `1.3` |
| 同一名词反复出现但没有整段循环 | `penalty_freq: 0.05 → 0.08` | 不要开启很高的 `penalty_present` |
| 太保守、答案机械 | `top_p: 0.90 → 0.95` 或 `top_k: 40 → 60` | 不要同时提高 temperature |
| 胡编、角色漂移 | `temp: 0.70 → 0.65` | 不要通过提高重复惩罚解决事实问题 |
| 专名被刻意避开 | `penalty_repeat: 1.12 → 1.08` | 不要全上下文惩罚（`penalty_last_n=-1`） |

建议把最终确定的数值和使用模型名写回 `new_sampler()` 注释；这样以后更换 GGUF 时能明确知道当前值是产品调优值，不是 llama.cpp 默认值。

## 7. 第二阶段：给角色模板补“语义去重”约束

只有在角色模式仍出现“同一事实换句话再说一遍”时才做本阶段。采样惩罚针对 token 重复，不能完全理解“朋友教我保密”和“他们让我学会守住秘密”是同义重复；这需要 prompt 约束。

编辑 `app/src/main/assets/harness/characters/prompts/roleplay_system.prompt` 第 3 行。在“少于120字。”之后插入下面这句话：

```text
同一事实、理由或结论只说一次；已经表达后不要换词、换句式或换顺序重复。若信息不足，简短停在已知处，不用同义句填满篇幅。
```

修改后的开头应类似：

```text
只说角色此刻会亲口说的话，通常一至三句、少于120字。同一事实、理由或结论只说一次；已经表达后不要换词、换句式或换顺序重复。若信息不足，简短停在已知处，不用同义句填满篇幅。不要旁白、分析、标题、列表、资料摘要或任何尖括号标签。
```

作用与边界：

- 这条规则由 `CharacterPromptCompiler.kt` 加载并填充为 system prompt，天然比玩家输入和 RAG 文档优先级高；
- 它只影响角色/RAG 模式，普通聊天不会得到此约束；
- 它与现有“紧张时可以重复词句”的角色设定可能有轻微张力。新规则应优先阻止**事实复述**，但不必禁止一次自然的情绪停顿或短促重复；
- 修改 assets 后也要重建、重装 APK。已安装的旧 APK 不会自动读取工作区里的文件。

为这个改动添加一条 JVM 回归测试是可取的：在 `app/src/test/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompilerTest.kt` 中编译任意角色 prompt，断言渲染后的 system prompt 包含“同一事实、理由或结论只说一次”。它证明模板加载链没有断，但不证明模型真的不重复；真实生成仍必须在设备上验收。

## 8. 第三阶段（可选）：缩短默认生成上限

仅当 Logcat 经常出现 `STOP: hitting stop position`，且产品本身期望短角色回答时，才编辑 `app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt`：

```kotlin
const val DEFAULT_PREDICT_LENGTH = 128
```

先改为：

```kotlin
const val DEFAULT_PREDICT_LENGTH = 96
```

它的作用是把“模型未产生 EOG 时最多还能生成多少 token”从 128 降到 96。该值通过 `sendUserPrompt()` 传给 JNI 的 `processUserPrompt(..., predictLength)`，native 再以 `current_position + n_predict` 作为硬停止位置。

这不是去重算法：它只能缩短循环造成的损失，也会截断真正需要长回答的问题。若普通问答和角色问答有不同产品需求，更好的后续设计是在 `HarnessFacade.sendChatPrompt()` 调用点为角色模式明确传 `96`，普通聊天仍使用 `128`，而不是降低全局常量。

## 9. 不建议的“修复”及原因

- 不要在 `MainActivity`、Markdown 渲染器或 RecyclerView 中删除重复句：显示层删不掉 KV cache 已接收的重复 token，且会让屏幕内容与下一轮上下文不一致。
- 不要改 `llama.cpp-omni/common/common.h` 的全局默认值：它是 vendor 源码，升级/合并时容易冲突；本 app 已在 `new_sampler()` 覆盖参数。
- 不要把 `temperature` 设为 `0`：这会贪心解码，通常更容易卡进确定性的重复循环。
- 不要一开始启用 DRY、Mirostat、grammar、输出后处理等多个机制：它们会混淆归因，并可能伤害中文人名、术语与角色风格。
- 不要用“禁止重复”替代采样参数：prompt 是软约束，小模型在高概率循环里常常无法遵从；采样惩罚才在每一步 token 选择时直接生效。

## 10. 完成定义与下一步

完成本任务的最低标准是：第一阶段参数已编进 debug APK，固定测试输入连续 5 次的原始输出已记录，且能说明重复率与自然结束情况相对 baseline 的变化。第二阶段和第三阶段按观察结果选择，而不是默认全部启用。

如果 `sampler-v1` 后仍出现整段逐字重复，请保留原始回复、模型名称/量化、Logcat 中的 `STOP`/`IS EOG`、以及 5 次结果表。下一轮诊断应检查 chat template、输入中是否有重复 RAG 证据、GGUF 的训练/量化质量和 EOG token，而不是继续无上限提高惩罚。
