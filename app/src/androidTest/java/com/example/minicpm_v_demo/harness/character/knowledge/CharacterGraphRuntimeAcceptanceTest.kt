package com.example.minicpm_v_demo.harness.character.knowledge

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.harness.character.CharacterPromptCompiler
import com.example.minicpm_v_demo.harness.character.CharacterJsonLoader
import com.example.minicpm_v_demo.harness.character.RoleplayAnswerabilityDecision
import com.example.minicpm_v_demo.harness.character.RoleplayAnswerabilityGate
import com.example.minicpm_v_demo.harness.character.RuntimeContext
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs against Android's real application-private files, rather than source
 * assets.  It validates the complete graph-to-system-prompt path without
 * recording a player query or a fact body in logcat.
 */
@RunWith(AndroidJUnit4::class)
class CharacterGraphRuntimeAcceptanceTest {
    @Test
    fun authorizedFactsReachSystemAndFutureFactsDoNot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = AssetBootstrapper.bootstrap(context)
        val compiler = CharacterPromptCompiler.fromPaths(paths)

        val known = compiler.buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙为何还要毁掉明阳",
            runtimeContext = RuntimeContext(storyCutoff = "evt-018", maxChars = 900),
        )
        assertEquals("ready", known.debug.knowledgeGraphStatus)
        assertEquals("skipped_v2_authoritative", known.debug.v1ClaimGraphStatus)
        assertTrue(known.debug.selectedItems.any { it.id == "v2-claim-evt-012" })
        assertTrue(known.messages.first { it.role == "system" }.content.contains("拒绝玄谙以生存和能力为理由的取代方案"))
        assertEquals("玄谙为何还要毁掉明阳", known.messages.first { it.role == "user" }.content)

        val earlier = compiler.buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙为何还要毁掉明阳",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )
        assertFalse(earlier.messages.first { it.role == "system" }.content.contains("拒绝玄谙以生存和能力为理由的取代方案"))
        assertTrue(earlier.debug.knowledgeGraphDecisions.any {
            it.claimId == "v2-claim-evt-012" && it.reason == KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF
        })

        val relation = compiler.buildNpcPrompt(
            npcId = "bai_junyi",
            userInput = "你怎么看玄谙？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-024", maxChars = 1_200),
        )
        assertEquals("ready", relation.debug.v1RelationshipGraphStatus)
        assertTrue(relation.debug.selectedItems.any { it.id == "rel-84a3d8143c5b7647" })
        assertTrue(relation.messages.first { it.role == "system" }.content.contains("玄谙是你的长期上位者与差遣者"))
        assertTrue(relation.debug.v1RelationshipGraphInjectedChars > 0)

        val profile = compiler.buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你从何而来？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )
        assertEquals("explicit", profile.debug.profileRoute)
        assertTrue(profile.debug.profileInjectedChars > 0)

        val state = compiler.buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你现在在哪里？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-018", maxChars = 900),
        )
        assertEquals("explicit", state.debug.stateRoute)
        assertTrue(state.debug.stateInjectedChars > 0)

        val possessionBefore = compiler.buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你手里有什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )
        assertEquals("no_current_state", possessionBefore.debug.possessionRoute)

        val possessionAtCutoff = compiler.buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你手里有什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-018", maxChars = 900),
        )
        assertEquals("no_carried_items", possessionAtCutoff.debug.possessionRoute)
        assertEquals(0, possessionAtCutoff.debug.possessionInjectedChars)
        assertTrue(RoleplayAnswerabilityGate.evaluate(possessionAtCutoff.debug) is RoleplayAnswerabilityDecision.Allow)

        val v1FallbackCompiler = CharacterPromptCompiler(
            loader = CharacterJsonLoader(
                charactersDir = paths.charactersDir,
                storyEventsFile = paths.storyEventsFile,
                promptTemplateFile = File(paths.promptsDir, "roleplay_system.prompt"),
            ),
            v2KnowledgeStore = V2EpistemicKnowledgeStore(File(paths.v2CharacterKnowledgeDir, "missing.json")),
            v1ClaimKnowledgeStore = V1ClaimKnowledgeStore(
                manifestFile = paths.v1CharacterKnowledgeManifestFile,
                claimsFile = File(paths.v1CharacterKnowledgeDir, "claims.jsonl"),
            ),
        )
        val v1Fallback = v1FallbackCompiler.buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙究竟是什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )
        assertEquals("unavailable", v1Fallback.debug.knowledgeGraphStatus)
        assertEquals("ready", v1Fallback.debug.v1ClaimGraphStatus)
        assertTrue(v1Fallback.debug.selectedItems.any { it.id == "event-evt-009" })
        assertTrue(v1Fallback.debug.v1ClaimGraphInjectedChars > 0)
        assertFalse(v1Fallback.debug.legacyStoryRetrievalExecuted)

        Log.i(
            TAG,
            "Character graph runtime acceptance: status=${known.debug.knowledgeGraphStatus}, " +
                "systemChars=${known.debug.promptChars}, graphChars=${known.debug.knowledgeGraphInjectedChars}, " +
                "futureFactBlocked=true, v1Relations=${relation.debug.v1RelationshipGraphInjectedChars}, " +
                    "v1ClaimsFallback=${v1Fallback.debug.v1ClaimGraphInjectedChars}, " +
                    "profile=${profile.debug.profileInjectedChars}, state=${state.debug.stateInjectedChars}, " +
                    "possession=${possessionAtCutoff.debug.possessionRoute}",
        )
    }

    private companion object {
        const val TAG = "CharacterGraphAcceptance"
    }
}
