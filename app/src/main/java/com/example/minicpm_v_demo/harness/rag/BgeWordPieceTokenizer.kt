package com.example.minicpm_v_demo.harness.rag

import java.io.File
import java.text.Normalizer
import java.util.Locale

/** BERT basic + WordPiece tokenizer for the pinned BGE vocabulary. */
class BgeWordPieceTokenizer(vocabulary: List<String>, private val maxLength: Int = 512) {
    private val vocabularyIds = vocabulary.withIndex().associate { it.value to it.index }
    private val specials = listOf("[UNK]", "[SEP]", "[PAD]", "[CLS]", "[MASK]")
    private val specialIds = specials.associateWith { requireNotNull(vocabularyIds[it]) { "Missing BERT special token" } }

    init {
        require(maxLength >= 2)
        require(vocabularyIds.size == vocabulary.size) { "Duplicate BERT vocabulary tokens" }
    }

    fun encode(text: String): LongArray {
        val ids = mutableListOf(specialIds.getValue("[CLS]").toLong())
        for (token in tokens(text)) {
            if (ids.size >= maxLength - 1) break
            ids += token.toLong()
        }
        ids += specialIds.getValue("[SEP]").toLong()
        return ids.toLongArray()
    }

    private fun tokens(input: String): List<Int> {
        val pieces = mutableListOf<Int>()
        var position = 0
        while (position < input.length) {
            val next = specials.mapNotNull { special ->
                input.indexOf(special, position).takeIf { it >= 0 }?.let { it to special }
            }.minByOrNull { it.first }
            val end = next?.first ?: input.length
            basicTokens(input.substring(position, end)).forEach { pieces += wordPiece(it) }
            if (next == null) break
            pieces += specialIds.getValue(next.second)
            position = next.first + next.second.length
        }
        return pieces
    }

    private fun basicTokens(input: String): List<String> {
        val cleaned = StringBuilder()
        input.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            when {
                cp == 0 || cp == 0xfffd -> Unit
                isWhitespace(cp) -> cleaned.append(' ')
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> Unit
                isChinese(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                else -> cleaned.appendCodePoint(cp)
            }
        }
        val lowered = Normalizer.normalize(cleaned.toString().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val accentsRemoved = StringBuilder()
        lowered.codePoints().filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .forEach { accentsRemoved.appendCodePoint(it) }
        val output = mutableListOf<String>()
        val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { output += word.toString(); word.setLength(0) } }
        accentsRemoved.toString().codePoints().forEach { cp ->
            when {
                isWhitespace(cp) -> flush()
                isPunctuation(cp) -> { flush(); output += String(Character.toChars(cp)) }
                else -> word.appendCodePoint(cp)
            }
        }
        flush()
        return output
    }

    private fun wordPiece(word: String): List<Int> {
        val count = word.codePointCount(0, word.length)
        if (count > 100) return listOf(specialIds.getValue("[UNK]"))
        val offsets = IntArray(count + 1) { word.offsetByCodePoints(0, it) }
        val result = mutableListOf<Int>()
        var start = 0
        while (start < count) {
            var end = count
            var found: Int? = null
            while (end > start) {
                val token = (if (start > 0) "##" else "") + word.substring(offsets[start], offsets[end])
                found = vocabularyIds[token]
                if (found != null) break
                end--
            }
            if (found == null) return listOf(specialIds.getValue("[UNK]"))
            result += found
            start = end
        }
        return result
    }

    private fun isWhitespace(cp: Int) = cp == 9 || cp == 10 || cp == 13 || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()
    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return Character.getType(cp) in setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
    }
    private fun isChinese(cp: Int) = cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf ||
        cp in 0x20000..0x2a6df || cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f ||
        cp in 0x2b820..0x2ceaf || cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f

    companion object {
        fun fromFile(file: File) = BgeWordPieceTokenizer(file.readLines(Charsets.UTF_8))
    }
}
