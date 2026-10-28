# Phase 4：会话、Memory 与数据包隔离验收

日期：2026-10-28

## 结果和范围

当前包以 `dataset_id + dataset_fingerprint` 作为精确身份。会话、Memory、追问和一个生成回合都绑定此身份；改变内容即使保留同一数据集 ID，也不会恢复旧版本的摘要或追问。

本阶段实现计划中以 APK 更新为入口的数据包隔离。项目尚无设备内数据包导入或热切换界面；运行时直接调用初始化时，如果仍有回合持有数据包，将返回 `database_turn_active`，调用方需要先取消并等待回合结束，再重试。没有把等待耗时或请求失败当成用户授权。

## 会话与历史

`ChatSession` 新增可空 `datasetId / datasetFingerprint`；新会话的 `characterPackId` 与数据集 ID 一致。旧记录缺字段时仍能解析为历史记录，但不能成为新包的活动会话。

```text
<filesDir>/harness/sessions/
├─ recent_session.json                  # 旧版本记录；只读历史，不自动迁移或覆盖
└─ by-dataset/<dataset_id>/<fingerprint>/
   ├─ recent_session.json               # 此数据包版本的可恢复会话
   └─ history/<session_id>.json          # 会话归档
```

- 启动按当前数据包读取对应 recent 文件，并验证数据集 ID、fingerprint、`characterPackId`、角色 ID、cutoff 和 turn 角色一致性。
- 未命中或不兼容时，从当前 manifest 默认角色及 cutoff 开始；没有旧小说 ID 兜底。
- 用户可以在“清空对话”对话框选择“查看历史记录”。历史对话只在独立只读对话框展示，不会注入当前摘要或 Memory。
- 清空对话和切换角色移除当前包的 recent 入口，归档继续存在；写入通过同目录临时文件及重命名完成。
- 同一个 session ID 若在不同数据包中出现，历史列表按 session ID、数据集 ID 和 fingerprint 区分。
- 同一包内切换模型保留 session、摘要和历史显示；后续 turn 记录新的模型 ID。模型的下载/选择不参与数据库路径计算。

## Memory、追问与缓存

`MemoryRecord` 增加数据集 ID 与 fingerprint；`observeTurn()` 从其会话写入相同身份。`MemoryStore.search()` 和 `MemoryPromptSelector` 都要求显式的当前包身份及角色 ID，缺字段的旧 Memory 和其他角色的数据不会被选中。

当前 `MemoryPromptSelector` 没有接入生产 prompt；本阶段验证它的边界，不宣称已启用长期 Memory 注入。会话摘要直接来自通过身份校验的当前 session，编译前再次校验角色与 cutoff。

`CharacterFollowUpResolver` 额外要求当前包身份匹配，继续执行同角色、同 cutoff、上一轮、无外部来源与单目标检查。随后 V2 事实仍重新检查角色授权和获取时间；相同 claim ID 不会跨数据包复用。

`AndroidRagOrchestrator` 每次编译和列出角色时核对当前 manifest fingerprint。旧对象在包更换后返回 `database_handle_stale`；主界面在下一回合或选择角色前检测绑定变化，清空 native context、重新构造向量、角色与 V2 consumers，再恢复新包的匹配会话。向量和 V2 的缓存因此不跨包重用。

## 生成、切换与重试

- `DatabaseRuntime.beginTurn()` 发放包含当前 bundle/identity 的 lease，并由 generation job 的完成回调释放。生成、guard 重试与会话持久化均在同一 lease 内。
- `DatabaseRuntime.initialize()` 通过 `DatabaseTurnGate.atBoundary()` 检查没有活动 lease 才执行安装和发布。活动回合中尝试激活会得到 `database_turn_active`，旧安装状态不变。
- 每回合的模式、角色、cutoff、session、store、模型 ID 和模型 family 在开始时固定。重试只修改该回合的修复指令，继续使用同一编译请求和包。
- 用户切换角色或清空对话时，先发出 native 取消并 `cancelAndJoin()` 当前生成任务；再通过 native dispatcher 清空 context，最后更新角色/会话及界面。界面在转换期间禁止新输入。
- 数据包身份变更后，主界面在下一回合前持有新包 lease、等待旧 generation job 收敛、清理 context、重建消费者并装载匹配会话。普通结构化 prompt 继续走 `HarnessBackend.sendChatPrompt()` 的清空 context / 设置 system 流程。
- 应用进入后台时对活动生成发出 native 取消，再取消 generation job。

上面的边界不提供在线换库按钮，也不支持绕开 `DatabaseRuntime` 直接修改应用内部文件。用户当前的换库流程仍是替换仓库根数据、重新生成并构建 APK。

## 诊断与隐私

RAG/角色回合的 `RagTurnTrace` 及 debug 对话框包含数据集 ID、bundle fingerprint、向量索引文件 hash、向量候选 ID、已授权事实 ID、最终注入 ID、拒绝原因码和预算丢弃原因；不把问题或证据正文放入该 trace/JSONL。

已有的 `RagPromptDebug` 原始 prompt Logcat 功能默认关闭；仅在 Debug 构建且 `rag_debug` SharedPreferences 中 `raw_prompt_logcat=true` 时启用。应用内单条回答的 Final Prompt 查看仍然可用。显式启用原文日志时，其内容会包含问题和证据，使用者应按该调试用途处理。

## 实际验证

```bash
bash ./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:verifyDebugDatabaseAssets
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.minicpm_v_demo.harness.data.database.DatabaseSessionIsolationDeviceTest,com.example.minicpm_v_demo.harness.data.database.DatabaseRetrievalDeviceTest,com.example.minicpm_v_demo.harness.data.database.DatabaseBundleInstallerDeviceTest \
  com.example.minicpm_v_demo.test/androidx.test.runner.AndroidJUnitRunner
```

- JVM 全量 **152 项通过、0 失败、0 跳过**。
- ARM64 `Pixel_9_Pro_XL` / `emulator-5554` 专项 **36 项通过**：8 项会话隔离、14 项检索与旧文件隔离、14 项安装器回归。
- APK 检查：13 个当前知识文件、3 个程序资源，旧 `assets/harness/` 文件为 0。
- 最终 APK 主界面启动命令成功；当次启动使用 `three_body_2`，安装状态 `UNCHANGED`。
- 设备已有旧会话在覆盖安装和设备测试前后 SHA-256 均为 `cdece280231bc6741d8c96f235b1a06960cd74d1d3d6ddb614dc52b55080873f`。两份已下载模型文件的名称、大小与修改时间保持一致；没有卸载或清除应用数据。

| 覆盖场景 | 结果 |
| --- | --- |
| 旧会话 → A → B → A | 旧会话文件字节不变且可从历史 API 读取；A/B 分别恢复自己的摘要和 session；切回 A 后恢复 A |
| 相同角色、cutoff 与事实 ID | B 不采用 A 的追问目标；B 自己的上一轮目标可再次经过授权使用 |
| 同数据集不同 fingerprint | 新版本不会恢复旧版本 recent、摘要、Memory 或 follow-up，旧记录仍可查看 |
| Memory 与角色 | 按数据集、fingerprint 和角色过滤；无身份旧记录不会被 prompt selector 选择 |
| 篡改与未知值 | fingerprint、角色或 cutoff 不匹配的会话不保存/不恢复 |
| 模型切换 | 会话保存与恢复模型 ID 更新；MiniCPM Text/Qwen 模板编译均使用同一 bundle fingerprint 和已授权事实 |
| 活动回合 | 单元测试中，切换在 lease 活动期间被拒绝；任务取消并 join、lease 释放后可切换；重复 close 安全 |
| Android 实际 runtime lease | 使用真实 `DatabaseRuntime.beginTurn()` 持有期间调用 `initialize()` 返回 `database_turn_active`，原句柄保留；释放后重新初始化成功 |
| 旧缓存与追踪 | 已加载的 A orchestrator 在 B 激活后拒绝编译与列角色；B 的新 trace 包含 B 身份、索引 hash、候选、授权、注入与拒绝原因，trace 不含查询或事实正文 |
| Phase 3 / 旧库 | 原有 28 项设备用例再次通过，包括当前 facts 进入最终 prompt、角色/时间授权和旧文件 OPEN 事件为 0 |

设备场景中的 A/B 数据包和对应会话在 app 专用 cache 测试目录构造，使用相同角色、cutoff 与 claim ID 但不同数据集身份；没有覆盖设备真实历史或模型。

## 尚未验证或尚未完成

- 没有在两份实体模型之间执行原生模型切换与真实多轮回答；模板/会话级测试不能代替该设备回归。Phase 5 需用实际安装的模型验证回答、上下文清理和切换体验。
- 设备内数据包导入、热切换按钮及在活动生成时自动排队切包不属于本期 APK 换库范围。
- Phase 5 仍需完整端到端验收和文档更新；Phase 6 仍需按文件清单删除迁移后失效的旧资源、旧工具与死代码。
