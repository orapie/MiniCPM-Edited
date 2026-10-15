package com.example.minicpm_v_demo.harness.character.knowledge

/**
 * Debug-only selection record.  It intentionally contains no claim statement,
 * evidence text, or player query, so callers can aggregate it without logging
 * private story content.
 */
data class KnowledgeDecision(
    val claimId: String,
    val disposition: KnowledgeDisposition,
    val reason: KnowledgeDecisionReason,
    val score: Double? = null,
)

enum class KnowledgeDisposition { SELECTED, DROPPED }

enum class KnowledgeDecisionReason {
    SELECTED,
    NO_CHARACTER_KNOWLEDGE_EDGE,
    ACQUIRED_AFTER_CUTOFF,
    PROFILE_CLAIM,
    UNVERIFIED_SOURCE,
    UNSUPPORTED_BELIEF_STATUS,
    NOT_VALID_AT_CUTOFF,
    NO_LEXICAL_MATCH,
    RANK_LIMIT,
    RELATION_NOT_FROM_CHARACTER,
    RELATION_NOT_RELEVANT,
}

enum class KnowledgeStoreStatus { READY, UNAVAILABLE, INVALID_REQUEST }

data class V2KnowledgeRetrieval(
    val status: KnowledgeStoreStatus,
    val facts: List<V2KnowledgeFact>,
    val decisions: List<KnowledgeDecision>,
    /** A stable code only; never surface filesystem paths or database text. */
    val fallbackReason: String? = null,
)

data class V1RelationshipRetrieval(
    val status: KnowledgeStoreStatus,
    val relations: List<V1KnowledgeRelation>,
    val decisions: List<KnowledgeDecision>,
    /** A stable code only; never surface filesystem paths or graph text. */
    val fallbackReason: String? = null,
)

data class V1KnowledgeRelation(
    val relationId: String,
    val targetId: String,
    val targetName: String,
    val relationshipType: String,
    val attitude: String,
    val confidence: Double,
    val score: Double,
)
