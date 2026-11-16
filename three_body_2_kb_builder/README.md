# 《三体Ⅱ》离线知识库构建工具

本目录的脚本从 [小说原文](../raw_data/three_body_2.txt) 生成角色卡、场景证据、角色认知和 SQLite 语义索引。生成结果存于相邻的 [database/](../database/)，由仓库根目录的构建任务校验并打包给 Android。

## 重建源数据

在本目录安装依赖并构建离线索引。

```bash
python3 -m pip install -r requirements-semantic.txt
python3 scripts/build_knowledge_base_v2.py
python3 scripts/build_semantic_index.py --device cpu
python3 scripts/evaluate_retrieval.py
python3 scripts/audit_database.py
```

SQLite 文件位于 `../database/knowledge_base_v2/character_knowledge_v2.sqlite3`。语义索引默认使用 `BAAI/bge-small-zh-v1.5` revision `7999e1d3359715c523056ef9478215996d62a620`。变更模型、查询指令、内容模板或池化后，重新构建语义向量并校验与 Android 查询编码器的一致性。

需要重建角色卡初稿时，可以先输出到独立目录，再对照已有的人工公开条目、事实原句和证据修订。

```bash
python3 scripts/generate_character_cards.py --output /tmp/three-body-card-draft/characters
```

脚本默认输出会覆盖 `../database/characters/` 下的角色卡，生成卡目前没有人工维护的 `public_profile`。合并新卡前，按[知识包说明](../database/README.md#数据与授权)复核这些条目的路径、证据和截止点。

源数据或人工审核更新后，从仓库根目录刷新 Android 导出。

```bash
python3 scripts/prepare_database_bundle.py --source database
python3 scripts/prepare_database_bundle.py --check
```

## 查询与审计

```bash
python3 scripts/query_evidence.py \
  --npc luo_ji --cutoff evt-125 --query "你教什么课？" --mode hybrid --limit 3 --debug
```

离线查询默认筛选已获授权且在 cutoff 前取得的认知。扩大到场景候选的选项用于审计，Android 的实际角色授权由知识包导出和运行时共同决定。

- [build_knowledge_base_v2.py](scripts/build_knowledge_base_v2.py) 构建场景、证据、角色认知、关系、状态及 SQLite。
- [build_semantic_index.py](scripts/build_semantic_index.py) 计算 BGE passage embedding 并写入 SQLite。
- [query_evidence.py](scripts/query_evidence.py) 提供词项、语义和融合检索。
- [evaluate_retrieval.py](scripts/evaluate_retrieval.py) 对照检索效果。
- [audit_database.py](scripts/audit_database.py) 核对结构、数量及一致性。
- [cognition_overrides.json](curation/cognition_overrides.json) 保存需要对照原文复核的源认知修订。
