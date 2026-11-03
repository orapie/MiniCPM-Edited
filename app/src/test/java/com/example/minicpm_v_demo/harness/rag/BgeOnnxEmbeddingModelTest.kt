package com.example.minicpm_v_demo.harness.rag

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class BgeOnnxEmbeddingModelTest {
    @Test fun outputMatchesReferenceModelForSyntheticQueries() {
        val root = (if (File("settings.gradle.kts").isFile) File(".") else File("..")).canonicalFile
        val golden = SimpleJsonParser(File(root, "scripts/fixtures/semantic_v2_2/bge_query_golden.json").readText())
            .parse().asJsonObject("golden")
        BgeOnnxEmbeddingModel(File(root, "database/embedding/bge-small-zh-v1.5/model.onnx"),
            File(root, "database/embedding/bge-small-zh-v1.5/vocab.txt"), golden.stringValue("query_instruction")).use { model ->
            for (raw in golden["cases"].asJsonArray("cases")) {
                val row = raw.asJsonObject("case")
                val expected = row["embedding"].asJsonArray("embedding").map { it as Double }
                val actual = model.embed(listOf(row.stringValue("query"))).single()
                assertEquals(512, actual.size)
                assertTrue("query=${row.stringValue("query")}", actual.indices.maxOf { abs(actual[it] - expected[it]) } < 0.0001)
            }
        }
    }

    @Test fun unequalVectorDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { cosine(doubleArrayOf(1.0), doubleArrayOf(1.0, 0.0)) }
    }
}
