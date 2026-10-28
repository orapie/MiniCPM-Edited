package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleHandle

class RagSearchService(
    private val index: VectorIndex,
    private val embeddingModel: EmbeddingModel = HashEmbeddingModel(),
    val collectionName: String = "synthetic_test",
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
        fun fromBundle(bundle: DatabaseBundleHandle): RagSearchService {
            require(bundle.manifest.supports("vector")) { "database_vector_unsupported" }
            return RagSearchService(VectorIndex.load(bundle.paths.resolve(
                bundle.manifest.paths.getValue("vector_index"))),
                collectionName = "database:${bundle.manifest.datasetId}:${bundle.manifest.datasetFingerprint}")
        }


    }
}
