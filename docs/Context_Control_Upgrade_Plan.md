# MiniCPM Android 上下文控制改造方案

本文根据 `docs/papers` 中的论文，以及当前 Android/Kotlin/JNI 实现，制定面向端侧低延迟的上下文控制改造路线。目标不是追求最大的上下文窗口，而是在不损害角色一致性、剧情边界和 RAG 事实性的前提下，减少每轮需要预填充的 token，并让上下文预算可观测、可解释、可回滚。

## 1. 结论

推荐采用以下顺序：

1. **P0：Prompt-side Context Controller**
   借鉴 LongLLMLingua 的查询感知排序、预算控制和位置重排，结合 LLMLingua-2 的抽取式压缩思想。只改 Kotlin prompt/RAG 层，风险最低，最适合当前工程。
2. **P1：分层会话记忆**
   借鉴 Mem0 的长期记忆管理，将稳定角色信息、会话状态、近期对话和外部资料分开管理，避免扩大完整历史。
3. **P2：稳定前缀复用**
   在设备实测确认 system prompt prefill 是主要瓶颈后，再改 Harness/JNI，使稳定角色前缀可以在同一会话内复用。
4. **P3：KV cache 压缩或稀疏 attention**
   SnapKV、CacheBlend、Squeezed Attention 只作为后续 native 研究方向。当前 context 规模和 llama.cpp 接口不足以证明它们值得承担实现风险。

核心判断是：先做“可解释的上下文选择器”，再做“模型内部 KV 优化”。当前 4096/8192 的 native context 和较短的角色/RAG输入，更可能从减少无效 prefill 获益，而不是从复杂 GPU kernel 获益。

## 2. 证据与适配度排序

评分含义：可信性考虑发表 venue、是否有完整实验和是否容易复核；适配度考虑当前 Android CPU、llama.cpp/mtmd JNI、现有 RAG 结构和输入规模。

| 方案 | 主要证据 | 可信性 | 当前适配度 | 采用结论 |
|---|---|---:|---:|---|
| [LongLLMLingua](./papers/longllmlingua-2024.md) | ACL 2024，查询感知压缩、预算控制、位置重排 | 高 | 高 | P0 设计基线 |
| [LLMLingua-2](./papers/llmlingua-2-2024.md) | Findings ACL 2024，抽取式压缩和小型编码器 | 高 | 中高 | P0 先做规则版，模型版后置 |
| [Mem0](./papers/mem0-2025.md) | 2025 预印本，长期记忆提取、合并、检索 | 中 | 高 | P1 借鉴架构，不直接复制实现 |
| [Squeezed Attention](./papers/squeezed-attention-2025.md) | ACL 2025，固定上下文稀疏 attention | 高 | 中低 | P3，只借鉴离线路由 |
| [CacheBlend](./papers/cacheblend-2024.md) | EuroSys 2025，RAG 非前缀 KV 重用 | 高 | 低 | P2/P3，先做稳定前缀简化版 |
| [SnapKV](./papers/snapkv-2024.md) | 2024 预印本，query-aware KV 压缩 | 中 | 低 | 暂不实现 |

论文中的加速数字主要来自长上下文、GPU 或服务端场景，不能直接当作本项目的性能承诺。所有收益必须以目标 Android 设备上的首 token 延迟和 prefill 日志为准。

## 3. 当前实现约束

### 3.1 Prompt 层

- 角色 prompt 在 [CharacterPromptCompiler.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompiler.kt) 中按字符数限制，必选内容和可选候选已经分开。
- 角色模式入口在 [AndroidRagOrchestrator.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/rag/AndroidRagOrchestrator.kt)，默认 `maxCharacterChars` 为 900。
- 外部资料由 [CharacterRagPromptBuilder.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/rag/CharacterRagPromptBuilder.kt) 拼入 user prompt，当前上下文上限为 600 字符。
- 当前摘要由 [ConversationSummaryBuilder.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/session/ConversationSummaryBuilder.kt) 生成，但角色编译器再次将摘要限制为 100 字符，存在信息损失。

### 3.2 Runtime/JNI 层

- [HarnessBackend.kt](../app/src/main/java/com/example/minicpm_v_demo/harness/HarnessBackend.kt) 在带 system prompt 的请求中先 `clearContext()`，再重新处理 system prompt。
- [llama_jni.cpp](../app/src/main/cpp/llama_jni.cpp) 默认 context 为 4096，MiniCPM-V 4.6 为 8192，当前 native 路径没有公开按 head/token 操作 KV cache 的接口。
- native user prompt 超长时会截断 token，但随后仍使用截断前的 token 数推进 `current_position`，这一问题必须在 P0 之前修复。

## 4. P0：Prompt-side Context Controller

### 4.1 目标

在不调用额外压缩模型的前提下，实现查询感知的选择、抽取、预算和重排。第一版不改变角色身份、知识边界和外部资料的信任边界。

### 4.2 建议新增的数据结构

建议新增 `app/src/main/java/com/example/minicpm_v_demo/harness/context/`：

- `ContextItem`：上下文项，包含 `section`、`id`、`text`、`source`、`priority`、`protected`。
- `ContextBudget`：包含总 token 预算、system 预算、user 预算、回答预留、图像预留和安全余量。
- `ContextDecision`：记录 selected、dropped、compressed、reason、estimatedTokens。
- `ContextController`：负责评分、预算分配、句子级抽取和最终重排。

`CharacterPromptDebug` 不应被替换，而应扩展以记录 token 预算和决策。这样既保留现有测试契约，也能解释“为什么某个剧情事实没有进 prompt”。

### 4.3 处理流程

```text
用户问题
  -> 查询规范化和实体/时间/关系提取
  -> 角色边界过滤
  -> 候选项评分
  -> 固定项预算分配
  -> 可选项句子级抽取
  -> query-aware 重排
  -> token 预算校验
  -> system/user prompt
  -> native tokenizer 和推理
```

评分可以从现有 priority 扩展为：

```text
score = retrievalScore
      + importance
      + queryEntityOverlap
      + relationshipMatch
      + definitionIntentBoost
      + recencyBoost
      - lengthPenalty
```

角色身份、知识 cutoff、边界规则和当前动态状态为 protected 项，不参与普通丢弃。关系、授权剧情事实、检索记忆和外部资料按预算竞争。

### 4.4 预算策略

不要继续只用字符数作最终限制。推荐由 native tokenizer 提供一个只读 `countTokens(text, modelState)` JNI 方法，Kotlin 层先用字符数估算筛选，最后用 native token 数校验：

```text
usableInput = n_ctx
             - predictLength
             - imageTokenReserve
             - templateOverhead
             - overflowHeadroom
```

推荐初始预算顺序：

1. protected system：身份、边界、当前状态。
2. 当前问题和必要的对话状态。
3. 与当前 query 最相关的剧情/关系/记忆。
4. 外部 RAG 资料。
5. 低优先级示例、重复说明和旧摘要。

### 4.5 抽取和重排原则

- 以句子、短语或结构化字段为最小单位，不做任意字符截断。
- 中文人名、时间、否定词、关系类型和来源标签必须成组保留。
- 外部资料继续位于 user prompt，并明确标记为不可信资料。
- 最高相关的 1–2 个证据放在靠近用户问题的位置；不要把所有资料塞进 system prompt 中间。
- 只在上下文超过阈值时压缩。短输入直接通过，避免压缩成本超过收益。

### 4.6 P0 进入和退出条件

进入条件：当前 prompt 组装逻辑和现有测试保持通过，并先记录未压缩 baseline。

建议验收门槛：

- 端侧 prompt token 数平均减少 20% 以上。
- 首 token 延迟平均下降，且不以降低回答长度为代价。
- 角色身份、知识边界和 RAG 来源约束测试全部通过。
- 角色问答、定义问答、外部资料问答没有明显质量回退。
- 所有被丢弃或压缩的内容都能在 debug 中解释原因。

如果 token 数下降但首 token 延迟不降，停止继续增加压缩规则，转入 P2 的前缀复用评估。

### 4.7 当前实现进度

已完成第一轮 P0 运行时接入：

- `llama_jni.cpp` 提供只读 `countTokens` JNI 接口，使用当前加载模型的真实 tokenizer 计数，不修改 KV cache。
- system/user prompt 的 native 处理路径记录实际 token 数、截断 token 数、`current_position` 和处理耗时。
- `LlamaEngine` 记录 system 处理耗时、user prefill 耗时和首 token 延迟，并暴露 `countPromptTokens` 供后续动态预算调用。
- `HarnessBackend.sendChatPrompt` 在结构化请求进入 native 前记录 system/user/total 的 tokenizer 预检值；native 处理日志仍记录模板展开后的最终 decode token 数。
- 普通 RAG 和角色 RAG 均记录每条资料的 `selected`、`compressed` 或 `dropped` 决策、原因、字符数和启发式 token 数。
- 角色会话摘要不再固定 `take(100)`，改为在受保护角色信息之后按剩余 Prompt 预算进行查询感知压缩，并记录实际保留字符数。

这些改动已通过 Kotlin 单元测试和 `assembleDebug` 编译验证。真实 Android 设备上的 baseline/controller A/B、质量回归和“平均减少 20% token 且首 token 延迟下降”的门禁仍未完成，不能仅凭编译结果宣称 P0 完成。

### 4.8 Pixel 9 Pro XL ARM64 虚拟机验证

已在 Pixel 9 Pro XL ARM64 Android 虚拟机上安装 Debug APK，并完成一次 RAG controller 端到端请求。运行结果：

- 模型和 mmproj 成功加载，native context 为 4096。
- system 处理路径记录 `current_position=38`，耗时约 `1881 ms`。
- user 预检 token 为 `694`，native 实际 decode token 为 `701`，未发生截断。
- user prefill 约 `27661 ms`，首 token 延迟约 `27720 ms`。
- 请求完成并生成 assistant 输出，没有 native 崩溃或 JNI 错误。

该结果证明 P0 的运行时链路在 ARM64 虚拟机可用，并提供了可采集的指标。虚拟机当前只有单个 controller 样本，尚未构成 baseline/controller A/B 或质量门禁；其 CPU 延迟也不能代表实体 Android 设备。

## 5. P1：分层会话记忆

### 5.1 目标

借鉴 Mem0 的“提取、更新、检索”分离思想，但第一版使用确定性规则，避免调用第二个模型产生额外端侧延迟和错误记忆。

将当前会话信息拆为：

| 层 | 内容 | 注入位置 | 生命周期 |
|---|---|---|---|
| Identity | 角色身份、语气、知识边界 | system protected | 角色/剧本切换 |
| State | 当前场景、情绪、最近关系变化 | system dynamic | 每轮或状态变化 |
| Episodic memory | 已确认剧情事实和关系 | 按 query 选择 | 会话/剧本 |
| Recent turns | 最近 1–2 轮 | user 或 summary | 短期 |
| External evidence | 本轮外部资料 | user | 仅本轮 |

### 5.2 改造点

- 扩展 `ConversationSummaryBuilder`，输出结构化的状态摘要，而不是把多轮原文拼成一段。
- 将 `conversationSummary.take(100)` 改为由受保护 Prompt 占用量决定的动态剩余预算，不再使用固定字符截断。
- 由 `MemoryPromptSelector` 根据角色、story cutoff、query 和置信度选择记忆。
- 记忆必须保留来源、角色可见性、时间范围、置信度和是否为模型观察结果。
- 外部 RAG 资料不写入角色 identity；只有经过明确规则确认的内容才能进入 episodic memory。

### 5.3 P1 退出条件

- 多轮会话 prompt token 数不随历史线性增长。
- 记忆命中后能够解释来源和选择原因。
- 清空会话、切换角色、切换 cutoff、切换模型时记忆边界正确失效。
- 误记忆和越界记忆测试通过。

## 6. P2：稳定前缀复用

只有在 P0/P1 后仍然确认 system prefill 占首 token 延迟主要部分时实施。

### 6.1 最小改造

先不要实现 CacheBlend 的任意块 KV 拼接，只实现同一 native session 内的稳定 system prefix：

- 为 system prompt 计算 fingerprint：模型、角色、cutoff、模板、protected identity 版本都必须参与。
- 若 fingerprint 未变化，只更新动态状态、摘要和本轮 user prompt。
- 角色切换、模型切换、图像/视频 prefill、cutoff 变化和显式清空时强制失效。
- 在 `HarnessBackend` 增加明确的 `ensureSystemPrompt`/`invalidateContext` 语义，不用隐式绕过 `clearContext()`。

### 6.2 风险

llama.cpp 当前的 `system_prompt_position`、`current_position` 和 `shift_context()` 是 native 内部状态。没有完整的状态边界测试前，不能只在 Kotlin 层缓存字符串；否则可能出现“看似复用，实际 KV 仍然旧”的错误。

## 7. P3：KV 压缩和稀疏 attention

SnapKV、CacheBlend 和 Squeezed Attention 的共同问题是：它们需要访问或改变 attention/KV 内部状态，而当前项目通过 llama.cpp/mtmd 封装了这些细节。

只有满足以下条件才进入 P3：

- P0 已经让 prompt token 数减少，但设备首 token 延迟仍不可接受。
- 目标场景出现数千到数万 token 的稳定上下文。
- 已经有可重复的 native benchmark 和质量基线。
- 能接受按模型 family 建立不同实现和回归测试。

优先顺序建议为：

1. 稳定 system prefix cache。
2. 固定角色知识的离线聚类和 query 路由。
3. 再评估 SnapKV 或 ChunkKV 类 cache 选择。
4. 最后才考虑 Squeezed Attention 的稀疏 kernel 或 CacheBlend 的非前缀 KV 融合。

## 8. 必须先修复的 native 边界问题

在引入新的上下文控制之前，修复 `llama_jni.cpp` 中 user token 截断后的 position 计算：当前代码将 `user_tokens` resize 后，仍用原始 `user_prompt_size` 增加 `current_position`。正确逻辑应以实际送入 decode 的 token 数推进位置，并增加超长输入测试。

建议同时增加：

- system prompt 超出 context 时的明确错误信息。
- user prompt 截断数量和实际 token 数日志。
- `current_position`、`system_prompt_position`、`g_n_ctx` 和 `n_predict` 的每轮 debug 指标。
- 图像 token、文本 token、模板 token 的分项统计。

## 9. 验证方案

### 9.1 离线测试

覆盖现有 `app/src/test` 中的：

- `CharacterPromptCompilerTest`
- `AndroidRagOrchestratorTest`
- `ChatSessionStoreTest`

新增测试重点：

- protected identity/boundary 永不被压缩丢弃。
- 同一 query 的选择结果确定性一致。
- 中文人名、否定词、时间范围和来源标签不会被拆坏。
- 外部资料保持 user-side，不进入角色 identity。
- 压缩前后 `selectedItems`、`droppedItems` 和 token budget 可解释。
- 不同 model family 的 chat template 仍保持 system/user 顺序。

### 9.2 端侧 A/B

每个 case 同时跑 baseline 和 controller 版本，记录：

- system token 数、user token 数、总 input token 数。
- system prefill 时间、user prefill 时间、首 token 延迟、总响应时间。
- 生成 tokens/s、峰值内存、context shift 次数。
- 角色一致性、剧情事实正确性、外部 RAG 命中率和越界率。

测试集至少包括：普通聊天、角色定义问答、关系问答、剧情事实问答、外部资料问答、连续追问、长摘要和图像/文本混合输入。

### 9.3 决策门

- **通过 P0**：token 和首 token 延迟都改善，质量无明显回退，进入 P1。
- **P0 只减 token 不降延迟**：保留 controller，优先评估 P2 prefix reuse。
- **质量下降**：降低压缩比例、扩大 protected 区域，禁止进入 P2。
- **P0/P1 已足够快**：不做 P3 native kernel，保持实现简单和可维护。

## 10. 最终实施顺序

```text
基线指标与 native position 修复
  -> token budget 与 ContextDecision
  -> query-aware 选择和句子级抽取
  -> prompt 重排与 debug 可观测性
  -> 分层会话记忆
  -> 设备 A/B 和质量门禁
  -> 稳定 system prefix 复用
  -> 仅在必要时进入 KV cache/kernel 研究
```

首个可实现阶段应限定为 P0，不同时改会话存储、JNI KV cache 和 attention kernel。这样可以单独验证 LongLLMLingua/LLMLingua-2 思路对当前端侧路径是否真的降低延迟。
