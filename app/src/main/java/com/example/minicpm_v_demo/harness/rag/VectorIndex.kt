package com.example.minicpm_v_demo.harness.rag

import java.io.File
import com.example.minicpm_v_demo.harness.context.ContextController

data class RagChunk(
    val id: String,
    val text: String,
    val sourcePath: String,
    val start: Int,
    val end: Int,
    val metadata: Map<String, String>,
)

data class IndexedChunk(
    val chunk: RagChunk,
    val embedding: DoubleArray,
)

data class SearchResult(
    val chunk: RagChunk,
    val score: Double,
    val chunkScore: Double? = null,
    val parentScore: Double? = null,
    val lexicalScore: Double? = null,
    val semanticRank: Int? = null,
    val lexicalRank: Int? = null,
    val fusionScore: Double? = null,
)

class VectorIndex(
    private val items: List<IndexedChunk>,
    val schemaVersion: String = "synthetic",
    private val parentEmbeddings: Map<String, DoubleArray> = emptyMap(),
    val semanticIndexId: String? = null,
) {
    val size: Int get() = items.size
    private val lexicalDocumentTerms: Map<String, Set<String>> by lazy {
        items.associate { it.chunk.id to lexicalTerms(it.chunk.text, removeQuestionWords = false) }
    }

    fun search(query: String, embeddingModel: EmbeddingModel, topK: Int = 5, maxSourceEnd: Long? = null): List<SearchResult> {
        require(topK > 0) { "topK must be positive" }
        val queryEmbedding = embeddingModel.embed(listOf(query)).first()
        val parentScores = parentEmbeddings.mapValues { (_, embedding) -> cosine(queryEmbedding, embedding) }
        val ranked = items.asSequence()
            .filter { maxSourceEnd == null || (it.chunk.start >= 1 && it.chunk.end >= it.chunk.start && it.chunk.end.toLong() <= maxSourceEnd) }
            .map {
                val parent = it.chunk.metadata["claim_id"]
                val parentScore = if (it.chunk.metadata["record_kind"] == "scene_candidate") parentScores[parent] else null
                val chunkScore = cosine(queryEmbedding, it.embedding)
                SearchResult(it.chunk, if (parentScore == null) chunkScore else 0.8 * parentScore + 0.2 * chunkScore,
                    chunkScore = chunkScore, parentScore = parentScore)
            }
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.chunk.id }).toList()
        val seen = mutableSetOf<String>()
        return ranked.filter { result ->
            val chunk = result.chunk
            // Overlapping windows from one scene must not crowd out other scenes.
            val key = if (chunk.metadata["record_kind"] == "scene_candidate")
                chunk.metadata["parent_claim_id"]?.let { "scene:$it" } ?: "scene:${chunk.id}"
            else "fact:${chunk.id}"
            seen.add(key)
        }.take(topK).map { it.copy(lexicalScore = ContextController.queryBoost(query, it.chunk.text)) }
    }

    /** Kotlin-only lexical recall over the same evidence as the semantic index. */
    fun lexicalSearch(query: String, topK: Int, maxSourceEnd: Long? = null): List<SearchResult> {
        require(topK > 0) { "topK must be positive" }
        val terms = lexicalTerms(query)
        if (terms.isEmpty()) return emptyList()
        return items.asSequence()
            .filter { maxSourceEnd == null || (it.chunk.start >= 1 && it.chunk.end >= it.chunk.start && it.chunk.end.toLong() <= maxSourceEnd) }
            .mapNotNull { item ->
                val textTerms = lexicalDocumentTerms[item.chunk.id].orEmpty()
                val score = terms.count { it in textTerms }.toDouble() / terms.size
                // A lone common bigram should not be enough for a reference.
                if (score < 0.20 || (terms.size > 1 && terms.count { it in textTerms } < 2)) null
                else SearchResult(item.chunk, score, lexicalScore = score)
            }
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.chunk.id })
            .distinctBy { it.chunk.metadata["evidence_id"] ?: it.chunk.id }
            .take(topK).toList()
    }

    private fun lexicalTerms(text: String, removeQuestionWords: Boolean = true): Set<String> {
        var normalized = text.lowercase(java.util.Locale.ROOT)
        if (removeQuestionWords) {
            for (word in QUESTION_WORDS) normalized = normalized.replace(word, " ")
        }
        val terms = linkedSetOf<String>()
        for (match in LEXICAL_UNITS.findAll(normalized)) {
            val unit = match.value
            if (unit[0] in '\u3400'..'\u9fff') {
                if (unit.length == 1) continue
                for (i in 0 until unit.length - 1) terms.add(unit.substring(i, i + 2))
                if (unit.length >= 3) for (i in 0 until unit.length - 2) terms.add(unit.substring(i, i + 3))
            } else if (unit.length >= 2) terms.add(unit)
        }
        return terms
    }

    companion object {
        private val LEXICAL_UNITS = Regex("[\\p{IsHan}]+|[A-Za-z0-9_]+")
        private val QUESTION_WORDS = listOf("请告诉我", "告诉我", "为什么", "怎么样", "是什么", "有哪些", "有没有", "什么", "如何", "哪里", "是否", "请问", "你觉得", "你的", "一个", "这个", "那个", "一下")

        fun load(file: File): VectorIndex {
            require(file.isFile) { "RAG index does not exist: ${file.absolutePath}" }
            return fromJson(file.readText(Charsets.UTF_8))
        }

        fun fromJson(json: String): VectorIndex {
            val root = SimpleJsonParser(json).parse().asJsonObject("index")
            val schemaVersion = root.stringValue("schema_version")
            require(schemaVersion in setOf("1.1", "1.2")) { "Unsupported RAG index schema: $schemaVersion" }
            val items = root["items"].asJsonArray("items").mapIndexed { itemIndex, rawItem ->
                val item = rawItem.asJsonObject("items[$itemIndex]")
                val chunkObject = item["chunk"].asJsonObject("items[$itemIndex].chunk")
                IndexedChunk(
                    chunk = RagChunk(
                        id = chunkObject.stringValue("id"),
                        text = chunkObject.stringValue("text"),
                        sourcePath = chunkObject.stringValue("source_path"),
                        start = chunkObject.intValue("start"),
                        end = chunkObject.intValue("end"),
                        metadata = parseMetadata(chunkObject["metadata"].asJsonObject("metadata")),
                    ),
                    embedding = item.doubleListValue("embedding").toDoubleArray(),
                )
            }
            val parentEmbeddings = if (schemaVersion == "1.2") {
                root["parent_embeddings"].asJsonObject("parent embeddings").mapValues { (id, value) ->
                    value.asJsonArray("parent embedding $id").map { it as Double }.toDoubleArray()
                }
            } else emptyMap()
            return VectorIndex(items, schemaVersion, parentEmbeddings, root["semantic_index_id"] as? String)
        }

        private fun parseMetadata(value: Map<String, Any?>): Map<String, String> {
            return value.mapValues { (_, raw) -> raw?.toString().orEmpty() }
        }
    }
}

