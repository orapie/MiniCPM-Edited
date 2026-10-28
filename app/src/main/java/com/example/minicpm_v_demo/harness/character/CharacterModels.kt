package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.rag.RagPromptStageLog

data class HarnessChatMessage(
    val role: String,
    val content: String,
)

data class RuntimeContext(
    val storyCutoff: String? = null,
    val dynamicState: DynamicState? = null,
    val maxChars: Int = 4500,
    val topK: Int = 8,
    val conversationSummary: String = "",
    val knowledgeGraphEnabled: Boolean = true,
    val graphFactTopK: Int = 4,
    val graphRelationTopK: Int = 2,
    val knowledgeGraphMaxChars: Int = 320,
    val knowledgeGraphRelationMaxChars: Int = 160,
    /** Query-aware facts from the current character card, never graph profile claims. */
    val profileTopK: Int = 2,
    val profileMaxChars: Int = 180,
    /** Query-aware current-state evidence from the current character card. */
    val stateTopK: Int = 1,
    val stateMaxChars: Int = 96,
    /** Query-aware, time-bounded possessions; historical facts never supply this. */
    val possessionTopK: Int = 2,
    val possessionMaxChars: Int = 160,
    /** A prior, authorized internal target; never contains source text or player text. */
    val followUpTarget: CharacterFollowUpTarget? = null,
    val vectorCandidateScores: Map<String, Double> = emptyMap(),
)

data class CompiledCharacterPrompt(
    val messages: List<HarnessChatMessage>,
    val debug: CharacterPromptDebug,
    /** Raw-text, in-memory only snapshots for Debug Logcat. Never persist these. */
    val stageLogs: List<RagPromptStageLog> = emptyList(),
)

data class CharacterPromptDebug(
    val npcId: String,
    val storyCutoff: String,
    val maxChars: Int,
    val promptChars: Int,
    val mandatoryChars: Int,
    val budgetExceededByMandatory: Boolean,
    val selectedItems: List<SelectedPromptItem>,
    val droppedItems: List<DroppedPromptItem>,
    val retrievalDecisions: List<RetrievalDecision>,
    val estimatedPromptTokens: Int = 0,
    val estimatedMandatoryTokens: Int = 0,
    val conversationSummaryChars: Int = 0,
    /** Budget held back from conversation history so current-turn evidence can be injected. */
    val reservedDynamicEvidenceChars: Int = 0,
    val estimatedUserTokens: Int = 0,
    val knowledgeGraphStatus: String = "not_configured",
    val knowledgeGraphFallbackReason: String? = null,
    val knowledgeGraphDecisions: List<com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDecision> = emptyList(),
    val knowledgeGraphInjectedChars: Int = 0,
    val v1ClaimGraphStatus: String = "not_configured",
    val v1ClaimGraphFallbackReason: String? = null,
    val v1ClaimGraphDecisions: List<com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDecision> = emptyList(),
    val v1ClaimGraphInjectedChars: Int = 0,
    val v1RelationshipGraphStatus: String = "not_configured",
    val v1RelationshipGraphFallbackReason: String? = null,
    val v1RelationshipGraphDecisions: List<com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDecision> = emptyList(),
    val v1RelationshipGraphInjectedChars: Int = 0,
    /** Content-free audit fields for the current character-card profile path. */
    val profileRoute: String = "not_requested",
    val profileCandidateCount: Int = 0,
    val profileCandidateIds: List<String> = emptyList(),
    val profileSelectedIds: List<String> = emptyList(),
    val profileBudgetDroppedCount: Int = 0,
    val profileInjectedChars: Int = 0,
    /** Content-free audit fields for the current DynamicState retrieval path. */
    val stateRoute: String = "not_requested",
    val stateCandidateCount: Int = 0,
    val stateCandidateIds: List<String> = emptyList(),
    val stateSelectedIds: List<String> = emptyList(),
    val stateBudgetDroppedCount: Int = 0,
    val stateInjectedChars: Int = 0,
    /** Content-free audit fields for time-bounded current possessions. */
    val possessionRoute: String = "not_requested",
    val possessionCandidateCount: Int = 0,
    val possessionSelectedCount: Int = 0,
    val possessionBudgetDroppedCount: Int = 0,
    val possessionInjectedChars: Int = 0,
    /** Whether this turn accepted a same-character, same-cutoff follow-up target. */
    val followUpRoute: String = "not_requested",
    /** Stable, content-free routing label for runtime A/B measurements. */
    val knowledgeQueryIntent: String = "unknown",
    /** Phase 1 query analysis, represented as stable enum names only. */
    val queryIntents: List<String> = emptyList(),
    val requestedKnowledgeSurfaces: List<String> = emptyList(),
    val queryExplanationCodes: List<String> = emptyList(),
    val legacyStoryRetrievalExecuted: Boolean = false,
    val legacyMemoryRetrievalExecuted: Boolean = false,
    /** Hash of the fixed template/base sections before per-turn evidence is selected. */
    val basePromptFingerprint: String = "none",
    /** Hash of only the selected per-turn evidence sections; `none` means no evidence was injected. */
    val dynamicEvidenceFingerprint: String = "none",
    /** Hash of selected section/ID pairs, independent from their source text. */
    val selectedItemsFingerprint: String = "none",
    val databaseCapabilities: List<String> = emptyList(),
)

data class SelectedPromptItem(
    val kind: String,
    val id: String,
    val priority: Double,
)

/** Persisted as an opaque ID plus section only; raw RAG text is never retained here. */
data class CharacterFollowUpTarget(
    val kind: String,
    val id: String,
)

data class DroppedPromptItem(
    val kind: String,
    val id: String,
    val reason: String,
)

data class RetrievalDecision(
    val eventId: String,
    val allowed: Boolean,
    val reason: String,
    val score: Double,
)

data class CharacterCard(
    val npcId: String,
    val identity: IdentityCore,
    /** Evidence references for atomized profile records, keyed by source card path. */
    val profileEvidenceRefs: Map<String, List<String>> = emptyMap(),
    val relationships: List<CharacterRelationship>,
    val knowledgeScope: KnowledgeScope,
    val dynamicState: DynamicState,
    val episodicMemory: List<EpisodicMemory>,
)

data class IdentityCore(
    val name: String,
    val aliases: List<String>,
    val worldviewPosition: String,
    val background: List<String>,
    val coreMotivations: List<String>,
    val values: List<String>,
    val psychologicalTraits: List<String>,
    val speechStyle: List<String>,
    val speechExamples: List<String>,
    val behaviorRules: List<String>,
    val hardConstraints: List<String>,
)

data class CharacterRelationship(
    val targetName: String,
    val relationshipType: String,
    val attitude: String,
)

data class KnowledgeScope(
    val worldFactRefs: Set<String>,
    val storyCutoff: String,
)

data class DynamicState(
    val scene: String,
    val emotion: EmotionState,
    val questState: String,
    val currentGoal: String,
    val affinity: Int,
)

/** Time-bounded, evidence-backed state kept separate from the stable character card. */
data class CharacterStateDocument(
    val characterId: String,
    val states: List<TimedCharacterState>,
)

data class TimedCharacterState(
    val stateId: String,
    val validFrom: String,
    val validTo: String?,
    val scene: String,
    val questState: String,
    val currentGoal: String,
    val possessions: List<CurrentPossession>,
)

data class CurrentPossession(
    val itemId: String,
    val name: String,
    val status: PossessionStatus,
    val description: String,
    val keywords: List<String>,
)

enum class PossessionStatus {
    CARRIED,
    NOT_CARRIED,
    UNKNOWN;

    companion object {
        fun fromWire(value: String): PossessionStatus = when (value) {
            "carried" -> CARRIED
            "not_carried" -> NOT_CARRIED
            "unknown" -> UNKNOWN
            else -> error("Unsupported possession status: $value")
        }
    }
}

data class EmotionState(
    val label: String,
    val trigger: String,
    val intensity: Double,
    val decayRule: String,
)

data class EpisodicMemory(
    val factRef: String,
    val validFrom: String,
    val validTo: String?,
    val source: String,
    val visibility: Set<String>,
    val confidence: Double,
)

data class StoryEvent(
    val eventId: String,
    val timeIndex: Int,
    val fact: String,
    val importance: Double,
    val confidence: Double,
    val visibility: Set<String>,
    val keywords: List<String>,
    val knowledgeByCharacter: Map<String, CharacterKnowledge>,
)

data class CharacterKnowledge(
    val source: String,
    val availableAfter: String,
    val confidence: Double,
)

data class RetrievedEvent(
    val event: StoryEvent,
    val score: Double,
    val matchedTerms: List<String>,
)

data class RetrievedMemory(
    val memory: EpisodicMemory,
    val event: StoryEvent,
    val score: Double,
)
