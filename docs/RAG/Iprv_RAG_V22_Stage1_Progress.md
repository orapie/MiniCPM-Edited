# V2.2 数据库接入：阶段 1 实施报告

阶段 1 已完成：根目录 V2.2 数据包已重新生成，Android 调试 APK 已包含新 SQLite 和相关数据，并在模拟器成功安装和激活。当前运行时仍使用从新库原文证据生成的 384 维 `hash_ngram` 过渡索引；SQLite 中 512 维 BGE 向量的查询编码器与语义检索属于阶段 2，尚未接入。

## 数据包和授权

- `scripts/prepare_database_bundle.py` 根据源 schema 路由 V2.0 和 V2.2；新增 `prepare_database_v22.py` 生成新库运行时导出和 manifest。生成时先在临时目录验证候选包，再替换受控文件，manifest 最后替换；替换异常时回滚已替换文件。构建任务将相关辅助脚本计入输入。
- V2.2 包包含源 SQLite、来源文档、语义索引描述、角色卡、剧情时间、候选导出、审核事实和过渡向量索引。当前数据包 fingerprint：`785262c2476a3bc9d6fa4889c139f914cf2944c22b5b4653cfb35c20bc6aea7c`；源 SQLite SHA-256 未变，仍为 `45c3ddedf21d72ad0399821f40a900632d845a77c2b9005d30bdf65627bd98c9`。
- 536 条原文证据经 `v22_evidence_projection.py` 映射为保留行边界的检索候选，768 条源边保留其来源状态，但不会被批量授权。过渡索引有 1,066 个候选片段和 3 条已审核短事实。
- 原有三条审核事实按原文、行号和既有审核依据映射到新证据；仅保留原先相同的短事实、角色和知情方式，不授予整条新证据或源库的 `approved` 边额外权限。已由 Codex 对照原文检查映射，独立人工审核仍待完成，输出元数据明确记录 `human_independent_review=pending`。
- Android `DatabaseBundleValidator` 按 V2.2 来源格式检查原文投影、角色边、旧审核记录、semantic 描述和过渡索引；V2.0 路径保留。SQLite 的模型权重来源真实性和 466 条新库 `approved` 边的角色知情正确性没有通过本阶段证明。

## 启动与验证

模拟器首次测试时，新数据包同步安装和校验阻塞应用启动，引发 Android 启动超时。现已把应用启动时的数据包初始化放到后台，主界面等待初始化后再绑定对应会话和检索器；不会为了恢复启动而关闭数据校验。

- `python3 scripts/prepare_database_bundle.py --check`：通过。
- Python 脚本全套：51 项通过，包含新包生成、替换失败回滚、来源和向量篡改及旧索引隔离检查。
- `:app:testDebugUnitTest :app:assembleDebugAndroidTest :app:verifyDebugDatabaseAssets`：通过；JVM 测试 159 项通过，APK 数据包资产 16 个，未包含旧 Harness 资产。
- Android 模拟器：10 项安装、更新、同库失败保留、跨库切换、回滚、会话隔离及检索边界测试通过；模拟器日志显示新 fingerprint 已激活，主界面进入 resumed 状态。测试前后原有 recent session 和 memory 文件的 SHA-256 一致。
- 没有运行 BGE 设备查询，也没有评估新包的模型回答或检索精度；这些不能从安装测试通过推断。

## 使用与后续

从仓库根目录运行 `python3 scripts/prepare_database_bundle.py --check` 可只读检查当前包。源文件更新后，通过 `python3 scripts/prepare_database_bundle.py --source database --dataset-version <版本>` 重新生成，再构建 APK；不要单独改 manifest hash。数据集 fingerprint 变化会隔离新旧会话，原会话文件保留。

阶段 2 需要为设备接入与 SQLite 向量同模型、同 revision、同 tokenizer 和查询指令的 BGE 查询编码器。阶段 3 再根据独立题集评估词项与语义融合。用户不要求简繁转换。
