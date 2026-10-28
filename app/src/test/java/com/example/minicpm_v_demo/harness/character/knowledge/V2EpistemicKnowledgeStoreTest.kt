package com.example.minicpm_v_demo.harness.character.knowledge

import com.example.minicpm_v_demo.harness.data.database.databaseTestSource
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V2EpistemicKnowledgeStoreTest {
    private val bundle = databaseTestSource().resolve("runtime/android")
    private val store = V2EpistemicKnowledgeStore(
        fallbackFile = File(bundle, "knowledge_fallback.json"),
        sourceDatabaseFile = databaseTestSource().resolve("knowledge_base_v2/character_knowledge_v2.sqlite3"),
        reviewedBundle = DatabaseBundleValidator.validate(databaseTestSource()),
    )

    @Test
    fun retrievesOnlyFactsKnownByCharacterAtCutoff() {
        val results = store.retrieve(
            V2KnowledgeQuery(
                characterId = "luo_ji",
                query = "叶文洁提出的宇宙社会学公理是什么",
                storyCutoff = "evt-002",
            ),
        )

        assertTrue(results.any { it.claimId == "reviewed-luo-ji-civilization-axioms" })
        val fact = results.first { it.claimId == "reviewed-luo-ji-civilization-axioms" }
        assertEquals("reported", fact.acquisitionMethod)
        assertTrue(fact.matchedTerms.isNotEmpty())
    }

    @Test
    fun rejectsFutureKnowledgeBeforeItsAcquisitionCutoff() {
        val results = store.retrieve(
            V2KnowledgeQuery(
                characterId = "luo_ji",
                query = "叶文洁提出的宇宙社会学公理是什么",
                storyCutoff = "evt-001",
            ),
        )

        assertTrue(results.none { it.claimId == "reviewed-luo-ji-civilization-axioms" })
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownCutoffRatherThanUsingAPermissiveDefault() {
        store.retrieve(V2KnowledgeQuery("luo_ji", "宇宙社会学公理", "evt-999"))
    }

    @Test
    fun auditRecordsSelectionAndFutureKnowledgeRejectionWithoutStoryText() {
        val futureResult = store.retrieveAudited(
            V2KnowledgeQuery("luo_ji", "叶文洁提出的宇宙社会学公理是什么", "evt-001"),
        )

        assertEquals(KnowledgeStoreStatus.READY, futureResult.status)
        val futureDecision = futureResult.decisions.first { it.claimId == "reviewed-luo-ji-civilization-axioms" }
        assertEquals(KnowledgeDisposition.DROPPED, futureDecision.disposition)
        assertEquals(KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF, futureDecision.reason)
        assertTrue(futureResult.decisions.none { it.toString().contains("宇宙社会学公理") })

        val selectedResult = store.retrieveAudited(
            V2KnowledgeQuery("luo_ji", "叶文洁提出的宇宙社会学公理是什么", "evt-002"),
        )
        val selectedDecision = selectedResult.decisions.first { it.claimId == "reviewed-luo-ji-civilization-axioms" }
        assertEquals(KnowledgeDisposition.SELECTED, selectedDecision.disposition)
        assertEquals(KnowledgeDecisionReason.SELECTED, selectedDecision.reason)
        assertTrue(selectedDecision.score != null)
    }

    @Test
    fun unavailableExportFailsClosedForLaterStoryEventsFallback() {
        val unavailable = V2EpistemicKnowledgeStore(File(bundle, "missing.json"))
            .retrieveSafely(V2KnowledgeQuery("luo_ji", "宇宙社会学公理", "evt-002"))

        assertEquals(KnowledgeStoreStatus.UNAVAILABLE, unavailable.status)
        assertTrue(unavailable.facts.isEmpty())
        assertTrue(unavailable.decisions.isEmpty())
        assertEquals("v2_store_unavailable", unavailable.fallbackReason)
    }
}
