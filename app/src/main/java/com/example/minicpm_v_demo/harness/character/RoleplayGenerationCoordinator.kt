package com.example.minicpm_v_demo.harness.character

/**
 * Pure policy for one buffered CHARACTER_RAG generation.  It never retains a
 * rejected model string, so callers cannot accidentally persist it while
 * deciding whether to retry.
 */
class RoleplayGenerationCoordinator(
    private val fallbackText: String = DEFAULT_SAFE_FALLBACK,
) {
    /** 根据输出检查结果交付可见文本；当前移动端策略对不安全输出直接使用固定回退文本。 */
    fun resolve(
        attempt: Int,
        rawOutput: String,
        characterName: String,
        userInput: String,
        characterAliases: List<String> = emptyList(),
        fixedResponse: Boolean = false,
        publicProfileReply: String? = null,
    ): RoleplayGenerationAction {
        val decision = RoleplayOutputGuard.evaluate(rawOutput, characterName, userInput, characterAliases)
        if (!fixedResponse && publicProfileReply != null) {
            val approved = RoleplayOutputGuard.evaluate(publicProfileReply, characterName, userInput, characterAliases)
            if (approved is RoleplayOutputDecision.Accept && (decision !is RoleplayOutputDecision.Accept ||
                    comparableReply(decision.visibleText) != comparableReply(approved.visibleText))) {
                return RoleplayGenerationAction.Fallback(
                    approved.visibleText,
                    listOf(PUBLIC_PROFILE_FALLBACK),
                    persistVisibleText = true,
                )
            }
        }
        return when (decision) {
            is RoleplayOutputDecision.Accept -> RoleplayGenerationAction.Deliver(
                visibleText = decision.visibleText,
                persistVisibleText = true,
            )
            is RoleplayOutputDecision.RetryWithRepair -> {
                if (!fixedResponse && attempt < MAX_REPAIR_ATTEMPTS) {
                    RoleplayGenerationAction.Retry(decision.ruleIds)
                } else {
                    RoleplayGenerationAction.Fallback(fallbackText, decision.ruleIds)
                }
            }
            is RoleplayOutputDecision.SafeFallback -> RoleplayGenerationAction.Fallback(
                visibleText = fallbackText,
                ruleIds = decision.ruleIds,
            )
        }
    }

    private fun comparableReply(reply: String): String = reply.trim().trimEnd('。', '.', '！', '!')

    companion object {
        // A second full mobile inference can double end-to-end latency. Reject
        // unsafe output immediately and use the deterministic safe fallback.
        const val MAX_REPAIR_ATTEMPTS = 0
        const val DEFAULT_SAFE_FALLBACK = "这话我一时说不清。"
        const val PUBLIC_PROFILE_FALLBACK = "public_profile_unverified"
        const val REPAIR_INSTRUCTION =
            "只直接用角色第一人称回答玩家，不反问或追问玩家，不谈论你收到的说明或材料。"

        fun repairInstruction(ruleIds: List<String>): String {
            val perspectiveIssue = ruleIds.any {
                it == RoleplayLeakRule.SELF_SECOND_PERSON_NARRATION.id ||
                    it == RoleplayLeakRule.SELF_THIRD_PERSON_NARRATION.id ||
                    it == RoleplayLeakRule.ASSISTANT_IDENTITY.id
            }
            return if (perspectiveIssue) {
                REPAIR_INSTRUCTION + "\n玩家问句里的“你”是当前角色。答自己的身份或经历时，直接从“我”说起。"
            } else REPAIR_INSTRUCTION
        }
    }
}

sealed interface RoleplayGenerationAction {
    data class Deliver(
        val visibleText: String,
        val persistVisibleText: Boolean,
    ) : RoleplayGenerationAction

    data class Retry(val ruleIds: List<String>) : RoleplayGenerationAction

    /** Generic fallback text is display-only. A reviewed profile reply may be persisted. */
    data class Fallback(
        val visibleText: String,
        val ruleIds: List<String>,
        val persistVisibleText: Boolean = false,
    ) : RoleplayGenerationAction
}
