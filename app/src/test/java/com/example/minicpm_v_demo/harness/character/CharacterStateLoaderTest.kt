package com.example.minicpm_v_demo.harness.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CharacterStateLoaderTest {
    @Test
    fun stateRespectsStartAndDoesNotTreatHistoricalItemAsCarried() {
        val directory = Files.createTempDirectory("character-state").toFile()
        try {
            File(directory, "lu_jiangxian.json").writeText("""{"schema_version":"1.0","character_id":"lu_jiangxian","states":[{"state_id":"test","valid_from":"evt-018","valid_to":null,"scene":"甲","quest_state":"乙","current_goal":"丙","evidence_refs":["fixture:L1"],"possessions":[{"item_id":"item","name":"旧物","status":"not_carried","description":"已放下","keywords":["旧物"],"evidence_refs":["fixture:L2"]}]}]}""")
            val loader = CharacterStateLoader(directory, events())
            assertNull(loader.currentState("lu_jiangxian", "evt-010"))
            assertEquals(PossessionStatus.NOT_CARRIED, loader.currentState("lu_jiangxian", "evt-018")!!.possessions.single().status)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun overlappingStateIntervalsAreRejectedFailClosed() {
        val directory = Files.createTempDirectory("overlapping-character-state").toFile()
        try {
            File(directory, "lu_jiangxian.json").writeText(overlappingFixture(), Charsets.UTF_8)
            val loader = CharacterStateLoader(directory, events())

            assertTrue(runCatching { loader.currentState("lu_jiangxian", "evt-018") }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unknownCharacterStateAssetIsRejectedWhenTheKnownRosterIsSupplied() {
        val directory = Files.createTempDirectory("unknown-character-state").toFile()
        try {
            File(directory, "unknown_npc.json").writeText(singleStateFixture("unknown_npc"), Charsets.UTF_8)
            val loader = CharacterStateLoader(
                stateDir = directory,
                events = events(),
                knownCharacterIds = setOf("lu_jiangxian"),
            )

            assertTrue(runCatching { loader.currentState("unknown_npc", "evt-018") }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun events() = listOf("evt-010", "evt-018").mapIndexed { index, id ->
        StoryEvent(id, index, "synthetic", 1.0, 1.0, emptySet(), emptyList(), emptyMap())
    }

    private fun overlappingFixture(): String = """
        {"schema_version":"1.0","character_id":"lu_jiangxian","states":[
          {"state_id":"first","valid_from":"evt-010","valid_to":"evt-018","scene":"甲","quest_state":"甲","current_goal":"甲","evidence_refs":["fixture:L1"],"possessions":[]},
          {"state_id":"second","valid_from":"evt-018","valid_to":null,"scene":"乙","quest_state":"乙","current_goal":"乙","evidence_refs":["fixture:L2"],"possessions":[]}
        ]}
    """.trimIndent()

    private fun singleStateFixture(characterId: String): String = """
        {"schema_version":"1.0","character_id":"$characterId","states":[
          {"state_id":"only","valid_from":"evt-018","valid_to":null,"scene":"甲","quest_state":"甲","current_goal":"甲","evidence_refs":["fixture:L1"],"possessions":[]}
        ]}
    """.trimIndent()
}
