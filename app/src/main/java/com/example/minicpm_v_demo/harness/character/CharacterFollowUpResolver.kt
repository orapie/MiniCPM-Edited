package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.rag.RagMode
import com.example.minicpm_v_demo.harness.session.ChatSession
import com.example.minicpm_v_demo.harness.data.database.DatabaseIdentity

/**
 * Reuses only one opaque, already-authorized target from the immediately prior
 * same-character turn.  It never rewrites a player question or carries over
 * external RAG text, and ambiguity deliberately falls back to normal retrieval.
 */
object CharacterFollowUpResolver {
    private val PronounSignals = listOf("他", "她", "它", "那把", "那个", "那件", "此物")
    private val ContinuationSignals = listOf("其他的还有吗", "还有吗", "还有什么", "再说说", "进一步", "展开", "详细点", "详细说")
    private val AllowedKinds = setOf(
        "authorized_story_facts",
        "relevant_relationships",
        "relevant_possessions",
        "relevant_profile",
    )

    fun resolve(
        session: ChatSession?,
        userInput: String,
        characterId: String,
        storyCutoff: String?,
        knownCharacterNames: Collection<String>,
        datasetIdentity: DatabaseIdentity,
    ): CharacterFollowUpTarget? {
        val isPronounFollowUp = PronounSignals.any { it in userInput }
        val isContinuationFollowUp = ContinuationSignals.any { it in userInput }
        if (!isPronounFollowUp && !isContinuationFollowUp) return null
        if (knownCharacterNames.any { it.isNotBlank() && it in userInput }) return null
        val previous = session?.turns?.lastOrNull() ?: return null
        if (session.datasetId != datasetIdentity.datasetId || session.datasetFingerprint != datasetIdentity.fingerprint ||
            session.characterPackId != datasetIdentity.datasetId) return null
        if (session.characterId != characterId || session.storyCutoff != storyCutoff) return null
        if (previous.characterId != characterId || previous.ragMode != RagMode.CHARACTER_RAG.name) return null
        if (previous.sources.isNotEmpty()) return null
        val targets = previous.characterContextTargets.filter { it.kind in AllowedKinds }
        // A profile turn can select two atomic statements. Persist one opaque
        // record ID so a continuation can retrieve a sibling record rather
        // than copying raw prior prompt text into the next prompt.
        return when {
            targets.size == 1 -> targets.single()
            isContinuationFollowUp -> targets.singleOrNull { it.kind == "relevant_profile" }
            else -> null
        }
    }

    fun persistableTargets(debug: CharacterPromptDebug?): List<CharacterFollowUpTarget> = debug
        ?.selectedItems
        .orEmpty()
        .filter { it.kind in AllowedKinds }
        .map { CharacterFollowUpTarget(it.kind, it.id) }
        .distinct()
        .groupBy { it.kind }
        .flatMap { (kind, targets) -> if (kind == "relevant_profile") targets.take(1) else targets }
}
