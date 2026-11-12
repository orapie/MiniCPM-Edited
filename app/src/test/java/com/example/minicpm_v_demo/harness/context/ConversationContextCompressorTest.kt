package com.example.minicpm_v_demo.harness.context

import com.example.minicpm_v_demo.harness.session.ChatSession
import com.example.minicpm_v_demo.harness.session.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextCompressorTest {
    private val identity = ConversationContextIdentity(
        datasetId = "fixture",
        datasetFingerprint = "a".repeat(64),
        characterId = "lu_jiangxian",
        storyCutoff = "evt-010",
    )

    @Test
    fun compressesOnlyMatchingSessionAndKeepsQuestionRelevantTurn() {
        val session = session(
            turns = listOf(
                turn("闲聊", "天气不错。"),
                turn("元府三司如何建立？", "元府三司由三位成员建立。"),
            ),
        )

        val result = ConversationContextCompressor().compress(
            session = session,
            currentQuestion = "三司是如何建立的？",
            maxChars = 120,
            identity = identity,
        )

        assertEquals(2, result.sourceTurnCount)
        assertTrue(result.sourceChars >= result.text.length)
        assertTrue(result.text.contains("元府三司"))
        assertTrue(result.text.length <= 120)
        assertTrue(result.fingerprint != "none")
    }

    @Test
    fun rejectsDifferentDatasetCharacterOrCutoff() {
        val compressor = ConversationContextCompressor()
        val session = session(turns = listOf(turn("问题", "回答")))

        assertTrue(compressor.compress(session, "问题", 120, identity.copy(characterId = "xuan_an")).isEmpty)
        assertTrue(compressor.compress(session, "问题", 120, identity.copy(storyCutoff = "evt-018")).isEmpty)
        assertTrue(compressor.compress(session, "问题", 120, identity.copy(datasetFingerprint = "b".repeat(64))).isEmpty)
    }

    @Test
    fun emptySessionAndZeroBudgetProduceNoPlaceholderContext() {
        val compressor = ConversationContextCompressor()
        assertEquals(CompressedConversationContext.EMPTY, compressor.compress(null, "问题", 120, identity))
        assertEquals(CompressedConversationContext.EMPTY, compressor.compress(session(emptyList()), "问题", 120, identity))
        assertEquals(CompressedConversationContext.EMPTY, compressor.compress(session(listOf(turn("问题", "回答"))), "问题", 0, identity))
    }

    @Test
    fun compressionIsDeterministic() {
        val session = session(listOf(turn("你从哪里来？", "我从元府而来。")))
        val compressor = ConversationContextCompressor()

        val first = compressor.compress(session, "你来自哪里？", 120, identity)
        val second = compressor.compress(session, "你来自哪里？", 120, identity)

        assertEquals(first, second)
        assertFalse(first.isEmpty)
    }

    private fun session(turns: List<ChatTurn>) = ChatSession(
        sessionId = "session",
        characterId = identity.characterId,
        characterPackId = identity.datasetId,
        storyCutoff = identity.storyCutoff,
        selectedModelId = "test",
        datasetId = identity.datasetId,
        datasetFingerprint = identity.datasetFingerprint,
        turns = turns,
    )

    private fun turn(user: String, assistant: String) = ChatTurn(
        turnId = "$user-$assistant",
        userText = user,
        assistantText = assistant,
        ragMode = "CHARACTER_RAG",
        characterId = identity.characterId,
        modelId = "test",
    )
}
