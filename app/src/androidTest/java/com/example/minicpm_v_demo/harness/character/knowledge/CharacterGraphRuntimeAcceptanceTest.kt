package com.example.minicpm_v_demo.harness.character.knowledge

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.harness.character.CharacterPromptCompiler
import com.example.minicpm_v_demo.harness.character.RuntimeContext
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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

        Log.i(
            TAG,
            "Character graph runtime acceptance: status=${known.debug.knowledgeGraphStatus}, " +
                "systemChars=${known.debug.promptChars}, graphChars=${known.debug.knowledgeGraphInjectedChars}, " +
                "futureFactBlocked=true, v1Relations=${relation.debug.v1RelationshipGraphInjectedChars}",
        )
    }

    private companion object {
        const val TAG = "CharacterGraphAcceptance"
    }
}
