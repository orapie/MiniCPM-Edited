package com.example.minicpm_v_demo.harness.data.database

import java.security.MessageDigest

/**
 * Source-only validation for V2.2 quotes; no role knowledge or bundle activation.
 * Columns are Unicode code point offsets in the unmodified source line, end exclusive.
 */
internal object V22SourceProjection {
    data class Span(val line: Int, val start: Int, val end: Int)
    data class Result(val text: String, val textSha256: String, val spans: List<Span>)

    fun verifyDeclared(
        lines: List<String>, lineStart: Int, lineEnd: Int, quote: String, quoteSha256: String,
        declared: Result, offsetUnit: String, projection: String,
    ) {
        require(offsetUnit == "unicode_code_point" && projection == "trim_lines_then_preserve_line_boundaries_v1") {
            "Unsupported V2.2 source projection"
        }
        require(verify(lines, lineStart, lineEnd, quote, quoteSha256) == declared) {
            "V2.2 source projection mismatch"
        }
    }

    /** 将证据引文映射回原文的指定行范围，校验文本摘要并返回匹配片段的位置。 */
    fun verify(lines: List<String>, lineStart: Int, lineEnd: Int, quote: String, quoteSha256: String): Result {
        require(lineStart >= 1 && lineEnd >= lineStart && lineEnd <= lines.size) { "Invalid V2.2 source line range" }
        require(quote.isNotEmpty() && sha256(quote) == quoteSha256) { "V2.2 quote hash mismatch" }
        val original = lines.subList(lineStart - 1, lineEnd)
        val stripped = original.map { it.trim() }
        val joined = stripped.joinToString("")
        val start = joined.indexOf(quote)
        require(start >= 0 && joined.indexOf(quote, start + 1) < 0) { "V2.2 quote must occur exactly once" }
        val end = start + quote.length
        var position = 0
        val parts = mutableListOf<String>()
        val spans = mutableListOf<Span>()
        for ((index, raw) in original.withIndex()) {
            val trimmed = stripped[index]
            val left = (start - position).coerceAtLeast(0)
            val right = minOf(trimmed.length, end - position)
            if (right > left) {
                val leading = raw.length - raw.trimStart().length
                val rawStart = leading + left
                val rawEnd = leading + right
                require(!(rawStart > 0 && Character.isLowSurrogate(raw[rawStart])) &&
                    !(rawEnd < raw.length && Character.isLowSurrogate(raw[rawEnd]))) { "V2.2 quote splits surrogate" }
                parts += raw.substring(rawStart, rawEnd)
                spans += Span(lineStart + index, raw.codePointCount(0, rawStart), raw.codePointCount(0, rawEnd))
            } else {
                parts += ""
            }
            position += trimmed.length
        }
        require(spans.isNotEmpty() && spans.first().line == lineStart && spans.last().line == lineEnd) {
            "V2.2 source range is broader than quote"
        }
        require(parts.joinToString("") == quote) { "V2.2 source reconstruction mismatch" }
        val text = parts.joinToString("\n")
        return Result(text, sha256(text), spans)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
