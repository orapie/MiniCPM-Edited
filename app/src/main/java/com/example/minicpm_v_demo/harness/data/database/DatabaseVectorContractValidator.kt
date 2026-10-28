package com.example.minicpm_v_demo.harness.data.database

import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue
import kotlin.math.abs

/** Validate provenance before VectorIndex consumes the schema-1.1 artifact. */
internal object DatabaseVectorContractValidator {
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
        require(actualEmbedding == embedding && index.stringValue("schema_version") == "1.1") { "Index embedding/schema mismatch" }
        require(index.stringValue("dataset_id") == manifest.datasetId &&
            index.stringValue("dataset_version") == manifest.datasetVersion &&
            index.stringValue("source_database_sha256") == databaseHash) { "Index dataset/source mismatch" }
        val records = (candidates + facts).associateBy { it.stringValue("claim_id") }
        require(records.size == candidates.size + facts.size) { "Vector record ID collision" }
        val candidateIds = candidates.map { it.stringValue("claim_id") }.toSet()
        val proofByClaim = proofs.associateBy { it.stringValue("claim_id") }
        val reviewById = reviews.associateBy { it.stringValue("claim_id") }
        val seen = mutableSetOf<String>()
        index["items"].asJsonArray("vector items").forEach { raw ->
            val item = raw.asJsonObject("vector item")
            val chunk = item["chunk"].asJsonObject("chunk")
            val id = chunk.stringValue("id")
            require(seen.add(id) && id in records) { "Unknown/duplicate vector record" }
            val metadata = chunk["metadata"].asJsonObject("metadata")
            val candidate = id in candidateIds
            val proof = if (candidate) proofByClaim.getValue(id) else reviewById.getValue(id)
            require(chunk.stringValue("text") == records.getValue(id).stringValue("statement") &&
                chunk.stringValue("source_path") == "database/${manifest.paths.getValue("v2_database")}" &&
                chunk["start"] == proof["line_start"] && chunk["end"] == proof["line_end"]) { "Vector text/evidence mismatch" }
            require(metadata["dataset_id"] == manifest.datasetId && metadata["dataset_version"] == manifest.datasetVersion &&
                metadata["claim_id"] == id && metadata["source_database_sha256"] == databaseHash &&
                metadata["authorization_required"] == "true" &&
                metadata["record_kind"] == if (candidate) "scene_candidate" else "reviewed_fact") { "Vector metadata mismatch" }
            if (!candidate) {
                require(metadata["character_id"] == proof["character_id"] &&
                    metadata["parent_claim_id"] == records.getValue(id)["parent_claim_id"]) { "Vector authorization provenance mismatch" }
            }
            val vector = item["embedding"].asJsonArray("embedding")
            require(vector.size == 384 && vector.all { it is Double && it.isFinite() }) { "Invalid vector dimension/value" }
            require(abs(vector.sumOf { (it as Double) * it } - 1.0) < 1e-9) { "Vector must be L2 normalized" }
        }
        require(seen == records.keys) { "Incomplete vector index" }
    }
}
