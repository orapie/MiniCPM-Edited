# LongLLMLingua

论文：LongLLMLingua: Accelerating and Enhancing LLMs in Long Context Scenarios via Prompt Compression

- 作者：Jiang et al.
- 发表：ACL 2024
- 正式页面：[ACL Anthology](https://aclanthology.org/2024.acl-long.91/)
- PDF：[官方 PDF](https://aclanthology.org/2024.acl-long.91.pdf)

## 核心方法

LongLLMLingua 将 prompt 压缩、查询信息和长上下文位置偏置放到同一个控制过程里，重点包括：query-aware compression、压缩预算控制、上下文重排和对关键内容的保护。

论文在约 10k token 输入上报告了 2x–6x 压缩和 1.4x–2.6x 端到端加速，但这些结果主要来自服务端/大模型场景，不能直接外推到本项目的 600–900 字符 RAG 输入。

## 对本项目的适配

适合直接移植的是思想，而不是完整压缩器：

- 在 `CharacterPromptCompiler` 中用当前问题重新计算关系、剧情事实、记忆的优先级。
- 为角色身份、知识边界、当前状态设置不可压缩区。
- 为 system 角色信息、对话摘要、外部资料分别分配 token budget。
- 将最相关的证据放在靠近用户问题的位置，减少长 system prompt 的位置损失。

## 可行性

- 可行性：高。
- 端侧成本：低，第一版可以只用现有检索分数、关键词覆盖率、时间/重要性和长度惩罚。
- 主要风险：过度压缩会损害角色语气和剧情事实；必须保留 `retrievalDecisions`、`selectedItems`、`droppedItems` 做 A/B 分析。
- 优先级：P0，适合作为 context controller 的设计基线。
