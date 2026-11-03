# BGE 查询编码器

本目录的 ONNX 模型从 `BAAI/bge-small-zh-v1.5` revision `7999e1d3359715c523056ef9478215996d62a620` 转换，原模型许可证为 MIT。原模型卡：<https://huggingface.co/BAAI/bge-small-zh-v1.5>。

`model_manifest.json` 记录原权重、ONNX 和词表的 SHA-256 与查询编码参数。原模型目录的 `sentence_bert_config.json` 指定 `do_lower_case=true`；这与部分 tokenizer 配置中的值不同。项目沿用与数据库已有向量一致的小写处理、CLS pooling、L2 归一化和查询前缀。

离线重建可使用 `scripts/export_bge_onnx.py`；需要已安装 PyTorch、Transformers、ONNX 和已下载的固定版本原模型。运行时只使用打包到当前 `database/` 的文件，不下载模型。
