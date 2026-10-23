package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.data.HarnessDataPaths

class RagSearchService(
    private val index: VectorIndex,
    private val embeddingModel: EmbeddingModel = HashEmbeddingModel(),
) {
    /** Diagnostic-only identifier; no query text or vector values are exposed. */
    val embeddingBackendName: String
        get() = when (embeddingModel) {
            is HashEmbeddingModel -> "hash_ngram"
            else -> embeddingModel::class.java.simpleName.ifBlank { "custom_embedding" }
        }

    fun search(
        query: String,
        topK: Int = 5,
        minScore: Double = 0.0,
    ): List<SearchResult> {
        require(minScore in -1.0..1.0) { "minScore must be between -1 and 1" }
        return index.search(query, embeddingModel, topK).filter { it.score >= minScore }
    }

    companion object {
        fun fromPaths(paths: HarnessDataPaths): RagSearchService {
            return RagSearchService(VectorIndex.load(paths.ragIndexFile))
        }
    }
}
