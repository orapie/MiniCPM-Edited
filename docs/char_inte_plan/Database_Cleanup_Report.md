# Phase 6 清理记录

以下是已实际删除的旧根目录文件；当前阶段仍在进行，剩余任务见文末。替代数据源为根目录 `database/`，数据准备入口为 `scripts/prepare_database_bundle.py`。

- `character/.DS_Store`
- `character/CHARACTER_KNOWLEDGE_GRAPH_INTEGRATION_PLAN.md`
- `character/CHARACTER_RAG_QUERY_ROUTING_PLAN.md`
- `character/character_kb_agent_bundle/.DS_Store`
- `character/character_kb_agent_bundle/README.md`
- `character/character_kb_agent_bundle/kb/claims.jsonl`
- `character/character_kb_agent_bundle/kb/entities.jsonl`
- `character/character_kb_agent_bundle/kb/graph.json`
- `character/character_kb_agent_bundle/kb/manifest.json`
- `character/character_kb_agent_bundle/kb/relations.jsonl`
- `character/character_kb_agent_bundle/python/graph_knowledge.py`
- `character/character_kb_agent_bundle/sources/1453.txt`
- `character/character_kb_agent_bundle/sources/1454.txt`
- `character/character_kb_agent_bundle/sources/1455.txt`
- `character/character_kb_agent_bundle/sources/1456.txt`
- `character/character_kb_agent_bundle/sources/1457.txt`
- `character/character_kb_agent_bundle/sources/1458.txt`
- `character/character_kb_v2_agent_bundle/.DS_Store`
- `character/character_kb_v2_agent_bundle/README.md`
- `character/character_kb_v2_agent_bundle/kb/character_knowledge_v2.sqlite3`
- `character/character_kb_v2_agent_bundle/kb/manifest.json`
- `character/character_kb_v2_agent_bundle/python/v2_knowledge_store.py`
- `character/character_kb_v2_agent_bundle/sources/1453.txt`
- `character/character_kb_v2_agent_bundle/sources/1454.txt`
- `character/character_kb_v2_agent_bundle/sources/1455.txt`
- `character/character_kb_v2_agent_bundle/sources/1456.txt`
- `character/character_kb_v2_agent_bundle/sources/1457.txt`
- `character/character_kb_v2_agent_bundle/sources/1458.txt`
- `character/export_v2_knowledge_fallback.py`

已删除被当前安装器、检索/时间边界和设备样本覆盖的旧安装测试及旧数据专用 Android 测试：

- `app/src/test/java/com/example/minicpm_v_demo/harness/data/AssetBootstrapperKnowledgeBundleTest.kt`
- `app/src/androidTest/java/com/example/minicpm_v_demo/harness/character/CharacterGraphNativeInferenceTest.kt`
- `app/src/androidTest/java/com/example/minicpm_v_demo/harness/character/CharacterProfileNativeInferenceTest.kt`
- `app/src/androidTest/java/com/example/minicpm_v_demo/harness/character/CharacterRoleplayRegressionTest.kt`
- `app/src/androidTest/java/com/example/minicpm_v_demo/harness/character/knowledge/CharacterGraphRuntimeAcceptanceTest.kt`
- `app/src/androidTest/java/com/example/minicpm_v_demo/harness/character/knowledge/V2SqliteCapabilityProbeTest.kt`
- `app/src/main/java/com/example/minicpm_v_demo/harness/character/knowledge/V2SqliteCapabilityProbe.kt`

已删除无消费者的旧说明、评测、模板、schema、角色预设及文档原文；同名提取模板在 `database/prompts/` 是独立的数据构造工具资源：

- `app/src/main/assets/harness/agent/ab_cases.json`
- `app/src/main/assets/harness/agent/multiturn_cases.json`
- `app/src/main/assets/harness/agent/scenarios.json`
- `app/src/main/assets/harness/characters/characters/README.md`
- `app/src/main/assets/harness/characters/prompts/README.md`
- `app/src/main/assets/harness/characters/prompts/character_extraction.prompt`
- `app/src/main/assets/harness/characters/schemas/README.md`
- `app/src/main/assets/harness/characters/schemas/character_state_schema.json`
- `app/src/main/assets/harness/characters/schemas/npc_schema.json`
- `app/src/main/assets/harness/characters/schemas/story_event_schema.json`
- `app/src/main/assets/harness/characters/story/README.md`
- `app/src/main/assets/harness/fixtures/showcase_questions.json`
- `app/src/main/assets/harness/manifest.json`
- `app/src/main/assets/harness/rag/documents/novel_test/1453.txt`
- `app/src/main/assets/harness/rag/documents/novel_test/1454.txt`
- `app/src/main/assets/harness/rag/documents/novel_test/1455.txt`
- `app/src/main/assets/harness/rag/documents/novel_test/1456.txt`
- `app/src/main/assets/harness/rag/documents/novel_test/1457.txt`
- `app/src/main/assets/harness/rag/documents/novel_test/1458.txt`
- `app/src/main/assets/harness/rag/documents/novel_test/qa.json`
- `app/src/main/assets/harness/rag/documents_manifest.json`
- `app/src/main/assets/harness/roles/default.json`
- `app/src/main/assets/harness/roles/xuan_an.json`

- `app/src/main/assets/harness/rag/index.json`：旧外部向量索引；向量和编排测试已改为新包。

## 迁移和保留

- `app/src/main/assets/prompts/roleplay_system.prompt` 是唯一程序模板源；Gradle、APK 清单校验和新库测试已指向新位置。`database/prompts/character_extraction.prompt` 被数据生成工具使用，予以保留。
- 旧 `fromPaths` 生产工厂、旧知识安装器以及 `HarnessDataPaths` 中旧数据属性已移除。当前会话和 Memory 路径保留。
- `LegacyHarnessCleanup` 仅在验证后的数据库已就绪时运行，按历史内置资源文件名与 SHA-256 删除字节一致的副本，不递归删除不明文件，也不跟随符号链接；保留会话、Memory、模型和当前数据库。
- 已在 ARM64 模拟器执行 42 项非模型回归，原有会话文件 SHA-256 前后相同。迁移标记存在；旧目录中 28 个 `world_cup` 文件不在可核实的旧内置哈希清单内，保持原样且不注册为知识源，不能宣称设备旧内容全部删除。
- 旧角色卡、事件、状态、V1/V2 图谱文件目前仍被 `CharacterPromptCompilerTest` 使用；应先将其仍有价值的状态、预算、追问与授权断言改为合成 fixture 或当前包，再删除剩余旧角色资源和测试目录定位器。禁止通过删除整组测试来伪造完成。
- `V1ClaimKnowledgeStore`、`V1GraphKnowledgeStore` 的兼容路径和旧契约校验仍需结合可支持数据包协议及测试迁移确认；不能因为当前包 v1=false 而擅自宣称完全无用。
