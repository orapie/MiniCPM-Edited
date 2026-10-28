# Phase 2：设备数据库安装与激活验收

日期：2026-10-27  
范围：`Database_Path_Unification_Plan.md` 的 Phase 2。安装已完成；实际检索消费者接入属于 Phase 3。

## 当前运行路径

```text
仓库 database/
  → 构建校验及 generated assets（Phase 1）
  → APK assets/database/
  → DatabaseBundleInstaller
      → <filesDir>/.database.install-UUID/：复制、hash/协议/引用/向量校验
      → <filesDir>/.database.previous/：激活前的旧目录及恢复点
      → <filesDir>/database/：重新验证后发布的当前包
  → DatabaseRuntime.installation / requireBundle()
```

只从显式数据源打开 `manifest.json` 和其中声明的文件，不扫描 `character/` 或旧 Harness 数据目录。
程序模板另由白名单安装至 `<filesDir>/program/prompts/roleplay_system.prompt`；TTS 仍读取 APK 中保留的参考音频。
Harness sessions、Memory、settings、diagnostics 目录继续存在，不属于知识安装源。

## 实现文件与职责

| 文件 | 实施内容 |
| --- | --- |
| `harness/data/database/DatabaseBundleSource.kt` | APK 源固定 `database/` 命名空间；显式目录源用于离线及测试；拒绝越界路径 |
| `harness/data/database/DatabaseBundleInstaller.kt` | staging、整包校验、目录切换、回退、恢复、串行锁及状态结果 |
| `harness/data/database/DatabaseRuntime.kt` | 发布本次选定包的结果；无有效包时拒绝提供句柄 |
| `harness/data/AssetBootstrapper.kt` | 生产启动不再递归复制旧 Harness 或安装旧 V1/V2；只准备通用目录/模板并初始化新包 |
| `MiniCPMApplication.kt` | 输出安装状态、数据集、实际/请求 fingerprint、原因码，不输出用户问题或资料正文 |
| `harness/rag/AndroidRagOrchestrator.kt` | 关闭旧生产构造入口，等待 Phase 3 注入新包消费者 |
| `MainActivity.kt`、中英文 strings | 初始化不可用时阻止角色/RAG 请求与模式切换，说明当前状态 |
| `app/src/sharedTest/java/.../DatabaseInstallerScenarios.kt` | JVM 和 Android 共用 12 个真实文件系统安装场景 |
| JVM/Android `DatabaseBundleInstaller*Test.kt` | 主机与设备测试入口，Android 另覆盖应用启动和旧工厂封锁 |
| `.gitignore` | 允许版本控制源码树中 `harness/data` 下的新测试，避免通用 data 忽略规则漏掉测试源码 |

旧 `AssetBootstrapper.bootstrapFromDirectory()` 暂保留为既有 JVM fixture helper，不在生产 bootstrap 调用链内。
其旧 bundle 安装逻辑和旧测试将在后续消费者迁移、覆盖移植后按 Phase 6 删除。

## 激活与故障处理

1. **安装串行化。** 进程内锁加 `<filesDir>/.database.install.lock` 文件锁，避免并发安装交错操作。目标、恢复目录、锁文件拒绝符号链接。
2. **复制前恢复。** 若上次进程在 active→previous 后退出，下一次先恢复 previous；若两个目录都存在，依据完整校验结果保留有效当前包，或恢复有效 previous。未知/修改过的数据隔离保留。
3. **同包复用。** 每次先复核已安装包的文件 hash、协议和完整清单；fingerprint 一致时只读取源 manifest，不重新复制有效 payload。已安装文件损坏时不能走此快路径。
4. **staging 校验。** 只复制清单声明文件，文件写完执行 `fd.sync()`；完成复制后移除 staging 所有权标记，再运行统一 validator。校验包含受控文件、源导出身份、证据/授权、向量契约和精确文件清单。
5. **整目录替换。** 验证成功才执行 active→previous、staging→active，并再次校验。新包未声明的旧受控文件不会残留在 active。
6. **失败处理。** 激活失败尝试恢复 previous。同 `dataset_id` 且保留包通过完整复核时，返回 `RETAINED_PREVIOUS`，同时报告请求与实际 fingerprint；另一数据集失败或 manifest 身份无法解析时返回 `Unavailable`，不把旧包当作新包发布。
7. **清理边界。** 只清理本安装器拥有的 staging 和可验证旧受控包。修改过或包含额外用户文件的目录重命名至 `.database.preserved-UUID`；被拒绝的激活目录可保留至 `.database.rejected-UUID`。这些目录不注册为检索候选，也不会自动读回。旧 Harness 副本此阶段不删。
8. **提交后清理。** 新 active 已通过校验后，previous 的清理失败只报告 `previous_cleanup_deferred`，不能把部分清理的 previous 回滚为当前包。

目录切换采用同一文件系统上的重命名和下次启动恢复；测试模拟了复制/重命名中断及 rename 失败。
没有进行突然断电或文件系统故障试验，也不把该实现宣称为支持运行中热切包；活动回合与会话隔离仍属于 Phase 4。

## 验证结果

### 构建与主机测试

```bash
bash ./gradlew :app:testDebugUnitTest :app:verifyDebugDatabaseAssets :app:assembleDebugAndroidTest
```

- JVM 全量 **132 项通过，0 失败，0 跳过**，其中安装器 12 项。
- Debug APK 和 Android 专项测试 APK 构建成功。
- 最终 APK 校验：13 个知识文件全部在 `assets/database/`，3 个程序资源，旧 `assets/harness/` 文件为 0。
- 未修改 Phase 1 的源库、索引内容或 fingerprint；当前仍为 `three_body_2 / 2026-10-27-phase1`。

### Android 文件系统与启动测试

设备：`emulator-5554`，ARM64 模拟器。

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.minicpm_v_demo.harness.data.database.DatabaseBundleInstallerDeviceTest \
  com.example.minicpm_v_demo.test/androidx.test.runner.AndroidJUnitRunner
```

**14 项通过。** 前 12 项在测试专用 cache 子目录中操作，避免覆盖设备真实数据库；另两项验证真实 Application 启动安装，以及生产工厂无法重新打开旧文件。

| 场景 | 核对内容 |
| --- | --- |
| 首次安装及重复启动 | 当前路径、fingerprint、3 条授权样本；重复安装只向源请求 manifest |
| 同数据集升级 | 新包激活，旧清单已移除文件消失，模型/会话/Memory 哨兵字节不变 |
| 损坏更新 | hash 错误拒绝激活，同库显式保留上一有效版本及实际 fingerprint |
| 失败跨库切换 | 返回不可用，不发布旧数据集句柄 |
| 成功跨库切换 | 当前目录与句柄均属于新数据集 |
| 已安装 payload 损坏 | 从当前 APK 修复，损坏旧目录隔离保留 |
| 激活 rename 失败 | 回滚上一有效包，报告原因 |
| 重命名中断 | 从 previous 恢复后正常复用 |
| 归属不明文件 | 不进入新 active，隔离目录中原字节仍在 |
| 无法解析 manifest | 不猜测所选数据集，不请求其他数据文件 |
| 复制中断 | 已标记的残留 staging 被清理，新安装完成 |
| 并发安装 | 两实例返回 INSTALLED/UNCHANGED，无目录交错 |
| 真实启动 | `DatabaseRuntime` 发布 `filesDir/database`，程序模板存在，授权事实数为 3 |
| 旧生产入口 | 用拒绝访问 filesDir 的 Context 调用工厂，返回迁移未完成，而非尝试创建旧 stores |

实际启动日志已观察到：

```text
database status=INSTALLED dataset=three_body_2
active=4d972793125e2867292b3b9944614d3060f1ffce54c716306ba8842f06605a44
requested=4d972793125e2867292b3b9944614d3060f1ffce54c716306ba8842f06605a44
reason=none
```

实际 `filesDir/database/` 文件清单与包内 13 个知识文件一致。覆盖安装前后，两份现有模型保持同名、大小和修改时间：

- `MiniCPM-V-4_6-Q4_K_M.gguf`：529,101,504 字节。
- `mmproj-model-f16.gguf`：1,108,746,944 字节。

设备采用 `install -r`，未卸载或清除应用数据。会话/Memory 保留由安装器哨兵测试验证；没有读取或比较设备真实会话正文。主界面启动命令成功，未在本阶段运行模型生成或问答质量回归。

## 当前限制与下一阶段入口

**安装完成不等于在线检索完成。** 当前 `AndroidRagOrchestrator.fromContext()` 先检查新包句柄，然后明确返回“消费者迁移待完成”；MainActivity 对角色/RAG 给出不可用提示，防止旧升级数据继续用于回答。OFF 普通推理后端未修改。

Phase 3 应从 `DatabaseRuntime.requireBundle()` 取得同一包，构造向量、角色卡、授权事实及能力适配器，改写旧路径 factory 并删除本阶段临时封锁分支。之后才能验证新库特定事实实际进入 prompt。Phase 4 继续完成会话、默认角色和缓存隔离；旧文件删除仍按 Phase 6 执行。
