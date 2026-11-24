package com.example.minicpm_v_demo.harness.attribution

import android.content.Context
import android.util.Log
import com.example.minicpm_v_demo.AttributionDeadlineExceeded
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
    private val engine = AttributionLlamaEngine.getInstance(context)
    @Volatile private var circuitOpenAfterTimeout = false

    override val enabled: Boolean
        get() = featureFlags.localAgentAEnabled &&
            isForegroundRuntimeReady() &&
            modelStore.isSelectedModelDownloaded()

    /** 检查当前运行时、模型文件和请求预算，在共享时限内加载模型、生成并解析归因结果。任一环节失败都返回回退原因。 */
    override fun rewrite(request: AttributionAgentRequest): AttributionAgentResult {
        if (!featureFlags.localAgentAEnabled) return AttributionAgentResult.Fallback("feature_disabled")
        if (!isForegroundRuntimeReady()) return AttributionAgentResult.Fallback("foreground_runtime_unavailable")
        if (circuitOpenAfterTimeout) return AttributionAgentResult.Fallback("agent_a_circuit_open")
        val files = modelStore.getSelectedModelFiles()
        if (!files.llmFile.isFile) return AttributionAgentResult.Fallback("agent_a_model_unavailable")
        val spec = requireNotNull(files.entry.attributionSpec)
        val referenceCount = request.raw.references.size
        if (referenceCount == 0) return AttributionAgentResult.Fallback("no_authorized_references")
        val budget = AgentAAttributionCodec.outputBudget(referenceCount)
        if (budget > spec.maxOutputTokens) return AttributionAgentResult.Fallback("agent_a_output_budget_exceeded")
        if (request.messages.sumOf { it.content.length } > spec.maxContextChars) {
            return AttributionAgentResult.Fallback("agent_a_input_budget_exceeded")
        }
        val startedAt = System.nanoTime()
        val result = runCatching {
            val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000
            runBlocking {
                withTimeout(timeoutMillis) {
                    val output = engine.generate(
                        model = files.entry.model,
                        modelFile = files.llmFile,
                        systemPrompt = request.messages[0].content,
                        userPrompt = request.messages[1].content,
                        maxOutputTokens = budget,
                        grammar = AgentAAttributionCodec.grammar(referenceCount),
                        deadlineNanos = deadlineNanos,
                    )
                    AttributionAgentResult.Draft(
                        AgentAAttributionCodec.decode(output, request.raw),
                        files.entry.model.id,
                    )
                }
            }
        }.getOrElse { error ->
            val timedOut = error is kotlinx.coroutines.TimeoutCancellationException || error is AttributionDeadlineExceeded
            if (timedOut) circuitOpenAfterTimeout = true
            val reason = if (timedOut) {
                "agent_a_timeout"
            } else {
                "agent_a_${error.message?.takeIf(String::isNotBlank)?.replace(Regex("[^a-zA-Z0-9_]+"), "_") ?: "failed"}"
            }
            AttributionAgentResult.Fallback(reason.take(96))
        }
        Log.i(
            TAG,
            "AGENT_A_REQUEST model=${files.entry.model.id} references=$referenceCount " +
                "elapsed_ms=${(System.nanoTime() - startedAt) / 1_000_000L} " +
                "result=${when (result) {
                    is AttributionAgentResult.Draft -> "success"
                    is AttributionAgentResult.Fallback -> result.reason
                }} circuit_open=$circuitOpenAfterTimeout",
        )
        return result
    }

    override fun close() {
        engine.cancelGeneration()
        runCatching { runBlocking { engine.unloadModel() } }
    }

    companion object {
        private const val TAG = "LocalAttribution"
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}
