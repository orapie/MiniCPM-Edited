package com.example.minicpm_v_demo.harness

import android.content.Context
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 将 Harness 的推理接口映射到应用内共享的 LlamaEngine 实例。 */
class LlamaBackendAdapter(context: Context) : HarnessBackend {
    private val engine = LlamaEngine.getInstance(context.applicationContext)

    override val state: StateFlow<LlamaState>
        get() = engine.state

    override suspend fun loadModel(modelPath: String) {
        engine.loadModel(modelPath)
    }

    override suspend fun unloadModel() {
        engine.unloadModel()
    }

    override suspend fun setSystemPrompt(prompt: String) {
        engine.setSystemPrompt(prompt)
    }

    override suspend fun clearContext() {
        engine.clearContext()
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> =
        engine.sendUserPrompt(message, predictLength)

    override suspend fun countPromptTokens(prompt: String): Int =
        engine.countPromptTokens(prompt)

    override fun cancelGeneration() {
        engine.cancelGeneration()
    }

    override fun resetToInitialized() {
        engine.resetToInitialized()
    }

    override fun destroy() {
        engine.destroy()
    }
}
