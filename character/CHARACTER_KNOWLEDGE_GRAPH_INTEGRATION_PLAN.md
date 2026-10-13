# Character 双知识图谱接入 MiniCPM Android：实施计划

## 1. 结论与范围

`character/` 下的两个 bundle 不应互相覆盖，也不应直接替换现有的
`app/src/main/assets/harness/characters/story/story_events.jsonl`。

| 图谱 | 现有产物 | 在 App 中的职责 | 推荐权威边界 |
| --- | --- | --- | --- |
| 人物—关系—人物图谱（V1） | `character_kb_agent_bundle/kb/claims.jsonl`、`relations.jsonl`、`manifest.json` | 回答“我与谁是什么关系、如何看待对方”；补充可被当前角色知道的剧情事实 | 关系与关系态度；V1 claim 仅作 V2 不具备时的兼容事实源 |
| 事件和认知状态图谱（V2） | `character_kb_v2_agent_bundle/kb/character_knowledge_v2.sqlite3`、`manifest.json` | 回答“发生了什么、我何时通过何种方式知道”；阻止未来事件和他人私密认知泄漏 | 剧情事实、时点、获得方式、可信度、证据；优先级高于 V1 同一事实 |

目标是让 **CHARACTER_RAG** 模式在每一轮根据 `(characterId, storyCutoff, userInput)`
检索图谱，硬过滤后才把少量、可说出的事实注入角色提示词。该计划只描述后续实现；
本次不修改 Android 运行时代码、图谱内容或现有角色卡。

## 2. 已核对的当前状态

### 2.1 图谱数据

- V1 `manifest.json` 声明 215 条 claim、17 条 relation，并把 `evt-001`～
  `evt-028` 映射到可比较的 `sequence_key`。`claims.jsonl` 的关键字段包括
  `known_by`、`sequence_key`、`importance`、`confidence`、`source_tier` 与
  `canon_status`；`relations.jsonl` 带有关系双方、描述、有效期和证据引用。
- V2 是同一批素材的更细粒度时序/认知模型：215 条 claim、251 条
  `knowledge_edges`、28 个 cutoffs。数据库约 1.3 MB，包含 FTS5 `trigram`
  虚表 `claims_fts`，并以 `knowledge_edges.acquired_sequence` 表达“角色何时知道”。
- 两份 bundle 的角色 ID 与现有角色卡一致（例如 `lu_jiangxian`、`xuan_an`），
  cutoffs 同为 `evt-*`。但 V2 manifest 中 `evt-021` 之后并非单调叙事顺序，
  因此**绝不能**按 event ID 字符串或列表位置比较，必须使用各图谱自己的
  sequence 字段。

### 2.2 当前 Android 调用链

```text
assets/harness/characters/{characters,story,prompts}
  -> AssetBootstrapper 复制到 filesDir/harness
  -> HarnessDataPaths
  -> CharacterJsonLoader
  -> CharacterPromptCompiler.buildNpcPrompt()
  -> AndroidRagOrchestrator.compile(CHARACTER_RAG)
  -> MainActivity.compilePromptForHarness()
  -> LlamaBackendAdapter / MiniCPM
```

`CharacterPromptCompiler` 目前只用 `KeywordStoryRetriever` 在
`story_events.jsonl` 召回，再由 `KnowledgeBoundaryFilter` 根据角色卡的
`world_fact_refs`、事件时间、`knowledge_by_character` 和可见性过滤。
`CharacterRagPromptBuilder` 已正确把普通外部 RAG 资料放到 user message；
角色身份和已授权剧情事实留在 system message。新图谱必须保持这个边界。

### 2.3 当前实施状态（2026-10-13，`char_sys`）

| 项目 | 实际状态 | 已有证据 | 尚未完成的边界 |
| --- | --- | --- | --- |
| Phase 0：契约与 SQLite 可行性 | **已完成，选择 JSON 回退** | API 36 ARM64 模拟器实测能只读打开 V2（SQLite `3.50.4`）且 28 个 cutoff 正常，但 `fts5/trigram` 的 `MATCH` 无法命中 | Android SQLite 不能作为 V2 FTS 生产主路径；原始数据库继续仅作审计资产 |
| Phase 1：资产打包与路径 | **已实现，随 V2 回退资产更新** | V1/V2 最小资产已进入 APK；V2 同时包含由源库导出的 `knowledge_fallback.json`；启动时复制到 `filesDir/harness/characters/knowledge/` | 资产存在不表示模型已经读取图谱 |
| Character+RAG 传输前置条件 | **已修复** | 文本模型的 system/user 消息在无内嵌模板 GGUF 上也使用 ChatML；编译测试覆盖 system/user/assistant 边界 | 仍需要设备上的真实模型回归，不能仅凭编译断言模型行为 |
| Phase 2：统一检索器 | **2A/2B 已实现，未注入提示词** | `V2EpistemicKnowledgeStore` 读取带源库 SHA-256 的 JSON 导出，执行 V2 时间/认知/审核过滤、稳定评分、中文 n-gram 召回；`KnowledgeDecision` 记录选择/拒绝理由，`retrieveSafely()` 为资产异常提供 fail-closed 降级结果 | V1、跨图谱统一门面、UI/模型调用均未开始 |
| Phase 3：提示词注入/回退 | **3A 已实现：仅 V2 facts** | `CharacterPromptCompiler.fromPaths()` 创建 V2 store；已授权 V2 fact 写入 system 的 `authorized_story_facts`，无图谱/无命中时回退到 `story_events.jsonl` | V1 人物关系和 V1 claim 兼容尚未接入 |
| Phase 4：端侧质量验收 | **未开始** | JVM 测试与 Debug APK 构建通过 | 没有设备功能、TTFT 或防剧透实测结论 |

当前实际检索链仍是：

```text
story_events.jsonl -> KeywordStoryRetriever -> KnowledgeBoundaryFilter
```

因此下一步是 **Phase 4A：设备功能与防剧透验收**。V1 关系检索和 V1 claim 兼容应分别延后，
避免一次合并三种事实来源而无法定位角色失真或剧透问题。

## 3. 目标架构与数据流

```text
用户问题 + 当前角色 + 当前剧情截止点
                 |
                 v
      CharacterKnowledgeRetriever（新增门面）
         |                         |
         | V2：事件/认知主检索       | V1：关系检索与兼容 claim 检索
         v                         v
   时间、认知、来源、审核硬过滤       时间、来源、审核、双方关系过滤
         \                         /
          \-- 去重、排序、预算选择 --/
                       |
                       v
  CharacterPromptCompiler：可信角色知识块（system）
                       |
                       +--> CharacterRagPromptBuilder：普通外部资料（user）
```

### 不可变的规则

1. 图谱候选不能先放入 prompt 再请模型自行判断；角色可知性、未来事件、审核状态
   必须在 Kotlin 检索器中硬过滤。
2. 缺少 `characterId`、`storyCutoff`、图谱版本不匹配或图谱初始化失败时，禁止注入
   图谱事实，并回退到当前 `story_events.jsonl` 链路；不可默认使用全书末尾时点。
3. 同一事实只保留 V2 的条目；V1 claim 只用于 V2 没有覆盖的兼容场景。关系事实
   单独保留，因为它服务于“态度/关系”表达，不能被一般事件召回淹没。
4. 只接受 `source_tier in {canon, curated, external}`，其中 `external` 必须
   `accepted`；只接受 `canon_status in {accepted, auto_accepted}`。V2 同时要求
   `verification_status` 为已接受状态；任何 `review_required` 或未知值均拒绝。
5. 角色身份、性格、硬约束、知识边界和已授权的内部剧情事实属于可信控制上下文，
   放在 system message；联网/文档等不可信外部 RAG 仍保持在 user message，且明确
   标记为“不可信指令”。

## 4. 分阶段实施

### Phase 0：冻结数据契约与端侧可行性验证

**目的：先证明 V2 数据库可在 `minSdk 24` 的真实 Android SQLite 环境打开和查询，
再决定运行时格式，避免把桌面 Python/SQLite 能力误当成手机能力。**

**当前状态：** `CharacterKnowledgeContractValidator` 已校验 V1/V2 schema、cutoff 和
最小文件；`V2SqliteCapabilityProbe` 已以只读方式检查 metadata、cutoffs、FTS 声明和一次
`MATCH`。在 API 36 ARM64 模拟器上，数据库可打开且 schema/cutoff 验证通过（SQLite
`3.50.4`），但 `MATCH` 没有命中。因此已选择 JSON 导出而非 SQLite FTS 主路径。

1. 新建 `CharacterKnowledgeManifest`，读取两个 `manifest.json`，校验：
   `schema_version` 分别为 `1.x`/`2.x`、`knowledge_base_version == v2`、数据库文件
   存在、所选角色和 cutoff 同时存在于角色卡与图谱。
2. 在 instrumentation test 和至少一台/一个 API 24+ 设备（或模拟器）上，用
   `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` 打开复制后的 V2 数据库，并执行：
   `SELECT value FROM metadata WHERE key='schema_version'`、一个 `cutoffs` 查询、一个
   `claims_fts MATCH` 查询。
3. 记录设备 SQLite 版本、FTS5/trigram 成功或失败信息。不能假设 Android 系统 SQLite
   对预构建的 FTS5/trigram 虚表可用。
4. 决策门：
   - **通过：** V2 SQLite 是生产主路径；保留 FTS5 + Kotlin bigram/LIKE 回退。
   - **失败：** 不引入 Python 运行时；在构建阶段把 V2 的
     `graph/{claims,knowledge_edges,relations}.jsonl` 与 cutoffs 导出成紧凑、版本化 JSON，
     由同一 Kotlin 过滤/中文 n-gram 检索实现读取。V2 语义不变，仅替换存储/候选召回层。

已执行的验收：在 Android 上完成 V2 数据库打开、候选 `MATCH` 查询和关闭，并保存 SQLite
版本及 FTS5/trigram 结果。由于该查询不工作，已新增可重复的
`character/export_v2_knowledge_fallback.py`；它将 V2 claims、knowledge edges、cutoffs 和源库
SHA-256 导出为 `knowledge_fallback.json`。Android 只读取该导出，仍保留 SQLite 文件用于版本
与审计核对。

### Phase 1：资产打包、升级和路径

**目的：把图谱变成 App 能安全读取、可随资产版本升级的数据，而不是在源码根目录读取。**

**当前状态：已实现，尚未接入角色回答。** V1 最小包和 V2 SQLite 已位于
`app/src/main/assets/harness/characters/knowledge/{v1,v2}`；`.sqlite3` 在 APK 内为未压缩
条目。`AssetBootstrapper` 对每个实际文件计算 SHA-256，使用临时目录校验后备份替换，并在
JVM 测试中验证损坏的 manifest 会被下一次 bootstrap 恢复。

1. 将生产副本加入：

   ```text
   app/src/main/assets/harness/characters/knowledge/
   ├── v1/manifest.json
   ├── v1/claims.jsonl
   ├── v1/relations.jsonl
   └── v2/manifest.json
       └── character_knowledge_v2.sqlite3
   ```

   `entities.jsonl`、`graph.json`、V2 原始语料/证据只保留为构建/审计材料，不进入首版
   APK；需要“查看依据”功能时再按需携带经裁剪的证据索引。
2. 扩展 `HarnessDataPaths`，新增 `characterKnowledgeDir`、`v1KnowledgeDir`、
   `v2KnowledgeDatabaseFile` 与 manifest 路径，全部位于 `filesDir/harness`。
3. 扩展 `AssetBootstrapper`：按最小包每个文件的 SHA-256 决定是否升级；先复制到临时目录，
   校验 V1/V2 manifest 与最小文件，再备份替换。运行期数据库始终只读，不能直接在 APK
   asset 内打开。此项已实现。
4. 在 `app/build.gradle.kts` 中为 `sqlite3` 添加 `androidResources.noCompress`；这样 APK
   内的预构建数据库可以被可靠复制，且不会产生不必要的启动解压成本。
5. 把“资产版本”和“当前检索器版本”写入 debug 日志，不记录完整用户提问或证据原文。
   该观测项移入 Phase 3，与实际检索结果一起实现；当前安装器不记录用户内容。

验收：冷启动后 `filesDir/harness/characters/knowledge/v2/character_knowledge_v2.sqlite3`
存在、可只读打开；升级 manifest 后只替换对应知识库，旧版失败时仍保有最后一次有效文件。

### Phase 2：统一领域模型与检索器

**目的：在不让 UI 感知 V1/V2 差异的前提下，保留每条结果为什么被允许或拒绝的审计信息。**

**当前状态：2A 已实现；必须在 Phase 0 的设备决策门得出结论后实施。** 首个实现切片为
**Phase 2A（仅 V2 facts）**：定义领域模型、只读 V2 store、认知/时间/审核硬过滤与
`KnowledgeDecision`。不读取 V1、不改变 `CharacterPromptCompiler`、不向模型注入任何结果。
这样可以先独立验证“召回/拒绝理由正确”，再扩展关系与提示词。

新增 `app/src/main/java/com/example/minicpm_v_demo/harness/character/knowledge/`：

| 文件/类型 | 职责 |
| --- | --- |
| `CharacterKnowledgeStore.kt` | `retrieveFacts()`、`retrieveRelations()`、`health()` 的只读接口 |
| `V1GraphKnowledgeStore.kt` | 解析 JSONL；复现 `known_by`、`sequence_key`、审核/来源过滤；检索关系 |
| `V2EpistemicKnowledgeStore.kt` | 打开 V2 SQLite 或 Phase 0 确认的等价导出；查询 claim、`knowledge_edges`、cutoff、relations |
| `CharacterKnowledgeRetriever.kt` | 统一候选、V2 优先去重、排序、字符预算与降级 |
| `KnowledgeModels.kt` | `KnowledgeFact`、`KnowledgeRelation`、`KnowledgeDecision`、`KnowledgeDebug` |
| `ChineseKnowledgeTokenizer.kt` | 与现有 `KeywordStoryRetriever` 一致的连续汉字二/三元 gram、英文 token 规范化，避免空格分词导致中文漏召回 |

检索输入固定为 `characterId`、`query`、`storyCutoff`、`factLimit`、`relationLimit`。
输出不得把原始 JSON、claim ID、筛选原因或证据文本直接交给模型。

#### V2 主路径算法

1. 由 `cutoffs.event_id` 得到 `cutoffSequence`。
2. 用 FTS5 trigram 检索候选；异常或无命中时用限制数量的中文二/三元 gram 做
   `LIKE`/内存候选回退，最多取 120 条候选。
3. 对每条候选依次检查来源/审核状态、角色的 `knowledge_edges`、
   `acquired_sequence <= cutoffSequence`、`valid_from_sequence <= cutoffSequence`、
   `valid_to_sequence` 未过期，并排除 `claim_scope=profile`（角色卡已负责身份）。
4. 评分使用 lexical/retrieval score、importance、claim confidence、knowledge-edge confidence；
   排序稳定地以 claim ID 作为最终 tie-breaker。
5. 返回获得方式（direct/witnessed/reported/inferred）和可信度，以便生成“亲历/目击/
   听说/推断”的限定语。

#### Phase 2B：评分、审计与降级（已完成）

`V2EpistemicKnowledgeStore.retrieveAudited()` 为每条 V2 claim 产出不含事实正文、玩家问题或
证据原文的 `KnowledgeDecision`。拒绝原因包括无角色认知边、取得时间晚于 cutoff、profile、
审核状态、belief 状态、有效期、词法不匹配和 top-K 截断；仅最终入选项保留 score。
`retrieveSafely()` 在 JSON 缺失、哈希不匹配或解析失败时返回 `UNAVAILABLE` 与空结果，供下一
阶段无条件回退到既有 `story_events.jsonl`，而不是容忍未知事实或阻塞应用启动。对应 JVM 测试
覆盖未来知识拒绝、审计无正文以及资产缺失的 fail-closed 行为。

#### V1 关系与兼容路径算法

1. 关系查询优先匹配 `target_id` 的 canonical name/alias；用户没有点名对象且询问
   “关系、看法、他/她/他们”时，至多返回当前角色的两条高相关关系。
2. 关系只在 `source_id == characterId` 时以“你的态度”注入；反向关系只能作为
   叙事事实，不可误写成当前角色的主观态度。
3. V1 claim 复用 `known_by[characterId]` 和 `sequence_key <= manifest.cutoffs[storyCutoff]`
   的硬过滤。其时间比较只能使用 V1 manifest，不混用 V2 的 `story_order`。
4. 用 `(normalized statement, subject_ids, predicate)` 近似去重；若与 V2 事件相同，
   丢弃 V1 版本并在 debug 中记录 `shadowed_by_v2`。

### Phase 3：接入角色提示词编译与现有回退

**目的：替换“只依赖 `story_events.jsonl`”的事实来源，但不改变普通 RAG 的安全边界。**

**当前状态：3A 已实现（V2 facts），V1 部分未开始。** 已完成的 ChatML native 传输修复是本阶段
的前置条件，不属于图谱注入本身：今后必须保留“system/user/assistant 边界”回归断言，不能因接入
图谱而回退为裸文本。

3A 实现细节：`RuntimeContext` 默认启用 `knowledgeGraphEnabled`，并以 `graphFactTopK=4`、
`knowledgeGraphMaxChars=320` 为 V2 事实设置独立上限；V2 有已授权命中时优先填充
`authorized_story_facts`，否则保留既有 `KeywordStoryRetriever + KnowledgeBoundaryFilter`。图谱
事实只保留在 system message；外部 RAG 原文仍只经 `CharacterRagPromptBuilder` 放入 user message。
`CharacterPromptDebug` 记录图谱状态、降级原因、无正文的筛选决策和实际注入字符数。

1. 为 `CharacterPromptCompiler` 注入可选 `CharacterKnowledgeRetriever`。`fromPaths()` 创建
   检索器；构造/manifest/DB 失败不阻止应用启动，标记为 unavailable。
2. `buildNpcPrompt()` 在确认角色与 cutoff 后调用图谱检索：
   - V2 facts → `authorized_story_facts`；
   - V1 relations → `relevant_relationships`；
   - 仍保留 `MemoryRetriever` 和会话摘要；
   - 图谱 unavailable 或没有通过硬过滤的结果时，继续使用现有
     `KeywordStoryRetriever + KnowledgeBoundaryFilter`，以兼容当前资产。
3. 扩展 `RuntimeContext`，仅增加显式配置（建议默认开启）：
   `knowledgeGraphEnabled`、`graphFactTopK=4`、`graphRelationTopK=2`、
   `knowledgeGraphMaxChars`。不能把图谱结果塞入已有 900 字符默认预算之外。
4. 将 `CharacterPromptDebug` 升级为能记录：使用的图谱版本、健康状态、每项选择/丢弃、
   reason、得分、注入字符数、V1/V2 去重数量与回退原因。保留现有字段，避免破坏 UI
   和既有单元测试。
5. 文本格式只呈现角色可说的内容，例如：

   ```text
   【本轮允许使用的剧情事实】
   - 你亲历并确认：陆江仙拒绝以无辜者作为明阳之局的资粮。
   - 你听人提及：元府三司曾由李原、玄谙、蒋清掌控。

   【本轮相关关系】
   - 玄谙：同源对立者；你高度戒备，拒绝其生存至上的方案。
   ```

   `confidence < 0.9` 使用“据称/可能/你推断”，不将概率、ID、evidence 行号暴露给模型。
6. `AndroidRagOrchestrator.compile()` 的普通外部 RAG 流程不动：外部文档继续经
   `CharacterQueryRouter` 和 `CharacterRagPromptBuilder` 放入 user message；内部图谱
   不应被当成 `RagSource` 或混入不可信文档块。
7. `MainActivity.compilePromptForHarness()` 继续以 `splitPrompt.systemPrompt`/
   `userPrompt` 发送；扩展其 debug/`RoleplayOutputObserver` 元数据以记录图谱版本、
   selected/dropped 数和图谱注入 token 估算，便于现场定位泄漏或预算问题。

### Phase 4：测试、观测与设备验收

#### 单元与集成测试

新增 `harness/character/knowledge` 测试，并在既有
`CharacterPromptCompilerTest`、`AndroidRagOrchestratorTest` 增加覆盖：

1. `lu_jiangxian + evt-018` 能召回其拒绝牺牲无辜者的 V2 事实。
2. `xuan_an + evt-018` 不能拿到陆江仙前世北斗记忆。
3. 任意角色在 `evt-018` 不能得到 `evt-020` 才获得的知识；覆盖“先出现的 event ID
   不等于先发生”的 V2 cutoff 情况。
4. `external + review_required`、未知 tier/status、无 `knowledge_edge`、过期 claim 都不进
   system prompt。
5. 关系查询“你怎么看玄谙？”只能写入当前 NPC 的关系方向；反向关系不倒置。
6. V2/V1 重复事实只注入一次，且 V2 获胜；V2 不可用时现有 story-events 回退仍成功。
7. `maxCharacterChars=900` 下身份和硬约束不丢失，图谱项被预算裁剪时 debug 记录
   `character_budget`；无授权事实时模型提示中包含“不得自行补全”。
8. 验证最后的 `splitPrompt`：身份/内部图谱在 system，外部 RAG 原文仅在 user。

#### Android 设备验收

1. 执行 `sh gradlew :app:testDebugUnitTest`、`sh gradlew :app:assembleDebug`、
   `git diff --check`；数据库路径、升级和 FTS 能力另加 instrumentation test。
2. 在可用 ARM64 模拟器或真机上分别运行图谱关闭/开启的**相同**角色、cutoff、问题、
   `predictLength`，每种至少多次。记录功能是否正确、prompt token、预填充耗时、TTFT、
   首个可见字时间和完整输出；不要把不同上下文长度的单次结果写成性能 A/B。
3. 审查 Logcat/debug：图谱版本、筛选拒绝原因聚合、实际 system/user 字符与 token、
   DB/回退状态。严禁将完整玩家文本、证据原文或角色私密事实上传到生产日志。
4. 人工角色一致性回归：至少覆盖知识问答、关系态度、未知事实追问、未来剧透诱导、
   外部文档 prompt injection、连续多轮记忆。输出文本经 `RoleplayOutputObserver` 观察，
   但不自动改写模型回答。

## 5. 迁移顺序、风险与完成定义

调整后的顺序为：**完成 Phase 0 设备决策门 → Phase 2A（仅 V2 facts、无提示词注入） →
Phase 2B（V2 评分/降级/审计，已完成） → Phase 3A（V2 注入与 story-events 回退，已完成） → Phase 4A
（设备功能与防剧透） → V1 relation → V1 claim 兼容 → Phase 4B（完整质量/性能回归）**。
这样先让“事件/认知”这个最关键的防泄漏模型落地，再加入关系表达，避免两个数据源同时改变
导致问题无法归因。

主要风险及控制：

| 风险 | 控制措施 |
| --- | --- |
| Android SQLite 不支持 V2 的 FTS5/trigram | Phase 0 真机/模拟器门禁；采用等价 JSON + Kotlin n-gram 回退，不依赖桌面 Python |
| 图谱覆盖未来或他人私密事实 | 代码硬过滤 `knowledge_edges`/sequence/status；默认没有角色和 cutoff 即不注入 |
| V1/V2 双重注入造成重复、膨胀或角色失真 | V2 优先、规范化去重、关系与事件分预算、保留 debug 决策 |
| 图谱/数据库资产更新损坏运行时 | manifest 版本校验、临时复制后校验、原子替换、保留最近有效副本和 story-events 回退 |
| 角色提示过长拖慢端侧首 token | 对图谱分配独立字符上限；以真实 prompt token、prefill、TTFT 评估 |
| 把外部资料提升为角色记忆 | 外部 RAG 固定在 user；未经 accepted 的 external claim 硬拒绝 |

完成的定义不是“数据库已复制到 APK”，而是：V2 时序/认知边界和 V1 关系态度都可在
真实 CHARACTER_RAG 调用链中检索、审计和注入；未来/越权/未审核信息无法到达 prompt；
图谱不可用时现有角色功能仍可工作；并且构建、自动化测试和至少一次端侧功能验证均通过。
