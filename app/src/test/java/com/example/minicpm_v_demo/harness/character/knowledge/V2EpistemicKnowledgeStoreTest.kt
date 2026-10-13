package com.example.minicpm_v_demo.harness.character.knowledge

import com.example.minicpm_v_demo.harness.data.harnessAssetsDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V2EpistemicKnowledgeStoreTest {
    private val bundle = File(harnessAssetsDir(), "characters/knowledge/v2")
    private val store = V2EpistemicKnowledgeStore(
        fallbackFile = File(bundle, "knowledge_fallback.json"),
        sourceDatabaseFile = File(bundle, "character_knowledge_v2.sqlite3"),
    )

    @Test
    fun retrievesOnlyFactsKnownByCharacterAtCutoff() {
        val results = store.retrieve(
            V2KnowledgeQuery(
                characterId = "lu_jiangxian",
                query = "玄谙为何还要毁掉明阳",
                storyCutoff = "evt-018",
            ),
        )

        assertTrue(results.any { it.claimId == "v2-claim-evt-012" })
        val fact = results.first { it.claimId == "v2-claim-evt-012" }
        assertEquals("direct", fact.acquisitionMethod)
        assertTrue(fact.matchedTerms.isNotEmpty())
    }

    @Test
    fun rejectsFutureKnowledgeBeforeItsAcquisitionCutoff() {
        val results = store.retrieve(
            V2KnowledgeQuery(
                characterId = "lu_jiangxian",
                query = "玄谙为何还要毁掉明阳",
                storyCutoff = "evt-010",
            ),
        )

        assertTrue(results.none { it.claimId == "v2-claim-evt-012" })
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownCutoffRatherThanUsingAPermissiveDefault() {
        store.retrieve(V2KnowledgeQuery("lu_jiangxian", "明阳", "evt-999"))
    }

    @Test
    fun auditRecordsSelectionAndFutureKnowledgeRejectionWithoutStoryText() {
        val futureResult = store.retrieveAudited(
            V2KnowledgeQuery("lu_jiangxian", "玄谙为何还要毁掉明阳", "evt-010"),
        )

        assertEquals(KnowledgeStoreStatus.READY, futureResult.status)
        val futureDecision = futureResult.decisions.first { it.claimId == "v2-claim-evt-012" }
        assertEquals(KnowledgeDisposition.DROPPED, futureDecision.disposition)
        assertEquals(KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF, futureDecision.reason)
        assertTrue(futureResult.decisions.none { it.toString().contains("毁掉明阳") })

        val selectedResult = store.retrieveAudited(
            V2KnowledgeQuery("lu_jiangxian", "玄谙为何还要毁掉明阳", "evt-018"),
        )
        val selectedDecision = selectedResult.decisions.first { it.claimId == "v2-claim-evt-012" }
        assertEquals(KnowledgeDisposition.SELECTED, selectedDecision.disposition)
        assertEquals(KnowledgeDecisionReason.SELECTED, selectedDecision.reason)
        assertTrue(selectedDecision.score != null)
    }

    @Test
    fun unavailableExportFailsClosedForLaterStoryEventsFallback() {
        val unavailable = V2EpistemicKnowledgeStore(File(bundle, "missing.json"))
            .retrieveSafely(V2KnowledgeQuery("lu_jiangxian", "明阳", "evt-018"))

        assertEquals(KnowledgeStoreStatus.UNAVAILABLE, unavailable.status)
        assertTrue(unavailable.facts.isEmpty())
        assertTrue(unavailable.decisions.isEmpty())
        assertEquals("v2_store_unavailable", unavailable.fallbackReason)
    }
}
