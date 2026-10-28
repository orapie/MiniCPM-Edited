package com.example.minicpm_v_demo.harness.character

/**
 * Pure policy for one buffered CHARACTER_RAG generation.  It never retains a
 * rejected model string, so callers cannot accidentally persist it while
 * deciding whether to retry.
 */
class RoleplayGenerationCoordinator(
    private val fallbackText: String = DEFAULT_SAFE_FALLBACK,
) {
    fun resolve(
        attempt: Int,
        rawOutput: String,
        characterName: String,
        userInput: String,
    ): RoleplayGenerationAction = when (
        val decision = RoleplayOutputGuard.evaluate(rawOutput, characterName, userInput)
    ) {
        is RoleplayOutputDecision.Accept -> RoleplayGenerationAction.Deliver(
            visibleText = decision.visibleText,
            persistVisibleText = true,
        )
        is RoleplayOutputDecision.RetryWithRepair -> {
            if (attempt < MAX_REPAIR_ATTEMPTS) {
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

    companion object {
        const val MAX_REPAIR_ATTEMPTS = 1
        const val DEFAULT_SAFE_FALLBACK = "这话我一时说不清。"
        const val REPAIR_INSTRUCTION =
            "只直接用角色第一人称回答玩家，不反问或追问玩家，不谈论你收到的说明或材料。"
    }
}

sealed interface RoleplayGenerationAction {
    data class Deliver(
        val visibleText: String,
        val persistVisibleText: Boolean,
    ) : RoleplayGenerationAction

    data class Retry(val ruleIds: List<String>) : RoleplayGenerationAction

    /** Safe text is shown but not persisted as a conversational fact/target. */
    data class Fallback(
        val visibleText: String,
        val ruleIds: List<String>,
    ) : RoleplayGenerationAction
}
