# Phase 3：当前数据库检索消费者接入验收

日期：2026-10-28  
范围：统一数据路径计划的 Phase 3；Phase 4 的完整历史/缓存隔离与 Phase 6 删除不在本次完成范围。

## 实际运行链路

```text
Application → DatabaseBundleInstaller → DatabaseRuntime.requireBundle()
  → AndroidRagOrchestrator.fromContext()
      → fromBundle()：复核包文件与句柄 fingerprint
      → RagSearchService.fromBundle()：当前包 vector_index → 内存 VectorIndex
      → CharacterPromptCompiler.fromBundle()
          ├─ characters / story_events：同一包的角色与时间轴
          ├─ runtime_v2 / v2_database：同一包的已审查导出和源 hash
          └─ 独立程序模板：filesDir/program/prompts/roleplay_system.prompt
```

每轮 active RAG 搜索当前内存向量索引；不会重新扫描电脑目录，也不执行设备 SQLite FTS。
旧 `HarnessDataPaths` 的知识路径仍保留在待清理的 fixture 工厂声明中，但生产 `fromContext()` 没有调用这些工厂。

| 模式 | 当前行为 |
| --- | --- |
| OFF | 普通聊天；诊断开关允许观测检索时，也只查询当前包；检索资料不注入 |
| RAG | 当前问题查询本包向量索引，按相关性/预算选取资料放入 user 消息 |
| CHARACTER_RAG | 当前问题查询同一索引；已审查事实候选与 V2 词项召回合并，再经角色、时间、来源和预算过滤后进入 system |

保留既有向量诊断开关：默认每轮检索，显式关闭时 trace 报 `disabled_by_flag`。
角色事实的审核/认知检查不会因知识图谱开关关闭而被绕过，也不会因此启用原始场景 fallback。

## 关键实现

### 1. 唯一数据源与外部分支移除

- `RagSearchService.fromBundle()` 从 manifest 的 `vector_index` 取得路径，collection 为 `database:<dataset_id>:<fingerprint>`。
- `AndroidRagOrchestrator.fromContext()` 取得已安装句柄与独立模板，构建当前包消费者；Phase 2 临时报错已经移除。
- 删除 `CharacterQueryRouter`、外部关键词触发和 `/novel_test/` 黑名单分支。角色模式不向 `CharacterRagPromptBuilder` 传递原始向量文本。
- 角色模式的 user 消息仍是当前问题，未经授权的数据库场景不会从 user 侧绕过角色认知边界。
- 向量日志候选使用真实 chunk/claim ID；collection 自带当前数据集身份。原 `external` trace 字段名暂保留以兼容日志消费者，其 collection 已能明确区分当前库；字段名称整理可在后续清理阶段处理。

### 2. 审核事实与混合召回

`V2EpistemicKnowledgeStore` 取得当前 `ValidatedDatabaseBundle` 时才接受 `canon / source_span_verified / accepted`，并要求事实 ID 与正文属于独立审核的授权清单。
仍未向旧 fixture 的 `curated / verified_curated` 政策添加无条件放行。

向量 `reviewed_fact` 候选的 ID/score 经 `RuntimeContext.vectorCandidateScores` 传给 V2 查询：

1. 已审查事实可由词项命中或向量候选进入审核。
2. 原始 `scene_candidate` 不提升成可信事实，也不把 parent 场景全段拆成角色已知内容。
3. 必须具备当前角色授权边、`believed` 状态、合法来源和截止点内的获取/有效时间。
4. 排名结合词项相关性、事实属性和向量分数；最终仍受 topK、事实段预算及整个 system 的 900 字符预算约束。
5. 来历类问题补充“经历、背景、职业、专业、过去”等通用检索词，不写死当前小说人物或专业内容。
6. 追问事实 ID 仍执行角色/时间/来源检查。

装载 V2 运行导出时复核 hash；生产工厂构造时复核当前包，数据损坏或身份变化不会静默使用旧路径。
`MainActivity` 编译失败会返回明确的数据包错误提示，不再偷偷退回裸问题推理。

### 3. 时间状态、角色卡与关系

新 `DatabaseCharacterCardAdapter` 使用当前包 cutoff 的 sequence，而不是比较字符串或旧库事件序号。

- 角色卡是其声明截止点的完整描述，只有回合 cutoff 达到该点时才使用完整背景、动机、别名和定向关系。
- 更早回合只保留基本姓名、语气等，不使用全传身份结论、关系或经历；具体经历依靠已审查事实。
- 当前 `timed_state=false`，不把卡内末场景、任务、目标和情绪当作当前状态；位置和情绪明确为未知，随身物品无可确认记录。
- 当前 `v1=false`，不构造 V1 stores，不读取旧 V1 补齐。
- 旧事件/episodic memory fallback 在生产包模式完全关闭，不能借 story 中批量生成的 `direct` 边绕过审核。
- 当前源 V2 的 8 条关系没有带角色认知审核的运行导出，故报告 `v2_relations_not_authorized`，没有批量授权。已在当前角色卡中声明的关系仍按卡的有效截止点、发起角色及方向使用；例如罗辑对史强的态度不会反向当作史强对罗辑的态度。

`databaseStatusCodes` 在日志、JSONL 和调试文本中记录 `v1_unsupported`、`timed_state_unsupported`、`v2_relations_not_authorized`、`legacy_fallback_disabled`、`profile_scoped_to_card_cutoff`。
纯 STORY_FACT 路由标签已修正为 `story_fact`，不再落为 `unknown`。

## 验收证据

### 主机与构建

```bash
bash ./gradlew :app:testDebugUnitTest :app:verifyDebugDatabaseAssets :app:assembleDebugAndroidTest
```

JVM 全量 **145 项通过，0 失败，0 跳过**，新增 13 个共享新库检索场景。Debug APK 和测试包构建通过。
实际 APK 仍只有 13 个当前知识文件、3 个程序资源，`assets/harness/` 文件数为 0。

数据集与生成产物未改写：

```text
dataset_id: three_body_2
dataset_version: 2026-10-27-phase1
dataset_fingerprint: 4d972793125e2867292b3b9944614d3060f1ffce54c716306ba8842f06605a44
vector: 128 records / 384 dimensions
authorized facts: 3
```

### Android

在现有 ARM64 `Pixel_9_Pro_XL` / `emulator-5554` 上用 `install -r` 覆盖安装，未卸载、清数据或删除模型。

```bash
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.minicpm_v_demo.harness.data.database.DatabaseRetrievalDeviceTest,com.example.minicpm_v_demo.harness.data.database.DatabaseBundleInstallerDeviceTest \
  com.example.minicpm_v_demo.test/androidx.test.runner.AndroidJUnitRunner
```

**28 项设备测试通过**：14 项检索/隔离测试，加 14 项安装器回归。安装器设备测试的临时“消费者迁移报错”断言已替换成生产工厂实际列出当前五角色。

| 核验点 | 具体断言 |
| --- | --- |
| 正向事实注入 | 早期职业问题命中 `reviewed-luo-ji-profession`，最终 system 包含“大学教社会学”，注入字符数大于 0，system 不超过 900 字符 |
| 当前问题改变证据 | 杨冬高中同学问题选择 classmate，宇宙社会学公理问题选择 axioms；动态证据 fingerprint 和最终 system 不同 |
| 传闻语义 | 公理按 `reported` 保留“你听人提及”语义，不变成已验证宇宙定律 |
| 未来过滤 | `evt-001` 不注入 `evt-002` 公理，决策含 `ACQUIRED_AFTER_CUTOFF` |
| 角色过滤 | 同一公理不会进入史强 prompt，追问旧事实 ID 也不能绕过 |
| 向量单独命中 | 即使无词项匹配，reviewed 向量候选可以召回；换角色后拒绝；原始场景候选不能被提升 |
| 全传与状态隔离 | 早期 prompt 不包含“执剑人”等全传结论；状态/物品无未经时间核验的注入 |
| 来历查询 | 早期来历问题可由当前库职业短事实支撑，不需要复制完整背景 |
| 关系时间范围 | 罗辑对史强“高度信任”的卡内关系仅在卡截止点生效，早期不注入 |
| 模式与开关 | OFF/RAG/CHARACTER_RAG × 向量开关 × OFF 观测开关 × 图谱开关，任何执行检索的回合都绑定当前包 |
| 旧关键词 | “世界杯、外部资料、文档、搜索”等不请求旧外部注入 |
| 损坏拒绝 | 安装后破坏索引，无法创建新的消费者；测试恢复原字节 |
| 身份与路径拒绝 | 非当前库角色、越界角色 ID 及未知 cutoff 在编译入口明确拒绝；角色 loader 同时限制文件必须属于角色目录 |
| 旧文件零读取 | 在测试根目录放入旧 Harness 索引、旧 character manifest、旧角色卡诱饵，用 Android `FileObserver.OPEN` 监测；上述模式/开关回合累计 OPEN 为 0。随后主动读取诱饵得到事件，证明监测有效 |

设备测试在专用 cache 子目录安装场景包、创建诱饵；没有修改真实旧知识副本。真实 Application 启动及生产工厂测试另验证实际 `filesDir/database` 与五角色。

本阶段验证的是实际 Kotlin→文件→检索→最终 prompt 链路，没有运行 MiniCPM 原生模型问答质量回归；不能据此宣称所有模型都能正确回答或不再出现措辞问题。

## 会话过渡与后续边界

为使本阶段可运行，启动默认角色/cutoff 取当前 manifest，避免旧 `lu_jiangxian / evt-010` 恢复进新库。
现有持久化会话尚无新数据包 fingerprint，当前不自动恢复；原文件保留。同一次启动中创建的新会话仍能积累摘要、历史与追问。

Phase 4 应完成 dataset/fingerprint 持久化、兼容历史恢复、Memory 与 follow-up 隔离、同包换版本缓存失效及生成期间切换协调。
本阶段只在启动时绑定包，不提供运行中热切换。旧路径 fixture 工厂及旧数据目录按 Phase 6 的明确清单删除；它们未被生产检索工厂调用。
