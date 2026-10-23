# Plan 2：让角色 RAG 按问题检索，并能证明每轮选择了不同资料

## 1. 问题定义与本计划的边界

现象不是单纯的“最终 system prompt 的文本相同”。角色扮演模式本应保留一段稳定的角色行为内核；若用户每轮都看到这段内核相同，这是正常且有益的。真正的问题是：针对不同问题，**可回答该问题的角色资料没有被稳定地路由、检索、选择和记录**，因而最终传给模型的动态证据常常没有变化，或根本无法证明它变化过。

本计划的目标是建立下面的可验证链路：

```text
用户问题
  -> 问题意图与所需资料面（可多选）
  -> 在角色资料库中按词项/结构检索候选
  -> 安全、时间线、可信度过滤和排序
  -> 在预算内选择可回答当前问题的证据
  -> 编译为稳定内核 + 本轮动态证据
  -> 输出可审计的选择轨迹和指纹
```

这里的“资料库”包括角色卡的稳定档案、当前状态/持有物、关系、V2 事实、V1 兼容事实及外部 RAG 索引；它们不是同一种可信度来源。不能为了让 prompt 每轮变化而把外部文档或未来剧情塞进角色记忆。

本计划不做以下事情：

- 不要求每一轮完整 system prompt 都不同；闲聊、问候和同一追问可能合理地复用相同证据。
- 不将 `character_knowledge_v2.sqlite3` 的 FTS/trigram 查询直接搬到 Android 运行时。当前运行时读取的是兼容的 JSON fallback；SQLite 在本项目中是审计/构建来源而不是手机上的在线查询引擎。
- 不取消既有的 cutoff、scope、可信度和角色边界过滤。
- 不把“模型回答质量”与“检索是否选对资料”混为同一个验收条件。两者需分别验证。

## 2. 当前实现与根因

| 现象 | 当前实现事实 | 造成的结果 |
| --- | --- | --- |
| 角色模式多数问题没有走 `rag/index.json` | `AndroidRagOrchestrator` 仅在 `CharacterQueryRouter.requiresExternalKnowledge()` 命中“文档、现实、搜索、联网”等显式词时检索外部 RAG | “你是谁”“你最想去哪里”等角色问题不会查询外部索引；这本身是正确的隔离策略，但容易被误认为 RAG 失效 |
| 角色事实看似没有从数据库检索 | `V2EpistemicKnowledgeStore` 在运行时读 `knowledge_fallback.json`；SQLite 仅参与资料审计/哈希校验 | 若只查看 SQLite 或只期待 SQLite FTS 日志，会误判运行时没有资料；也缺少能看见 JSON 选中条目的诊断 |
| 词项匹配过窄 | V2/V1 主要从用户原话生成字符 n-gram/关键词，再配合少量 `ProfileSignals`、`StateSignals`、`PossessionSignals` | “最想去哪里”“更愿意怎样”“为什么这样做”等偏好/动机问题没有明确的资料面和同义词路由，容易落入泛化 profile 或无候选 |
| `你是谁` 等身份问题刻意不取剧情事实 | `planKnowledgeQuery` 将身份/寒暄与剧情事实检索分开 | 正确地避免无关剧情污染身份回答，但目前没有把“身份、来历、偏好、动机”拆成可检索的原子档案记录 |
| 动态资料易被固定内容挤掉 | 当前角色 prompt 总预算约 900 字符，先装载全局摘要、性格、示例、状态等，再以优先级和 `queryBoost` 追加可选资料 | 即使检索到候选，也可能因预算被跳过；日志只显示数量，不能指出哪一项被选择或丢弃 |
| 默认 `topK=1` 且缺少问题矩阵测试 | 外部资料通常只取 1 段；角色动态选择缺少“不同提问必须选到不同证据”的回归断言 | 单条泛化资料可反复胜出，测试也无法揭露它 |

因此，不能只修改模板措辞或强制每次添加随机文本。改进重点是：将“用户问题需要什么资料”变成一等结构化结果，并让所选资料可观测、可测试、受预算保护。

## 3. 目标架构

### 3.1 查询分析结果

新增不可变的 `CharacterQueryAnalysis`（名称可按现有 Kotlin 命名调整），由 `CharacterPromptCompiler` 之前的专用分析器产生：

```kotlin
data class CharacterQueryAnalysis(
    val normalizedQuery: String,
    val intents: Set<CharacterQueryIntent>,
    val requestedSurfaces: Set<KnowledgeSurface>,
    val targetCharacterId: String?,
    val lexicalTerms: List<String>,
    val isFollowUp: Boolean,
    val requiresExternalKnowledge: Boolean,
    val explanationCodes: List<String>
)

enum class CharacterQueryIntent {
    IDENTITY, ORIGIN, PREFERENCE, MOTIVATION, VALUES, TRAITS,
    STATE_SCENE, STATE_QUEST, STATE_GOAL, POSSESSION, RELATIONSHIP,
    STORY_EVENT, EXTERNAL_DOCUMENT, FOLLOW_UP, UNKNOWN
}
```

一个问题可同时拥有多个意图。例如“你为什么还带着那把刀？”应请求 `MOTIVATION + POSSESSION`；“你现在想去哪里？”需区分当前地点/当前目标与长期偏好，不能只按“哪里”命中同一条背景资料。

### 3.2 统一证据包

所有角色资料面都输出统一的 `EvidenceCandidate`，再由一个选择器处理，而不是让各分支直接拼字符串：

```kotlin
data class EvidenceCandidate(
    val id: String,                 // 稳定、非正文的内部 ID
    val surface: KnowledgeSurface,  // PROFILE / STATE / POSSESSION / ...
    val text: String,
    val score: Double,
    val matchReasons: Set<String>,
    val trust: EvidenceTrust,
    val cutoffSafe: Boolean,
    val charCost: Int,
    val sourceRef: String
)
```

选择后的 `EvidenceBundle` 至少携带 `selectedIds`、`droppedIds`、各项分数/丢弃原因、各资料面计数和一个不含正文的 `fingerprint`。生产日志不记录角色私密正文；调试构建可在用户已开启的 Prompt Debug 页面中显示经过脱敏/明确授权的摘要。

### 3.3 Prompt 层次不变、动态层可见

最终 prompt 保持当前安全边界：

```text
[稳定角色内核]       角色身份、对话规则、时间线与安全规则
[本轮回答证据]       只放本问题所需的 profile/state/fact/relationship 等
[外部资料（可选）]   仅当问题确实请求外部文档，且放在 user context
[用户输入]           原始问题
```

稳定内核可复用；`[本轮回答证据]` 必须对非闲聊问题有独立指纹。外部资料仍然标为不可信参考，绝不升级为角色记忆。

## 4. 分阶段实施

### Phase 0：先建立可重复的基线和可观测性

> 实施状态（2026-10-21）：代码与 JVM 基线已完成。`RagTurnTrace` 会在 Debug 构建追加到
> `filesDir/harness/diagnostics/rag_turn_trace.jsonl`，并在“查看 Prompt”窗口附带不含正文的诊断信息。
> 设备上的真实模型回合基线仍需在已安装 Debug APK 后采集；该采集用于观察，不改变检索或回答策略。

**目的：** 在不改变回答行为前，准确区分“没有检索”“检索了但没选中”“选中却在 native 格式化后不可见”“模型忽略了证据”。

实施项：

1. 为 `AndroidRagOrchestrator` 的每次编译生成 `RagTurnTrace`，至少含：
   - `queryHash`、模式、角色 ID、模型/模板 ID；
   - `CharacterQueryAnalysis` 的意图、资料面和 explanation codes；
   - V2/V1/legacy/profile/state/possession/external 的候选数、选中 ID、淘汰 ID 与原因；
   - 稳定内核、本轮动态证据、外部 user context 三段各自的长度和 SHA-256 短指纹；
   - `fallbackUsed`、异常类型和最终发送路径。
2. 让 `MainActivity` 在现有 Debug/Logcat 路径输出上述非正文元数据；不要只输出“检索 N 条”。
3. 增加一个仅调试构建可用的“本轮 RAG 诊断”展示：显示 route、ID、分数、长度、指纹和拒绝原因。正式 UI 不展示资料正文和内部 prompt。
4. 为下面的问题建立固定角色、固定 session、固定索引的基线快照，保存 trace JSONL（不保存私密正文）：

| 探针 | 预期资料面 |
| --- | --- |
| 你是谁？ | identity profile |
| 你来自哪里？ | origin profile |
| 你最想去哪里？ | preference/motivation profile；若资料无此事实，明确无证据 |
| 你现在在哪里？ | current state scene |
| 你接下来要做什么？ | current state goal/quest |
| 你手里有什么？ | possession |
| 你和 X 是什么关系？ | relationship |
| 之前发生了什么？ | cutoff-safe story event |
| 文档里如何描述 X？ | external document RAG |

**通过条件：** 对每个探针都能从一条 trace 判断其是否走了预期路径；“最终 prompt 相同”不再是唯一诊断信号。

### Phase 1：补齐问题意图和资料面路由

> 实施状态（2026-10-21）：已完成。`CharacterQueryAnalyzer` 已接入 `CharacterPromptCompiler`，
> 将 identity/origin/preference/motivation/state/possession/relationship/story/follow-up 解析为
> 可审计的意图、资料面和原因码。该结果进入 Prompt Debug 与 `RagTurnTrace`；未修改 V2/V1
> 的角色、截止点、scope 或可信度过滤。profile 原子记录也可作为立即追问目标：例如“继续说明
> 来历”后的“其他的还有吗？”会以已保存的 opaque profile ID 重新检索同一档案面，而非读取摘要正文。

**目的：** 将散落的 keyword gate 收敛为可测试的查询分析器，优先解决角色档案类问题。

实施项：

1. 提取/替换 `CharacterPromptCompiler` 中分散的 `ProfileSignals`、`StateSignals`、`PossessionSignals` 和故事判断为 `CharacterQueryAnalyzer`。保留当前规则作为初始规则表，避免一次性引入远程模型分类。
2. 补充中文模式、同义词和否定/歧义处理，至少覆盖：
   - `IDENTITY`：你是谁、你叫什么、你的身份；
   - `ORIGIN`：来自哪里、出身、过去、来历；
   - `PREFERENCE`：最想/想去/更愿意/喜欢/宁愿/选择；
   - `MOTIVATION`：为什么、目的、打算、计划、想要；
   - `STATE_SCENE` 与 `PREFERENCE`：明确区分“你在哪里”与“你最想去哪里”；
   - `STATE_GOAL`：现在要做什么、下一步；
   - `POSSESSION`：手里/带着/装备/物品；
   - `RELATIONSHIP`：和某人什么关系、怎么看某人；
   - `FOLLOW_UP`：它/那里/这件事等需借助已授权会话摘要的指代。
3. 规则输出可多选的 `requestedSurfaces`，而非“身份问题就不检索”的单一布尔分支。
4. 对未识别问题输出 `UNKNOWN` 与显式原因码；此时可以保留稳定资料，但不得伪称已检索到相关事实。

**通过条件：** 上述探针的 intent、资料面、解释码均有 JVM 单测；“最想去哪里”不再与“现在在哪里”路由到同一类状态证据。

### Phase 2：将角色档案原子化并做真实的本地检索

> 实施状态（2026-10-21）：已完成运行时实现。角色卡的 identity/background/motivation/
> values/traits/behavior 已在加载后原子化为带稳定 ID、关键词和既有 `evidence_refs` 的
> `ProfileRecord`；`CharacterProfileStore` 以加载时建立的倒排索引召回候选。V2 与 V1
> claim store 同样改为加载时建立本地倒排索引；它们仍在召回后执行原有角色、cutoff、scope、
> 来源、审核与 belief 硬过滤。未直接执行 Android SQLite FTS/trigram 查询。

**目的：** 使档案、偏好和动机像事实资料一样可按问题选择，而不是每轮都塞进同一段大 profile。

实施项：

1. 保留既有角色卡字段的向后兼容；在加载时把背景、身份、价值观、说话风格、动机拆成带稳定 ID 的 `ProfileRecord`。
2. 对确有依据且需要直接回答的角色偏好，引入可选的结构化记录（例如 `answerable_preferences`）：

```json
{
  "id": "preference.destination.01",
  "questionTypes": ["preferred_destination"],
  "statement": "……",
  "keywords": ["想去", "目的地", "最想"],
  "evidenceRefs": ["card:..."],
  "confidence": "curated"
}
```

   没有证据的角色卡不得凭空补造“最想去的地点”；运行时应提供符合人设但明确不知/未定的回答依据。
3. 为 JSON fallback 建立构建期生成的轻量倒排索引（关键词 + 2/3-gram 到 record/claim ID），或在启动时缓存等价索引。索引只加速候选发现，最终仍必须经过现有 V2 的角色、时间、scope、可信度和 cutoff 门槛。
4. V2、V1、profile、state、possession 都返回 `EvidenceCandidate`。按以下顺序排序：硬安全过滤 → 意图/资料面匹配 → 精确词命中 → n-gram/同义词分 → 重要度/可信度 → 当前会话追问加分。
5. 在 `knowledge_fallback.json` 的 manifest 中添加索引/资料版本与源哈希。升级资产时原子替换 bundle；索引缺失或校验失败时降级为当前全量扫描并记录 trace，不中断聊天。

**通过条件：** 不同问题能选到不同的 `ProfileRecord`/claim ID；任何选中的故事条目仍满足 cutoff 和角色范围检查；JSON 索引不可用时行为安全降级。

### Phase 3：改造证据选择和 prompt 预算

> 实施状态（2026-10-21）：已完成首个关键修复。历史 `conversation_summary` 不再填满固定
> prompt 后的全部剩余预算；当本轮路由请求 profile/state/possession/relationship/story evidence
> 时，编译器先保留最多 220 字符给本轮证据。已增加“长摘要不能挤掉来历证据”的回归测试。
> 统一 `PromptEvidenceSelector` 的结构重整仍留在本阶段后续工作中。

**目的：** 防止相关证据被固定样例挤出，并让“无证据”成为受控结果。

实施项：

1. 在 `CharacterPromptCompiler` 中引入唯一的 `PromptEvidenceSelector`：输入 `CharacterQueryAnalysis + candidates + RuntimeContext`，输出 `EvidenceBundle`。禁止各资料分支直接追加 prompt 字符串。
2. 将 900 字符的现有系统预算分段核算。先在 Phase 0 测量实际固定部分长度，再制定上限；目标是为非闲聊问题保留可用的回答证据空间（建议不低于 220 字符），优先缩短重复示例和泛化描述，而不是静默丢弃问题相关证据。
3. 选择规则：
   - 若请求的资料面存在安全候选，至少保留一条最高分候选；
   - 多意图问题可在预算内各保留一条；
   - 同一资料不得以不同文案重复占用预算；
   - 对被淘汰候选记录 `budget_exceeded`、`lower_score`、`unsafe_cutoff` 等原因；
   - 无安全候选时写入简短结构化“本轮无已授权事实”，并由角色规则要求直接说明不确定，不得虚构。
4. 模板中明确区分“本轮回答证据”与固定角色设定。全局角色规则增加可测的直答约束：先直接回应用户的主问题，再允许自然推进对话；不能用反问替代答案。该约束不是证据，不能弥补检索缺失。

**通过条件：** 选中证据的动态段指纹在预定义的不同资料面探针之间发生预期变化；完整 system prompt 允许存在相同前缀，但不能在有不同安全候选时退化成同一动态段。

### Phase 4：把外部 RAG 与角色资料 RAG 明确分开

**目的：** 消除“所有问题都应查 `rag/index.json`”的错误预期，同时改进真正请求外部资料时的检索质量。

实施项：

1. 将当前的 `requiresExternalKnowledge` 关键词门改为 `ExternalKnowledgeNeedRouter`：识别用户是否要求附件、文档、新闻、现实信息、搜索结果或某个外部来源；不因“哪里/为什么”等普通角色问题误走外部库。
2. 外部查询保留 `RagSearchService` 的 HashEmbedding/向量检索与来源记录；对至少两条不同文档问题验证命中不同 `rag/index.json` chunk/source ID。
3. 外部上下文继续放在 user message，标为不可信资料；角色系统提示中只放“如何处理外部资料”的稳定规则，不将外部文本写入角色长期设定。
4. UI/诊断文案明确显示本轮是 `character_profile`、`character_state`、`character_story` 还是 `external_rag`，避免将角色资料路径误认为没查数据库。

**通过条件：** “你来自哪里”走角色档案而非外部 RAG；“文档中如何描述 X”能给出外部 source ID；两路径的上下文位置和可信度标签不同。

### Phase 5：将模型模板问题从检索问题中隔离

**目的：** 避免换模型后把 chat template、thinking 注入、采样参数或上下文截断误诊为 RAG 失效。

实施项：

1. 记录 native 实际采用的 chat template ID、thinking 开关、采样配置、最终消息段长度与 native 发送指纹；不要只依据 debug 渲染的 ChatML 文本判断模型收到的 token。
2. 为每个支持的模型家族维护显式配置/回归表。MiniCPM5-2B 的模板与采样评估是单独任务，不能用修改 RAG 检索来掩盖其服从性问题。
3. 当 native 层截断任意动态证据段时，trace 必须标为失败/降级，并返回可诊断的原因码。

**通过条件：** 同一已编译 `EvidenceBundle` 在不同模型上的差异能够归因到模板/采样/截断，而非不明的“RAG 没生效”。

## 5. 测试与验收矩阵

### 5.1 JVM 单元/集成测试

新增或扩展以下测试族：

1. `CharacterQueryAnalyzerTest`：对 Phase 0 的探针断言 intents、资料面、解释码；覆盖“你在哪里/你最想去哪里”的最小对照组。
2. `CharacterEvidenceRetrievalTest`：固定 JSON 资料库下断言候选 ID、排序、同义词、无命中和索引降级。
3. `CharacterPromptCompilerTest`：断言动态段的 selected ID、淘汰原因、预算下至少一条相关证据、无未来事实、无其他角色事实。
4. `AndroidRagOrchestratorTest`：断言角色资料与外部资料走不同路径，外部资料仅在 router 判定后进入 user context。
5. `RagTurnTraceTest`：断言 trace 不含正文/用户私密内容，且同一输入可重现相同指纹和选择结果。

不要用“整段 final prompt 字符串完全不同”作为唯一断言；应断言 route、selected IDs、动态段 fingerprint 与安全边界。

### 5.2 设备/模型回归

先用当前已验证的角色模型跑最小矩阵，再分别验证 MiniCPM5-2B：

- 每个资料面至少一问；重复两次以检查稳定性；
- 检查 trace 的选中 ID 与预期资料面；
- 检查回答先正面作答，不以反问取代答案；
- 检查不泄漏未来剧情、其他角色私密资料或外部上下文指令；
- 单独记录预填充长度、TTFT、输出 token、模型模板 ID。HTTP 200 或 native 调用成功不能视为回答/检索验收通过。

## 6. 文件级改动清单（实施时更新为实际路径）

| 范围 | 预期改动 |
| --- | --- |
| `AndroidRagOrchestrator.kt` | 接入 query analysis、证据包与 trace；保持 external/user context 分层 |
| `CharacterPromptCompiler.kt` | 删除分散拼接决策，使用统一选择器和预算账本 |
| 新增 `CharacterQueryAnalyzer.kt` | 规则化意图、资料面、同义词和解释码 |
| 新增/扩展 `CharacterEvidenceSelector.kt` | 候选排序、预算分配、淘汰原因 |
| `V2EpistemicKnowledgeStore.kt`、V1/档案加载器 | 输出统一候选，接入本地倒排索引及安全过滤 |
| `knowledge_fallback.json`、角色卡资产 | 版本化索引资料；仅在有依据时增加可回答偏好记录 |
| `roleplay_system.prompt` | 保持稳定内核，增加本轮证据标记与直答约束 |
| `MainActivity`/debug 设施 | 显示非正文 trace 元数据和 native 模板元数据 |
| 对应 JVM/instrumentation 测试 | 实现第 5 节矩阵 |

实际实施前需先用 `rg` 确认这些类的当前包名和资产复制路径，避免按计划新增第二套平行运行链路。

## 7. 推进顺序、发布与回滚

1. 先完成 Phase 0，并用 trace 复现当前“多问题同 prompt”的样本；此阶段不改用户可见行为。
2. 完成 Phase 1 与测试矩阵后，先只启用 query analysis/trace 比对；确认没有把身份问题误导到剧情事实。
3. 完成 Phase 2 与 Phase 3 后，对一个角色卡灰度启用原子档案和预算选择；保留当前全量扫描作为 fallback。
4. 完成 Phase 4 后再扩大到外部资料问题；Phase 5 的模型调优独立发布。

回滚开关应至少支持：关闭新索引回到安全的全量 JSON 扫描、关闭新选择器回到旧编译器、关闭调试 trace 正文展示。无论哪个开关生效，cutoff/可信度过滤不得关闭。

## 8. 最终验收清单

- [ ] 每次角色编译都能提供不含正文的 query/route/selection trace。
- [ ] `你是谁、来自哪里、最想去哪里、现在在哪里、下一步、手里有什么、与某人关系、之前发生什么、文档中如何描述 X` 均命中可解释的预期资料面或明确无证据。
- [ ] 上述不同资料面的问题，其动态证据 selected IDs/fingerprint 按预期不同；稳定内核可相同。
- [ ] 角色问题不会错误查询外部 `rag/index.json`；外部资料问题会提供可审计 source ID。
- [ ] 所有 V2/V1/状态/持有物证据仍通过角色、时间线、scope 和可信度过滤。
- [ ] 预算紧张时相关证据优先于重复样例；被丢弃项有明确原因。
- [ ] 至少一个已验证模型和 MiniCPM5-2B 分别完成设备端回归；模型输出问题能与检索选择问题明确区分。
