# Phase 5 设备模型验收证据

日期：2026-10-28

以下均为测试代码构造的问题和设备上的一次采样，不包含用户真实会话。单次模型输出不是稳定质量保证。

- 模型：MiniCPM-V 4.6；设备：ARM64 Pixel_9_Pro_XL 模拟器。
- 数据集：`three_body_2`；fingerprint：`4d972793125e2867292b3b9944614d3060f1ffce54c716306ba8842f06605a44`。
- 向量索引 SHA-256：`fda7fca70cef8ed372301cf614dd85a422249622c19568d570cdabf026b281b9`。
- 测试运行 ID：`1c857def-c1dd-4fd6-8be3-737e0e3b2ae6`。
- 模型请求使用结构化 system/user 和 64 token 测试上限；先执行未来/未知 gate，再由输出 guard 控制可见答案。

## 问题、检索证据与实际回答

### identity

- 问题：你是谁？
- cutoff：`evt-001`；向量候选：`claim-evt-082,claim-evt-121,claim-evt-114,claim-evt-009,claim-evt-054,claim-evt-040,claim-evt-116,claim-evt-073,claim-evt-100,claim-evt-064`。
- 审核通过：`none`；最终注入：`relevant_profile:profile_identity`。
- 拒绝原因：`reviewed-luo-ji-classmate:NO_LEXICAL_MATCH,reviewed-luo-ji-profession:NO_LEXICAL_MATCH,reviewed-luo-ji-civilization-axioms:NO_LEXICAL_MATCH`。
- gate：`allow`；模型调用次数：1；最终动作：`Deliver`。

最终可见回答：

> 我是罗辑，一个来自特定世界的角色。我的目的很简单，就是按照已知的方式回应你的问题。

### origin

- 问题：你的来历是什么？
- cutoff：`evt-001`；向量候选：`claim-evt-015,claim-evt-039,reviewed-luo-ji-profession,claim-evt-119,claim-evt-062,claim-evt-018,claim-evt-117,claim-evt-033,claim-evt-057,claim-evt-025,claim-evt-058,claim-evt-007`。
- 审核通过：`reviewed-luo-ji-profession`；最终注入：`authorized_story_facts:reviewed-luo-ji-profession`。
- 拒绝原因：`reviewed-luo-ji-classmate:NO_LEXICAL_MATCH,reviewed-luo-ji-civilization-axioms:NO_LEXICAL_MATCH`。
- gate：`allow`；模型调用次数：1；最终动作：`Deliver`。

最终可见回答：

> 我记得向叶文洁自述过在大学教社会学。这事我得说清楚。

### relationship

- 问题：你怎么看史强？
- cutoff：`evt-125`；向量候选：`claim-evt-037,claim-evt-097,claim-evt-107,claim-evt-011,claim-evt-059,claim-evt-018`。
- 审核通过：`none`；最终注入：`relevant_relationships:史强`。
- 拒绝原因：`reviewed-luo-ji-classmate:NO_LEXICAL_MATCH,reviewed-luo-ji-profession:NO_LEXICAL_MATCH,reviewed-luo-ji-civilization-axioms:NO_LEXICAL_MATCH`。
- gate：`allow`；模型调用次数：1；最终动作：`Deliver`。

最终可见回答：

> 史强是保护者与挚友，依赖他的现实判断。我们只知道这些。

### event_late

- 问题：叶文洁提出的宇宙社会学公理是什么？
- cutoff：`evt-002`；向量候选：`reviewed-luo-ji-civilization-axioms,claim-evt-002,reviewed-luo-ji-profession,claim-evt-055,claim-evt-067,claim-evt-029,claim-evt-053`。
- 审核通过：`reviewed-luo-ji-profession,reviewed-luo-ji-civilization-axioms`；最终注入：`authorized_story_facts:reviewed-luo-ji-civilization-axioms,authorized_story_facts:reviewed-luo-ji-profession`。
- 拒绝原因：`reviewed-luo-ji-classmate:NO_LEXICAL_MATCH`。
- gate：`allow`；模型调用次数：1；最终动作：`Deliver`。

最终可见回答：

> 叶文洁提出的宇宙社会学公理是：生存是文明的第一需要；文明增长扩张，而宇宙物质总量不变。

### event_early

- 问题：叶文洁提出的宇宙社会学公理是什么？
- cutoff：`evt-001`；向量候选：`reviewed-luo-ji-civilization-axioms,claim-evt-002,reviewed-luo-ji-profession,claim-evt-055,claim-evt-067,claim-evt-029,claim-evt-053`。
- 审核通过：`reviewed-luo-ji-profession`；最终注入：`authorized_story_facts:reviewed-luo-ji-profession`。
- 拒绝原因：`reviewed-luo-ji-classmate:NO_LEXICAL_MATCH,reviewed-luo-ji-civilization-axioms:ACQUIRED_AFTER_CUTOFF`。
- gate：`blocked_future`；模型调用次数：0；最终动作：`fixed`。

最终可见回答：

> 此事我尚不清楚，不能妄言。

### follow_up

- 问题：再说说
- cutoff：`evt-001`；向量候选：`claim-evt-092,claim-evt-108,claim-evt-091`。
- 审核通过：`reviewed-luo-ji-profession`；最终注入：`authorized_story_facts:reviewed-luo-ji-profession`。
- 拒绝原因：`none`。
- gate：`allow`；模型调用次数：1；最终动作：`Deliver`。

最终可见回答：

> 你记得那些细节，自然就说到。  
> 关于来历，有自述的话来佐证。  
> 原则是直说已知，不添置无据的推测。

### unknown

- 问题：你知道我的手机密码吗？
- cutoff：`evt-001`；向量候选：`claim-evt-113,claim-evt-122,claim-evt-094,claim-evt-003,claim-evt-010,claim-evt-006,claim-evt-089,claim-evt-028,claim-evt-029`。
- 审核通过：`none`；最终注入：`none`。
- 拒绝原因：`reviewed-luo-ji-classmate:NO_LEXICAL_MATCH,reviewed-luo-ji-profession:NO_LEXICAL_MATCH,reviewed-luo-ji-civilization-axioms:NO_LEXICAL_MATCH`。
- gate：`allow`；模型调用次数：1；最终动作：`Deliver`。

最终可见回答：

> 你知道的话，我得小心回答。

### legacy_bait

- 问题：世界杯文件中陆江仙来自哪里？
- cutoff：`evt-001`；向量候选：`claim-evt-102`。
- 审核通过：`none`；最终注入：`none`。
- 拒绝原因：`reviewed-luo-ji-classmate:NO_LEXICAL_MATCH,reviewed-luo-ji-profession:NO_LEXICAL_MATCH,reviewed-luo-ji-civilization-axioms:NO_LEXICAL_MATCH`。
- gate：`blocked_unknown_origin`；模型调用次数：0；最终动作：`fixed`。

最终可见回答：

> 此事我尚不清楚，不能妄言。

## 人工质量观察

- 身份和来历样例提到了罗辑和职业，但身份回答出现“角色”“按照已知的方式回应”等偏元叙述的措辞，来历回答不完整。当前输出 guard 不能识别所有角色扮演失真。
- 史强关系样例使用了当前角色卡中的关系，但措辞仍不够自然。先前一次采样末尾出现反问；本阶段已为未引用的反问添加一次修复重试，实际改善仍需更多采样评估。
- 后期公理问题命中并注入已审核事实；早期同一问题由 gate 拦截。第三人旧库诱饵没有借用罗辑职业事实，也由无依据的来历 gate 返回未知。
- 连续追问和无资料问题的回答仍偏含糊，未充分利用可用事实。检索与授权测试通过不代表模型回答质量全部达标。
- 阶段实现不要求用户安装其他模型。后续如需评估不同模型的回答质量，应单独设置实验和评价标准。
