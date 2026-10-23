package com.example.minicpm_v_demo.harness.character

import java.security.MessageDigest

/**
 * Stage 0 observability for character-mode output.
 *
 * This component only records evidence. It must not reject, rewrite, retry, or otherwise change
 * what the user sees; enforcement belongs to the later output-guard stage.
 */
data class RoleplayObservationContext(
    val observationId: String,
    val characterId: String,
    val characterName: String,
    val storyCutoff: String?,
    val modelId: String,
    val ragMode: String,
    val systemPromptChars: Int,
    val userPromptChars: Int,
    val renderedPromptChars: Int,
    val systemPromptSha256: String,
    val userPromptSha256: String,
    val estimatedSystemTokens: Int?,
    val estimatedUserTokens: Int?,
    val contextChars: Int,
    val sourceCount: Int,
    val predictLength: Int,
)

data class RoleplayOutputObservation(
    val context: RoleplayObservationContext,
    val attempt: Int,
    val decision: String,
    val completion: String,
    val rawOutputChars: Int,
    val visibleOutputChars: Int,
    val rawOutputSha256: String,
    val visibleOutputSha256: String,
    val signals: List<RoleplayOutputSignal>,
    /** Present only for debug Logcat; never persist this field in sessions or memory. */
    val debugRawOutput: String? = null,
    /** Present only for debug Logcat; never persist this field in sessions or memory. */
    val debugVisibleOutput: String? = null,
) {
    fun toLogLine(includeRawText: Boolean): String = buildString {
        append("roleplay_observation id=").append(context.observationId)
        append(" attempt=").append(attempt)
        append(" decision=").append(decision)
        append(" completion=").append(completion)
        append(" character=").append(context.characterId)
        append(" cutoff=").append(context.storyCutoff ?: "n/a")
        append(" model=").append(context.modelId)
        append(" mode=").append(context.ragMode)
        append(" promptChars=").append(context.systemPromptChars).append('/').append(context.userPromptChars)
        append(" promptSha256=").append(context.systemPromptSha256).append('/').append(context.userPromptSha256)
        append(" renderedChars=").append(context.renderedPromptChars)
        append(" estimatedTokens=").append(context.estimatedSystemTokens ?: "n/a")
            .append('/').append(context.estimatedUserTokens ?: "n/a")
        append(" contextChars=").append(context.contextChars)
        append(" sources=").append(context.sourceCount)
        append(" predictLength=").append(context.predictLength)
        append(" outputChars=").append(rawOutputChars).append('/').append(visibleOutputChars)
        append(" outputSha256=").append(rawOutputSha256).append('/').append(visibleOutputSha256)
        append(" signals=").append(signals.joinToString(",") { it.ruleId })
        if (includeRawText) {
            append(" rawOutput=").append(debugRawOutput.orEmpty().singleLineForLog())
            append(" visibleOutput=").append(debugVisibleOutput.orEmpty().singleLineForLog())
        }
    }
}

object RoleplayOutputObserver {
    fun createContext(
        observationId: String,
        characterId: String,
        characterName: String,
        storyCutoff: String?,
        modelId: String,
        ragMode: String,
        systemPrompt: String,
        userPrompt: String,
        renderedPrompt: String,
        estimatedSystemTokens: Int?,
        estimatedUserTokens: Int?,
        contextChars: Int,
        sourceCount: Int,
        predictLength: Int,
    ): RoleplayObservationContext = RoleplayObservationContext(
        observationId = observationId,
        characterId = characterId,
        characterName = characterName,
        storyCutoff = storyCutoff,
        modelId = modelId,
        ragMode = ragMode,
        systemPromptChars = systemPrompt.length,
        userPromptChars = userPrompt.length,
        renderedPromptChars = renderedPrompt.length,
        systemPromptSha256 = sha256(systemPrompt),
        userPromptSha256 = sha256(userPrompt),
        estimatedSystemTokens = estimatedSystemTokens,
        estimatedUserTokens = estimatedUserTokens,
        contextChars = contextChars,
        sourceCount = sourceCount,
        predictLength = predictLength,
    )

    fun observe(
        context: RoleplayObservationContext,
        rawOutput: String,
        visibleOutput: String,
        completion: String,
        includeRawText: Boolean,
        attempt: Int = 0,
        decision: RoleplayOutputDecision? = null,
    ): RoleplayOutputObservation {
        val signals = RoleplayLeakClassifier.classify(rawOutput, visibleOutput, context.characterName)
        return RoleplayOutputObservation(
            context = context,
            attempt = attempt,
            decision = decision?.auditLabel() ?: "observed",
            completion = completion,
            rawOutputChars = rawOutput.length,
            visibleOutputChars = visibleOutput.length,
            rawOutputSha256 = sha256(rawOutput),
            visibleOutputSha256 = sha256(visibleOutput),
            signals = signals,
            debugRawOutput = rawOutput.takeIf { includeRawText },
            debugVisibleOutput = visibleOutput.takeIf { includeRawText },
        )
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

}

private fun RoleplayOutputDecision.auditLabel(): String = when (this) {
    is RoleplayOutputDecision.Accept -> "accept"
    is RoleplayOutputDecision.RetryWithRepair -> "retry_with_repair"
    is RoleplayOutputDecision.SafeFallback -> "safe_fallback"
}

private fun String.singleLineForLog(): String = replace('\n', ' ').replace('\r', ' ').trim()
