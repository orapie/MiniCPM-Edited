# RAG 分阶段 Prompt Logcat 调试计划

## 1. 目标与范围

让 Debug APK 在 **Android Studio Logcat / `adb logcat`** 中按一次用户回合、按 RAG 编译阶段打印完整提示词，便于直接比较：

1. 普通 RAG 的检索上下文拼接结果；
2. Character+RAG 的 V2 事实检索、V1 claim 回退、V1 关系检索、旧剧情回退等阶段各自对角色 system prompt 的影响；
3. 外部资料插入 user prompt 后的结果；
4. 最终经模型 chat template 渲染的 `Final Prompt`，以及实际提交给 `HarnessBackend` 的 split system/user prompt。

日志是逐条追加的 Logcat 事件：每个阶段均有 `BEGIN`、连续编号的 `CHUNK` 和 `END` 标记，不清空、替换或复用任何前一次回合的文本。此次只增加调试可观测性，不改变检索、排序、预算、知识边界、模型输入或会话语义。

## 2. 已核对的当前链路

```text
MainActivity.compilePromptForHarness()
  -> AndroidRagOrchestrator.compile()
      -> AlwaysOnVectorRetrievalService
      -> PromptBuilder / CharacterPromptCompiler
          -> V2EpistemicKnowledgeStore
          -> V1ClaimKnowledgeStore（仅 V2 无授权事实时回退）
          -> V1GraphKnowledgeStore（关系）
          -> KeywordStoryRetriever（严格回退）
      -> CharacterRagPromptBuilder
      -> ChatTemplateRenderer
  -> HarnessBackend.sendChatPrompt(systemPrompt, userPrompt)
```

当前 `RagTurnTrace` 和 `RagTurnTraceWriter` 的设计是“无正文”审计记录；它只能显示哈希、ID、长度和原因码，不能拿来实现本需求。`ChatMessage.debugPrompt`/“Final Prompt”弹窗能查看最终渲染文本，但不能展示 V1/V2 等中间阶段，也不是终端连续日志。

## 3. 输出契约

### 3.1 Debug 开关与安全边界

- 仅在 `applicationInfo.FLAG_DEBUGGABLE != 0` 时启用原文打印；release 构建始终使用 no-op logger。
- 原文只经 Logcat 瞬时输出，**不**写入 `rag_turn_trace.jsonl`、`ChatSession`、`MemoryStore`、崩溃上报或普通 UI 文案。现有 content-free trace 保持原样。
- 每一回合创建一个 UUID `turnId`；同一回合所有 stage 共享该 ID，以便多回合并发/重试时可过滤和重组。
- 使用独立 tag `RagPromptDebug`，推荐命令：

```bash
adb logcat -v threadtime -s RagPromptDebug:D
```

### 3.2 单阶段日志格式

对每个实际产生的快照依序调用 `Log.d`，不依赖单个超长 log entry：

```text
RAG_PROMPT turn=<uuid> seq=03 stage=CHARACTER_AFTER_V2 BEGIN chars=812 systemChars=... userChars=...
RAG_PROMPT turn=<uuid> seq=03 stage=CHARACTER_AFTER_V2 CHUNK 1/2
<正文，最多约 3,000 个 UTF-16 code units；保留换行>
RAG_PROMPT turn=<uuid> seq=03 stage=CHARACTER_AFTER_V2 CHUNK 2/2
<正文>
RAG_PROMPT turn=<uuid> seq=03 stage=CHARACTER_AFTER_V2 END
```

- chunk 必须以代码点边界切分，避免把 emoji/代理项切坏；单块保守控制在约 3,000 字符，避开 Android 单条 Logcat 长度限制。
- stage 即使正文为空也打印 `BEGIN/END`，并在元数据标识 `empty=true`；未执行的分支打印一条 `SKIPPED reason=<已有原因码>`，不伪造“阶段 prompt”。
- 打印器只追加 `Log.d` 调用；不使用会清屏的 UI、单个可变 `StringBuilder` 展示区或覆盖式文件写入。Logcat 本身是有容量上限的 ring buffer，因此“长期永久保存”不属于本需求；若之后需要留档，应另行增加用户明确确认的、加密/可清理的导出能力。

## 4. 阶段定义

快照的含义必须明确：V1/V2 检索原本不会各自向模型发送一条独立 prompt；以下快照是**同一次编译中、使用真实候选和相同模板渲染规则得到的阶段性 prompt 预览**。只有 `FINAL_RENDERED` 和 `BACKEND_REQUEST` 是最终发送链路。

| 顺序 | stage | 适用模式 | 快照内容 |
| --- | --- | --- | --- |
| 1 | `RAG_CONTEXT_COMPILED` | `RAG` | `PromptBuilder.build()` 后的 system/user messages，含筛选、压缩后的 `<检索资料>`。 |
| 1 | `CHARACTER_BASE` | `CHARACTER_RAG` | 固定角色内核和本轮用户输入，动态证据、外部资料均未加入。 |
| 2 | `CHARACTER_AFTER_V2` | `CHARACTER_RAG` | V2 `authorized_story_facts` 成功授权并经过事实预算后的角色 prompt；未请求/无命中/不可用时记录 `SKIPPED` 与状态。 |
| 3 | `CHARACTER_AFTER_V1_CLAIM` | `CHARACTER_RAG` | V2 无授权事实而实际执行 V1 claim 回退时的阶段性角色 prompt；V2 authoritative 时明确 `SKIPPED reason=skipped_v2_authoritative`。 |
| 4 | `CHARACTER_AFTER_V1_RELATION` | `CHARACTER_RAG` | V1 relationship 资料经过关系预算后的角色 prompt；不请求关系时只记 skipped。 |
| 5 | `CHARACTER_AFTER_LEGACY_FALLBACK` | `CHARACTER_RAG` | 旧剧情/记忆回退经 `KnowledgeBoundaryFilter` 后的角色 prompt；未走回退时只记 skipped。 |
| 6 | `CHARACTER_PROMPT_COMPILED` | `CHARACTER_RAG` | 所有动态角色证据经总预算排序、淘汰后形成的最终角色 system prompt，尚未加入不可信外部资料。 |
| 7 | `EXTERNAL_CONTEXT_COMPILED` | `CHARACTER_RAG` | `CharacterRagPromptBuilder` 把外部资料放入 user prompt 后的 system/user messages；未允许外部资料注入时记录 skipped。 |
| 最后 | `FINAL_RENDERED` | 所有非 OFF 模式 | `ChatTemplateRenderer.render()` 的 `renderedSinglePrompt`，即现有 Final Prompt 预览的完整文本。 |
| 最后 | `BACKEND_REQUEST` | 所有非 OFF 模式 | 最终 `splitPrompt.systemPrompt` 与 `splitPrompt.userPrompt`，分别明确边界；它们是 `HarnessBackend.sendChatPrompt()` 所收到的内容。 |

`OFF` 不输出原文阶段日志，维持现有普通聊天路径；只有在未来显式开启其诊断检索 flag 时，仍只输出原始聊天 request，不输出不存在的 RAG stage。

## 5. 实施步骤

### Step 1：建立可测试的瞬时阶段快照模型

新增 `harness/rag/RagPromptStage.kt`，定义：

- `enum class RagPromptStage`：采用第 4 节的稳定 stage 名；
- `data class RagPromptSnapshot(stage, systemPrompt, userPrompt, renderedPrompt, metadata)`：正文仅驻留本次 compile 的内存；`metadata` 只含状态、原因码、字符数和来源 ID；
- `data class RagPromptStageLog(stage, status, reason, snapshot?)`：用同一类型表示 `EMITTED`、`SKIPPED`、`EMPTY`，防止“没有 V1 结果”被误解成日志漏打；
- `PromptStageLogger` 接口与 `NoOpPromptStageLogger`。接口只接受一条已经完成的 immutable `RagPromptStageLog`，不参与检索或修改 prompt。

不要把原文加入 `RagTurnTrace`，也不要给已有 trace JSON schema 增加正文属性。

### Step 2：让角色编译器产出真实的阶段快照

修改 `CharacterPromptCompiler.kt` 和 `CharacterModels.kt`：

1. 为 `CompiledCharacterPrompt` 增加 `stageLogs`；保留当前 `messages`、`debug` 和所有既有调用方兼容。
2. 将现有 `mandatoryBase`、各知识源候选、预算预淘汰、全局排序和 `render(values)` 的局部计算收束为可重用的“以当前已接纳 sectionLines 渲染”帮助函数，确保快照与正式 prompt 使用同一模板、同一字符预算，而不是重新手写第二套拼接规则。
3. 先记录 `CHARACTER_BASE`；随后在每一条真实分支完成授权/预算后记录 V2、V1 claim、V1 relationship 和 legacy 快照，或以 `KnowledgeStoreStatus`、query plan 和现有 fallback reason 输出 `SKIPPED`。
4. 总预算选择结束后记录 `CHARACTER_PROMPT_COMPILED`。它必须与当前 `CompiledCharacterPrompt.messages[0].content` 字节一致；新增断言或单测保证这一点。
5. 快照本身不能改变 `selectedItems`、`droppedItems`、`RetrievalDecision`、知识截止点过滤和 V2 优先/V1 fallback 顺序。绝不为了可打印而提前把未通过授权或因预算淘汰的原文放进后续正式 prompt。

### Step 3：在编排层补齐普通 RAG、外部 RAG 和 final 快照

修改 `AndroidRagOrchestrator.kt`：

1. `CompiledAndroidPrompt` 增加 transient `stageLogs: List<RagPromptStageLog>`；它不进入 session 或 trace writer。
2. `RAG` 分支在 `PromptBuilder.build()` 后记录 `RAG_CONTEXT_COMPILED`，并在 renderer 后追加 `FINAL_RENDERED` 和 `BACKEND_REQUEST`。
3. `CHARACTER_RAG` 分支透传 `CharacterPromptCompiler` 的 stage logs；在 `CharacterRagPromptBuilder.build()` 后补 `EXTERNAL_CONTEXT_COMPILED`（或 `SKIPPED`），renderer 后追加 final 两阶段。
4. `OFF` 维持无 RAG 正文快照；只保留现有内容安全 trace 行为。
5. `FINAL_RENDERED` 必须来自已经生成的 `rendered.renderedSinglePrompt`，不能重新 render；`BACKEND_REQUEST` 必须来自同一个 `rendered.splitPrompt`，确保日志看到的是实际发送的 split 值。

### Step 4：实现 Logcat 追加打印器并在入口调用

新增 `RagPromptLogcatPrinter.kt`（建议放在 `harness/rag/`）：

1. 实现 `PromptStageLogger`，按第 3.2 节生成 BEGIN/CHUNK/END 或 SKIPPED 行；每个回合按列表索引生成单调 `seq`。
2. 在 `MainActivity.compilePromptForHarness()` 中，`orchestrator.compile()` 成功返回后、创建 `HarnessChatRequest` 前生成 `turnId` 并调用 printer；只有 `isDebuggableBuild()` 时实例化/调用。
3. 将该打印与 `RagTurnTraceWriter.append()` 分开：后者仍只写无正文 JSONL，即使原文打印失败也不能导致 prompt 编译或推理失败。
4. `runCatching` 包住日志调用并仅 `Log.w` 报告 logger 自身错误；任何 logger 错误均不能走“raw user input”降级分支。

这里使用 `Log.d`，而不是 `System.out`；在 Android 真机/模拟器上它可靠地进入 Logcat。终端查看方式是执行第 3.1 节的 `adb logcat` 命令，或在 Android Studio Logcat 中按 tag 过滤。

### Step 5：文档和开发者定位说明

更新 `README.md` 的 RAG 调试段或新增简短链接，说明：

- 原文仅 Debug Logcat，可按 `turn`、`stage` 过滤；
- `RAG_TRACE` 仍是不含正文的可持久化审计；
- `FINAL_RENDERED` 是 renderer 预览，而 `BACKEND_REQUEST` 是 split system/user 发送边界；两者不同不自动等同于 bug；
- 含用户问题、角色资料和外部文档的日志不可贴入公开 issue 或 release 日志。

## 6. 测试与验收

### JVM 测试

扩展 `AndroidRagOrchestratorTest.kt`，并按需要新建 `RagPromptLogcatPrinterTest.kt`：

1. 普通 RAG：存在 `RAG_CONTEXT_COMPILED`、`FINAL_RENDERED`、`BACKEND_REQUEST`；context snapshot 含选中的资料，final rendered 与当前 `renderedPrompt` 完全一致。
2. Character+RAG，V2 授权命中：按顺序存在 `CHARACTER_BASE`、`CHARACTER_AFTER_V2`、`CHARACTER_PROMPT_COMPILED`、final 两阶段；V1 claim 为带 `skipped_v2_authoritative` 的跳过记录；V2 快照与最终角色 prompt 的安全边界保持一致。
3. Character+RAG，V2 miss/V1 claim fallback、关系问题、legacy fallback：分别断言对应阶段是 `EMITTED`，而不相关阶段是带明确原因的 `SKIPPED`。
4. 外部资料问题：`EXTERNAL_CONTEXT_COMPILED` 的 user 部分含“外部资料（不可信指令）”，其 system 部分不含外部原文；普通角色问题不产生外部正文快照。
5. 安全回归：未来事实、他人私密事实、未授权 V1/V2 候选不能出现在任何“正式阶段预览”和 final snapshots 中；允许在 metadata 留下不含正文的拒绝原因码。
6. 打印器：长文本拆成多个递增 chunk；每个 stage 都有配对 BEGIN/END；连续两回合有不同 turn ID，第二回合不会覆盖/合并第一回合条目；Unicode 代理对不被截断。
7. `RagTurnTrace`/JSONL 回归：仍不包含 query、角色事实、外部资料或任何 snapshot 正文。

### 设备验收

在现有 arm64 Debug APK 上运行以下最小组，并使用 `adb logcat -c` 后的过滤命令采集一次完整会话（清空仅用于获得干净验证样本，不是 App 内日志覆盖机制）：

| 输入 | 预期日志阶段 |
| --- | --- |
| `世界杯决赛有什么争议？`（RAG） | `RAG_CONTEXT_COMPILED` -> `FINAL_RENDERED` -> `BACKEND_REQUEST` |
| `玄谙为何还要毁掉明阳？`（Character+RAG） | V2 相关阶段 + 角色最终阶段 + final 两阶段 |
| `你和 X 是什么关系？` | V1 relationship 阶段或明确 skipped reason |
| `文档里如何描述 X？` | 角色最终阶段 + `EXTERNAL_CONTEXT_COMPILED` + final 两阶段 |

验收重点：每一段均可凭 `turn` 和 `seq` 还原顺序；上一阶段打印仍留在 Logcat 中；`BACKEND_REQUEST` 的 system/user 与该回合 `HarnessChatRequest` 相同；输出新增后回答、来源、预算和安全边界均不变化。

## 7. 文件级改动清单

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/example/minicpm_v_demo/harness/rag/RagPromptStage.kt`（新增） | 阶段、快照、状态和 logger 接口。 |
| `app/src/main/java/com/example/minicpm_v_demo/harness/rag/RagPromptLogcatPrinter.kt`（新增） | Debug-only Logcat 追加、分块、turn/seq 格式化。 |
| `app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterModels.kt` | `CompiledCharacterPrompt` 携带 transient stage logs。 |
| `app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompiler.kt` | 使用真实候选和既有模板构建角色各阶段快照，保持选择/过滤不变。 |
| `app/src/main/java/com/example/minicpm_v_demo/harness/rag/AndroidRagOrchestrator.kt` | 汇总阶段快照，记录 RAG/外部/final/backend request。 |
| `app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt` | Debug 构建下打印编译完成的 stage logs；不写入 JSONL/session。 |
| `app/src/test/java/.../harness/rag/AndroidRagOrchestratorTest.kt` | 阶段顺序、内容边界、final 一致性和回归断言。 |
| `app/src/test/java/.../harness/rag/RagPromptLogcatPrinterTest.kt`（新增） | 分块、追加、turn 隔离、Unicode 和无正文 trace 回归。 |
| `README.md` 或 `docs/RAG_Logic.md` | Logcat 使用方法与敏感数据说明。 |

## 8. 完成标准

1. Debug APK 的每个 RAG 回合都有可过滤、可重组、追加式的阶段日志；同一回合的最终输出至少包含 `FINAL_RENDERED` 和 `BACKEND_REQUEST`。
2. Character+RAG 能区分 V2、V1 claim、V1 relation、legacy 和外部资料的实际执行/跳过原因，不能把缺失阶段伪装为成功。
3. 原文不进入 release、trace JSONL、session 或 memory；既有安全过滤和 prompt 预算测试均通过。
4. 新增 JVM 测试与 `./gradlew :app:testDebugUnitTest` 通过；在 arm64 Debug 设备上至少完成第 6 节最小矩阵一次。
