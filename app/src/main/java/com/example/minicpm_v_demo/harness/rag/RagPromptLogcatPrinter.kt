package com.example.minicpm_v_demo.harness.rag

import android.util.Log

/** Debug-only raw prompt printer. It has no file output and never mutates prompts. */
object RagPromptLogcatPrinter {
    const val TAG = "RagPromptDebug"
    private const val MAX_CHUNK_CHARS = 900

    fun print(turnId: String, stageLogs: List<RagPromptStageLog>) {
        stageLogs.forEachIndexed { index, stageLog ->
            val sequence = index + 1
            val prefix = "RAG_PROMPT turn=$turnId seq=$sequence stage=${stageLog.stage.name}"
            if (stageLog.status == RagPromptStageStatus.SKIPPED) {
                Log.d(TAG, "$prefix SKIPPED reason=${stageLog.reason ?: "unspecified"}")
                return@forEachIndexed
            }
            val snapshot = requireNotNull(stageLog.snapshot)
            val body = snapshot.displayText()
            val chunks = splitForLogcat(body)
            Log.d(
                TAG,
                "$prefix BEGIN chars=${body.length} systemChars=${snapshot.systemPrompt?.length ?: 0} " +
                    "userChars=${snapshot.userPrompt?.length ?: 0} renderedChars=${snapshot.renderedPrompt?.length ?: 0}",
            )
            chunks.forEachIndexed { chunkIndex, chunk ->
                Log.d(TAG, "$prefix CHUNK ${chunkIndex + 1}/${chunks.size}\n$chunk")
            }
            Log.d(TAG, "$prefix END")
        }
    }

    /** Kept pure for JVM tests and split on code-point boundaries. */
    internal fun splitForLogcat(text: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
        require(maxChars > 1) { "maxChars must be greater than one" }
        if (text.isEmpty()) return listOf("")
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChars, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end -= 1
            if (end == start) end = minOf(start + 2, text.length)
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }
}
