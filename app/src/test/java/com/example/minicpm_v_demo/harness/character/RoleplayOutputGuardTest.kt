package com.example.minicpm_v_demo.harness.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayOutputGuardTest {
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
