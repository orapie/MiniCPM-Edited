package com.example.minicpm_v_demo.harness.character.knowledge

import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue
import java.io.File
import kotlin.math.round

/**
 * Read-only V1 relationship store.  Relations are deliberately separate from
 * V1 claims: they express the current speaker's stance and must never be
 * inferred by reversing another character's edge.
 */
class V1GraphKnowledgeStore(
    private val manifestFile: File,
    private val relationsFile: File,
) {
    private val document: V1RelationshipDocument by lazy { loadDocument() }

    fun retrieveSafely(request: V1RelationshipQuery): V1RelationshipRetrieval {
        if (request.limit <= 0 || request.characterId.isBlank() || request.storyCutoff.isBlank()) {
            return V1RelationshipRetrieval(KnowledgeStoreStatus.INVALID_REQUEST, emptyList(), emptyList(), "invalid_request")
        }
        return try {
            retrieveAudited(request)
        } catch (_: Exception) {
            V1RelationshipRetrieval(KnowledgeStoreStatus.UNAVAILABLE, emptyList(), emptyList(), "v1_store_unavailable")
        }
    }

    private fun retrieveAudited(request: V1RelationshipQuery): V1RelationshipRetrieval {
        require(document.cutoffs.contains(request.storyCutoff)) { "Unknown V1 story cutoff: ${request.storyCutoff}" }
        val normalizedQuery = normalize(request.query)
        val explicitTargetIds = request.targetNames
            .filter { (targetId, names) -> names.any { normalize(it).isNotBlank() && normalize(it) in normalizedQuery } }
            .keys
        val relationIntent = RelationSignals.any { it in request.query }
        val selected = mutableListOf<V1KnowledgeRelation>()
        val decisions = document.relations.map { relation ->
            when {
                relation.sourceId != request.characterId ->
                    KnowledgeDecision(relation.relationId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.RELATION_NOT_FROM_CHARACTER)
                relation.sourceTier !in AllowedSourceTiers || relation.canonStatus !in AllowedCanonStatuses ->
                    KnowledgeDecision(relation.relationId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.UNVERIFIED_SOURCE)
                explicitTargetIds.isNotEmpty() && relation.targetId !in explicitTargetIds ->
                    KnowledgeDecision(relation.relationId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.RELATION_NOT_RELEVANT)
                explicitTargetIds.isEmpty() && !relationIntent ->
                    KnowledgeDecision(relation.relationId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.RELATION_NOT_RELEVANT)
                else -> {
                    val targetName = request.targetNames[relation.targetId]?.firstOrNull().orEmpty()
                    if (targetName.isBlank()) {
                        KnowledgeDecision(relation.relationId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.RELATION_NOT_RELEVANT)
                    } else {
                        val explicitBoost = if (relation.targetId in explicitTargetIds) 1.0 else 0.0
                        val score = round5(explicitBoost + relation.confidence)
                        selected += V1KnowledgeRelation(
                            relationId = relation.relationId,
                            targetId = relation.targetId,
                            targetName = targetName,
                            relationshipType = relation.relationshipType,
                            attitude = relation.description,
                            confidence = relation.confidence,
                            score = score,
                        )
                        KnowledgeDecision(relation.relationId, KnowledgeDisposition.SELECTED, KnowledgeDecisionReason.SELECTED, score)
                    }
                }
            }
        }
        val relations = selected.sortedWith(compareByDescending<V1KnowledgeRelation> { it.score }.thenBy { it.relationId }).take(request.limit)
        val returnedIds = relations.mapTo(mutableSetOf()) { it.relationId }
        return V1RelationshipRetrieval(
            status = KnowledgeStoreStatus.READY,
            relations = relations,
            decisions = decisions.map { decision ->
                if (decision.disposition == KnowledgeDisposition.SELECTED && decision.claimId !in returnedIds) {
                    decision.copy(disposition = KnowledgeDisposition.DROPPED, reason = KnowledgeDecisionReason.RANK_LIMIT, score = null)
                } else decision
            },
        )
    }

    private fun loadDocument(): V1RelationshipDocument {
        require(manifestFile.isFile && relationsFile.isFile) { "Missing V1 knowledge files" }
        val manifest = SimpleJsonParser(manifestFile.readText(Charsets.UTF_8)).parse().asJsonObject("V1 manifest")
        require(manifest.stringValue("schema_version").startsWith("1.")) { "Unsupported V1 knowledge schema" }
        val cutoffs = manifest["cutoffs"].asJsonObject("V1 cutoffs").keys
        require(cutoffs.isNotEmpty()) { "V1 cutoff map is empty" }
        val relations = relationsFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            val raw = SimpleJsonParser(line).parse().asJsonObject("V1 relation")
            V1Relation(
                relationId = raw.stringValue("relation_id"),
                sourceId = raw.stringValue("source_id"),
                targetId = raw.stringValue("target_id"),
                relationshipType = raw.stringValue("relation_type"),
                description = raw.stringValue("description"),
                confidence = raw["confidence"] as? Double ?: error("V1 relation has no confidence"),
                sourceTier = raw.stringValue("source_tier"),
                canonStatus = raw.stringValue("canon_status"),
            )
        }
        return V1RelationshipDocument(cutoffs, relations)
    }

    private fun normalize(value: String): String = value.lowercase().replace(Regex("[\\s，。！？、：；“”‘’（）《》【】,.!?;:'\\\"()\\[\\]]"), "")
    private fun round5(value: Double): Double = round(value * 100000.0) / 100000.0

    private data class V1RelationshipDocument(val cutoffs: Set<String>, val relations: List<V1Relation>)
    private data class V1Relation(
        val relationId: String,
        val sourceId: String,
        val targetId: String,
        val relationshipType: String,
        val description: String,
        val confidence: Double,
        val sourceTier: String,
        val canonStatus: String,
    )

    private companion object {
        val RelationSignals = listOf("关系", "怎么看", "如何看", "态度", "他", "她", "祂", "他们", "她们")
        val AllowedSourceTiers = setOf("canon", "curated", "external")
        val AllowedCanonStatuses = setOf("accepted", "auto_accepted")
    }
}

data class V1RelationshipQuery(
    val characterId: String,
    val query: String,
    val storyCutoff: String,
    /** canonical name first, followed by safe aliases, indexed by target id. */
    val targetNames: Map<String, List<String>>,
    val limit: Int = 2,
)
