# Character RAG 查询感知角色上下文：实施方案

## 1. 目标与问题边界

当前 `CHARACTER_RAG` 的最终 system prompt 由一段稳定角色核与少量可选剧情/关系事实组成。稳定角色核每轮相同是合理的；问题在于角色卡里已经存在的来历、动机、价值观、当前场景等信息没有成为可检索候选，V2 图谱中的 `profile` claim 又被运行时硬过滤。因此用户问“你来自哪里”“你手里有什么”时，通常不会得到与问题相关的角色事实注入。

本方案的目标是在不改变外部文档 RAG 边界的前提下，让每轮 `CHARACTER_RAG` 按 `(characterId, storyCutoff, userInput, runtimeState)` 生成：

```text
稳定角色核（始终保留，system）
+ 与问题相关的角色档案事实（system）
+ 与问题相关的当前状态/持有物（system）
+ 已授权的剧情事实和人物关系（system）
+ 会话摘要（system，仅解决承接）
+ 外部文档上下文（user，仅在外部请求时）
```

这不是把完整角色卡、整张图谱或原始外部文档塞入 system prompt。每轮只选择可解释、可授权、预算内的少量条目。

### 非目标

- 不改变 `RagSearchService` 的外部文档检索语义。
- 不把外部文档、玩家陈述或未审核资料写入角色身份/记忆区。
- 不让模型自行判断未来剧情、角色可知性或 profile 资料是否可用。
- 不在本阶段实现自动长期记忆写入、工具调用或模型驱动的意图分类。
- 不把“手里有什么”凭空补成模型猜测；没有结构化数据时必须保留未知。

## 2. 已确认的现状与根因

| 数据/能力 | 当前状态 | 为什么不能回答查询感知问题 |
| --- | --- | --- |
| `identity_core.background`、`core_motivations`、`values`、`psychological_traits`、`hard_constraints` | 已存在于角色 JSON，`CharacterJsonLoader` 也会加载 | `CharacterPromptCompiler.compileGlobalSummary()` 仅渲染姓名、别名和 `worldview_position`；其余字段不是候选项 |
| `dynamic_state` | 有场景、情绪、任务状态、当前目标 | 当前模板只渲染场景和情绪；不按问题选取任务或目标 |
| 当前持有物/装备 | 没有独立模型或字段 | “手里有什么”没有可信数据源和时间语义 |
| V2 `profile` claim | 已出现在 `knowledge_fallback.json` | `V2EpistemicKnowledgeStore` 明确以 `PROFILE_CLAIM` 丢弃；`role_constraint` 边也不能作为剧情事实注入 |
| V2 `story` claim | 已可检索、带角色认知和 cutoff 边界 | 只覆盖剧情，不能替代角色档案或当前物品 |
| V1 relationship | 已可检索 | 只覆盖“当前 NPC 对谁的关系/态度” |

结论：必须增加一条独立的**角色档案/状态检索路径**。不能简单删除 `claim.scope != "story"` 或 `beliefStatus != "believed"` 的过滤，否则 profile 约束、行为风格、剧情事实会混在同一语义中，破坏现有审计和知识边界。

## 3. 必须保持的安全与提示词边界

1. 角色档案、持有物、当前状态、关系和已授权剧情事实均为角色内部可信上下文，只能进入 system message。
2. 外部 RAG 文档仍由 `CharacterRagPromptBuilder` 放在 user message，并带“不可信指令”标识；不得转换为 profile、物品或角色记忆。
3. 时间相关资料必须经过 `storyCutoff` 和有效区间过滤；对“现在手里有什么”不能仅依赖不带时间范围的历史剧情。
4. `hard_constraints` 是始终生效的稳定约束，不应因某个问题不相关而被检索器丢弃。
5. 审计日志只能记录枚举、ID、数量、字符数和 reason code。完整 prompt/事实正文仅允许既有 debug 开发能力显示，不能进入 release 日志。
6. 无候选、候选不在当前时间有效、或预算不足时，system prompt 应明确保留“未记录/未知”，而不是补全为虚构物品或背景。

## 4. 目标架构

```text
MainActivity.compilePromptForHarness()
  -> AndroidRagOrchestrator.compile(CHARACTER_RAG)
    -> CharacterPromptCompiler.buildNpcPrompt()
      -> CharacterContextNeedRouter.decide(userInput)
      -> CharacterProfileStore.retrieve(...)       // 来历、身份、动机、价值、特征
      -> CharacterStateStore.retrieve(...)         // 场景、任务、目标、持有物
      -> V2EpistemicKnowledgeStore.retrieve(...)   // 剧情认知事实
      -> V1GraphKnowledgeStore.retrieve(...)       // 当前角色发出的关系边
      -> PromptBudgetAllocator.select(...)
      -> roleplay_system.prompt（system）
    -> CharacterRagPromptBuilder.build(...)
      -> 外部资料（如被外部路由选择，留在 user）
```

`CharacterContextNeedRouter` 只决定内部知识类别，不读取正文、不加载 assets、不调用模型。各 Store 负责候选及硬过滤；编译器是唯一 prompt 注入点。

## 5. 数据契约

### 5.1 新的运行时领域模型

在 `app/src/main/java/com/example/minicpm_v_demo/harness/character/context/` 新增纯 Kotlin 领域模型；名称可随现有包结构微调，但语义不可改变。

| 模型 | 关键字段 | 用途 |
| --- | --- | --- |
| `CharacterContextKind` | `PROFILE`, `STATE`, `POSSESSION`, `STORY_FACT`, `RELATIONSHIP` | 分开配额、审计和模板区块 |
| `CharacterContextItem` | `id`, `kind`, `text`, `keywords`, `validFrom`, `validTo`, `importance`, `source`, `confidence` | 单一可注入候选，不暴露原始 JSON/数据库行 |
| `CharacterContextQuery` | `characterId`, `storyCutoff`, `userInput`, `requestedKinds`, `limitByKind`, `charBudgetByKind` | Store 的输入契约 |
| `CharacterContextRetrieval` | `status`, `items`, `decisions`, `fallbackReason` | 与现有 V1/V2 审计形态一致 |
| `CharacterContextDecision` | `id`, `kind`, `disposition`, `reason`, `score?` | 区分未请求、无词法匹配、过期、无数据、预算淘汰 |

`CharacterContextItem.text` 必须是已经转换为第一人称可用语义的短句，或至少是无需暴露内部字段的事实句。不要在 prompt 内暴露 claim ID、证据路径、可信度数值或 schema 名称。

### 5.2 角色档案来源

第一版直接使用现有角色 JSON，避免先改数据库构建管线：

| JSON 来源 | `kind` | 例子 | 时间语义 |
| --- | --- | --- | --- |
| `identity_core.background` | `PROFILE` | “初入此世时曾长期藏于鉴身……” | 默认稳定；若后续有冲突，迁移为显式有效期 |
| `identity_core.core_motivations` | `PROFILE` | “查明法鉴和元府秘密……” | 默认稳定或由后续状态覆盖 |
| `identity_core.values` | `PROFILE` | “不以无辜者为资粮……” | 稳定 |
| `identity_core.psychological_traits` | `PROFILE` | “行事克制……” | 稳定；仅作回答立场参考 |
| `identity_core.hard_constraints` | 不作为普通候选 | 禁止身份切换、未来剧透等 | 始终渲染到稳定角色核 |

不要把 `speech_examples` 作为可回答事实检索。它们只承担语言风格，继续保留在稳定角色核。

### 5.3 新增持有物与状态数据

为当前物品建立独立资产，而不是把物品散落在 `background` 文本或依靠剧情关键词猜测。建议新增：

`app/src/main/assets/harness/characters/state/<characterId>.json`

```json
{
  "schema_version": "1.0",
  "character_id": "lu_jiangxian",
  "states": [
    {
      "state_id": "lu-evt-018",
      "valid_from": "evt-018",
      "valid_to": null,
      "scene": "……",
      "current_goal": "……",
      "possessions": [
        {
          "item_id": "mingyang-white-jade-sword",
          "name": "命阳白玉剑",
          "status": "随身/已使用/不在手边/未知",
          "description": "仅限本轮回答所需的短描述",
          "keywords": ["剑", "手里", "携带", "法器"]
        }
      ]
    }
  ]
}
```

约束：

- `possessions` 表示**当前可确认的持有状态**，不是人物一生曾接触过的物品清单。
- `valid_from`/`valid_to` 必填或显式继承，不能以“角色应该有”替代剧情证据。
- `status = unknown` 可记录“资料不确定”，但不可编成“正拿着某物”。
- 资产需配套 `evidence_refs` 或构建期可追溯来源；运行时不注入该路径。
- 第一版只增已有充分文本证据的物品；没有证据的角色允许空 `possessions`。

### 5.4 V2 profile 的处理

本阶段不修改 `V2EpistemicKnowledgeStore.retrieveAudited()` 对剧情检索的 `story` 限制。新增独立 `CharacterProfileStore` 从角色 JSON 提供可回答的 profile 候选。

后续若希望复用 V2 profile claim，单独新增 `V2ProfileStore`：

- 只接受 `claim_scope = profile`；
- 只接受当前角色的 `self_profile` 且经过白名单化的 profile 类型；
- 不把 `role_constraint` 当作一般事实回答，而是映射到稳定角色约束或行为风格；
- 返回独立 `PROFILE_*` reason code，绝不复用 `STORY_FACT` 结论。

只有在 JSON 与 V2 profile 的来源、版本和冲突优先级确定后，才进入该扩展；它不属于首个实现切片。

## 6. 查询路由规则

### 6.1 分类结果

`CharacterContextNeedRouter` 返回可组合的类别集合，而非单选 intent：

| 用户问题特征 | 请求类别 | 示例 |
| --- | --- | --- |
| 来历、过去、出身、从哪里来、为何成为这样 | `PROFILE` + 可选 `STORY_FACT` | “你来自哪里？” |
| 此刻、现在、正在做什么、接下来打算 | `STATE` | “你现在在哪里？” |
| 手里、带着、携带、法器、装备、有什么东西 | `POSSESSION` + `STATE` | “你手里有什么？” |
| 某人、关系、看法、态度 | `RELATIONSHIP`，必要时可并行 `PROFILE` | “你怎么看玄谙？” |
| 发生了什么、为何、真相、何时 | `STORY_FACT` | “元府为何崩坏？” |
| 闲聊、问候、感谢 | 无可选检索 | “晚安。” |
| 混合问法 | 多类别并行 | “你从哪里来，又为何带着这把剑？” |

路由必须保守：没有明确信号时可请求 `PROFILE` 的低预算候选，但不能因路由不确定而把所有类别全部注入。`userInput` 原文必须原样交给最终 user message；路由使用规范化副本。

### 6.2 信号与反例

- “你来自哪里”不能再被当前 `CasualOrIdentityQueries` 当作“你是谁”的等价问候；它是 `PROFILE`。
- “你手里有什么”必须请求 `POSSESSION`，即使故事事实检索没有词法命中。
- “你的剑从哪里来的”应请求 `POSSESSION + PROFILE + STORY_FACT`，但每类各自受预算限制。
- “你是谁”可以保持稳定身份核，不强制检索；若产品希望回答更具体，可请求 `PROFILE` 但限 1 项。
- 玩家要求“根据文档说明角色来自哪里”时，内部 `PROFILE` 与外部资料可以同时存在；外部资料仍不能覆盖角色内部身份。

## 7. Prompt 模板与预算

### 7.1 模板区块

在 `roleplay_system.prompt` 中保留原有稳定区块，并新增清晰、互不混淆的占位符：

```text
与你此问直接相关的来历、立场或经历：{{relevant_profile}}
此刻的处境、目标或状态：{{relevant_state}}
此刻可确认的随身物品：{{relevant_possessions}}
问到这些人时可依据：{{relevant_relationships}}
此刻确实知道的事：{{authorized_story_facts}}
```

无内容使用简短稳定值“无额外记录。”；对 `POSSESSION` 的无记录，使用“当前资料未记录可确认的随身物品。”，以避免模型把空值理解成“没有任何物品”。

### 7.2 推荐字符预算

保持总角色 system prompt 默认上限 900 字符，先给稳定核留出不可压缩预算，再为动态块分配独立上限：

| 区块 | 初始上限 | 选择规则 |
| --- | ---:| --- |
| stable core / hard constraints / style | 现有预算，必须保留 | 不能由动态候选挤掉 |
| profile | 120 | 默认最多 2 条，问题明确问来历时优先 |
| state | 90 | 最多 1 个当前状态 |
| possession | 100 | 最多 2 项；`unknown` 不作为肯定持有 |
| relationship | 96–160 | 复用现有 V1 规则 |
| story fact | 180 | 复用现有 V2 规则 |
| conversation summary | 仅使用剩余预算 | 不得挤占当前问题的动态证据 |

这只是首版配置，不是性能结论。`PromptBudgetAllocator` 应对同一输入稳定排序，优先级为：明确请求的类别 > 安全/时间有效 > 分数 > 重要度 > ID。若总预算不足，记录 `PROMPT_BUDGET` 原因；不能静默淘汰。

## 8. 分阶段实施

### Phase 0：冻结契约与样本（只写测试/资产草案）

目的：先固定语义，避免实现中把“角色档案”“剧情认知”“当前物品”混为一谈。

1. 在 `docs/rag/` 补充或维护本方案中的数据样例与字段说明。
2. 为六个现有角色列出最小验收问答：来历、动机、当前状态、物品、关系、剧情、无记录物品、混合问题。
3. 为每个候选物品写明 cutoff、证据、有效区间和“是否当前持有”。没有证据则不建资产。
4. 冻结 reason code 集合：`NOT_REQUESTED`、`NO_PROFILE_MATCH`、`NO_STATE_AT_CUTOFF`、`NO_POSSESSION_RECORDED`、`NOT_VALID_AT_CUTOFF`、`UNSUPPORTED_PROFILE_CONSTRAINT`、`RANK_LIMIT`、`PROMPT_BUDGET`、`SELECTED`。

验收：评审能仅依样例判断“你手里有什么”在无物品记录时应如何回答；不开始改生产 Kotlin。

### Phase 1：纯函数路由与角色档案检索

目的：让“来自哪里”等 profile 问题可稳定选出角色 JSON 的相关条目。

1. 新增 `CharacterContextNeedRouter` 及单元测试，输出类别集合和无正文 reason code。
2. 新增 `CharacterProfileStore`，将角色 JSON 的 `background`、`core_motivations`、`values`、`psychological_traits` 展开为候选项。
3. 复用连续汉字二/三元 gram 与现有归一化策略，避免中文依赖空格分词；为“来自哪里/出身/过往”等建立可测试的意图 boost，而不是只匹配“来”。
4. 角色档案条目默认不受剧情 cutoff 排除，但保留 `validFrom/validTo` 字段，供未来可变 profile 使用。
5. 不改 `V2EpistemicKnowledgeStore`，不改外部 RAG，暂不改 prompt 模板。

验收：`CharacterProfileStore` 对“陆江仙来自哪里”返回其 `background` 候选；闲聊不加载档案正文；相同输入的路由和排序稳定。

### Phase 2：状态与物品资产、时间过滤

目的：让“此刻”和“手里”有独立、可审计且不会幻觉的数据源。

1. 新增 state asset schema、解析器、`CharacterStateStore` 和构建期校验器。
2. 扩展 `AssetBootstrapper`/`HarnessDataPaths`，保证新增 assets 随 APK 更新；对 schema、角色 ID、cutoff、重复 item ID、无效时间范围 fail closed。
3. `CharacterStateStore` 按当前 cutoff 选择有效状态，再按 query 选择场景/目标/物品候选。
4. 明确没有物品记录的检索结果为 `READY + empty + NO_POSSESSION_RECORDED`，不是 store 不可用。
5. 初期只录入证据充分的少量物品和状态，不能为了覆盖率填充猜测数据。

验收：低于物品生效 cutoff 时不能注入；有效 cutoff 且问“手里有什么”能注入对应物品；无记录时不会出现任意历史物品。

### Phase 3：接入 `CharacterPromptCompiler` 与预算器

目的：在同一轮真实编译链中组合 profile/state/possession/V1/V2，同时保持 system/user 边界。

1. 在 `RuntimeContext` 增加可配置的 profile/state/possession topK 和字符预算，默认值采用第 7.2 节。
2. 扩展 `CharacterPromptDebug`，分项记录每类 `status`、候选数、选择数、注入字符、丢弃原因和 route reason code。
3. 让 `CharacterPromptCompiler.buildNpcPrompt()` 先取得路由决策，再调用对应 Store；不被请求的 Store 不读取正文。
4. 引入统一 `PromptBudgetAllocator` 代替散落的可选项排序，保证显式问题类别优先且可解释。
5. 扩展 `roleplay_system.prompt` 占位符并在 `render()` 中对未解析占位符 fail fast。
6. 继续保证 V2 剧情/V1 关系/角色档案/状态物品只在 system；外部 `CharacterRagPromptBuilder` 的资料仍只在 user。

验收：对同一角色，询问来历、物品、关系、剧情后，除稳定核与历史外 system prompt 的相应动态区块不同；闲聊不产生无关动态资料。

### Phase 4：可观测性、回归与真机验收

目的：证明实际 APK 而不是仅 JVM 测试会针对输入改变 prompt，且不以更长 prompt 换来不可接受时延。

1. 在 `MainActivity.compilePromptForHarness()` 增加仅含元数据的日志：内部 route、每类 status/selected/injected chars、总 system/user tokens、是否预算淘汰。
2. Debug 构建可继续通过既有 prompt 查看功能人工检查完整 system prompt；release 不输出正文。
3. 增加固定真机样本并清空会话后逐题运行：

| 类型 | 样例 | 必须观察到的动态区块 |
| --- | --- | --- |
| 来历 | “你来自哪里？” | `relevant_profile` |
| 动机 | “你为何要查元府？” | `relevant_profile`，必要时 `authorized_story_facts` |
| 状态 | “你现在在何处？” | `relevant_state` |
| 物品 | “你手里有什么？” | `relevant_possessions` 或“当前资料未记录” |
| 关系 | “你怎么看玄谙？” | `relevant_relationships` |
| 剧情 | “玄谙为何要毁掉明阳？” | `authorized_story_facts` |
| 闲聊 | “晚安。” | 不请求动态知识 |
| 混合 | “你从哪里来，又带着什么？” | profile + possession，均受预算 |

4. 每题至少记录：角色、cutoff、模型、route、各区块注入字符、system/user prompt tokens、prefill、TTFT、可见回答。不同问题上下文长度不同，只能报告功能样本和成本，不能称为性能 A/B。
5. 验证未来剧情、无物品记录、外部资料 prompt injection、连续追问和图谱关闭时的降级行为。

验收：真机日志和 prompt 视图均能证明每种问题类别触发对应动态区块；没有把外部资料写入 system；没有出现未来物品或角色未知事实。

## 9. 必需的 JVM 测试

1. `CharacterContextNeedRouterTest`
   - 来历、当前状态、物品、关系、剧情、闲聊、混合和外部资料请求。
   - 中文同义问法与标点/空白归一化。
2. `CharacterProfileStoreTest`
   - “来自哪里”命中 background；“为什么这样做”命中 motivation；不把 `hard_constraints` 当普通回答事实。
3. `CharacterStateStoreTest`
   - cutoff 前后、无物品、物品已失去/不在手边、重复 ID、无效 schema。
4. `CharacterPromptCompilerTest`
   - 每类事实只在 system；同角色不同问题生成不同相应区块；动态区块不突破 900 字符预算；预算淘汰有 reason code。
5. `AndroidRagOrchestratorTest`
   - 运行时 assets 路径可加载新 state asset；外部文档仍只在 user；普通 RAG 与 RAG OFF 不受影响。
6. 既有 V2/V1 回归
   - 未来剧情仍被拒绝；只注入当前 NPC 发出的关系；图谱不可用时旧 `story_events.jsonl` 回退仍生效。

## 10. 风险与决策门

| 风险 | 防护 |
| --- | --- |
| 为了动态化而把整张角色卡塞入 prompt | 分类别 topK/字符上限，固定稳定核预算，记录每类注入字符 |
| profile 与剧情事实冲突 | 第一版以角色 JSON 为稳定档案、V2 为时序剧情；冲突不自动合并，先记录并明确优先级 |
| 当前物品被历史剧情误当为正在持有 | 独立 `possessions` + 有效期，不从“曾出现过某剑”推断当前手持 |
| 不知道时模型编造 | 使用“当前资料未记录可确认的随身物品”提示，且输出仍受原有未知事实约束 |
| 路由词表扩大后误触发 | 先做纯函数测试和 reason code；只在明确问题上启用，逐步扩展同义词 |
| prompt 变长导致端侧变慢 | 真机记录 prompt tokens、prefill、TTFT；不以 JVM 成功宣称性能合格 |
| 误把外部资料固化为角色知识 | 内部 Store 不读取 `RagSearchService` 结果，外部文档继续仅在 user |

进入下一阶段的决策门：先完成 **Phase 0 + Phase 1**，以角色档案问答验证“不同问题得到不同动态 system 区块”。确认 profile 注入质量和预算后，再录入带时间语义的物品/状态资产，避免同时改变路由、数据模型、模板和性能变量。
