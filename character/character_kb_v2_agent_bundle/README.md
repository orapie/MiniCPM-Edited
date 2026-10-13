# V2：时序角色认知知识库

V2 与 V1 并存。版本边界见 [KNOWLEDGE_BASE_VERSIONS.md](KNOWLEDGE_BASE_VERSIONS.md)。

## 当前产物

```text
knowledge_base_v2/
├── manifest.json
├── character_knowledge_v2.sqlite3
├── corpus/
│   ├── documents.jsonl
│   ├── scenes.jsonl
│   └── utterances.jsonl
├── graph/
│   ├── entities.jsonl
│   ├── aliases.jsonl
│   ├── entity_mentions.jsonl
│   ├── events.jsonl
│   ├── claims.jsonl
│   ├── evidence.jsonl
│   ├── knowledge_edges.jsonl
│   └── relations.jsonl
├── snapshots/
└── quality/
```

Agent 运行时只需交付 `character_knowledge_v2.sqlite3`。`manifest.json` 用于版本检查和审计；其他 JSONL 是可读的构建中间产物。

## 与 V1 的核心区别

- 按场景和对话组织原文，不再只使用固定长度 chunk；
- 实体、别名、原文 mention 和消歧结果分开保存；
- claim 与 evidence 分离，每段证据带原文、行号和 SHA-256；
- “事实是什么”和“角色何时、通过什么方式知道”分离；
- 抽取与验证是两个独立模型调用，可指定不同 verifier 模型；
- SQLite 使用 FTS5 trigram，适合中文端侧检索；
- role profile 默认不参加剧情事实检索，避免角色卡中的未来描述间接泄漏。

## 构建

当前已生成的版本是 `curated`：使用现有已审核事件和角色卡作为事实金标，同时重新生成场景、对白、实体 mention、证据、认知边、快照和 SQLite。

```powershell
python scripts\build_knowledge_base_v2.py --mode curated
```

配置模型后运行完整双阶段抽取：

```powershell
$env:OPENAI_BASE_URL = "http://127.0.0.1:8000/v1"
$env:MODEL_NAME = "extractor-model"
$env:VERIFIER_MODEL_NAME = "verifier-model"

python scripts\build_knowledge_base_v2.py --mode hybrid
```

如果只有一个模型，验证阶段仍是独立提示和独立调用；若配置不同的 `VERIFIER_MODEL_NAME`，可进一步降低同模型自证偏差。两阶段结果按 scene ID 缓存，未变化场景不会重复调用。

## 端侧查询

```powershell
python scripts\query_knowledge_base_v2.py `
  --npc lu_jiangxian `
  --cutoff evt-018 `
  --query "你为什么拒绝用无辜者换取生路？" `
  --debug
```

Python API：

```python
from runtime import SQLiteKnowledgeStoreV2

with SQLiteKnowledgeStoreV2("knowledge_base_v2/character_knowledge_v2.sqlite3") as kb:
    claims, decisions = kb.retrieve(
        character_id="lu_jiangxian",
        query="你为什么拒绝用无辜者换取生路？",
        story_cutoff="evt-018",
        limit=6,
    )
```

检索器依次执行：FTS5 + bigram 候选召回、来源审核过滤、`knowledge_edges` 角色过滤、获得时间过滤、claim 有效期过滤、相关性/重要度/可信度排序。

## 接入现有 Agent

```powershell
python scripts\chat_agent.py `
  --npc lu_jiangxian `
  --model your-model-name `
  --knowledge-base-v2 .\knowledge_base_v2\character_knowledge_v2.sqlite3
```

Showcase：

```powershell
python scripts\run_showcase.py `
  --model your-model-name `
  --knowledge-base-v2 .\knowledge_base_v2\character_knowledge_v2.sqlite3
```

V1 使用 `--knowledge-base`，V2 使用 `--knowledge-base-v2`，两个参数互斥。

给其他 Agent 同事导出最小接入包：

```powershell
python scripts\export_agent_kb_bundle_v2.py --with-sources
```

产物为 `dist/character_kb_v2_agent_bundle.zip`，包含 SQLite 数据库、manifest、只读 Python 检索器、接入说明和可选原文证据。

## 当前质量状态

机械场景切分和已审核事实可以立即运行。对白说话人只有在局部上下文出现明确“姓名 + 道/问/答”等证据时才自动绑定；其余保持 unresolved，不强行猜测。运行 `hybrid` 后，场景抽取器和独立 verifier 会补充可被原文直接支持的说话人、事件和认知边。

不要为了提高“已解析率”而将模糊代词强制绑定角色；错误说话人会直接污染人物语言风格和知识范围。
