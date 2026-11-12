package com.example.minicpm_v_demo.harness.context

/** In-memory-only history result used by the next prompt; raw dialogue is never persisted here. */
data class CompressedConversationContext(
    val text: String,
    val sourceTurnCount: Int,
    val sourceChars: Int,
    val fingerprint: String,
) {
    val isEmpty: Boolean get() = text.isBlank()

    companion object {
        val EMPTY = CompressedConversationContext("", 0, 0, "none")
    }
}
