package com.example.minicpm_v_demo.harness.character.knowledge

import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue
import java.io.File
import kotlin.math.round

/**
 * Read-only compatibility source for V1 *story-event* claims.
 *
 * V1 also contains character observations and role constraints.  Those are
 * intentionally excluded: V2 treats the comparable profile scope as role-card
 * material, so allowing it here would bypass the existing knowledge boundary.
 * The caller invokes this store only when V2 supplied no authorized fact.
 */
class V1ClaimKnowledgeStore(
    private val manifestFile: File,
    private val claimsFile: File,
) {
    private val document: V1ClaimDocument by lazy { loadDocument() }

    fun retrieveSafely(request: V1ClaimQuery): V1ClaimRetrieval {
        if (request.limit <= 0 || request.characterId.isBlank() || request.storyCutoff.isBlank()) {
            return V1ClaimRetrieval(KnowledgeStoreStatus.INVALID_REQUEST, emptyList(), emptyList(), "invalid_request")
        }
        return try {
            retrieveAudited(request)
        } catch (_: Exception) {
            V1ClaimRetrieval(KnowledgeStoreStatus.UNAVAILABLE, emptyList(), emptyList(), "v1_claim_store_unavailable")
        }
    }

    private fun retrieveAudited(request: V1ClaimQuery): V1ClaimRetrieval {
        val cutoff = document.cutoffs[request.storyCutoff]
            ?: error("Unknown V1 story cutoff: ${request.storyCutoff}")
        val queryTerms = terms(request.query)
        val selected = mutableListOf<V1KnowledgeFact>()
        val decisions = document.claims.map { claim ->
            val matchedTerms = queryTerms.intersect(terms(claim.statement + claim.keywords.joinToString("")))
            val knownByCharacter = claim.knownBy[request.characterId]
            val acquiredSequence = knownByCharacter?.availableAfter?.let(document.cutoffs::get)
            when {
                queryTerms.isEmpty() || matchedTerms.isEmpty() ->
                    KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.NO_LEXICAL_MATCH)
                knownByCharacter == null ->
                    KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.NO_CHARACTER_KNOWLEDGE_EDGE)
                claim.claimType != "story_event" ->
                    KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.PROFILE_CLAIM)
                acquiredSequence == null || acquiredSequence > cutoff ->
                    KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF)
                claim.sourceTier !in AllowedSourceTiers || claim.canonStatus !in AllowedCanonStatuses ||
                    knownByCharacter.source !in AllowedAcquisitionMethods ->
                    KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.UNVERIFIED_SOURCE)
                else -> {
                    val lexical = matchedTerms.size.toDouble() / queryTerms.size
                    val score = round5(
                        lexical * 0.70 + claim.importance * 0.15 + claim.confidence * 0.10 + knownByCharacter.confidence * 0.05,
                    )
                    selected += V1KnowledgeFact(
                        claimId = claim.claimId,
                        statement = claim.statement,
                        eventId = claim.claimId.removePrefix("event-").takeIf { claim.claimId.startsWith("event-") },
                        matchedTerms = matchedTerms.sorted(),
                        acquisitionMethod = knownByCharacter.source,
                        acquiredSequence = acquiredSequence,
                        confidence = minOf(claim.confidence, knownByCharacter.confidence),
                        score = score,
                    )
                    KnowledgeDecision(claim.claimId, KnowledgeDisposition.SELECTED, KnowledgeDecisionReason.SELECTED, score)
                }
            }
        }
        val facts = selected.sortedWith(compareByDescending<V1KnowledgeFact> { it.score }.thenBy { it.claimId }).take(request.limit)
        val returnedIds = facts.mapTo(mutableSetOf()) { it.claimId }
        return V1ClaimRetrieval(
            status = KnowledgeStoreStatus.READY,
            facts = facts,
            decisions = decisions.map { decision ->
                if (decision.disposition == KnowledgeDisposition.SELECTED && decision.claimId !in returnedIds) {
                    decision.copy(disposition = KnowledgeDisposition.DROPPED, reason = KnowledgeDecisionReason.RANK_LIMIT, score = null)
                } else {
                    decision
                }
            },
        )
    }

    private fun loadDocument(): V1ClaimDocument {
        require(manifestFile.isFile && claimsFile.isFile) { "Missing V1 knowledge files" }
        val manifest = SimpleJsonParser(manifestFile.readText(Charsets.UTF_8)).parse().asJsonObject("V1 manifest")
        require(manifest.stringValue("schema_version").startsWith("1.")) { "Unsupported V1 knowledge schema" }
        val cutoffs = manifest["cutoffs"].asJsonObject("V1 cutoffs").mapValues { (_, raw) ->
            (raw as? Double ?: error("V1 cutoff must be numeric")).toLong()
        }
        require(cutoffs.isNotEmpty()) { "V1 cutoff map is empty" }
        val claims = claimsFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            val raw = SimpleJsonParser(line).parse().asJsonObject("V1 claim")
            val knownBy = raw["known_by"].asJsonObject("known_by").mapValues { (_, value) ->
                val entry = value.asJsonObject("known_by entry")
                V1KnownBy(
                    source = entry.stringValue("source"),
                    availableAfter = entry["available_after"] as? String,
                    confidence = entry["confidence"] as? Double ?: error("V1 known_by confidence missing"),
                )
            }
            V1Claim(
                claimId = raw.stringValue("claim_id"),
                claimType = raw.stringValue("claim_type"),
                statement = raw.stringValue("statement"),
                keywords = raw["keywords"].asJsonArray("keywords").map { it as? String ?: error("V1 keyword must be a string") },
                importance = raw["importance"] as? Double ?: error("V1 claim importance missing"),
                confidence = raw["confidence"] as? Double ?: error("V1 claim confidence missing"),
                sourceTier = raw.stringValue("source_tier"),
                canonStatus = raw.stringValue("canon_status"),
                knownBy = knownBy,
            )
        }
        return V1ClaimDocument(cutoffs, claims)
    }

    private fun terms(text: String): Set<String> {
        var normalized = text.lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[，。！？、：；“”‘’（）《》【】,.!?;:'\\\"()\\[\\]]"), "")
        QueryFillers.forEach { normalized = normalized.replace(it, "") }
        val output = mutableSetOf<String>()
        Regex("[a-z0-9_]{2,}").findAll(normalized).forEach { output += it.value }
        val chinese = Regex("[\\u3400-\\u9fff]").findAll(normalized).joinToString("") { it.value }
        for (index in 0 until maxOf(0, chinese.length - 1)) output += chinese.substring(index, index + 2)
        for (index in 0 until maxOf(0, chinese.length - 2)) output += chinese.substring(index, index + 3)
        return output.filter { it !in SearchStopTerms }.toSet()
    }

    private fun round5(value: Double): Double = round(value * 100000.0) / 100000.0

    private data class V1ClaimDocument(val cutoffs: Map<String, Long>, val claims: List<V1Claim>)
    private data class V1Claim(
        val claimId: String,
        val claimType: String,
        val statement: String,
        val keywords: List<String>,
        val importance: Double,
        val confidence: Double,
        val sourceTier: String,
        val canonStatus: String,
        val knownBy: Map<String, V1KnownBy>,
    )
    private data class V1KnownBy(val source: String, val availableAfter: String?, val confidence: Double)

    private companion object {
        val AllowedSourceTiers = setOf("canon", "curated", "external")
        val AllowedCanonStatuses = setOf("accepted", "auto_accepted")
        val AllowedAcquisitionMethods = setOf("direct", "witnessed", "reported", "inferred")
        val QueryFillers = listOf("究竟", "到底", "请问", "真的", "为什么", "是什么", "怎么样", "是不是", "会不会", "是谁", "什么", "怎么", "为何", "如何", "吗", "呢")
        val SearchStopTerms = setOf("你是", "我是", "有何", "有关", "关于")
    }
}

data class V1ClaimQuery(
    val characterId: String,
    val query: String,
    val storyCutoff: String,
    val limit: Int = 2,
)
