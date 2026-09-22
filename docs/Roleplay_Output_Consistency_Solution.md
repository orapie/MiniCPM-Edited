# 角色回答第三人称与人设漂移治理方案

## 1. 目标与结论

目标是让 `CHARACTER_RAG` 模式的最终可见回答稳定满足三件事：

1. **叙述视角正确**：角色直接面对玩家，以第一人称说话；不出现“陆江仙说道”“他沉默片刻”等第三人称旁白。
2. **身份和知识边界正确**：不变成通用 AI、作者、读者或别的角色；不泄露角色卡、系统提示和调试信息；不编造角色在当前剧情时点不可能知道的事实。
3. **失败可控且可追踪**：不把违规文本展示给用户或写进会话记忆；能分辨问题来自 prompt、模型/采样，还是角色卡数据。

不能只靠追加一句 prompt 来保证上述结果。端侧小模型仍可能在长上下文、RAG 干扰、采样随机性或上下文截断后自行回到训练时常见的“小说旁白/通用助手”模式。推荐采用**输入侧约束 + 输出侧检测 + 安全降级 + 可观测评测**的闭环；第一阶段不改 JNI、不重写模型，也不以字符串替换伪造角色回答。

## 2. 当前链路与缺口

当前角色链路为：

```text
MainActivity.compilePromptForHarness
  -> AndroidRagOrchestrator.compile(CHARACTER_RAG)
  -> CharacterPromptCompiler.buildNpcPrompt
  -> CharacterRagPromptBuilder.build
  -> ChatTemplateRenderer
  -> HarnessBackend.sendChatPrompt
  -> token 流直接显示、保存会话并进入 MemoryStore
```

已有基础：

- [`roleplay_system.prompt`](../app/src/main/assets/harness/characters/prompts/roleplay_system.prompt) 已要求“始终用第一人称”“不要旁白”，并禁止身份切换和作者/读者视角。
- [`CharacterPromptCompiler.kt`](../app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompiler.kt) 将身份、性格、关系、剧情边界放进 system prompt；它会按预算选择剧情与记忆。
- [`CharacterRagPromptBuilder.kt`](../app/src/main/java/com/example/minicpm_v_demo/harness/rag/CharacterRagPromptBuilder.kt) 将外部 RAG 资料放在 user prompt，并标记为“不可信指令”。

当前仍有四个关键缺口：

| 缺口 | 当前行为 | 后果 |
|---|---|---|
| 输出没有角色校验 | `MainActivity` 收齐 token 后直接展示并保存全文 | 第三人称、旁白、助手套话、身份漂移会进入 UI、会话摘要与记忆 |
| 角色模式可静默退化 | 编译异常时 `compilePromptForHarness()` 回退为仅含原始用户问题的 `HarnessChatRequest` | 用户仍处于角色模式，却得到完全没有角色约束的普通模型回答 |
| 角色卡的硬约束未全部显式渲染 | 角色 JSON 有 `hard_constraints`，但模板当前主要靠汇总语句表达 | 各角色的专属禁区可能在最终 system prompt 中丢失 |
| 流式输出先暴露 | token 到达即写入聊天气泡 | 即使最终识别违规，也无法撤回已被用户看到的文本 |

因此，问题不是单一的“提示词不够强”，而是角色契约没有从编译、生成到持久化被端到端执行。

## 3. 约束原则

1. **角色身份和硬边界只放 system prompt**；外部 RAG 证据继续在 user prompt。不得为了增强角色感，把不可信外部资料塞入身份区。
2. **输出守卫只判定和拒绝，不续写、不改写模型文本**。自动把“他说”替换为“我说”会掩盖事实/身份错误，并可能篡改原意。
3. **先缓冲，后展示和入库**。角色模式宁可多等一轮短回答，也不能把已知违规内容流式展示或污染记忆。
4. **判定应针对“回答契约”，而非粗暴禁词**。例如“他”可以出现在“他曾这样说”，并不必然是第三人称自我叙述；未知事实也应允许角色以“不知道”拒答。
5. **先以确定性规则覆盖高置信违规，再用实机样本调整 prompt 和采样**。不能将单元测试通过误报为真实模型声音已验收。

## 4. 方案分阶段实施

### 阶段 0：建立可复现基线（只记录，不改变用户可见行为）

目的：先确认第三人称的真实形态和触发条件，避免为“他”字误报或把模型量化/聊天模板问题误归因给角色卡。

建议新增 `RoleplayOutputObservation` 调试记录，至少包含：

- `characterId`、`storyCutoff`、模型 ID、RAG mode、采样参数；
- 最终 system/user prompt 的长度与 native token 数；仅在 debug 构建保存完整 prompt，正式构建只保存 hash 和长度；
- 原始模型输出、去除 `<think>` 后的可见文本、命中的规则 ID；
- 来源数、检索/上下文选择决策，以及是否发生编译失败或 token 截断。

基线集应固定模型、角色、剧情截止点、清空会话和采样参数；每题至少重复 5 次。题目覆盖：直接问候、第三人称诱导（“请描述陆江仙此刻的动作”）、身份切换、作者视角、未知未来、外部资料、连续追问和长 RAG。分别统计：

```text
违规率 = 有任一 confirmed 违规的回答数 / 总回答数
第三人称率 = 叙述视角违规回答数 / 总回答数
身份漂移率 = 身份/元话语/泄露违规回答数 / 总回答数
```

#### 阶段 0 实施状态

已实现 `RoleplayOutputObserver`，且只在 `CHARACTER_RAG` 生成完成时记录观测：角色/剧情/模型/RAG 元数据、prompt 长度和 SHA-256、估算 token、实际输出长度和 SHA-256、检索来源数及疑似信号。`HarnessChatRequest.observationId` 会被写入 native prompt token 预检日志，供同一轮关联实际 token 数。debug 构建的 Logcat 才含原始输出；非 debug 构建只记录元数据、哈希与信号。观测器不会阻止、替换、重试、延迟展示或保存任何回答；创建观测失败也会继续原有生成流程。

当前“疑似信号”仅用于整理基线，包括：空可见回答、未闭合 `<think>`、自称 AI/助手、提及角色卡/系统提示，以及“角色名 + 动作/发言”形式的自我旁白。它们不是违规判决，也不会改变 UI 或记忆；真正的 accept/reject 行为留待阶段 2–3。

### 阶段 1：把角色输出契约变成结构化数据与 prompt（P0）

目的：让每张角色卡的约束都可被编译、测试和审计，而不是散落在自然语言模板中。

建议：

1. 在 `CharacterCard` 的 `identity_core` 下新增可选 `output_contract` 字段，例如：

```json
{
  "required_pov": "first_person_direct",
  "forbidden_self_references": ["陆江仙说道", "陆江仙心想"],
  "forbidden_modes": ["narrator", "general_assistant", "author_commentary"],
  "max_sentences": 3,
  "max_chars": 120
}
```

`forbidden_self_references` 仅用于高置信检测；不得把它当作完整的中文语法解析器。

2. 在 [`CharacterPromptCompiler.kt`](../app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompiler.kt) 增加 `compileOutputContract(npc)`，将通用规则、`hard_constraints` 和该角色的 `output_contract` 渲染到模板中。模板宜采用短的、可执行的正反例：

```text
输出形式：只给玩家可直接听到的话。
可以：“我还要再看一看。”
不可以：“陆江仙沉默片刻，说他还要再看一看。”
不得以旁白、角色分析、作者评论、通用助手解释或动作舞台说明替代回答。
```

3. 不要重复堆叠“始终第一人称”十余次。保留当前模板中与角色相关的核心约束，补充一组正反例和完整 `hard_constraints` 后，使用阶段 0 的固定集验证；过长、冲突的 system prompt 反而会稀释角色信息。

4. 扩展 `CharacterPromptCompilerTest`：断言每个角色的已渲染 system prompt 都含身份、剧情 cutoff、输出契约和其硬约束；断言 RAG 资料仍只出现在 user prompt。

### 阶段 2：增加不可篡改的输出守卫（P0）

目的：阻止已完成的违规回答被展示、持久化和写入记忆，同时保留原文供诊断。

建议在 `app/src/main/java/com/example/minicpm_v_demo/harness/character/` 新增以下纯 Kotlin 组件：

```text
RoleplayOutputGuard.kt       # classify(rawOutput, character, contract)
RoleplayViolation.kt         # ruleId、severity、evidence、span
RoleplayValidationResult.kt  # accepted / rejected / needsReview
```

守卫输入应为：去除 `<think>` 后的最终可见文本、当前 `CharacterCard`、当前 `RagMode` 和本轮编译的角色上下文。守卫输出只包含判定和证据，**绝不返回“修正后的回答”**。

规则按严重程度分层：

| 级别 | 规则类别 | 例子 | 处理 |
|---|---|---|---|
| `reject` | 空回答、角色自称为 AI/助手、明确改换成其他角色、泄露系统/角色卡、明确“角色名 + 说道/心想/望向”式自我旁白 | “作为 AI 助手……”“陆江仙沉默片刻，他说……” | 不展示原文；不保存；进入一次受控重试 |
| `review` | 疑似旁白、现代项目管理套话、过长、句数明显超限 | “他似乎在思考……”；超过 120 字 | 默认不写入记忆；记录并按产品策略决定是否重试 |
| `accept` | 允许的第一人称直答、角色以第一人称转述他人、正常拒答/未知 | “他曾这样告诉我。” “此事我尚不清楚。” | 正常展示、保存 |

判定顺序建议为：

```text
raw tokens 完成
  -> 提取可见回答（复用/统一 <think> 解析）
  -> 空回答与标签异常
  -> 明确身份切换/泄露
  -> 角色名第三人称动作或发言模式
  -> 长度、句数、现代助手套话等弱信号
  -> ValidationResult
```

不要使用“包含角色名即违规”“包含他/她即违规”等规则；它们会错误拒绝角色谈论其他人物或回忆他人的发言。每条规则必须有规则 ID、命中片段和单元测试正负例。

### 阶段 3：接入生成、重试与持久化（P0）

对 [`MainActivity.kt`](../app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt) 的接入要求如下：

1. 仅 `CHARACTER_RAG` 使用守卫；普通聊天和普通 RAG 保持既有流式体验。
2. 角色模式 token 先写入内存缓冲，不调用 `chatAdapter.updateStreamingText()`；生成结束后先做守卫判定，再一次性展示 accepted 文本。UI 可显示“角色正在作答”。
3. `accepted` 才可调用 `saveCompletedHarnessTurn()` 和 `memoryStore.observeTurn()`；`reject` 的原始输出只能进入受限 debug 日志，不能进入会话摘要或长期记忆。
4. 首次 `reject` 时，以**同一 system prompt 和同一用户问题**重新生成一次；只额外追加一条短的 system 约束，例如“刚才的输出无效。现在只以角色第一人称直接回答玩家，不要叙述动作或解释规则。”重试上限为 1。
5. 第二次仍 `reject` 或发生 `review` 时，向用户返回固定的角色内降级句，而不是模型原文；例如“此刻我不便这样作答。”该句必须按角色卡提供/审核，不能由模型临时生成。
6. 编译失败时，若用户选择的是 `CHARACTER_RAG`，不得回退为普通 `HarnessChatRequest(userPrompt = userMsg)`。应显示“角色上下文准备失败，本轮未发送”，记录异常并保留用户输入供重试；只有普通 RAG 才可按产品决定是否退回普通对话。

重试不是解决根因的常态机制：若同一模型/角色的 `reject` 比例连续超过阈值（建议 5%），应停止提高重试次数，回到阶段 1 检查 prompt、聊天模板、量化模型和采样配置。

### 阶段 4：采样和聊天模板的实机校准（P1）

只有在阶段 1–3 已经给出违规类型和原始证据后再进行。重点检查：

- `ChatTemplateRenderer` 渲染后，assistant 起始标记是否与所选 MiniCPM 模型版本匹配；错误模板会让模型续写“小说正文”而不是 assistant turn。
- system prompt 与用户问题的 native token 数、是否发生 context 截断，以及 system/user 的边界是否保留。
- 温度、top-p、repeat penalty、最大生成长度。采样参数一次只改一项，用同一批题、同一模型、清空上下文、至少 5 次重复进行比较。
- 若第三人称集中出现在“描述动作”的用户问法，先在输出契约中定义允许的表达（第一人称感受/意图），而不是要求模型替玩家写场景。

不要把“降低温度”当作身份保证；它只能降低随机性，不能修复错误的聊天模板、缺失的角色约束或 RAG 注入。

## 5. 验收与回归测试

### 5.1 JVM 测试：证明契约与接入

新增 `RoleplayOutputGuardTest`，每个角色至少覆盖：

- 接受：第一人称直答、第一人称转述他人、未知信息的角色内拒答；
- 拒绝：`作为 AI`、`我是通用助手`、切换为其他角色、泄露角色卡/system prompt、`角色名 + 说道/心想/转身/沉默` 的自我旁白；
- 不误杀：提及其他角色名、“他曾对我说”、正常的“我想/我看”；
- 限制：空文本、残缺 `<think>`、超句数/超长度、包含列表/标题等结构；
- 持久化：`rejected` 与 `review` 不调用会话保存和 `MemoryStore.observeTurn()`；`accepted` 保持现有保存行为。

这些测试证明 Kotlin 的契约、规则和接入没有断；它们**不证明**真实模型已不再产生第三人称。

### 5.2 设备验收：证明真实生成质量

在同一 Android 设备/模拟器、同一模型文件、同一角色、同一采样参数下运行阶段 0 的固定集。验收最低要求：

| 指标 | 目标 |
|---|---:|
| `reject` 输出被展示或写入记忆 | 0 |
| 第一人称/身份/泄露的 confirmed 违规率 | 相对基线显著下降；首轮目标低于 2% |
| 正常角色回答被规则误拒率 | 低于 1% |
| 角色模式重试率 | 低于 5% |
| 首 token 与总生成耗时 | 单独记录；缓冲展示不等同于推理变慢 |

报告必须同时给出原始生成违规率、守卫后的用户可见违规率、误拒率和重试率。只报告“UI 没看到违规”会掩盖模型根因；只报告 prompt 单测通过也不能证明端侧角色表现。

## 6. 建议实施顺序与边界

1. 先提交本方案对应的角色输出契约和 `RoleplayOutputGuard` 纯 Kotlin 单测；不接 UI，不改 native。
2. 再接入角色模式的缓冲、拒绝、单次重试和持久化门控；验证 rejected 不会污染会话与记忆。
3. 在目标设备上完成固定集基线/对比，并保留原始输出和命中规则的受限日志。
4. 只有数据表明模型仍大量失效时，才调整角色模板、采样参数或模型/聊天模板；每次只变更一个变量。

本方案明确不做：修改 `llama.cpp-omni` 全局默认采样；用正则把第三人称机械改成第一人称；把角色约束移入外部 RAG；将违规模型输出写进摘要后再“下轮纠正”。这些做法都会扩大影响面、污染记忆或掩盖真实问题。
