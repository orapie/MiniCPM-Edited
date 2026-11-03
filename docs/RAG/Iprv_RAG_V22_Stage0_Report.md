# V2.2 新数据库接入：阶段 0 审计与评估基线

日期：2026-11-03。范围是 `database/` 当前新源库的只读检查、故障注入和可复现的初始样本。**没有接入 Android、没有重写数据库、没有重建或更换 embedding，也没有完成独立人工角色知识审核。** 本阶段的工具和初始样本已建立，但受审核覆盖和现有 Android 包不兼容限制，阶段 0 的完整质量验收尚不能标为通过。

## 1. 可复现操作

在项目根目录执行：

```sh
python3 scripts/audit_semantic_database.py \
  --snapshot scripts/fixtures/semantic_v2_2/source_snapshot.json \
  --output docs/RAG/evidence/semantic_v2_2_audit.json
python3 scripts/evaluate_semantic_baseline.py \
  --output docs/RAG/evidence/semantic_v2_2_lexical_baseline.json
python3 -W error::ResourceWarning -m unittest discover -s scripts/tests -p 'test_*semantic*.py' -v
```

审计器只读打开 SQLite、读取同一数据目录的 JSONL/清单；禁止把输出报告写到输入 `database/` 内。快照记录当前 SQLite 和按 evidence ID 排序的向量字节摘要，用于发现后续更改；它从当前库生成，不能作为向量由声明模型产生的独立证明。若不传快照，工具会记录未比对可信快照的警告。若换库或更新源数据，应重新审查、重新标注并生成新快照，不能直接改旧 hash 掩盖变化。

基线工具只复制 SQLite 到系统临时目录，并调用现有 `three_body_2_kb_builder/scripts/query_evidence.py` 的 PC 词项检索与 `approved` 政策；**不加载 BGE，也不是 Android 运行结果**。运行结束临时副本被清理。

## 2. 只读审计结论

[结构与完整性报告](evidence/semantic_v2_2_audit.json) 的源库结构检查通过，0 个错误、2 个警告。该通过只代表列出的结构检查通过，**不表示 Android 包可用、标注可信或召回准确**。

- SQLite 完整性正常；536 条证据与 536 条活动索引向量一一对应，维度 512，`float32_le`，单条 2,048 字节，范数约 1。索引 metadata、每条检索文本 hash、内容总 hash、向量字节快照一致；没有因此证明向量确由所声明 BGE 模型产生。
- 校验 evidence / claim / scene / event / character / cutoff 关联、角色边时间、source 文档 hash、各 JSONL 与 SQLite 的对应投影。来源引用按**每行去前后空白再拼接**，验证引用出现在声明行范围内且 quote hash 一致：长行切分使部分 quote 只覆盖范围内的子串，不能错误要求引用等于整行或由此声称已确定原始字符偏移。跨 cutoff 的 quote 拒绝作为角色可用资料，不能因截断文字就假设可以安全截断证据。
- 536 条 claim 的 `statement` 与 quote 不完全相等，503 条 `fact` 末尾为省略号；这是派生字段的观察，不构成错误率。标题、`fact`、预测问题不能自动等同原文或角色认知。
- 768 条角色边中 `approved=466`、`candidate=302`。批准状态是新库的源标注，尚未逐条核实角色是否实际知情，不能自动视为旧政策的人工审核结果。
- 两项警告：根 bundle 清单中 8 个文件 hash 过期；现有 Android 工具只接受 V2 `2.0.0`，该源库为 `2.2.0`。独立执行 `python3 scripts/prepare_database_bundle.py --check` 当前在 `characters/ding_yi.json` 的 hash 检查处失败。保持当前清单不变，留待阶段 1 正式适配和生成。

## 3. 旧审核事实在新库的位置

| 旧事实 | 新库候选 evidence | 原文核对 | 新边状态与迁移 |
| --- | --- | --- | --- |
| 罗辑与杨冬是高中同学 | `evd-27cb1755df99f56c` | 行 15–23 包含旧审核引文 | 罗辑边为 `candidate/unreviewed`；仅定位，不自动授予知识 |
| 罗辑教社会学 | `evd-3056d0c06fe12d9c` | 行 24–31 包含旧审核引文 | 罗辑边为 `approved/believed`；仍需对新 evidence 范围复核 |
| 叶文洁提出的公理 | `evd-dc393efd32a0b95d` | 行 44–49 包含旧审核引文 | 罗辑边为 `candidate/unreviewed`；仅定位，不自动授予知识 |

三条旧 parent ID 和 evidence ID 均不在新库。候选由旧审核引文与新证据行号/正文匹配得到，不能自动授权新片段中的其他旁白。来源检查与角色实际知情、截断边界和来源事实的含义是不同问题；阶段 1 应保留旧审核依据，逐条复核后显式迁移。

## 4. 初始标注与基线

[初始样本](../../scripts/fixtures/semantic_v2_2/evidence_cases.json) 为 12 个合成问题：开发集 4 个，验证集 8 个（其中 3 个无答案或未来问题）；覆盖关系、职业、物品、事件、第三人、同义改写和无答案。每个正例记录 evidence ID、原文来源和行范围、可定位的答案片段；负例只是待核验假设，不证明库中不存在任何其他可能有关的资料。标注由 Codex 对照源文本初审，**不是独立人工认知审核，也不构成角色授权**。

开发集和验证集按目标角色、正例场景和新库 `entity_ids` 字段隔离，规则由基线脚本检查；同一证据的改写放在同一集合。数据库自动标注的实体可能漏人，检查通过并不能证明真实文本中不存在跨组实体或表述泄漏；还需独立人工复核并扩大题集。标签非穷举，下面的“命中”只指预先标注的一个 evidence ID，不能把未列出的合理候选一律判错。

[PC 词项基线](evidence/semantic_v2_2_lexical_baseline.json) 使用源构建器的 `lexical` + `approved`、top 20：

| 集合 | 正例 | 标注 evidence 命中 top 3 | 命中 top 20 |
| --- | ---: | ---: | ---: |
| 开发 | 4 | 1 | 1 |
| 验证 | 5 | 4 | 5 |

开发集中同学和公理对应的新边为 `candidate`，因该 PC 策略仅选 `approved` 不会返回；职业改写未召回目标。验证集的两条章北海问题使用同一证据，不能看作独立事实；一个改写排第 4。三条负例分别返回 9、3、1 个候选：候选非空并不自动等于错误注入或错误回答，须逐条审核；已标注的未来目标 evidence 未出现在该例候选里。此题集太小，不能据此调权或声明泛化质量。

## 5. 测试和未完成项

- 新增 14 项审计/基线测试通过；在临时 SQLite 副本中覆盖错误长度、NaN、非单位范数、删除向量、多个活动索引、伪造引用、未知/未来获取时间、JSONL 与 SQLite 不一致、维持单位范数的向量篡改、跨组泄漏和旧 fingerprint 等；验证工具不会把报告写入源数据库。源 SQLite 测试前后 hash 一致。
- 对整个 `scripts/tests` 的运行得到 33 项、6 项失败和 5 项错误；失败均为旧测试仍依赖 V2 `2.0.0` 或旧 bundle hash，在预期的旧兼容检查之前被当前源库版本/过期 hash 拒绝。该结果不是阶段 0 新工具通过，也不表示 Android 已能构建；阶段 1 应更新协议和适配相关测试，而非跳过检查。
- 待独立审核的角色认知、来源片段及负例见样本中的 `label_status`。当前未覆盖的更多角色、关系、物品、历史追问和多数据集，必须在阶段 1–3 扩充；严禁把本次少量评估结果作为生产权重或准确率结论。
- 新库接入、BGE 设备编码、Android 语义查询、词项融合、真实设备与模型生成均为后续阶段；不得修改旧 bundle hash 后假装已接入。
