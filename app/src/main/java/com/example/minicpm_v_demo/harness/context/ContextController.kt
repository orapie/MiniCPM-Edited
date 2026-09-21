package com.example.minicpm_v_demo.harness.context

import kotlin.math.max

/** Lightweight, deterministic context control for the Android prompt path. */
object ContextController {
    private val sentenceUnit = Regex("[^。！？!?；;\\n]+[。！？!?；;]?")
    private val lexicalUnit = Regex("[\\p{IsHan}]+|[A-Za-z0-9_]+")

    fun queryBoost(query: String, text: String): Double {
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return 0.0
        val textTerms = terms(text).toSet()
        val matched = queryTerms.count { it in textTerms }
        return matched.toDouble() / queryTerms.size.coerceAtLeast(1)
    }

    /**
     * Keeps the most query-relevant complete sentences, then restores source order.
     * Structured prefixes are preserved when the full block fits the budget.
     */
    fun compress(text: String, query: String, maxChars: Int): String {
        require(maxChars >= 1) { "maxChars must be positive" }
        val normalized = text.replace(Regex("[ \\t]+"), " ").trim()
        if (normalized.length <= maxChars) return normalized

        val units = sentenceUnit.findAll(normalized)
            .map { it.value.trim() }
            .filter(String::isNotEmpty)
            .toList()
        if (units.isEmpty()) return safeTake(normalized, maxChars)

        val ranked = units.mapIndexed { index, unit ->
            RankedUnit(index, unit, queryBoost(query, unit) + 1.0 / (index + 1))
        }.sortedWith(compareByDescending<RankedUnit> { it.score }.thenBy { it.index })

        val selected = mutableSetOf<Int>()
        var used = 0
        val bestRelevant = ranked
            .filter { queryBoost(query, it.text) > 0.0 }
            .maxWithOrNull(compareBy<RankedUnit> { queryBoost(query, it.text) }.thenByDescending { it.score })
        val ordered = if (bestRelevant == null) ranked else {
            listOf(bestRelevant) + ranked.filter { it.index != bestRelevant.index }
        }
        for (unit in ordered) {
            val separator = if (selected.isEmpty()) 0 else 1
            if (used + separator + unit.text.length > maxChars) continue
            selected += unit.index
            used += separator + unit.text.length
        }

        if (selected.isEmpty()) return safeTake(normalized, maxChars)
        return units.filterIndexed { index, _ -> index in selected }
            .joinToString(" ")
            .let { safeTake(it, maxChars) }
    }

    /** Conservative estimate for debug and early ranking; native remains authoritative. */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var tokens = 0
        var latinRun = 0
        for (codePoint in text.codePoints().toArray()) {
            if (codePoint <= 0x7f && !Character.isWhitespace(codePoint)) {
                latinRun++
                if (latinRun == 4) {
                    tokens++
                    latinRun = 0
                }
            } else {
                if (latinRun > 0) tokens++
                latinRun = 0
                tokens++
            }
        }
        if (latinRun > 0) tokens++
        return max(tokens, 1)
    }

    private fun terms(text: String): List<String> {
        val output = mutableListOf<String>()
        for (match in lexicalUnit.findAll(text.lowercase())) {
            val unit = match.value
            if (unit.all { it.code in 0x3400..0x9fff }) {
                if (unit.length >= 2) {
                    for (index in 0 until unit.length - 1) {
                        output += unit.substring(index, index + 2)
                    }
                }
                if (unit.length >= 3) output += unit
            } else {
                output += unit
            }
        }
        return output.filter { it.length >= 2 }
    }

    private fun safeTake(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var end = maxChars
        while (end > 0 && Character.isLowSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd()
    }

    private data class RankedUnit(
        val index: Int,
        val text: String,
        val score: Double,
    )
}
