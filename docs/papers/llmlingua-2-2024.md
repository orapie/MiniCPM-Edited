# LLMLingua-2

论文：LLMLingua-2: Data Distillation for Efficient and Faithful Task-Agnostic Prompt Compression

- 作者：Pan et al.
- 发表：Findings of ACL 2024
- 正式页面：[ACL Anthology](https://aclanthology.org/2024.findings-acl.57/)
- PDF：[官方 PDF](https://aclanthology.org/2024.findings-acl.57.pdf)

## 核心方法

LLMLingua-2 将 prompt 压缩建模为 token classification，用双向编码器判断哪些 token 可以保留。它避免每次都调用一个大型 causal LM 来估计 token 信息量，并报告了比已有压缩方法更低的压缩开销。

## 对本项目的适配

完整的 XLM-RoBERTa-large 或 mBERT 压缩器不适合直接作为当前 Android app 的默认依赖，但方法可以拆成两个阶段：

1. 第一阶段采用抽取式压缩：按句子/短语保留命中查询实体、时间、关系和知识来源的内容。
2. 第二阶段再评估量化的小型压缩模型，并只在输入超过阈值时触发。

不要在当前 600–900 字符上下文上无条件调用压缩模型，否则压缩模型的推理时间可能超过节省的 MiniCPM prefill 时间。

## 可行性

- 可行性：规则/抽取式版本高；端侧模型版本中等。
- 适合位置：`CharacterRagPromptBuilder.compileContext` 和 `PromptBuilder.compileContext` 之后、`ChatTemplateRenderer` 之前。
- 主要风险：token 删除可能破坏中文实体、否定词、时间边界和角色视角；应以句子/短语为最小单元，而不是直接按字符删除。
- 优先级：P1。
