# Context Control Papers

本目录整理与 MiniCPM Android 端侧上下文控制相关的论文，并把论文中的方法翻译成当前项目可以验证的工程问题。重点不是简单扩大上下文窗口，而是决定：哪些内容进入上下文、以什么顺序进入、哪些内容应长期保存，以及哪些内容可以在 native 层复用。

## 总体结论

当前项目应按“先 prompt、后 KV”的顺序推进：

1. **Prompt-side context controller（P0）**：按当前问题重新排序、筛选和分配预算。主要借鉴 LongLLMLingua。
2. **低成本片段压缩（P1）**：先做句子/短语级抽取式压缩，再评估端侧压缩模型。主要借鉴 LLMLingua-2。
3. **分层长期记忆（P1）**：把短期对话、角色事实、会话摘要和外部资料分开管理。主要借鉴 Mem0。
4. **KV cache/稀疏 attention（P2）**：在前面验证收益后，再评估 CacheBlend、SnapKV、Squeezed Attention；这些方向需要改 llama.cpp/ggml 或 JNI，不能仅靠拼接字符串实现。

| 层次 | 要回答的问题 | 当前实现位置 | 主要论文 |
|---|---|---|---|
| 选择与排序 | 当前问题最需要哪些角色事实、记忆和外部证据？ | `CharacterPromptCompiler`、`AndroidRagOrchestrator` | LongLLMLingua |
| 内容压缩 | 选中的片段中哪些句子/短语可以省略？ | `CharacterRagPromptBuilder`、`PromptBuilder` 之前 | LLMLingua-2 |
| 记忆管理 | 哪些信息值得跨轮次保存？ | `ConversationSummaryBuilder`、`MemoryPromptSelector`、`MemoryStore` | Mem0 |
| KV/attention | 已有 token 如何少算、复用或稀疏计算？ | `llama_jni.cpp`、ggml/native backend | CacheBlend、SnapKV、Squeezed Attention |

## 论文逐篇说明

### 1. LongLLMLingua：按问题决定“上下文放什么”

论文：[LongLLMLingua: Accelerating and Enhancing LLMs in Long Context Scenarios via Prompt Compression](./longllmlingua-2024.md)

#### 核心论点

LongLLMLingua 的关键不是无差别删除 token，而是**查询感知压缩**：同一段上下文对不同问题的价值不同，压缩必须结合当前 query。论文把 query-aware compression、预算控制、上下文重排和长上下文位置偏置放到同一个流程中，优先保留真正有助于回答当前问题的内容，并降低关键信息位于长 prompt 中间时的利用损失。

因此它对应的工程假设是：上下文控制器不应只有固定 `topK` 或固定字符上限，还要同时考虑查询、角色身份、知识边界、重要性和剩余预算。

#### 对本项目的指导作用

- 在 `CharacterPromptCompiler` 中，将角色身份、核心性格、知识截止点和规则设为不可压缩区；关系、授权剧情事实、检索记忆属于查询相关的可选区。
- 用当前问题重新计算候选优先级，不要每轮注入相同角色卡。现有 `KeywordStoryRetriever`、`KnowledgeBoundaryFilter`、`retrievalDecisions`、`selectedItems`、`droppedItems` 已经可以支撑第一版可解释控制器。
- 为角色必需信息、会话摘要、长期记忆、外部 RAG 分配独立预算。
- 将相关证据放在靠近本轮用户问题的位置，同时保留“外部资料不是角色记忆”的边界。

#### 当前落地方式

第一版可用“检索分数 + 查询关键词覆盖 + 事件重要性 + 长度惩罚 + 角色授权过滤”完成，不必复刻论文压缩模型。每轮记录 prompt token 数、各区段字符数、选中/丢弃条目、prefill 时间、首 token 延迟和回答质量。

论文中的长上下文加速比例来自更长输入和服务端场景，不能直接外推到当前几百字符的 RAG 输入。对本项目最有价值的是**控制框架**，不是论文数字。

### 2. LLMLingua-2：把“选中的内容”进一步压短

论文：[LLMLingua-2: Data Distillation for Efficient and Faithful Task-Agnostic Prompt Compression](./llmlingua-2-2024.md)

#### 核心论点

LLMLingua-2 将 prompt compression 建模为 token classification：压缩器判断每个 token 是否保留，而不是每次调用大型 causal LM 估计 token 重要性。论文主张，经过数据蒸馏训练的双向编码器可以作为独立压缩器，在不依赖目标 LLM 逐 token 打分的情况下，以较低开销完成任务无关压缩。

它与 LongLLMLingua 的区别是：LongLLMLingua 更强调查询驱动的整体上下文控制，LLMLingua-2 更强调独立部署的抽取式保留器。

#### 对本项目的指导作用

- 先做句子/短语级抽取，不要直接按字符删中文；实体、否定词、时间边界、来源和角色视角可能被破坏。
- 角色身份、知识边界、动态状态和禁止编造规则不得交给通用压缩器处理。
- 只有输入超过阈值才触发压缩模型；600–900 字符的输入上，压缩器自身推理时间可能超过节省的 MiniCPM prefill 时间。
- 同时记录压缩前后文本、保留原因和回答质量，验证“更短”是否换来了事实损失。

#### 当前落地方式

可在 `CharacterRagPromptBuilder`/`PromptBuilder` 生成候选片段后、`ChatTemplateRenderer` 渲染前插入。第一阶段按查询实体、时间、关系和来源保留句子；第二阶段再评估量化的小型压缩模型。压缩只改变本轮输入，不覆盖原始资料库。

### 3. Mem0：决定“什么值得留下来”

论文：[Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory](./mem0-2025.md)

#### 核心论点

Mem0 不把长期记忆理解成一份更短的完整历史，而是把它当成独立生命周期：从对话提取候选记忆，判断是否保存，与已有记忆合并/更新，最后按需检索。它强调“记忆选择”比完整历史重放更重要。

对角色系统而言，模型刚刚说出的内容不能自动变成角色事实；长期记忆必须有来源、范围和更新规则。

#### 对本项目的指导作用

- 短期上下文只保留当前问题和少量最近轮次；稳定身份、关系和确认过的剧情事实进入角色记忆层。
- `ConversationSummaryBuilder` 只保存后续需要的状态变化，不把完整对话伪装成角色记忆。
- `MemoryPromptSelector` 继续执行 prompt-safe、scope、kind 过滤；外部 RAG 只在本轮需要时注入，不自动写入身份层。
- 每条长期记忆保留来源、置信度、角色可见性、story cutoff、更新时间和用户确认状态。

#### 当前落地方式

先明确四类边界：当前轮次、近期摘要、角色长期记忆、外部 RAG。对用户明确陈述、模型推断、检索文档事实采用不同写入策略。重点验证错误记忆能否撤销、故事截止点是否被绕过，以及记忆是否真的降低 prompt 长度。

### 4. CacheBlend：复用多个 RAG 块的 KV

论文：[CacheBlend: Fast Large Language Model Serving for RAG with Cached Knowledge Fusion](./cacheblend-2024.md)

#### 核心论点

普通 prefix cache 只能复用完全相同的前缀，而 RAG 的文本块组合会随查询变化。CacheBlend 的核心方法是预先缓存各知识块的 KV，组合后只对少量 token 选择性重算，以补偿不同块之间原本缺失的 cross-attention。

它不是把 KV 数组直接拼起来：位置编码、块间交互和重算位置必须一致。

#### 对本项目的指导作用

- 未来可把稳定角色 system prompt、重复外部文档块和会话摘要视为不同缓存单元。
- 当前 `clearContext()` 会清空 native context；要做缓存，必须限定同一模型、角色、chat template、story cutoff 和上下文版本。
- 角色身份与动态 RAG 应分层，否则角色切换、截止点变化或资料变化会导致缓存失效边界不清。

#### 当前限制

当前工程不是 vLLM 式服务端多请求架构，也没有持久化 KV cache JNI ABI。可先验证同一会话内稳定 system prefix 复用，并与完整 prefill 对照；不要直接在 native 层拼接不同块的缓存数组。优先级 P2。

### 5. SnapKV：生成前压缩重要 KV 位置

论文：[SnapKV: LLM Knows What You are Looking for Before Generation](./snapkv-2024.md)

#### 核心论点

SnapKV 利用 prompt 末尾 observation window 的注意力模式，认为其中已经包含当前问题对上下文的需求信号。它为不同 attention head 选择并聚类重要 KV 位置，在生成前丢弃不重要的 KV，以降低长上下文 decode 成本，且不要求微调目标模型。

#### 对本项目的指导作用

- 先区分 prefill 和 decode：若慢在首次处理长 prompt，SnapKV 不是第一解；若生成阶段占主导，KV 压缩才可能有价值。
- 评估必须分别记录 prompt token 数、prefill 时间、首 token 延迟、每 token decode 时间和 KV 内存。
- 角色 RAG 还要处理图像 token、system prompt、RoPE 位置和 context shift，不能直接套用纯文本模型结果。

#### 当前限制

`llama_jni.cpp` 使用 llama.cpp/mtmd 的统一 native context，没有向 Kotlin 暴露按 head/token 操作 KV 的稳定接口。实现 SnapKV 需要注意力统计、cache 重建以及多模态位置一致性，因此是 P2 方向；在 4096/8192 context 和短 RAG 输入上不能预设它一定有收益。

### 6. Squeezed Attention：固定上下文只计算相关部分

论文：[Squeezed Attention: Accelerating Long Context Length LLM Inference](./squeezed-attention-2025.md)

#### 核心论点

Squeezed Attention 针对大量重复的固定上下文，离线对 key 聚类；推理时先用 query 匹配聚类中心，只对可能相关的 key 执行精确 attention。它把全量扫描变成“粗筛 + 精算”，并讨论层次化聚类和稀疏 kernel。

#### 对本项目的指导作用

- 角色身份、固定规则和角色卡稳定资料符合固定上下文特征，可借鉴离线预处理。
- 不改 native kernel 时，可先按主题对角色资料分组，运行时根据问题选择相关主题，再交给 `CharacterPromptCompiler` 做知识边界和预算过滤。
- 该方向适合大规模角色知识库；对当前较小角色卡，聚类管理成本可能高于推理节省。

#### 当前限制

优先实现“主题簇 + 查询路由 + prompt 选择”，不要直接实现稀疏 attention。kernel 版本需要改 llama.cpp/ggml backend，并针对量化模型、CPU 路径和 Android 内存布局验证，优先级 P2。

## 论文之间的关系

这些论文不是六个可以同时开启的开关，而是一条逐步下沉的路线：

```text
当前问题
  -> LongLLMLingua：选择、排序、预算
  -> LLMLingua-2：压缩选中的句子/短语
  -> Mem0：决定哪些信息跨轮次保存
  -> CacheBlend：尝试复用稳定上下文 KV
  -> SnapKV / Squeezed Attention：减少 KV 或 attention 计算
```

前两层主要改变输入内容，容易 A/B；Mem0 改变数据生命周期，需要防止错误记忆污染；后三篇改变 native 计算路径，只有 profiling 证明 prompt-side 优化仍不足时才进入。

## 与当前工程的映射

- `app/src/main/java/com/example/minicpm_v_demo/harness/rag/AndroidRagOrchestrator.kt`：选择普通 RAG、角色 RAG、外部知识路由和 prompt 编译流程。
- `app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompiler.kt`：角色必需信息、查询候选、知识边界、字符预算及 `selectedItems`/`droppedItems`/`retrievalDecisions`。
- `app/src/main/java/com/example/minicpm_v_demo/harness/rag/CharacterRagPromptBuilder.kt`：把外部资料作为本轮参考资料放入 user message，并限制外部上下文。
- `app/src/main/java/com/example/minicpm_v_demo/harness/session/ConversationSummaryBuilder.kt`：生成受长度和轮数限制的近期摘要。
- `app/src/main/java/com/example/minicpm_v_demo/harness/session/MemoryPromptSelector.kt`、`MemoryStore.kt`：筛选可安全放入 prompt 的长期记忆。
- `app/src/main/cpp/llama_jni.cpp`：创建、清理和移动 native context，并执行 system/user prompt 的 token eval；后三篇的实现最终会落到这一层或其 llama.cpp/ggml 依赖。

## 建议的验证指标

每次策略变更都应同时记录成本和质量：

- **成本**：system/user prompt token 数、总上下文 token 数、prefill 时间、首 token 延迟、decode 每 token 时间、峰值内存。
- **选择过程**：各区段预算、候选数、选中/丢弃条目、丢弃原因、外部资料来源和分数。
- **质量**：角色身份保持、知识截止点遵守、外部事实引用、剧情事实完整性、跨轮次记忆一致性、无依据内容比例。
- **边界**：无 RAG、普通 RAG、角色 RAG、切换角色、切换模型、清空 context、超预算和图像输入。

论文中的服务端长上下文加速数字不能直接作为 Android CPU、量化和多模态路径的预期结果；最终应以真实设备日志、实际模型配置和质量回归为准。

## 2026 观察项

Decoupled Attention Fusion、AgentKVShift、HYBRIDKV 等方向可继续跟踪，但目前主要面向服务端 RAG KV reuse、agent memory KV reuse 或 GPU attention kernel。它们暂不纳入首期实现清单；是否引入，应由真实设备 profiling、llama.cpp 接口能力和可复现质量回归决定。
