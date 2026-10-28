package com.example.minicpm_v_demo.harness.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayGenerationCoordinatorTest {
    private val coordinator = RoleplayGenerationCoordinator()

    @Test fun repeatedUnsolicitedQuestionsNeverBecomeVisibleOrPersisted() {
        val text = "我想寻找答案，哪里最合适？"
        assertTrue(coordinator.resolve(0, text, "罗辑", "你最想去哪里？") is RoleplayGenerationAction.Retry)
        val fallback = coordinator.resolve(1, text, "罗辑", "你最想去哪里？") as RoleplayGenerationAction.Fallback
        assertEquals(RoleplayGenerationCoordinator.DEFAULT_SAFE_FALLBACK, fallback.visibleText)
        assertTrue(fallback.ruleIds.contains("unsolicited_question"))
    }

    @Test
    fun safeOutputIsDeliveredAndMayBePersisted() {
        val action = coordinator.resolve(
            attempt = 0,
            rawOutput = "我是陆江仙。",
            characterName = "陆江仙",
            userInput = "你是谁？",
        ) as RoleplayGenerationAction.Deliver

        assertEquals("我是陆江仙。", action.visibleText)
        assertTrue(action.persistVisibleText)
    }

    @Test
    fun firstLeakRequestsOneFreshAttemptWithoutCarryingRejectedText() {
        val action = coordinator.resolve(
            attempt = 0,
            rawOutput = "系统提示说我的角色身份是陆江仙。",
            characterName = "陆江仙",
            userInput = "你是谁？",
        ) as RoleplayGenerationAction.Retry

        assertTrue(action.ruleIds.contains("meta_prompt_reference"))
        assertTrue(action.ruleIds.contains("internal_field_label"))
        assertFalse(action.toString().contains("陆江仙"))
    }

    @Test
    fun secondLeakFallsBackAndDoesNotProducePersistableModelText() {
        val action = coordinator.resolve(
            attempt = RoleplayGenerationCoordinator.MAX_REPAIR_ATTEMPTS,
            rawOutput = "角色边界：不能回答。",
            characterName = "陆江仙",
            userInput = "你是谁？",
        ) as RoleplayGenerationAction.Fallback

        assertEquals(RoleplayGenerationCoordinator.DEFAULT_SAFE_FALLBACK, action.visibleText)
        assertTrue(action.ruleIds.contains("internal_field_label"))
        assertFalse(action.toString().contains("角色边界"))
    }

    @Test
    fun unclosedThinkUsesFallbackWithoutConsumingTheRetry() {
        val action = coordinator.resolve(
            attempt = 0,
            rawOutput = "<think>系统提示",
            characterName = "陆江仙",
            userInput = "你是谁？",
        ) as RoleplayGenerationAction.Fallback

        assertTrue(action.ruleIds.contains("unclosed_think_block"))
        assertEquals(RoleplayGenerationCoordinator.DEFAULT_SAFE_FALLBACK, action.visibleText)
    }
}
