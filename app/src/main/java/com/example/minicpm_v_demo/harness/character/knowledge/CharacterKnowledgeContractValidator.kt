package com.example.minicpm_v_demo.harness.character.knowledge

import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue
import java.io.File

enum class CharacterKnowledgeGraphVersion {
    V1,
    V2,
}

data class CharacterKnowledgeManifest(
    val version: CharacterKnowledgeGraphVersion,
    val schemaVersion: String,
    val cutoffs: Map<String, Long>,
    val databaseFile: File? = null,
)

/**
 * Validates the on-disk contract of the two character-knowledge bundles before a
 * later phase makes either bundle part of the runtime retrieval path.
 *
 * This class deliberately does not select facts or construct prompts. A failed
 * validation is a reason to keep the existing story-events path, not to infer a
 * permissive cutoff or a default character.
 */
object CharacterKnowledgeContractValidator {
    private const val V1_MANIFEST = "manifest.json"
    private const val V1_CLAIMS = "claims.jsonl"
    private const val V1_RELATIONS = "relations.jsonl"
    private const val V2_DATABASE = "character_knowledge_v2.sqlite3"

    fun validateV1(
        bundleDir: File,
        requiredCharacterIds: Set<String> = emptySet(),
        requiredCutoffs: Set<String> = emptySet(),
    ): CharacterKnowledgeManifest {
        require(bundleDir.isDirectory) { "V1 knowledge bundle is not a directory: ${bundleDir.absolutePath}" }
        val manifestFile = File(bundleDir, V1_MANIFEST)
        val claimsFile = File(bundleDir, V1_CLAIMS)
        val relationsFile = File(bundleDir, V1_RELATIONS)
        require(manifestFile.isFile) { "Missing V1 manifest: ${manifestFile.absolutePath}" }
        require(claimsFile.isFile) { "Missing V1 claims: ${claimsFile.absolutePath}" }
        require(relationsFile.isFile) { "Missing V1 relations: ${relationsFile.absolutePath}" }

        val manifest = parseManifest(manifestFile, CharacterKnowledgeGraphVersion.V1)
        require(manifest.schemaVersion.startsWith("1.")) {
            "Unsupported V1 schema version: ${manifest.schemaVersion}"
        }
        require(manifest.cutoffs.isNotEmpty()) { "V1 manifest has no cutoffs" }
        require(requiredCutoffs.all(manifest.cutoffs::containsKey)) {
            "V1 manifest is missing required cutoffs: ${requiredCutoffs - manifest.cutoffs.keys}"
        }
        validateV1Claims(claimsFile, requiredCharacterIds)
        return manifest
    }

    fun validateV2(
        bundleDir: File,
        requiredCutoffs: Set<String> = emptySet(),
    ): CharacterKnowledgeManifest {
        require(bundleDir.isDirectory) { "V2 knowledge bundle is not a directory: ${bundleDir.absolutePath}" }
        val manifestFile = File(bundleDir, V1_MANIFEST)
        val databaseFile = File(bundleDir, V2_DATABASE)
        require(manifestFile.isFile) { "Missing V2 manifest: ${manifestFile.absolutePath}" }
        require(databaseFile.isFile && databaseFile.length() > 0) {
            "Missing or empty V2 database: ${databaseFile.absolutePath}"
        }

        val manifest = parseManifest(manifestFile, CharacterKnowledgeGraphVersion.V2)
        require(manifest.schemaVersion.startsWith("2.")) {
            "Unsupported V2 schema version: ${manifest.schemaVersion}"
        }
        require(requiredCutoffs.all(manifest.cutoffs::containsKey)) {
            "V2 manifest is missing required cutoffs: ${requiredCutoffs - manifest.cutoffs.keys}"
        }
        return manifest.copy(databaseFile = databaseFile)
    }

    private fun parseManifest(file: File, version: CharacterKnowledgeGraphVersion): CharacterKnowledgeManifest {
        val root = SimpleJsonParser(file.readText(Charsets.UTF_8)).parse().asJsonObject("knowledge manifest")
        val schemaVersion = root.stringValue("schema_version")
        if (version == CharacterKnowledgeGraphVersion.V2) {
            require(root.stringValue("knowledge_base_version") == "v2") {
                "V2 manifest does not declare knowledge_base_version=v2"
            }
        }
        val rawCutoffs = root["cutoffs"].asJsonObject("cutoffs")
        val cutoffs = rawCutoffs.mapValues { (eventId, rawValue) ->
            when (rawValue) {
                is Double -> rawValue.toLong()
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val cutoff = rawValue as Map<String, Any?>
                    (cutoff["sequence"] as? Double)?.toLong()
                        ?: error("Cutoff $eventId has no numeric sequence")
                }
                else -> error("Cutoff $eventId has an unsupported value")
            }
        }
        return CharacterKnowledgeManifest(version, schemaVersion, cutoffs)
    }

    private fun validateV1Claims(claimsFile: File, requiredCharacterIds: Set<String>) {
        if (requiredCharacterIds.isEmpty()) return
        val found = mutableSetOf<String>()
        claimsFile.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank() || found.containsAll(requiredCharacterIds)) return@forEachLine
            val claim = SimpleJsonParser(line).parse().asJsonObject("V1 claim")
            val knownBy = claim["known_by"].asJsonObject("known_by")
            found += requiredCharacterIds.intersect(knownBy.keys)
        }
        require(found.containsAll(requiredCharacterIds)) {
            "V1 claims have no known_by entry for: ${requiredCharacterIds - found}"
        }
    }
}
