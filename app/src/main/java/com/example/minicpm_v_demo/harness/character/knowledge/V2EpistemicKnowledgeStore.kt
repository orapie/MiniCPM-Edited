package com.example.minicpm_v_demo.harness.character.knowledge

import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.rag.stringValue
import java.io.File
import com.example.minicpm_v_demo.harness.data.database.ValidatedDatabaseBundle
import java.security.MessageDigest
import kotlin.math.round

/**
 * Read-only, portable V2 fact store.
 *
 * Android's SQLite build can open the source database but, on the verified API
 * 36 device, cannot execute its FTS5/trigram MATCH query.  This store consumes
 * the build-time export instead. A validated portable bundle enables its
 * reviewed source policy; standalone legacy fixtures keep their curated policy.
 * Vector scores improve recall but never grant character knowledge.
 */
class V2EpistemicKnowledgeStore(
    private val fallbackFile: File,
    private val sourceDatabaseFile: File? = null,
    private val reviewedBundle: ValidatedDatabaseBundle? = null,
) {
    private val document: V2KnowledgeDocument by lazy { loadDocument() }

    fun retrieve(request: V2KnowledgeQuery): List<V2KnowledgeFact> {
        return retrieveAudited(request).facts
    }

    /**
     * Strict V2 retrieval with an explainable decision for every candidate.
     * Invalid requests still fail closed; use [retrieveSafely] at a runtime
     * boundary that reports an unavailable graph; current bundles do not
     * fall back to unreviewed story events.
     */
    fun retrieveAudited(request: V2KnowledgeQuery): V2KnowledgeRetrieval {
        require(request.limit > 0) { "limit must be positive" }
        val cutoff = document.cutoffs[request.storyCutoff]
            ?: error("Unknown V2 story cutoff: ${request.storyCutoff}")
        val queryTerms = terms(request.query)
        val matchedClaimIds = queryTerms.flatMapTo(linkedSetOf()) { term ->
            document.claimIdsByTerm[term].orEmpty()
        }
        val selected = mutableListOf<V2KnowledgeFact>()
        val decisions = document.claims.map { claim ->
            val matchedTerms = if (claim.claimId in matchedClaimIds) {
                queryTerms.intersect(document.termsByClaimId.getValue(claim.claimId))
            } else {
                emptySet()
            }
            val allEdges = document.edgesByCharacterAndClaim[request.characterId to claim.claimId].orEmpty()
            val edge = allEdges.filter { it.acquiredSequence <= cutoff }.maxByOrNull { it.acquiredSequence }
            val lexical = matchedTerms.size.toDouble() / queryTerms.size.coerceAtLeast(1)
            val confidence = edge?.confidence ?: allEdges.maxOfOrNull { it.confidence } ?: 0.0
            val relevance = round5(lexical * 0.70 + claim.importance * 0.15 + claim.confidence * 0.10 + confidence * 0.05 +
                (request.candidateScores[claim.claimId] ?: 0.0).coerceIn(0.0, 1.0) * 0.30)
            when {
                matchedTerms.isEmpty() && (request.candidateScores[claim.claimId] ?: 0.0) <= 0.0 ->
                    KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.NO_LEXICAL_MATCH)
                allEdges.isEmpty() -> KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.NO_CHARACTER_KNOWLEDGE_EDGE)
                edge == null -> KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF, relevance)
                claim.scope != "story" -> KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.PROFILE_CLAIM)
                !sourceAccepted(claim) ->
                    KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.UNVERIFIED_SOURCE)
                edge.beliefStatus != "believed" -> KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.UNSUPPORTED_BELIEF_STATUS)
                claim.validFromSequence > cutoff || (claim.validToSequence != null && claim.validToSequence < cutoff) ->
                    KnowledgeDecision(claim.claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.NOT_VALID_AT_CUTOFF)
                else -> {
                    val score = relevance
                    selected += V2KnowledgeFact(
                        claimId = claim.claimId,
                        statement = claim.statement,
                        eventId = claim.eventId,
                        matchedTerms = matchedTerms.sorted(),
                        acquisitionMethod = edge.acquisitionMethod,
                        acquiredSequence = edge.acquiredSequence,
                        confidence = minOf(claim.confidence, edge.confidence),
                        score = score,
                    )
                    KnowledgeDecision(claim.claimId, KnowledgeDisposition.SELECTED, KnowledgeDecisionReason.SELECTED, score)
                }
            }
        }
        val ranked = selected
            .sortedWith(compareByDescending<V2KnowledgeFact> { it.score }.thenBy { it.claimId })
        val facts = ranked.take(request.limit)
        val returnedIds = facts.mapTo(mutableSetOf()) { it.claimId }
        val finalDecisions = decisions.map { decision ->
            if (decision.disposition == KnowledgeDisposition.SELECTED && decision.claimId !in returnedIds) {
                decision.copy(disposition = KnowledgeDisposition.DROPPED, reason = KnowledgeDecisionReason.RANK_LIMIT, score = null)
            } else {
                decision
            }
        }
        return V2KnowledgeRetrieval(
            status = KnowledgeStoreStatus.READY,
            facts = facts,
            decisions = finalDecisions,
        )
    }

    /** Fail closed for application startup/runtime fallback paths. */
    fun retrieveSafely(request: V2KnowledgeQuery): V2KnowledgeRetrieval {
        if (request.limit <= 0) {
            return V2KnowledgeRetrieval(KnowledgeStoreStatus.INVALID_REQUEST, emptyList(), emptyList(), "invalid_request")
        }
        return try {
            retrieveAudited(request)
        } catch (_: Exception) {
            V2KnowledgeRetrieval(KnowledgeStoreStatus.UNAVAILABLE, emptyList(), emptyList(), "v2_store_unavailable")
        }
    }

    /**
     * Revalidates one opaque ID from the immediately prior turn.  This is not
     * a bypass: the same character, cutoff, source, belief and validity checks
     * still decide whether the claim can be reused.
     */
    fun retrieveByClaimIdSafely(
        characterId: String,
        claimId: String,
        storyCutoff: String,
    ): V2KnowledgeRetrieval = try {
        val cutoff = document.cutoffs[storyCutoff] ?: error("Unknown V2 story cutoff: $storyCutoff")
        val claim = document.claims.firstOrNull { it.claimId == claimId }
            ?: return V2KnowledgeRetrieval(
                KnowledgeStoreStatus.READY,
                emptyList(),
                listOf(KnowledgeDecision(claimId, KnowledgeDisposition.DROPPED, KnowledgeDecisionReason.NO_LEXICAL_MATCH)),
            )
        val edge = document.edgesByCharacterAndClaim[characterId to claimId].orEmpty()
            .filter { it.acquiredSequence <= cutoff }
            .maxByOrNull { it.acquiredSequence }
        val rejection = when {
            document.edgesByCharacterAndClaim[characterId to claimId].orEmpty().isEmpty() -> KnowledgeDecisionReason.NO_CHARACTER_KNOWLEDGE_EDGE
            edge == null -> KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF
            claim.scope != "story" -> KnowledgeDecisionReason.PROFILE_CLAIM
            !sourceAccepted(claim) -> KnowledgeDecisionReason.UNVERIFIED_SOURCE
            edge.beliefStatus != "believed" -> KnowledgeDecisionReason.UNSUPPORTED_BELIEF_STATUS
            claim.validFromSequence > cutoff || (claim.validToSequence != null && claim.validToSequence < cutoff) -> KnowledgeDecisionReason.NOT_VALID_AT_CUTOFF
            else -> null
        }
        if (rejection != null) {
            V2KnowledgeRetrieval(KnowledgeStoreStatus.READY, emptyList(), listOf(KnowledgeDecision(claimId, KnowledgeDisposition.DROPPED, rejection)))
        } else {
            V2KnowledgeRetrieval(
                KnowledgeStoreStatus.READY,
                listOf(
                    V2KnowledgeFact(
                        claimId = claimId,
                        statement = claim.statement,
                        eventId = claim.eventId,
                        matchedTerms = emptyList(),
                        acquisitionMethod = edge!!.acquisitionMethod,
                        acquiredSequence = edge.acquiredSequence,
                        confidence = minOf(claim.confidence, edge.confidence),
                        score = 1.0,
                    ),
                ),
                listOf(KnowledgeDecision(claimId, KnowledgeDisposition.SELECTED, KnowledgeDecisionReason.SELECTED, 1.0)),
            )
        }
    } catch (_: Exception) {
        V2KnowledgeRetrieval(KnowledgeStoreStatus.UNAVAILABLE, emptyList(), emptyList(), "v2_store_unavailable")
    }

    private fun loadDocument(): V2KnowledgeDocument {
        require(fallbackFile.isFile) { "Missing V2 fallback export: ${fallbackFile.absolutePath}" }
        val root = SimpleJsonParser(fallbackFile.readText(Charsets.UTF_8)).parse().asJsonObject("V2 fallback export")
        reviewedBundle?.let { bundle ->
            require(fallbackFile.canonicalFile == bundle.paths.resolve(bundle.manifest.paths.getValue("runtime_v2")))
            require(sha256(fallbackFile) == bundle.manifest.files.getValue(bundle.manifest.paths.getValue("runtime_v2"))) {
                "database_runtime_hash_mismatch"
            }
            require(root.stringValue("dataset_id") == bundle.manifest.datasetId &&
                root.stringValue("dataset_version") == bundle.manifest.datasetVersion) { "database_identity_mismatch" }
        }
        require(root.stringValue("schema_version").startsWith("2.")) { "Unsupported V2 fallback schema" }
        sourceDatabaseFile?.let { database ->
            require(database.isFile) { "Missing V2 source database: ${database.absolutePath}" }
            require(root.stringValue("source_database_sha256") == sha256(database)) {
                "V2 fallback export does not match the installed source database"
            }
        }
        val cutoffs = root["cutoffs"].asJsonObject("cutoffs").mapValues { (_, raw) ->
            val value = raw.asJsonObject("cutoff")["sequence"] as? Double
                ?: error("V2 cutoff has no numeric sequence")
            value.toLong()
        }
        val claims = root["claims"].asJsonArray("claims").map { raw -> parseClaim(raw.asJsonObject("claim")) }
        val edges = root["knowledge_edges"].asJsonArray("knowledge_edges").map { raw -> parseEdge(raw.asJsonObject("knowledge edge")) }
        val termsByClaimId = claims.associate { claim ->
            claim.claimId to terms(claim.statement + claim.keywords.joinToString(""))
        }
        val claimIdsByTerm = mutableMapOf<String, MutableSet<String>>()
        termsByClaimId.forEach { (claimId, terms) ->
            terms.forEach { term -> claimIdsByTerm.getOrPut(term) { linkedSetOf() } += claimId }
        }
        return V2KnowledgeDocument(
            cutoffs = cutoffs,
            claims = claims,
            edgesByCharacterAndClaim = edges.groupBy { it.characterId to it.claimId },
            termsByClaimId = termsByClaimId,
            claimIdsByTerm = claimIdsByTerm,
        )
    }

    private fun parseClaim(raw: Map<String, Any?>) = V2Claim(
        claimId = raw.stringValue("claim_id"),
        scope = raw.stringValue("claim_scope"),
        statement = raw.stringValue("statement"),
        eventId = raw["event_id"] as? String,
        validFromSequence = raw.number("valid_from_sequence").toLong(),
        validToSequence = raw["valid_to_sequence"]?.let { it as? Double ?: error("valid_to_sequence must be numeric") }?.toLong(),
        importance = raw.number("importance"),
        confidence = raw.number("confidence"),
        keywords = raw["keywords_json"].asJsonArray("keywords_json").map { it as? String ?: error("keyword must be a string") },
        sourceTier = raw.stringValue("source_tier"),
        canonStatus = raw.stringValue("canon_status"),
        verificationStatus = raw.stringValue("verification_status"),
    )

    private fun sourceAccepted(claim: V2Claim): Boolean = if (reviewedBundle != null) {
        claim.sourceTier == "canon" && claim.verificationStatus == "source_span_verified" &&
            claim.canonStatus == "accepted" && reviewedBundle.authorizedFacts.any {
                it.claimId == claim.claimId && it.statement == claim.statement
            }
    } else {
        claim.sourceTier == "curated" && claim.canonStatus == "accepted" && claim.verificationStatus == "verified_curated"
    }

    private fun parseEdge(raw: Map<String, Any?>) = V2KnowledgeEdge(
        characterId = raw.stringValue("character_id"),
        claimId = raw.stringValue("claim_id"),
        acquisitionMethod = raw.stringValue("acquisition_method"),
        acquiredSequence = raw.number("acquired_sequence").toLong(),
        confidence = raw.number("confidence"),
        beliefStatus = raw.stringValue("belief_status"),
    )

    private fun Map<String, Any?>.number(key: String): Double =
        this[key] as? Double ?: error("Missing numeric key: $key")

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

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private data class V2KnowledgeDocument(
        val cutoffs: Map<String, Long>,
        val claims: List<V2Claim>,
        val edgesByCharacterAndClaim: Map<Pair<String, String>, List<V2KnowledgeEdge>>,
        /** Build-once local inverted index; SQLite FTS is intentionally not used on Android. */
        val termsByClaimId: Map<String, Set<String>>,
        val claimIdsByTerm: Map<String, Set<String>>,
    )

    private data class V2Claim(
        val claimId: String,
        val scope: String,
        val statement: String,
        val eventId: String?,
        val validFromSequence: Long,
        val validToSequence: Long?,
        val importance: Double,
        val confidence: Double,
        val keywords: List<String>,
        val sourceTier: String,
        val canonStatus: String,
        val verificationStatus: String,
    )

    private data class V2KnowledgeEdge(
        val characterId: String,
        val claimId: String,
        val acquisitionMethod: String,
        val acquiredSequence: Long,
        val confidence: Double,
        val beliefStatus: String,
    )

    private companion object {
        val QueryFillers = listOf("究竟", "到底", "请问", "真的", "为什么", "是什么", "怎么样", "是不是", "会不会", "是谁", "什么", "怎么", "为何", "如何", "吗", "呢")
        val SearchStopTerms = setOf("你是", "我是", "有何", "有关", "关于")
    }
}

data class V2KnowledgeQuery(
    val characterId: String,
    val query: String,
    val storyCutoff: String,
    val limit: Int = 8,
    val candidateScores: Map<String, Double> = emptyMap(),
)

data class V2KnowledgeFact(
    val claimId: String,
    val statement: String,
    val eventId: String?,
    val matchedTerms: List<String>,
    val acquisitionMethod: String,
    val acquiredSequence: Long,
    val confidence: Double,
    val score: Double,
)
