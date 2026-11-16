package com.example.minicpm_v_demo.harness.attribution

import android.content.Context
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

    override val enabled: Boolean
        get() = featureFlags.localAgentAEnabled &&
            isForegroundRuntimeReady() &&
            modelStore.isSelectedModelDownloaded()

    /** 检查当前运行时、模型文件和请求预算，在共享时限内加载模型、生成并解析归因结果。任一环节失败都返回回退原因。 */
    override fun rewrite(request: AttributionAgentRequest): AttributionAgentResult {
        if (!featureFlags.localAgentAEnabled) return AttributionAgentResult.Fallback("feature_disabled")
        if (!isForegroundRuntimeReady()) return AttributionAgentResult.Fallback("foreground_runtime_unavailable")
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
        return runCatching {
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
            val reason = if (error is kotlinx.coroutines.TimeoutCancellationException || error is AttributionDeadlineExceeded) {
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
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}
