# Character RAG 按需知识库检索：实施计划

## 1. 目标、范围与结论

目标是在当前 `CHARACTER_RAG` 工作流中，让每轮问题先得到一个**可审计的检索决策**：

- 问候、闲聊、创作续写、角色态度表达等不依赖资料的问题，不启动外部知识库检索；
- 询问角色在当前剧情截止点可知的事实、关系或经历时，只检索角色知识图谱；
- 询问本地 RAG 文档中的外部事实时，才检索外部文档库；
- 同时涉及角色剧情和外部资料时，允许两路并行，但严格保持两种资料的提示词边界。

本计划只描述后续代码、测试和端侧验收；**本次不修改 Kotlin 运行逻辑、角色资产或 UI**。

这里的“知识库”必须拆成两类，不能由一个二元开关混用：

| 知识源 | 当前实现位置 | 适用问题 | 注入位置 | 不适用时的行为 |
| --- | --- | --- | --- | --- |
| 角色内部剧情知识 | `V2EpistemicKnowledgeStore` → `CharacterPromptCompiler` | “你知道什么”“你怎么看某人”“当时发生了什么” | system（已授权事实） | 不查；保留角色身份、边界和“不确定时不要补全”约束 |
| 外部文档 RAG | `RagSearchService` → `CharacterRagPromptBuilder` | “根据文档/新闻/世界杯资料……”“搜索本地资料……” | user（明确标注为不可信资料） | 不查；user 保持原始玩家提问 |

`CharacterPromptCompiler` 中的身份、性格、知识边界和会话摘要不是可选 RAG，任何路由结果都必须保留。已授权的内部剧情事实也不得当成 `RagSource` 或混进外部资料块。

## 2. 已核对的当前调用链与缺口

当前的 `AndroidRagOrchestrator.compile()` 在 `CHARACTER_RAG` 分支执行：

```text
玩家问题
  ├─ CharacterQueryRouter.requiresExternalKnowledge()
  │    └─ 命中固定关键词时：RagSearchService.search() → 外部资料 user message
  └─ CharacterPromptCompiler.buildNpcPrompt()
       └─ V2 角色图谱（可用时）或 story_events 回退 → system message
```

目前 `CharacterQueryRouter` 仅检查 `外部资料、文档、文件、新闻、现实、世界杯、联网、搜索`。
因此它能跳过一部分闲聊的外部检索，却有四个不足：

1. 不区分“无需任何检索”“只需角色图谱”“只需外部文档”和“双源问题”。
2. “世界杯决赛有什么争议？”这类本地语料可回答的问题没有显式触发词时可能漏检；反之仅出现“新闻”字样的创作请求可能误检。
3. 路由决策不进入 `CompiledAndroidPrompt`、日志或测试断言，现场无法解释为什么查了或没查。
4. 外部 RAG 的候选扩张、阈值过滤与 `topK` 选择都发生在路由之后；无命中、低分和“路由跳过”三种情况没有清晰区分。

现有角色知识图谱已经有独立的时间、认知和审核硬过滤，以及 `story_events.jsonl` 回退。新路由不能绕过这些过滤，亦不能用“无需检索”来放宽角色知识边界。

## 3. 目标工作流

采用**规则优先、两路独立、默认不查外部资料**的本地路由。首版不调用模型来决定是否检索，避免在端侧额外增加一次推理、不可复现的路由结果和 prompt 注入面。

```text
用户问题 + characterId + storyCutoff + 当前 CHARACTER_RAG
                         |
                         v
                 KnowledgeNeedRouter
                         |
       +-----------------+------------------+
       |                                    |
角色剧情检索计划                       外部文档检索计划
NONE / GRAPH                           NONE / EXTERNAL
       |                                    |
V2 硬过滤、预算、story 回退             向量召回、最低分、上下文预算
       |                                    |
可信剧情事实 → system                   不可信文档块 → user
       \                                    /
        \---------- ChatTemplateRenderer --/
```

路由输出不是 `Boolean`，而是下列不可变数据；这样同一问题可以同时选择两路：

```kotlin
enum class CharacterKnowledgePlan { NONE, GRAPH }
enum class ExternalKnowledgePlan { NONE, EXTERNAL }

data class KnowledgeRouteDecision(
    val characterPlan: CharacterKnowledgePlan,
    val externalPlan: ExternalKnowledgePlan,
    val reasonCodes: List<String>,
    val normalizedQuery: String,
)
```

`reasonCodes` 只能记录规则 ID、来源类别和分数区间，不能记录完整玩家问题、文档正文或私密剧情事实。例如：`external_explicit_document`、`external_corpus_entity`、`character_relation_question`、`smalltalk_no_evidence_needed`、`route_conflict_external_wins`。

### 3.1 决策表

| 问题类别 | 图谱 | 外部文档 | 示例 | 原因 |
| --- | --- | --- | --- | --- |
| 问候/闲聊/纯角色表达 | NONE | NONE | “你好”“你现在心情如何”“用你的口吻写一句话” | 回答只需角色卡和对话上下文 |
| 角色身份或已写入角色卡的稳定设定 | NONE | NONE | “你是谁”“你的性格是什么” | 不应为角色卡已有内容付出检索成本 |
| 剧情、人物关系、角色所见所闻 | GRAPH | NONE | “你怎么看玄谙？”“元府当年如何建立？” | 需要按角色/cutoff 过滤的内部事实 |
| 明示外部资料/本地文档/搜索意图 | NONE 或 GRAPH | EXTERNAL | “根据世界杯文档说明决赛争议” | 外部语料属于不可信输入，不可写入角色记忆 |
| 角色剧情 + 外部资料的比较/解释 | GRAPH | EXTERNAL | “结合资料解释这件事对你意味着什么” | 两类证据分别取得、分别注入 |
| 资料无法判定或无有效命中 | 按角色问题规则 | NONE（或 EXTERNAL 后空结果） | “你知道 X 吗？” | 不能用猜测强行检索；角色应承认当前未知 |

当外部资料显式请求和“纯角色扮演”信号冲突时，外部资料请求优先；当用户未明确请求外部资料、但只出现泛化事实问句时，首版采用可解释的本地语料主题/实体命中规则，而不是默认全量检索。

## 4. 分阶段实施

### Phase 1：冻结路由契约与纯函数规则

**目的：** 先让“查不查、查哪一种、为什么”可单元测试，不改现有检索或提示词组装。

新增 `app/src/main/java/com/example/minicpm_v_demo/harness/rag/KnowledgeNeedRouter.kt` 和相邻的领域模型文件：

1. 规范化输入：trim、Unicode 空白折叠、中文/英文大小写归一；保留原始 `userInput` 给最终 prompt，不能用规范化文本替代用户原文。
2. 定义可审计的正向规则：
   - 外部显式词：`文档`、`资料`、`根据文件`、`本地知识库`、`检索`、`搜索`、`新闻`、`现实世界`；
   - 角色剧情问法：人物名/别名 + `关系、怎么看、发生、当时、为何、是否知道、亲历、听说`；
   - 外部语料主题/实体：从 `documents_manifest.json` 或扩展后的索引元数据读取，首版仅使用已知 `world_cup` 类别和稳定别名，不能扫描文档正文作路由；
   - 无证据需求：短问候、感谢、表达情绪、格式/文风改写、明确“不要查资料”。
3. 定义优先级：显式“不查资料”只抑制外部检索，不抑制为防剧透所必需的角色知识边界；显式“查/根据文档”高于无证据信号；角色关系/剧情和外部请求可同时成立。
4. 路由器不访问 `RagSearchService`、不读图谱正文、不写日志；它是确定性、无 Android 依赖的纯函数。
5. 把旧 `CharacterQueryRouter` 标注为迁移目标，Phase 1 完成后仅由新路由的 `externalPlan` 替代它，避免双重关键词判定。

验收：相同输入必得相同 `KnowledgeRouteDecision`；测试可断言 reason code；router 不加载索引、不创建 embedding、不依赖设备或模型。

### Phase 2：接入编排器，但保持检索结果和提示词边界不变

**目的：** 以最小改动将决策真正用于 `CHARACTER_RAG` 调用链。

修改 `AndroidRagOrchestrator.compile()`：

1. 在 `CHARACTER_RAG` 分支开头执行一次 `KnowledgeNeedRouter.route(userInput, characterId, storyCutoff)`。
2. 只有 `externalPlan == EXTERNAL` 时调用当前的 `RagSearchService.search()`；保留现有的 `topK * 8` 候选扩张、`CHARACTER_EXTERNAL_RAG_MIN_SCORE`、`novel_test` 排除和最终 `take(topK)`，第一阶段不调整检索质量参数。
3. 只有 `characterPlan == GRAPH` 时，让 `RuntimeContext` 显式携带 `knowledgeGraphQueryEnabled = true`，由 `CharacterPromptCompiler` 调用现有 V2/回退内部知识链；`NONE` 时跳过该次事实召回，但仍生成身份、知识边界、会话摘要和用户消息。
4. `CharacterRagPromptBuilder` 继续把外部结果放入 user message，且仅在 `results.isNotEmpty()` 时添加“外部资料仅供本轮参考”约束。内部图谱事实仍只可能出现在 system message。
5. 扩展 `CompiledAndroidPrompt`，增加 `knowledgeRoute: KnowledgeRouteDecision?`；`RAG` 和 `OFF` 模式可为 `null`，避免误把通用 RAG 的“必检索”流程伪装成角色路由。

需要同步调整 `RuntimeContext`、`CharacterPromptDebug` 的构造点和 JVM fixture，使“图谱被跳过”与“图谱不可用/无命中后回退”是不同状态。建议 debug 状态为：`not_requested`、`ready_selected`、`ready_no_match`、`unavailable_fallback`、`disabled`。

验收：`NONE/NONE` 不调用 `RagSearchService`，生成的 user prompt 与原问题相同；`GRAPH/NONE` 不产生 `RagSource`；`NONE/EXTERNAL` 不将外部原文写入 system；`GRAPH/EXTERNAL` 同时保持两个边界。

### Phase 3：可维护的路由词表和索引元数据

**目的：** 避免把领域词硬编码在 Kotlin，使新增知识库时能同步更新路由而不重训模型。

1. 在 `app/src/main/assets/harness/rag/` 新增版本化的 `routing_manifest.json`，包含每个资料集的 `datasetId`、`aliases`、`topicTerms`、`enabled` 和版本；不包含文档内容和不可信指令。
2. 扩展 `AssetBootstrapper`/`HarnessDataPaths`，使 manifest 与 `rag/index.json` 同步复制、校验并可回滚。manifest 损坏、版本不匹配或不存在时，禁用“语料主题自动触发”，但保留明确的“查文档/搜索”请求。
3. 为 `KnowledgeNeedRouter` 注入只读 `RoutingCatalog`；加载失败输出 `routing_catalog_unavailable`，不阻塞聊天、不自动扩大到全库检索。
4. 角色姓名和别名从 `CharacterJsonLoader` 提供的角色卡读取，避免新角色加入后路由器漏识别；不能把角色卡中的全部背景文本作为关键词表。
5. 首版不提供用户可见的“自动/始终检索”切换。需要调试时只在 debug build 增加一次性 route override，且不能持久化为生产默认值。

验收：新增一个资料集只需更新其索引和 `routing_manifest.json`；损坏 catalog 不会导致 RAG 崩溃或将无关资料注入 prompt。

### Phase 4：测试、观测与端侧验收

#### JVM 测试

新增 `KnowledgeNeedRouterTest.kt`，并扩展 `AndroidRagOrchestratorTest.kt`：

1. “你好”“用陆江仙口吻写一句鼓励” → `NONE/NONE`，外部 source 和 context 为空。
2. “你怎么看玄谙？”在有效 cutoff → `GRAPH/NONE`；system 可含已授权剧情，user 不含资料块。
3. “根据世界杯文档说明决赛争议” → `NONE/EXTERNAL`；若命中，资料只在 user；若无命中，记录 `external_searched_no_results`，不伪装为路由跳过。
4. “结合文档说说玄谙的选择” → `GRAPH/EXTERNAL`；验证 V2 未来知识仍不能进入 system。
5. “不要查资料，和我聊聊” → 外部 `NONE`；身份/边界不丢失。
6. 显式外部请求、资料 catalog 缺失、低分结果、图谱不可用、图谱无命中分别有不同 reason/status。
7. 回归最终 `splitPrompt`：system 不含外部原文；user 不含内部 claim ID、审核状态、筛选理由或 V2 私密事实。
8. 回归既有 `RagMode.RAG`：它仍按原语义直接检索，不经 `KnowledgeNeedRouter`。

#### 观测

在 `MainActivity.compilePromptForHarness()` 记录聚合字段：`route_character_plan`、`route_external_plan`、reason code、外部候选数/最终 source 数、图谱状态、system/user 字符数与 token 估算。不得记录完整问题、文档正文、内部事实正文或渲染后的完整 prompt 到生产日志。

#### Android 设备验收

1. 运行 `sh gradlew :app:testDebugUnitTest`、`sh gradlew :app:assembleDebug` 和 `git diff --check`。
2. 在 ARM64 模拟器或真机上，以同一模型、同一角色、同一 cutoff 和相同 `predictLength`，分别验证 NONE/NONE、GRAPH/NONE、NONE/EXTERNAL、GRAPH/EXTERNAL 四条路径。
3. 每条路径至少记录：路由结果、是否真的执行外部检索、最终 source 数、prompt token、prefill、TTFT、首个可见字时间和完整输出。不同 route 的上下文长度不同，只能报告功能/成本样本，不能据单次数据宣称性能 A/B。
4. 人工检查：未来剧情诱导、外部资料 prompt injection、未知事实追问和连续对话下，角色没有把外部文档当作自身记忆，也没有因跳过检索而越权补全事实。

## 5. 风险、降级与完成定义

| 风险 | 控制方式 |
| --- | --- |
| 关键词路由漏检或误检 | 规则和 reason code 单测；明确请求优先；资料主题从版本化 catalog 读取；以真实查询集迭代词表 |
| 为了省检索而削弱防剧透 | 跳过的是事实召回，不是角色身份/知识边界；V2 硬过滤仍是唯一允许内部事实进入 system 的路径 |
| 外部文档污染角色记忆 | 两路独立决策、独立结果类型；外部文档固定留在 user，绝不回写角色知识库或会话摘要 |
| catalog 损坏导致聊天不可用 | fail-soft：禁用自动主题触发，明确外部请求仍可检索，角色基础对话继续可用 |
| 日志泄露玩家或剧情内容 | 仅记录枚举、reason code、数量和长度；debug 显示完整 prompt 的既有能力必须保持开发环境限定 |

完成不等于“新增了几个关键词”。完成标准是：四种路由组合均可在真实 `CHARACTER_RAG` 调用链中复现；每次检索/跳过都有可读原因；角色内部知识和外部文档始终分处 system/user；构建、单元测试和至少一次端侧四路径功能验证通过。

建议实施顺序：**Phase 1 → Phase 2 → JVM 回归 → Phase 3 → 端侧验收**。先落地纯规则和调用链，再扩大资料集和词表，便于定位每次变化对召回、角色一致性与端侧时延的影响。
