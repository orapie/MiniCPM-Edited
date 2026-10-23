package com.example.minicpm_v_demo.harness.session

import com.example.minicpm_v_demo.harness.data.HarnessDataPaths
import com.example.minicpm_v_demo.harness.rag.RagMode
import com.example.minicpm_v_demo.harness.rag.RagSource
import com.example.minicpm_v_demo.harness.character.CharacterFollowUpResolver
import com.example.minicpm_v_demo.harness.character.CharacterFollowUpTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChatSessionStoreTest {
    @Test
    fun appendTurnPersistsRecentSessionWithSourcesModelAndSummary() {
        val tempRoot = Files.createTempDirectory("chat-session-store-test").toFile()
        try {
            val store = ChatSessionStore(HarnessDataPaths(File(tempRoot, "harness")))
            val session = store.create(
                characterId = "lu_jiangxian",
                storyCutoff = "evt-010",
                selectedModelId = "minicpm-test",
            )
            val turn = ChatTurn(
                turnId = "turn-1",
                userText = "玄谙是什么？",
                assistantText = "我只能说到当前剧情已知的部分。",
                ragMode = RagMode.CHARACTER_RAG.name,
                sources = listOf(RagSource("docs/a.md", 3, 9, 0.75)),
                characterContextTargets = listOf(CharacterFollowUpTarget("authorized_story_facts", "v2-claim-evt-012")),
                renderedPrompt = "<|system|>身份：我是陆江仙",
                characterId = "lu_jiangxian",
                modelId = "minicpm-test",
                createdAt = 1234L,
            )

            store.appendTurn(session, turn)

            val restored = store.loadRecentSession()
            requireNotNull(restored)
            assertEquals("lu_jiangxian", restored.characterId)
            assertEquals("evt-010", restored.storyCutoff)
            assertEquals("minicpm-test", restored.selectedModelId)
            assertEquals(1, restored.turns.size)
            assertEquals("docs/a.md", restored.turns.single().sources.single().source)
            assertEquals(1234L, restored.turns.single().createdAt)
            assertEquals("v2-claim-evt-012", restored.turns.single().characterContextTargets.single().id)
            assertTrue(restored.conversationSummary.contains("不可信对话记录"))
            assertTrue(restored.conversationSummary.contains("玄谙是什么？"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun followUpResolverRequiresSameCharacterCutoffAndAPlainPronounTurn() {
        val target = CharacterFollowUpTarget("authorized_story_facts", "v2-claim-evt-012")
        val session = ChatSession(
            sessionId = "session",
            characterId = "lu_jiangxian",
            characterPackId = "character_system",
            storyCutoff = "evt-018",
            selectedModelId = "minicpm-test",
            turns = listOf(
                ChatTurn(
                    turnId = "turn",
                    userText = "玄谙为何这样做？",
                    assistantText = "……",
                    ragMode = RagMode.CHARACTER_RAG.name,
                    characterContextTargets = listOf(target),
                    characterId = "lu_jiangxian",
                    modelId = "minicpm-test",
                ),
            ),
        )

        assertEquals(
            target,
            CharacterFollowUpResolver.resolve(session, "他为何这样做？", "lu_jiangxian", "evt-018", listOf("玄谙")),
        )
        assertEquals(
            null,
            CharacterFollowUpResolver.resolve(session, "玄谙为何这样做？", "lu_jiangxian", "evt-018", listOf("玄谙")),
        )
        assertEquals(
            null,
            CharacterFollowUpResolver.resolve(session, "他为何这样做？", "xuan_an", "evt-018", listOf("玄谙")),
        )
    }

    @Test
    fun profileContinuationAcceptsOnlyAnImmediateSafeContinuationPhrase() {
        val target = CharacterFollowUpTarget("relevant_profile", "profile_background_0")
        val session = ChatSession(
            sessionId = "session",
            characterId = "li_jiangqun",
            characterPackId = "character_system",
            storyCutoff = "evt-028",
            selectedModelId = "minicpm-test",
            turns = listOf(
                ChatTurn(
                    turnId = "turn",
                    userText = "你的来历是什么？",
                    assistantText = "……",
                    ragMode = RagMode.CHARACTER_RAG.name,
                    characterContextTargets = listOf(target),
                    characterId = "li_jiangqun",
                    modelId = "minicpm-test",
                ),
            ),
        )

        assertEquals(
            target,
            CharacterFollowUpResolver.resolve(session, "其他的还有吗？", "li_jiangqun", "evt-028", listOf("玄谙")),
        )
        assertEquals(
            null,
            CharacterFollowUpResolver.resolve(session, "换个话题吧", "li_jiangqun", "evt-028", listOf("玄谙")),
        )
    }

    @Test
    fun clearRecentSessionRemovesRestorableSession() {
        val tempRoot = Files.createTempDirectory("chat-session-clear-test").toFile()
        try {
            val store = ChatSessionStore(HarnessDataPaths(File(tempRoot, "harness")))
            store.create("lu_jiangxian", "evt-010", "minicpm-test")

            store.clearRecentSession()

            assertEquals(null, store.loadRecentSession())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
