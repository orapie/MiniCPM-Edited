# 角色人设贴合与双 Agent 提示词改进计划

## 1. 目标与范围

目标是让与玩家交互的 Agent B 以当前角色的身份、知识和表达方式自然回答，优先解决第一轮就可能发生的“玩家问你、角色回答也称自己为你”，再提升不同角色的性格、态度和说话差异。先通过真实链路与配对评测定位原因，再逐项实施；不假定增加提示、双 Agent 或 JSON 就能提升表现。

JSON 是后续受控实验：Agent A 的输入和输出已经使用 JSON；可比较 Agent B 的自然语言资料标签与 JSON 数据格式。system/user 角色仍由聊天模板提供，JSON 不替代 system 消息。只有评测证明收益且成本可接受时，才考虑改变默认格式。

本计划补充 `Double_Agent_Prompt_Attribution_Plan.md`，不改变双 Agent、独立上下文压缩、GGUF 模型和最终用户可见自然语言回复的既有设计。此文是实施计划，本阶段不修改运行代码。

## 给实施者的执行说明

### 0.1 进入项目后先做什么

1. 读取本计划与仓库适用的 `AGENTS.md`；执行 `git status --short`，保留已有未提交改动。本文记录的是制定时观察，不代表当前代码或设备状态；修改前重新核实。
2. 从 `app/src/main/java/com/example/minicpm_v_demo/` 追踪角色卡 → prompt 编译 → A 请求/校验 → B 后端请求 → 输出门控/保护 → 可见回复。不要只以 `FINAL_RENDERED`、模型加载成功或构建成功证明模型行为正确。
3. 检查 `adb devices`、现有应用与已下载模型；不要默认设备、模型和采样种子可用。真实设备验证涉及已有会话时，使用隔离测试会话，保留原会话；不清除应用数据、不删除模型、不自动下载模型。覆盖安装前确认目标、签名兼容与数据保留；无法安全验证时记录原因。
4. 按 0 → 1 → 2 → 3 的顺序推进；JSON 属于后续独立实验。用户只要求某一个阶段时，完成该阶段及其必要依赖，记录下一阶段，不擅自扩大为整个计划。

### 0.2 实施约束

- 不修改已授权事实或角色原文来适配测试；不将引用中的第一人称自动认作当前角色；角色、别名和授权范围从当前数据集动态取得。
- 不借角色风格改造泄露隐藏动机、剧情截止点之后的信息或第三方隐私；不将对话摘要变成事实来源。保持 system/user 聊天角色、独立压缩、A 的 GGUF 模型与原有回退路径。
- 不把所有“你”改为“我”，不通过放宽检测或新增硬编码固定回答来虚增通过率。保留正常称呼玩家、引用及不确定回答；必要时最多一次重试并记录其代价。
- 修改前定位已有测试与日志开关；优先扩展已有模块，避免复制另一套 prompt 编译或模型运行链路。不要默认修改 `database/` 即会同步已生成的 Android 索引。
- Debug 明文采集须显式开启并控制保存范围；报告默认只含测试输入或脱敏数据、统计和指纹，不保存真实用户对话、完整 prompt 或模型思维过程。

### 0.3 建议交付物与记录格式

在 `docs/double_agent/` 下新建 `roleplay_eval/` 保存可复用评测材料：

- `cases.jsonl`：每行含 `case_id`、`character_id`、`story_cutoff`、`question`、`scenario`、`expected_speaker`、`allowed_facts`、`forbidden_claims`、`expected_behavior`；测试案例使用合成问题和已授权数据，不把期望回答硬编码成唯一合法文本。固定参考资料的实验另外保存授权来源 ID 和内容指纹，不复制受限原文到日志。
- `baseline.md`：记录代码版本、设备、模型文件名/量化、生成参数、角色卡和资料版本、运行方式、可重复性与限制；记录各组样本数及每项指标的分子/分母。
- `results.md`：按 case 和实验组记录 `case_id`、组别、A 状态及原因、请求/资料指纹、输出来源（原始生成/重试/固定答复/兜底）、评判、延迟、失败或排除原因。测试文本若需保存，仅使用经确认可留存的测试数据；原始用户数据不入库。

评判维度最少包含角色主体/人称正确、事实与授权、角色风格、自然度和延迟；给人设及自然度定义一致的人工评分标准，分别记录不确定项。先记录基线，再在实验前写明比较样本和验收阈值；没有足够样本时只报告探索性结果。不得凭已有一两条回答推断普遍改善。

### 0.4 常用验证命令

在仓库根目录运行；目标测试类以当前文件实际存在为准，按修改范围选择：

```bash
git status --short
git diff --check
bash ./gradlew :app:testDebugUnitTest --tests 'com.example.minicpm_v_demo.harness.character.RoleplayOutputGuardTest' --tests 'com.example.minicpm_v_demo.harness.character.RoleplayGenerationCoordinatorTest' --tests 'com.example.minicpm_v_demo.harness.character.CharacterPromptCompilerTest'
bash ./gradlew :app:testDebugUnitTest --tests 'com.example.minicpm_v_demo.harness.attribution.FinalPromptContractsTest' --tests 'com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestratorTest' --tests 'com.example.minicpm_v_demo.harness.context.ConversationContextCompressorTest'
bash ./gradlew :app:assembleDebug :app:verifyDebugDatabaseAssets
adb devices
```

上面是两组可选择的针对性测试，并非每次都必须全跑；涉及 Android ICU 或 JNI 时另查并按需运行 `RoleplayOutputGuardDeviceTest`、`RoleplayPerspectiveNativeSmokeTest`，确认测试设备 ABI、模型和测试前置条件。构建受依赖、设备或权限阻塞时记录具体命令与错误，不把未运行的检查写成通过。

### 0.5 阶段依赖与停止条件

阶段零先产出可重放用例和基线；阶段一用这些用例修复视角，阶段二在身份和授权边界下改进人设，阶段三仅在具备可比较模型和设备时评估。JSON 实验要求冻结同一个已验证 A 草稿并具备真实 B 测试条件；真实 A 不可用时可做假数据契约测试，但不能声称已完成实验或据此切换默认。

设备或真实模型不可用不阻止离线用例、代码审查与可验证的 JVM 工作；只有需要实际推理的验收标记为未完成。若基线不能复现，不臆造根因；先保留复现材料并排查输入和模型路径，必要时向用户索取测试回合。若判别规则无法以足够高的准确率保护正常“你”，保持观察或默认关闭，而不是强行上线。只有设备评测达到预先确定的指标，才能宣称角色贴合度改善。

## 2. 当前状态与待确认事项

- Kotlin 将角色、问题、压缩上下文和授权参考资料打包为 `RawPromptPackage`；Agent A 的 user 消息已经包含该包的 JSON，system 消息另行提供处理指令；A 返回受约束的 `FinalPromptDraft` JSON。
- 校验通过后，`FinalPromptMaterializer` 根据原始资料和归属结果生成 B 的自然语言 user 消息，system 消息保留角色规则。A 失败、关闭或无授权参考时，当前代码回退或跳过，沿用原有消息。
- 模板安装缺失曾导致 A 未执行；目前模板复制已修复，但尚未有修复后真实模型成功处理并由 B 接收的完整实机证据。`status=success` 表明应用层校验和定稿成功，仍需对照 `BACKEND_REQUEST` 与实际生成链路确认 B 使用了该消息。
- 消息角色和 JSON 是不同层次：system/user 保持模型聊天模板的角色；JSON 是 user 消息里的数据格式。检索内容始终是不可信参考资料，不获得指令权限。
- 角色模板已有第一人称、风格和例句要求；编译器当前最多取两条说话风格、两条例句，角色卡其他字段是否进入当前请求须按实际路径核对。回答也可能来自答复门控、输出保护重试或固定兜底，不能全部归因于模型。
- 第一轮即可出错，因此长历史漂移并非必要条件；优先测试问句人称、聊天模板、角色信息和参考资料的影响。此前 A 模板复制问题已修复，但真实 A 成功后的端到端推理尚待设备验证。

## 3. 优先实施：角色贴合与单轮视角

### 阶段零：建立可复用基线和归因

1. 至少选两个不同角色、每个覆盖身份自述、动机态度、已知经历、人物关系、日常交流和未知事实；同时覆盖“你是谁／你为何这样做”、正常称呼玩家、引用原话，以及当前角色为说话者、对象或旁观者的资料。预先写明正确角色、允许事实、不可泄露内容与人称，避免把所有“你”判错。
2. 对同一角色和问题比较：① 无外部参考、A 关闭；② 同一授权参考、A 关闭；③ 同一参考、A 成功；可增加无角色 RAG 的诊断组。记录检索结果和 A 状态；③ 中 A 未成功或无资料的回合单列，不能算作成功实验。无外部参考仍应保留角色规则及当前问题，不能把 RAG OFF 误当等价条件。
3. 记录设备、B/A GGUF 文件及量化、聊天模板、解码设置、角色卡和资料版本、输入 token 或可测的替代指标、首字及整轮耗时。每例查看同一回合 `BACKEND_REQUEST`、实际后端调用以及最终可见输出；区分模型原始回答、门控固定答复、保护重试与兜底。Debug 原文采集需显式开启，默认只存内容无关指标。
4. 分别统计身份/说话者错误率、事实授权错误、角色风格贴合度、自然度、拒答和统一套话率；每项给出样本数和判分准则，并区分第一轮与多轮。固定随机种子不可用时重复生成并记录。只在无参考组已完成对照后判断资料影响。

验收：可复现至少一条真实错误或说明未复现；能判断各组实际输入和输出来源。无法连接设备或运行真实模型时，完成离线评测框架并明确设备验证未完成，不宣称修复。

### 阶段一：修复单轮身份与说话者视角

实施入口：`app/src/main/assets/prompts/roleplay_system.prompt`、`harness/character/CharacterPromptCompiler.kt`、`harness/rag/CharacterRagPromptBuilder.kt`、`MainActivity.kt`、`app/src/main/cpp/llama_jni.cpp`。仅在证据指向相应路径时修改；保护规则入口为 `harness/character/RoleplayOutputGuard.kt` 和 `RoleplayGenerationCoordinator.kt`。

1. 核对 `CharacterPromptCompiler.kt`、`CharacterRagPromptBuilder.kt`、`MainActivity.kt` 及 `llama_jni.cpp` 的 system/user 和 GGUF chat template 实际处理，包含纯文本与视觉分支；排查首轮状态、已有历史及模型格式兼容问题。
2. 以现有模板为对照，只改变一项尝试：把当前角色身份、玩家与角色的称呼关系写清；测试简短问答格式示例，明确其为虚构格式演示，不向角色注入示例事实。角色名从当前角色卡读取，不针对单一人物硬编码。
3. 审查参考片段说话者与对象、来源及可知范围。真实 A 成功后比较其标注与人工基准；A 的判断未知、低置信度或失败时，保留现有安全回退，不把引用人称转换为角色记忆。确认 B 看到的是经校验的结果。
4. 针对明确的角色自述被误写成第二人称的高置信场景，在 `RoleplayOutputGuard.kt` / `RoleplayGenerationCoordinator.kt` 设计结合问题与语境的检测和最多一次修复重试；保护对玩家称呼、引用和正常反问。先观察误报、漏报，再考虑启用；禁止全局替换代词。

验收：针对多个角色及单轮正反例的 JVM 检测测试、无参考实机回放与带参考回放通过；统计模型原答、重试和兜底各自效果，明确剩余误报漏报。实际质量以设备评测为准。

### 阶段二：改善角色信息与自然表达

实施入口：`database/characters/` 对应角色数据、角色卡解析器、`CharacterPromptCompiler.kt`、`roleplay_system.prompt`、`RoleplayAnswerabilityGate.kt` 和 `harness/context/ConversationContextCompressor.kt`。先检查数据是否已有可用证据，再确定是否需要修改数据或编译逻辑。

1. 在角色卡加载和编译器中审计身份、性格、价值观、动机、风格、行为规则和例句的数据来源、可用性、截断、预算及实际注入。将稳定身份/表达、角色知识和对玩家可公开的信息分开；隐藏动机、剧情截止点和第三方私事必须遵循授权，不能因为存在卡片便全部放入模型。
2. 通过对照选择少量具区分度的性格、价值观与说话示例，优先复用有来源、符合角色时间线的现有数据；缺少例句时使用明确通用回退，不伪造角色原话。例句仅作风格参考，不构成新增事实；测量不同角色是否仍能被区分。
3. 逐项对比模板中答复长度、禁用措辞、追问限制等统一规则，验证是否导致生硬和角色同质化；保留身份、知识与安全边界，选择性放宽表达而非一次删除所有约束。检查 `RoleplayAnswerabilityGate` 的固定答复和保护兜底对自然度的影响，不能为改善风格绕过授权。
4. 检查独立 `ConversationContextCompressor` 对错误回答、代词和角色身份的处理；摘要只辅助承接，不能覆盖可信角色卡或把未经验证的历史输出当角色事实。多轮压缩单独评测，不混同首轮问题。

验收：至少两个角色的同题对比展示可区分的表达；身份、人称、事实和剧情边界不劣于基线，记录自然度及固定答复比例。设备上未测到改善时继续调整或如实报告，不以构建成功替代质量验收。

### 阶段三：模型与性能对照

在同一角色、问题、资料和设置下，比较当前 GGUF 与已兼容项目加载和聊天模板的候选模型。记录模型与量化、设备内存、延迟、人称和人设指标；先验证加载、模板及能力，不假定任意 GGUF 均兼容。若当前模型在无资料首轮仍不稳定，将模型能力列为可能瓶颈，依据实测决定是否更换；不能将不同模型或采样设置的差异归因于提示格式。

## 4. 后续实验：Agent B JSON 数据契约

对照组为现有 `TEXT` 定稿格式；实验组为 `JSON` 定稿格式。两组使用**相同的 RawPromptPackage 和同一个已校验的 A 草稿**，仅改变 B 的 user 消息序列化，system 内容、模型、聊天模板、采样参数、上下文、资料与授权范围均相同。允许记录随机种子；如果后端无法固定随机性，每个样本多次生成，采用配对分析。

JSON 示例（说明结构，非模型生成内容）：

```json
{
  "schemaVersion": 1,
  "character": {"id": "character_id", "name": "角色名"},
  "conversationContext": "仅用于理解代词或明确承接的压缩对话",
  "references": [
    {
      "id": "ref_1",
      "characterRole": "SPEAKER",
      "perspective": "FIRST_PERSON",
      "evidenceRelation": "QUOTE",
      "disposition": "CAUTION",
      "confidence": 0.8,
      "text": "授权参考片段原文"
    }
  ],
  "question": "玩家当前问题"
}
```

- B 的 system 规则说明 JSON 是输入数据，参考内容不是指令、原文人称不等于当前角色；要求自然语言回答而非输出 JSON。实验两组使用同一份 system 文本，以免引入额外变量；新增说明须先在两组一致地验证其不会改变基线含义。
- 角色 ID 与名称从当前角色卡取得；问题、上下文和片段正文从已授权的 Raw 包原样复制；归属字段只使用已通过校验的草稿。禁止 A 自由改写资料、添加实体或替换用户问题。
- 明确 `UNKNOWN`、低置信度与 `CAUTION/REJECT` 的消费含义：复用现有安全语义，不把不确定信息当作角色亲历。当前代码把 disposition 作为标签传入而未必过滤正文，实验不得悄然改变过滤策略；另行审计并记录模型对这些标签的遵循情况。
- JSON 序列化使用可靠编码器处理引号、换行、反斜线和 Unicode；保持片段顺序、完整性和 ID 唯一。版本号、字段含义、缺省/空值和长度预算需固定并测试。超预算时采用与对照组等价的确定性截断策略，记录裁剪结果，禁止只对某一组额外丢资料。
- 处理含伪造 JSON 结束符、角色指令等片段时，内容仍仅作为字符串数据；JSON 本身不构成安全隔离。数据来源及可见范围不因格式改变而扩大。

## 5. JSON 对照实施步骤

### JSON 步骤一：建立可验证基线

1. 复核 A 模板、开关、已下载 GGUF、模型加载状态和角色 RAG 带授权参考资料的触发条件。记录设备、模型文件及量化、应用版本、上下文与推理设置。
2. 在 Debug 设备跑真实 A，记录 `ATTRIBUTION_TRACE` 的 status、reason、模型 ID 与 fingerprint；比对同一回合的 A 请求、验证后结果、`BACKEND_REQUEST` 与 B 实际生成调用。原文日志只在显式 Debug 开关下查看，避免持久化敏感内容。
3. 记录成功率、失败与跳过原因；A 不成功时先定位并修复链路，不能以回退请求当作成功样本。保留现有回退行为并单独统计，不纳入格式对照的成功样本。

验收：至少一条真实 A 成功的回合能关联到 B 实际处理的 system/user 消息及最终答复；明确现有 TEXT 基线。若真实 A 尚不可用，仍可进行下一阶段的假数据及离线实现，但不得宣称端到端实验完成。

### JSON 步骤二：实现可切换定稿格式

实施入口：`harness/attribution/FinalPromptContracts.kt` 中的 `RawPromptPackage`、`FinalPromptValidator`、`FinalPromptMaterializer`，以及 `harness/rag/AndroidRagOrchestrator.kt` 中成功/回退分支；测试入口为 `FinalPromptContractsTest` 与 `AndroidRagOrchestratorTest`。配置入口需先确认现有 feature flag 模式，新增实验开关默认 TEXT。

1. 在 `harness/attribution/FinalPromptContracts.kt` 附近定义版本化 B 输入结构及编码器；复用 `FinalPromptValidator` 和 `FinalPromptMaterializer` 的数据边界，保留 TEXT 实现。
2. 增加默认 `TEXT` 的实验配置，编译时固定每回合格式；仅在 A 草稿校验成功时选择对应定稿器。关闭、无资料、超时、解析失败及校验失败继续走现有原始消息回退，不伪装成 JSON 成功。
3. 扩展内容无关 trace，记录 format、schema version、A 结果、最终指纹、片段数、裁剪情况和耗时；Debug `BACKEND_REQUEST` 展示真正发送的 system/user，Release 不记录原文。不要把预览 `FINAL_RENDERED` 当成后端调用证据。
4. 编码或预算校验失败时记录明确原因并回退现有 TEXT 定稿；A 本身失败时仍按当前 Raw 消息回退。取消传播和既有输出保护链保持一致。

验收：同一有效草稿可确定性生成两种格式；B 的 system 角色未被 JSON 替代；默认与失败路径行为可解释且可回退。

### JSON 步骤三：功能与安全验证

- 扩展 `FinalPromptContractsTest`：JSON 可解析，字段来源正确；覆盖中文、引号、换行、反斜线、空上下文、多个片段及 ID，验证低置信度、UNKNOWN、CAUTION、REJECT 的语义与 TEXT 一致。
- 扩展 `AndroidRagOrchestratorTest`：fake A 成功时检查两种格式实际进入 `BACKEND_REQUEST`；分别覆盖 A 关闭、无参考、非法 JSON、校验失败及 JSON 定稿失败，确认 trace 和回退。
- 用含恶意指令或伪造分隔符的授权片段检查序列化与消息角色边界；保持现有角色输出保护和权限测试。运行对应 JVM 测试、Debug 构建与资源校验。
- 在目标设备检查实际聊天模板、模型加载、取消及一次完整 A→B 推理；记录设备限制与失败原因。

### JSON 步骤四：配对评测与决策

建立可复用评测集，覆盖当前角色为说话者、受话者、第三人称对象、旁观者、引用嵌套、多个角色、未知归属、无关资料及多轮代词承接；覆盖多个角色卡和不同问题，不硬编码单一角色。为样本预先定义可核对的归属与答复标准。

先冻结每个样本的授权资料和成功 A 草稿，再分别运行 TEXT/JSON；记录 A 失败率、有效配对数、B 角色归属正确率、角色泄漏/资料冒充亲历率、事实正确率、自然语言回答质量、意外输出 JSON 比例、输入 token、首字及整轮延迟、峰值内存。若未能获得真实 token 或内存数据，明确标为不可测而非以字符数冒充。

报告每个指标的分子/分母、逐样本差异及失败和排除原因；记录人工判分规则与评审一致性，适当给出不确定区间。先预注册样本量与显著提升、可接受延迟/内存门槛；在没有设备基线和产品目标时不虚设数字。样本不足只作探索性结论。若 JSON 未显示可靠收益或安全、时延明显退化，维持 TEXT 默认；只有达到预设门槛才考虑灰度启用 JSON，并保留开关和回退。

## 6. 交付与完成条件

按顺序交付评测集及基线、单轮人称修复与误报测试、角色卡映射和自然度对照、设备模型对照，以及在具备真实 A 成功样本后的 JSON 实验。每项分别列出实施文件、测试结果、数据来源、性能与已知局限。报告应区分“代码链路通过”“真实 A 成功”“B 实际收到对应格式”“人设及回答质量改善”；任何一项未验证都明确标注。真实设备不可用或真实 A 持续失败时，保留 TEXT 默认并交付可复现结果和阻塞原因，不把离线假数据测试报告为端到端成功。
