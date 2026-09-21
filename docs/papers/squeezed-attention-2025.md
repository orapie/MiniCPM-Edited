# Squeezed Attention

论文：Squeezed Attention: Accelerating Long Context Length LLM Inference

- 作者：Hooper et al.
- 发表：ACL 2025
- 正式页面：[ACL Anthology](https://aclanthology.org/2025.acl-long.1568/)
- PDF：[官方 PDF](https://aclanthology.org/2025.acl-long.1568.pdf)

## 核心方法

Squeezed Attention 针对大量固定上下文：离线对固定上下文的 key 做聚类，推理时先用 query 与聚类中心匹配，再只对可能重要的 key 执行精确 attention。论文同时讨论了层次化聚类和稀疏 kernel。

## 对本项目的适配

项目中的角色身份、固定规则和部分角色卡内容符合“固定上下文”特征，但当前 Android native 路径没有稀疏 FlashAttention 或可插入的 attention kernel。直接移植会超出 Kotlin prompt 控制的范围，需要针对 llama.cpp/ggml 写新的 backend/kernel。

可以先借鉴它的离线思想：将固定角色资料预先按主题聚类或摘要化，运行时只把与当前 query 相关的簇送进 prompt。这个版本不需要改 attention kernel。

## 可行性

- 离线聚类 + query 路由：中到高。
- native 稀疏 attention：低。
- 当前项目优先级：P2；作为未来大规模角色知识库的方向。
