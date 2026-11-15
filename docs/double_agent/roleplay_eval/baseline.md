# 阶段零基线记录

## 环境与样本

- 检查时间：2026-11-14，代码 HEAD `6e7348d`；工作区存在用户未提交的 `ModelInfo.kt` 与计划文档修改，设备测试由当前工作区构建，故不能仅用 HEAD 唯一标识运行代码。
- 设备：Android 模拟器 `emulator-5554`，Android 16，arm64-v8a。测试前应用版本 2.3、versionCode 15，安装时间 2026-11-10 19:51:44。此前误以为 Gradle connected test 全程保留应用数据：其安装时保留了数据，但随后确认测试流程会卸载应用，不能再作此假设，见下方事故记录。
- 模型选择：B 与 A 均选择 `minicpm5-2b`；本地文件 `MiniCPM5-2B-Q4_K_M.gguf`，1,561,318,368 字节。双 Agent 开关为 true，两个 prompt 模板在设备运行目录存在。这些配置本身不证明 A 执行。
- 数据集 `database/manifest.json` 默认截止 `evt-125`；工作区 manifest SHA-256 `8d439842eda4af9df64ae69da55d952ca61bc36a0c6e229fcd53211249a036e6`；章北海角色卡 `bfd04cea8149bd4e120fe47df04096b8f49c755ffd919cd27aa542f5904f0475`，史强角色卡 `a319da776f1ff1eeccef825afb60897f6196da81d371a75215d29af1f8c27415`。这些是工作区文件指纹，不冒称设备数据库与工作区完全一致。
- `cases.jsonl` 共 14 条、两个角色各 7 条，ID 均唯一；输入是合成问题，事实权限以运行时数据库和剧情截止为准。样本的用词不是授权证明，执行前须核实具体事实与资料。
- 模型实际模板、解码参数、随机种子、首字延迟、峰值内存、设备实际数据库指纹尚未完整采集，不能作配对实验控制条件。

## 已做的真实模型冒烟检查

命令：`bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.minicpm_v_demo.harness.character.RoleplayPerspectiveNativeSmokeTest`。

- 设备测试 1/1 通过，使用罗辑、截止 `evt-125`，合成问题“雪地工程的目的是什么？”。它是现有的隔离冒烟测试，不读写聊天会话；不是 14 条正式评测中的配对样本。
- 该测试通过 `AndroidRagOrchestrator.fromContext` 创建默认 Noop A 服务，检索注入了资料，直接调用 B；无论设备开关如何，本例都不证明双 Agent 执行。
- 实际日志出现测试请求和模型结果；测试设置输出上限 96 token。模型输出未闭合的思考块，无可交付的可见回答，输出保护返回固定兜底“这话我一时说不清。”。测试只断言原始输出非空，因此 BUILD SUCCESSFUL 不等于角色表现通过。此样本不能判断“你／我”准确率，也不能据此判断无限生成设置的表现。
- 该已有 Debug 测试会将合成 prompt、参考文本与模型原始输出写入设备 Logcat；本报告不保存参考原文或思考内容。测试结束后未清除设备 Logcat，以避免删除无关记录。

## 首次设备评测前的缺口和操作限制（2026-11-14）

- 14 条 case 在三组实验中均未执行；各指标分母目前为 0，无法判断人称错误、事实、风格和自然度，也不能声称已复现或修复第一轮错误。
- 应用的 `clearChat()` 会调用 `clearRecentSession()` 删除最近会话指针。没有通过清除聊天制造首轮，也没有直接修改开关和用户历史；但下面的卸载事件可能已删除本地数据。
- 要完成可比基线，需实现或使用不访问 `ChatSessionStore` 的独立设备评测入口，逐条运行全新模型上下文，固定参考 ID 与内容指纹，并为 A 关闭、A 成功分别注入真实服务；确认无参考组禁用的是外部参考而非关闭角色 RAG。记录 B 真正后端请求、A 状态、模型输出和门控/重试/兜底路径；对 A 未成功单独计数。
- `results.md` 尚为空表，阶段零的真实三组对照验收未达成。已有 JVM/Debug 冒烟通过不能替代质量验收；下一步是构建隔离评测入口并在设备执行。

## 设备测试卸载事故（2026-11-14）

新增 `RoleplayBaselineDeviceTest` 后运行 `connectedDebugAndroidTest`，首个评测因测试签名错误失败，修正后再次运行因找不到选中模型失败。随后 `pm list packages` 与 `run-as` 均找不到应用；Logcat 显示测试期间曾安装应用，并在 17:06:19 出现应用包删除和 FULLY_REMOVED。无法仅根据这些日志精确判断最初那份数据在第几次测试后消失，但模拟器上的应用私有数据（包含模型、设置和本地会话）可能随卸载被移除。`adb emu avd snapshot list` 返回没有可用快照；尚无证据表明这些数据可恢复。未运行清理命令，也未尝试覆盖模拟器快照。

新测试包已成功编译并包含 `cases.jsonl`，但实际三组推理未跑通。再次执行前禁止对有价值数据的设备使用 `connectedDebugAndroidTest`，先使用独立模拟器并准备模型数据；确认安装、卸载和数据保留策略后，再使用可控的显式测试安装与 `adb shell am instrument` 运行。不要把首次启动失败写成模型性能结果。

## 离线基线补充（2026-11-14）

- `adb devices -l` 返回无设备。没有重新安装应用、写入模型或修改设备数据；真实模型对照仍未运行。
- 运行 `:app:testDebugUnitTest`，限定 `RoleplayOutputGuardTest` 和 `AndroidRagOrchestratorTest`，构建成功。后者覆盖无向量参考、带参考且 A 关闭、fake A 成功与无效输出回退；fake A 不代表真实 A 或 B 已运行。
- 新增的现状刻画测试确认：用户问“你是谁？”、候选答复“你是章北海。”时，当前 `RoleplayOutputGuard` 返回 Accept；正常面向玩家说“你先休息，我来守着。”也被接受。前者说明保护器确实不会拦截该合成错位，但不是模型实际生成率。此断言描述旧行为，阶段一修复时应更新。
- 三组数据依然为 0，现阶段不能得出 JSON、A 或参考资料对真实模型质量的影响。新评测入口只完成编译，需在有独立设备、对应 GGUF 和受控安装运行方式时验证。

## 受控设备实测（2026-11-15）

- 使用已安装的主应用和 `emulator-5554`（Android 16、arm64-v8a）。只用 `adb install -t -r` 安装现有测试 APK，再用 `adb shell am instrument` 调用测试。独立测试通过真实后端发送 system/user 请求，每次从新上下文推理，不读写用户会话。主应用始终保留原安装路径，评测未调整模型配置或开关。
- A/B 均选用 `MiniCPM5-2B-Q4_K_M.gguf`（1,561,318,368 字节），设备双 Agent 开关开启。原生日志确认 A 的 thinking 关闭，B 的 thinking 开启。两者上下文窗口为 4096，使用 GGUF 中的聊天模板。原生采样沿用已安装应用设置，无固定种子对照。B 为隔离评测使用 512 token 输出上限，应用常规角色对话可能用不同上限。
- 数据集 `three_body_2`，设备报告 fingerprint `2c0b90db9383839146dbd703a4bd7ab2e74040760a31ba3549877d5130df5a53`。实验记录以设备实际请求的 system/user 指纹、注入上下文指纹和原生推理为准。`system_tokens` / `user_tokens` 为消息正文的 tokenizer 计数，不含聊天模板额外 token。`first_token_ms` 自编译开始计时，含检索及 A 的等待，且首片可能属于思考段，不能当作首个用户可见字。峰值内存未采集。
- 重放命令如下。`GROUP` 取 `no_refs`、`refs_no_a`、`refs_a`，`CASE_ID` 来自 `cases.jsonl`。先确认目标设备及主应用已有模型，并已单独安装匹配的测试包。

```bash
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.example.minicpm_v_demo.harness.character.RoleplayBaselineDeviceTest \
  -e caseId "$CASE_ID" -e group "$GROUP" \
  com.example.minicpm_v_demo.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 logcat -d -s RoleplayBaseline:I
```

- `z03` 的 A 组原生日志显示 338 个系统 token 用 8.229 秒预填充，990 个用户 token 用 27.033 秒预填充，已超过服务 15 秒时限。A 加载成功后回退 `agent_a_timeout`，实际约 56.5 秒才进入 B 的后端请求。时限未能在 A 的原生推理中及时中断。为保留当前应用基线，评测没有调整时限或输出预算。
- 另一个需后续核实的配置风险是 A 要逐字回显 system/user 文本并为每个引用输出多字段 JSON，A 本轮输出上限为 384 token。`z03` 的 B system 正文单独计数即 417 token。序列化后的规范 JSON 尚未实测 token 数，且本轮可证实的实际失败原因是超时。
- `s01` 也回退 `agent_a_timeout`，A 预填充为 338/1030 个 token，用 8.113/27.041 秒。标准配置下，双 Agent 功能开启与 A 已加载都不足以证明 A 的改写被 B 使用。在两个配对用例中，关闭 A 和 A 超时后的参考、system 和 user 指纹一致。
- [实测结果](results.md)记录了两名角色。没有外部资料时也出现了模型自我身份泄漏，有资料时额外观察到将自己说成另一个人物、套用别人的背景等错误。`s01` 在有资料且 A 关闭的路径中实际复现了“你是谁？”→“你是丁仪。”，最终可见输出来自一次修复重试。该回合用于阶段一回归。
- 当前完成首轮问题复现和回退路径归因。尚未获得真实 A 成功组，14 条用例未全部跑完，未做多轮和正式人设打分评审。可在更快设备复跑现有 A 配置，或先另行修正 A 时限和输出契约，再补齐成功组。改过运行参数的结果应另列，不混入本轮基线。

## Agent A 修复后对照（2026-11-15）

- A 不再接收或回显 B 的 system prompt 和请求指纹。它输出定长 JSON 数组，每个参考片段按原顺序占一项。原生语法约束限定项数和合法字符。Kotlin 从原请求恢复引用 ID、角色规则、玩家问题和指纹，继续使用原校验和 B 定稿逻辑。
- A 按参考数量分配预算，7 条为 59 token，8 条为 66 token。默认时限改为 60 秒，覆盖加载与推理，原生 CPU 推理可检查截止时间。在同一条件下复跑原有的有资料对照，沿用旧 B 生成设置及 512 token 测试上限。无资料组和旧回退组仍保留在[结果表](results.md)。
- 使用 `assembleDebug` 和 `assembleDebugAndroidTest` 构建后，以 `adb install -r` 和 `adb install -t -r` 覆盖安装。测试仍由 `adb shell am instrument` 独立运行。覆盖安装前后，最近会话及模型设置的 SHA-256 一致，本地 GGUF 大小一致。没有卸载或清除数据。
- 对原有配对用例 `s01`、`z03`、`z01`，真实 A 改写全部成功（3/3），编译耗时 18.77–21.57 秒，含检索和 A 加载。以 `s01` 为例，A 实际预填充从 338/1030 token、35.15 秒降为 197/385 token、15.33 秒。
- 同一 `s01` 以设备评测参数 `-e aTimeoutMs 12000` 复跑。原生日志显示预填充被中止，编译总耗时 15.34 秒（含检索），得到 `agent_a_timeout`。B 仍执行生成，system/user 指纹与关闭 A 一致。此次属于短时限验证，不计入成功组。运行所有用例之后，再次核对最近会话、模型设置与 GGUF，结果仍与安装前一致。
