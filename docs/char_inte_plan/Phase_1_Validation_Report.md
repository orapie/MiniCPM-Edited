# Phase 1：向量索引与 APK 唯一数据源打包验收

日期：2026-10-27

## 实施内容

1. `scripts/prepare_database_bundle.py` 新增确定性的向量生成，算法与 Kotlin `HashEmbeddingModel` 相同：384 维、2–4 gram、BLAKE2b 64-bit、大端桶选择、digest[4] 符号位、L2 归一化。处理 UTF-16 n-gram 切分，保持 JVM 字符串规则。
2. `database/manifest.json` 版本更新为 `2026-10-27-phase1`，声明 `vector=true`，纳入 embedding 元数据、向量索引路径与文件 hash；包 fingerprint 同时覆盖配置和内容。
3. 向量索引含 128 条记录：125 条 `scene_candidate`、3 条 `reviewed_fact`。每条带 dataset/version、claim ID、源库 hash、证据行号、`authorization_required=true`；reviewed 条目还带角色和 parent claim。候选命中不会自动成为角色知识。
4. Kotlin `DatabaseVectorContractValidator` 校验索引 schema、数据集、embedding 配置、维度、有限数值、归一化、原文一致性、证据范围和记录完整性。Python 只读校验还逐条重新计算索引并比较，防止旧索引被误打包。
5. `prepareDatabaseBundle` 只从根 `database/` 校验并复制 manifest 受控文件，输出 `app/build/generated/databaseAssets/database/`；不改写输入，不包含源脚本、报告目录或独立原文目录。缺失/损坏/不兼容数据导致构建失败，无旧库回退。
6. `prepareProgramAssets` 单独保留角色通用模板及 `ref_audios/`；main assets 不再包含原始整个 Harness 树。通过 AGP `addGeneratedSourceDirectory` 显式关联任务，不依赖猜测资源合并顺序。
7. 新增 `verifyDebugDatabaseAssets` 与 `scripts/verify_database_apk.py`，按最终 APK 的真实字节核对完整清单；处理 AAPT 对中文 ZIP 文件名 UTF-8 标记的兼容情况，确保参考音频并未被误判或丢失。

## 打包结果

| 项目 | 已验证结果 |
| --- | --- |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 当前数据集 | `three_body_2` |
| 版本 | `2026-10-27-phase1` |
| fingerprint | `4d972793125e2867292b3b9944614d3060f1ffce54c716306ba8842f06605a44` |
| 当前知识文件 | 13 个，全部位于 `assets/database/` |
| 程序资源 | `assets/prompts/roleplay_system.prompt`，两份 `assets/ref_audios/` WAV |
| 旧 Harness 资源 | 0 个 |
| 旧 `rag/index.json`、旧六角色卡、旧 V1/V2 | 均不在 APK |
| 包内文件一致性 | 与根数据库入口与受控文件逐字节一致 |
| APK 清单报告 | `app/build/reports/database/apk_inventory.json` |

## 实际执行与验收项目

```bash
python3 scripts/prepare_database_bundle.py --source database --check
python3 -W error::ResourceWarning -m unittest discover -s scripts/tests -v
bash ./gradlew :app:testDebugUnitTest :app:verifyDebugDatabaseAssets
```

- JVM 完整单测 120 项通过。新增验证 Python 生成的职业事实向量与 Kotlin 实时 embedding 的每一维一致（误差 1e-12），并确认“大学社会学”查询首条命中已审查职业事实；索引维度被篡改后，即使刷新文件 hash 也被拒绝。
- Python 共 15 项测试通过，覆盖输出确定性、源库不变、场景关联不提升、证据审查、索引来源/维度/归一化、路径越界、过期输出、生成目录所有权与替换清理，以及 APK 夹带旧库/含过期新库文件的负向场景。
- 第二次运行构建观察到 `prepareDatabaseBundle UP-TO-DATE`。
- 将构建生成目录移到专用临时位置后再次执行验收，任务重新生成完整目录，APK 校验仍通过；没有读取其他数据目录恢复。
- 临时增加一个非受控根数据库输入探针后，Gradle 任务重新执行；探针不会进入受控清单或 APK，验证后已移除。
- 专用临时测试证明生成目录中的上个包残留会被清除；源码/索引损坏会在替换既有生成目录前失败；非任务所有的目录拒绝替换。

## 尚未实施的运行时部分

本阶段没有安装或启动新 APK，没有执行设备模型回归。新包安装器、`<filesDir>/database/` 激活、在线检索器切换、旧会话隔离及最终删除按 Phase 2–6 实施。

当前原有 `AssetBootstrapper` 仍要求旧 Harness 知识资源，而这些资源已被排除打包。全新安装这一中间构建会使角色/RAG 初始化不可用；升级设备可能仍保留旧副本，也不表示已使用新库。普通模型生成后端未改动。后续应先完成新包安装与消费者切换，再进行用户问答验收，不能把本报告的打包通过等同于应用检索通过。
