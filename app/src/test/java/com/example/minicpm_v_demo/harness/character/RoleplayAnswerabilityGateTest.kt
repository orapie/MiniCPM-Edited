package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDecision
import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDecisionReason
import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayAnswerabilityGateTest {
    @Test
    fun blocksMatchedFutureFactWhenNoAuthorizedFactIsAvailable() {
        val decision = RoleplayAnswerabilityGate.evaluate(
            listOf(
                KnowledgeDecision(
                    claimId = "v2-claim-evt-020",
                    disposition = KnowledgeDisposition.DROPPED,
                    reason = KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF,
                ),
            ),
        )

        assertEquals(
            RoleplayAnswerabilityGate.FUTURE_FACT_RESPONSE,
            (decision as RoleplayAnswerabilityDecision.BlockFutureFact).visibleResponse,
        )
    }

    @Test
    fun keepsGenerationAvailableWhenAnAuthorizedFactCanAnswerTheTurn() {
        val decision = RoleplayAnswerabilityGate.evaluate(
            listOf(
                KnowledgeDecision(
                    claimId = "event-evt-018",
                    disposition = KnowledgeDisposition.SELECTED,
                    reason = KnowledgeDecisionReason.SELECTED,
                ),
                KnowledgeDecision(
                    claimId = "v2-claim-evt-020",
                    disposition = KnowledgeDisposition.DROPPED,
                    reason = KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF,
                ),
            ),
        )

        assertTrue(decision is RoleplayAnswerabilityDecision.Allow)
    }
}
