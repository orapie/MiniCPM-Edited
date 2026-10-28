package com.example.minicpm_v_demo.harness.data.database

import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue
import java.security.MessageDigest

data class DatabaseBundleManifest(
    val schemaVersion: String,
    val datasetId: String,
    val datasetVersion: String,
    val defaultCharacterId: String,
    val defaultStoryCutoff: String,
    val authorizationPolicy: String,
    val capabilities: Map<String, Boolean>,
    val paths: Map<String, String>,
    val files: Map<String, String>,
    val datasetFingerprint: String,
    val embedding: Map<String, String> = emptyMap(),
) {
    fun supports(capability: String): Boolean = capabilities[capability] == true

    /** Canonical sorted lines; excludes only the fingerprint itself. */
    fun computeFingerprint(): String {
        val values = sortedMapOf(
            "bundle_schema_version" to schemaVersion,
            "dataset_id" to datasetId,
            "dataset_version" to datasetVersion,
            "default_character_id" to defaultCharacterId,
            "default_story_cutoff" to defaultStoryCutoff,
            "authorization_policy" to authorizationPolicy,
        )
        capabilities.forEach { (name, value) -> values["capabilities.$name"] = value.toString() }
        paths.forEach { (name, value) -> values["paths.$name"] = value }
        embedding.forEach { (name, value) -> values["embedding.$name"] = value }
        files.forEach { (name, value) -> values["files.$name"] = value }
        require(values.all { (key, value) -> '\n' !in key && '\r' !in key && '\n' !in value && '\r' !in value }) {
            "Database manifest values contain line breaks"
        }
        val text = "database-bundle-v1\n" + values.entries.joinToString("") { (key, value) -> "$key=$value\n" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val AUTHORIZATION_POLICY = "source_span_and_reviewed_dialogue_v1"

        fun parse(text: String): DatabaseBundleManifest {
            val root = SimpleJsonParser(text).parse().asJsonObject("database bundle manifest")
            val capabilities = root["capabilities"].asJsonObject("capabilities").mapValues { (name, value) ->
                value as? Boolean ?: error("Capability $name must be boolean")
            }
            val paths = root["paths"].asJsonObject("paths").mapValues { (name, value) ->
                value as? String ?: error("Path $name must be a string")
            }
            val fileRows = root["files"].asJsonArray("files").map { raw ->
                val file = raw.asJsonObject("file")
                file.stringValue("path") to file.stringValue("sha256")
            }
            require(fileRows.map { it.first }.distinct().size == fileRows.size) { "Duplicate database file entries" }
            return DatabaseBundleManifest(
                schemaVersion = root.stringValue("bundle_schema_version"),
                datasetId = root.stringValue("dataset_id"),
                datasetVersion = root.stringValue("dataset_version"),
                defaultCharacterId = root.stringValue("default_character_id"),
                defaultStoryCutoff = root.stringValue("default_story_cutoff"),
                authorizationPolicy = root.stringValue("authorization_policy"),
                capabilities = capabilities,
                paths = paths,
                files = fileRows.toMap(),
                datasetFingerprint = root.stringValue("dataset_fingerprint"),
                embedding = root["embedding"]?.asJsonObject("embedding")?.mapValues { (_, value) ->
                    when (value) {
                        is String -> value
                        is Double -> {
                            require(value.isFinite() && value % 1.0 == 0.0) { "Invalid embedding parameter" }
                            value.toLong().toString()
                        }
                        else -> error("Unsupported embedding parameter")
                    }
                }.orEmpty(),
            )
        }
    }
}
