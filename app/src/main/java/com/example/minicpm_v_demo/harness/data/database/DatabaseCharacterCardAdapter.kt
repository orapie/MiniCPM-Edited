package com.example.minicpm_v_demo.harness.data.database

import com.example.minicpm_v_demo.harness.character.*

/** Full biographies describe the card's declared cutoff, never an earlier scene. */
class DatabaseCharacterCardAdapter(private val bundle: ValidatedDatabaseBundle) {
    fun atCutoff(card: CharacterCard, cutoff: String): CharacterCard {
        val sequence = bundle.cutoffs[cutoff] ?: error("database_unknown_cutoff")
        val cardSequence = bundle.cutoffs[card.knowledgeScope.storyCutoff] ?: error("database_unknown_card_cutoff")
        val scoped = if (sequence >= cardSequence) card else card.copy(
            identity = card.identity.copy(
                aliases = emptyList(), worldviewPosition = "",
                background = emptyList(), coreMotivations = emptyList(), values = emptyList(),
                psychologicalTraits = emptyList(), behaviorRules = emptyList(),
                speechExamples = emptyList(),
            ),
            profileEvidenceRefs = emptyMap(), relationships = emptyList(), episodicMemory = emptyList(),
        )
        // No timed-state capability: a full-biography card's last scene cannot
        // establish where the speaker is, what they carry or what they do now.
        return scoped.copy(dynamicState = DynamicState("", EmotionState("平静", "", 0.0, ""), "", "", 0))
    }
}
