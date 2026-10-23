package com.example.minicpm_v_demo.harness.rag

import android.content.Context

/**
 * Small, injectable kill-switch surface for Phase 0 rollout.  Defaults keep
 * vector retrieval mandatory for every RAG/CHARACTER_RAG turn.  OFF remains
 * offline unless an explicit diagnostic experiment opts in.
 */
data class VectorRetrievalFeatureFlags(
    val alwaysOnVectorRetrieval: Boolean = true,
    val retrieveInOffMode: Boolean = false,
) {
    companion object {
        private const val PREFS_NAME = "rag_retrieval_flags"
        private const val KEY_ALWAYS_ON = "always_on_vector_retrieval"
        private const val KEY_RETRIEVE_IN_OFF_MODE = "retrieve_in_off_mode"

        fun from(context: Context): VectorRetrievalFeatureFlags {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return VectorRetrievalFeatureFlags(
                alwaysOnVectorRetrieval = prefs.getBoolean(KEY_ALWAYS_ON, true),
                retrieveInOffMode = prefs.getBoolean(KEY_RETRIEVE_IN_OFF_MODE, false),
            )
        }
    }
}

/**
 * Phase 0 contract for the external-document vector index.
 *
 * Callers use this for every non-OFF turn, even when policy later decides that
 * external text must not enter the prompt.  This makes "we queried the vector
 * index" independently auditable from "we injected external evidence".
 */
class AlwaysOnVectorRetrievalService(
    private val ragSearchService: RagSearchService,
) {
    fun searchAlways(
        query: String,
        candidateLimit: Int,
        minScore: Double,
    ): AlwaysOnVectorRetrievalResult {
        require(candidateLimit > 0) { "candidateLimit must be positive" }
        val startedAtNanos = System.nanoTime()
        return try {
            val results = ragSearchService.search(query, candidateLimit, minScore)
            AlwaysOnVectorRetrievalResult(
                attempt = VectorRetrievalAttempt(
                    executed = true,
                    collection = EXTERNAL_UNTRUSTED_COLLECTION,
                    candidateLimit = candidateLimit,
                    candidateIds = results.map { result -> result.chunk.stableId() },
                    elapsedMs = elapsedMs(startedAtNanos),
                    backend = ragSearchService.embeddingBackendName,
                ),
                results = results,
            )
        } catch (error: Exception) {
            // Retrieval failures must not disable the existing safe character
            // stores or prevent a user from chatting.  The trace records the
            // failed attempted lookup rather than claiming no lookup happened.
            AlwaysOnVectorRetrievalResult(
                attempt = VectorRetrievalAttempt(
                    executed = true,
                    collection = EXTERNAL_UNTRUSTED_COLLECTION,
                    candidateLimit = candidateLimit,
                    candidateIds = emptyList(),
                    elapsedMs = elapsedMs(startedAtNanos),
                    backend = ragSearchService.embeddingBackendName,
                    failureCode = error.javaClass.simpleName,
                ),
                results = emptyList(),
            )
        }
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND

    companion object {
        const val EXTERNAL_UNTRUSTED_COLLECTION = "external_untrusted"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

data class AlwaysOnVectorRetrievalResult(
    val attempt: VectorRetrievalAttempt,
    val results: List<SearchResult>,
)

/** Content-free metadata for exactly one vector-index call. */
data class VectorRetrievalAttempt(
    val executed: Boolean,
    val collection: String,
    val candidateLimit: Int,
    val candidateIds: List<String>,
    val elapsedMs: Long,
    val backend: String,
    val failureCode: String? = null,
) {
    companion object {
        fun notRequested(backend: String = "not_requested"): VectorRetrievalAttempt = VectorRetrievalAttempt(
            executed = false,
            collection = AlwaysOnVectorRetrievalService.EXTERNAL_UNTRUSTED_COLLECTION,
            candidateLimit = 0,
            candidateIds = emptyList(),
            elapsedMs = 0L,
            backend = backend,
        )
    }
}

private fun RagChunk.stableId(): String = "$sourcePath@$start:$end"
