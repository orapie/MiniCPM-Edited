package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.data.harnessAssetsDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CharacterStateLoaderTest {
    @Test
    fun bundledStateIsUnavailableBeforeItsValidFromAndNeverTurnsNotCarriedIntoCarried() {
        val root = harnessAssetsDir()
        val loader = CharacterStateLoader(
            stateDir = File(root, "characters/state"),
            events = characterLoader().loadEvents(),
        )

        assertNull(loader.currentState("lu_jiangxian", "evt-010"))
        val state = requireNotNull(loader.currentState("lu_jiangxian", "evt-018"))
        assertEquals("lu-evt-018-after-door-decision", state.stateId)
        assertEquals(PossessionStatus.NOT_CARRIED, state.possessions.single().status)
    }

    @Test
    fun overlappingStateIntervalsAreRejectedFailClosed() {
        val directory = Files.createTempDirectory("overlapping-character-state").toFile()
        try {
            File(directory, "lu_jiangxian.json").writeText(overlappingFixture(), Charsets.UTF_8)
            val loader = CharacterStateLoader(directory, characterLoader().loadEvents())

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
                events = characterLoader().loadEvents(),
                knownCharacterIds = setOf("lu_jiangxian"),
            )

            assertTrue(runCatching { loader.currentState("unknown_npc", "evt-018") }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun characterLoader(): CharacterJsonLoader {
        val root = harnessAssetsDir()
        return CharacterJsonLoader(
            charactersDir = File(root, "characters/characters"),
            storyEventsFile = File(root, "characters/story/story_events.jsonl"),
            promptTemplateFile = File(root, "characters/prompts/roleplay_system.prompt"),
        )
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
