# 角色知识库接入 Agent

## 结论

不要只交付 `knowledge_base/graph.json`。

`graph.json` 是面向展示、遍历或导入图数据库的 nodes/edges 视图。为了控制角色知识边界，它有意没有在 claim 节点中重复保存完整的 `known_by`、`sequence_key`、`evidence` 等运行时字段。只用该文件，Agent 无法可靠判断“谁知道这件事”和“当前剧情时点能否知道”。

最小运行时交付：

```text
kb/
├── manifest.json       # event cutoff -> 原文顺序号
└── claims.jsonl        # 可检索事实及知识边界
```

推荐完整交付：

```text
kb/
├── manifest.json
├── claims.jsonl
├── entities.jsonl
├── relations.jsonl
└── graph.json
python/
└── graph_knowledge.py  # 可直接复用的 Python 检索实现
```

其中 `entities.jsonl`、`relations.jsonl`、`graph.json` 不属于最小运行依赖，但便于实体消歧、关系查询、可视化和后续迁移。

## Agent 每轮需要提供的输入

- `npc_id`：当前角色，如 `lu_jiangxian`；
- `query`：玩家本轮问题；
- `story_cutoff`：当前剧情截止事件，如 `evt-018`；
- `top_k`：最多注入多少条事实，建议 4–8。

缺少 `npc_id` 或 `story_cutoff` 时，不应启用知识库事实注入。尤其不要默认使用全书最后时间点。

## 事实结构

`claims.jsonl` 每行是一个 JSON 对象，关键字段如下：

| 字段 | 含义 | 运行时用途 |
| --- | --- | --- |
| `claim_id` | 稳定事实 ID | 日志、去重、评测 |
| `statement` | 可注入 Prompt 的自然语言事实 | 生成上下文 |
| `subject_ids` | 涉及的实体 | 实体过滤 |
| `known_by` | 哪些角色知道，以及来源/可信度 | 最关键的角色知识边界 |
| `sequence_key` | 事实在原文中的顺序号 | 防止未来剧情泄漏 |
| `importance` | 重要度 0–1 | 排序 |
| `confidence` | 可信度 0–1 | 排序与措辞 |
| `keywords` | 关键词 | 端侧关键词召回 |
| `source_tier` | `canon`、`curated` 或 `external` | 来源隔离 |
| `canon_status` | `accepted`、`auto_accepted`、`review_required` | 审核门禁 |
| `evidence` | 原文文件和行号 | 审计与纠错 |

`manifest.json` 中的 `cutoffs` 将 `evt-018` 之类的业务时间点转换为可比较的 `sequence_key`。

## 标准接入流程

```text
用户问题
  -> 关键词/BM25/向量召回候选 claim
  -> 来源状态过滤
  -> known_by 角色知识过滤
  -> sequence_key 时间线过滤
  -> 相关性 + importance + confidence 排序
  -> 去重并取 Top-K
  -> 转成带“亲历/目击/听说/推断”标记的自然语言
  -> 注入可信 System Prompt
  -> 模型生成
  -> 一致性 Reviewer 复核
```

推荐先做硬过滤，再把事实交给模型：

```python
cutoff_sequence = manifest["cutoffs"][story_cutoff]

def is_allowed(claim, npc_id):
    tier = claim["source_tier"]
    status = claim["canon_status"]
    if tier == "external" and status != "accepted":
        return False
    if tier not in {"canon", "curated", "external"}:
        return False
    if status not in {"accepted", "auto_accepted"}:
        return False
    if npc_id not in claim["known_by"]:
        return False
    if claim["sequence_key"] > cutoff_sequence:
        return False
    return True
```

不要把检索候选全部放进 Prompt 后再要求模型自行判断。知识边界必须由 Agent 代码执行。

## 直接使用现有 Python 实现

独立检索：

```python
from pathlib import Path
from graph_knowledge import GraphKnowledgeStore

store = GraphKnowledgeStore(Path("kb"))
claims, decisions = store.retrieve(
    npc_id="lu_jiangxian",
    query="你为什么拒绝用无辜者换取生路？",
    cutoff="evt-018",
    limit=6,
)

for item in claims:
    print(item.claim["statement"], item.score)
```

`decisions` 用于开发日志和评测，可查看某条事实为什么被拒绝。不要把 decisions 或完整 JSON 暴露给生成模型。

如果同事直接复用当前 `character_system`：

```python
from runtime import OpenAICompatibleChatModel, RoleConsistencyAgent

model = OpenAICompatibleChatModel(
    model="your-model-name",
    base_url="http://127.0.0.1:8000/v1",
    api_key="EMPTY",
)

agent = RoleConsistencyAgent(
    root="character_system",
    model=model,
    knowledge_base_dir="character_system/knowledge_base",
)

session = agent.start_session("lu_jiangxian", story_cutoff="evt-018")
turn = agent.reply(session, "你为什么拒绝用无辜者换取生路？")
print(turn.response)
```

命令行接入：

```powershell
python scripts\chat_agent.py `
  --npc lu_jiangxian `
  --model your-model-name `
  --knowledge-base .\knowledge_base
```

## Prompt 注入格式

建议只注入 `statement` 和角色对该事实的获知方式，不要注入内部字段：

```text
【本轮允许使用的剧情事实】
- 陆江仙拒绝玄谙以生存为由的取代方案。〔亲历，可信度 1.00〕
- 玄谙曾说明元府三司的旧事。〔听说，可信度 0.90〕
```

低于 0.9 的事实应使用“可能”“据称”“我推断”等限定表达。没有授权事实时明确告诉模型不要用常识补全。

## 验收用例

至少验证以下场景：

1. `lu_jiangxian + evt-018` 能召回其拒绝牺牲无辜者的事实；
2. `xuan_an + evt-018` 不能获得陆江仙私有的前世北斗记忆；
3. `xuan_an + evt-018` 不能获得 `evt-020` 的未来结局；
4. `external + review_required` 事实不能进入 Prompt；
5. Prompt 注入后回答不泄露 `claim_id`、内部字段或筛选原因；
6. 未启用知识库和启用知识库使用同一问题集做 A/B，重点比较 knowledge、identity、behavior、style。

当前仓库中的 `tests/test_knowledge_workflow.py` 已覆盖前 3 项及 Prompt 接入。
