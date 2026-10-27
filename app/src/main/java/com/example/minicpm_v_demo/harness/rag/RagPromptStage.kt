package com.example.minicpm_v_demo.harness.rag

/**
 * Transient, raw-text debugging stages for one RAG compilation.
 *
 * These records are deliberately separate from [RagTurnTrace]: they may contain
 * player text, character facts, and retrieved document text, so callers must
 * never serialize them to session storage or diagnostics JSONL.
 */
enum class RagPromptStage {
    RAG_CONTEXT_COMPILED,
    CHARACTER_BASE,
    V2_RETRIEVAL_RESULT,
    CHARACTER_AFTER_V2,
    V1_CLAIM_RETRIEVAL_RESULT,
    CHARACTER_AFTER_V1_CLAIM,
    V1_RELATIONSHIP_RETRIEVAL_RESULT,
    CHARACTER_AFTER_V1_RELATION,
    LEGACY_RETRIEVAL_RESULT,
    CHARACTER_AFTER_LEGACY_FALLBACK,
    CHARACTER_PROMPT_COMPILED,
    EXTERNAL_CONTEXT_COMPILED,
    FINAL_RENDERED,
    BACKEND_REQUEST,
}

enum class RagPromptStageStatus {
    EMITTED,
    SKIPPED,
}

data class RagPromptSnapshot(
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val renderedPrompt: String? = null,
    /** Human-readable source retrieval result; may say that no authorized candidates exist. */
    val retrievalText: String? = null,
) {
    init {
        require(systemPrompt != null || userPrompt != null || renderedPrompt != null || retrievalText != null) {
            "a prompt snapshot must contain text"
        }
    }

    fun displayText(): String = buildString {
        retrievalText?.let { result ->
            append("<RETRIEVAL_RESULT>\n")
            append(result)
            append("\n</RETRIEVAL_RESULT>")
        }
        renderedPrompt?.let { prompt ->
            if (isNotEmpty()) append("\n\n")
            append("<RENDERED_PROMPT>\n")
            append(prompt)
            append("\n</RENDERED_PROMPT>")
        }
        if (systemPrompt != null || userPrompt != null) {
            if (isNotEmpty()) append("\n\n")
            systemPrompt?.let { prompt ->
                append("<SYSTEM>\n")
                append(prompt)
                append("\n</SYSTEM>")
            }
            userPrompt?.let { prompt ->
                if (systemPrompt != null) append("\n\n")
                append("<USER>\n")
                append(prompt)
                append("\n</USER>")
            }
        }
    }
}

data class RagPromptStageLog(
    val stage: RagPromptStage,
    val status: RagPromptStageStatus,
    val reason: String? = null,
    val snapshot: RagPromptSnapshot? = null,
) {
    init {
        require((status == RagPromptStageStatus.EMITTED) == (snapshot != null)) {
            "emitted stage logs require a snapshot; skipped logs must not retain prompt text"
        }
    }

    companion object {
        fun emitted(stage: RagPromptStage, snapshot: RagPromptSnapshot): RagPromptStageLog =
            RagPromptStageLog(stage, RagPromptStageStatus.EMITTED, snapshot = snapshot)

        fun skipped(stage: RagPromptStage, reason: String): RagPromptStageLog =
            RagPromptStageLog(stage, RagPromptStageStatus.SKIPPED, reason = reason)
    }
}
