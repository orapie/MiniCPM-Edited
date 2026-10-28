# MiniCPM-V-demo-Android

`MiniCPM-V-demo-Android` 是一个端侧大模型 Android demo。当前主工程已经不只是原始 MiniCPM-V 聊天页，而是同时包含：

- Android 本地 GGUF 模型加载、下载、切换和推理。
- MiniCPM-V 图片理解和 `MiniCPM-V-4.6` 视频理解入口。
- VoxCPM2 TTS 页面。
- Harness 编排层、模型注册表、模型文件管理和下载适配。
- Android 侧 RAG、角色 Prompt、会话保存和 Memory 记录。
- Character+RAG 的 V2「事件和认知状态」知识图谱：按角色认知、剧情截止点和审核状态过滤后注入角色上下文。

## 项目概览

- 项目类型：单模块 Android Application
- 应用包名：`com.example.minicpm_v_demo`
- 当前版本：`2.3`
- 最低系统版本：Android 7.0 (`minSdk 24`)
- 目标系统版本：Android 16 (`targetSdk 36`)
- 支持 ABI：`arm64-v8a`
- 主要技术栈：Kotlin、Android ViewBinding、JNI、CMake、llama.cpp-omni

## 当前能力

- 本地 GGUF 文本对话、视觉模型图片输入、MiniCPM-V 4.6 视频理解及 VoxCPM2 TTS。
- OFF、RAG 和角色 RAG；角色 RAG 从当前数据包的角色卡、已审核知识和当前问题编译提示词。
- 单一知识输入目录：[database/](database/)。构建将受控文件打包到 `assets/database/`，启动时校验并安装到 `<filesDir>/database/`。
- 会话、Memory 按数据集 ID 和 fingerprint 隔离；旧无标识会话可在“清空对话 → 查看历史记录”中只读查看。

## 项目结构与数据路径

```text
repository/database/                         # 唯一知识源，编辑与替换入口
  ├─ characters/、story/、knowledge_base_v2/  # 当前数据集角色、事件和源数据库
  ├─ reviewed/atomic_facts.json              # 独立审核的短事实和角色认知
  ├─ runtime/android/                        # 向量索引、候选及授权导出
  └─ manifest.json                           # 身份、能力、路径和 SHA-256
      ↓ scripts/prepare_database_bundle.py --check
app/build/generated/databaseAssets/database/ # 构建生成，不手工编辑
      ↓ APK assets/database/
Android <filesDir>/database/                 # 校验后原子安装，检索器唯一知识根
```

`app/src/main/java/.../harness/` 包含编排、检索、角色编译、数据安装和会话实现；`LlamaEngine` 经 JNI 调用 `llama.cpp-omni`。程序模板和 TTS 音频是独立程序资源，不是另一套知识库。

## Android 运行链路

```text
MiniCPMApplication → DatabaseBundleInstaller → DatabaseRuntime
MainActivity → AndroidRagOrchestrator
  ├─ RagSearchService → 当前包 384 维 hash n-gram VectorIndex
  └─ CharacterPromptCompiler → 当前包角色卡 + 已审核 V2 事实
       → 角色、cutoff、来源及预算过滤 → ChatTemplateRenderer
  → HarnessBackend → LlamaEngine → JNI → llama.cpp-omni
```

RAG 默认按当前输入搜索向量索引；角色模式将向量候选与词项检索合并，但只有角色已知且时间有效的审核事实能进入 system。原始场景关联只可召回，不能自行提升为角色知识；当前包没有已授权 V1、时间状态或 V2 关系导出。角色卡完整描述只在其声明 cutoff 及之后使用。

`OFF` 普通对话通过 MainActivity 直接发送用户输入；编排器的 OFF 向量观测开关是可选诊断。明确关闭向量检索的诊断开关会使该回合不查索引，不会切回旧数据源。

## 模型与资源

模型清单由 [ModelInfo.kt](app/src/main/java/com/example/minicpm_v_demo/ModelInfo.kt) 和 [HarnessModelRegistry.kt](app/src/main/java/com/example/minicpm_v_demo/harness/HarnessModelRegistry.kt) 管理。模型文件由下载页面或设备目录提供，不随仓库提交。当前三体数据包有 5 张角色卡、125 条场景候选、229 条场景关联边和 3 条独立审核的角色可知短事实；数据覆盖有限，不能保证每个问题有授权答案。

当前 SQLite 是源与哈希审计对象，在线角色事实由 `knowledge_fallback.json` 的内存 n-gram 和本包向量候选共同召回；不是每轮执行 SQLite FTS。根目录旧 `character/` 与旧外部索引已删除；旧角色 fixture 仍供待迁移的 JVM 测试使用，生产检索不会加载它们。

更换知识库时，准备符合 [database/README.md](database/README.md) 协议的数据，运行 `python3 scripts/prepare_database_bundle.py --source database` 重新生成，再执行 `--check` 和 APK 构建。项目不提供设备内任意数据库导入或自动格式转换。

## 构建与运行

基本要求：

- Android Studio
- Android SDK / NDK `27.0.12077973`
- CMake `4.1.2`
- arm64 真机或 arm64 模拟器

调试构建：

```bash
./gradlew :app:assembleDebug
```

默认调试 APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

当前 APK 只打包 `arm64-v8a`。普通 `x86/x86_64` 模拟器通常不能直接验证 native 推理。

## 关键文件

- [app/build.gradle.kts](app/build.gradle.kts)：Android、NDK、CMake、ABI、依赖与动态 CPU so 构建配置。
- [app/src/main/java/com/example/minicpm_v_demo/MiniCPMApplication.kt](app/src/main/java/com/example/minicpm_v_demo/MiniCPMApplication.kt)：应用启动与 Harness assets 初始化。
- [app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt](app/src/main/java/com/example/minicpm_v_demo/MainActivity.kt)：主聊天页、媒体选择、RAG 模式、会话保存。
- [app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt](app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt)：native 模型加载、推理、图片/视频 prefill 和 TTS runtime 接入。
- [app/src/main/java/com/example/minicpm_v_demo/harness/HarnessFacade.kt](app/src/main/java/com/example/minicpm_v_demo/harness/HarnessFacade.kt)：Android 页面访问 Harness 的统一入口。
- [app/src/main/java/com/example/minicpm_v_demo/harness/data/AssetBootstrapper.kt](app/src/main/java/com/example/minicpm_v_demo/harness/data/AssetBootstrapper.kt)：打包资源到运行目录的复制逻辑。
- [app/src/main/java/com/example/minicpm_v_demo/harness/rag/AndroidRagOrchestrator.kt](app/src/main/java/com/example/minicpm_v_demo/harness/rag/AndroidRagOrchestrator.kt)：普通 RAG 和角色 RAG Prompt 编译。
- [app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompiler.kt](app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompiler.kt)：角色身份、剧情边界、关系和记忆片段组装。
- [app/src/main/java/com/example/minicpm_v_demo/harness/character/knowledge/V2EpistemicKnowledgeStore.kt](app/src/main/java/com/example/minicpm_v_demo/harness/character/knowledge/V2EpistemicKnowledgeStore.kt)：V2 事实/认知图谱 JSON 回退、检索、边界过滤和审计。
- [scripts/prepare_database_bundle.py](scripts/prepare_database_bundle.py)：从根 database 生成并校验运行时导出与索引。
- [app/src/main/java/com/example/minicpm_v_demo/harness/session/ChatSessionStore.kt](app/src/main/java/com/example/minicpm_v_demo/harness/session/ChatSessionStore.kt)：Android 侧会话落盘。

## 文档索引

- [docs/RAG_Logic.md](docs/RAG_Logic.md)：当前 RAG 数据路径、授权、会话和排障。
- [docs/char_inte_plan/Database_Path_Unification_Plan.md](docs/char_inte_plan/Database_Path_Unification_Plan.md)：迁移和清理计划。
- [database/README.md](database/README.md)：数据包协议与生成命令。

- [docs/Harness_Quickstart_Guide.md](docs/Harness_Quickstart_Guide.md)：Harness 快速认知。
- [docs/Harness/Inte_Harness.md](docs/Harness/Inte_Harness.md)：Harness 接入方案。
- [docs/Harness/Mini_Change.md](docs/Harness/Mini_Change.md)：阶段改动记录。
- [docs/Harness/Lightweight_Model_SDK_Roadmap.md](docs/Harness/Lightweight_Model_SDK_Roadmap.md)：轻量模型 SDK 化演进路线。

## 当前边界

- 可用知识受当前已审核事实和角色卡范围约束；未审核场景、未知物品和未来信息不会自动变成角色已知事实。
- 项目使用本地 hash n-gram 向量索引；相似度召回不保证语义精准，模型回答仍需要设备回归。
- 当前不支持设备内热导入任意数据库；替换数据需重新生成受控产物并构建 APK。
- Phase 6 正在迁移剩余旧角色 fixture 测试；旧会话按数据集隔离保留。
