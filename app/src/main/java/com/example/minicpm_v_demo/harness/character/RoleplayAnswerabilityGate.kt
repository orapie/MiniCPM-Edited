package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDecision
import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDecisionReason
import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDisposition

/**
 * Stops a Character+RAG turn before generation when a matched future fact has
 * no authorized alternative or is substantially more relevant than all
 * authorized matches. Ordinary retrieval misses still reach the model.
 */
object RoleplayAnswerabilityGate {
    const val FUTURE_FACT_RESPONSE = "此事我尚不清楚，不能妄言。"

    fun evaluate(debug: CharacterPromptDebug?, userInput: String? = null): RoleplayAnswerabilityDecision {
        if (debug == null) return RoleplayAnswerabilityDecision.Allow
        val future = evaluate(debug.knowledgeGraphDecisions + debug.v1ClaimGraphDecisions)
        if (future is RoleplayAnswerabilityDecision.BlockFutureFact) return future
        val noRelevantEvidence = debug.selectedItems.none { it.kind in setOf(
            "authorized_story_facts", "relevant_relationships", "relevant_profile", "retrieved_memories",
        ) }
        return if (userInput != null && "legacy_fallback_disabled" in debug.databaseCapabilities &&
            "ORIGIN" in debug.queryIntents && !CharacterQuestionSubject.isSelfOrigin(userInput) && noRelevantEvidence) {
            RoleplayAnswerabilityDecision.BlockUnknownFact(FUTURE_FACT_RESPONSE)
        } else RoleplayAnswerabilityDecision.Allow
    }

    fun evaluate(decisions: List<KnowledgeDecision>): RoleplayAnswerabilityDecision {
        val authorized = decisions.filter { it.disposition == KnowledgeDisposition.SELECTED }
        val future = decisions.filter {
            it.disposition == KnowledgeDisposition.DROPPED &&
                it.reason == KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF
        }
        val bestAuthorized = authorized.mapNotNull { it.score }.maxOrNull()
        val bestFuture = future.mapNotNull { it.score }.maxOrNull()
        // A weak match on a shared name is not enough to answer a question
        // whose substantially stronger match is unavailable at this cutoff.
        val futureDominates = bestFuture != null && bestAuthorized != null &&
            bestFuture > bestAuthorized + FUTURE_RELEVANCE_MARGIN
        return if (future.isNotEmpty() && (authorized.isEmpty() || futureDominates)) {
            RoleplayAnswerabilityDecision.BlockFutureFact(FUTURE_FACT_RESPONSE)
        } else {
            RoleplayAnswerabilityDecision.Allow
        }
    }

    private const val FUTURE_RELEVANCE_MARGIN = 0.15
}

sealed interface RoleplayAnswerabilityDecision {
    data object Allow : RoleplayAnswerabilityDecision

    data class BlockFutureFact(val visibleResponse: String) : RoleplayAnswerabilityDecision
    data class BlockUnknownFact(val visibleResponse: String) : RoleplayAnswerabilityDecision
}
