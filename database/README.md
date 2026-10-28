# 《三体Ⅱ：黑暗森林》角色知识库

本目录是项目统一知识数据源。已完成 Phase 0–5 的协议/校验、向量索引、APK 打包、设备安装激活、检索消费者接入、会话隔离和阶段验收。构建只打包当前 `assets/database/` 知识包，启动时安装到 `<filesDir>/database/`；生产检索工厂只从该包创建服务。

## 内容

- `characters/`：5 张角色卡，角色为罗辑、章北海、史强、庄颜和丁仪；
- `story/story_events.jsonl`：125 条带原文行号的角色相关事件；
- `knowledge_base_v2/`：场景、实体、证据、角色知识边、快照及 SQLite/FTS5 数据库；
- `reports/character_cards.md`：角色卡摘要；
- `schemas/`：角色卡和剧情事件 JSON Schema；
- `scripts/`：可重复运行的角色卡与知识库生成脚本。
- `manifest.json`：统一入口、数据集身份、文件 SHA-256 与包 fingerprint。
- `reviewed/atomic_facts.json`：单独核验过对话上下文、角色知情与原文行号的短事实样本。
- `runtime/android/scene_candidates.json`：125 条原场景事实和 229 条关联边的忠实导出，只能用于候选召回。
- `runtime/android/knowledge_fallback.json`：3 条经显式对话审查的短事实及授权边，保留 `canon/source_span_verified` 来源属性。
- `runtime/android/compatibility_report.json`：来源、授权、长度与未完成能力的审计报告。
- `runtime/android/vector/index.json`：384 维 hash n-gram 向量索引，含 125 条场景候选和 3 条已审查事实；所有条目仍要求角色授权检查。

从仓库根目录执行：

```bash
python3 scripts/prepare_database_bundle.py --source database
python3 scripts/prepare_database_bundle.py --source database --check
python3 -m unittest discover -s scripts/tests -v
bash ./gradlew :app:verifyDebugDatabaseAssets
```

第一个命令更新运行导出与入口 manifest；`--check` 只读校验，不改写文件。生成工具只使用显式数据根目录，原文取本包嵌入 corpus，不查找旧 `character/`、旧 Harness 文档或 manifest 中的 Windows 来源路径。修改源库、角色卡、事件或已审查事实后，必须重新生成，过期 hash 会使校验失败。

当前 manifest 声明 `v2/vector=true`，`v1/timed_state=false`。向量算法与现有 Kotlin `HashEmbeddingModel` 一致，包含维度、n-gram、hash、归一化配置；索引和文件清单均携带数据集身份和来源 hash。229 条原边的 `character_scene_relevance/available` 仅代表场景关联；生成器也不会把 story 中批量标为 `direct` 的场景自动授权。只有单独审查的 3 条样本被导出为角色可知事实，其中宇宙社会学公理保留为罗辑听闻叶文洁提出的观点。

`prepareDatabaseBundle` 构建任务只校验和复制，不会改写本目录；源 manifest、hash 或预生成索引过期时，构建会失败并要求先运行生成命令。输出固定到 `app/build/generated/databaseAssets/database/`，不会与输入形成循环。APK 校验报告位于 `app/build/reports/database/apk_inventory.json`，核对实际包内文件、hash、旧数据排除和 TTS 音频保留情况。

启动流程只从 APK 当前数据包复制 manifest 中的受控文件，先在同父目录 staging 校验，再整包激活；不在旧文件上逐个覆盖。相同 fingerprint 在复核已安装文件后复用；同数据集更新失败可保留已验证版本并报告 `RETAINED_PREVIOUS`，不同数据集安装失败不返回旧库句柄。归属不明或修改过的旧包隔离保留，模型、会话和 Memory 不删除。安装状态在 `DatabaseRuntime.installation` 和 `DatabaseInstallation` Logcat 标签中可查。

RAG 模式按当前问题查询本包向量索引，选中资料放入 user 消息。角色模式同样检索本包索引，只有审查事实经当前角色、cutoff 和预算审核后才进入 system；原始长场景不会直接作为角色记忆。旧“世界杯、文档、搜索”等外部路由与小说路径黑名单已经删除，输入这些词不会改变数据根目录。默认每轮检索；诊断向量开关关闭时也不会恢复旧数据源。索引初始化时加载到内存，每轮搜索这个索引，不是每轮执行 SQLite FTS。

完整角色卡按卡内声明的 cutoff 使用；更早回合仅保留基本身份与语气，由已审查事实支撑具体经历。缺时间状态时不把角色卡末场景当作当前任务、情绪或随身物品。原 V2 场景边与未导出的关系仍不自动授权，角色卡中的定向关系只能在卡的有效截止点使用。日志 collection 带 dataset/fingerprint，`databaseStatusCodes` 说明能力限制。

当前会话按 `dataset_id / dataset_fingerprint` 分开保存；重启后只恢复与当前包、角色及 cutoff 匹配的会话。不同包或不同 fingerprint 的历史和 Memory 不会进入当前摘要或追问。缺少包标识的旧会话仍保留，可从“清空对话”对话框进入“查看历史记录”只读查看。切换角色和清空对话保留归档；模型切换不改变数据包身份。

本期换库仍通过修改根 `database/`、生成数据并重新构建 APK 完成，没有设备内导入入口。活动回合持有当前包直到生成、重试和持久化结束；激活请求在这期间会被拒绝，调用方须先取消并等待回合结束。现有向量、角色与 V2 消费者在 fingerprint 变化后失效并重建。Phase 6 已清理可校验的设备旧内置副本；无法确认归属的文件仍保留，但不参与生产检索。旧角色测试 fixture 迁移仍在进行。Phase 5 设备样本与检索证据见 `docs/char_inte_plan/Phase_5_Device_Evidence.md`；其中仍有元叙述和含糊回答，不能将检索通过等同于所有回答质量达标。

原库、角色卡和 story 文件均保留原内容。在线 `V2EpistemicKnowledgeStore` 仅在传入已验证当前包时启用新来源政策；旧合成测试仍使用原 curated 政策。不要把本包导出复制到旧 Harness 目录。

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
