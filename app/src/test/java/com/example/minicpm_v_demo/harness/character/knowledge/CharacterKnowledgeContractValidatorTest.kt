package com.example.minicpm_v_demo.harness.character.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CharacterKnowledgeContractValidatorTest {
    @Test
    fun validatesTheCheckedInV1BundleForCurrentCharactersAndCutoffs() {
        val manifest = CharacterKnowledgeContractValidator.validateV1(
            bundleDir = File(characterRoot(), "character_kb_agent_bundle/kb"),
            requiredCharacterIds = setOf("lu_jiangxian", "xuan_an"),
            requiredCutoffs = setOf("evt-010", "evt-018"),
        )

        assertEquals(CharacterKnowledgeGraphVersion.V1, manifest.version)
        assertEquals("1.0.0", manifest.schemaVersion)
        assertTrue(manifest.cutoffs.getValue("evt-018") > manifest.cutoffs.getValue("evt-010"))
    }

    @Test
    fun validatesTheCheckedInV2BundleAndPreservesSequenceBasedCutoffs() {
        val manifest = CharacterKnowledgeContractValidator.validateV2(
            bundleDir = File(characterRoot(), "character_kb_v2_agent_bundle/kb"),
            requiredCutoffs = setOf("evt-010", "evt-018", "evt-021"),
        )

        assertEquals(CharacterKnowledgeGraphVersion.V2, manifest.version)
        assertEquals("2.0.0", manifest.schemaVersion)
        assertTrue(manifest.databaseFile?.isFile == true)
        assertTrue(manifest.cutoffs.getValue("evt-021") < manifest.cutoffs.getValue("evt-020"))
    }

    private fun characterRoot(): File = listOf(File("character"), File("../character"))
        .firstOrNull(File::isDirectory)
        ?: error("Cannot locate the checked-in character knowledge bundles")
}
