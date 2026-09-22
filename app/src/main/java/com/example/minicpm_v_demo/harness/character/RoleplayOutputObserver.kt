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

data class RoleplayOutputSignal(
    val ruleId: String,
    val evidence: String,
)

data class RoleplayOutputObservation(
    val context: RoleplayObservationContext,
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
    ): RoleplayOutputObservation {
        val signals = buildList {
            if (visibleOutput.isBlank()) add(RoleplayOutputSignal("empty_visible_output", "visible output is blank"))
            if (rawOutput.contains("<think>") && !rawOutput.contains("</think>")) {
                add(RoleplayOutputSignal("unclosed_think_block", "<think> has no closing tag"))
            }
            if (assistantIdentityPattern.containsMatchIn(visibleOutput)) {
                add(RoleplayOutputSignal("assistant_identity", "assistant identity phrase"))
            }
            if (metaPromptPattern.containsMatchIn(visibleOutput)) {
                add(RoleplayOutputSignal("meta_prompt_reference", "role-card or prompt reference"))
            }
            if (selfNarrationPattern(context.characterName).containsMatchIn(visibleOutput)) {
                add(RoleplayOutputSignal("possible_self_narration", "character-name narration pattern"))
            }
        }
        return RoleplayOutputObservation(
            context = context,
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

    private val assistantIdentityPattern = Regex("作为(?:一个)?(?:AI|人工智能|语言模型|通用助手|助手)")
    private val metaPromptPattern = Regex("角色卡|系统提示|提示词|\\bprompt\\b", RegexOption.IGNORE_CASE)

    private fun selfNarrationPattern(characterName: String): Regex = Regex(
        "${Regex.escape(characterName)}[，、 ]*(?:沉默|转身|抬眼|皱眉|心想|说道|说|答道|开口)",
    )

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

}

private fun String.singleLineForLog(): String = replace('\n', ' ').replace('\r', ' ').trim()
