package com.example.minicpm_v_demo.harness.attribution

import android.content.Context
import com.example.minicpm_v_demo.AttributionLlamaEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Local-only Agent A adapter. It never falls back to a remote model and all
 * malformed/late output is converted into a normal Raw-Prompt fallback.
 */
class LocalAttributionRewriteService(
    context: Context,
    private val isForegroundRuntimeReady: () -> Boolean,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : FinalPromptRewriteService, AutoCloseable {
    private val modelStore = AttributionModelStore(context)
    private val featureFlags = AttributionFeatureFlags(context)
    private val engine = AttributionLlamaEngine(context)

    override val enabled: Boolean
        get() = featureFlags.localAgentAEnabled &&
            isForegroundRuntimeReady() &&
            modelStore.isSelectedModelDownloaded()

    override fun rewrite(request: AttributionAgentRequest): AttributionAgentResult {
        if (!featureFlags.localAgentAEnabled) return AttributionAgentResult.Fallback("feature_disabled")
        if (!isForegroundRuntimeReady()) return AttributionAgentResult.Fallback("foreground_runtime_unavailable")
        val files = modelStore.getSelectedModelFiles()
        if (!files.llmFile.isFile) return AttributionAgentResult.Fallback("agent_a_model_unavailable")
        return runCatching {
            runBlocking {
                withTimeout(timeoutMillis) {
                    engine.loadTextModel(files.entry.model, files.llmFile)
                    val output = engine.generate(
                        systemPrompt = request.messages[0].content,
                        userPrompt = request.messages[1].content,
                        maxOutputTokens = requireNotNull(files.entry.attributionSpec).maxOutputTokens,
                    )
                    AttributionAgentResult.Draft(
                        FinalPromptDraftJsonCodec.decode(output.trim()),
                        files.entry.model.id,
                    )
                }
            }
        }.getOrElse { error ->
            val reason = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                "agent_a_timeout"
            } else {
                "agent_a_${error.message?.takeIf(String::isNotBlank)?.replace(Regex("[^a-zA-Z0-9_]+"), "_") ?: "failed"}"
            }
            AttributionAgentResult.Fallback(reason.take(96))
        }
    }

    override fun close() {
        engine.cancelGeneration()
        runCatching { runBlocking { engine.unloadModel() } }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}
