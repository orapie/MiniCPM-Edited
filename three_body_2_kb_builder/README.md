# 《三体Ⅱ》知识库构建工具

本目录仅存放构建、索引、检索、审计和评测代码。生成后的角色卡和知识库数据位于相邻的 `database/`，小说源文件默认为仓库的 `raw_data/three_body_2.txt`。

## 环境

```powershell
python -m pip install -r requirements-semantic.txt
```

## 完整重建

在本目录执行：

```powershell
python scripts\generate_character_cards.py
python scripts\build_knowledge_base_v2.py
python scripts\build_semantic_index.py --device cpu
python scripts\evaluate_retrieval.py
python scripts\audit_database.py
```

输出数据库：

```text
../database/knowledge_base_v2/character_knowledge_v2.sqlite3
```

`build_semantic_index.py` 默认固定使用 `BAAI/bge-small-zh-v1.5` revision `7999e1d3359715c523056ef9478215996d62a620`。更换模型、revision、查询指令、内容模板或池化方式后必须重建向量索引。

## 查询

```powershell
python scripts\query_evidence.py `
  --npc luo_ji `
  --cutoff evt-125 `
  --query "你教什么课？" `
  --mode hybrid `
  --limit 3 `
  --debug
```

默认只允许 `authorization_status=approved` 且在指定剧情 cutoff 前获得的认知记录。`--knowledge-policy include_candidates` 仅用于审计，不应作为角色问答默认配置。

## 目录

- `scripts/build_knowledge_base_v2.py`：构建场景、证据、事实、认知、关系、状态和 SQLite/FTS5。
- `scripts/build_semantic_index.py`：计算 BGE passage embedding 并写入 SQLite BLOB。
- `scripts/query_evidence.py`：字符词项召回、语义召回和 RRF 融合。
- `scripts/evaluate_retrieval.py`：词项/语义/混合三路对照评测。
- `scripts/audit_database.py`：输出表结构、数量、示例和一致性检查。
- `scripts/generate_character_cards.py`：重新生成五张固定角色卡。
- `curation/cognition_overrides.json`：需要人工或 AI 对照原文审核的认知授权覆盖。
