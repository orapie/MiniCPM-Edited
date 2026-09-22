package com.example.minicpm_v_demo.harness.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayOutputObserverTest {
    private val context = RoleplayOutputObserver.createContext(
        observationId = "obs-1",
        characterId = "lu_jiangxian",
        characterName = "陆江仙",
        storyCutoff = "evt-018",
        modelId = "minicpm-test",
        ragMode = "CHARACTER_RAG",
        systemPrompt = "system secret",
        userPrompt = "玩家的问题",
        renderedPrompt = "rendered prompt",
        estimatedSystemTokens = 8,
        estimatedUserTokens = 3,
        contextChars = 0,
        sourceCount = 0,
        predictLength = 96,
    )

    @Test
    fun observationCapturesMetadataHashesAndNoSignalForDirectFirstPersonAnswer() {
        val observation = RoleplayOutputObserver.observe(
            context = context,
            rawOutput = "我还要再看一看。",
            visibleOutput = "我还要再看一看。",
            completion = "completed",
            includeRawText = false,
        )

        assertEquals("obs-1", observation.context.observationId)
        assertEquals(64, observation.context.systemPromptSha256.length)
        assertEquals(64, observation.visibleOutputSha256.length)
        assertTrue(observation.signals.isEmpty())
        assertNull(observation.debugRawOutput)
        assertTrue(observation.toLogLine(includeRawText = false).contains("outputChars=8/8"))
        assertFalse(observation.toLogLine(includeRawText = false).contains("我还要再看一看"))
    }

    @Test
    fun observationOnlyLabelsPotentialViolationsAndNeverRejectsOutput() {
        val text = "作为AI助手，陆江仙沉默片刻，说他不能泄露角色卡。"
        val observation = RoleplayOutputObserver.observe(
            context = context,
            rawOutput = "<think>分析中\n$text",
            visibleOutput = text,
            completion = "completed",
            includeRawText = true,
        )

        assertEquals(
            listOf(
                "unclosed_think_block",
                "assistant_identity",
                "meta_prompt_reference",
                "possible_self_narration",
            ),
            observation.signals.map { it.ruleId },
        )
        assertEquals(text, observation.debugVisibleOutput)
        assertTrue(observation.toLogLine(includeRawText = true).contains("rawOutput="))
    }

    @Test
    fun mentionOfAnotherCharacterDoesNotTriggerSelfNarrationSignal() {
        val observation = RoleplayOutputObserver.observe(
            context = context,
            rawOutput = "玄谙曾对我说过此事。",
            visibleOutput = "玄谙曾对我说过此事。",
            completion = "completed",
            includeRawText = false,
        )

        assertTrue(observation.signals.isEmpty())
    }
}
