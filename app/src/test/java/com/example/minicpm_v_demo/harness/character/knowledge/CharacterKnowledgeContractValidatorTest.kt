package com.example.minicpm_v_demo.harness.character.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import com.example.minicpm_v_demo.harness.data.database.databaseTestSource

class CharacterKnowledgeContractValidatorTest {
    @Test
    fun validatesV1ContractWithMinimalSyntheticFixtureInsteadOfLegacyDatabase() {
        val temp = Files.createTempDirectory("v1-contract-fixture").toFile()
        try {
            File(temp, "manifest.json").writeText("""{"schema_version":"1.0.0","cutoffs":{"test-early":1,"test-late":2}}""")
            File(temp, "claims.jsonl").writeText("""{"known_by":{"test_character":{}}}""")
            File(temp, "relations.jsonl").writeText("")
            val manifest = CharacterKnowledgeContractValidator.validateV1(
                temp, setOf("test_character"), setOf("test-early", "test-late"),
            )
            assertEquals(CharacterKnowledgeGraphVersion.V1, manifest.version)
            assertTrue(manifest.cutoffs.getValue("test-late") > manifest.cutoffs.getValue("test-early"))
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun validatesCurrentDatabaseV2SourceManifest() {
        val manifest = CharacterKnowledgeContractValidator.validateV2(
            bundleDir = databaseTestSource().resolve("knowledge_base_v2"),
            requiredCutoffs = setOf("evt-010", "evt-018", "evt-021"),
        )

        assertEquals(CharacterKnowledgeGraphVersion.V2, manifest.version)
        assertEquals("2.0.0", manifest.schemaVersion)
        assertTrue(manifest.databaseFile?.isFile == true)
        assertTrue(manifest.cutoffs.getValue("evt-021") > manifest.cutoffs.getValue("evt-020"))
    }

}
