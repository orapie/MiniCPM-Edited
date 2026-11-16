package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleHandle

/** 把编码器和向量索引组合成检索入口。BGE 路径开启混合检索时，会合并语义与词法排名。 */
class RagSearchService(
    private val index: VectorIndex,
    private val embeddingModel: EmbeddingModel = HashEmbeddingModel(),
    val collectionName: String = "synthetic_test",
    private val hybridEnabled: Boolean = false,
) : AutoCloseable {
    val retrievalMode: String get() = if (hybridEnabled && embeddingModel is BgeOnnxEmbeddingModel) "hybrid_rrf" else "semantic_or_hash"
    val indexSchemaVersion: String get() = index.semanticIndexId?.let { "${index.schemaVersion}/$it" } ?: index.schemaVersion
    /** Diagnostic-only identifier; no query text or vector values are exposed. */
    val embeddingBackendName: String
        get() = when (embeddingModel) {
            is HashEmbeddingModel -> "hash_ngram"
            is BgeOnnxEmbeddingModel -> "bge_onnx"
            else -> embeddingModel::class.java.simpleName.ifBlank { "custom_embedding" }
        }

    fun search(
        query: String,
        topK: Int = 5,
        minScore: Double = 0.0,
        maxSourceEnd: Long? = null,
    ): List<SearchResult> {
        require(minScore in -1.0..1.0) { "minScore must be between -1 and 1" }
        val isBge = embeddingModel is BgeOnnxEmbeddingModel
        val semantic = index.search(query, embeddingModel, topK,
            if (isBge) maxSourceEnd else null).filter { it.score >= minScore }
        if (!isBge || !hybridEnabled) return semantic
        val lexical = index.lexicalSearch(query, topK, maxSourceEnd)
        data class Candidate(var semantic: SearchResult? = null, var lexical: SearchResult? = null,
            var semanticRank: Int? = null, var lexicalRank: Int? = null)
        val candidates = linkedMapOf<String, Candidate>()
        fun key(result: SearchResult) = result.chunk.metadata["evidence_id"] ?: result.chunk.id
        semantic.forEachIndexed { rank, result ->
            candidates.getOrPut(key(result)) { Candidate() }.apply { this.semantic = result; semanticRank = rank + 1 }
        }
        lexical.forEachIndexed { rank, result ->
            candidates.getOrPut(key(result)) { Candidate() }.apply { this.lexical = result; lexicalRank = rank + 1 }
        }
        // RRF is rank-based; the old cosine minScore is applied only above, never to RRF.
        return candidates.values.map { candidate ->
            val score = (candidate.semanticRank?.let { 1.0 / (60 + it) } ?: 0.0) +
                (candidate.lexicalRank?.let { 1.0 / (60 + it) } ?: 0.0)
            val base = candidate.semantic ?: requireNotNull(candidate.lexical)
            base.copy(score = score, chunkScore = candidate.semantic?.chunkScore,
                lexicalScore = candidate.lexical?.lexicalScore, semanticRank = candidate.semanticRank,
                lexicalRank = candidate.lexicalRank, fusionScore = score)
        }.sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.chunk.id }).take(topK)
    }

    override fun close() {
        (embeddingModel as? AutoCloseable)?.close()
    }

    companion object {
        fun fromBundle(bundle: DatabaseBundleHandle, hybridEnabled: Boolean = false): RagSearchService {
            require(bundle.manifest.supports("vector")) { "database_vector_unsupported" }
            if ("bge_index" in bundle.manifest.paths) {
                val descriptor = SimpleJsonParser(bundle.paths.resolve(
                    bundle.manifest.paths.getValue("semantic_index")).readText(Charsets.UTF_8))
                    .parse().asJsonObject("semantic descriptor")
                val encoder = BgeOnnxEmbeddingModel(
                    bundle.paths.resolve(bundle.manifest.paths.getValue("bge_model")),
                    bundle.paths.resolve(bundle.manifest.paths.getValue("bge_vocab")),
                    descriptor.stringValue("query_instruction"),
                )
                return RagSearchService(VectorIndex.load(bundle.paths.resolve(bundle.manifest.paths.getValue("bge_index"))),
                    encoder, "database:${bundle.manifest.datasetId}:${bundle.manifest.datasetFingerprint}", hybridEnabled)
            }
            return RagSearchService(VectorIndex.load(bundle.paths.resolve(
                bundle.manifest.paths.getValue("vector_index"))),
                collectionName = "database:${bundle.manifest.datasetId}:${bundle.manifest.datasetFingerprint}")
        }


    }
}
