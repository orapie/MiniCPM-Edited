package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.character.HarnessChatMessage
import com.example.minicpm_v_demo.harness.context.ContextController

data class RagSource(
    val source: String,
    val start: Int,
    val end: Int,
    val score: Double,
)

data class RagContextDecision(
    val source: String,
    val start: Int,
    val end: Int,
    val score: Double,
    val decision: String,
    val reason: String,
    val originalChars: Int,
    val selectedChars: Int,
    val estimatedOriginalTokens: Int,
    val estimatedSelectedTokens: Int,
)

data class CompiledRagPrompt(
    val messages: List<HarnessChatMessage>,
    val contextText: String,
    val sources: List<RagSource>,
    val contextDecisions: List<RagContextDecision> = emptyList(),
)

class PromptBuilder(
    private val maxContextChars: Int = 900,
) {
    init {
        require(maxContextChars >= 500) { "maxContextChars must be at least 500" }
    }

    fun build(query: String, results: List<SearchResult>): CompiledRagPrompt {
        val (contextText, sources, decisions) = compileContext(query, results)
        val system = listOf(
            "你是一个本地 RAG 助手。",
            "你必须优先依据<检索资料>回答。不要把检索资料之外的信息说成已证实事实。",
        ).joinToString("\n\n")
        val user = listOf(
            "<检索资料>",
            contextText,
            "</检索资料>",
            "",
            "<用户问题>",
            query,
            "</用户问题>",
        ).joinToString("\n")
        return CompiledRagPrompt(
            messages = listOf(
                HarnessChatMessage("system", system),
                HarnessChatMessage("user", user),
            ),
            contextText = contextText,
            sources = sources,
            contextDecisions = decisions,
        )
    }

    private fun compileContext(
        query: String,
        results: List<SearchResult>,
    ): Triple<String, List<RagSource>, List<RagContextDecision>> {
        val parts = mutableListOf<String>()
        val sources = mutableListOf<RagSource>()
        val decisions = mutableListOf<RagContextDecision>()
        var usedChars = 0
        val rankedResults = results.sortedWith(
            compareByDescending<SearchResult> { it.score + ContextController.queryBoost(query, it.chunk.text) }
                .thenBy { it.chunk.sourcePath },
        )
        for ((index, result) in rankedResults.withIndex()) {
            val block = "[资料 ${index + 1}] source=${result.chunk.sourcePath} " +
                "span=${result.chunk.start}:${result.chunk.end} " +
                "score=${"%.4f".format(result.score)}\n${result.chunk.text}"
            val remaining = maxContextChars - usedChars
            if (remaining <= 0) {
                decisions += decision(result, "dropped", "context_budget_exhausted", block, "")
                continue
            }
            val selectedBlock = ContextController.compress(block, query, remaining)
            if (selectedBlock.isBlank()) {
                decisions += decision(result, "dropped", "compression_empty", block, selectedBlock)
                continue
            }
            parts += selectedBlock
            usedChars += selectedBlock.length
            sources += RagSource(
                source = result.chunk.sourcePath,
                start = result.chunk.start,
                end = result.chunk.end,
                score = round6(result.score),
            )
            decisions += decision(
                result,
                if (selectedBlock == block) "selected" else "compressed",
                if (selectedBlock == block) "within_budget" else "query_aware_sentence_compression",
                block,
                selectedBlock,
            )
        }
        return Triple(
            parts.joinToString("\n\n").ifBlank { "没有检索到可用资料。" },
            sources,
            decisions,
        )
    }

    private fun decision(
        result: SearchResult,
        decision: String,
        reason: String,
        originalText: String,
        selectedText: String,
    ): RagContextDecision = RagContextDecision(
        source = result.chunk.sourcePath,
        start = result.chunk.start,
        end = result.chunk.end,
        score = round6(result.score),
        decision = decision,
        reason = reason,
        originalChars = originalText.length,
        selectedChars = selectedText.length,
        estimatedOriginalTokens = ContextController.estimateTokens(originalText),
        estimatedSelectedTokens = ContextController.estimateTokens(selectedText),
    )
}

internal fun round6(value: Double): Double = kotlin.math.round(value * 1000000.0) / 1000000.0
