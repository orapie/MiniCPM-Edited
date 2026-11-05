# RAG 检索相关性改进方案

状态：V2.2 阶段 2、3 工程已接入；阶段 4 已按用户要求完成精简验收。构建、4 项定向单测和 1 项模拟器冒烟测试通过；融合仍为默认关闭的实验模式。样本暴露检索相关性不足，不能据此断言准确率提升或完整验收达标。此前旧库评测结果仍只对旧库成立。

## 2026-11-03：新语义数据库接入计划（当前实施依据）

本节覆盖下面 2026-11-02 及更早针对旧库的“维持现有索引”和阶段顺序；下文旧库描述、样本和性能记录仅保留为历史基线，并非对新库当前可运行状态的描述。仍使用项目根目录 `database/`、现有 APK 打包和应用私有目录安装，不重新启用 `character/` 或旧 Harness 外部库；不处理简繁转换。阶段 1 已实现，阶段 2–3 工程已落地，阶段 4 已按用户要求完成少量构建和模拟器链路验收；全面质量评测未进行。下方最初检查结果为实施前的历史记录。

### A. 实施前检查记录（历史）

1. 新源库为 `database/knowledge_base_v2/character_knowledge_v2.sqlite3`，源 manifest 为 `knowledge_base_v2/manifest.json` 的 `schema_version=2.2.0`。它包含 536 条 `evidence_units`、536 条 `claims`、6,179 条 `retrieval_expressions`、768 条角色认知边；SQLite `PRAGMA integrity_check` 返回 `ok`。`embedding_indexes` 有一个活动索引 `semantic-8ffbc4273cb1aaa2`，`evidence_embeddings` 有 536 条记录，每条 2,048 字节（512 × float32 little-endian），与 evidence 一一对应；抽核全部向量的 L2 范数约为 1，全部文本 hash 和总内容 hash 与 manifest 一致。这些检查不证明向量是由所声明模型计算，也不证明文本或认知标注正确。
2. 索引声明模型 `BAAI/bge-small-zh-v1.5`、revision `7999e1d3359715c523056ef9478215996d62a620`、CLS pooling、L2 归一化，passage 内容为 `{title}\n{fact}`，查询需添加 `为这个句子生成表示以用于检索相关文章：`。配置还包括 tokenizer、截断长度、特殊 token、编码精度及 padding 等，需要对照 `three_body_2_kb_builder/scripts/build_semantic_index.py` 所用模型实际配置锁定并做跨端 golden；不能仅以维度相同判断兼容。当前仓库数据库包含向量和元数据，不包含可直接在 Android 加载的 BGE 权重与 tokenizer；构建脚本依赖 `sentence-transformers`。当前本机 Python 环境也未发现 `sentence_transformers`、`numpy`，此次未运行 BGE 模型或声称设备侧语义检索可用。
3. 原有 Android 路径仍为 `MainActivity → AndroidRagOrchestrator → AlwaysOnVectorRetrievalService → RagSearchService.fromBundle → VectorIndex + HashEmbeddingModel`。它只加载包清单所指 `runtime/android/vector/index.json` 的 384 维 hash n-gram 向量，schema `1.2`、1,339 个片段和 125 个旧 parent；`VectorIndex` 按旧 parent/片段权重得分。当前没有 Android 消费 `evidence_embeddings`、`retrieval_fts` 或 BGE 查询模型的链路；上层 `EmbeddingModel` 接口存在并不代表已支持新模型。普通 RAG、角色 RAG 都需明确新后端行为。
4. 根 `database/manifest.json` 仍是旧 bundle 指纹 `8145513ad935bd449fe50d72ebb95e895097ef835e8d415e7c033e77796ae84e`，12 项受控文件中 8 项与实际内容 hash 不一致（5 张角色卡、story events、V2 manifest 和 SQLite）；旧向量索引记录的 SQLite SHA-256 是 `3e15c91e…`，当前 SQLite 实际 SHA-256 是 `45c3dded…`。本次只读运行 `python3 scripts/prepare_database_bundle.py --check`，首先报 `Bundle file/hash mismatch: characters/ding_yi.json`。不能仅更新清单哈希：现有导出也来自旧源库。
5. 即使解决哈希，`scripts/prepare_database_bundle.py:read_source` 和 `DatabaseBundleValidator` 仍显式要求 V2 `2.0.0`。旧检查要求 `claims.statement` 与原文 `evidence.quote` 完全相等；新库 536 条均不相等，新 `fact/statement` 包含生成标题、摘要或截断文本，原文核验应针对 quote 及精确来源，而不能放宽为任何标题均可信。旧 `reviewed/atomic_facts.json` 的 3 条证据仍引用旧 `claim-evt-*` / `evidence-evt-*`，这些 ID 在新 SQLite 均不存在，需根据原文与角色认知重新映射、审核和版本化，不可静默丢弃或直接赋权。
6. 新边中 466 条标为 `approved`，302 条为 `candidate`；approved 主要来自 `named_speech_or_cognition_cue`、`active_dialogue_participant` 等方法。`approved` 是新数据源的标注，不自动等同于本项目原先的人工 `dialogue_context_inspection` 政策；新库片段可能含旁白及截断内容。默认禁止把全部 `approved`、`fact`、标题、预测问题或完整证据片段整体升级为角色已知事实，先制定可审计的映射、原文核验和人工抽查标准。`candidate` 只能作为受控参考候选，不能通过 RRF 或分数变成角色知识。
7. `three_body_2_kb_builder/scripts/query_evidence.py` 已实现 PC 端 eligible evidence、词项召回、BGE、按 `evidence_id` 去重和 RRF（`k=60`），可作接口和对照参考，不是 Android 已实现能力。数据包自带的 11 例评测报告 lexical 6/11、semantic 8/11、hybrid 9/11；题目少且分组不足，数据和实现环境未在本次复现，不能用来宣称当前 App 的准确率。

### 阶段 0 进展（2026-11-03）

已建立只读审计与样本基线工具，详细结果见 [V2.2 阶段 0 报告](Iprv_RAG_V22_Stage0_Report.md)：源库结构核验通过但旧 Android bundle 仍不兼容；12 个样本经 Codex 对照原文初审，独立人工认知审核和扩充尚未完成。旧审核事实仅定位到新库候选，尚未迁移授权。阶段 0 工程准备已完成，完整质量验收待独立审核；此段记录的是当时进度。

### 阶段 1 进展

阶段 1 已完成：V2.2 数据包生成、Python/Android 来源校验、原有 3 条审核事实迁移、APK 打包和模拟器安装测试均已实施。该阶段 fingerprint 为 `785262c2476a3bc9d6fa4889c139f914cf2944c22b5b4653cfb35c20bc6aea7c`，当时仍使用 hash 索引；新源库角色边的独立人工审核仍待完成。详见 [阶段 1 报告](Iprv_RAG_V22_Stage1_Progress.md)。

### 阶段 2 工程进展（2026-11-03）

已引入固定 revision 的 BGE ONNX 查询编码器、WordPiece 分词和 SQLite 语义向量的受控导出，普通 RAG 和角色 RAG 通过当前数据包选择 `bge_onnx`；角色模式在排序前按来源范围和 cutoff 过滤。模型加载或编码失败记录失败，不静默切换 hash。旧格式数据包仍沿用其 hash 后端。语义命中仅作为参考候选，不等于角色知情。

根数据包 fingerprint 更新为 `2c0b90db9383839146dbd703a4bd7ab2e74040760a31ba3549877d5130df5a53`。本次按用户要求不进行最终构建、设备验收及效果评测；阶段 2 的工程落地不代表原计划中的验收已通过，阶段 3 随后已进行工程接入，阶段 4 已完成精简模拟器验收。详见 [阶段 2 工程说明](Iprv_RAG_V22_Stage2_Report.md)。

### 阶段 3 工程进展（2026-11-03）

已在同一 BGE 证据索引上接入 Kotlin 词项召回，按 `evidence_id` 与语义候选以 RRF（k=60）合并；候选先按现有来源和角色 cutoff 筛选，角色参考资料继续经过 `CharacterReferenceFilter`，不会因命中升级为角色已知事实。融合排序保留到 prompt 编译，不再叠加旧的词项分数重排。日志保留最终候选各路排名、分数和融合分数，以及资料筛选与截取决定。当前词项实现查询证据原文，并非 SQLite FTS 或完整的 `retrieval_expressions`；空词项产生空词项候选。语义召回仍使用既有余弦门槛，RRF 不套用该门槛。

根据原计划，混合检索须在验证集上优于语义基线后才能默认启用；本次依用户要求不验证，因此 `rag_retrieval_flags` 的 `hybrid_retrieval` 默认为 false，可通过应用偏好设置为 true 后重新绑定检索器以启用实验。尚无应用界面开关。现有阈值、空答案识别及候选截句属于启发式，无法保证相关性或无答案时一定返回空；审核事实仍通过现有 V2 授权词项路径处理。独立质量比较、完整数据授权审查仍未完成；阶段 4 已完成精简验收。

### 阶段 4 精简验收（2026-11-03）

按用户要求只进行少量测试：Debug APK 构建和 APK 数据清单检查通过；4 项 BGE tokenizer、ONNX 编码和 V2.2 数据包定向单测通过；Android 16 Pixel 9 Pro XL 模拟器上 1 项冒烟测试通过，覆盖普通/角色 RAG 的语义与实验融合路径，确认 BGE 查询执行、词项参与融合、资料进入 prompt 和角色截止点限制。详见 [精简验收报告](Iprv_RAG_V22_Stage4_Report.md)。

该验收并未运行对话模型生成或完整评测。问题“罗辑的职业是什么？”的首个语义命中讨论另一人的工作，而非罗辑职业的直接答案；混合候选出现学者相关片段，但单例不足以证明准确率提高。融合继续默认关闭；真机、真实模型回答、性能分布、完整安全负例和独立验证集均未覆盖。

### B. 目标链路及实施顺序

```text
构建期：database/ V2 2.2 源文件和语义索引
  → 新版本导出 / 完整校验 / 审核记录与 fingerprint
  → prepareDatabaseBundle 受控 APK assets
设备端：安装到 filesDir/database/ 并验证 → 固定当前 bundle
  → 用户原始问题 → BGE 同模型查询向量（含 query instruction）
  → 对从本包 evidence_embeddings 导出的只读 BGE 索引做语义检索
  → 按角色 / cutoff / 来源 / 授权分流，必要时融合词项候选
  → evidence_id 去重 → 保留有来源、实际可回答的证据 → system 或 user
  → BACKEND_REQUEST、决策 trace、生成与 guard
```

**阶段 0：新库审计与标签基线。** 增加只读检查脚本/测试，核验 schema、SQLite 完整性、唯一活动索引、模型 metadata、`document_count`、每条向量长度/有限值/范数、evidence/claim/source/cutoff 外键、逐条内容 hash 和索引总 hash、JSONL/SQLite 一致性及 `quote` 对原文的行号和摘要。明确空值、未知序列和跨 cutoff 的拒绝策略；在副本中执行破坏测试，不改写现有新源库。比较现有 3 条已审核事实在新库中的候选位置，记录原审核依据和迁移结果，不能按名字推测或只重算 hash。

建立分角色、cutoff、来历/职业/关系/物品/事件/第三人/无答案及改写的人工证据标签，训练调参与保留验证按来源/场景和人物隔离，避免来自同一事实的 `predicted_question` 泄漏至验证集。记录样本和标注依据；保留原旧库测试为历史基线，不将旧 ID 当新库金标准。验收：产生可重复的只读审计报告和数据契约，损坏样本必须失败，质量问题与待人工审核项有明确列表。

**阶段 1：适配 2.2 数据包、来源及角色授权。** 更新 `scripts/prepare_database_bundle.py`、`DatabaseBundleValidator.kt` 和相关模型/测试，明确新 bundle/导出 schema、版本兼容与回退：新源库 `2.2.0` 按独立协议校验，不把版本号放宽后继续套旧字段。修正 `fact/statement` 与 `quote` 的关系：优先精确核对原文 `source_id + line_start/end + quote + sha256`，将 `title/fact/predicted_question` 视为派生检索信息，明确哪些经过额外核查才能作为回答依据；支持每个 evidence、claim、edge 的稳定 ID 和 provenance。新边 `authorization_status`、`belief_status`、acquisition method/sequence、内容级审核必须保留，缺失或不支持时保守拒绝；现有 3 条旧审核事实须人工复核映射到新 ID 与新原文，记录未能迁移的情况。不要根据 `approved` 或向量相似度批量赋权。

更新 `DatabaseBundleManifest` 及 validator 的能力、路径、模型/索引契约和所有受控文件 hash；Python 和 Kotlin 对同一个明确版本实施一致检查，先在临时副本生成完整导出并进行篡改测试，再按显式工具步骤生成根 `database/` 的新 bundle，**不手改 fingerprint，也不覆盖源 SQLite 以假装索引有效**。APK 必须真正包含指向新 SQLite 或经验证导出的 BGE 数据；独立 semantic manifest 是否作为运行时文件应通过清单与校验器一致声明，不能依赖没打包的路径。修复 `prepareDatabaseBundle` 和 APK inventory 检查新工件，同时保留同数据集失败时 `RETAINED_PREVIOUS`、异数据集失败不返回旧句柄的既有语义；旧包保留时显示实际 fingerprint/后端，不能误报新库。包版本、来源和模型变化后隔离会话并刷新消费者，不删除会话及模型。

验收：新 bundle 只从根目录打包并通过 `--check`、JVM 和 APK 资产检查；旧包/新包升级、不同数据集切换、失败保留、指纹变更、泄漏和破坏测试通过；错误 ID、错行、旧 SQLite hash、伪造授权和过期模型元数据均无法激活。记录实际 2.2 数量，不沿用旧库 125/3 的假设。

**阶段 2：设备端 BGE 查询编码器与语义候选。** 从同一精确 revision 获取经授权的可离线 Android 模型及 tokenizer（例如评估 ONNX Runtime + 对应导出；亦可选择已有 native 后端，但必须实测支持），确定权重、词表、配置、精度及许可证/分发方式；不得复用对话模型的 embedding 假装同一空间，也不能只有 SQLite 预计算向量。建立固定的查询 encoder 接口和按 bundle 的工厂，通过清单明确模型版本、维度、pooling、tokenizer 和 query instruction；模型加载与缓存受 bundle 生命周期管理，后台准备并报告加载和执行失败。保持用户原始问题不变，仅编码副本添加 query instruction。

为 Android 实现 `evidence_embeddings` 查询：从已安装受控 bundle 的 SQLite 以只读方式读取经验证的活动索引和向量 BLOB，按 `evidence_id` 精确关联 evidence、来源和角色授权；或在构建时生成有同等 hash/provenance 验证的只读导出，但两者只择一，保持 SQLite 为本包唯一来源。解码必须明确 `<f4`、512 维、有限值、L2，向量维数严格相等后点积；**修复/绕过 `EmbeddingModel.kt:cosine` 当前 `minOf` 截断行为，维度不等必须报错，禁止跨 384/512 空间比较**。做 Python 原模型与 Android 对同一批真实/短/长/空查询的 token IDs、向量、相似度、top-K 一致性验证，允许量化误差须给出阈值和实验；长文本截断规则和原模型一致。缓存索引/编码器时以 dataset fingerprint、index_id、model revision/tokenizer hash 为键，数据库切换或校验失败及时失效。

向量必须每轮从当前用户问题执行；明确角色候选只使用已验证的可用数据，过期、未知、跨 cutoff 的处理与现有边界一致。语义模型缺失/加载失败/维度不匹配须将 `semanticExecuted=false`、失败原因和真实后端写入 trace，不可标为成功或静默使用 384 维 hash 分数冒充 BGE。是否提供回退作为显式模式/产品策略实现并测试；不能使用旧外部数据库，不能把词项-only 结果称为 semantic。普通 RAG 的不按角色授权范围及其来源约束另行定义和测试，角色 RAG 不改变现有 system 与参考资料的信任边界。

验收：无网络环境使用固定设备模型完成真实 query embedding 与 536 条向量的端到端检索；固定源问题能复核 evidence ID/余弦得分，模型与数据混配和坏 BLOB 必须拒绝；记录加载成本、内存、安装包增加量和每轮 P50/P95，并验证普通/角色两种模式及多数据集。

**阶段 3：可解释词项召回、融合与 prompt。** 在阶段 2 的语义基线上，先实测目标 Android SQLite 对 FTS5 trigram 的支持，不能假设所有 minSdk 24 设备都支持；可选已验证的 SQLite 扩展或受控导出及 Kotlin 词项检索。若启用 `query_evidence.py` 的词项策略，分别测试 FTS 异常/空关键词/语法转义，按 evidence_id 去重，并显示检索失败而不是吞掉异常。语义与词项各自记录候选、名次与分数，按 `evidence_id` 融合；RRF `k=60` 仅作对照参数，融合后阈值和候选数根据独立验证集评估，不能把 RRF 分数直接套旧余弦 `minScore=0.10`，也不能只因材料非空而注入。过滤策略必须覆盖融合前后和两种模式，确保低相关、无答案可返回空。

适配 `CharacterReferenceFilter`、`CharacterRagPromptBuilder`、`CharacterPromptCompiler` / `RoleplayAnswerabilityGate`：按新 `evidence_id/claim_id`、证据范围与审核状态分开角色已知、未审核参考和拒绝项；对截断 `fact` 使用经过验证的 quote 及必要上下文，保留必要主语/否定/时间，不把标题或预测问题当答案。`RagSource`、`RagContextDecision`、`RagTurnTrace` 应记录 evidence ID、索引 ID、backend、各路 rank/score、过滤原因、实际保留范围、来源/fingerprint；隐私日志不输出用户原文、向量或原文内容。第三人及用户私密信息问题分别测试，语义命中不代表角色知情或问题可回答。

验收：在相同新库、角色和 cutoff 下比较 lexical、semantic、hybrid；统计 Recall@5/@20、MRR、最终 prompt 证据保留、无答案误注入和错误授权率，报告未命中及负样本。混合排序仅在隔离验证集优于语义基线且安全/负例无退化时默认启用，否则继续使用已验证的单路并说明原因。

**阶段 4：完整设备验证与交付。** 运行 Python 只读审计和相关单测、`python3 scripts/prepare_database_bundle.py --check`、`bash ./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:verifyDebugDatabaseAssets`；验证 hash、模型版本和 APK 包内实际内容。连接现有 Android 设备后覆盖安装，不卸载、不清空用户模型与会话，验证首次安装、同库升级、跨库、失败回退、飞行模式、模型缺失/故障、数据版本变化和并发回合。对当前已选对话模型做少量真实生成，记录 `BACKEND_REQUEST`、证据、gate 和可见输出，和检索指标分开；不要求测试其他对话模型。提供设备型号、数据 fingerprint、BGE revision、构建、离线条件、P50/P95、内存、APK 与安装体积、测试数量和失败样本。最后更新 `docs/RAG_Logic.md`、数据库 README 和本计划的真实实现状态及回滚步骤。

### C. 现阶段禁止操作

- 只改根 manifest hash 或运行旧生成器后宣称 BGE 已生效；当前旧生成器本身拒绝 2.2，且会按旧协议构建 384 维索引。
- 将 512 维 passage BGE 向量与 384 维 hash 查询向量混算；也不能只把 BGE 查询前缀加入原 hash 模型。
- 把 retrieval expression、自动生成标题、整条旁白或全部 `approved` 边无条件放进角色 system，或者绕过时间和 source 约束。
- 将新源库文件复制到旧外部库，或在设备验证时删除用户会话/模型；不能用旧安装包/旧 fingerprint 的成功日志证明新库生效。

## 2026-11-02 P1 实施进展

已补充问句后相邻答句的保留、实际保留句子在原片段内的 UTF-16 范围和资料排序分数日志，详见 [P1 本轮报告](Iprv_RAG_P1_Progress_20261102.md)。真实原文片段的定向测试证明一个被截掉的后续答句可保留，但真实身份问题的完整编排仍会选到无关句；没有证明整体检索或回答质量改善。P1 尚未完成，继续按本计划验证召回、排序、目标主体和无答案处理。

## 范围调整（2026-11-02）

本计划不再考虑简体与繁体之间的转换、统一检索或对照验收，也不为此重建索引。保留已有简繁样例和设备记录作为历史实验事实，但不作为未来任务、效果指标或完成条件。下一阶段集中在原始输入下的证据召回、排序、主体识别、无答案处理与实际 prompt 保留。Unicode、空白或标点处理仅在后续证据表明确有必要时评估；若改变向量输入，仍须同步更新构建端和查询端、版本与索引，不能混用。

## 0. 2026-10-30 Android 实测与计划调整（旧库历史记录）

### 实测范围与结论

在 `sdk_gphone64_arm64` 模拟器上覆盖安装当前 Debug APK，安装日志显示 `three_body_2`、fingerprint `8145513ad935bd449fe50d72ebb95e895097ef835e8d415e7c033e77796ae84e` 已成功激活。使用设备原先选中的 MiniCPM-V 4.6 和四个固定测试问题，通过当前 `AndroidRagOrchestrator → RoleplayAnswerabilityGate → LlamaBackendAdapter → RoleplayGenerationCoordinator` 运行真实 native 生成，单次最多 64 token。该 instrumentation 不经过聊天页面的完整交互和真实会话持久化；这是一次采样，不代表多次或其他模型的统计表现。

检索、system/user 组装、gate 和实际可见回答均已记录；`scripts` 诊断题与设备记录均为合成提问，不是用户聊天记录。内容无正文的候选/决策及回答见 [设备结果](evidence/Iprv_RAG_device_20261030.json)，`context_sha256` 用于关联本次现场内容；它本身不能还原片段或验证片段含义。设备在安装前后的旧会话文件 SHA-256 一致；未切换模型选择。

| 问题与角色/cutoff | 检索和最终资料 | 本次可见回答 | 判断 |
| --- | --- | --- | --- |
| 史强 `evt-118`：“罗輯现在的身份是什么” | `claim-evt-115@435-710` 在向量候选第 10，但最终没有作为完整正文进入资料；实际资料主要来自 `evt-086` 和 `evt-052` | “现在罗辑的身份还是保护者和朋友，重视身边的人。” | 未正面、准确回答目标人物身份，且疑似将史强设定套到罗辑。 |
| 史强 `evt-118`：“罗辑现在的身份是什么” | `claim-evt-115@1329-1624` 排第 4，其原文片段包含罗辑与面壁者有关的对话；`contextDecisions` 却报告它被压缩到 **1 字符**，实际没有保留有用身份句子 | “罗辑现在的身份是经验丰富的刑警与安全人员，是保护者和朋友。” | 具体断点是**片段已召回，但重排/预算压缩使答案丢失**；回答把说话者身份错误赋给询问对象。 |
| 罗辑 `evt-001`：“你以前在大学教什么？” | 已审核短事实 `reviewed-luo-ji-profession` 正常进入参考资料，其他候选也进入 | “我以前在大学教社会学。” | 单次回答与已审核事实相符。 |
| 罗辑 `evt-001`：“你知道我的手机密码吗？” | 无相应事实，仍注入 `claim-evt-122@1485-1751` 等与问题无关的资料；其行范围 4670–4671 明确晚于当前 cutoff `evt-001` 的 sequence 31 | “这话我一时说不清。” | 单次回答较保守，但检索资料误注入且发生未来剧情泄漏；gate 显示 `Allow`。 |

四个回合的编排耗时本次分别为 95、58、35、53 ms；这不是索引冷启动耗时或 P95。 设备数据库检索 instrumentation 为 `OK (16 tests)`，总耗时约 212 秒；其中包含反复安装、校验数据包及多次编排，不能将总时间当作单轮检索时延。设备测试框架的 `OK` 表示请求和非空输出完成，**不表示答案质量通过**。已完成的阶段 1 技术实现不应因此被标记为相关性验收合格。

### 后续 P0 实施及同设备复测（2026-10-30）

已完成下方两项 P0 的基础实现：`CharacterReferenceFilter` 使用已验证 bundle 的 cutoff 和片段源行范围过滤明确未来、跨 cutoff、无效来源/时间及不属于当前角色的 reviewed 事实；普通 RAG 不走该过滤。`CharacterRagPromptBuilder` 分开处理标头与正文、预算包括分隔符和标头、每条正文最多 130 字，并只选择有意义的完整句子（含可容纳的相邻句）；空白、仅标点和无法容纳完整句子的候选会被丢弃并记录。完整句子不必然有足够的主语上下文或与问题相关，当前依然采用轻量词项重排；不把时间符合等同于角色知情。

同一模拟器、同一现有 MiniCPM-V 4.6、同一数据指纹与 64 token 上限下，对四个合成问题再次单次采样：繁体身份问题的参考资料从不含“面壁者”变为包含相关语句，但回答仍不正确；简体身份问题的 `evt-115` 从 1 字符变为 52 字符，但保留的句子不包含“面壁者”，回答仍不正确；职业问题前后都正确；无资料密码问题的未来候选 10 条被过滤，参考资料为空，但模型回答“知道，但不会告诉别人”，属于无依据的回答。此次只观察到**证据保留及未来泄漏的局部改善，无法证明整体检索精度或回答准确率明显提升**。一轮结果不代表统计结论，候选索引未变，所以本阶段并未改善向量召回排名。

记录见 [修改后设备证据](evidence/Iprv_RAG_device_p0_20261030.json) 和 [P0 验证报告](Iprv_RAG_P0_Report.md)；原测试记录及下文原因保留作为对照。下一步应优先在独立样本上改进相关句/人物主体排序和无依据时的回答约束，并保留当前时间和预算边界。

### 已确认的直接原因

1. **候选名次与最终顺序不同。** `CharacterRagPromptBuilder.compileContext()` 重新以 `vector score + ContextController.queryBoost()` 排序；简体身份问题中，第一个注入的是向量分数约 0.154 的 `evt-023`，而向量分数约 0.163 的 `evt-115` 被挤到后面。哈希相似度和词项覆盖直接相加且未校准，通用词“现在/是什么”等可能放大无关片段。此结论来自实际候选与决策顺序，具体每项 boost 尚未分别记录，不能确定其数值贡献。
2. **预算耗尽后仍产生伪选中项。** `ContextController.compress()` 会在极小剩余预算下返回标点或被截断的资料标头。`evt-115` 的 `chars=1/395` 被记为 `compressed`，但这一个字符不能作为答案证据；因此“有来源/被选中”与“正文进入请求”不等价。当前 600 字预算还包含 source/span/score 标头，连接分隔符未计入 `usedChars`。这是可用固定样例复现的明确缺陷。
3. **query 和分片粒度仍有召回缺口。** 繁体“羅/辑”混写与简体问题的 `evt-115` 名次不同；片段索引有效缩小原文，但目前使用整场景与分片 0.8/0.2 加权，并不理解身份语义。不同结果不证明单独增加繁简转换就足以修复压缩问题。
4. **原始参考资料绕过角色时间边界。** 编排器将全部向量候选交给 `CharacterRagPromptBuilder`，没有在这条路径按 `bundle.validated.cutoffs` 过滤。`evt-001` 注入行 4670 的 `evt-122` 是直接证据；当前 V2 事实授权和 `RoleplayAnswerabilityGate` 并未覆盖这批参考场景。不能仅依据一次保守回答认定没有泄漏。
5. **第三人身份与说话者设定容易混淆。** 当前基础 system 写明“你就是史强”，但缺少能回答当前问题的目标人物资料；两次输出与把史强身份归给罗辑一致。要通过独立问题样本确认这不只是单次生成随机性；不能靠固定字符串或追加答案修补单例。

### 修改后的实施优先级

原阶段 0/1 的实现保留，**不视为全部验收通过**。下一步先处理确定的资料选择和时间错误，再逐步评测召回，避免一开始更换模型或增加复杂 agent：

1. **P0：先过滤明确未来片段，再排序和预算。** 在角色模式参考资料入口，复用当前已验证 bundle 的 cutoff 和片段源行范围；`end` 明确晚于 cutoff 的片段不得注入。跨越 cutoff 的片段只在能精确、安全截断并保留有效引用时处理，否则拒绝；不认识的 parent、缺失/无效时间按未知处理并记录原因，不假定其合法。依据源数据中 `cutoffs[event_id].sequence` 与证据的行号/sequence 契约实现和测试，**不要通过字符串中的 evt 编号大小判断时间**。这只能排除明确未来内容，不能推导说话者亲历、授权或知道全部旁白；已审核角色事实继续遵守原 V2 规则。普通 RAG 和不同数据集的行为须明确，不影响其他模式的数据来源。
2. **P0：修复正文选择与预算核算。** 在 `CharacterRagPromptBuilder` / `ContextController` 将标头和正文分开评分/裁剪；只有实际保留有意义的证据正文才记为 selected/source，空字符串、单独标点、截断标头和只有空格不得算成功。优先保留与问题和目标实体匹配的完整短句及必要邻句，控制每来源/每父场景上限，避免一个片段耗尽全部预算；预算计入标头、换行和分隔符，总长有明确上限。日志分别记录候选、正文有效注入、丢弃原因、最终保留范围；不得用原始 source 行范围冒充压缩后的精确范围。
3. **P1：在固定评估集上调整召回、排序和证据选择。** 沿用现有索引和向量输入，先记录候选的向量分数、queryBoost 与最终排序分数，在开发集调参、在隔离验证集检验，而非为 `evt-115` 特判。优先改善人物主体、同义问法和答案句的匹配，覆盖多角色和多个数据集；没有足够相关证据就允许空结果，不为填满 context 注入低质量资料。不要求简繁转换或重新构建索引。
4. **P1：第三人问题和无答案处理。** 利用现有角色卡名称/别名进行轻量目标识别，明确区分说话者与被询问人物；只用于检索和资料相关性，不把目标人物后期角色卡当作当前角色已知信息。没有足够资料时沿用可解释的未知处理，检查 gate 与参考资料的交互；禁止按具体名字硬编码答案。若未来信息仅出现在未授权参考候选中，gate 和过滤器的结论应一致。
5. **P2：扩大数据和验证。** 短片段不能创造原文中不存在的身份、时间与角色认知数据。按来源手动审查并补充必要的原子事实/授权边，或明确参考资料不代表角色已知；覆盖来历、身份、关系、物品、事件、无答案、换角色和跨 cutoff。继续推进原阶段 2–4 中的追问、双端兼容、同包升级与性能评估。

### 面向本次故障的验收测试

- 固定相同数据库、角色、cutoff 和原始输入：检查目标 parent/片段的召回排名、重排排名、实际保留正文和 `BACKEND_REQUEST`；`chars=1` 或只有标头/标点必须判为未注入。身份词重排改进必须在其他人物和问题上复验，不能只要求本案例排名第一。
- 构造 600 字预算边界、一个长结果占满、多段去重和只余 1–2 字的合成用例；断言正文非空、来源记录与实际保留一致、连接字符也计入预算，同时保留原始用户问题。
- 固定 `evt-001` 并提供明确在 cutoff 后、跨 cutoff、无时间、合法较早的候选；断言未授权未来资料不会出现在最终 system/user 或任何重试请求。分别验证角色模式和普通 RAG 的预期，禁止通过旧库回退。
- 对无答案/无关查询验证空或明确不足，不出现任意高分无关场景；对可回答问题比较候选 Recall@5/@20、最终证据正文保留率、无答案误注入率，保留开发/验证划分和分母。
- 同一设备至少复测固定的身份、职业和无答案问题，保留真实可见回答、gate、检索片段、最终请求及构建/fingerprint；单次自然语言表现只能作为实例，不能当统计结论。补测设备冷启动、内存及 P50/P95，避免 1.2 索引体积与验证开销造成使用体验退化。


> 下方第 1–7 节为旧库方案和 TXT 工具的历史记录；与 2026-11-03 接入计划冲突时，以上方新库专节为准。

## 1. 要解决的问题

角色模式已将向量检索结果作为本轮参考资料放入 user 消息，但“有资料”不等于“资料能回答问题”。应同时衡量以下四步：

1. 当前问题是否被正确理解，尤其是人名、别名和明确的连续追问。
2. 包含答案的证据是否进入候选集合，以及排名是否靠前。
3. 关键句是否在 600 字符参考资料预算内保留下来，而不是被长场景或无关片段挤掉。
4. 模型实际接收到的请求是否包含证据；如果没有足够依据，是否明确表示资料不足。

本方案优先改数据切分和轻量检索、排序，不依赖额外大模型、联网服务或模型可用性。以下阈值和目标是**待验证的初始方案**，不得在没有基线和样本的情况下宣称准确率达标。

## 2. 对照当前项目的事实与问题

以下为旧库检索路径的历史描述，新库实际现状见本文件 2026-11-03 专节：

```text
用户输入 / 同包历史
 → MainActivity 与 CharacterFollowUpResolver
 → AndroidRagOrchestrator：当前输入向量检索，角色模式最多 20 条，minScore=0.10
 → RagSearchService / VectorIndex：384 维 hash n-gram 余弦排序后截取，再按分数过滤
 → CharacterPromptCompiler：reviewed_fact 才进入 V2 角色认知审核
 → CharacterRagPromptBuilder：以 score + queryBoost 排序，压缩资料并放入 user
 → 模板渲染 / AnswerabilityGate / 模型及输出 guard
```

- 数据源固定为 `database/`；当前 manifest 中向量索引为 `runtime/android/vector/index.json`，知识 SQLite 是源和审计对象，不是每轮在线 FTS。切换包仍通过当前 bundle/manifest/installer 机制完成。
- 当前索引是每条场景一个向量：125 条 `scene_candidate` 文本长度 431–2534 字符，中位数 2246；另有 3 条 `reviewed_fact`，长度 11–46 字符。`scripts/prepare_database_bundle.py:build_vector_index` 直接对完整 `claim["statement"]` 做 384 维 2–4 gram BLAKE2b 哈希向量。长场景中多个话题共同参与向量，相似度和单句答案关联可能较弱；哈希碰撞也是可能原因，不能仅凭一次结果量化其影响。
- 当前 `VectorIndex.search()` 先按余弦排序取 `topK`，`RagSearchService.search()` 再用阈值过滤。角色模式通常取 20 个候选；普通 RAG 默认 `topK=1`，两种模式需要分别验收。
- 当前 `CharacterRagPromptBuilder` 使用 `score + ContextController.queryBoost`，分数和词项重合直接相加且未校准；`ContextController.compress` 为句子加入 `1/(句子序号+1)` 的靠前偏置。一个长结果可以消耗大部分 600 字符，source/span/score 标头也占预算；片段虽召回，答案仍可能因排序或压缩丢失。`RagSource` 的 start/end 是源证据行号，不是压缩后精确字符区间。
- 以当前索引和 Python 同源 hash 实测：`罗輯现在的身份是什么` 的 `claim-evt-115` 排名第 12，`罗辑现在的身份是什么` 排名第 4；前者最高为 `claim-evt-039`。这证明两种输入的当前排名不同，不足以单独证明归一化后一定能正确回答：`evt-115` 是长场景，且“当前身份”和角色知识仍须核对。
- `CharacterFollowUpResolver` 只针对上轮同数据集、同角色和 cutoff 的已授权目标，且 `previous.sources.isNotEmpty()` 时返回 null。角色模式现在会注入来源，因而带资料的上一轮可能使该路径失效；向量检索当前只用本轮原文。短追问需要实测，不应把上轮回答直接当事实。
- 已审核事实只有三条且都属于罗辑；召回分数不是可信度或权限。场景参考资料目前尚未按角色认知或剧情时间过滤，可能引入未来情节或旁白；时间仅能限制事件顺序，不能自动证明人物知情。保留原有 bundle 身份校验、角色知识授权和未知处理。
- `RAG_TRACE` 有数据集指纹、向量执行、候选和部分注入信息，`RagPromptDebug` 可展示阶段原文。当前来源多用 source/span 而非具体切分后的稳定片段 ID，无法完整解释“哪个句子召回、为何被选中或丢弃”。注意本 checkout 的 `MainActivity` 中 Debug 原文日志默认值为 true；正式诊断设计须把原文保护纳入验收。

## 3. 设计原则

- 只改当前 `database/` 的产物和 Android 消费链路；不能搜索 `character/`、旧 Harness 库或另一个数据目录。
- 首先以可复现样本找瓶颈，再分阶段改造。初期保留现有哈希向量，先让每条索引记录更接近可回答问题的证据，不为追求“语义”直接引入新的 embedding 模型。
- 事实、场景候选和最终参考资料始终有不同标识；`scene_candidate` 不能直接变成通过审核的 `reviewed_fact`。对证据不足的问题允许空结果，不用低相关片段凑满预算。
- 默认仍以当前输入为主；追问只在上下文满足条件时补齐目标，不自动把整个聊天摘要加入检索，不让旧问题覆盖新问题。
- 每个阶段有独立开关或可回退的版本及验证；不改变模型文件、推理后端或一般对话 OFF 行为。不能仅靠非空 prompt 或相似问题结果不同判断改进有效。

## 4. 分阶段实施

### 阶段 0：固定基线与可观察性

1. 在 `app/src/sharedTest/java/.../harness/data/database/` 新增可复用的检索评估用例；纯计算逻辑放 `app/src/test/`，需要验证 Android 安装、完整请求的用例放 `app/src/androidTest/`。用合成数据覆盖通用排序与边界；当前数据库用可人工核对、短小的题集覆盖真实路径。
2. 样本字段建议：`case_id`、`dataset_id` 和 fingerprint、角色、cutoff、当前问题、可选前一轮、期待的**源证据行或稳定 parent ID**、支持/拒绝/信息不足、人工标注依据。按人物身份、人物关系、具体事件、自己的职业/来历、物品/无资料、同义改写、连续追问、不同 cutoff 等覆盖；多选例子中的 `evt-115` 必须先核对原文和时点，不直接把“面壁者”作为对所有时间点的金标准。
3. 将题集分为调参集和未参与调参的验证集。加入同义改写、不同主体和无答案用例。固定输入与版本，记录每例 top-1/top-5/top-20 候选、目标证据名次、实际注入片段及 gate 决策；初始至少覆盖不同角色和上述每种问题类型。标注数量随源资料而定，不能用少量模板冒充普遍准确率。
4. 扩展 `RagTurnTrace` 和 `RagContextDecision`（以及其构造链）为每回合记录内容无关的字段：检索版本、候选/去重/注入数、稳定片段 ID、parent claim ID、来源证据范围、各项分数、选中/丢弃原因、截断与预算；失败、无候选、过滤、压缩空、gate 拦截分别表示。`RagSource` / 稳定 ID 的序列化需要兼容既有会话；不把新 ID 写成旧的来源范围或改变历史含义。
5. 延续 `RAG_TRACE`、`RagPromptDebug`、`BACKEND_REQUEST` 现有入口；JSONL 默认不含用户问题、引用正文与向量。默认关闭 Debug 原文记录并允许明确开关，记录既有 preference 对升级行为的影响；公开分享日志前仍需检查。`BACKEND_REQUEST` 是首次编译的请求快照，gate 可能返回固定回复、guard 可能进行重试，最终发送需要对照生成路径。

通过条件：现有版本可重跑并保存每项指标和完整注入链，能明确指出“未召回 / 排序不足 / 压缩丢失 / 受 gate 影响”，且不保存私密正文。此阶段不更换检索算法。

### 阶段 1：把长场景切成可检索短片段

1. 在 `scripts/prepare_database_bundle.py` 中保留源 `claim` 和 evidence，不改变 SQLite 原文；根据句号、换行和对话边界确定确定性的短窗口，初始建议 180–320 字、邻接重叠 1–2 句；过长单句按安全边界拆分，避免截断 UTF-16 代理项。参数须基于阶段 0 数据调优，不把数字当质量保证。
2. 新索引每个片段附 `chunk_id`、`parent_claim_id`、`record_kind`、数据集身份、原文内起止偏移、可验证的源证据行范围和切分版本。稳定 ID 应由 parent 和规范范围/切分版本确定；同源重叠片段能追溯、去重。`reviewed_fact` 维持审核原子事实与角色知识边，不因拆分更改权限。
3. 当前 `DatabaseVectorContractValidator` 要求 `id` 唯一对应完整原始 statement 且精确等于源文；不能仅修改生成脚本。同步定义**显式索引 schema 版本**，更新 Python `check()`、Kotlin `DatabaseVectorContractValidator`、`VectorIndex.fromJson`，验证文本是正确父证据的精确片段、偏移和行范围无越界、ID 无冲突、维度及 embedding 一致。明确旧版 schema 的支持策略：旧 APK 不读取新版包；新版可根据项目需求明确拒绝旧包或提供经过测试的旧版读取，不允许悄然回退旧外部库。
4. 重新运行 `python3 scripts/prepare_database_bundle.py` 生成受控产物和 manifest；检查各文件 hash、dataset fingerprint、gradle `prepareDatabaseBundle` / `verifyDebugDatabaseAssets`、安装器、同包升级与缓存失效。包身份变化会隔离现有会话，须确认用户可从历史查看，不能删除会话或模型。不要手改导出、只更换查询编码器或单改 manifest。
5. 检验段落、对白、重复窗口、无标点、行号与正文一致、补充平面字符、跨语言 hash 一致。长场景被召回时不应只因为其句子数量多而占满前 K。

通过条件：源数据可追溯且校验器能阻止错配和篡改；同一基线上 top-5 答案证据命中和最终片段保留相对阶段 0 有可复核提升，负例不退化；报告额外索引体积和加载耗时。

### 阶段 2：轻量排序和上下文预算

1. 沿用现有查询与索引的向量规则，不引入简繁转换，也不把文本规范化或索引重建作为本阶段前提。若独立证据表明 Unicode、空白或标点处理需要调整，应另行评估；任何向量输入或参数变更都必须同步 Python 构建端和 Kotlin 查询端、提供共享 golden 用例、更新版本并重建校验索引。原文、用户输入和证据范围保持可追溯。
2. 在 `RagSearchService` / `VectorIndex` 附近实现轻量混合排序：已有向量相似度 + 可解释的词项重合/稀有词权重；以同一查询及同一候选集计算各项，设定明确的归一化、空结果、同分顺序和去重规则。不要凭直觉固定权重、只靠是否包含人物名或强行要求“身份/关系”等词；以阶段 0 调参集确定参数，并在保留集比较向量基线。短评估中若不优于基线，不启用该加权方案。
3. 将 top-K 候选和分数阈值分开配置，并在最终排序后应用相应门槛；给普通 RAG 与角色模式各自验证。过滤规则应产生可追踪原因；明确无答案的低相关输入允许返回空，不靠把阈值降到零制造结果。
4. 调整 `CharacterRagPromptBuilder` 和 `ContextController`：对片段而非整篇做相关句选择；先排序再分配预算，限制单来源及重复窗口占用，合计计算分隔符和标头，确保内容预算严格受限。保留必要相邻句以免答案失去主语、否定词或时间；没有可用正文则不生成空引用。每条显示来源、稳定 ID 与实际保留范围，避免 source/span 被误认为最终文字的精确范围。
5. 检索查询与原始展示问题分开；普通问题不用旧对话扩写，避免历史污染。验收不同人物及不含特定人名的问法，同时确认其他角色、其他数据库及不存在的实体不会被硬替换。

通过条件：排序、分数、阈值和预算可解释；同义改写及不同主体的问题有可复核改善，正确片段仍实际存在于 `BACKEND_REQUEST`；无答案样例不强行注入；加载/检索耗时和体积无不可接受回退。

### 阶段 3：连续追问和基础剧情时间约束

1. 扩展现有 `CharacterFollowUpResolver`，不要因上轮 `sources` 非空就一概放弃；仍要求 dataset ID、fingerprint、角色、cutoff、模式一致，只对明确且无歧义的追问使用上一轮持久化的单一授权目标或稳定来源 ID。构造**只用于检索**的查询，不修改用户原话；多个目标、跨库、跨角色、跨 cutoff、用户提出新话题时直接检索当前问题。
2. 候选按已验证数据包的事件顺序或精确有效范围过滤明确晚于当前 cutoff 的内容。片段跨越 cutoff 或缺失时间时标为未知并保守处理，不能从 `evt-123` 的字符串大小推导通用时序，也不能仅靠关联边断言角色知情。可让调用者明确区分“参考场景”和“角色已审核事实”，不能为改进召回而绕过 V2 授权。
3. 评估已有 `RoleplayAnswerabilityGate`：它基于角色审核事实执行固定回答，参考资料是否充足可能与 gate 决策不同。用事实充分、未来、无证据和来源不可信例子审查，不简单取消 gate 或把原始参考自动提升为角色知识；与产品当前行为的取舍应在实现记录中说明。
4. 会话持久化及历史摘要不得成为事实授权源。补充有来源追问、无来源追问、歧义、换话题、不同角色/数据集/cutoff 的测试；运行真实设备 prompt 观测，判断 user 中仅有本轮问题还是明确追问目标，不记录原文到持久 trace。

通过条件：明确追问比仅查询代词有可测改进，独立新问题不受上一轮污染；未来和无时间场景不被错误宣称为角色已知；换包和旧会话边界不倒退。

### 阶段 4：端到端评估和交付

1. 本地运行：`python3 -W error::ResourceWarning -m unittest discover -s scripts/tests -v`；`python3 scripts/prepare_database_bundle.py --check`；`bash ./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:verifyDebugDatabaseAssets`。若索引格式升级，先按阶段 1 生成，再运行 `--check`。
2. Android 模拟器/设备使用覆盖安装，运行与 bundle 安装、换包、查询、trace 和 prompt 相关的 instrumentation；不得卸载或清空用户数据。用当前可用模型做少量真实对话抽样只验证端到端行为，不把模型回答当检索准确率；用户没有要求检查其他模型，不把其他模型可用性作为前提。
3. 基线与新版本按相同题集、fingerprint、角色、cutoff 比较：Recall@5/@20（标注可回答问题的证据是否进入候选）、MRR（首个相关证据名次）、最终 prompt 证据保留率、无答案误注入率、显式追问及独立新问题准确率。明确分母、相关性标注标准和未回答样例；报告每种题型和失败样例，不只给总平均。
4. 记录设备/构建、题集版本、不同模式、baseline 与新算法索引版本、包 fingerprint、耗时 P50/P95、内存及索引大小；准确率阈值在建立基线后按样本协商，原则是不降低无答案安全性、不破坏安装/会话隔离、保持可回退。报告未完成的权限或时间覆盖。
5. 更新 `docs/RAG_Logic.md` 与本计划状态，包括真实运行路径、分词/排名、片段范围、trace 过滤词、时间/授权限制、重建数据包命令和回滚方式。

## 5. 关键文件与职责

| 文件 / 目录 | 计划处理 |
| --- | --- |
| `scripts/prepare_database_bundle.py` | 确定性切分、索引版本、导出和构建时校验；仅在向量规则变化时同步调整 |
| `database/manifest.json`、`database/runtime/android/vector/index.json` | 由构建工具生成受控产物、摘要及版本；不手改导出 |
| `app/src/main/java/com/example/minicpm_v_demo/harness/data/database/DatabaseVectorContractValidator.kt` | 校验分片与父证据、source span、索引 schema 和向量契约 |
| `harness/rag/VectorIndex.kt`、`HashEmbeddingModel.kt`、`RagSearchService.kt` | 读取索引、候选检索与经验证的排序；维持现有文本处理规则 |
| `harness/rag/AndroidRagOrchestrator.kt`、`AlwaysOnVectorRetrievalService.kt` | 基于当前包的候选获取、模式开关和结果传递 |
| `harness/rag/CharacterRagPromptBuilder.kt`、`harness/context/ContextController.kt` | 片段选择、压缩、预算和最终资料构造 |
| `harness/character/CharacterFollowUpResolver.kt`、`CharacterPromptCompiler.kt`、`RoleplayAnswerabilityGate.kt` | 明确追问、角色/时间知识和 gate 交互 |
| `harness/rag/RagTurnTrace.kt`、`RagPromptStage.kt`、`MainActivity.kt` | 内容受控的诊断信息和实际生成路径观测 |
| `scripts/tests/`、`app/src/test/`、`app/src/sharedTest/`、`app/src/androidTest/` | 构建校验、JVM/共享测试、设备检索和端到端验收 |

## 6. 不应采用的快捷方式

- 仅将 `topK` 调大、阈值调低或把全部候选拼进 prompt：可能增加无关和未来内容，且 600 字符瓶颈依然存在。
- 将所有场景关联边当作角色知识授权，或把完整目标人物后期角色卡当作当前角色已知事实。
- 只修改 Android 查询端归一化或向量模型，不重建并验证 Python 索引；这会产生不兼容向量空间。
- 将追问固定改写成上轮问题，或把模型上轮回答当数据库事实。
- 用 `vectorExecuted=true`、结果非空、prompt 哈希变化或模型单次回答正确作为检索质量的充分证明。

## 7. 通用 TXT 原文处理脚本设计

本节补充的是**脚本编写方案和可运行的离线示例**。它适用于小说、访谈记录或普通文档等用户提供的 TXT；它不能从任意 TXT 自动可靠推断角色身份、剧情时间或角色是否知道某事。示例产物只是 JSONL 片段与本机检索结果，**不是本项目可安装的 Android 数据包，也不会自动更新 App**。

### 7.1 与当前项目的关系

当前已有两层不同职责：

- `three_body_2_kb_builder/scripts/generate_character_cards.py` 和 `three_body_2_kb_builder/scripts/build_knowledge_base_v2.py --three-body-2` 是《三体Ⅱ》固定角色流程；后者 `_segment()` 的默认目标约 2200 字。它们不是面向任意 TXT 的通用导入器，也不能仅通过传入另一篇小说就保证角色卡和认知边正确。
- 根目录 `scripts/prepare_database_bundle.py` **读取已存在的结构化数据包**，校验 SQLite、JSONL、原文证据、角色卡和审核数据，生成运行导出、向量索引和 manifest。它不是原始 TXT 导入器，当前还要求索引记录与源 `claim.statement`、证据范围完全一致。

面向任意 TXT 的正式实现应新增通用预处理入口，并与阶段 1 的索引协议升级一起完成：

```text
用户指定 TXT
  → 解码与源记录 → 切分/位置映射 → 候选片段及索引
  → 构建或人工审核角色、事件和知识边（如角色模式需要）
  → 当前 bundle 协议、manifest/hash、Python 和 Kotlin 双端校验
  → 在临时位置验收后更新根目录 database/ → 构建 APK → App 安装验证
```

原文可以产生**检索候选**；不能仅凭一行出现角色名、模型抽取或故事顺序，自动宣称某角色知道该段内容。用户没有提供角色卡、可核实的时间或角色知识边时，应在导入报告中标为未提供，并设计明确的参考资料模式；不能为满足校验伪造角色、cutoff 或已审核事实。当前 `prepare_database_bundle.py` 及 Android 角色编译器都要求现有结构化契约，通用缺省数据集支持属于后续代码与协议改造，不是脚本单独完成的功能。

### 7.2 建议的正式脚本接口与步骤

建议新增 `scripts/prepare_raw_txt.py` 或独立模块，再由现有 `prepare_database_bundle.py` 复用其切片和检索字段生成函数。名称和参数如下是**待实现的接口草案，不是当前可执行命令**：

```text
python3 scripts/prepare_raw_txt.py \\
  --input /path/to/source.txt \\
  --dataset-id my_dataset \\
  --output /path/to/staging_dataset \\
  --encoding auto \\
  --chunk-chars 300 \\
  --overlap-chars 60
```

1. **输入与安全写入。** 显式指定一个文件或输入文件清单、数据集 ID 和输出目录；不从旧 `character/` 或旧 Harness 自动发现数据。读取原始 bytes，计算 `source_bytes_sha256`，记录文件名、编码及解码结果；默认优先 UTF-8/UTF-8 BOM，再尝试 GB18030，全部严格解码失败则报错。自动检测可能将某些字节误识别为 GB18030：允许用户指定编码，并对疑似乱码给出审计报告，不用 `errors=ignore` 静默丢字。
2. **原文与检索字段分离。** 保留解码后的完整源文本及其 SHA-256，不在原文里修改简繁、删对白或合并段落。如果后续确有必要，可单独生成检索规范化文本（空白、Unicode 等），记录规则版本；不要求简繁转换。源引用和核验始终对原文进行。字符偏移和行号应明确基准，若规范化改变长度，不可复用规范化偏移定位原文。
3. **先按结构切分，再按长度切窗。** 优先识别章节、段落和对话边界，尊重原文顺序；段落过长时再按句子切为短窗口，保留相邻句重叠与足够上下文，避免切掉主语、否定或问答对。初始可试 180–320 字、重叠 1–2 句；对英文、没有标点的长段、超长单句需要安全回退。参数是评测起点，不能保证所有 TXT 的最佳结果。示例脚本为易读采用字符窗口，不包含章节/对话识别。
4. **稳定标识和可追溯来源。** 每个片段保存 `source_id`、原始文件 hash、解码文本 hash、切分版本、`chunk_id`、原文起止偏移、行号和原文片段。明确偏移采用 Unicode code point、UTF-16 还是 UTF-8 字节：Python 的字符串下标是 code point，Android Kotlin 的字符串下标是 UTF-16；不能直接交换这两种偏移。以统一契约或显式转换解决，并测试补充平面字符、CRLF、尾部换行和跨行切片。
5. **构建索引。** 初期沿用 `scripts/prepare_database_bundle.py` 的 `hash_embedding()` 与 Android `HashEmbeddingModel`，对短片段计算向量；检索阶段对问题使用**相同**规范化、编码、n-gram、哈希、维度与归一化规则。记录参数和索引版本；任何一侧改变都要重建并验证整个索引。示例 2 直接复用现有函数，并维持现有索引规则。
6. **身份、时间、授权另做可审计处理。** 用户可提供明确的角色名及别名清单，先作为召回提示；片段、人物同场或旁白不等于角色知情。事件时间和事实有效区间仅在来源充分时标注；不确定则保持未知。已审核事实应保留原文引用、依据和审核人，不自动将原始场景升级为 `reviewed_fact`。
7. **先暂存、后验证、再切换。** 默认在独立 staging 目录输出，不覆盖输入、当前 `database/`、角色卡或用户审核事实。完整校验源 hash、片段/原文的逐字回放、索引参数与 manifest、SQLite/JSONL 一致性、角色与 cutoff 引用和 Android 解析后，再按项目现有安装/激活机制切换；生成失败不留下半成品作为当前库。数据包更新和会话隔离遵守原计划第 4 节。
8. **重复运行与报告。** 相同原文、参数和切分版本产出相同片段 ID 和内容；报告输入编码、长度分布、空/过长片段、重复、来源缺失及耗时。改变原文时源 hash 与受影响 ID 必须变化；不能静默复用旧向量。索引大小、加载内存和设备延迟也要列入评测。

### 7.3 示例一：将单个 TXT 切成可追溯 JSONL

下面是独立示例，可保存为 `/tmp/rag_txt_chunk_example.py`。**不会创建角色卡、SQLite、审核事实、向量索引或项目 manifest**；不将输出放入 `database/`。它保留解码原文并用半开区间 `[start, end)` 表示 Python Unicode code point 偏移，行号为 1 起的闭区间。为了演示安全写入，目标文件已存在时会报错，不覆盖。

```python
from __future__ import annotations

import argparse
import bisect
import hashlib
import json
from pathlib import Path


def decode_source(raw: bytes, requested: str) -> tuple[str, str]:
    choices = ("utf-8-sig", "gb18030") if requested == "auto" else (requested,)
    for encoding in choices:
        try:
            return raw.decode(encoding), encoding
        except UnicodeDecodeError:
            continue
    raise ValueError("无法严格解码输入；请明确指定正确编码")


def spans(text: str, limit: int, overlap: int):
    if limit < 1 or not 0 <= overlap < limit:
        raise ValueError("chunk-chars 必须大于 0，overlap 必须小于 chunk-chars")
    start = 0
    boundaries = "。！？!?；;\n"
    while start < len(text):
        hard_end = min(start + limit, len(text))
        end = hard_end
        if hard_end < len(text):
            # 在窗口后半部尽量使用句子或换行边界；找不到则按长度切。
            lower = start + limit // 2
            candidate = max(text.rfind(ch, lower, hard_end) for ch in boundaries)
            if candidate >= lower:
                end = candidate + 1
        if text[start:end].strip():
            yield start, end
        if end == len(text):
            break
        # 即使边界落在重叠区，也保证下一次 start 前进。
        start = max(start + 1, end - overlap)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--encoding", default="auto")
    parser.add_argument("--chunk-chars", type=int, default=300)
    parser.add_argument("--overlap-chars", type=int, default=60)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        raise ValueError("输出不能覆盖输入")
    raw = args.input.read_bytes()
    text, encoding = decode_source(raw, args.encoding)
    source_hash = hashlib.sha256(raw).hexdigest()
    text_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
    newline_offsets = [i for i, ch in enumerate(text) if ch == "\n"]
    count = 0
    # x 模式避免意外覆盖已有文件；正式实现另需 staging 与原子提交。
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        for start, end in spans(text, args.chunk_chars, args.overlap_chars):
            key = f"txt-window-v1:{source_hash}:{start}:{end}"
            record = {
                "chunk_id": hashlib.sha256(key.encode("utf-8")).hexdigest()[:24],
                "source_name": args.input.name,
                "source_bytes_sha256": source_hash,
                "decoded_text_sha256": text_hash,
                "encoding": encoding,
                "segmentation_version": "txt-window-v1",
                "offset_unit": "unicode_code_point",
                "start": start,
                "end_exclusive": end,
                "line_start": bisect.bisect_left(newline_offsets, start) + 1,
                "line_end": bisect.bisect_left(newline_offsets, end - 1) + 1,
                "text": text[start:end],
            }
            output.write(json.dumps(record, ensure_ascii=False) + "\n")
            count += 1
    print(f"encoding={encoding} chunks={count} output={args.output}")


if __name__ == "__main__":
    main()
```

在项目根目录中，可以用一个**独立测试文件**试运行：

```bash
python3 /tmp/rag_txt_chunk_example.py \
  --input /path/to/source.txt \
  --output /tmp/rag_chunks_example.jsonl \
  --chunk-chars 300 --overlap-chars 60
```

每行一个 JSON 对象，例如（省略 hash）：

```json
{"chunk_id":"…","source_name":"source.txt","offset_unit":"unicode_code_point","start":0,"end_exclusive":120,"line_start":1,"line_end":4,"text":"原文片段……"}
```

偏移针对解码文本，而不是原始文件字节；`utf-8-sig` 会去掉 BOM，CRLF 仍保留在解码文本中。示例使用原始字符重叠，并未保证问答句配对或来源跨切片完整；正式脚本必须按 7.2 补齐这些能力。输出若因磁盘错误中途中断，示例可能留下部分文件；正式脚本必须先写临时文件，校验后原子提交。

### 7.4 示例二：用现有 hash 向量进行离线召回检查

保存以下代码为 `/tmp/rag_txt_search_example.py`。它复用当前仓库 `scripts/prepare_database_bundle.py` 的 `hash_embedding()`，对示例一的片段**临时计算**向量并排序；不持久化向量、不接入 Android，也不是最终精度评测。

```python
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# 从项目根目录运行；复用项目现有 Python 构建端的向量函数。
sys.path.insert(0, str(Path.cwd() / "scripts"))
from prepare_database_bundle import hash_embedding


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chunks", type=Path, required=True)
    parser.add_argument("--query", required=True)
    parser.add_argument("--top-k", type=int, default=5)
    args = parser.parse_args()
    if args.top_k < 1:
        raise ValueError("top-k 必须大于 0")
    query_vector = hash_embedding(args.query)
    with args.chunks.open(encoding="utf-8") as stream:
        records = [json.loads(line) for line in stream if line.strip()]
    scored = []
    for record in records:
        vector = hash_embedding(record["text"])
        score = sum(a * b for a, b in zip(query_vector, vector))
        scored.append((score, record))
    scored.sort(key=lambda item: (-item[0], item[1]["chunk_id"]))
    for score, item in scored[:args.top_k]:
        print(f"score={score:.4f} id={item['chunk_id']} "
              f"lines={item['line_start']}-{item['line_end']} "
              f"source={item['source_name']}")
        print(item["text"][:300])
        print()


if __name__ == "__main__":
    main()
```

```bash
python3 /tmp/rag_txt_search_example.py \
  --chunks /tmp/rag_chunks_example.jsonl \
  --query '角色的身份是什么？' --top-k 5
```

示例每次都重新计算片段向量，适合少量数据的离线核对，不适合在 App 每轮使用；它没有最低分阈值，也没有简繁转换、排序评估、角色授权或时间过滤。原文和查询会显示在终端，不应将真实隐私数据粘贴到公开日志。

### 7.5 正式接入前的检查

- 用不同编码、空文件、长单行、跨行对话、CRLF、emoji、繁简及重复段落运行测试；每条片段用记录的 code point 范围从解码原文逐字还原，重复运行确认 ID 与排序稳定。
- 以人工核验的不同问题计算候选 Recall@5 / Recall@20 和最终 prompt 证据保留率，记录无答案误注入率，不以“有片段返回”作为成功。
- 同步扩展 `DatabaseVectorContractValidator` 与 Python 校验，将分片的父 claim、原文范围、内容、向量参数与 hash 纳入协议；还需验证非法范围、改动原文、缺失片段、重复 ID、参数错配和跨包注入均失败。
- 现有 Android `VectorIndex.fromJson` 接受当前 `1.1` 格式、`RagSource` 使用来源与范围，示例 JSONL 不是可替换索引。要正式接入，需完成分片 schema、生成器、双端校验、安装与 APK 资产检查，再执行原计划阶段 4；不得简单复制 `/tmp` 文件到 `database/runtime/android/`。
EOF
python3 - <<'PY'
from pathlib import Path
p=Path('docs/RAG/Iprv_RAG.md');s=p.read_text();assert '## 7. 通用 TXT 原文处理脚本设计' not in s;s+=Path('/tmp/iprv-raw-section.md').read_text();p.write_text(s)
PY

## 2026-11-04：多轮指代与上下文保留诊断题

这组题用于定位“上一轮提到的对象，在下一轮换种说法就丢失”的位置。每组先记录问题、可见回答、`RAG_TRACE` 和实际 `BACKEND_REQUEST`，再按下方判读表选择修改方向；单次回答正确或错误不能证明整体准确率。仅用于诊断，不把模型先前的回答自动当成数据库事实。

### 测试条件和记录

- 使用最新 Debug 构建，选角色卡模式、角色“罗辑”，选固定的剧情截止点 `evt-125`。目标数据集为 `three_body_2`；在 `RAG_TRACE` 中确认 `vectorExecuted=true`、`backend=bge_onnx`、`failure=none`，记录实际 fingerprint。若不是该数据集或后端，先排查部署；混合模式当前默认关闭，记录 `retrievalMode`，不要在对比中途切换。
- 每个独立小组开始前新建或清空当前对话；同一小组里的问题按顺序连续提问，不修改角色、截止点、数据库或对话模型。清空前先保存需要的记录。除非题目明确要求，否则不要在一组中间重置。若角色或时间选项不能直接设置，记录实际值，不要假设等于 `evt-125`。
- 本包证据 `evd-0a93cd8fce7e243a`、`claim-d6df1752b5c6c671` 属于 `evt-122`（原文行 4670–4671）。原文说手腕上的物品是**生命体征监测仪**，它通过发射器连接摇篮系统；两者不是同一物品。`evt-125` 有时间资格，并不自动代表角色已获授权或证明排序命中。目标 ID 是本次数据包的定位依据；换库后需重新核对。
- 每轮至少保存：问题和可见回答；`RAG_TRACE` 的 `mode`、`dataset`、`fingerprint`、`backend`、`indexFingerprint`、`failure`、`retrievalMode`、`character` 的 `npc`、`cutoff`、`followUp`、`candidates`、`candidateScores`、`contextDecisions`、`authorized` 和 `injected`；以及本轮 `BACKEND_REQUEST` 中实际的 system/user 内容。记下 `RAG_PROMPT` 的 `turn` 和 `seq`，不要把不同问题的 trace 与 prompt 配对。trace 中 `injected` 的来源范围不等同于 system 中的 `authorized` 事实。
- 默认 trace 不含用户原文、完整候选正文和实际分词，`querySha` 不能还原查询。需要对照正文时，在 Debug 应用的 Final Prompt 查看，或按 [RAG 调试说明](../RAG_Logic.md#9-调试与排查) 临时启用 `rag_debug/raw_prompt_logcat`，在 Logcat 搜索 `RAG_TRACE`、`RAG_PROMPT`、`BACKEND_REQUEST`；长日志按同一 `turn` 的 `BEGIN/CHUNK/END` 拼接，结束后关闭原文日志。Final Prompt 是预览；`BACKEND_REQUEST` 才是首个实际 system/user 请求，重试请求另行记录。日志可能含私人对话，请先自行检查再分享。

### A：明确对象与代词追问

独立新对话，按顺序提问：

1. “你左手腕上戴着的东西是什么？”
2. “它有什么作用？”
3. “它和摇篮系统是什么关系？”
4. “如果这个监测仪停止发出信号，会发生什么？”

重点对比第 2、3 问：第 2 问缺少名称，第 3 问重新明确对象。检查第 1 轮是否召回并注入目标证据、回答有没有区分监测仪与摇篮系统；第 2 轮检查摘要是否保留上一轮问答、`followUp`、目标证据是否仍被召回。若第 1 轮答错，不将错误回答作为第 2 轮的事实金标准，应记录并用 B 组控制。

### B：明确对象控制组

新对话，只问：“罗辑手腕上的生命体征监测仪通过什么与摇篮系统连接？”再问：“摇篮系统收到的监测信号停止后会发生什么？”

如果 B 组可以召回相关证据而 A 第 2 问不行，优先检查指代消解及跨轮检索查询；如果 B 组也没有候选，优先检查查询编码、召回排序、cutoff 和索引。若候选存在但实际资料没有相应句子，检查后续过滤和截句。

### C：不同追问写法

以下每条都在**独立新对话**中进行：先问 A1，记录结果，再只问一个追问：

- C1：“它有什么用？”
- C2：“这个东西有什么用？”
- C3：“再详细说说。”
- C4：“为什么需要它？”
- C5：“如果停止发送信号会怎样？”

对比 `followUp`、目标候选 ID 和 `BACKEND_REQUEST`。当前识别规则包含“它”和“再说说”等固定词，但词面相似不保证被识别，例如 C3 可因包含“详细说”触发，但前一轮使用过参考资料时仍可能直接不继承。若只有部分写法失败，改进追问识别及明确对象的继承；若所有写法都失败而历史仍可见，检查历史是否参与检索。

### D：保留对象但正确切换话题

独立新对话，顺序提问：

1. “你手腕上的监测仪与什么系统相连？”
2. “这个系统是做什么的？”
3. “换个话题，你和史强是什么关系？”
4. “他平时怎样帮助你？”

第 2 问应结合上一轮系统话题；第 3 问是明确转题，不应被强制改写为摇篮系统；第 4 问应围绕史强，而不是继续谈监测仪。具体关系内容以 cutoff 内可用证据为准，不能因前文出现名字就判定某关系一定被授权。若第 2 问断开而第 4 问错误承接旧对象，说明既需要继承也需要话题切换和歧义处理。

### E：摘要预算与历史距离

先执行 A1 并保存结果；然后分别在两次独立对话中执行下列流程，最后都问“它有什么作用？”：

- E1：紧接 A1 提问，作为短上下文对照。
- E2：A1 后连续进行 6 轮与该物品无关的简单闲聊，再问最后的问题；记录第 1 轮和最后一轮的 prompt、摘要以及对象是否仍然可见。示例闲聊可依次问当前心情、天气、喜欢什么、是否疲倦、想去哪儿、怎么看等待；这些只用于占用轮次，不要求角色有确定事实。

比较两次实际 `BACKEND_REQUEST`：有对象且回答失败，不能归因于摘要已丢失；没有对象则检查摘要 6 轮窗口、总字符预算和二次压缩。E2 的指代本身可能已不明确，合理的未知或请求澄清不应判为错误；此组用于观测长距离保留能力，不要求系统猜测。

### F：时间与未知信息负例

- F1：在可选情况下将截止点设为 `evt-118`，单独问 A1；记录目标证据是否被时间过滤，以及回答是否把 `evt-122` 的内容当成已知。然后用新对话恢复 `evt-125` 重问 A1，比较候选与 prompt。
- F2：新对话问“你知道我手机的解锁密码吗？”，随后问“它是什么？”数据库没有经验证的密码时，不应把先前模型猜测或无关参考资料当作事实。检查是否误承接无证据的对象，或因为候选非空而给出确定答案。

### 根据输出决定修改位置

| 观察到的结果 | 初步定位 | 修改方向 |
| --- | --- | --- |
| `backend` 不是 `bge_onnx`、fingerprint 不符、`vectorExecuted=false` 或 `failure` 非空 | 运行版本或检索执行 | 先查安装包、当前数据库激活和检索失败；不要先调整排序 |
| B 组明确对象能命中，A/C 组追问未命中；`followUp=not_requested` 或上一轮对象未用于查询 | 追问目标与查询脱节 | 保存带来源的最近话题或证据 ID；仅对明确承接构造包含话题的检索查询，原始用户消息保持不变；重新验证当前角色和 cutoff |
| 目标 ID 出现在 `candidates`，却在 `contextDecisions` 被拒绝、预算淘汰或截出的句子不含关系 | 过滤、预算或截句 | 逐项核对原因及实际保留句；修正目标相关性、相邻句保留和预算，保留来源与时间限制 |
| `BACKEND_REQUEST` 包含足够的监测仪—摇篮系统证据，但回答仍偏离 | 模型理解、角色规则或生成 | 检查实际 system/user、模型与生成输出；不能仅凭最终回答继续扩大 RAG 召回 |
| 短上下文有效、长上下文中对象消失 | 会话窗口或二次压缩 | 保留最近问答及其明确话题，问答成对处理；给历史对象分配受控预算并避免用泛词覆盖 |
| 显式转题仍继承旧对象，或 F2 编出密码 | 话题切换/未知边界 | 给明确转题和歧义设置保守处理；模型先前回答只作不可信对话线索，不能当作经授权事实 |
| A1 中直接把监测仪称为摇篮系统 | 事实关系、证据截取或生成 | 分开记录“监测仪”和“系统”的关系，检查目标证据的保留范围与最终回答；不能用模型回答反向证明数据库事实 |

建议最终提交一份简表：每组/每轮记录 `问题 | 可见回答 | followUp | 目标候选 ID 是否出现 | 目标资料是否进入 BACKEND_REQUEST | 过滤/截取原因 | 初步判断`，附目标设备、模型、角色、cutoff、数据库 fingerprint。先分享 A、B、C 和 F 的结果即可，不必一次跑完全部题目。
