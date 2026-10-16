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

### 2.3 当前实施状态（2026-10-16，`char_sys`）

| 项目 | 实际状态 | 已有证据 | 尚未完成的边界 |
| --- | --- | --- | --- |
| Phase 0：契约与 SQLite 可行性 | **已完成，选择 JSON 回退** | API 36 ARM64 模拟器实测能只读打开 V2（SQLite `3.50.4`）且 28 个 cutoff 正常，但 `fts5/trigram` 的 `MATCH` 无法命中 | Android SQLite 不能作为 V2 FTS 生产主路径；原始数据库继续仅作审计资产 |
| Phase 1：资产打包与路径 | **已实现，随 V2 回退资产更新** | V1/V2 最小资产已进入 APK；V2 同时包含由源库导出的 `knowledge_fallback.json`；启动时复制到 `filesDir/harness/characters/knowledge/` | 资产存在不表示模型已经读取图谱 |
| Character+RAG 传输前置条件 | **已修复** | 文本模型的 system/user 消息在无内嵌模板 GGUF 上也使用 ChatML；编译测试覆盖 system/user/assistant 边界 | 仍需要设备上的真实模型回归，不能仅凭编译断言模型行为 |
| Phase 2：统一检索器 | **2A/2B、V1 relation 与 V1 claim 兼容已实现** | V2 负责带时间/认知边界的事件事实；`V1GraphKnowledgeStore` 读取定向审核关系；`V1ClaimKnowledgeStore` 只读取 `story_event` claim 并复用角色/cutoff/审核过滤 | V1 与 V2 的原始事件覆盖高度重叠；V1 仅是 V2 无授权命中或不可用时的兼容源，不作为第二路并行事实 |
| Phase 3：提示词注入/回退 | **3A + V1 relation + V1 claim + 7A 角色资料已实现** | V2 facts 或 V1 兼容 facts 写入 system 的 `authorized_story_facts`；V1 relation 写入独立的 `relevant_relationships`；角色卡中与问题相关的背景、动机、原则、性格和行为准则写入 `relevant_profile` | 代词续问的事实消歧仍主要依赖会话摘要和模型，尚未做查询改写 |
| Phase 4：端侧质量验收与优化 | **4A、4B、4C 与 Phase 6 已完成** | 2026-10-16 Pixel 9 Pro XL API 36.1、V4.6/Q4：4B 确认图谱开时 V2 `ready/91 chars` 且旧剧情不执行；4C 因果题图谱开 3/3 直接使用 V2 事实；Phase 6 覆盖六类问题各 3 次，未出现系统控制标记泄露 | 外部资料内容仍可按用户问题被模型引用；续问质量尚不等于已完成的实体消歧 |

当前 `CHARACTER_RAG` 实际检索链是：

```text
身份/闲聊 -> 不查询图谱或 story_events，仅使用角色卡 system context
关系问题 -> V1 relations.jsonl -> 当前 NPC 为 source 的审核关系 -> system relevant_relationships
剧情问题 -> V2 knowledge_fallback.json -> 时间/认知/审核硬过滤 -> system authorized_story_facts
  -> V2 无授权命中或不可用时：V1 claims.jsonl（仅 story_event，复用角色/cutoff/审核过滤）
  -> V1 也无授权命中、不可用或图谱手动关闭时：story_events.jsonl -> KeywordStoryRetriever -> KnowledgeBoundaryFilter
  -> V1 不可用时：角色卡 relationships 回退
```

下一步是 **Phase 7B：交互式验收与续问消歧**。在真实聊天 UI 中按固定脚本核验会话保存、摘要、角色切换和图谱开关；
仅当 Phase 6 中“他为何这样做”这类代词续问的人工判断仍不稳定时，再实现保守的查询改写/实体承接，不能为了提高命中
而把历史角色事实直接拼回 prompt。

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

**当前状态：3A（V2 facts）与 V1 relation 已实现。** 已完成的 ChatML native 传输修复是本阶段
的前置条件，不属于图谱注入本身：今后必须保留“system/user/assistant 边界”回归断言，不能因接入
图谱而回退为裸文本。

4B 优化后，`RuntimeContext` 默认启用 `knowledgeGraphEnabled`，但按问题意图调整实际工作量：身份/闲聊
跳过所有知识检索；明确关系题只查 V1；剧情题将 V2 限制为最多 2 条/180 字符，V1 定向关系限制为
1 条/96 字符。仅当 V2 无授权命中、不可用或被开关关闭时，才调用旧的
`KeywordStoryRetriever + KnowledgeBoundaryFilter + MemoryRetriever`。图谱事实只保留在 system；外部 RAG
原文仍只经 `CharacterRagPromptBuilder` 放入 user。`CharacterPromptDebug` 和 Logcat 记录无正文的意图、
图谱状态、注入字符、旧链路是否执行及 token 估算。

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
   外部文档 prompt injection、连续多轮记忆。输出文本经 `RoleplayOutputObserver` 观察；只有图谱已
   匹配且因 cutoff 拒绝、同时没有任何授权图谱事实可答的未来事实问题，才允许前置守卫返回固定保守回答。

#### Phase 4A 已执行的端侧验收

新增 `CharacterGraphRuntimeAcceptanceTest` 并在 API 36 ARM64 模拟器执行。测试使用目标应用的
`filesDir/harness`，而非源码目录：它重跑资产安装、从运行时路径创建 `CharacterPromptCompiler`，
确认 `lu_jiangxian + evt-018` 的 V2 授权事实进入 system、原问题保持在 user，且在 `evt-010`
同一事实得到 `ACQUIRED_AFTER_CUTOFF` 决策而不进入 system。Logcat 只输出
`status`、system 字符数、图谱注入字符数和未来事实已阻断标记；不记录玩家问题或事实正文。

该验收证明端侧图谱数据路径和防剧透边界，不证明模型一定不会自行幻觉未来事件，也不构成图谱
开启/关闭的性能对比。完整模型输出、prompt token、prefill、TTFT 与人工角色一致性保留给后续
质量验收，并须使用相同角色、cutoff、问题和 `predictLength` 的重复样本。

V1 relation 的同一 instrumentation test 已扩展为验证 `bai_junyi -> xuan_an` 的定向关系进入
system，并检查注入字符数。2026-10-16 在 Pixel 9 Pro XL API 36.1 成功安装当前 Debug APK 后，
该 instrumentation test 执行 1/1 通过。此前的 `INSTALL_FAILED_INSUFFICIENT_STORAGE` 已不再阻断
安装；真实生成与延迟基线见下方 V4.6 A/B 探针，不能由这项 prompt 编译验收替代。

#### 4B 真实 V4.6 A/B 探针与已知限制

新增 `CharacterGraphNativeInferenceTest`，固定 `minicpm-v-4_6-instruct`、`lu_jiangxian`、
`evt-018`、问题“玄谙为何还要毁掉明阳？”和 `predictLength=64`，分别编译图谱开/关 prompt 并
通过同一 `LlamaEngine` 的真实 JNI `setSystemPrompt`/`sendUserPrompt` 测量 system prefill、TTFT 和
完整生成时间。测试通过 `adb install -r` 更新 test APK 后再由 `adb shell am instrument` 启动，不重装
目标 APK；结束后已再次确认两个模型文件仍完整。固定问题下的 3+3 样本没有输出 `<|system|>` 标记，
未观察到系统提示词泄露；但两组回答都未充分回答“为何”，所以不能将图谱接入视为准确性或角色一致性的提升。

| 条件 | system/user chars | native token | system prefill | TTFT | 完整生成 | 路由 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 图谱开（3 次均值；范围） | 734 / 11 | 493 / 10 | 3650 ms；3644–3661 | 219 ms；197–263 | 542 ms；415–663 | V2 `ready`，91 字符，旧剧情未执行 |
| 图谱关（3 次均值；范围） | 886 / 11 | 614 / 10 | 4548 ms；4467–4691 | 194 ms；193–197 | 477 ms；344–583 | V2 `disabled`，旧剧情执行 |

性能结论限于这套固定条件：图谱开减少 121 个 system token（约 19.7%）和约 898 ms system prefill
（约 19.7%）；但 user TTFT 平均增加约 25 ms，完整生成时间受输出长度差异影响，不能比较为生成速度收益。
质量结论为未通过：图谱开三次分别只给出泛化提问、要求“仔细考虑”、或无证据猜测；图谱关三次均为
“想毁掉明阳”的复述/追问，没有一次直接给出被授权事件中的原因。后续验收继续使用上述保留数据的 ADB
路径；不要在模型位于 `filesDir/models/` 时使用 `connectedDebugAndroidTest`，该任务可能重装目标 APK
并清空私有模型目录。

#### 4C 回答可用性修复与复验

问题不在 V2 的权限或时间过滤：4B 的 `v2=ready/91` 已证明授权事实确实进入 system，但通用的
“此刻确实知道的事”标签没有让 V4.6 将该事实作为答案。模板现将该区域标为“回答剧情问题的唯一依据”，
并明确“能作答就先说结论，不得猜测或反问”；用户原问题仍保持为独立的 user message。

`CharacterGraphNativeInferenceTest` 也改为可判定的因果问句：固定
`xuan_an + evt-018 + “你为什么停止拼合七枚鉴身碎片？” + predictLength=64`，图谱开/关各运行三次。
图谱开启均为 V2 `ready/116`、旧剧情未执行，三次第一句均直接给出“避免七枚碎片归位导致自身消亡”的
授权原因；没有 `<|system|>` 或提示词正文。测试要求图谱开至少 2/3 次出现该原因关键词且不使用回避语，
实际 3/3 通过。图谱关闭的旧 `story_events.jsonl` 回退也能回答这条直接因果问题，因此这不是“质量超过旧库”
的结论，而是对图谱路径的回答可用性门槛验证。

| 条件 | system/user chars | native token | system prefill 均值 | TTFT 均值 | 完整生成均值 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 图谱开（3 次） | 812 / 15 | 556 / 10 | 4158 ms | 198 ms | 977 ms |
| 图谱关（3 次） | 863 / 15 | 595 / 10 | 4451 ms | 196 ms | 1136 ms |

此表只适用于 4C 固定问题；它显示图谱开少 39 个 system token、平均少约 293 ms system prefill，完整输出长度
不同，不能外推为一般生成速度结论。JVM 全部单测、Debug APK 和 Debug AndroidTest APK 均已构建通过；设备用例
1/1 通过。用 `adb install -r` 覆盖安装，模型文件保留。

#### Phase 5 V1 claim 兼容与跨图谱去重

新增 `V1ClaimKnowledgeStore`，读取运行时 V1 `manifest.json + claims.jsonl`，但只接受
`claim_type=story_event`。V1 的其余 187 条 `character_observation` 是角色卡/约束类信息，若把它们当成
剧情事实注入，会绕过 V2 对 profile scope 的拒绝，因此一律以 `PROFILE_CLAIM` 丢弃。

每条 V1 story claim 仍要求：当前角色在 `known_by` 中、`available_after <= storyCutoff`、来源和审核状态
被接受、获得方式为 direct/witnessed/reported/inferred，且与玩家问题有词法命中。V2 返回任一授权事实时，
编译器不调用 V1 claim store，debug 记为 `skipped_v2_authoritative`；只有 V2 无授权命中或不可用才查 V1，
所以同一事件不会来自两个图谱并行注入。V1 也无事实时，才调用既有 `story_events.jsonl` 回退。

新增 JVM 回归覆盖 V2 不可用时的 V1 注入、V2 命中时的 V1 跳过，以及 `evt-009` 在 `evt-008` 被 V1 cutoff
拒绝。`CharacterGraphRuntimeAcceptanceTest` 在 Pixel 9 Pro XL API 36.1 上从目标应用 `filesDir/harness`
运行，模拟 V2 不可用后成功选择 V1 `event-evt-009`、未执行旧剧情检索，设备用例 1/1 通过。该用例验证
提示词编译和资产路径，不测量模型生成质量或延迟。

#### Phase 6 多轮与安全角色一致性回归

新增 `CharacterRoleplayRegressionTest`，固定已安装的 `minicpm-v-4_6-instruct`，用真实
`LlamaEngine` 执行知识因果、关系态度、未知事实、未来剧透、外部资料 prompt injection 与包含会话摘要的
代词续问；每类各 3 次，`predictLength=64`。测试同时断言 system/user 分层、V2/V1 路由、未来 claim 的
`ACQUIRED_AFTER_CUTOFF` 决策，以及每个可见回答不含 `<|system|>`、未解析模板标记或角色卡身份语句。

首次设备回归发现一个运行时缺口：`evt-020` 因 cutoff 被排除后，V4.6 仍会自行断言“已死谢罪”。因此新增
`RoleplayAnswerabilityGate`：只有审核决策显示本轮词法命中的 future claim 被 cutoff 拒绝，且没有 selected 的
授权图谱 fact 时，才跳过模型生成并回复“此事尚未发生，我不能妄言。”。为避免未命中的 future claim 触发该守卫，
V1/V2 store 现先判定词法命中、再审计角色认知与时间；这只改变无关 claim 的 debug reason，不放宽任何授权条件。

2026-10-16 最终在 Pixel 9 Pro XL API 36.1 上以 `adb install -r` 安装 Debug/AndroidTest APK 并完成回归：

- 因果题 3/3 使用 V2 `ready/116` 的“避免自身消亡”事实；关系题 3/3 使用 V1 relation `ready/44`。
- 未知问题、外部资料与带摘要的续问均未被 future 守卫误拦；三类共 9 次走 V4.6 真实生成。
- 未来剧透 3/3 走前置守卫并返回固定保守回答；不再让模型生成未来断言。
- 共 15 次真实 V4.6 生成及 3 次守卫回答均未检测到系统控制标记或角色卡泄露。外部资料仍位于 user message，模型可以在
  用户明确询问时概述其内容，但没有执行“完整复述角色卡”的注入指令。

该阶段是功能/安全回归，不是新的性能 A/B：未把输出长度不同的样本换算成 TTFT 或吞吐结论。代词续问能保留上轮
主题，但自然度和事实精度仍需在下一阶段的 UI 验收中人工评估。

#### Phase 7A 角色自身资料的查询感知扩展

为减少“有角色卡却频繁回答不清楚”的情况，`CharacterPromptCompiler` 新增独立的 `relevant_profile` 区块。
它只从当前角色的 JSON 角色卡选择背景、动机、价值观、性格、行为准则和硬约束，按问题词面和类别信号排序，
每轮最多 2 项、180 字符。它不读取 V2 `profile` claim，因此不会绕过 V2 对剧情事件、他人私密认知和 cutoff 的
过滤；未来剧情守卫也仍只依据 V1/V2 story claim 的审计结果。

提示词改为：涉及角色自身的来历、动机、性格、原则、偏好或打算时，即使没有具体剧情事件，也可根据该区块直接
表达立场或选择；具体经历、他人内心和未来事件仍不可编造。JVM 测试覆盖“你从哪里来？”会注入陆江仙的来历，
并保持 900 字符预算。2026-10-16 在 Pixel 9 Pro XL API 36.1 的真实 V4.6 上执行
`CharacterProfileNativeInferenceTest`，1/1 通过：输出非空、不含“尚不清楚”或 system token，并含鉴身/太阴的
来历线索。

## 5. 迁移顺序、风险与完成定义

调整后的顺序为：**完成 Phase 0 设备决策门 → Phase 2A（仅 V2 facts、无提示词注入） →
Phase 2B（V2 评分/降级/审计，已完成） → Phase 3A（V2 注入与 story-events 回退，已完成） → Phase 4A
（设备功能与防剧透，已完成） → V1 relation（已完成） → Phase 4B 路由/观测与受控基线（已完成） →
Phase 4C 回答可用性修复与端侧复验（已完成） → Phase 5 V1 claim 兼容与跨图谱去重（已完成） →
Phase 6 多轮/安全角色一致性回归（已完成） → Phase 7A 角色自身资料扩展（已完成） →
Phase 7B 交互式验收与续问消歧**。
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
