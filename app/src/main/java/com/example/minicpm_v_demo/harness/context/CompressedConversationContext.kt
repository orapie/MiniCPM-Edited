package com.example.minicpm_v_demo.harness.context

/** In-memory-only history result used by the next prompt; raw dialogue is never persisted here. */
data class CompressedConversationContext(
    val text: String,
    val sourceTurnCount: Int,
    val sourceChars: Int,
    val fingerprint: String,
) {
    val isEmpty: Boolean get() = text.isBlank()

    /** 无可用历史时使用统一空对象，诊断计数和指纹也采用空上下文的约定值。 */
    companion object {
        val EMPTY = CompressedConversationContext("", 0, 0, "none")
    }
}
