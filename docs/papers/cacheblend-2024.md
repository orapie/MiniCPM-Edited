# CacheBlend

论文：CacheBlend: Fast Large Language Model Serving for RAG with Cached Knowledge Fusion

- 作者：Yao et al.
- 发表：EuroSys 2025；预印本 2024
- 预印本：[arXiv](https://arxiv.org/abs/2405.16444)
- PDF：[arXiv PDF](https://arxiv.org/pdf/2405.16444)

## 核心方法

CacheBlend 针对 RAG 的多个文本块：预先保存各块 KV cache，然后对少量 token 做选择性重算，以处理不同文本块之间原本缺失的 cross-attention。它解决了普通 prefix cache 只能复用最前缀的问题。

## 对本项目的适配

当前角色 RAG 每轮会先 `clearContext()`，再把 system 和 user prompt 重新送入 native。若未来把稳定角色资料、重复外部文档块和会话摘要分成可缓存块，CacheBlend 的“块缓存 + 少量重算”思想有参考价值。

但当前项目不是 vLLM/服务端多请求架构，也没有持久化 KV cache 的 JNI ABI。Android 端还要考虑模型切换、量化格式、内存压力和 KV cache 与上下文位置的严格一致性。

## 可行性

- 可行性：当前版本低；拆成同一会话内的稳定 system prefix cache 后为中等。
- 适合先做的简化版：角色身份和固定规则只 prefill 一次，动态摘要与本轮 RAG 单独更新。
- 当前项目优先级：P2。
- 主要风险：跨块直接拼 KV 会产生位置和 cross-attention 错误，不能只把文本块的缓存数组直接连接起来。
