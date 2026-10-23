# Character RAG 查询感知上下文：当前项目增量实施方案

## 1. 目的与范围

本方案面向当前 `char_sys` 工作树，不重建已经存在的图谱、角色卡或 prompt 编译链。目标是让同一角色面对不同问题时，在保留稳定角色核的同时，只向 system prompt 补入与问题直接有关的角色资料、当前状态、物品、关系或剧情事实。

期望结构：

```text
稳定角色核（身份、语气、通用输出规则）
+ relevant_profile（已实现：来历、动机、立场、性格、行为准则）
+ relevant_state（已实现：按问题选取当前场景、目标或任务状态）
+ relevant_possessions（已实现：仅当前有效 state 中的 `carried` 项）
+ relevant_relationships（已实现：当前角色发出的 V1 关系边）
+ authorized_story_facts（已实现：V2 / V1 claim / legacy 回退）
+ conversation_summary（已有：只用于承接）

外部资料（仅用户明确请求时，继续位于 user message）
```

这里的“查询感知”不要求每轮 system prompt 都完全不同。问候、纯身份确认等问题可以只使用稳定角色核；但问来历、动机、当前处境、物品、关系或剧情时，对应动态区块必须可见、可审计且不越过角色知识边界。

## 2. 当前项目事实基线（2026-10-16）

### 2.1 已有真实调用链

```text
MainActivity.compilePromptForHarness()
  -> AndroidRagOrchestrator.compile(CHARACTER_RAG)
    -> CharacterPromptCompiler.buildNpcPrompt()
      -> planKnowledgeQuery()                 // 当前为编译器私有的确定性规则
      -> profileCandidates()                  // 当前角色卡 profile 候选
      -> V2EpistemicKnowledgeStore            // 首选剧情认知事实
      -> V1ClaimKnowledgeStore                // 仅当 V2 无授权命中/不可用时的剧情兼容回退
      -> V1GraphKnowledgeStore                // 当前 NPC 发出的关系边
      -> KeywordStoryRetriever                // V1/V2 均无事实时的旧回退
      -> roleplay_system.prompt               // 渲染 system
    -> CharacterRagPromptBuilder.build()      // 外部 RAG 仅放入 user
```

`MainActivity` 已把分离后的 `systemPrompt` 与 `userPrompt` 送入 `HarnessChatRequest`；不应绕开该链路另建 prompt。

### 2.2 已实现，后续不得重复建设

| 能力 | 当前实现 | 保留原则 |
| --- | --- | --- |
| 查询相关角色资料 | `CharacterPromptCompiler.profileCandidates()` 从当前角色 JSON 的 `background`、`coreMotivations`、`values`、`psychologicalTraits`、`behaviorRules` 选取 | 仅当前角色卡；不放宽 V2 profile claim 过滤 |
| 稳定角色边界 | `compileRoleConstraints()` + `{{role_constraints}}` | 每轮保留角色身份/知识边界的短强化；通用反编造、反泄露、反剧透规则仍由模板末尾稳定承担 |
| profile prompt 区 | `roleplay_system.prompt` 的 `{{relevant_profile}}` | 资料只在 system；默认最多 2 条、180 字符 |
| V2 剧情事实 | `V2EpistemicKnowledgeStore` | 按当前角色、cutoff、审核状态和角色认知边硬过滤 |
| V1 claim 兼容 | `V1ClaimKnowledgeStore` | 只接受 `claim_type=story_event`；只能作为 V2 无命中/不可用时回退，不能与 V2 并行重复注入 |
| V1 关系 | `V1GraphKnowledgeStore` | 仅接受由当前 NPC 指向目标的已审核关系，禁止反向推断 |
| 未来事实保护 | `RoleplayAnswerabilityGate` | 仅在“词法命中但 cutoff 拒绝且无授权事实”时阻断模型生成；普通未知问题不得被误拦 |
| 端侧最低验证 | `CharacterGraphNativeInferenceTest`、`CharacterProfileNativeInferenceTest`、`CharacterRoleplayRegressionTest` | 现有设备证据是功能/安全回归，不可外推为全面性能结论 |

已有 profile 方案不需要新增 `CharacterProfileStore`、`CharacterContextNeedRouter` 或通用 `PromptBudgetAllocator` 才能工作。当前角色卡规模很小，先在现有 `CharacterPromptCompiler` 内做最小增量更贴合项目，也避免并行两套选取逻辑。

### 2.3 仍未覆盖的真实问题

| 用户问题 | 当前行为 | 缺口 |
| --- | --- | --- |
| “你从哪里来？” | 已可从 `relevant_profile` 注入来历 | 需继续扩大问法测试和 UI 回归，但不是待建能力 |
| “你为何这样做/你在乎什么？” | 已可按 profile 类别信号选择动机/原则 | profile 选择没有独立 audit reason，需要补可观测性 |
| “你现在在哪里/在做什么？” | 已从 `DynamicState` 按问题选取 `scene`、`questState` 或 `currentGoal`，情绪仍稳定保留 | 尚需真实 UI 回归验证 prompt 查看页可见变化 |
| “你手里有什么？” | 仅从当前有效 state 的 `carried` 项注入；无记录时明确未记录 | 仍需逐角色补充有原文证据的 state 资产与 UI 回归 |
| “那把剑从哪里来？” | 可能命中剧情，也可能无命中 | 需要把“物品身份/来源”和“当前是否持有”分开建模 |
| 代词续问“他为何这样做？” | 主要依赖 conversation summary 与模型理解 | 尚无基于前一轮已选实体/事实的确定性查询改写或消歧 |

## 3. 必须保持的边界

1. `relevant_profile`、`relevant_state`、`relevant_possessions`、关系和内部剧情事实都是角色可信上下文，只能进入 system。
2. `CharacterRagPromptBuilder` 中的外部资料继续在 user，保留“不可信指令”标识；不得成为角色记忆、档案、物品或状态。
3. V2 `claim_scope=profile` 和 V1 `character_observation` 仍不能直接混入 `authorized_story_facts`。当前角色卡 profile 是单独、受限的角色自身资料面。
4. `hardConstraints` 属于角色边界，不能因为普通 profile 排序或总预算被静默丢掉。后续需要把它从普通 `relevant_profile` 候选中拆出，放入稳定约束区。
5. “当前持有”必须来自带有效区间的结构化状态，不得由“历史上使用/看见过某物”推断。
6. 无数据必须保留“资料未记录/尚不清楚”的语义，不得为让 prompt 变化而制造物品或经历。
7. 任一动态项被 cutoff、审核、预算或路由拒绝时，debug 必须能说明原因；release 日志不记录事实正文或玩家原文。

## 4. 目标的最小数据模型

### 4.1 复用现有角色卡的 profile

本部分已实现，后续只做收敛与审计，不迁移至 SQLite：

| 原字段 | system 语义 | 是否时序化 |
| --- | --- | --- |
| `identity_core.background` | 来历、经历、身份来源 | 当前按稳定资料处理 |
| `core_motivations` | 当前/长期动机 | 当前按稳定资料处理；可能被状态覆盖 |
| `values` | 立场与原则 | 稳定资料 |
| `psychological_traits` | 性情与习惯 | 稳定资料，不能当作具体事件 |
| `behavior_rules` | 行事倾向 | 稳定资料 |
| `hard_constraints` | 不可逾越的角色边界 | 下一阶段改入稳定约束区 |

### 4.2 新增状态/物品资产

不要修改现有 `CharacterCard` JSON 的事实字段来临时塞入“物品”。新增一个与既有 assets 并列的运行时资产目录：

```text
app/src/main/assets/harness/characters/
  characters/                 # 既有角色卡
  state/                      # 新增：每角色的可变状态
    lu_jiangxian.json
    ...
  schemas/
    character_state_schema.json
```

建议的最小 schema：

```json
{
  "schema_version": "1.0",
  "character_id": "lu_jiangxian",
  "states": [
    {
      "state_id": "lu-evt-018",
      "valid_from": "evt-018",
      "valid_to": null,
      "scene": "……",
      "quest_state": "……",
      "current_goal": "……",
      "evidence_refs": ["1458.txt:L82-L101"],
      "possessions": [
        {
          "item_id": "mingyang-white-jade-sword",
          "name": "命阳白玉剑",
          "status": "carried",
          "description": "仅描述当前可确认的持有/可用状态",
          "keywords": ["剑", "手里", "带着", "携带", "法器"],
          "evidence_refs": ["145x.txt:Lx-Ly"]
        }
      ]
    }
  ]
}
```

约束：

- `valid_from` 必填；`valid_to=null` 表示直到被后续 state 覆盖。
- 同一角色的有效 state 不得在同一 cutoff 区间产生矛盾的当前持有结论。
- `status` 至少区分 `carried`、`not_carried`、`unknown`。仅 `carried` 可回答“手里/随身有什么”；`unknown` 只支持保守回答。
- `evidence_refs` 供构建期人工核验，不进入 prompt 或 release 日志。
- state 为空是合法情况；它代表“无当前状态资料”，不是加载失败。

`AssetBootstrapper` 的常规 asset 复制已经会复制 `characters/state/`；只有 knowledge bundle 才有单独哈希安装。新增 state 资产时重点是给解析器/校验器加 fail-closed 校验，而不是再复制一套 knowledge-bundle 安装框架。

## 5. 落点与职责

| 文件/位置 | 已有职责 | 下一步改动 |
| --- | --- | --- |
| `harness/character/CharacterModels.kt` | `RuntimeContext`、`CharacterPromptDebug`、`DynamicState` | 增加 state/possession 的预算和无正文调试字段；避免把物品直接塞入 `DynamicState` 而无有效期 |
| `harness/character/CharacterJsonLoader.kt` | 加载稳定角色卡 | 保持角色卡解析；新增相邻 `CharacterStateLoader`，不要让它承担 V1/V2 图谱读取 |
| `harness/character/CharacterPromptCompiler.kt` | 唯一内部 prompt 注入点 | 在现有 `planKnowledgeQuery()`、候选排序和 `sectionLines` 上增量加入 `relevant_state`、`relevant_possessions`；不另起平行 composer |
| `assets/.../prompts/roleplay_system.prompt` | system 模板 | 增加两个明确的占位符和“未记录”语义；保持外部资料不在此模板 |
| `harness/data/HarnessDataPaths.kt` | 运行时路径 | 新增 `characterStateDir`/文件路径辅助属性 |
| `harness/data/AssetBootstrapper.kt` | assets 安装 | 对新增 schema 实施启动前/加载前校验；不可破坏 V1/V2 原子安装 |
| `harness/character/knowledge/*` | V1/V2 事件与关系图谱 | 不为 state/possession 放宽现有 V1/V2 过滤 |
| `MainActivity.kt` | 编译、发送、无正文 Logcat | 扩展日志的类别状态/注入字符；不能记录 state 或物品正文 |

## 6. 分阶段实施

### Phase 7A-R：收敛已落地的 profile 实现（已完成）

已实现内容：

1. `hardConstraints` 已从 `profileCandidates()` 移出；模板新增每轮必定渲染的 `{{role_constraints}}`。为不挤掉 900 字符预算内的当前证据，该区只保留角色身份/知识边界的短强化；模板末尾继续稳定承载通用反编造、反泄露、反剧透和反元问题规则。
2. `relevant_profile` 现在只负责可回答的背景、动机、价值、性情与行为准则。
3. `CharacterPromptDebug` 已记录 `profileRoute`、候选数量和 ID、选中 ID、预预算淘汰数量及注入字符数；这些字段不包含资料正文或玩家输入。
4. `MainActivity` 的无正文 Logcat 输出 profile 路由、候选数、选中数、预算淘汰数与注入字符数，不再把 `knowledgeQueryIntent` 误当作完整 profile 路由。
5. `CharacterPromptCompilerTest` 覆盖来历的显式 profile 路由、问候跳过 profile 但仍渲染稳定边界、以及 profile 字符预算淘汰。

预算结果：初版直接固定两条原始 `hardConstraints` 会挤掉 900 字符 prompt 中的 V2 事实；最终保留短稳定边界，并将语气示例从 4 条收为 3 条，为当前轮的 profile/剧情证据腾出空间。定向 JVM 回归已验证 profile、V2、V1/legacy 回退和编排器路径均仍通过。

### Phase 7B：先用已有 `DynamicState` 做查询感知状态（已完成）

目的：不等新物品数据，先使已有 `scene`、`questState`、`currentGoal` 服务于“此刻/正在做什么/打算如何”的问题。

已实现内容：

1. `RuntimeContext` 新增 `stateTopK=1`、`stateMaxChars=96`，并校验均不可为负数；总 system 上限仍由既有 `maxChars`（Android 默认 900）硬限制。
2. `planKnowledgeQuery()` 对“此刻/现在/在哪里/正在/打算/下一步/目标/任务”等状态信号启用 `retrieveState`，并让该路径不再同时引入无关的 profile 或剧情事实。
3. `stateCandidates()` 仅从当前角色已有的 `DynamicState.scene`、`questState`、`currentGoal` 选取最多一项；情绪保留在稳定的 `dynamic_state` 摘要，场景不再无条件重复。
4. 模板已增加 `{{relevant_state}}`；未命中时安全地渲染为“无”。
5. `CharacterPromptDebug` 与 `MainActivity` 的无正文 Logcat 已记录 state 路由、候选数/ID、选中数/ID、预预算淘汰数和注入字符数；选项仍复用现有 `sectionLines` 与预算选择流程。
6. `CharacterPromptCompilerTest` 已覆盖场景、下一步目标、来历问题不注入状态、状态预算淘汰以及玩家原始问题保持为 user message。

为给动态状态保留 900 字符内的空间，语气示例从 3 条缩为 2 条；角色身份边界、情绪摘要和模板末尾的通用安全规则仍稳定保留。

验收：

- “你现在在哪里？”注入场景；“你接下来打算做什么？”注入目标/任务。
- “你来自哪里？”不因 state 路由而额外填入任务状态。
- 总 system 字符数仍不超过 `maxCharacterChars=900`；预算无法容纳时有无正文理由。
- `CHARACTER_RAG` 的 user message 仍严格等于玩家原始问题（无外部 RAG 时）。

### Phase 7C：结构化当前物品/状态资产（已完成）

目的：解决“手里有什么”不能从历史事实猜测的问题。

已实现内容：

1. 新增 `characters/state/`、`character_state_schema.json`、`CharacterStateLoader` 和独立数据类；首个角色资产只记录由原文明确支持的“未随身携带”结论，不因历史出现过仙器而杜撰当前持有。
2. loader 按既有事件时间序校验 `valid_from`/`valid_to`，拒绝未知 cutoff、重复 state ID、无效/重叠区间、角色 ID 不匹配和同 state 的重复物品 ID。
3. 编译器只读取当前 cutoff 命中的 state，且仅 `carried` 项可进入 `relevant_possessions`；`not_carried` 与 `unknown` 永不进入该区块。
4. 物品来源仍由既有 V2/V1/legacy 剧情路径处理；当前持有只由 state asset 处理，两条路径不互相推断。
5. 模板已增加 `{{relevant_possessions}}`。用户明确询问随身物时，如无有效 `carried` 记录，system 明示“当前资料未记录可确认的随身物品”。
6. runtime/debug 记录 possession 路由、候选数、选中数、预算淘汰和注入字符数，不记录物品正文或内部 ID。

验收：

- `valid_from` 前查询“手里有什么”不会注入未来物品。
- 当前有效区间内查询“手里有什么”只注入 `carried` 项。
- 曾出现但 `not_carried` 的物品不会被说成当前手持。
- 无记录时最终 prompt 明确“未记录”，不会注入任意历史剑/法器。

### Phase 7D：可观测性和真实 UI 验收（工程已完成；真机执行待完成）

目的：证明用户在 App 的“final prompt”视图中能看到按问题变化的动态区块，而非只通过 JVM 断言。

已实现的工程部分：

1. `MainActivity.compilePromptForHarness()` 的无正文日志现已包含：
   - `profileRoute/status/chars`；
   - `stateRoute/status/chars`；
   - `possessionRoute/status/chars`；
   - 预算淘汰数量；
   - V2、V1 claim、V1 relation、legacy 状态。
2. 新增/扩展 Android instrumentation：
   - 资产从真实 `filesDir/harness` 加载 state asset；
   - cutoff 前后物品差异；
   - query-aware state/profile 进入 system；
   - 外部资料仍只在 user；
   - future guard 对 state/possession 路径不误触发。
3. `CharacterProfileNativeInferenceTest` 已增加固定状态题和物品未记录题；每题在推理前清空 native context，并使用固定模型、角色、cutoff 和 `predictLength`。

仍需在真实设备完成的验收：

1. Debug APK 使用已有 prompt 查看入口，逐题人工比较 system prompt；release 不显示内部 ID、证据、完整资料或玩家问题。
2. 执行上述 instrumentation/native 用例并记录实际可见回答、system/user token、prefill、TTFT 和完整生成时间；JVM 断言与 instrumentation 源码编译不能替代这一步。

建议真机矩阵：

| 类别 | 固定问题 | final system prompt 的预期变化 |
| --- | --- | --- |
| profile-来历 | “你从何而来？” | `relevant_profile` 为 background |
| profile-动机 | “你为何要查元府？” | `relevant_profile` 为 motivation/values |
| state-场景 | “你现在在哪里？” | `relevant_state` 为 scene |
| state-目标 | “你接下来打算做什么？” | `relevant_state` 为 current goal/quest |
| possession | “你手里有什么？” | `relevant_possessions` 为当前 `carried` 项或未记录 |
| relationship | “你怎么看玄谙？” | `relevant_relationships` |
| story | “你为什么停止拼合七枚鉴身碎片？” | `authorized_story_facts` |
| greeting | “晚安。” | 不请求 profile/state/possession |
| mixed | “你从何而来，又带着什么？” | profile + possession，均在预算内 |

每题至少记录功能结果、system/user token 数、system prefill、TTFT、完整生成时间和最终可见回答。不同问题的上下文不相同，这些只能用于功能与成本观察，不能构成性能 A/B。

### Phase 7E：续问消歧（已完成）

当前 `conversationSummary` 仍只帮助模型理解代词；确定性消歧改为保存上一轮**已选的无正文 ID 和类别**，不保存或重放原始 RAG 文本。

已实现内容：

1. `ChatTurn` 只持久化允许类别的 opaque target（授权剧情事实、关系、当前 `carried` 物品），并保持旧会话兼容。
2. `CharacterFollowUpResolver` 仅接受紧邻的同角色、同 cutoff、无外部资料轮次中的单一 target；问题必须含明确代词且不得重新点名已知角色，否则回到常规检索。
3. V2/V1 claim、V1 关系与 state possession 在注入前重新检查当前角色、cutoff、审核/认知边界与当前有效期；续问 ID 不是绕过授权的后门。
4. JVM 回归覆盖已授权 V2 续问、较早 cutoff 拒绝、物品续问、会话持久化、角色切换和显式实体回退。

## 7. 必需测试

### JVM

1. `CharacterPromptCompilerTest`
   - profile 类别选择、稳定 hard constraint、state 路由、state/possession 预算、混合问题。
   - 任意 `maxChars=900` 下没有未解析模板标记。
   - profile/state/possession 只在 system，普通 user 问题保持原文。
2. `CharacterStateLoaderTest`
   - schema、角色 ID、cutoff、重复 ID、重叠区间、未知 status、无物品记录。
3. `CharacterPromptCompilerTest`
   - cutoff 前后、`carried/not_carried/unknown`、场景/目标问题、无状态回退。
4. 既有 V2/V1 回归
   - V2 命中时 V1 claim 为 `skipped_v2_authoritative`；V1 claim 只在 V2 无授权事实时使用；未来事实仍被拒绝；当前 NPC 关系边不反向注入。
5. `RoleplayAnswerabilityGateTest`
   - state/possession 无记录和普通 profile miss 绝不触发 future block。

### Android / 真机

1. `CharacterGraphRuntimeAcceptanceTest` 扩展为从应用私有运行目录验证 profile/state/possession 的 prompt 注入与 cutoff。
2. 保留 `CharacterProfileNativeInferenceTest`，新增状态/物品原生用例；不使用 `connectedDebugAndroidTest` 覆盖安装而删除私有模型文件。
3. 保留 `CharacterRoleplayRegressionTest`，向矩阵增加状态题、物品未知题和混合题；外部 injection、未来剧透与续问仍必须回归。

## 8. 不应实施的捷径

- 不删除 V2 的 `PROFILE_CLAIM` / `UNSUPPORTED_BELIEF_STATUS` 过滤来“复用所有 profile”；这会把角色约束和剧情事实混为一类。
- 不从 `story_events.jsonl` 或 V1/V2 历史事实自动推断当前手持物品。
- 不为当前 6 个角色先引入大型通用检索框架或模型驱动 Router；当前私有 `planKnowledgeQuery()` 足以承载小步扩展。
- 不让 `conversationSummary` 或外部 RAG 文本直接升级为角色自身事实。
- 不把 system prompt 每轮变化本身当成成功指标；必须同时检查注入事实是否相关、合法、受 cutoff 约束且没有泄露控制文本。

## 9. 推荐执行顺序与完成标准

推荐顺序：**Phase 7A-R → 7B → JVM 回归 → 7C → instrumentation → 真机 UI 验收 → 7E（可选）**。

本方案完成的最低标准不是“新增更多 prompt 文本”，而是：

1. 来历/动机和当前状态问题会选择不同的、可解释的 system 动态区块；
2. “手里有什么”仅由带 cutoff 的当前状态资产回答，缺数据时保守处理；
3. V2/V1 的时序、认知、审核、关系方向和 future gate 不回退；
4. 外部资料始终留在 user；
5. JVM、APK 构建和至少一组真实设备 final-prompt/生成回归均通过；
6. 报告 token、prefill、TTFT 时明确样本配置，不把不同 prompt 的单次结果称为性能对比。
