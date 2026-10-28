# RAG 工作逻辑与运行流程

本文描述 2026-10-28 当前 Android 代码使用的知识数据、检索、角色授权、prompt 编译、生成及会话边界。当前仍在路径统一计划中：Phase 6 已移除旧根目录和外部索引，旧角色测试 fixture 的迁移尚未完成。

## 1. 唯一知识来源和安装

```text
repository/database/                         唯一知识输入
  ├─ manifest.json                           包身份、能力、受控路径和文件哈希
  ├─ characters/、story/                     角色卡和时间轴
  ├─ knowledge_base_v2/                      SQLite 审计源及原始候选
  ├─ reviewed/atomic_facts.json              经角色知情审核的短事实
  └─ runtime/android/                         当前包的导出和向量索引
       ↓ scripts/prepare_database_bundle.py --check
app/build/generated/databaseAssets/database/ 构建输出，勿手工修改
       ↓ APK assets/database/
<Android filesDir>/database/                 设备唯一当前知识包
```

`prepareDatabaseBundle` 校验仓库根 `database/` 并生成 APK 受控 assets；`verifyDebugDatabaseAssets` 检查最终 APK 的文件和哈希。数据变更后先运行生成命令，再用 `--check` 校验；不要直接编辑 generated 目录或 APK 内的索引。

`DatabaseBundleInstaller` 从 APK 的 `assets/database/` 安装到同父 staging，验证 manifest、每个文件、事实授权和向量契约后整包替换。相同数据集更新失败可以保留已验证旧版本并报告 `RETAINED_PREVIOUS`；不同数据集失败不发布旧包句柄。`DatabaseRuntime` 提供已验证的当前 bundle，回合 lease 防止活动生成中激活另一个包。

程序模板另由白名单打包至 `assets/prompts/roleplay_system.prompt`，安装至 `<filesDir>/program/prompts/roleplay_system.prompt`。其源码位于 `app/src/main/assets/prompts/roleplay_system.prompt`。TTS 参考音频也是程序资源。

旧 `character/` 和源码中的 `assets/harness/rag/` 已删除；旧角色 fixture 仅用于尚未迁移的 JVM 测试。新数据库验证成功后启动一次性设备清理，仅删除路径及哈希与旧内置清单一致的文件；修改过或来源不明的文件保留，生产 factory 不从旧目录加载知识。

## 2. 当前数据集与能力

当前 `database/manifest.json` 指向 `three_body_2`，默认角色为罗辑。包内有 5 张角色卡、125 条原始场景候选、229 条角色场景关联边、8 条源关系和 3 条单独审核的罗辑可知短事实。

| 数据 | 当前用途 | 不能推导出的结论 |
| --- | --- | --- |
| 125 条原始场景 | 向量候选；普通 RAG 和角色模式可作为本轮参考资料 | 场景相关不证明角色知道全部旁白、秘密或他人想法 |
| 229 条 `character_scene_relevance/available` 边 | 记录场景关联 | 不自动变成 `believed/direct` 授权边 |
| 3 条 reviewed 原子事实 | 经过角色、时间和预算过滤后可进入角色 system | 不能覆盖所有问题；本包当前均属于罗辑 |
| 8 条源关系 | 当前没有带角色认知审核的运行导出 | 不自动作为角色已知关系；角色卡有向关系按卡的 cutoff 使用 |
| SQLite/FTS5 | 源文件、导出一致性和 SHA-256 审计 | 不是 Android 角色回合的在线 FTS 查询 |
| 384 维 hash n-gram 向量索引 | 当前输入的候选召回，含场景和 reviewed 事实 | 相似度不保证事实正确或角色有权知道 |

包的能力标记当前为 `vector=true`、`v2=true`、`v1=false`、`timed_state=false`。缺失 V1、时间状态或已授权 V2 关系不会回退到旧库；状态和物品不足时按未知处理。

## 3. 向量索引与 V2 事实的区别

索引由 `scripts/prepare_database_bundle.py` 生成，使用 384 维、2–4 gram、BLAKE2b-64、L2 归一化；Kotlin `HashEmbeddingModel` 用相同算法编码本轮问题，`VectorIndex` 按余弦相似度排序。索引在消费者初始化时加载到内存，之后每轮搜索当前输入，而不是每轮重新读取 SQLite。

V2 `knowledge_fallback.json` 包含经单独审核的短事实、角色认知边、获取 sequence 和 cutoff；`V2EpistemicKnowledgeStore` 对问题做中文 2/3 gram 词项召回，也接收 `reviewed_fact` 向量候选 ID/分数。两者用于召回和排序，最终仍检查来源策略、角色、`believed`、获取时间与有效时间。`scene_candidate` 不被直接提升为角色事实。

当前来源策略是已验证包的 `canon / source_span_verified / accepted` 加独立 `dialogue_context_inspection` 审核。独立旧 fixture 仍可测试原有 `curated / verified_curated` 规则；生产不能借此读旧包。

<a id="routing-modes"></a>

## 4. 三种模式

| 模式 | 实际入口 | 检索与 prompt |
| --- | --- | --- |
| OFF | `MainActivity` 直接构造普通用户请求 | 正常聊天；MainActivity 的 OFF 路径不会调用编排器。编排器另有 OFF 向量观测开关，只有显式调用编排器时生效，结果不注入 |
| RAG | `AndroidRagOrchestrator.compile()` | 默认每轮查询当前包向量索引；`PromptBuilder` 将压缩资料放入 user 的 `<检索资料>`，system 给出普通 RAG 规则 |
| CHARACTER_RAG | 同一编排器及 `CharacterPromptCompiler` | 默认每轮查当前包向量索引；审核通过的角色事实和角色卡片段进入 system；向量候选压缩后作为参考资料与当前问题一起进入 user |

`always_on_vector_retrieval` 默认为 true，可通过调试偏好关闭；关闭时 trace 为 `disabled_by_flag`，不能声称该轮执行了向量查询。`retrieve_in_off_mode` 仅影响直接调用编排器的 OFF 路径。无论开关组合如何，都不会重新注册旧外部索引。

角色模式不再用“世界杯、文档、搜索”等关键词打开旧外部库；向量候选只来自当前 `database/`，可压缩后作为未审核的本轮资料附加到 user 消息。当前尚未对这条参考资料路径执行时间或角色知识过滤，存在未来情节和旁白进入模型上下文的风险。

<a id="routing-rules"></a>

## 5. 角色回合的处理顺序

1. `MainActivity` 固定本轮的 bundle、角色、cutoff、会话、模式和模型 family，取得回合 lease。
2. 编排器验证句柄仍指向当前 manifest；角色 ID 和 cutoff 必须在当前包内。
3. 对当前 user 输入进行向量搜索；默认角色候选上限为 `max(topK × 8, 20)`、最低分 0.10。候选结果带当前包标识与 claim ID。
4. `CharacterQueryAnalyzer` 分类身份、来历、关系、状态、物品、剧情或追问。当前包中的非问候回合也会检查 V2 已审核事实；只有判定询问角色自身来历时才补充通用经历、背景、职业等检索词，第三人来历不借用说话者的 profile。
5. V2 将词项和 reviewed 向量候选交给角色授权、时间和来源检查。单一、合法的上一轮目标可以按 ID 重查，但不能绕过这些检查。
6. `DatabaseCharacterCardAdapter` 只在回合 cutoff 达到卡本身声明的 cutoff 后使用完整卡；早期回合只保留基本姓名和语气。无时间状态时不把卡的最终场景、任务或物品当作此刻状态。
7. `CharacterPromptCompiler` 将身份、约束、风格与动态证据放入 system；向量匹配不等于注入，候选还可能因 topK、角色授权、cutoff 和字符预算被丢弃。旧 story/Memory fallback 在当前包模式关闭。
8. `CharacterRagPromptBuilder` 接收向量结果，以 600 字符预算排序、压缩为本轮参考资料并和问题共同放入 user；system 标记资料不是角色亲身记忆或指令。无候选时保留原始 user 问题。`ChatTemplateRenderer` 形成 system/user 和用于显示的渲染版本。
9. `RoleplayAnswerabilityGate` 在仅命中未来事实，或未来事实显著强于仅由共名产生的当前事实匹配时，返回固定的安全回复。询问第三人的来历而没有相关证据时也返回未知；其他普通无命中继续交给模型。这些指代和相关性判断是启发式，不能保证完全理解所有复合问题。
10. 模型通过 `HarnessBackend.sendChatPrompt()` 接收分离的 system/user；结构化请求会清空 native context 并设置本轮 system。生成结果由 `RoleplayGenerationCoordinator` / `RoleplayOutputGuard` 缓冲检查，必要时最多修复重试一次，然后安全回退。

阶段 trace 中的 `authorizedFactIds` 是通过认知授权的候选，`selected` 是真正进入最终 prompt 的项目。二者不同可能只是预算淘汰；用例必须断言目标 ID 实际注入，而不是只断言搜索非空。

<a id="routing-examples"></a>

## 6. 问题示例

| 问题与截止点 | 当前实现可能使用的证据 | 边界 |
| --- | --- | --- |
| 罗辑 `evt-001`：“你的来历是什么？” | `reviewed-luo-ji-profession`：曾研究天文学、当时在大学教社会学 | 已知职业不等于有家乡信息；全传角色卡不会在早期直接注入 |
| 罗辑 `evt-002`：“叶文洁提出的宇宙社会学公理是什么？” | `reviewed-luo-ji-civilization-axioms`，获取方式 `reported` | 把它作为罗辑听到的观点，不能自动视为经验证的宇宙定律 |
| 同一问题在 `evt-001` | 公理边的获取时间在 cutoff 之后 | 审核事实不进入角色 system；原始参考资料目前未做时间过滤，但生成前 gate 可在匹配到未来事实时阻止模型生成 |
| 罗辑 `evt-125`：“你怎么看史强？” | 罗辑角色卡中的有向态度 | 早于角色卡声明的 cutoff 时不使用整张卡的关系 |
| 史强问罗辑才知道的公理 | 没有史强的已审核认知边 | 未经审核的向量片段可能作为参考资料进入 user，不能证明史强知道该事实 |
| “你手里有什么？” | 当前包没有 `timed_state` | 不从剧情中的历史物品猜测当前携带状态 |
| “世界杯文件中陆江仙来自哪里？” | 当前包索引内与问题相关的候选 | 不查询旧世界杯索引或旧角色卡；无可信事实时模型仍可能自行回答，需检查实际输出 |

## 7. 预算、会话与换包

Android 角色 system 默认最多 900 字符；编译器限制 profile、状态、物品、关系、事实各自的候选和预算，并在压缩历史前为本轮证据保留空间。普通 RAG `PromptBuilder` 默认资料预算为 900 字符。具体候选是否被裁掉由 `selectedItems`、`droppedItems` 和对应原因判断。

会话目录为 `<filesDir>/harness/sessions/by-dataset/<dataset_id>/<fingerprint>/`，仅同包、同 fingerprint、有效角色/cutoff 的 recent session 可自动恢复。旧 `<filesDir>/harness/sessions/recent_session.json` 缺少身份时只供历史查看；通过“清空对话 → 查看历史记录”打开，不会进入新回合摘要。Memory 搜索与可注入选择也要求相同包身份和角色 ID；目前生产 prompt 没有启用长期 Memory 注入。

数据包以根 `database/` 为入口，修改后重新生成并构建 APK；当前没有设备内任意数据库导入或热切换 UI。活动 lease 期间安装请求返回 `database_turn_active`，必须先取消并等待回合收敛。绑定变化后，主界面在下次回合或选择角色时重建检索消费者并清空 native context。模型切换只影响推理与聊天模板，不选择另一条知识目录。

## 8. 回答前后控制与质量限制

`RoleplayAnswerabilityGate` 仅处理受匹配和截止点约束的未来事实，不是通用事实正确性检查。`RoleplayOutputGuard` 检查模板 token、提示词泄露、助手身份、可见文本及未引用的反问；修复重试仍可能失败并显示保守回退。引号内的历史原话允许包含问号。分类器无法完美判断所有标点、反问或语义幻觉。

检索到相关依据并不保证模型会完整、自然或准确地回答。当前仅有 3 条已审核短事实，覆盖面有限；完整角色卡的未结构化叙述也不等同于逐事实审核。模型、采样设置与上下文会影响输出，必须区分 prompt 编译结果和真实设备生成结果。

<a id="routing-debug"></a>

## 9. 调试与排查

1. 确认设备运行的是新 APK：检查 `DatabaseInstallation` 的 `dataset`、active/requested fingerprint 和状态；不要以旧 Logcat 条目推断当前版本。
2. 在回答的 Final Prompt 对话框查看 system/user 和 trace；system 的固定角色规则相同是正常现象，关键是动态证据和 user 输入。
3. 查看 `RAG_TRACE`：`dataset`、`fingerprint`、`indexFingerprint`、`vectorExecuted`、`candidates`、`authorized`、`injected`、`rejected`。候选、授权和注入分别是不同阶段。
4. 未查询时检查模式和 `disabled_by_flag`；执行但无目标候选时检查输入与索引；有候选但无授权时看角色边、来源和 `ACQUIRED_AFTER_CUTOFF`；已授权但未注入时看 `dropped` 和预算；已注入而回答不佳时再检查模型生成与 guard。
5. trace/JSONL 默认不含用户问题或事实正文，JNI 只记录 prompt 长度、token 数和状态，不逐 token 打印正文。`RagPromptDebug` 的阶段原文 Logcat 仅 Debug 构建且 `rag_debug` SharedPreferences 的 `raw_prompt_logcat=true` 时启用；当前源码默认值为 true，会记录问题及资料原文，可以显式设为 false。应用内 Final Prompt 仍可查看。
6. 设备安装使用 `adb install -r` 保留数据；在保存本地模型的设备上避免会卸载应用的测试任务。JVM、APK 清单和设备 prompt 测试不代替真实模型输出验证。

### 9.1 使用 Logcat 确认本轮读取了哪个数据库

先运行当前项目构建的 **Debug APK**，选择角色卡模式并发出一个具体问题。在 Android Studio 的 Logcat 选中目标设备和 `com.example.minicpm_v_demo` 进程，再搜索下列标签或文本；确认日志时间对应刚才的提问。安装日志只在应用进程启动时输出，若没有看到，可重新启动应用后查看，不必清除应用数据。

| 搜索词 / 标签 | 看什么 | 如何判断 |
| --- | --- | --- |
| `DatabaseInstallation` | `database status=... dataset=... active=... requested=...` | `INSTALLED`、`UNCHANGED`、`UPDATED`、`SWITCHED`、`REPAIRED` 表示有已验证的活动包；`RETAINED_PREVIOUS` 表示更新失败后保留了已验证的同数据集旧包，需对比 `active` 和 `requested`；`unavailable` 表示没有可用包。 |
| `MainActivity` 和 `RAG_TRACE` | `mode`、`querySha`、`dataset`、`fingerprint`、`indexFingerprint`、`external`、`candidates` 等 | 每条 trace 对应一次 prompt 编译；`dataset` 和 `fingerprint` 应与预期包一致，`indexFingerprint` 应与该包 manifest 中 `vector_index` 对应文件的 SHA-256 一致。 |
| `Compiled CHARACTER_RAG prompt` | `knowledgeIntent`、`followUp`、`v2`、`profile`、`state`、`possession`、`legacyStory` | 辅助判断问题路由、知识源状态与注入字符数；当前数据包中 `legacyStory` / `legacyMemory` 应为 `false`。 |
| `Database prompt compilation failed`、`Database rebind failed` | 初始化、重新绑定或编译错误 | 结合异常与安装状态检查，不能把缺失 trace 直接解释为“没有检索结果”。 |

也可以在终端实时查看（多个设备时将 `-s <设备序列号>` 加在 `adb` 后）：

```bash
adb logcat -v threadtime -s DatabaseInstallation:I MainActivity:D RagPromptDebug:D '*:S'
```

停止查看按 `Ctrl+C`。过滤器中的 `MainActivity:D` 包含其他主界面消息；在 Android Studio 内搜索 `RAG_TRACE` 可以进一步缩小范围。如果 Logcat 丢失或截断长行，Debug 版本还会将不含正文的记录追加到应用私有目录，可查看最后几行：

```bash
adb shell run-as com.example.minicpm_v_demo cat files/harness/diagnostics/rag_turn_trace.jsonl | tail -n 5
```

`run-as` 需要可调试应用及设备支持；Release 包或设备限制可能使其失败。JSONL 是追加记录，必须结合本轮 `querySha256`、时间顺序及角色/cutoff 分辨，不能只看文件中任意一行。向量索引在初始化时加载到内存，后续每轮搜索内存索引；一次成功的检索并不意味着每轮重新打开 SQLite。

如需核对项目当前包的身份及索引哈希，可在项目根目录运行：

```bash
python3 - <<'PY'
import json
from pathlib import Path
root = Path('database')
manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
index_path = manifest['paths']['vector_index']
print('dataset:', manifest['dataset_id'])
print('fingerprint:', manifest['dataset_fingerprint'])
print('vector index:', index_path)
print('index fingerprint:', next(item['sha256'] for item in manifest['files'] if item['path'] == index_path))
PY
```

上面读取的是源码 manifest 的声明值；要检查实际文件与 APK 是否一致，运行 `python3 scripts/prepare_database_bundle.py --check` 和 `bash ./gradlew :app:verifyDebugDatabaseAssets`。如果安装日志显示不同 fingerprint，应先检查设备安装版本或 `RETAINED_PREVIOUS`，而不是仅根据源码判断设备正在使用哪个包。

### 9.2 判断“执行、命中、授权、注入”

同一条 `RAG_TRACE` 按以下顺序看，四步不可互相替代：

1. **是否执行：** `external=vectorExecuted=true` 表示尝试执行了向量搜索；还要检查 `failure=none`。`vectorExecuted=true` 且 `failure` 为异常名只表示尝试失败。`vectorExecuted=false` 时检查 `backend=disabled_by_flag` 等原因、模式和诊断开关。实际 UI 的 OFF 普通对话直接走普通推理路径，不应强行期待每轮都有 RAG trace。
2. **是否命中当前索引：** `collection=database:<dataset_id>:<fingerprint>` 应与当前包一致，`backend=hash_ngram` 表示当前哈希 n-gram 向量实现。`external` 内的 `candidates=数量/上限` 是过滤后的候选数；行尾独立的 `candidates=` 列出候选 ID。零候选表示该次搜索在阈值和上限下没有结果，不等于数据库为空。
3. **是否获得角色授权：** 角色模式看 `authorized=` 和 `rejected=`。例如 `ACQUIRED_AFTER_CUTOFF` 表示事实在当前时间点尚不可知，`NO_LEXICAL_MATCH` 表示未形成对应匹配。向量候选可以是未经审核的场景，出现候选 ID 不代表角色可以使用该内容。
4. **是否进入最终 prompt：** 角色模式看行尾 `injected=`、Final Prompt 内 `selected` 和 `dropped`，再直接检查最终 system 文本。授权事实可能被预算裁掉；`injected` 也可能包含 profile 或关系，并不全是向量候选。普通 RAG 资料在 user 消息的检索资料段中，查看其 `contextChars`、来源和最终 user prompt；角色模式的 `external` 内 `injected` 表示作为参考资料进入 user 的来源；行尾独立的 `injected` 表示进入 system 的角色条目。两者分别判断。

例如对当前 `three_body_2` 数据，选择罗辑、cutoff `evt-001`，提问“你的来历是什么？”：已有设备样本中 `reviewed-luo-ji-profession` 同时出现在候选、授权和 `authorized_story_facts:` 注入结果里，system 包含大学社会学相关内容。使用同一个问题，在不同角色、cutoff、数据版本或预算下结果可能不同。`evt-002` 下询问“叶文洁提出的宇宙社会学公理是什么？”可查看 `reviewed-luo-ji-civilization-axioms`；改到 `evt-001` 应检查该事实的 `ACQUIRED_AFTER_CUTOFF`，而不是期望它被注入。实测记录见 [Phase 5 设备样本](char_inte_plan/Phase_5_Device_Evidence.md)。

对比不同问题时，可用 `querySha` 确认是否是不同输入、用 `systemSha` 与选中 ID 对比动态证据，但哈希相同或不同本身不能证明检索准确。`querySha` 是输入 UTF-8 的 SHA-256 前 16 个十六进制字符；包括标点和空格。短输入的哈希仍可能被猜测，不应当作匿名化保证。

### 9.3 查看阶段正文和检索关键词的限制

默认的 `RAG_TRACE` 和 JSONL **不打印用户问题、事实正文或实际分词后的关键词**；独立的 `RagPromptDebug` 阶段正文在当前 Debug 源码中默认开启。`querySha` 不是关键词；`knowledgeIntent`、`intents`、`routeCodes` 是路由分类，也不是搜索词。当前没有直接展示向量查询分词列表的正式日志，不能从这些字段还原检索实际用了哪些 n-gram。应用内 Final Prompt 可以核对本轮 user 原文和最终 system；如果还需要查看阶段正文，可在 **Debug 可调试构建** 中临时打开 `rag_debug` SharedPreferences 的 `raw_prompt_logcat` 布尔值：

1. 在 Android Studio 用调试器连接应用，在 `MainActivity` 的 prompt 编译逻辑处暂停；使用 Evaluate Expression，在当前 `MainActivity` 上执行：

   ```kotlin
   getSharedPreferences("rag_debug", 0).edit().putBoolean("raw_prompt_logcat", true).apply()
   ```

   恢复运行后重新提问，并在 Logcat 搜索 `RagPromptDebug`。若当前调试器上下文不是 Activity，在表达式前使用实际的 Activity 实例；不要覆盖整个 preferences 文件，以免丢失其他设置。
2. 在同一 `turn=` 下按 `seq=` 查找 `V2_RETRIEVAL_RESULT`、`CHARACTER_AFTER_V2`、`CHARACTER_PROMPT_COMPILED`、`FINAL_RENDERED` 和 `BACKEND_REQUEST`。长内容使用 `BEGIN`、多个 `CHUNK`、`END` 分段，须按顺序查看。`V2_RETRIEVAL_RESULT` 的 `status`、`authorizedCandidateCount`、`id` 和正文描述已授权的 V2 候选，**不是**所有向量候选，也不是最终 prompt 注入的保证；`SKIPPED reason=...` 说明对应阶段没有可展示内容。`BACKEND_REQUEST` 是交给后端的 system/user 文本。
3. 调试结束后，在调试器中将相同布尔值设为 `false`：

   ```kotlin
   getSharedPreferences("rag_debug", 0).edit().putBoolean("raw_prompt_logcat", false).apply()
   ```

原文日志会包含用户输入、角色资料和检索文本；开启后产生的 Logcat 内容可能被设备或日志收集工具保留，关闭开关不会自动擦除已经产生的日志。Debug 阶段日志也不显示模型内部推理或所有 n-gram；需要逐项核对匹配词时，应在 `HashEmbeddingModel`、`VectorIndex.search` 或 `V2EpistemicKnowledgeStore.retrieveAudited` 设置断点，检查本次调用的输入和局部变量，注意不要把原文写入持久化日志。

## 10. 关键源码与计划

- `app/build.gradle.kts`、`scripts/prepare_database_bundle.py`：唯一输入、生成和 APK 校验。
- `harness/data/database/`：路径、manifest、validator、installer、runtime、lease 与角色卡时间适配。
- `harness/rag/AndroidRagOrchestrator.kt`、`RagSearchService.kt`、`VectorIndex.kt`：模式分流、当前包候选搜索及 trace。
- `harness/character/CharacterPromptCompiler.kt`、`knowledge/V2EpistemicKnowledgeStore.kt`：问题意图、角色/时间授权、排序、预算及 system 编译。
- `harness/session/ChatSessionStore.kt`、`MemoryStore.kt`、`CharacterFollowUpResolver.kt`：包身份隔离、只读历史和追问复核。
- `harness/character/RoleplayAnswerabilityGate.kt`、`RoleplayOutputGuard.kt`：未来事实 gate 和可见输出保护。
- [统一路径实施计划](char_inte_plan/Database_Path_Unification_Plan.md)、[数据库说明](../database/README.md)：阶段状态、数据协议及待删除范围。
