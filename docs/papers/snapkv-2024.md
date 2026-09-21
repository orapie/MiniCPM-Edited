# SnapKV

论文：SnapKV: LLM Knows What You are Looking for Before Generation

- 作者：Li et al.
- 时间：2024
- 预印本：[arXiv](https://arxiv.org/abs/2404.14469)
- PDF：[arXiv PDF](https://arxiv.org/pdf/2404.14469)

## 核心方法

SnapKV 利用 prompt 末尾 observation window 的注意力模式，按 attention head 选择和聚类重要 KV 位置，在 generation 前压缩 KV cache。方法不需要微调目标模型，主要收益发生在长上下文 decode 阶段。

## 对本项目的适配

理论上可以用于 `llama_jni.cpp` 的 KV cache，但当前工程使用 llama.cpp/mtmd 的统一 native context，代码没有暴露按 head/token 操作 KV cache 的接口。要实现 SnapKV，需要：

- 获取各层各 head 的 KV 或注意力统计。
- 在 generation 前重建压缩后的 cache。
- 处理 RoPE 位置、图像 token、system prompt 和 context shift 的一致性。

## 可行性

- 可行性：低到中。
- 适用场景：上下文达到数千到数万 token，且 decode 阶段成为主要瓶颈时。
- 当前项目优先级：P2。
- 结论：先做 prompt 压缩和 KV 前缀复用，再考虑 SnapKV；当前 4096/8192 context 和短 RAG 输入不足以证明它值得改 native kernel。
