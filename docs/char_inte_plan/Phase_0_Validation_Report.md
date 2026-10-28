# 第一阶段（Phase 0）实施与验证记录

日期：2026-10-27

## 已交付内容

- `database/manifest.json`：数据集 `three_body_2`、版本 `2026-10-27-phase0`、默认角色 `luo_ji`、cutoff `evt-125`、受控文件 hash 与跨语言 fingerprint。
- `harness/data/database/DatabaseDataPaths.kt`：显式仓库/设备根入口；拒绝路径穿越、绝对路径、Windows 路径和越界符号链接。缺少新库不搜索旧 `character` 或 `harness/rag`。
- `DatabaseBundleManifest.kt` / `DatabaseBundleValidator.kt`：文件 hash、包 fingerprint、角色/事件/cutoff 引用、源库 hash、候选证据、审查来源与授权边的只读校验。
- `scripts/prepare_database_bundle.py`：只读 SQLite；核对源 manifest、SQLite/graph JSONL、嵌入 corpus、证据范围和 SHA-256，分离场景候选与已审查短事实，生成确定性产物。
- `database/reviewed/atomic_facts.json`：明确记录 Codex 对话上下文审查、原文行号、获得方式与核验理由；不声称经过额外人工复核。
- `database/runtime/android/`：候选导出、授权导出、兼容审计报告。

指纹定义为 UTF-8 的 `database-bundle-v1` 前缀加排序的元数据、capabilities、paths、受控文件 hash 行；不包含 fingerprint 自身。Python 生成与 Kotlin 校验使用同一规则。

## 数据审计结果

| 项目 | 结果 |
| --- | --- |
| 源角色/事件 | 5 / 125 |
| 源 claims / knowledge_edges / relations | 125 / 229 / 8 |
| 原场景长度 | 最短 431、最长 2534 字符；125 条全部超 180 字符预算 |
| 源事实来源 | `canon / accepted / source_span_verified`，原值保留 |
| 源边语义 | 229 条 `character_scene_relevance / available`，全部只作候选 |
| story 批量 direct 边 | 229 条，不自动转换为新授权边 |
| 已审查短事实/新授权边 | 3 / 3，均来自明确对话而非旁白整段 |
| 当前能力 | V2 已准备；vector、V1、timed_state 显式不支持 |

授权样本：

| ID | 内容与获得方式 | 证据 |
| --- | --- | --- |
| `reviewed-luo-ji-classmate` | 罗辑自述是杨冬的高中同学；direct | `three_body_2.txt:L20-L20` |
| `reviewed-luo-ji-profession` | 罗辑自述以前研究天文学、当时在大学教社会学；direct | `three_body_2.txt:L26-L26` |
| `reviewed-luo-ji-civilization-axioms` | 叶文洁向罗辑提出两条宇宙社会学公理；reported | `three_body_2.txt:L47-L47` |

第三项取得时间为原文第 47 行：在 `evt-001`（sequence=31）不可用，在 `evt-002`（sequence=56）可用。
三项均未授权给史强等其他角色。对话样本之外的全库知情审查、早期 profile 时间范围审查仍待后续数据完善。

## 可重复验证

从仓库根运行：

```bash
python3 scripts/prepare_database_bundle.py --source database
python3 scripts/prepare_database_bundle.py --source database --check
python3 -m unittest discover -s scripts/tests -v
bash ./gradlew :app:testDebugUnitTest
```

Python 测试覆盖确定性输出、源库不变、原边未被提升、原文篡改、超长事实、缺源不回退、路径与符号链接、只读校验。
Kotlin 新增 9 项测试覆盖跨语言 fingerprint、候选/授权分离、具体角色/cutoff、文件与导出篡改、无索引的 capability、越界路径与旧目录不回退。
旧 contract 测试 2 项改为读取当前 `database` V2 manifest 与最小合成 V1 fixture，不再读取根 `character` 数据包。

实际执行结果：Python 9 项通过（将未关闭 SQLite 的 ResourceWarning 作为错误检查后仍通过）；完整 JVM 单测共 21 个 suite、118 项通过，0 failures / 0 errors / 0 skipped；`:app:assembleDebug` 构建成功。新包生成及 `--check` 均成功。未安装或执行设备模型测试，因为本阶段尚未接入运行时消费者。

## 阶段边界

本阶段新增独立校验能力与后续接入输入，不修改 SQLite/角色卡/story 源数据，不安装 APK 或删除旧文件。
现有应用的 `AndroidRagOrchestrator`、`AssetBootstrapper` 和在线 `V2EpistemicKnowledgeStore` 尚未切换。
3 条授权样本证明协议与时点边界可以贯通，不代表 125 条场景已完成角色知情审核，也不代表当前 App 已采用新库。

下一阶段是计划 Phase 1：构建只从根 `database/` 生成 APK 数据包，并补齐当前包向量索引的生成/校验；在线数据源切换和最终删除仍按后续阶段执行。
