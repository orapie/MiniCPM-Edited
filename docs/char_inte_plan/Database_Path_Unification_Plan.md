# 数据检索路径统一与旧数据源停用计划

日期：2026-10-27  
状态：计划待实施；本文依据当前仓库代码、目录与新库只读检查编写。  
目标：项目根目录 `database/` 成为唯一可替换的知识数据源，停止从旧 `character/` 数据包、旧 Harness 角色知识和旧外部 RAG 数据库取得回答依据。

本次仅新增方案文档，不迁移、删除或修改运行时代码与数据。当前工作区已经存在将 `three_body_2_character_kb/` 重命名为 `database/` 的暂存改动，实施时必须保留。

## 1. 当前检索路径：先纠正对现状的理解

### 1.1 Android 并不直接读取开发电脑上的根目录 `character/`

当前实际运行链路是：

```text
app/src/main/assets/harness/
  → AssetBootstrapper.bootstrap()
  → Android <filesDir>/harness/
  → HarnessDataPaths
      ├─ rag/index.json → RagSearchService → VectorIndex + HashEmbeddingModel
      └─ characters/
          ├─ characters/*.json → CharacterJsonLoader / CharacterProfileStore
          ├─ state/*.json → CharacterStateLoader
          ├─ story/story_events.jsonl → KeywordStoryRetriever / MemoryRetriever
          └─ knowledge/
              ├─ v2/knowledge_fallback.json → V2EpistemicKnowledgeStore
              ├─ v2/character_knowledge_v2.sqlite3 → 导出一致性哈希核验
              └─ v1/{manifest.json,claims.jsonl,relations.jsonl} → V1 stores
```

证据入口：

| 当前文件 | 已核实的行为 | 迁移影响 |
| --- | --- | --- |
| `app/src/main/java/com/example/minicpm_v_demo/harness/data/HarnessDataPaths.kt` | `from(context)` 固定到 `<filesDir>/harness`；`ragIndexFile`、角色卡、V1/V2 等路径直接由 Kotlin 属性拼接 | 只修改 JSON manifest 的 paths 不会改变这些消费者 |
| `app/src/main/java/com/example/minicpm_v_demo/harness/data/AssetBootstrapper.kt` | 从 APK 的 `harness` 复制普通资源，单独校验并安装旧 V1/V2；普通文件每次 bootstrap 可覆盖 | 必须同时调整打包、安装与加载，阻止旧包重新进入运行目录 |
| `app/src/main/java/com/example/minicpm_v_demo/harness/rag/AndroidRagOrchestrator.kt` | `fromContext()` 同时构建外部检索服务和角色编译器 | 旧外部索引在请求前就可能被加载，不能仅在拼 prompt 时忽略它 |
| `app/src/main/java/com/example/minicpm_v_demo/harness/rag/RagSearchService.kt` | `fromPaths()` 直接 `VectorIndex.load(paths.ragIndexFile)` | 当前唯一通用向量索引入口指向旧 Harness 数据 |
| `app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompiler.kt` | `fromPaths()` 创建角色卡、状态、V1、V2 的消费者；V2 无授权事实时可回退 V1 claim / story events | 所有回退也必须位于当前新数据包，不能保留旧小说兜底 |
| `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt` | 维护当前角色、cutoff、历史摘要；默认仍是 `lu_jiangxian` / `evt-010` | 换成三体库后必须从新包选择默认角色并检查旧会话 |

根目录 `character/` 当前的相关读取包括：

- `character/export_v2_knowledge_fallback.py`：导出工具，输入是调用方提供的 SQLite/manifest 路径；不是 Android 在线读取服务。
- `character/character_kb_agent_bundle/` 与 `character/character_kb_v2_agent_bundle/`：旧数据包、Python 读取器与来源文件。
- `app/src/test/java/.../character/knowledge/CharacterKnowledgeContractValidatorTest.kt`：通过 `characterRoot()` 查找 `character` 或 `../character`，直接验证旧 V1/V2 包。
- `app/build.gradle.kts` 当前没有将根目录 `character/` 或 `database/` 作为生成数据源接入的构建任务。

因此，应迁移上述工具和测试的活跃输入，同时把 Android 的旧 assets 数据入口替换掉；不能认为改根目录名称就会自动改变 APK。

### 1.2 当前两条检索分支的执行范围

| 模式 | 向量检索 | 角色资料/剧情检索 | 旧外部资料注入 |
| --- | --- | --- | --- |
| `OFF` | 默认不开启；`retrieveInOffMode` 开关可触发查询 | 不执行角色编译 | 不注入 |
| `RAG` | 开关默认允许每轮查旧 `rag/index.json` | 不执行角色编译 | 结果经 `PromptBuilder` 放入 user message |
| `CHARACTER_RAG` | 默认每轮也查旧 `rag/index.json` | 按问题意图检索 profile/state/possession/V1/V2；严格按角色/cutoff 过滤 | `CharacterQueryRouter` 命中“文档、世界杯、搜索”等词才选择外部结果；`/novel_test/` 路径黑名单只覆盖一部分来源 |

关键区别：当前角色 V2 使用内存中文 n-gram 倒排检索，V1 使用结构化/词项检索；这两者不能统称为“character 文件夹内的向量数据库”。通用向量检索是 `VectorIndex` 上的 384 维 hash n-gram 向量。当前 `database/` 的 SQLite/FTS5 也没有嵌入向量表。

## 2. 新 `database/` 的现场检查与接入阻碍

目前目录已经包含三体Ⅱ数据包：

```text
database/
├─ characters/                         # 罗辑、章北海、史强、庄颜、丁仪
├─ story/story_events.jsonl            # 125 条事件
├─ knowledge_base_v2/
│  ├─ manifest.json
│  ├─ character_knowledge_v2.sqlite3
│  ├─ graph/                           # claims、knowledge_edges、relations 等
│  ├─ corpus/                          # documents、scenes、utterances
│  ├─ snapshots/
│  └─ quality/
├─ schemas/
├─ scripts/
├─ runtime/validation.py
└─ README.md
```

只读 SQLite 检查结果：`PRAGMA integrity_check = ok`；125 条 claims、229 条 knowledge_edges、8 条 relations。它是独立数据包，当前 README 也声明尚未修改 Android 默认资源/角色。

必须在路径切换时一并解决以下问题：

1. **缺少移动端运行产物。** 当前未提供 `knowledge_fallback.json`、`VectorIndex` 可读取的 `index.json`、统一入口 manifest、独立 prompts 或时间状态目录。APK 不会自动执行 Python 或访问电脑根目录。
2. **审核字段不兼容。** 新库全部事实是 `source_tier=canon`、`verification_status=source_span_verified`、`canon_status=accepted`；当前 V2 只接受 `curated / verified_curated / accepted`。直接导出新库仍会全部因来源策略被拒绝。
3. **角色认知语义不兼容。** 新库全部 229 条边使用 `acquisition_method=character_scene_relevance`、`belief_status=available`；当前只接受 `believed`，且“场景出现/文本相关”本身不能证明角色知道场景中所有旁白、秘密或他人内心。不能批量改成 `direct / believed` 来通过校验。
4. **事实粒度超预算。** 新库 `claims.statement` 是场景正文，长度最短 431、平均约 2254、最长 2534 字符，125 条全部超过当前最多 180 字符的事实段预算。即使召回成功，原样注入也会被 `CharacterPromptCompiler` 丢弃；必须产生短的、有证据和认知归属的原子事实。
5. **关系不是旧 V1 格式。** 新库关系表是 `source_entity_id / target_entity_id / relation_type / description`；当前 V1 消费者要求旧 V1 bundle contract。缺少 V1 时必须声明不支持或使用新包内的适配器，不能借用旧 V1 关系。
6. **跨库 ID 会碰撞。** 旧库和新库都有 `evt-010` 等 ID；会话当前 `characterPackId` 默认 `character_system`，不足以证明数据集一致。旧摘要、记忆、follow-up ID、缓存不能进入新库对话。
7. **角色卡也可能包含未来信息。** 新卡的背景、身份、动机概括覆盖人物完整经历；早期 cutoff 不能仅因内容在 profile 中就直接使用。时间状态、profile 证据范围需要与新包一致，不得从旧 `state/lu_jiangxian.json` 补齐。

上述内容是本次代码与数据检查结论。`validation.passed=true` 和 SQLite 完整性通过不等于已满足 Android 的检索、认知与字符预算协议。

## 3. 目标路径与数据源约束

### 3.1 唯一编辑路径与唯一设备运行路径

约定“根目录”指仓库根目录，唯一知识数据输入为：

```text
<repository>/database/
    ↓ 校验与生成：只读该目录中的数据文件
<app>/build/generated/databaseAssets/database/
    ↓ 合并进 APK：assets/database/
<Android filesDir>/database/
    ↓ 所有检索器取得同一个 DatabaseDataPaths / DatabaseBundleHandle
当前问题检索 → 数据集内授权过滤 → 证据选择 → prompt
```

`assets/database/` 与 `<filesDir>/database/` 都是根目录数据包的受控产物/安装副本，不是独立维护的第二数据源。之后替换数据时，始终修改仓库根目录 `database/` 的内容，重新校验和构建 APK。

本期“切换数据库”指替换**符合约定的数据包**，不代表把任意 SQLite/Chroma/FAISS 文件放进目录就能自动使用。不同物理格式须先通过适配器生成统一 contract。若未来要求设备免重建换库，应另加数据包导入入口，并仍原子安装到 `<filesDir>/database/`；本期不引入后台扫描或多路径自动寻找。

### 3.2 明确禁止的活跃来源

- 根目录 `character/character_kb_agent_bundle/**`、`character/character_kb_v2_agent_bundle/**`。
- `app/src/main/assets/harness/rag/**` 及其已安装副本 `<filesDir>/harness/rag/**`。
- `app/src/main/assets/harness/characters/{characters,story,state,knowledge}/**` 及旧安装副本。
- 任意 `character`、`../character`、旧目录名、旧绝对路径的自动发现/回退。
- 为填补 V1、状态、证据或向量索引缺失而搜索旧库的逻辑。

旧文件可以保留作历史材料；生产打包、安装、服务构造和请求检索都必须排除它们。禁止依赖 `novel_test` 等名字黑名单判断来源，统一用当前包身份、受控根目录与文件清单判断。

`harness` 中模型配置、通用程序模板、agent 配置可继续存在，但数据复制必须改为允许清单。模板可作为代码资源复用，不能通过模板夹带旧人物事实。会话、Memory、诊断文件仍是用户运行数据，必须按数据包隔离。

## 4. 统一数据包协议

新增 `database/manifest.json` 作为包入口；已有 `knowledge_base_v2/manifest.json` 继续描述源知识库，二者职责不同。最低字段建议：

```json
{
  "bundle_schema_version": "1.0",
  "dataset_id": "three_body_2",
  "dataset_version": "<publisher-version>",
  "default_character_id": "luo_ji",
  "capabilities": {"vector": true, "v2": true, "v1": false, "timed_state": false},
  "paths": {
    "characters": "characters",
    "story_events": "story/story_events.jsonl",
    "v2_manifest": "knowledge_base_v2/manifest.json",
    "v2_database": "knowledge_base_v2/character_knowledge_v2.sqlite3",
    "runtime_v2": "runtime/android/knowledge_fallback.json",
    "vector_index": "runtime/android/vector/index.json"
  },
  "embedding": {"type": "hash_ngram", "dimensions": 384},
  "files": [{"path": "<relative-path>", "sha256": "<sha256>"}]
}
```

这是协议草案，字段值和 hash 必须由构建器计算。`dataset_fingerprint` 对包入口及受控文件清单计算，供安装、会话和缓存校验；不要递归将 fingerprint 自己纳入其输入。

协议要求：

1. 所有路径为包内相对路径，规范化后仍在 `database/` 内；拒绝 `..`、绝对路径和越界符号链接。源 manifest 中的 Windows `source` 路径仅是来源说明，不得变成运行时查找路径。
2. `characters`、事件、cutoff、知识边和证据 ID 必须交叉引用一致；默认角色必须存在，cutoff 从对应卡/manifest 验证，不能硬编码旧角色。
3. capability=false 时返回显式 `unsupported`，不构建相应 store，也不尝试其他目录。capability=true 但缺文件/hash 不匹配时整个候选包不能激活。
4. 导出文件必须携带源库 hash；向量索引还需记录 embedding 算法/维度、原子事实 ID、数据集 ID 和版本。元数据不完整的索引不参与角色授权。
5. 继续使用兼容现有 `VectorIndex` 的 hash n-gram 向量作为第一版，避免路径迁移同时引入新 embedding 依赖；后续可换算法，但查询与索引配置必须一致，变化时强制重建。
6. 向量命中只产生候选，不等于角色可知。必须按 claim ID 重新与当前包认知边、时点、审核和有效期校验；不得把整段原文直接当作角色记忆。

推荐的新增运行产物均写在 `database/runtime/android/`，保留现有 `database/runtime/validation.py`；构建期仅将需要的文件复制到 generated assets，`reports/`、构建脚本和非必要原文不打包。

## 5. 实施阶段与通过条件

### Phase 0：固定协议、路径入口与数据适配审计

新增 `DatabaseDataPaths.kt`、`DatabaseBundleManifest.kt`、`DatabaseBundleValidator.kt`（建议放在 `harness/data/database/`）。路径只能由根目录句柄派生；`HarnessDataPaths` 留给非知识运行数据。测试显式从仓库根 `database/` 解析，兼容 Gradle 以 `app/` 为工作目录的情况，但不能搜索其他数据目录。

新增根目录 `scripts/prepare_database_bundle.py`，负责：

- 接收唯一源目录 `--source database`，验证数据库、JSON/JSONL 和 manifest；缺文件时输出准确错误，不扫描 `character/`。
- 复用/迁移 `character/export_v2_knowledge_fallback.py` 的 SQLite 只读导出逻辑到通用 scripts 目录，统一输入来源；替换文档与测试中的活跃旧路径。
- 输出前明确列出当前三体库的来源/认知字段、长正文和缺失 capability，生成可审阅的兼容报告；保留原始字段，不通过伪造 `verified_curated` 或 `believed` 解决冲突。
- 为 `canon/source_span_verified` 制定独立来源验证规则；原文匹配通过只能确认文本存在，不能同时赋予角色知情权限。
- 补充经核验的短原子事实（建议每条不超过 120 字符）、来源跨度与真实获得方式；`character_scene_relevance/available` 暂仅作为候选检索边，角色授权未核实前不得进入可信事实段。
- 对旧 story fallback 的 `knowledge_by_character.source=direct` 同样审查：当前生成器按场景参与者批量填充，不能让它绕过 V2 的认知限制。

**通过条件：** 路径协议测试通过；能区分“文件有效”“候选可召回”“角色已授权”；至少形成一组当前新库可用的授权事实作为后续贯通样本。未通过数据适配时停在此阶段，不能以旧库补齐。

### Phase 1：构建只从根 `database/` 生成 APK 数据包

修改 `app/build.gradle.kts`：

1. 注册可增量执行的 `prepareDatabaseBundle` 任务，输入是根目录 `database/` 的数据与协议文件，输出是 `app/build/generated/databaseAssets/database/`；输出不得与输入形成任务循环。
2. 将 generated 目录注册到 main assets，保证资源 merge 依赖生成完成；构建必须使用本次数据库 hash，不能复用另一个包的输出。
3. 旧 `src/main/assets/harness/rag` 和旧角色数据排除在合并结果之外。可使用 AGP 支持的 source filtering，或生成只含允许文件的 Harness 资源树；不能仅靠设备端“不调用”。
4. 新角色 facts、向量索引、关系/状态可选产物来自同一包；通用 prompt 模板从程序资源加载，不拷贝旧小说角色目录。
5. 缺失协议、schema 不支持或索引不匹配时构建失败，错误信息指向 `database/`；禁止“自动使用原 assets”。

**通过条件：** 检查 APK 文件列表，只包含当前 `assets/database/` 知识包；没有 `assets/harness/rag/index.json`、旧小说角色卡或旧知识 bundle。删除 generated 输出后重建仍可从根目录恢复，改 `database/` 内容能使任务失效并重建。

### Phase 2：安装并原子激活 `<filesDir>/database/`

将知识安装从 `AssetBootstrapper` 中拆为 `DatabaseBundleInstaller`：

1. 从 `assets/database/` 解包至同父目录 staging，校验完整文件清单、源/导出一致性、schema 与 ID 引用。
2. 原子替换整个当前包，防止 A 包中没有的旧角色/关系文件残留；可复用现有临时目录、备份和 hash 校验思想。
3. 校验失败保留此前同一 `dataset_id` 的已验证版本并报告状态；候选属于另一数据集时，不能悄悄继续显示旧数据集为新库。当前所选包不可用时让依赖知识的功能显式不可用。
4. 调整 `MiniCPMApplication`/`AssetBootstrapper` 的调用顺序，初始化知识消费者前必须取得有效 bundle handle。
5. 旧 `<filesDir>/harness/rag` 与旧角色知识目录即使物理存在，也不注册为候选目录；默认不删除用户文件，通过访问计数/测试证明从未读它们。
6. Harness 通用文件复制改为白名单，防止每次启动又恢复旧数据。

**通过条件：** 新安装、覆盖升级、hash 损坏、同包升级、换数据集均有设备测试；安装后仅 `database` 根参与知识加载；模型文件、会话历史保留。

### Phase 3：检索器统一注入当前包，永久停用旧外部分支

修改 `AndroidRagOrchestrator.fromContext()`：只从 `DatabaseBundleHandle` 构造 `RagSearchService` 和 `CharacterPromptCompiler`。移除生产服务对 `HarnessDataPaths.ragIndexFile` 的依赖，角色子路径也改用 `DatabaseDataPaths`。

| 模式 | 迁移后行为 |
| --- | --- |
| `OFF` | 默认普通聊天；若保留 OFF 向量观测开关，也只能查询当前 `database`，不能因此构造旧服务 |
| `RAG` | 每轮查询 `database/runtime/android/vector/index.json`，依据当前包资料回答；旧外部库完全没有入口 |
| `CHARACTER_RAG` | 每轮查询当前包向量候选，再与当前包 profile/state/possession/关系/剧情检索合并；当前角色认知、cutoff、审核与最终预算过滤后才进入 prompt |

具体要求：

- 删除旧 `CharacterQueryRouter.requiresExternalKnowledge()` 外部资料触发分支及 `novel_test` 黑名单。输入含“世界杯、外部资料、文件、搜索”也不加载旧库。
- `CharacterRagPromptBuilder` 继续承担 system/user 信任层分离，但不再接收旧外部结果。通用资料引用只能来自当前包，不能赋予角色未获得的知识。
- `AlwaysOnVectorRetrievalService` 绑定当前包索引，不再把“每轮检索”实现为每轮查询旧外部库。任意开关组合都不能恢复旧数据路径。
- V2 export、V1 adapter、关系 adapter、状态 loader、profile store、旧事件回退全部注入同一 bundle。V1 缺失是正常 capability 状态，不能强制安装旧 V1。
- 新 V2 关系优先通过专用适配器检索；如生成 V1 兼容产物，产物必须来自同一新库，保留方向、有效期、证据和当前角色权限，并明确标记转换 schema。
- 缺时间状态时返回当前包的未知，不读旧状态；未经时间标注的角色全传资料不得在早期剧情点使用。
- 修复 `CharacterQueryAnalysis.stableIntentLabel()` 中纯 `STORY_FACT` 仍可能标成 `unknown` 的观测问题；检索开关与标签分离验证。
- 无命中、超预算、未授权、损坏或不支持必须有稳定原因码；禁止捕获异常后自动退回旧目录或普通模式而不说明状态。

**通过条件：** 在旧 Harness 文件仍存在且有高分“诱饵”命中的测试中，旧文件读取次数为 0。当前包存在样本可真实进入 prompt；未授权样本不会通过向量、V1 或事件回退旁路进入。

### Phase 4：角色选择、历史、缓存与模型上下文按数据包隔离

重点修改 `MainActivity.kt`、`ConversationSession.kt`、`ChatSessionStore.kt`、`MemoryStore.kt`、`MemoryPromptSelector.kt`、`CharacterFollowUpResolver.kt`：

1. 默认角色取 manifest 中的有效 ID；切换角色使用新卡的有效 cutoff，取消生产路径中的旧小说 ID 兜底。
2. 会话与 Memory 新增 `dataset_id / dataset_fingerprint`；读取历史前先验证。旧记录缺少 dataset 标识时只作历史查看，不进入当前摘要、memory selection 或 follow-up。
3. 若利用已有 `characterPackId` 表示数据集 ID，仍需新增 fingerprint，避免同名数据包换内容后沿用旧事实。
4. 同 ID 不同包的 `evt-010`、claim、profile ID 不得互通。包更换时开始隔离会话；保留原历史文件，不直接销毁。
5. 包 fingerprint 改变时重建 `ragOrchestrator`、V1/V2 store、向量索引、角色列表及相关缓存；不能让 lazy 初始化的旧 document 继续参与检索。
6. 切换只能发生在回合边界；活动生成须取消并收敛后切换句柄，避免新旧包混入同一回合。重试绑定原回合的 bundle；新包激活后清空 native 上下文并设置新的 system prompt。
7. 日志记录 `dataset_id / fingerprint / index fingerprint / candidate IDs / authorized IDs / injected IDs / rejected reason`，不记录用户问题或证据正文。让调试页能确认本轮使用哪一库。

**通过条件：** A→B 切库、同名角色/事件、同数据集换版本、旧会话恢复、连续追问和重试均不会复用其他包内容；模型切换只换推理后端，数据包路径仍固定。

### Phase 5：验收、文档与交付

自动化验收必须同时覆盖“新库可用”和“旧库禁止读取”：

| 测试 | 必须断言 |
| --- | --- |
| 路径/manifest | `../character`、绝对路径、越界链接被拒绝；受控文件均归属当前根 |
| 构建/APK | 从 `database/` 生成；旧 assets 不进 APK；增量构建响应输入变化 |
| 安装 | 文件清单一致；整包激活无旧角色残留；损坏不串用其他包 |
| 根目录与旧外部库隔离 | 给旧 `character`、旧 `harness/rag` 放独有诱饵；删除/破坏它们不影响新库运行；文件访问计数均为 0 |
| 三种模式与开关 | OFF/RAG/CHARACTER_RAG、向量开关与知识图谱开关均不能恢复旧来源 |
| 新库正向检索 | 用已核验三体问题断言特定原子 claim 命中、通过授权并进入最终 prompt；不能仅断言结果非空 |
| 源语义与预算 | scene relevance 不视作知情；未审核/未来/私密事实被排除；长原文不会全段塞入 180 字符预算；短授权事实不被标题与摘要挤掉 |
| 会话/追问 | 不同包同 claim/event ID 不可互用，旧 memory/summary 不进新回合 |
| 错误处理 | 缺索引/缺导出/缺角色/缺 capability 明确提示，无旧库回退 |

`CharacterKnowledgeContractValidatorTest` 当前直接读取根 `character` 的部分，应改为校验根 `database` 的新协议；旧 V1/V2 单元样本可成为明确的测试 fixture，但不能再作为生产默认数据源或隐式兼容目录。

设备端安装应用后执行：罗辑身份/来历、史强与罗辑关系、一个具体历史事件及同一事实前后 cutoff、代词追问、无资料问题、模型切换、旧库诱饵问题。至少在一个已安装可用模型上跑实测，分别保存检索证据与回答质量结果。JVM/APK 检查不能代替设备输出；其他模型未跑时如实注明。

更新 `README.md`、`docs/RAG_Logic.md`、`database/README.md` 与旧图谱接入文档：明确根 `database/` 是唯一输入，generated assets 不手工编辑，`character/` 不再参与活跃数据流程，旧外部资料永久停用。

## 6. 文件级实施清单

| 文件/范围 | 修改目的 |
| --- | --- |
| 新增 `database/manifest.json` | 数据包身份、schema、默认角色、capability、受控路径和 hash |
| 新增 `scripts/prepare_database_bundle.py` | 唯一输入校验、运行时导出、短事实/向量产物与 manifest 准备 |
| `character/export_v2_knowledge_fallback.py` | 将复用代码迁至通用工具；移除活跃工作流对根 `character` 的依赖 |
| `database/scripts/*` | 输出固定在本数据包，改进事实粒度与认知边生成；保留原始来源 |
| 新增 `harness/data/database/*` | 路径、manifest、validator、installer、bundle handle |
| `app/build.gradle.kts` | 接入唯一根输入和 generated assets，排除旧知识资源 |
| `AssetBootstrapper.kt` / `HarnessDataPaths.kt` / `MiniCPMApplication.kt` | 分离程序资源与知识安装；停止旧库恢复和旧路径暴露 |
| `AndroidRagOrchestrator.kt` / `RagSearchService.kt` / `AlwaysOnVectorRetrievalService.kt` | 只构建当前包检索服务，删除旧外部触发路径 |
| `VectorIndex.kt` / `HashEmbeddingModel.kt` | 校验索引与 embedding contract，候选携带当前包和事实 ID |
| `CharacterPromptCompiler.kt` / loaders / knowledge stores | 所有知识面与回退使用同一包；兼容审核与关系结构，保留认知过滤 |
| `CharacterRagPromptBuilder.kt` / `RagTurnTrace.kt` | 证据来源与信任分层、数据包溯源，不再注入旧外部资料 |
| `MainActivity.kt` / session / memory / follow-up | 新包默认角色、旧会话隔离、切包后重新构建服务与上下文 |
| JVM / Android tests | 路径隔离、旧库零访问、新库证据贯通和切包回归 |

## 7. 推荐实施顺序与完成定义

顺序固定为：**协议与新库语义核验 → 生成/打包 → 原子安装 → 消费者切换及旧外部入口移除 → 会话/缓存隔离 → 设备验收**。

第一阶段交付应是可运行的校验/适配工具、入口 manifest 和路径协议；不能仅把旧路径字符串替换为 `database` 就宣布完成。整体完成必须满足：

- [ ] 替换仓库根 `database/` 中符合协议的数据后，无需修改 Kotlin 路径即可构建并使用新库。
- [ ] APK 与设备运行时仅有一个活跃知识数据包，所有回合能追溯到同一 dataset fingerprint。
- [ ] 生产代码、构建任务、活跃测试默认输入都不再搜索根 `character/`。
- [ ] 旧 Harness 外部索引在初始化、每轮检索、关键词触发、异常回退下均读取 0 次。
- [ ] 新数据事实实际通过授权与预算进入 prompt；全部被过滤或全部超预算不算迁移成功。
- [ ] 新库角色/状态/关系/剧情保持时点和知情边界，没有借用旧角色补齐。
- [ ] 旧会话、Memory、follow-up、缓存与 native context 不会污染新数据集。
- [ ] 自动化与至少一个真实模型的设备验收有结果；未覆盖模型明确列为待验收。

本次旧库停用是持续约束。回滚只允许恢复同一 `dataset_id` 的已验证数据包或暂停依赖知识的功能，不允许重新启用 `character/` / `harness/rag` 以填补错误。
