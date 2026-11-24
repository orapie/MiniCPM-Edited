package com.example.minicpm_v_demo.harness.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayOutputGuardTest {
    @Test fun secondPersonIdentityEchoRetriesWithoutRejectingNormalPlayerAddress() {
        val repair = RoleplayOutputDecision.RetryWithRepair(listOf("self_second_person_narration"))
        assertEquals(repair, RoleplayOutputGuard.evaluate("你是章北海。", "章北海", "你是谁？"))
        assertEquals(repair, RoleplayOutputGuard.evaluate("你是丁仪。", "史强", "你是谁？"))
        assertEquals(repair, RoleplayOutputGuard.evaluate("你以前当过刑警。", "史强", "你以前做过什么工作？"))
        val playerAddress = "你先休息，我来守着。"
        assertEquals(RoleplayOutputDecision.Accept(playerAddress),
            RoleplayOutputGuard.evaluate(playerAddress, "章北海", "我累了，你会怎么做？"))
        val quote = "她对我说：“你是丁仪。”我没理会。"
        assertEquals(RoleplayOutputDecision.Accept(quote),
            RoleplayOutputGuard.evaluate(quote, "史强", "你是谁？"))
        val correct = "我是史强。你是来找我的吧。"
        assertEquals(RoleplayOutputDecision.Accept(correct),
            RoleplayOutputGuard.evaluate(correct, "史强", "你是谁？"))
    }

    @Test fun phaseZeroSelfDescriptionAndAssistantLeaksRequestRepair() {
        for ((answer, name, question) in listOf(
            Triple("史强——就是那个总能在关键时刻救你的人。", "史强", "你是谁？"),
            Triple("章北海曾从事军种政治工作。", "章北海", "你以前做过什么工作？"),
            Triple("他曾经担任军官。", "章北海", "你以前做过什么工作？"),
        )) {
            assertEquals(RoleplayOutputDecision.RetryWithRepair(listOf("self_third_person_narration")),
                RoleplayOutputGuard.evaluate(answer, name, question))
        }
        for (answer in listOf(
            "我是 MiniCPM 系列模型。", "我是一个人工智能助手。",
            "我是一个由面壁智能（ModelBest）和 OpenBMB 开源社区开发的 AI 助手。",
        )) {
            assertEquals(RoleplayOutputDecision.RetryWithRepair(listOf("assistant_identity")),
                RoleplayOutputGuard.evaluate(answer, "史强", "你是谁？"))
        }
        val coordinator = RoleplayGenerationCoordinator()
        val fallback = coordinator.resolve(0, "你是丁仪。", "史强", "你是谁？") as RoleplayGenerationAction.Fallback
        assertTrue(fallback.ruleIds.contains("self_second_person_narration"))
    }
    @Test fun selfIdentificationAsAiRetriesWithoutRejectingThirdPartyReferences() {
        val question = "你以前做过什么工作？"
        val leak = "我没有个人工作经历哦，毕竟是AI嘛。"
        assertEquals(RoleplayOutputDecision.RetryWithRepair(listOf("assistant_identity")),
            RoleplayOutputGuard.evaluate(leak, "史强", question))
        val thirdParty = "它毕竟是AI嘛，我以前靠自己跑现场。"
        assertEquals(RoleplayOutputDecision.Accept(thirdParty),
            RoleplayOutputGuard.evaluate(thirdParty, "史强", question))
    }
    @Test fun unsolicitedQuestionRequestsRepairButQuotedSpeechIsAllowed() {
        assertEquals(RoleplayOutputDecision.RetryWithRepair(listOf("unsolicited_question")),
            RoleplayOutputGuard.evaluate("史强是我的朋友。我们怎么看他？", "罗辑", "你怎么看史强？"))
        assertEquals(RoleplayOutputDecision.RetryWithRepair(listOf("unsolicited_question")),
            RoleplayOutputGuard.evaluate("你想去哪里?", "罗辑", "你最想去哪里？"))
        val quoted = "她问我“你要去哪儿？”，我没有回答。"
        assertEquals(RoleplayOutputDecision.Accept(quoted), RoleplayOutputGuard.evaluate(quoted, "罗辑", "她说了什么？"))
        val title = "我读过《你是谁？》。"
        assertEquals(RoleplayOutputDecision.Accept(title), RoleplayOutputGuard.evaluate(title, "罗辑", "你读过什么？"))
        assertEquals(RoleplayOutputDecision.RetryWithRepair(listOf("unsolicited_question")),
            RoleplayOutputGuard.evaluate("“哪里最合适？", "罗辑", "你最想去哪里？"))
    }
    @Test
    fun promptAndInternalFieldLeaksRequestOneFreshAttemptWithoutReturningText() {
        val decision = RoleplayOutputGuard.evaluate(
            rawOutput = "角色身份：陆江仙。请遵照系统提示。",
            characterName = "陆江仙",
            userInput = "你是谁？",
        )

        val retry = decision as RoleplayOutputDecision.RetryWithRepair
        assertEquals(listOf("meta_prompt_reference", "internal_field_label"), retry.ruleIds)
        assertFalse(retry.toString().contains("陆江仙"))
        assertFalse(retry.toString().contains("系统提示"))
    }

    @Test
    fun normalFirstPersonIdentityAnswerIsAccepted() {
        val decision = RoleplayOutputGuard.evaluate(
            rawOutput = "我是陆江仙，族中人都这样叫我。",
            characterName = "陆江仙",
            userInput = "你的身份是什么？",
        )

        assertEquals(
            RoleplayOutputDecision.Accept("我是陆江仙，族中人都这样叫我。"),
            decision,
        )
    }

    @Test
    fun completedThinkBlockIsRemovedBeforeAVisibleAnswerIsAccepted() {
        val decision = RoleplayOutputGuard.evaluate(
            rawOutput = "<think>先分析提示</think>我从元府而来。",
            characterName = "陆江仙",
            userInput = "你来自哪里？",
        )

        assertEquals(RoleplayOutputDecision.Accept("我从元府而来。"), decision)
    }

    @Test
    fun templateTokensRequestRepairWithoutCrashingTheClassifier() {
        val decision = RoleplayOutputGuard.evaluate(
            rawOutput = "{{relevant_profile}}",
            characterName = "陆江仙",
            userInput = "你是谁？",
        )

        assertEquals(
            RoleplayOutputDecision.RetryWithRepair(listOf("template_token")),
            decision,
        )
    }

    @Test
    fun unclosedThinkBlockUsesSafeFallbackAndNeverReturnsItsText() {
        val decision = RoleplayOutputGuard.evaluate(
            rawOutput = "<think>角色身份：陆江仙",
            characterName = "陆江仙",
            userInput = "你是谁？",
        )

        val fallback = decision as RoleplayOutputDecision.SafeFallback
        assertTrue(fallback.ruleIds.contains("unclosed_think_block"))
        assertTrue(fallback.ruleIds.contains("empty_visible_output"))
        assertFalse(fallback.toString().contains("角色身份"))
    }

    @Test
    fun observerAndGuardShareTheSameLeakRuleIds() {
        val rawOutput = "作为AI助手，角色边界：不能说。"
        val observer = RoleplayOutputObserver.observe(
            context = testContext(),
            rawOutput = rawOutput,
            visibleOutput = rawOutput,
            completion = "completed",
            includeRawText = false,
        )
        val decision = RoleplayOutputGuard.evaluate(rawOutput, "陆江仙", "你是谁？")

        assertEquals(
            observer.signals.filter { it.severity == RoleplayLeakSeverity.HIGH }.map { it.ruleId },
            (decision as RoleplayOutputDecision.RetryWithRepair).ruleIds,
        )
    }

    private fun testContext(): RoleplayObservationContext = RoleplayOutputObserver.createContext(
        observationId = "guard-observation",
        characterId = "lu_jiangxian",
        characterName = "陆江仙",
        storyCutoff = "evt-018",
        modelId = "minicpm-test",
        ragMode = "CHARACTER_RAG",
        systemPrompt = "system",
        userPrompt = "user",
        renderedPrompt = "rendered",
        estimatedSystemTokens = null,
        estimatedUserTokens = null,
        contextChars = 0,
        sourceCount = 0,
        predictLength = 96,
    )
}
