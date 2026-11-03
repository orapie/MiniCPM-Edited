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
)

class VectorIndex(
    private val items: List<IndexedChunk>,
    val schemaVersion: String = "synthetic",
    private val parentEmbeddings: Map<String, DoubleArray> = emptyMap(),
) {
    val size: Int get() = items.size

    fun search(query: String, embeddingModel: EmbeddingModel, topK: Int = 5): List<SearchResult> {
        require(topK > 0) { "topK must be positive" }
        val queryEmbedding = embeddingModel.embed(listOf(query)).first()
        val parentScores = parentEmbeddings.mapValues { (_, embedding) -> cosine(queryEmbedding, embedding) }
        val ranked = items
            .map {
                val parent = it.chunk.metadata["claim_id"]
                val parentScore = if (it.chunk.metadata["record_kind"] == "scene_candidate") parentScores[parent] else null
                val chunkScore = cosine(queryEmbedding, it.embedding)
                SearchResult(it.chunk, if (parentScore == null) chunkScore else 0.8 * parentScore + 0.2 * chunkScore,
                    chunkScore = chunkScore, parentScore = parentScore)
            }
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.chunk.id })
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

    companion object {
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
            return VectorIndex(items, schemaVersion, parentEmbeddings)
        }

        private fun parseMetadata(value: Map<String, Any?>): Map<String, String> {
            return value.mapValues { (_, raw) -> raw?.toString().orEmpty() }
        }
    }
}

