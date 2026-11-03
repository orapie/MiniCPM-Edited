package com.example.minicpm_v_demo.harness.data.database

import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue
import com.example.minicpm_v_demo.harness.rag.HashEmbeddingModel
import kotlin.math.abs
import java.security.MessageDigest

/** Validate provenance before VectorIndex consumes a supported index artifact. */
internal object DatabaseVectorContractValidator {
    @Volatile private var cachedParentKey: String? = null
    @Volatile private var cachedParents: Map<String, DoubleArray> = emptyMap()
    private val cachedFragments = object : LinkedHashMap<String, DoubleArray>(2048, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DoubleArray>?): Boolean = size > 2048
    }
    val embedding = mapOf("type" to "hash_ngram", "dimensions" to "384", "ngram_min" to "2", "ngram_max" to "4",
        "hash_algorithm" to "blake2b-64", "bucket_byte_order" to "big", "sign_byte_index" to "4",
        "normalization" to "l2", "empty_text" to "zero_vector")

    fun validate(
        index: Map<String, Any?>,
        manifest: DatabaseBundleManifest,
        databaseHash: String,
        candidates: List<Map<String, Any?>>,
        proofs: List<Map<String, Any?>>,
        facts: List<Map<String, Any?>>,
        reviews: List<Map<String, Any?>>,
    ) {
        require(manifest.embedding == embedding) { "Embedding configuration mismatch" }
        val actualEmbedding = index["embedding"].asJsonObject("index embedding").mapValues { (_, raw) ->
            if (raw is Double && raw.isFinite() && raw % 1.0 == 0.0) raw.toLong().toString() else raw.toString()
        }
        val schema = index.stringValue("schema_version")
        require(actualEmbedding == embedding && schema in setOf("1.1", "1.2")) { "Index embedding/schema mismatch" }
        require(index.stringValue("dataset_id") == manifest.datasetId &&
            index.stringValue("dataset_version") == manifest.datasetVersion &&
            index.stringValue("source_database_sha256") == databaseHash) { "Index dataset/source mismatch" }
        val records = (candidates + facts).associateBy { it.stringValue("claim_id") }
        require(records.size == candidates.size + facts.size) { "Vector record ID collision" }
        val candidateIds = candidates.map { it.stringValue("claim_id") }.toSet()
        if (schema == "1.2") {
            val parents = index["parent_embeddings"].asJsonObject("parent embeddings")
            require(parents.keys == candidateIds) { "Invalid parent embedding inventory" }
            val expectedParents = expectedParentEmbeddings(candidates)
            parents.forEach { (id, raw) ->
                val values = raw.asJsonArray("parent embedding")
                require(values.size == 384 && values.all { it is Double && it.isFinite() }) { "Invalid parent embedding" }
                val expected = expectedParents.getValue(id)
                require(values.indices.all { abs((values[it] as Double) - expected[it]) < 1e-9 }) { "Parent embedding mismatch" }
            }
        }
        val proofByClaim = proofs.associateBy { it.stringValue("claim_id") }
        val reviewById = reviews.associateBy { it.stringValue("claim_id") }
        val seen = mutableSetOf<String>()
        val covered = mutableMapOf<String, MutableList<IntRange>>()
        index["items"].asJsonArray("vector items").forEach { raw ->
            val item = raw.asJsonObject("vector item")
            val chunk = item["chunk"].asJsonObject("chunk")
            val id = chunk.stringValue("id")
            require(seen.add(id)) { "Unknown/duplicate vector record" }
            val metadata = chunk["metadata"].asJsonObject("metadata")
            val claimId = metadata.stringValue("claim_id")
            require(claimId in records) { "Unknown vector parent" }
            val candidate = claimId in candidateIds
            val proof = if (candidate) proofByClaim.getValue(claimId) else reviewById.getValue(claimId)
            val statement = records.getValue(claimId).stringValue("statement")
            val fragmented = schema == "1.2" && candidate
            if (fragmented) {
                val begin = metadata.stringValue("offset_start").toIntOrNull()
                val end = metadata.stringValue("offset_end").toIntOrNull()
                val count = statement.codePointCount(0, statement.length)
                require(begin != null && end != null && begin >= 0 && end > begin && end <= count && end - begin <= 300 &&
                    metadata["parent_claim_id"] == claimId && metadata["segmentation_version"] == "scene-window-v1" &&
                    metadata["offset_unit"] == "unicode_code_point" && id == "$claimId@$begin-$end") { "Invalid vector fragment range" }
                val from = statement.offsetByCodePoints(0, begin)
                val to = statement.offsetByCodePoints(0, end)
                val baseLine = (proof["line_start"] as? Double)?.toInt() ?: error("Invalid evidence line")
                val firstLine = baseLine + statement.substring(0, from).count { it == '\n' }
                val lastChar = statement.offsetByCodePoints(0, end - 1)
                val lastLine = baseLine + statement.substring(0, lastChar).count { it == '\n' }
                require(chunk.stringValue("text") == statement.substring(from, to) &&
                    chunk["start"] == firstLine.toDouble() && chunk["end"] == lastLine.toDouble()) { "Vector text/evidence mismatch" }
                covered.getOrPut(claimId) { mutableListOf() }.add(begin until end)
            } else {
                require(id == claimId && chunk.stringValue("text") == statement &&
                    chunk["start"] == proof["line_start"] && chunk["end"] == proof["line_end"]) { "Vector text/evidence mismatch" }
            }
            require(chunk.stringValue("source_path") == "database/${manifest.paths.getValue("v2_database")}") { "Vector text/evidence mismatch" }
            require(metadata["dataset_id"] == manifest.datasetId && metadata["dataset_version"] == manifest.datasetVersion &&
                metadata["claim_id"] == claimId && metadata["source_database_sha256"] == databaseHash &&
                metadata["authorization_required"] == "true" &&
                metadata["record_kind"] == if (candidate) "scene_candidate" else "reviewed_fact") { "Vector metadata mismatch" }
            if (!candidate) {
                require(metadata["character_id"] == proof["character_id"] &&
                    metadata["parent_claim_id"] == records.getValue(claimId)["parent_claim_id"]) { "Vector authorization provenance mismatch" }
            }
            val vector = item["embedding"].asJsonArray("embedding")
            require(vector.size == 384 && vector.all { it is Double && it.isFinite() }) { "Invalid vector dimension/value" }
            require(abs(vector.sumOf { (it as Double) * it } - 1.0) < 1e-9) { "Vector must be L2 normalized" }
            if (schema == "1.2") {
                val expected = expectedFragmentEmbedding(chunk.stringValue("text"))
                require(vector.indices.all { abs((vector[it] as Double) - expected[it]) < 1e-9 }) { "Vector embedding mismatch" }
            }
        }
        if (schema == "1.2") {
            require(covered.keys == candidateIds && seen.containsAll(records.keys - candidateIds)) { "Incomplete vector index" }
            covered.forEach { (id, ranges) ->
                val text = records.getValue(id).stringValue("statement")
                val points = text.codePoints().toArray()
                val coverage = BooleanArray(points.size)
                ranges.forEach { range -> range.forEach { coverage[it] = true } }
                require(points.indices.all { coverage[it] || Character.isWhitespace(points[it]) }) { "Incomplete vector fragment coverage" }
            }
        } else require(seen == records.keys) { "Incomplete vector index" }
    }

    @Synchronized
    private fun expectedFragmentEmbedding(text: String): DoubleArray {
        val key = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return cachedFragments.getOrPut(key) { HashEmbeddingModel().embedOne(text) }
    }

    @Synchronized
    private fun expectedParentEmbeddings(candidates: List<Map<String, Any?>>): Map<String, DoubleArray> {
        // The cache key includes all source statements, so a changed bundle or
        // tampered candidate cannot reuse embeddings from another dataset.
        val digest = MessageDigest.getInstance("SHA-256")
        val sorted = candidates.sortedBy { it.stringValue("claim_id") }
        sorted.forEach {
            for (value in listOf(it.stringValue("claim_id"), it.stringValue("statement"))) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(), bytes.size.toByte()))
                digest.update(bytes)
            }
        }
        val key = digest.digest().joinToString("") { "%02x".format(it) }
        if (key == cachedParentKey) return cachedParents
        val model = HashEmbeddingModel()
        val computed = sorted.associate { it.stringValue("claim_id") to model.embedOne(it.stringValue("statement")) }
        cachedParents = computed
        cachedParentKey = key
        return computed
    }
}
