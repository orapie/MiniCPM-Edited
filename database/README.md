# 《三体Ⅱ：黑暗森林》Android 知识包

本目录是 Android 应用唯一的知识包构建输入。当前包 ID 为 `three_body_2`，默认角色为罗辑，默认剧情截止点为 `evt-125`。知识包协议、能力、受控路径及文件校验值均由 [manifest.json](manifest.json) 声明。

## 数据与授权

| 数据 | 用途 |
|---|---|
| [characters/](characters/) | 五张角色卡。身份、别名、口吻和可公开条目由角色模板及编译器消化。 |
| [story/](story/) | 剧情时间轴及可使用的 cutoff。 |
| [knowledge_base_v2/](knowledge_base_v2/) | SQLite、场景、证据、源认知边及语义索引清单，用于离线构建和审计。 |
| [reviewed/atomic_facts.json](reviewed/atomic_facts.json) | 三条独立审核的角色可知剧情事实。 |
| [runtime/android/](runtime/android/) | 审核知识、场景候选及向量的 Android 导出。 |
| [embedding/bge-small-zh-v1.5/](embedding/bge-small-zh-v1.5/) | BGE ONNX 模型、词表及固定版本配置。 |

`public_profile` 以现有 `identity_core` 条目路径为键，值为可触发的问句短语，如 `"identity_core.background[0]": ["做过什么工作"]`。同一路径须有 `evidence_refs`，并核对卡片 cutoff、来源和公开范围。新增职业、学习或其他已确认经历沿用这一结构；在原条目上写经过复核的第一人称句子，可供精确匹配的输出保护使用。移动数组条目时同步检查路径。

`scene_candidates.json` 中的 536 条场景候选和 768 条源认知边服务于检索及来源审计。独立审核的剧情事实另行导出为 `knowledge_fallback.json`，用于角色知情匹配、追问和门控。当前 system 渲染角色身份、口吻和可公开本人事实。筛选后的外部文本进入 user 消息，仍作为参考。

Android 使用 `runtime/android/vector/bge_index.json` 和包内 ONNX 查询编码器。SQLite 是离线来源，回合查询使用内存索引。旧格式包可使用 `runtime/android/vector/index.json` 所代表的 hash n-gram 路径。当前包的顶层 `embedding` 描述兼容 hash 索引；BGE 路径单独声明在 `paths`。当前能力为 `v2/vector=true`、`v1/timed_state=false`。

## 更新与校验

从仓库根目录运行这些命令。

```bash
python3 scripts/prepare_database_bundle.py --source database
python3 scripts/prepare_database_bundle.py --source database --check
bash ./gradlew :app:verifyDebugDatabaseAssets
```

生成命令更新运行时导出及根 manifest。只读的 `--check` 校验输入、产物、授权映射和哈希。Gradle 的 `prepareDatabaseBundle` 负责校验并复制现有产物，输出到 `app/build/generated/databaseAssets/database/`；APK 清单报告位于 `app/build/reports/database/apk_inventory.json`。编辑角色卡、剧情、审核事实或源库后，运行生成和校验命令再构建。完整的源数据重建见[离线工具](../three_body_2_kb_builder/README.md)。

## 安装与会话

启动时先在设备的 staging 目录校验 APK 内的数据包，再整包激活到 `<filesDir>/database/`。相同 fingerprint 的已验证包可复用。同一数据集更新失败时，可以继续使用已验证的旧包，查看 `DatabaseInstallation` 中的 `RETAINED_PREVIOUS` 和实际活动 fingerprint。

归档会话和 Memory 按数据集 ID 和 fingerprint 隔离。自动恢复、摘要和追问还受角色及剧情截止点限制。可从历史记录查看旧会话。更换数据包从仓库根目录生成受控产物并重建 APK。

运行和故障排查见 [RAG 指南](../RAG_Logic.md)。
