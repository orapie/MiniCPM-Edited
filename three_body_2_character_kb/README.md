# 《三体Ⅱ：黑暗森林》角色知识库

本目录是一个独立的数据包，不修改项目现有 Android 资源或默认角色配置。

## 内容

- `characters/`：5 张角色卡，角色为罗辑、章北海、史强、庄颜和丁仪；
- `story/story_events.jsonl`：125 条带原文行号的角色相关事件；
- `knowledge_base_v2/`：场景、实体、证据、角色知识边、快照及 SQLite/FTS5 数据库；
- `reports/character_cards.md`：角色卡摘要；
- `schemas/`：角色卡和剧情事件 JSON Schema；
- `scripts/`：可重复运行的角色卡与知识库生成脚本。

主要运行时文件：

```text
knowledge_base_v2/character_knowledge_v2.sqlite3
```

当前数据规模：146 个场景、125 个事件、125 条事实、2,424 个实体提及和 229 条角色知识边。
`knowledge_base_v2/manifest.json` 记录构建参数、数量、cutoff 和校验状态。

## 重新生成

原始小说 TXT 未以单独文件放在本目录。准备好原文后执行：

```powershell
python scripts\generate_character_cards.py three-body-2 --source E:\path\to\three_body_2.txt
python scripts\build_knowledge_base_v2.py --three-body-2 --source E:\path\to\three_body_2.txt
```

脚本自动识别 UTF-8 或 GB18030 编码。重新构建会更新角色卡事件引用并清理旧快照。

## 校验结果

- 角色卡数量：5
- SQLite `PRAGMA integrity_check`：`ok`
- 角色、事件和证据交叉引用：通过
- 知识库 manifest：`validation.passed = true`
