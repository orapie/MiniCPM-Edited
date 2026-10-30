# RAG 检索相关性改进方案

状态：实施计划，尚未实施。适用项目：Android `MiniCPM-anji` 当前 `database/` 数据包。目标是让检索片段更能回答用户当前问题，并能证明相关内容实际进入模型请求；不更换模型，不建立另一套数据库，不按具体小说或问题硬编码答案。

## 1. 要解决的问题

角色模式已将向量检索结果作为本轮参考资料放入 user 消息，但“有资料”不等于“资料能回答问题”。应同时衡量以下四步：

1. 当前问题是否被正确理解，尤其是人名、别名和明确的连续追问。
2. 包含答案的证据是否进入候选集合，以及排名是否靠前。
3. 关键句是否在 600 字符参考资料预算内保留下来，而不是被长场景或无关片段挤掉。
4. 模型实际接收到的请求是否包含证据；如果没有足够依据，是否明确表示资料不足。

本方案优先改数据切分和轻量检索、排序，不依赖额外大模型、联网服务或模型可用性。以下阈值和目标是**待验证的初始方案**，不得在没有基线和样本的情况下宣称准确率达标。

## 2. 对照当前项目的事实与问题

当前路径（以实际 checkout 为准）：

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
2. 样本字段建议：`case_id`、`dataset_id` 和 fingerprint、角色、cutoff、当前问题、可选前一轮、期待的**源证据行或稳定 parent ID**、支持/拒绝/信息不足、人工标注依据。按人物身份、人物关系、具体事件、自己的职业/来历、物品/无资料、同义/简繁、连续追问、不同 cutoff 等覆盖；多选例子中的 `evt-115` 必须先核对原文和时点，不直接把“面壁者”作为对所有时间点的金标准。
3. 将题集分为调参集和未参与调参的验证集。对同问题简体/繁体、改写和无答案各加入成对用例。固定输入与版本，记录每例 top-1/top-5/top-20 候选、目标证据名次、实际注入片段及 gate 决策；初始至少覆盖不同角色和上述每种问题类型。标注数量随源资料而定，不能用少量模板冒充普遍准确率。
4. 扩展 `RagTurnTrace` 和 `RagContextDecision`（以及其构造链）为每回合记录内容无关的字段：检索版本、候选/去重/注入数、稳定片段 ID、parent claim ID、来源证据范围、各项分数、选中/丢弃原因、截断与预算；失败、无候选、过滤、压缩空、gate 拦截分别表示。`RagSource` / 稳定 ID 的序列化需要兼容既有会话；不把新 ID 写成旧的来源范围或改变历史含义。
5. 延续 `RAG_TRACE`、`RagPromptDebug`、`BACKEND_REQUEST` 现有入口；JSONL 默认不含用户问题、引用正文与向量。默认关闭 Debug 原文记录并允许明确开关，记录既有 preference 对升级行为的影响；公开分享日志前仍需检查。`BACKEND_REQUEST` 是首次编译的请求快照，gate 可能返回固定回复、guard 可能进行重试，最终发送需要对照生成路径。

通过条件：现有版本可重跑并保存每项指标和完整注入链，能明确指出“未召回 / 排序不足 / 压缩丢失 / 受 gate 影响”，且不保存私密正文。此阶段不更换检索算法。

### 阶段 1：把长场景切成可检索短片段

1. 在 `scripts/prepare_database_bundle.py` 中保留源 `claim` 和 evidence，不改变 SQLite 原文；根据句号、换行和对话边界确定确定性的短窗口，初始建议 180–320 字、邻接重叠 1–2 句；过长单句按安全边界拆分，避免截断 UTF-16 代理项。参数须基于阶段 0 数据调优，不把数字当质量保证。
2. 新索引每个片段附 `chunk_id`、`parent_claim_id`、`record_kind`、数据集身份、原文内起止偏移、可验证的源证据行范围和切分版本。稳定 ID 应由 parent 和规范范围/切分版本确定；同源重叠片段能追溯、去重。`reviewed_fact` 维持审核原子事实与角色知识边，不因拆分更改权限。
3. 当前 `DatabaseVectorContractValidator` 要求 `id` 唯一对应完整原始 statement 且精确等于源文；不能仅修改生成脚本。同步定义**显式索引 schema 版本**，更新 Python `check()`、Kotlin `DatabaseVectorContractValidator`、`VectorIndex.fromJson`，验证文本是正确父证据的精确片段、偏移和行范围无越界、ID 无冲突、维度及 embedding 一致。明确旧版 schema 的支持策略：旧 APK 不读取新版包；新版可根据项目需求明确拒绝旧包或提供经过测试的旧版读取，不允许悄然回退旧外部库。
4. 重新运行 `python3 scripts/prepare_database_bundle.py` 生成受控产物和 manifest；检查各文件 hash、dataset fingerprint、gradle `prepareDatabaseBundle` / `verifyDebugDatabaseAssets`、安装器、同包升级与缓存失效。包身份变化会隔离现有会话，须确认用户可从历史查看，不能删除会话或模型。不要手改导出、只更换查询编码器或单改 manifest。
5. 检验段落、对白、重复窗口、无标点、简繁、行号与正文一致、补充平面字符、跨语言 hash 一致。长场景被召回时不应只因为其句子数量多而占满前 K。

通过条件：源数据可追溯且校验器能阻止错配和篡改；同一基线上 top-5 答案证据命中和最终片段保留相对阶段 0 有可复核提升，负例不退化；报告额外索引体积和加载耗时。

### 阶段 2：归一化、轻量排序和上下文预算

1. 加入同一个、版本化的查询/索引归一化规则：Unicode 规范化、空白和常见标点处理；简繁转换使用离线可控映射或现有平台能力，明确兼容性并只在检索用字段生效，保留原文、原输入和原始证据范围。避免把不同实体简单当同名；短词和英文/数字不得被错误丢弃。Python 建索引与 Kotlin 查询必须有共享 golden 用例；若更改 embedding 输入或其参数，同步版本、重建索引并执行包一致性校验，严禁新查询向量与旧索引混用。
2. 在 `RagSearchService` / `VectorIndex` 附近实现轻量混合排序：已有向量相似度 + 可解释的词项重合/稀有词权重；以同一查询及同一候选集计算各项，设定明确的归一化、空结果、同分顺序和去重规则。不要凭直觉固定权重、只靠是否包含人物名或强行要求“身份/关系”等词；以阶段 0 调参集确定参数，并在保留集比较向量基线。短评估中若不优于基线，不启用该加权方案。
3. 将 top-K 候选和分数阈值分开配置，并在最终排序后应用相应门槛；给普通 RAG 与角色模式各自验证。过滤规则应产生可追踪原因；明确无答案的低相关输入允许返回空，不靠把阈值降到零制造结果。
4. 调整 `CharacterRagPromptBuilder` 和 `ContextController`：对片段而非整篇做相关句选择；先排序再分配预算，限制单来源及重复窗口占用，合计计算分隔符和标头，确保内容预算严格受限。保留必要相邻句以免答案失去主语、否定词或时间；没有可用正文则不生成空引用。每条显示来源、稳定 ID 与实际保留范围，避免 source/span 被误认为最终文字的精确范围。
5. 检索用规范化问题与原始展示问题分开；普通问题不用旧对话扩写，避免历史污染。验收 `罗輯/罗辑` 和多个不含该人名的问法，同时确认其他角色、其他数据库及不存在的实体不会被硬替换。

通过条件：排序、分数、阈值和预算可解释；同义及简繁对照改善，正确片段仍实际存在于 `BACKEND_REQUEST`；无答案样例不强行注入；加载/检索耗时和体积无不可接受回退。

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
4. 记录设备/构建、题集版本、不同模式、baseline 与新算法索引版本、包 fingerprint、耗时 P50/P95、内存及索引大小；准确率阈值在建立基线后按样本协商，原则是不降低无答案安全性、不破坏安装/会话隔离、保持可回退。报告未完成的简繁、权限或时间覆盖。
5. 更新 `docs/RAG_Logic.md` 与本计划状态，包括真实运行路径、分词/排名、片段范围、trace 过滤词、时间/授权限制、重建数据包命令和回滚方式。

## 5. 关键文件与职责

| 文件 / 目录 | 计划处理 |
| --- | --- |
| `scripts/prepare_database_bundle.py` | 确定性切分、索引与归一化版本、导出和构建时校验 |
| `database/manifest.json`、`database/runtime/android/vector/index.json` | 由构建工具生成受控产物、摘要及版本；不手改导出 |
| `app/src/main/java/com/example/minicpm_v_demo/harness/data/database/DatabaseVectorContractValidator.kt` | 校验分片与父证据、source span、索引 schema 和向量契约 |
| `harness/rag/VectorIndex.kt`、`HashEmbeddingModel.kt`、`RagSearchService.kt` | 读取新 schema、规范化、候选和混合排序 |
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

- `database/scripts/generate_character_cards.py three-body-2` 和 `database/scripts/build_knowledge_base_v2.py --three-body-2` 是《三体Ⅱ》固定角色流程；后者 `_segment()` 的默认目标约 2200 字。它们不是面向任意 TXT 的通用导入器，也不能仅通过传入另一篇小说就保证角色卡和认知边正确。
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
2. **原文与检索字段分离。** 保留解码后的完整源文本及其 SHA-256，不在原文里修改简繁、删对白或合并段落。可单独生成检索规范化文本（空白、Unicode、可控简繁等），记录规则版本；源引用和核验始终对原文进行。字符偏移和行号应明确基准，若规范化改变长度，不可复用规范化偏移定位原文。
3. **先按结构切分，再按长度切窗。** 优先识别章节、段落和对话边界，尊重原文顺序；段落过长时再按句子切为短窗口，保留相邻句重叠与足够上下文，避免切掉主语、否定或问答对。初始可试 180–320 字、重叠 1–2 句；对英文、没有标点的长段、超长单句需要安全回退。参数是评测起点，不能保证所有 TXT 的最佳结果。示例脚本为易读采用字符窗口，不包含章节/对话识别。
4. **稳定标识和可追溯来源。** 每个片段保存 `source_id`、原始文件 hash、解码文本 hash、切分版本、`chunk_id`、原文起止偏移、行号和原文片段。明确偏移采用 Unicode code point、UTF-16 还是 UTF-8 字节：Python 的字符串下标是 code point，Android Kotlin 的字符串下标是 UTF-16；不能直接交换这两种偏移。以统一契约或显式转换解决，并测试补充平面字符、CRLF、尾部换行和跨行切片。
5. **构建索引。** 初期沿用 `scripts/prepare_database_bundle.py` 的 `hash_embedding()` 与 Android `HashEmbeddingModel`，对短片段计算向量；检索阶段对问题使用**相同**规范化、编码、n-gram、哈希、维度与归一化规则。记录参数和索引版本；任何一侧改变都要重建并验证整个索引。示例 2 直接复用现有函数，故不引入尚未实现的简繁归一化。
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