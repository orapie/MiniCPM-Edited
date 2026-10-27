# RAG 工作逻辑与运行流程

本文描述当前 Android 工程中已经接入运行时的 RAG（Retrieval-Augmented Generation）链路。它以代码为准，覆盖普通文档 RAG、角色卡 RAG、角色知识图谱、当前状态/物品、会话承接、提示词拼装与运行期安全边界。

> 适用范围：`app/src/main/java/com/example/minicpm_v_demo/harness/` 与 `app/src/main/assets/harness/`。本文不把计划文档中的未来设计当作已运行能力。
>
> 核对日期：2026-10-27。本文记录**当前实际行为，包括已知误判**；文末的修复方向不代表代码已经修复。

使用者可按需要跳转：

- [三种模式和检索开关](#routing-modes)：先确定本轮走哪个入口。
- [角色问题完整路由规则](#routing-rules)：关键词、判断顺序、例外与资料来源。
- [问题示例对照](#routing-examples)：了解同义问法为什么可能走不同路径。
- [已确认的路由缺陷](#routing-known-issues)：人物关系被当作定义问题等现状。
- [Logcat 判读](#routing-debug)：区分未查询、查询无结果、未注入、被拦截。

## 1. 总览

应用有三种模式：`OFF`、`RAG` 和 `CHARACTER_RAG`。它们不是同一份提示词上简单加减资料，而是三条不同的编译路径。

```text
玩家输入
  │
  ├─ OFF ──────────────────────> 原始用户输入直送（MainActivity 提前返回）
  │
  ├─ RAG ──────────────────────> 文档向量检索 → 资料压缩 → user 资料块
  │                                  │
  │                                  └──────────────> 固定 RAG system
  │
  └─ CHARACTER_RAG ────────────> 外部向量索引每轮查询（默认开启）
                                      │
                         外部关键词仅决定是否使用召回结果
                                      │
                              角色问题分析与资料选择
                                      │
              ┌───────────────────────┼────────────────────────┐
              │                       │                        │
       角色卡/状态/物品          V2/V1/旧事件              外部资料（可选）
              │                       │                        │
              └─────> 角色 system <───┘                     user
                            │                                  │
                            └───────── 消息编译/渲染 ──────────┘
                                            │
                           生成前 gate → 模型/固定回复 → 输出 guard
```

重要结论：**“调用检索”“返回授权候选”“资料进入 prompt”“模型实际生成”是四个不同步骤。** 角色内部事实经过授权与预算后进入 system；外部文档进入 user。身份、语言风格和规则属于固定部分，每轮可能大段相同；可变的是角色证据区和外部资料区。

### 1.1 外部向量与 V1/V2 的区别

| 路径 | 数据与查询方法 | 调用条件 | prompt 位置 |
| --- | --- | --- | --- |
| 外部向量索引 | 本地文档切片 + 384 维 hash n-gram 向量；余弦相似度搜索。当前索引包含小说和世界杯测试资料。 | `RAG` / `CHARACTER_RAG` 默认每轮查询；角色模式另做注入判定。 | user；角色模式明确标为不可信外部资料。 |
| V2 剧情知识 | 事实、角色认知边与时间信息；中文 2/3-gram 召回，再做授权过滤。 | 请求 `STORY_FACT`，且图谱开关开启、store 已配置。 | system 的 `authorized_story_facts`。 |
| V1 claim | `claims.jsonl` 的剧情事件事实；文本 n-gram 匹配。 | 请求 `STORY_FACT` 且 V2 未返回授权事实时回退。 | system 的 `authorized_story_facts`。 |
| V1 relationship | `relations.jsonl` 中当前角色出发的有向关系；目标名与关系意图匹配。 | 请求 `RELATIONSHIP`，且图谱开关开启、store 已配置。 | system 的 `relevant_relationships`。 |

“外部”表示角色可信知识体系以外的参考文档，不表示互联网服务。V1/V2 当前并不是对外部向量索引的两次查询。同一段小说可以同时存在于文档索引和经过整理的角色事实库，但文档命中不会自动赋予角色该项认知。

## 2. 数据、安装与主要组件

### 2.1 打包资源与运行时目录

源资源根目录是 `app/src/main/assets/harness/`，启动时由 `AssetBootstrapper` 复制到：

```text
<Android filesDir>/harness/
├── rag/index.json
├── characters/
│   ├── characters/*.json
│   ├── story/story_events.jsonl
│   ├── state/*.json
│   ├── prompts/roleplay_system.prompt
│   └── knowledge/
│       ├── v1/{manifest.json, claims.jsonl, relations.jsonl}
│       └── v2/{manifest.json, character_knowledge_v2.sqlite3, knowledge_fallback.json}
├── sessions/recent_session.json
├── memory/
├── diagnostics/rag_turn_trace.jsonl
└── settings/
```

`AssetBootstrapper` 对 V1/V2 知识包单独安装：先复制到临时目录、校验结构和哈希，再原子替换目标目录。每个知识包的 `.asset_hashes` 防止因资源更新、安装中断或旧文件残留而混用版本。

外部向量索引的工程文件是 [`app/src/main/assets/harness/rag/index.json`](../app/src/main/assets/harness/rag/index.json)，原始文档在 [`rag/documents/`](../app/src/main/assets/harness/rag/documents/)。App 实际读取 `<filesDir>/harness/rag/index.json`，而不是直接读取开发电脑上的 assets。

### 2.2 关键类职责

| 组件 | 责任 |
| --- | --- |
| `MainActivity` | 接收输入、解析上一轮承接目标、调用编译器、执行生成、保存回合、记录日志。 |
| `AndroidRagOrchestrator` | 三种模式的总分流点。 |
| `RagSearchService` / `VectorIndex` | 普通文档索引加载、向量相似度检索。 |
| `AlwaysOnVectorRetrievalService` | 每个非 OFF 回合的向量查询与失败记录；与是否注入分开。 |
| `PromptBuilder` | 普通 RAG 的 system/user 消息和上下文预算。 |
| `CharacterQueryAnalyzer` | 关键词分析，输出意图集合、请求资料面和解释码。 |
| `CharacterPromptCompiler` | 角色内部检索、知识边界过滤、角色 system prompt 编译。 |
| `CharacterRagPromptBuilder` | 为角色模式可选地追加不可信外部资料。 |
| `ChatTemplateRenderer` | 将两条消息适配为 Llama、Qwen/ChatML、MiniCPM 等聊天模板。 |
| `RoleplayAnswerabilityGate` | 阻止只命中“角色在截止点后才知道”的未来事实。 |
| `RoleplayOutputObserver` | 生成后观测日志；不修改输出。 |
| `RoleplayGenerationCoordinator` / `RoleplayOutputGuard` | 角色回答检查，最多修复重试一次，必要时回退。 |
| `RagTurnTrace` / `RagPromptLogcatPrinter` | 无正文审计记录 / Debug 原文阶段日志。 |

<a id="routing-modes"></a>

## 3. 请求入口与模式分流

`MainActivity.compilePromptForHarness()` 首先判断模式。`OFF` 直接返回只有原始 user 输入的 `HarnessChatRequest`，不调用编排器；编排器尚未初始化时也会提前返回。其余模式解析 follow-up 后调用：

```kotlin
orchestrator.compile(
    userInput = userMsg,
    mode = currentRagMode,
    characterId = currentCharacterId,
    storyCutoff = currentStoryCutoff,
    conversationSummary = currentSession?.conversationSummary.orEmpty(),
    knowledgeGraphEnabled = characterKnowledgeGraphEnabled,
    followUpTarget = followUpTarget,
    modelFamily = harness.getSelectedModelSpec().family,
)
```

`AndroidRagOrchestrator.compile()` 随后分流：

| 模式 | 消息内容 | 是否查 `rag/index.json` | 是否使用角色知识 |
| --- | --- | --- | --- |
| `OFF` | App 主入口直接发送原始问题，不在此处构建新的 system | 否 | 否 |
| `RAG` | 固定 RAG 约束 + 包含 `<检索资料>` 的 user 消息 | 是，每轮 | 否 |
| `CHARACTER_RAG` | 角色 system + 原始问题，必要时 user 前缀附外部资料 | 是，每轮；外部关键词只控制注入 | 按 `requestedSurfaces` 选择，不是每轮查询所有角色库 |

上表的“每轮”以 `alwaysOnVectorRetrieval=true` 为前提。`VectorRetrievalFeatureFlags` 从 SharedPreferences `rag_retrieval_flags` 读取开关：

- `always_on_vector_retrieval` 默认 `true`；关闭后，非 OFF 回合的 trace 显示 `vectorExecuted=false`、`backend=disabled_by_flag`。
- `retrieve_in_off_mode` 默认 `false`；编排器直接调用支持 OFF 诊断查询，但 App 主入口在 OFF 已提前返回，单独开启它不会让普通聊天走 RAG。
- `currentRagMode` 初始化为 `OFF`；页面重新创建后应重新确认模式。角色图谱开关仅影响 V1/V2，不关闭外部向量、角色卡或旧事件回退。

直接在测试中调用 `compile(mode=OFF)` 时，编排器会构建“你是一个本地助手。”的消息；这与上述 App OFF 入口不同。

`RAG` 与 `CHARACTER_RAG` 通过 `ChatTemplateRenderer` 输出严格的两条消息（system、user），再由 `HarnessChatRequest` 分别提交。`FINAL_RENDERED` 是预览字符串，不等于 native 最终 token 序列；后续 gate 也可能让该回合直接使用固定回复。

## 4. 普通文档 RAG

### 4.1 索引和向量表示

普通 RAG 索引为 `rag/index.json`，schema 版本必须是 `1.1`。每项包含：

```text
chunk.id / chunk.text / chunk.source_path / chunk.start / chunk.end / metadata / embedding
```

当前运行时嵌入不是网络模型，而是 `HashEmbeddingModel`：

- 清除空白并转小写；
- 对文本生成 2～4 字符 n-gram；
- 使用 BLAKE2b-64 将 n-gram 映射到 384 维带符号桶；
- L2 归一化；
- `VectorIndex` 使用余弦相似度排序。

这意味着它轻量、离线且可复现，但对同义改写和跨语言语义的理解弱于专用 embedding 模型。索引生成参数记录在 `manifest.json`：900 字符分块、120 字符重叠，支持 txt/md/json/jsonl/csv/pdf/docx 等来源。

### 4.2 检索、阈值与重排

普通 RAG 对每轮输入调用：

```kotlin
// AndroidRagOrchestrator -> AlwaysOnVectorRetrievalService -> RagSearchService
searchAlways(query = userInput, candidateLimit = topK, minScore = 0.10)
```

App 使用编排器默认 `topK=1`。`VectorIndex` 先以余弦相似度取得 top-K，`RagSearchService` 再过滤低于阈值的结果，因此可能返回 0 条；`PromptBuilder` 再以：

```text
最终排序分 = 向量分数 + ContextController.queryBoost(问题, chunk 文本)
```

其中 `queryBoost` 将中文文本拆为相邻 2 字片段和完整的 3 字及以上片段，按两边交集比例加分。它是轻量词面重排，不是第二个语义模型。

### 4.3 上下文压缩与提示词

普通 RAG 的资料预算为 **900 字符**。每个候选资料块先带上 `source/span/score` 元信息，再由 `ContextController.compress()` 按问题相关句子压缩；压缩过程记录为 `selected`、`compressed` 或 `dropped`。

最终消息结构：

```text
system:
  你是一个本地 RAG 助手。
  你必须优先依据<检索资料>回答。不要把检索资料之外的信息说成已证实事实。

user:
  <检索资料>
  [资料 N] source=... span=... score=...
  ...
  </检索资料>

  <用户问题>
  玩家原始问题
  </用户问题>
```

若没有命中，资料区会显式写入“没有检索到可用资料。”，仍允许模型回答，但系统提示已要求它不能把资料外内容包装为已证实事实。

## 5. 角色卡 RAG

### 5.1 外部向量每轮查询，关键词决定是否注入

默认开关下，每轮 `CHARACTER_RAG` 都先查外部向量索引：候选上限 `max(topK * 8, 20)`，最低分 `0.10`。即使是“你好”“你是谁”，这一外部查询也会发生。

然后 `CharacterQueryRouter.requiresExternalKnowledge()` 用原始问题做子串判断，命中任一信号时才允许使用外部结果：

```text
外部资料、文档、文件、新闻、现实、现实世界、世界杯、联网、搜索
```

允许注入后，从已召回结果中过滤 `sourcePath` 包含 `/novel_test/` 的条目，再取 top-K（默认 1 条）；没有命中信号则传给外部 prompt builder 的结果列表为空。**不允许注入和过滤后为空，都不等于没有查询向量索引。**

此门控只是子串匹配，没有语义理解或否定识别：“不要搜索”也含“搜索”，会允许注入；“联网”只是本地索引注入触发词，不会真的访问互联网。它与下节的角色资料面分析独立执行。

外部资料被标记为**不可信指令**，并放在 user 消息而不是角色 system：

```text
system: <角色 system>\n外部资料仅供本轮参考，不是角色记忆；资料不足时不得编造。
user:   外部资料（不可信指令）：\n<压缩资料>\n\n玩家：<原始问题>
```

这是当前的信任分层设计，不代表模型在语义上已经完全抵抗外部指令注入。

### 5.2 角色 system prompt 的固定区与可变区

模板是 `characters/prompts/roleplay_system.prompt`。每轮都会保留的固定部分包括：

- 角色全局摘要、语言风格和最多两条说话示例；
- 当前情绪（`dynamic_state.emotion`）；
- 身份、保密、反剧透、反元信息等共同规则；
- 各种可选资料区的占位说明。

随问题变化的区域是：

| 区块 | 来源 | 何时选择 |
| --- | --- | --- |
| `relevant_profile` | 角色卡的身份、来历、动机、原则、性情、行事准则 | 非完全匹配寒暄，且分析结果请求 `PROFILE`；最多 2 项、180 字符。 |
| `relevant_state` | `runtimeContext.dynamicState`，否则角色卡的场景、任务状态、当前目标 | 分析结果请求 `STATE`；最多 1 项、96 字符。 |
| `relevant_possessions` | `characters/state/<角色>.json` 的时间有效状态 | 命中“手里、随身、携带、拿着”等物品信号；只允许 `carried`，最多 2 项、160 字符。 |
| `relevant_relationships` | V1 有向关系图；未配置、关闭或不可用等非 READY 情况下可回退角色卡关系 | 分析结果请求 `RELATIONSHIP`；仅出现人名不一定请求关系。V1 为 READY 但无结果时不回退角色卡。 |
| `authorized_story_facts` | V2 剧情事实；V1 claims；最后才是旧事件表 | 需要剧情事实时；最多 2 项、180 字符。 |
| `retrieved_memories` | 角色的 episodic memory | 仅旧事件表回退成功且对应记忆仍在有效期时。 |
| `conversation_summary` | 已保存会话的摘要 | 在固定区剩余预算内按当前问题压缩。 |

所有可选项先按“专用信号权重 + 词面相关度”排序，再逐项试填 system。若加入后超过 `RuntimeContext.maxChars` 则丢弃并记录原因；这保证 system 上限，而不是简单截断半条事实。

<a id="routing-rules"></a>

### 5.3 角色问题路由规则（按当前代码）

路由由 [`CharacterQueryAnalyzer`](../app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterQueryAnalyzer.kt) 分析，再由 `CharacterPromptCompiler.planKnowledgeQuery()` 转成检索计划。它是确定性关键词规则，不调用 LLM 分类，也不会因为“数据库存在相关内容”就自动选择该库。

#### 5.3.1 三类输出与检索开关

| 输出 | 含义 | 调试位置 |
| --- | --- | --- |
| `intents` | 可以同时命中的细分意图，如 `ORIGIN`、`STATE_GOAL`、`STORY_EVENT`。 | `RAG_TRACE character=...intents=`；Prompt 诊断窗口。 |
| `requestedSurfaces` | 本轮请求哪些资料面，直接决定角色检索开关。 | `RAG_TRACE ...surfaces=`。 |
| `explanationCodes` | 如 `definition_signal`、`current_goal_signal`，说明是哪种判断触发。 | Prompt 诊断窗口 `routeCodes:`；JSONL `queryExplanationCodes`。 |

资料面与执行条件如下，所有“请求”仍需后续授权和预算检查：

| 资料面 | 对应检索 | 实际调用条件 |
| --- | --- | --- |
| `PROFILE` | 当前角色卡原子资料 | 请求 PROFILE，且不是完全匹配寒暄。 |
| `STATE` | 当前角色状态字段 | 请求 STATE；按场景/任务/目标信号选取。 |
| `POSSESSION` | 时间有效的 carried 物品 | 请求 POSSESSION，且 `CharacterStateLoader` 找到当前状态。 |
| `RELATIONSHIP` | V1 有向关系，必要时角色卡回退 | 请求 RELATIONSHIP；V1 调用还要求图谱开关开启、store 存在。 |
| `STORY_FACT` | V2 → V1 claim → legacy | 请求 STORY_FACT；V2/V1 还要求图谱开关开启、store 存在。V2 返回授权事实时跳过 V1 claim；两者都无事实才走 legacy。 |

**不是所有非寒暄问题都会默认查 V2/V1。** 没有匹配任何信号时，输出 `UNKNOWN`，资料面为空，仍只保留角色固定区；外部向量查询照常按 5.1 执行。

#### 5.3.2 完整触发词

“完全匹配”先转小写、删除代码列出的空白和标点；其他下表信号主要对**原始输入做子串匹配**。完全匹配的“你好”与“你好，元府后来发生什么”不是同一路由。下表以当前 Kotlin 列表为准，不包含自然语言同义词推断。

| 意图/信号组 | 当前完整词表或条件 | 请求资料面 |
| --- | --- | --- |
| `CHITCHAT` | 完全匹配：你好、您好、嗨、哈喽、在吗、谢谢、再见、晚安、早上好 | 无 |
| `IDENTITY` | 完全匹配：你是谁、你都知道什么、你知道什么、你能做什么 | PROFILE |
| `ORIGIN` | 来历、出身、从哪里、从何、来自、何处而来 | PROFILE |
| `STATE_GOAL` | 接下来、下一步、当前目标、准备、要做、想做；或同时含“现在”与“想去/要去/准备去”之一 | STATE |
| `STATE_SCENE` | 此刻、在哪里、何处、位置、身在、场景 | STATE |
| `STATE_QUEST` | 正在做什么、在做什么、任务、进展、状态 | STATE |
| `POSSESSION` | 手里、手中、随身、携带、带着、带了、拿着、持有、持着 | POSSESSION |
| `PREFERENCE` | 最想、想去、更愿意、宁愿、偏好、喜欢什么、想要什么；且不满足上面的当前目标判断 | PROFILE |
| 自身动机组 | 你为什么、你为何、你的目的、你有何目的、你有什么目的、你的动机、你打算、你计划、你想要什么、你想做什么 | 无具体旧事动作时添加 MOTIVATION / PROFILE；具体旧事动作见下一行 |
| 具体旧事动作组 | 停止、拼合、拒绝、离开、建立、毁掉、杀、救、背叛、隐瞒 | 必须同时命中自身动机组，才作为 `specific_story_action_signal`，并参与剧情判断；单独出现这些动作不保证请求 STORY_FACT |
| `VALUES` | 原则、立场、底线、在乎、同意、拒绝 | PROFILE |
| `TRAITS` | 性格、怎样的人、习惯、害怕、讨厌、喜欢 | PROFILE |
| 定义组 | 是什么、究竟是什么、到底是什么、是谁、何人、何物、什么关系、有何关系、有什么关系、指什么；完全匹配身份问句除外 | 直接请求 STORY_FACT |
| 关系组 | 关系、怎么看、如何看、态度；或“已知角色名/别名 + 看法/想法/评价/信任/讨厌/喜欢/恨/怕” | **定义组未成立**时才请求 RELATIONSHIP |
| 普通剧情组 | 发生、做过、为何、为什么、原因、真相、事件、经历、过去、后来、何时、哪里、最后、建立、成员、哪些、如何 | 还需通过下节的排除条件才请求 STORY_FACT |

“已知角色名/别名”取自当前已加载角色卡的姓名和 aliases，不是任意命名实体。只提到“玄谙”而没有关系/态度信号，不会自动触发 V1 关系。

#### 5.3.3 判断顺序、并行与优先级

同一输入会按顺序累积多个 intents/surfaces，不是命中第一条就返回。当前关键逻辑如下：

1. 判断完全匹配寒暄/身份，并检查来历信号。
2. 检查当前目标、场景、任务和物品信号，先填入 STATE/POSSESSION。
3. 检查偏好、自身动机、具体旧事动作、原则和性情。当前目标会抑制 PREFERENCE；“自身动机 + 具体旧事动作”会抑制 MOTIVATION，给剧情事实检索机会。
4. 检查定义词。定义判断为真时，**不执行关系意图分支**；这包含“什么关系”这一已知冲突。
5. 判断剧情事实。定义问题直接成立；否则普通剧情信号/具体旧事动作必须通过排除条件。
6. 最后追加合法 follow-up 对应资料面，因此承接可以补上普通关键词未请求的来源。

第 5 步的布尔条件等价于：

```text
请求 STORY_FACT = 定义问题
                 OR (
                   (命中普通剧情词 OR 自身动机与具体旧事动作同时命中)
                   AND 不是普通自身动机问题
                   AND 没有 PREFERENCE 意图
                   AND 没有 ORIGIN 意图
                   AND 没有 STATE 资料面
                   AND 没有 POSSESSION 资料面
                 )
```

定义分支在 OR 左侧，因此可与 STATE、PROFILE、POSSESSION 同时成立；不能笼统理解为“状态问题绝不查剧情”。PROFILE 与 STATE 也可同时请求，例如“你在哪里，为什么坚持这个原则？”会匹配状态及原则/动机相关规则。

`planKnowledgeQuery()` 不会替未命中的资料面做补查。它还按是否命名关系目标限制关系数量：有目标时最多 1 条/96 字符，无命名目标时最多 2 条/160 字符。事实、profile、state、物品各有独立上限，见第 8 节。

#### 5.3.4 紧凑日志标签不等于完整路由

`stableIntentLabel()` 按以下优先级返回一个标签：

```text
完全匹配寒暄 → chitchat
IDENTITY → identity_or_chitchat
STORY_FACT + RELATIONSHIP → mixed_story_and_relationship
RELATIONSHIP → relationship
STATE + PROFILE → mixed_state_and_profile
STATE → state
POSSESSION → possession
PROFILE → profile
其余 → unknown
```

**当前缺少“纯 STORY_FACT”分支**，所以纯剧情问题也可能显示 `knowledgeIntent=unknown`。要判断实际查了哪些资料面，应优先查看 `intents`、`surfaces` 和各 store 的状态，不能只看 `knowledgeIntent`。

<a id="routing-examples"></a>

### 5.4 问题示例对照（当前行为）

以下均假设 `CHARACTER_RAG`、默认向量开关、图谱开关开启、没有合法 follow-up 目标，且角色资源可用。外部向量每行都会查询；表内重点列出角色资料面和外部注入许可。调用某个库不保证最终有命中或能装入预算。

| 问题 | 当前资料面/来源 | 外部注入 | 说明 |
| --- | --- | --- | --- |
| 你好 | 无动态资料面 | 否 | 完全匹配寒暄；角色固定身份仍存在。 |
| 你是谁？ | PROFILE / 角色卡 | 否 | 完全匹配身份。 |
| 你来自哪里？ | PROFILE / 角色卡 | 否 | ORIGIN 排除普通剧情分支。 |
| 你现在在哪里？ | STATE / 场景 | 否 | “在哪里”触发，不是单独的“现在”。 |
| 你最想去哪里？ | PROFILE / 动机、价值观 | 否 | 偏好排除普通剧情分支。 |
| 你现在想去哪里？ | STATE / 当前目标 | 否 | 当前目标判断抑制偏好。 |
| 你手里有什么？ | POSSESSION / 当前 carried 物品 | 否 | 不等于从历史推断持有。 |
| 你怎么看玄谙？ | RELATIONSHIP / V1 关系 | 否 | 明确的可用关系问法。 |
| 你和青谕遣是什么关系？ | **STORY_FACT / V2→V1 claim→legacy** | 否 | 已知误判：定义词挡住关系分支。是否可知取决于当前角色。 |
| 你都认识谁？他们和你是什么关系？ | **STORY_FACT** | 否 | 同上；不能依赖该问法调用关系库。 |
| 太虚元序玄司营造法进展如何？ | STATE / 任务状态 | 否 | “进展”先请求 STATE，抑制“如何”的普通剧情分支。 |
| 你为什么停止拼合七枚鉴身碎片？ | STORY_FACT / V2→V1 claim→legacy | 否 | 自身动机词 + 具体旧事动作优先于普通动机。 |
| 玄谙究竟是什么？ | STORY_FACT | 否 | 定义词直接请求事实。 |
| 文档里如何描述元府？ | STORY_FACT，同时允许使用外部结果 | 是 | 外部门控与内部路由独立；小说测试切片仍会被外部注入过滤。 |
| 世界杯决赛有什么争议？ | 无角色资料面；外部向量结果 | 是 | “世界杯”触发外部许可；角色图谱不因此必查。 |
| 你去哪里做什么？ | STORY_FACT | 否 | “哪里”命中剧情词；没有“在哪里/在做什么”等完整状态信号。 |

若只想对照 V1 关系调用，可使用“你怎么看玄谙？”，并以当前角色、cutoff、`status` 和实际候选为准；它是当前代码的路由探针，不保证任何角色都认识玄谙。

<a id="routing-known-issues"></a>

### 5.5 已确认的路由缺陷与日志陷阱（尚未修复）

1. **人物关系被定义问题截走。** `definitionSignals` 包含“什么关系”，且关系判断要求 `!definitionQuestion`。因此自然的关系问法会误查剧情，而 V1 relationship 显示 `not_requested`。
2. **状态词可吞掉明确的剧情实体问题。** “太虚元序玄司营造法进展如何”被 STATE_QUEST 接管，即使 V2 中有相关事实，也不会因此补查 V2。这是规则粒度的限制，不能推断知识库为空。
3. **纯剧情标签显示 unknown。** `stableIntentLabel()` 缺少纯 STORY_FACT 分支；完整 `surfaces` 比紧凑标签可靠。
4. **未来拦截可能误伤泛问题。** 错路由进入剧情检索后，词面碰到 `ACQUIRED_AFTER_CUTOFF` 且没有 SELECTED fact，就可能被 gate 整轮拦截。它没有重新确认问题是否真的在问那个未来事件。
5. **空候选可能被误报成预算淘汰。** `sourceStage()` 的预览为空时使用默认原因 `all_candidates_evicted_by_character_budget`，legacy 的候选列表本来为空也会落入该原因。只有实际 `droppedItems` / 淘汰决定能支持预算归因。
6. **结果阶段数量已经过部分预算。** V2/V1 的 `authorizedCandidateCount` 来自经过事实/关系专用字符预算筛选的列表，不是原始召回数；0 不能单独区分未命中、未授权或专用预算全部丢弃。

2026-10-27 的实际日志案例：问题“你都认识谁？他们和你是什么关系？”显示 `vectorExecuted=true,candidates=2/20,failure=none`，证明外部向量查询成功；`surfaces=STORY_FACT`、`v1Relations=not_requested` 表明关系路由误判；最终还出现未来事实 gate 日志。这三个现象分别属于查询、路由和回答拦截，不能合并为“数据库没有检索”。

## 6. 角色剧情知识检索与安全边界

### 6.1 V2：首选的剧情/认识论事实来源

运行时 V2 读取 `knowledge_fallback.json`，而不是直接执行 SQLite FTS 查询。SQLite 文件保留为可审计的源数据库；启动时会核验 fallback 记录的数据库 SHA-256 与已安装 SQLite 是否一致。这样可以避开 Android 环境中 FTS5/trigram `MATCH` 兼容性问题。

V2 对每个 claim 生成审计决定，只有同时满足以下条件才可注入：

1. 与问题至少有一个词面匹配；
2. 当前角色拥有该 claim 的知识边；
3. 获取时间不晚于当前剧情截止点；
4. claim 的 scope 是 `story`，而不是 profile；
5. 来源为 curated、canon accepted、verified curated；
6. 知识边 belief status 为 `believed`；
7. claim 的有效时间窗覆盖当前 cutoff。

通过后按下式排序：

```text
0.70 × 词面匹配率
+ 0.15 × 重要度
+ 0.10 × claim 置信度
+ 0.05 × 知识边置信度
```

得分最高的最多两条事实经过专用预算和 system 总预算后可进入 system，并带“亲历并确认 / 亲眼见到 / 听人提及 / 据线索推断”等认识来源前缀。授权事实已经返回但装不进总预算时，不会因此重新查询 V1 claim 或 legacy。

### 6.2 V1 claims：V2 未命中时的兼容回退

在请求 STORY_FACT 且图谱开关开启、V1 store 存在的前提下，仅当 V2 没有任何已授权剧情事实时，才查询 V1 的 `claims.jsonl`。V1 只接受 `claim_type == story_event`，并同样检查角色可知性、截止点、来源层级、canon 状态与获取方式。V1 的 profile/观察/约束 claims 被刻意排除，不能借回退绕开角色卡与剧情边界。

### 6.3 旧事件表：最后一级回退

仅当请求 STORY_FACT，且 V2 和 V1 均没有返回授权事实时（包括图谱关闭/不可用的情况）：

1. `KeywordStoryRetriever` 使用关键词精确包含、2/3-gram 重叠和事件重要度召回候选；
2. `KnowledgeBoundaryFilter` 拒绝元问题、角色未知事实、截止点未来事件、尚未得知的事实和不可见事件；
3. 通过的事件最多保留 `RuntimeContext.topK` 条；
4. 只从已授权事件中取仍有效、当前角色可见的 episodic memory。

因此旧事件检索不是 V2 的并行叠加来源：一旦 V2/V1 已给出有效剧情事实，旧事件与记忆检索不会执行，防止重复、冲突和不必要的 prompt 占用。

### 6.4 有向关系、当前状态和物品

- V1 关系仅允许“当前说话角色 → 目标角色”的边，绝不反转另一角色对说话者的看法。
- V1 关系目标名来自已加载角色卡；关系指向没有对应角色名的实体时可能被舍弃。当前关系 store 只检查 cutoff 名是否存在，没有 V2 那样的逐边获取时间过滤，不能把二者的时间边界实现视为一致。
- `relevant_state` 的场景、任务、目标来自角色卡/运行时动态状态；物品所用的时间有效状态资源按 `valid_from` / `valid_to` 与剧情事件序号选择，状态区间不能重叠，且必须携带证据引用。
- 物品回答只认可当前 active state 中 `status == carried` 的条目。旧剧情提到过某物，不代表角色当前仍携带它；`not_carried` 与 `unknown` 都不会被当作持有事实注入。

当前 cutoff 在启动时优先恢复最近会话，否则使用 `evt-010`；切换角色时改为该角色卡声明的 cutoff。因此验证同一句问题时，应同时记录 `npc` 和 `cutoff`，不要把不同角色、不同时间点的授权结果直接比较。

## 7. 会话承接与持久化

正常回合完成后，`ChatSessionStore` 保存：玩家文本、角色回复、模式、外部 RAG 来源、模型、会话摘要，以及**不含原文的**角色上下文目标 ID。现有 `ChatTurn.renderedPrompt` 还可保存 Final Prompt 预览；新增的分阶段 `stageLogs` 不随它保存，不能把“阶段原文不落盘”理解成“项目从不保存任何 prompt”。

代词承接由 `CharacterFollowUpResolver` 完成，而不是把上一轮原始资料整体塞回 prompt。它只会在以下条件都满足时复用一个目标：

1. 新问题包含“他/她/它/那把/那个/那件/此物”之一，或“其他的还有吗/还有吗/还有什么/再说说/进一步/展开/详细点/详细说”之一；
2. 新问题没有明确写出已知角色名；
3. 仅查看上一轮，且角色、剧情 cutoff、模式完全相同；
4. 上一轮没有外部 RAG source；
5. 上一轮有效目标恰好一个；或属于续问且过滤后恰好有一个 profile 目标。合法类型包括事实、关系、物品、profile。

`CharacterQueryAnalyzer` 最后按照目标类型追加 `FOLLOW_UP` 及资料面：

| 上轮目标 kind | 追加资料面 | 当前取法 |
| --- | --- | --- |
| `authorized_story_facts` | STORY_FACT | V2/V1 按 opaque claim ID 重新授权；legacy event ID 可直接定位旧事件再过滤。 |
| `relevant_relationships` | RELATIONSHIP | V1 使用 preferredRelationId，仍检查说话者方向。 |
| `relevant_possessions` | POSSESSION | 重新取当前有效状态，只保留匹配 item ID 且 carried 的物品。 |
| `relevant_profile` | PROFILE | 取同一档案 facet，可优先补充其他原子记录；不复制上轮正文。 |

歧义或条件不满足时，返回正常关键词路由；不会自动把所有上下文或上一轮外部资料塞入 system。

## 8. 预算、渲染与生成前后控制

### 8.1 预算层次

| 层次 | 当前默认/上限 | 用途 |
| --- | ---: | --- |
| 普通 RAG 外部资料 | 900 字符 | `PromptBuilder`。 |
| 角色外部资料 | 600 字符 | `CharacterRagPromptBuilder`。 |
| 角色 system | 编排器默认 900 字符 | `MainActivity` 未显式传 maxCharacterChars，使用编排器默认值。 |
| 角色剧情事实 | 最多 2 条、180 字符 | `CharacterPromptCompiler` 的查询计划上限。 |
| 角色关系 | 命名目标：最多 1 条、96 字符；否则最多 2 条、160 字符 | 仍受 RuntimeContext 更小值和 system 总预算约束。 |
| profile | 最多 2 条、180 字符 | 同上。 |
| state | 最多 1 条、96 字符 | 同上。 |
| possessions | 最多 2 条、160 字符 | 同上。 |
| 角色生成 | `predictLength=-1`，无固定生成 token 上限 | `LlamaEngine.UNLIMITED_PREDICT_LENGTH`；由 EOG 或用户取消等运行时条件停止。 |

编译器在压缩历史摘要前，先预留 `min(220, 900 - mandatoryBaseChars)` 给本轮动态证据；这不保证实际能留出 220 字符。例如固定区占 815 字符时，只能预留 85。候选还要通过各资料面的专用预算与最终 system 总预算。

`manifest.json` 里的较大 `context_budget` 是资源包元数据/建议；直接调用角色编译器的 `RuntimeContext.maxChars` 默认也是 4500，但 App 走编排器默认的 900。角色外部 builder 可能额外附加 system 约束，因此最终 `systemChars` 可能比角色编译器内的上限稍长。任何容量判断都应同时查看预算阶段、最终 system/user 长度与真实 tokenizer 计数。

### 8.2 聊天模板

`ChatTemplateRenderer` 根据模型家族产生不同格式：

| 模型家族 | 模板 |
| --- | --- |
| Llama | Llama 3 风格 system/user/assistant headers。 |
| Qwen | ChatML。 |
| MiniCPM text | ChatML 安全回退。 |
| MiniCPM vision | profile 名为 `minicpm_vision`，当前 Kotlin 预览落入简化 system/user/assistant 标记分支；原生层另按 system/user 消息处理。 |
| 其他 | 简化的 system/user/assistant 标记格式。 |

角色规则正确与否不仅取决于检索，也取决于实际加载模型使用的聊天模板。因此 JVM prompt 测试不能替代该模型、该设备上的生成回归。

### 8.3 生成前 gate 与生成后观测

`RoleplayAnswerabilityGate` 是狭窄的生成前保护：如果检索命中了当前 cutoff 之后才可知的事实、且没有其他已授权事实，它直接返回“此事尚未发生，我不能妄言。”；其他普通检索未命中仍交由模型回答。

该 gate 检查 V2/V1 claim 的 `KnowledgeDecision`；即使查到了外部文档或角色关系，也不会自动计入它的“已有授权剧情事实”判断。若 gate 返回固定回复，`collectRawResponse()` 会先通过 `flowOf(fixedResponse)` 完成该次输出，不调用 native 生成。于是“Final Prompt 已打印”也不代表这一轮实际已提交模型推理。

角色回答先缓冲，再交给 `RoleplayGenerationCoordinator` / `RoleplayOutputGuard`。通过时交付；命中需要修复的规则时，追加简短修复指令、清空结构化上下文后最多再试一次；仍失败则展示安全回退，不把被拒绝正文持久化。`RoleplayOutputObserver` 本身只记录哈希、长度、来源和异常信号，实际重试由 coordinator 执行。

当前 `BACKEND_REQUEST` 阶段记录的是首次编译得到的 split system/user，并非每次 native 调用入口记录：gate 可能阻止首次调用，guard 重试也可能追加不同 system 指令。精确判断发送与重试时应同时查看 gate、`HarnessBackend` preflight、重试和 native 日志。

<a id="routing-debug"></a>

## 9. 调试与排障流程

### 9.1 先用正确的 Logcat 筛选

Android Studio Logcat 中，判断外部向量是否调用，使用：

```text
package:com.example.minicpm_v_demo tag:MainActivity message:RAG_TRACE
```

查看原文阶段、V1/V2 结果和各阶段 prompt，使用：

```text
package:com.example.minicpm_v_demo tag:RagPromptDebug
```

排障初期可只筛选 `package:com.example.minicpm_v_demo`，保留 Debug 及以上级别，避免漏掉 gate/编译失败日志。终端可同时捕获两个 tag：

```bash
adb logcat -v threadtime MainActivity:D RagPromptDebug:D '*:S'
```

`RagPromptDebug` 的阶段原文只在 debuggable APK 输出，按 `turn + seq` 追加，并使用 BEGIN/CHUNK/END、约 900 字符分块。App 不清空前一回合日志，但 Logcat 缓冲区本身有容量上限。**仅筛选这个 tag 会漏掉 MainActivity 的 RAG_TRACE。** 当前没有名为 `Retrieval Request` 的阶段或 tag。

### 9.2 判断外部向量有没有查询

示例（数字为说明用途）：

```text
RAG_TRACE mode=CHARACTER_RAG ...
external=vectorExecuted=true,collection=external_untrusted,candidates=2/20,
backend=hash_ngram,elapsedMs=6,failure=none,
injectRequested=false,injected=none,decisions=0
```

| 字段 | 准确含义 |
| --- | --- |
| `vectorExecuted=true` | 已尝试调用向量查询；还需看 failure 确定成功与否。 |
| `failure=none` | 查询没有抛出异常。与 vectorExecuted=true 合看，才说明查询正常完成。 |
| `candidates=2/20` | 外部检索服务返回 2 个候选 ID，配置候选上限 20；数量已过最低分过滤。 |
| `candidates=0/20` | 调用可正常完成但没有返回达标候选，不等于未查询。 |
| `backend=hash_ngram` | 当前用本地哈希向量；不是远程 embedding 服务。 |
| `injectRequested=false` | 问题没有命中外部门控词，外部结果不会传给角色外部 builder。 |
| `injected=none` | 没有最终外部来源；还可能是候选被 novel_test 过滤或上下文压缩/预算丢弃。 |
| `sources=0` / `contextChars=0` | 编译后的外部注入为空；不能据此推断向量或角色图谱没有查。 |

`collection=external_untrusted` 标记外部索引路径，不表示调用了 V1/V2。当前 trace 的一行显示外部候选数量，不显示全部候选正文；要追查命中了哪些原文，需结合诊断 JSONL 中的 candidate IDs 和对应索引。

### 9.3 判断角色路由和知识库状态

先读 `RAG_TRACE` 的 `npc`、`cutoff`、`intents`、`surfaces`，再读 store 状态：

```text
character=npc=lu_jiangxian,cutoff=evt-010,intent=unknown,...
v2=ready,v1=ready,relations=not_requested,...
intents=STORY_EVENT,surfaces=STORY_FACT,...
```

这里实际请求了剧情事实；`intent=unknown` 是当前紧凑标签遗漏，不能推断没有请求。编译摘要中的 `v2=ready/0` 后面的数字是最终注入字符数；不是检索候选数。

角色阶段正文示例：

```text
stage=V2_RETRIEVAL_RESULT CHUNK 1/1
<RETRIEVAL_RESULT>
source=V2 authorized story facts
status=ready
authorizedCandidateCount=1

[1] id=v2-claim-...
section=authorized_story_facts
...经过格式化的授权事实...
</RETRIEVAL_RESULT>
```

| status | 判断 |
| --- | --- |
| `not_requested` | 本轮未请求对应角色资料面，没有调用该库；不能解释为“查了但没命中”。 |
| `ready` | 对应 store 调用正常返回；仍需看候选数与后续预算。 |
| `disabled` | 图谱开关关闭。 |
| `not_configured` | 对应 store 未配置。 |
| `unavailable` | 加载、解析、校验或查询异常；按 fail-closed 返回空并走允许的回退。 |
| `invalid_request` | 请求参数不合法，例如 limit 非正；不是正常未命中。 |
| `skipped_v2_authoritative` | V2 已返回授权事实，V1 claim 按设计不查；不影响独立的关系路由。 |
| legacy `executed` / `not_needed` | 已执行旧事件/记忆回退 / 本轮不需要该回退。 |

`authorizedCandidateCount` 表示阶段列表中的候选数，V2/V1 列表已过专用字符预算（见 5.5）。`ready` + 0 需结合 `knowledgeGraphDecisions` / `v1ClaimGraphDecisions`、预算淘汰记录判断原因，现有 Logcat 摘要没有逐条输出所有拒绝决定。

`profile/state/possession` 摘要还能显示 route、候选数、选中数、预算丢弃数和注入长度。profile 的 `contextual` 只是被请求后的选择路径，不表示每个普通问题都有 profile 兜底。

### 9.4 阶段日志顺序与最终输入

新版 Debug APK 在角色编译成功后按固定顺序输出：

| seq | stage | 内容 |
| --- | --- | --- |
| 1 | `CHARACTER_BASE` | 角色 mandatory 区和问题，动态证据未加入。 |
| 2 | `V2_RETRIEVAL_RESULT` | V2 状态及阶段候选；0 条或 not_requested 也输出。 |
| 3 | `CHARACTER_AFTER_V2` | V2 来源单独加入 mandatory 区的预算预览，或 SKIPPED。 |
| 4 / 5 | `V1_CLAIM_RETRIEVAL_RESULT` / `CHARACTER_AFTER_V1_CLAIM` | V1 claim 结果与对应预览。 |
| 6 / 7 | `V1_RELATIONSHIP_RETRIEVAL_RESULT` / `CHARACTER_AFTER_V1_RELATION` | V1 关系结果与对应预览。 |
| 8 / 9 | `LEGACY_RETRIEVAL_RESULT` / `CHARACTER_AFTER_LEGACY_FALLBACK` | legacy 结果与对应预览。 |
| 10 | `CHARACTER_PROMPT_COMPILED` | 所有候选经过全局排名/总预算后的真实角色 system。 |
| 11 | `EXTERNAL_CONTEXT_COMPILED` | 角色外部 builder 的结果，或 SKIPPED 与注入原因。 |
| 12 | `FINAL_RENDERED` | renderer 的字符串预览。 |
| 13 | `BACKEND_REQUEST` | 首次编译得到的 split system/user；仍可能被 gate 拦截或在重试时改变。 |

`CHARACTER_AFTER_*` 是在编译完成后用各个知识源单独做的预览，**不是实际逐阶段发给模型的请求，也不是逐个来源累计叠加的快照**。某事实在来源预览里出现，仍可能被最终全局排名/预算淘汰；以 `CHARACTER_PROMPT_COMPILED` 和 split 输入为准。所有阶段集中在 compile 返回后打印，不表示各条日志的时间戳是对应检索耗时。

普通 `RAG` 只有 `RAG_CONTEXT_COMPILED`、`FINAL_RENDERED`、`BACKEND_REQUEST` 三个阶段，没有角色 V1/V2 阶段。OFF 在 App 提前返回，没有这些 RAG 编译日志。模式为 CHARACTER_RAG 却整组缺失时，应检查 debuggable 构建、日志级别、编译/打印异常和安装的 APK 是否包含当前代码，不能直接归因于问题没命中关键词。

`RagTurnTraceWriter` 只在 Debug 构建把不含正文的 trace 追加到 `<filesDir>/harness/diagnostics/rag_turn_trace.jsonl`；上述 stageLogs 原文不写进这个文件。现有 Final Prompt 会话保存行为见第 7 节。

### 9.5 从日志定位问题的顺序

1. 看 `Compiled RAG/CHARACTER_RAG prompt` 或错误日志，确认模式与编译是否完成。`Failed to initialize Android RAG orchestrator` / `Failed to compile RAG prompt; falling back to raw user input` 表明没有正常完成当前链路。
2. 看 `vectorExecuted + failure + candidates`，判断外部向量是否实际查询与召回。
3. 看 `injectRequested + injected`，判断外部候选有没有被允许使用并最终进入 user。
4. 看角色 `intents + surfaces` 和诊断窗口 routeCodes，判断该请求的角色库有没有被路由到。
5. 看各 store status、授权/拒绝决定与阶段候选数；结合角色 ID、cutoff、图谱开关和资源版本解释过滤原因。
6. 看选中 ID、实际 droppedItems、注入字符数和最终 system/user；区分候选与最终证据。单独出现 `all_candidates_evicted_by_character_budget` 不足以证明预算淘汰。
7. 看 `Character answerability gate blocked a future fact`、`HarnessBackend` preflight、重试和 native 日志，确认走的是固定回复还是模型推理。
8. 若最终输入已有正确证据而输出失真，再做相同模型、模板和采样参数的设备回归。构建成功、JVM 测试通过只证明相应代码检查，不证明实际对话效果。

## 10. 当前边界与修改注意事项

- 普通文档 RAG 当前是离线哈希 n-gram 检索，不是远程 embedding 或在线搜索。
- 角色模式默认每轮查询普通文档向量索引；显式关键词控制是否使用外部资料。V1/V2 是否调用则由角色 requestedSurfaces、图谱开关和回退条件决定。
- V2 profile claim 被故意排除；角色自身背景/动机等由角色卡 `relevant_profile` 提供。
- 当前对角色偏好类问法仍主要依赖 profile 的关键词和角色卡内容；若要稳定回答非常具体的偏好，需要补充结构化、可证据化的角色偏好资料和对应路由，而不应让模型凭空编造。
- 当前已存在有限的 guard 重试；它不意味着所有回答语义和角色知识都经过验证。观测器日志、规则检查与实际回答质量应分别判断。
- 修改资料结构、知识格式或 prompt 时，应同时更新资产清单/校验、编译器测试和对应的真机回归；其中后者才可证明实际模型输出变化。

### 10.1 后续修复方向（本文未实施）

1. 修正“什么关系”与定义词的优先级，补充“认识谁/认识哪些人”的关系意图，并为复合问题保留多资料面检索。
2. 让命名剧情实体的状态/进展问题能按需同时请求 STORY_FACT，避免只查当前状态；是否增查应有明确、可测试的规则。
3. 为纯 STORY_FACT 补上紧凑标签，避免真实已查询却显示 unknown。
4. 让未来事实 gate 结合本轮目标和匹配强度判定，保留真实未来事件的角色认知边界，同时减少对泛关系问题的误拦截。
5. 日志区分原始召回、授权后、专用预算后、最终总预算后的数量与拒绝原因；空列表不再默认声称预算淘汰。

这些是从当前行为推导出的修复任务，不应靠添加“搜索”等触发词、无条件注入小说原文或放宽截止点来掩盖。本文只更新说明，不修改以上运行规则。

## 11. 相关源码索引

可优先打开以下文件核对路由：

- [MainActivity.kt](../app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt)：模式入口、follow-up、编译日志与生成执行。
- [AndroidRagOrchestrator.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/rag/AndroidRagOrchestrator.kt)：向量调用、外部注入门控、两类 prompt builder。
- [CharacterQueryAnalyzer.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterQueryAnalyzer.kt)：本章关键词、优先级和紧凑标签的直接依据。
- [CharacterPromptCompiler.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompiler.kt)：资料面计划、V2/V1/legacy 顺序、预算选择和阶段预览。
- [CharacterFollowUpResolver.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterFollowUpResolver.kt)：何时复用上一轮授权目标。
- [RoleplayAnswerabilityGate.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/character/RoleplayAnswerabilityGate.kt)：未来事实固定回复条件。
- [RagTurnTrace.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/rag/RagTurnTrace.kt)：Logcat/JSONL 审计字段。
- [RagPromptLogcatPrinter.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/rag/RagPromptLogcatPrinter.kt)：原文追加与分块格式。

```text
app/src/main/java/com/example/minicpm_v_demo/
├── MainActivity.kt
├── harness/data/{AssetBootstrapper,HarnessDataPaths}.kt
├── harness/rag/
│   ├── AndroidRagOrchestrator.kt
│   ├── AlwaysOnVectorRetrievalService.kt
│   ├── {RagSearchService,VectorIndex,HashEmbeddingModel}.kt
│   ├── {PromptBuilder,CharacterRagPromptBuilder}.kt
│   └── {RagTurnTrace,RagPromptStage,RagPromptLogcatPrinter}.kt
├── harness/context/ContextController.kt
├── harness/character/
│   ├── CharacterPromptCompiler.kt
│   ├── {CharacterQueryAnalyzer,CharacterProfileStore}.kt
│   ├── {CharacterStateLoader,CharacterFollowUpResolver}.kt
│   ├── {KnowledgeBoundaryFilter,KeywordStoryRetriever,MemoryRetriever}.kt
│   ├── {RoleplayAnswerabilityGate,RoleplayOutputObserver,RoleplayGenerationCoordinator,RoleplayOutputGuard}.kt
│   └── knowledge/{V2EpistemicKnowledgeStore,V1ClaimKnowledgeStore,V1GraphKnowledgeStore}.kt
└── harness/session/{ConversationSession,ChatSessionStore}.kt

app/src/main/assets/harness/
├── manifest.json
├── rag/index.json
└── characters/{characters,state,story,prompts,knowledge}/
```

