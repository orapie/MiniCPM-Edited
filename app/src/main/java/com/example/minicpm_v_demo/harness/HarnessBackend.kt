package com.example.minicpm_v_demo.harness

import android.util.Log
import com.example.minicpm_v_demo.LlamaState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** 表示一次后端生成请求所需的提示词和诊断元数据。 */
data class HarnessChatRequest(
    val userPrompt: String,
    val systemPrompt: String? = null,
    /** Correlates prompt preflight logs with optional caller-side diagnostics. */
    val observationId: String? = null,
) {
    init {
        require(userPrompt.isNotBlank()) { "User prompt must not be blank" }
        require(systemPrompt == null || systemPrompt.isNotBlank()) {
            "System prompt must be null or non-blank"
        }
        require(observationId == null || observationId.isNotBlank()) {
            "Observation id must be null or non-blank"
        }
    }
}

/** 应用层与推理实现之间的契约。统一模型生命周期、生成流和取消操作。 */
interface HarnessBackend {
    companion object {
        private const val TAG = "HarnessBackend"
    }

    val state: StateFlow<LlamaState>

    suspend fun loadModel(modelPath: String)
    suspend fun unloadModel()
    suspend fun setSystemPrompt(prompt: String)
    suspend fun clearContext()
    fun sendUserPrompt(message: String, predictLength: Int): Flow<String>

    /** Exact tokenizer count when the active backend exposes one; null for test/mock backends. */
    suspend fun countPromptTokens(prompt: String): Int? = null

    /**
     * Sends a role-aware chat request to the runtime.
     *
     * A structured prompt starts from a fresh context because the native runtime only accepts a
     * system message immediately after model load/reset. This also prevents a previous turn from
     * changing the authority or position of the new system message. Conversation history needed by
     * character mode is already supplied explicitly by the Harness prompt compiler.
     */
    fun sendChatPrompt(request: HarnessChatRequest, predictLength: Int): Flow<String> = flow {
        val systemTokens = request.systemPrompt?.let { countPromptTokens(it) }
        val userTokens = countPromptTokens(request.userPrompt)
        if (systemTokens != null || userTokens != null) {
            Log.i(
                TAG,
                "Prompt preflight tokens: system=${systemTokens ?: "n/a"}, " +
                    "user=${userTokens ?: "n/a"}, " +
                    "total=${if (systemTokens != null && userTokens != null) systemTokens + userTokens else "n/a"}, " +
                    "observationId=${request.observationId ?: "n/a"}",
            )
        }
        request.systemPrompt?.let { systemPrompt ->
            clearContext()
            setSystemPrompt(systemPrompt)
        }
        emitAll(sendUserPrompt(request.userPrompt, predictLength))
    }

    fun cancelGeneration()
    fun resetToInitialized()
    fun destroy()
}
