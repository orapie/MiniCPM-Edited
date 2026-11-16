package com.example.minicpm_v_demo.harness.context

import com.example.minicpm_v_demo.harness.ContentFingerprint
import com.example.minicpm_v_demo.harness.session.ChatSession
import com.example.minicpm_v_demo.harness.session.ConversationSummaryBuilder

/** Identity boundary for history that is eligible to influence a prompt. */
data class ConversationContextIdentity(
    val datasetId: String,
    val datasetFingerprint: String,
    val characterId: String,
    val storyCutoff: String,
)

/**
 * Produces a bounded, question-aware history excerpt for the active character turn.
 *
 * It deliberately operates only on the same dataset fingerprint, character and cutoff.
 * Character profile/state/fact retrieval remains outside this component.
 */
class ConversationContextCompressor(
    private val summaryBuilder: ConversationSummaryBuilder = ConversationSummaryBuilder(),
) {
    /** 只从数据包指纹、角色和剧情截止点均匹配的历史生成本轮摘要，并按当前问题压缩到指定预算。 */
    fun compress(
        session: ChatSession?,
        currentQuestion: String,
        maxChars: Int,
        identity: ConversationContextIdentity,
    ): CompressedConversationContext {
        require(currentQuestion.isNotBlank()) { "currentQuestion must not be blank" }
        require(maxChars >= 0) { "maxChars must not be negative" }
        if (maxChars == 0 || session == null || !matches(session, identity)) {
            return CompressedConversationContext.EMPTY
        }
        val summary = summaryBuilder.build(session.turns).trim()
        if (summary.isBlank()) return CompressedConversationContext.EMPTY
        val compressed = ContextController.compress(summary, currentQuestion, maxChars).trim()
        if (compressed.isBlank()) return CompressedConversationContext.EMPTY
        return CompressedConversationContext(
            text = compressed,
            sourceTurnCount = session.turns.size,
            sourceChars = summary.length,
            fingerprint = ContentFingerprint.sha256Short(compressed),
        )
    }

    private fun matches(session: ChatSession, identity: ConversationContextIdentity): Boolean =
        session.datasetId == identity.datasetId &&
            session.datasetFingerprint == identity.datasetFingerprint &&
            session.characterPackId == identity.datasetId &&
            session.characterId == identity.characterId &&
            session.storyCutoff == identity.storyCutoff &&
            session.turns.all { it.characterId == identity.characterId }
}
