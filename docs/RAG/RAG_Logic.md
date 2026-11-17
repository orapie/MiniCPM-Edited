# RAG 与角色对话运行指南

## 知识包与检索

[根目录 database](../../database/) 是 Android 应用的知识数据输入。Gradle 的 `prepareDatabaseBundle` 校验 manifest 和受控文件，再暂存到 `app/build/generated/databaseAssets/database/`。应用启动时由 `DatabaseBundleInstaller` 校验并激活 `<filesDir>/database/` 中的数据包，活动回合持有该包的句柄，直到生成及保存结束。程序提示模板与 TTS 参考音频走独立资源流程。

当前 `three_body_2` 包声明 `v2/vector=true`、`v1/timed_state=false`。普通和角色 RAG 使用包内 BGE ONNX 查询模型和 512 维语义索引。Android 在内存中查询索引，SQLite 用于离线构建与审计。384 维 hash n-gram 索引保留给未声明 BGE 路径的旧包。当前包的 BGE 初始化或查询失败会报告诊断状态；默认关闭的混合检索可额外融合词项候选。

- `OFF` 由主界面把用户原始输入交给聊天后端。
- `RAG` 按本轮问句检索，并将选中的片段作为 `user` 消息里的参考资料。
- `CHARACTER_RAG` 在检索之外编译当前角色的身份、说话风格和已授权的本人事实。外部候选按来源、角色和剧情截止点筛选后，作为本轮参考。

向量召回、角色知情授权和正文注入是三个可分别观察的步骤。V2 已审核剧情事实当前用于匹配、边界判断、追问及门控，也保留选中 ID 供诊断。当前角色系统模板负责渲染身份、口吻和 `public_profile_facts`。V2 剧情、旧动态状态、关系和记忆正文没有对应的角色 system 段。外部片段保留在 `user`，其中的代词和人物视角属于来源文本。

## 角色卡与对话承接

`CharacterJsonLoader` 从卡片加载 `identity_core`、`public_profile` 和 `evidence_refs`。卡片到达其声明的截止点后才可使用完整档案。对于直接问当前角色本人的问句，`CharacterProfileStore` 按已配置短语选取有证据引用的条目，编译器最多将两条预算内的公开事实放入 system。更早 cutoff 会移除完整档案和对应公开映射。

单一问句完整命中唯一已注入条目时，编译器使用角色卡独立作答。外部资料不进入 B 请求，A 记录 `not_requested / public_profile_sufficient`。复合问句、模糊匹配或条目超预算时沿用普通参考流程。对于这条单一事实路径，若角色卡原句以第一人称写成，B 仍先生成，输出流程再判断可见文字与审核原句是否一致。相符时交付模型回答，其他情况用原句回退并记录 `public_profile_unverified`，可见回复与模型质量分别计数。

`ConversationContextCompressor` 独立生成适合当前问句的有预算摘要。会话必须匹配数据集 ID、数据包 fingerprint、角色和截止点才能参与。摘要放在 user 消息中，用于代词消歧和明确承接。上一轮追问目标会重新走本轮授权。会话及 Memory 按数据包身份隔离，旧会话可从历史记录查看。

## 双 Agent 与提示格式

| 环节 | 数据 |
|---|---|
| Kotlin → A | `system` 是归因任务指令，`user` 内含角色、当前问题、可选对话摘要和参考原文的 JSON。 |
| A → Kotlin | 固定顺序的 JSON 数组，每条参考对应一个紧凑归因编码。Kotlin 从原始请求恢复引用 ID、问题和规则，再校验 `FinalPromptDraft`。 |
| Kotlin → B | 文本 `system` 承载角色规则与本人事实。默认 `user` 文本承载当前问题、适用摘要、获准的角色卡关系和带归因标签的参考。调试版的 JSON `user` 使用可选的 `relationshipContext` 字段承载同样的关系。 |

A 使用独立的纯文本 GGUF 和原生运行时。只有显式启用、模型可用且本轮有待处理参考时才运行。默认时限为 60 秒，包含模型加载和推理。A 关闭、超时或校验失败时，B 沿用原始 system/user。已授权公开事实在 system 内，各路径共用同一份内容。A 只标注参考的说话者、受话者和视角，原文及授权范围均由程序控制。

角色卡关系须通过当前角色的剧情截止点校验，并有可定位到数据包原文的证据。关系查询明确提到对象或解析到同一角色、同一截止点下的唯一追问对象时，程序才把完整关系放入 B 的 `user`。归因成功与回退共用这份关系内容。

调试器把 `rag_attribution_flags` 的 `agent_b_json_prompt` 布尔值设为 `true` 后，A 成功且校验通过的回合尝试 JSON 定稿。JSON user 超过 4,000 个 UTF-16 代码单元时使用原有 TEXT 定稿，完整保留参考和问题。日志中的 `format`、`schema`、`formatFallback` 分别记录实际输入格式、JSON 版本和格式回退原因。设为 `false` 恢复默认 TEXT。

B 生成前由 `RoleplayAnswerabilityGate` 检查可回答性。门控可以在特定未来知识或证据不足的回合直接返回保守答复。生成后由 `RoleplayGenerationCoordinator` 检查缓冲的可见输出，普通角色回答必要时有一次修复重试，随后采用安全回退。B 的 JSON user 格式见[契约和对照方案](../double_agent/Agent_B_JSON_Prompt_Experiment_Plan.md)。

## 排查

```bash
adb logcat -v threadtime -s DatabaseInstallation:I MainActivity:D RagPromptDebug:D '*:S'
```

| 日志或入口 | 核对内容 |
|---|---|
| `DatabaseInstallation` | 设备活动包与 APK 请求的 dataset/fingerprint。出现 `RETAINED_PREVIOUS` 时检查实际活动版本。 |
| `RAG_TRACE` | 向量是否执行、失败原因、候选 ID、角色授权、外部参考和最终注入。当前包应显示 BGE 后端。 |
| `ATTRIBUTION_TRACE` | A 的状态、原因、模型及请求指纹。公开事实分流是 `public_profile_sufficient`，超时是 `agent_a_timeout`。 |
| 应用内 Final Prompt 与 `BACKEND_REQUEST` | 核对最终 system/user。将应用编译的请求与真实生成记录配对；`FINAL_RENDERED` 用于预览。 |

默认诊断只保留元数据和指纹。需要查看阶段原文时，在可调试版本中通过调试器将 `rag_debug` 的 `raw_prompt_logcat` 布尔值临时设为 `true`。在 `RagPromptDebug` 中按同一 `turn`、`seq` 及 `BEGIN/CHUNK/END` 顺序读取，完成后恢复为 `false`。原文可能含用户输入和检索资料，复测使用合成用例。

在设备上复现见[角色评测与命令](../double_agent/roleplay_eval/results.md)。源码入口为 [AndroidRagOrchestrator](../../app/src/main/java/com/example/minicpm_v_demo/harness/rag/AndroidRagOrchestrator.kt)、[CharacterPromptCompiler](../../app/src/main/java/com/example/minicpm_v_demo/harness/character/CharacterPromptCompiler.kt) 和 [FinalPromptContracts](../../app/src/main/java/com/example/minicpm_v_demo/harness/attribution/FinalPromptContracts.kt)。
