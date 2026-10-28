package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.data.database.databaseTestSource
import org.junit.Assert.*
import org.junit.Test

class VectorIndexTest {
    private fun index() = VectorIndex.load(databaseTestSource().resolve("runtime/android/vector/index.json"))

    @Test fun searchesCurrentIndexAndRejectsInvalidLimits() {
        val index = index()
        val results = index.search("大学社会学", HashEmbeddingModel(), 5)
        assertTrue(index.size >= 128)
        assertEquals("reviewed-luo-ji-profession", results.first().chunk.id)
        assertTrue(runCatching { index.search("test", HashEmbeddingModel(), 0) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun thresholdNeverIncludesLowerScoringResults() {
        val service = RagSearchService(index())
        val all = service.search("大学社会学", topK = 128)
        val filtered = service.search("大学社会学", topK = 128, minScore = 0.15)
        assertTrue(filtered.isNotEmpty())
        assertEquals(all.filter { it.score >= 0.15 }.map { it.chunk.id }, filtered.map { it.chunk.id })
        assertTrue(filtered.size < all.size)
    }
}
