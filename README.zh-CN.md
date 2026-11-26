# MiniCPM-Edited

在 Android 设备上运行本地 GGUF 模型，支持普通对话、RAG 和角色对话。内置《三体Ⅱ：黑暗森林》知识包，角色模式可以选择罗辑、史强、章北海、丁仪或庄颜，并按剧情截止点回答。

## 构建与运行

用 Android Studio 打开仓库根目录。构建需要 Android SDK 36.1、NDK `27.0.12077973`、CMake `4.1.2` 和 Python 3。应用版本为 `2.5`（`versionCode = 17`），最低支持 Android 7.0（API 24），目标版本为 API 36。APK 仅支持 Android `arm64-v8a`，适配 4 KB 和 16 KB 内存页。

```bash
python3 scripts/prepare_database_bundle.py --check
bash ./gradlew :app:assembleDebug :app:verifyDebugDatabaseAssets
```

调试 APK 位于 `app/build/outputs/apk/debug/MiniCPM-Edited-debug.apk`。应用安装后在手机桌面显示为 **MiniCPM**。在模型管理页下载并选中聊天模型。文本 GGUF 权重从 ModelScope 下载，也可继续使用本地模型目录中的兼容文件。可用模型和能力以[聊天模型注册表](app/src/main/java/com/example/minicpm_v_demo/ModelInfo.kt)为准。

使用双 Agent 时，先在模型管理页选择并备齐 Agent A 的纯文本 GGUF，再启用“双 Agent”。它默认关闭，模型选择由[独立注册表](app/src/main/java/com/example/minicpm_v_demo/harness/attribution/AttributionModelRegistry.kt)管理。双 Agent 会额外使用本地内存。

## 打包发布

在 Android Studio 中选择 **Build → Generate Signed Bundle / APK**，沿用 v2.4 的发布密钥签名，以支持覆盖安装。将签名后的 APK 命名为 `MiniCPM-Edited-v2.5.apk`，作为 GitHub Release `v2.5` 的附件，标题为 `MiniCPM-Edited v2.5`。若选择 Android App Bundle，生成的文件名为 `MiniCPM-Edited-release.aab`。

### v2.5 更新

- 统一使用通用 ARM64 CPU 后端，修复 ARMv8.6 优化库预载引起的启动闪退。
- 提供 7 个文本 GGUF 模型，统一从 ModelScope 下载。支持并行下载和单独取消，Agent A 和 Agent B 的列表都会为已下载模型显示 **Installed** 标识。
- 移除聊天页工具栏标题，给窄屏上的运行模式按钮留出空间。

## 对话模式

| 模式 | 运行方式 |
|---|---|
| 普通对话（`OFF`） | 聊天模型直接处理用户输入。 |
| `RAG` | 在当前知识包内做向量检索，把相关材料放入聊天模型的 `user` 消息。 |
| 角色 + RAG（`CHARACTER_RAG`） | 按当前角色和剧情截止点编译身份、口吻及公开经历；外部参考按角色和时间边界筛选后送入回答链路。 |

当前内置包使用 BGE ONNX 查询编码器和 512 维 BGE 向量索引。384 维 hash n-gram 索引供旧格式包兼容使用。

角色模式的 Agent A 只给参考片段标注人物归属。A 的 `user` 消息包含 JSON，A 返回受约束的 JSON 归因数组。Kotlin 校验后默认将原参考片段和标签编成文本，交给 Agent B。调试版支持对照 JSON 格式的 B `user` 消息。A 关闭或失败时，B 沿用归因前的两条消息。单一本人问题已由一条公开卡片事实覆盖时，B 使用角色卡事实回答，并跳过外部参考和 A。

## 项目结构

| 路径 | 内容 |
|---|---|
| [app/](app/) | Android 界面、Harness 编排、双 Agent、会话和 JNI。 |
| [llama.cpp-omni/](llama.cpp-omni/) | 本地文本推理的原生依赖。 |
| [database/](database/) | 运行时知识包的唯一构建输入，含角色卡、剧情、审核事实和索引。 |
| [three_body_2_kb_builder/](three_body_2_kb_builder/) | 从小说原文生成角色卡、SQLite 知识库和语义索引的离线工具。 |
| [scripts/](scripts/) | 数据包生成、校验及 APK 检查。 |

构建把 manifest 所列文件复制到 `app/build/generated/databaseAssets/database/`，再打包为 `assets/database/`。启动时校验并安装到设备的 `<filesDir>/database/`。角色提示模板作为独立程序资源打包。更新知识包从根目录的 `database/` 开始，详见[数据包维护说明](database/README.md)。

## 测试与文档

```bash
bash ./gradlew :app:testDebugUnitTest
```

- [RAG、双 Agent 与日志排查](RAG_Logic.md)
- [角色设备评测入口](app/src/androidTest/java/com/example/minicpm_v_demo/harness/character/RoleplayBaselineDeviceTest.kt)与[测试用例](app/src/androidTest/assets/cases.jsonl)。
