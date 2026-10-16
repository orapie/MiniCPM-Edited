package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDecision
import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDecisionReason
import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDisposition

/**
 * Stops a Character+RAG turn before generation only when the graph has matched
 * a fact that the current character is not yet allowed to know.  This is a
 * narrow, deterministic boundary: ordinary retrieval misses still reach the
 * model, and a turn with at least one authorized graph fact is not blocked.
 */
object RoleplayAnswerabilityGate {
    const val FUTURE_FACT_RESPONSE = "此事尚未发生，我不能妄言。"

    fun evaluate(debug: CharacterPromptDebug?): RoleplayAnswerabilityDecision {
        if (debug == null) return RoleplayAnswerabilityDecision.Allow
        return evaluate(debug.knowledgeGraphDecisions + debug.v1ClaimGraphDecisions)
    }

    fun evaluate(decisions: List<KnowledgeDecision>): RoleplayAnswerabilityDecision {
        val hasAuthorizedFact = decisions.any { it.disposition == KnowledgeDisposition.SELECTED }
        val matchedFutureFact = decisions.any {
            it.disposition == KnowledgeDisposition.DROPPED &&
                it.reason == KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF
        }
        return if (matchedFutureFact && !hasAuthorizedFact) {
            RoleplayAnswerabilityDecision.BlockFutureFact(FUTURE_FACT_RESPONSE)
        } else {
            RoleplayAnswerabilityDecision.Allow
        }
    }
}

sealed interface RoleplayAnswerabilityDecision {
    data object Allow : RoleplayAnswerabilityDecision

    data class BlockFutureFact(val visibleResponse: String) : RoleplayAnswerabilityDecision
}
