package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.data.database.databaseTestSource
import org.junit.Assert.*
import org.junit.Test

class VectorIndexTest {
    private fun index() = VectorIndex.load(databaseTestSource().resolve("runtime/android/vector/index.json"))

    @Test fun legacyIndexReaderRemainsExplicitlySupported() {
        val legacy = VectorIndex.fromJson("""{"schema_version":"1.1","items":[{"chunk":{"id":"legacy-test","text":"示例","source_path":"database/example","start":1,"end":1,"metadata":{}},"embedding":[1.0]}]}""")
        assertEquals("1.1", legacy.schemaVersion)
        assertEquals(1, legacy.size)
    }

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

    @Test fun diagnosticsExplainScoresAndTiesAreDeterministic() {
        val embedding = object : EmbeddingModel {
            override fun embed(texts: List<String>) = texts.map { doubleArrayOf(1.0, 0.0) }
        }
        fun item(id: String) = IndexedChunk(RagChunk(id, "大学社会学", "database/test", 1, 1, emptyMap()),
            doubleArrayOf(1.0, 0.0))
        val result = VectorIndex(listOf(item("z"), item("a"))).search("大学社会学", embedding, 2)
        assertEquals(listOf("a", "z"), result.map { it.chunk.id })
        assertEquals(1.0, result.first().score, 0.0)
        assertEquals(1.0, result.first().chunkScore!!, 0.0)
        assertNull(result.first().parentScore)
        assertTrue(result.first().lexicalScore!! > 0.0)
        val attempt = AlwaysOnVectorRetrievalService(RagSearchService(VectorIndex(listOf(item("a"))), embedding))
            .searchAlways("大学社会学", 1, 0.0).attempt
        assertEquals("a:1.0:none:1.0:1.0", attempt.candidateScoreDetails.single())
    }
}
