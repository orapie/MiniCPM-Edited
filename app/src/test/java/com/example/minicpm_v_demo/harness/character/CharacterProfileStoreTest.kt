package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.data.database.databaseTestSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CharacterProfileStoreTest {
    private val loader = CharacterJsonLoader(
        charactersDir = databaseTestSource().resolve("characters"),
        storyEventsFile = databaseTestSource().resolve("story/story_events.jsonl"),
        promptTemplateFile = File("unused-template"),
    )
    private val analyzer = CharacterQueryAnalyzer()
    private val store = CharacterProfileStore()

    @Test
    fun cardProfileIsAtomizedIntoStableEvidenceLinkedRecords() {
        val card = loader.loadCharacter("luo_ji")
        val records = store.allRecords(card)

        assertTrue(records.any { it.id == "profile_identity" && it.facet == ProfileFacet.IDENTITY })
        assertTrue(records.any { it.id == "profile_background_0" && it.evidenceRefs.isNotEmpty() })
        assertTrue(records.any { it.id == "profile_motivation_0" && it.evidenceRefs.isNotEmpty() })
        assertTrue(records.none { it.statement.isBlank() })
    }

    @Test
    fun preferenceRetrievalUsesMotivationRecordsWithoutScanningStoryFacts() {
        val card = loader.loadCharacter("luo_ji")
        val analysis = analyzer.analyze("你最想去哪里？", listOf("史强"))

        val retrieval = store.retrieve(card, "你最想去哪里？", analysis, limit = 2)

        assertEquals(2, retrieval.matches.size)
        assertEquals(ProfileFacet.MOTIVATION, retrieval.matches.first().record.facet)
        assertTrue(retrieval.matches.all { it.record.facet in setOf(ProfileFacet.MOTIVATION, ProfileFacet.VALUES) })
        assertTrue(retrieval.candidateCount >= retrieval.matches.size)
    }
}
