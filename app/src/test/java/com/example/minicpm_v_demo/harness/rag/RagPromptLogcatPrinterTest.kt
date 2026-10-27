package com.example.minicpm_v_demo.harness.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagPromptLogcatPrinterTest {
    @Test
    fun splitsLongTextWithoutBreakingSurrogatePairsOrChangingContent() {
        val text = "前".repeat(4) + "😀" + "后".repeat(8)

        val chunks = RagPromptLogcatPrinter.splitForLogcat(text, maxChars = 5)

        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(separator = ""))
        assertTrue(chunks.none { chunk -> chunk.lastOrNull()?.let(Character::isHighSurrogate) == true })
    }

    @Test
    fun skippedStageCannotRetainRawSnapshot() {
        val skipped = RagPromptStageLog.skipped(RagPromptStage.CHARACTER_AFTER_V2, "not_requested")

        assertEquals(RagPromptStageStatus.SKIPPED, skipped.status)
        assertEquals(null, skipped.snapshot)
    }
}
