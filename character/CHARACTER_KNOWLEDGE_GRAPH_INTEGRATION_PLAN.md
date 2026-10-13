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

验收：在 Android 上对 `lu_jiangxian + evt-018` 完成一次数据库打开、候选查询和关闭；
失败分支有可重复的导出命令、产物校验与同等的检索测试。

### Phase 1：资产打包、升级和路径

**目的：把图谱变成 App 能安全读取、可随资产版本升级的数据，而不是在源码根目录读取。**

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
3. 扩展 `AssetBootstrapper`：按 manifest 的 schema/version/内容摘要决定是否升级；先复制到
   临时文件、校验可打开和版本正确，再原子替换。运行期数据库始终只读，不能直接在 APK
   asset 内打开。
4. 在 `app/build.gradle.kts` 中为 `sqlite3` 添加 `androidResources.noCompress`；这样 APK
   内的预构建数据库可以被可靠复制，且不会产生不必要的启动解压成本。
5. 把“资产版本”和“当前检索器版本”写入 debug 日志，不记录完整用户提问或证据原文。

验收：冷启动后 `filesDir/harness/characters/knowledge/v2/character_knowledge_v2.sqlite3`
存在、可只读打开；升级 manifest 后只替换对应知识库，旧版失败时仍保有最后一次有效文件。

### Phase 2：统一领域模型与检索器

**目的：在不让 UI 感知 V1/V2 差异的前提下，保留每条结果为什么被允许或拒绝的审计信息。**

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

建议按 **Phase 0 → 1 → 2（仅 V2 fact）→ 3 → 4 → V1 relation → V1 claim 兼容** 的
小步顺序提交。这样先让“事件/认知”这个最关键的防泄漏模型落地，再加入关系表达，避免
两个数据源同时改变导致问题无法归因。

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
