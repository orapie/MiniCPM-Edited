package com.example.minicpm_v_demo.harness.data.database

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.io.File
import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue

class V22SourceProjectionTest {
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test fun preservesBlankLinesAndCodePointOffsets() {
        val result = V22SourceProjection.verify(listOf("  前文😀甲乙 ", "   ", "\t丙丁后文  "), 1, 3, "甲乙丙丁", sha("甲乙丙丁"))
        assertEquals("甲乙\n\n丙丁", result.text)
        assertEquals(listOf(V22SourceProjection.Span(1, 5, 7), V22SourceProjection.Span(3, 1, 3)), result.spans)
        assertEquals(sha(result.text), result.textSha256)
    }

    @Test fun rejectsForgedOrAmbiguousQuoteAndWrongRange() {
        assertThrows(IllegalArgumentException::class.java) { V22SourceProjection.verify(listOf("甲乙"), 1, 1, "甲乙", sha("其他")) }
        assertThrows(IllegalArgumentException::class.java) { V22SourceProjection.verify(listOf("甲乙甲乙"), 1, 1, "甲乙", sha("甲乙")) }
        assertThrows(IllegalArgumentException::class.java) { V22SourceProjection.verify(listOf("甲乙", "丙丁"), 1, 2, "甲乙", sha("甲乙")) }
        assertThrows(IllegalArgumentException::class.java) { V22SourceProjection.verify(listOf("甲乙"), 0, 1, "甲乙", sha("甲乙")) }
    }

    @Test fun rejectsTamperedDeclaredProjectionOrOffsets() {
        val lines = listOf("  前文😀甲乙 ", "   ", "\t丙丁后文  ")
        val quote = "甲乙丙丁"
        val result = V22SourceProjection.verify(lines, 1, 3, quote, sha(quote))
        fun check(declared: V22SourceProjection.Result, unit: String = "unicode_code_point") =
            V22SourceProjection.verifyDeclared(lines, 1, 3, quote, sha(quote), declared, unit,
                "trim_lines_then_preserve_line_boundaries_v1")
        check(result)
        assertThrows(IllegalArgumentException::class.java) { check(result.copy(text = "篡改")) }
        assertThrows(IllegalArgumentException::class.java) { check(result.copy(spans = listOf(result.spans[0].copy(start = 0)))) }
        assertThrows(IllegalArgumentException::class.java) { check(result, "utf16") }
    }

    @Test fun allRealEvidenceMatchesPythonGolden() {
        val paths = databaseTestSource()
        val doc = SimpleJsonParser(paths.resolve("knowledge_base_v2/corpus/documents.jsonl")
            .readLines().single()).parse().asJsonObject("document")
        val lines = doc.stringValue("text").split("\n")
        val rows = paths.resolve("knowledge_base_v2/graph/evidence.jsonl").readLines()
            .filter { it.isNotBlank() }.map { SimpleJsonParser(it).parse().asJsonObject("evidence") }
            .associateBy { it.stringValue("evidence_id") }
        val repo = if (File("settings.gradle.kts").isFile) File(".") else File("..")
        val golden = SimpleJsonParser(File(repo, "scripts/fixtures/semantic_v2_2/source_projection_golden.json")
            .readText()).parse().asJsonObject("golden")
        val cases = golden["cases"].asJsonArray("cases")
        assertEquals(536, cases.size)
        for (raw in cases) {
            val item = raw.asJsonObject("case")
            val id = item.stringValue("evidence_id")
            val proof = rows.getValue(id)
            val start = (item["line_start"] as Double).toInt()
            val end = (item["line_end"] as Double).toInt()
            val projected = V22SourceProjection.verify(lines, start, end, proof.stringValue("quote"), item.stringValue("quote_sha256"))
            assertEquals("text hash $id", item.stringValue("text_sha256"), projected.textSha256)
            val expectedSpans = item["line_spans"].asJsonArray("spans").map { value ->
                val span = value.asJsonObject("span")
                V22SourceProjection.Span((span["line"] as Double).toInt(), (span["start"] as Double).toInt(), (span["end"] as Double).toInt())
            }
            assertEquals("source offsets $id", expectedSpans, projected.spans)
        }
    }
}
