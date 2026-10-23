package com.example.minicpm_v_demo.harness.character

/** Phase-0/2 stable severity taxonomy for roleplay output observation and guarding. */
enum class RoleplayLeakSeverity {
    OBSERVATION,
    WARNING,
    HIGH,
}

enum class RoleplayLeakRule(
    val id: String,
    val severity: RoleplayLeakSeverity,
) {
    EMPTY_VISIBLE_OUTPUT("empty_visible_output", RoleplayLeakSeverity.OBSERVATION),
    UNCLOSED_THINK_BLOCK("unclosed_think_block", RoleplayLeakSeverity.HIGH),
    ASSISTANT_IDENTITY("assistant_identity", RoleplayLeakSeverity.HIGH),
    META_PROMPT_REFERENCE("meta_prompt_reference", RoleplayLeakSeverity.HIGH),
    INTERNAL_FIELD_LABEL("internal_field_label", RoleplayLeakSeverity.HIGH),
    TEMPLATE_TOKEN("template_token", RoleplayLeakSeverity.HIGH),
    POSSIBLE_SELF_NARRATION("possible_self_narration", RoleplayLeakSeverity.WARNING),
}

data class RoleplayOutputSignal(
    val rule: RoleplayLeakRule,
    /** A fixed classifier description, never a copied substring from the model output. */
    val evidence: String,
) {
    val ruleId: String get() = rule.id
    val severity: RoleplayLeakSeverity get() = rule.severity
}

sealed interface RoleplayOutputDecision {
    data class Accept(val visibleText: String) : RoleplayOutputDecision

    /** The caller may make one fresh attempt without echoing the rejected output to the model. */
    data class RetryWithRepair(val ruleIds: List<String>) : RoleplayOutputDecision

    /** The caller must show a role-safe fallback rather than the rejected output. */
    data class SafeFallback(val ruleIds: List<String>) : RoleplayOutputDecision
}

/**
 * Pure Kotlin guard for the later buffered-rendering path.
 *
 * It does not modify text piecemeal.  A high-confidence prompt/field leak
 * invalidates the entire candidate so a caller can retry once or render a safe
 * in-character fallback.  The guard itself has no Android/UI dependencies.
 */
object RoleplayOutputGuard {
    fun evaluate(
        rawOutput: String,
        characterName: String,
        @Suppress("UNUSED_PARAMETER") userInput: String,
    ): RoleplayOutputDecision {
        val extraction = RoleplayVisibleText.extract(rawOutput)
        val signals = RoleplayLeakClassifier.classify(
            rawOutput = rawOutput,
            visibleOutput = extraction.visibleText,
            characterName = characterName,
        )
        val highRiskRuleIds = signals
            .filter { signal -> signal.severity == RoleplayLeakSeverity.HIGH }
            .map(RoleplayOutputSignal::ruleId)
            .distinct()

        return when {
            extraction.hasUnclosedThink || extraction.visibleText.isBlank() ->
                RoleplayOutputDecision.SafeFallback(signals.map(RoleplayOutputSignal::ruleId).distinct())
            highRiskRuleIds.isNotEmpty() -> RoleplayOutputDecision.RetryWithRepair(highRiskRuleIds)
            else -> RoleplayOutputDecision.Accept(extraction.visibleText)
        }
    }
}

/** Visible text extraction shared by the guard; complete thinking blocks are never user-visible. */
object RoleplayVisibleText {
    data class Extraction(
        val visibleText: String,
        val hasUnclosedThink: Boolean,
    )

    fun extract(rawOutput: String): Extraction {
        val visible = StringBuilder()
        var cursor = 0
        while (cursor < rawOutput.length) {
            val thinkStart = rawOutput.indexOf(THINK_OPEN, startIndex = cursor)
            if (thinkStart < 0) {
                visible.append(rawOutput.substring(cursor))
                break
            }
            visible.append(rawOutput.substring(cursor, thinkStart))
            val afterOpen = thinkStart + THINK_OPEN.length
            val thinkEnd = rawOutput.indexOf(THINK_CLOSE, startIndex = afterOpen)
            if (thinkEnd < 0) {
                return Extraction(visibleText = "", hasUnclosedThink = true)
            }
            cursor = thinkEnd + THINK_CLOSE.length
        }
        return Extraction(visibleText = visible.toString().trim(), hasUnclosedThink = false)
    }

    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"
}

/** One classifier used by both the observer and the later enforcement path. */
internal object RoleplayLeakClassifier {
    fun classify(
        rawOutput: String,
        visibleOutput: String,
        characterName: String,
    ): List<RoleplayOutputSignal> = buildList {
        if (visibleOutput.isBlank()) {
            add(RoleplayOutputSignal(RoleplayLeakRule.EMPTY_VISIBLE_OUTPUT, "visible output is blank"))
        }
        if (rawOutput.contains(THINK_OPEN) && !rawOutput.contains(THINK_CLOSE)) {
            add(RoleplayOutputSignal(RoleplayLeakRule.UNCLOSED_THINK_BLOCK, "<think> has no closing tag"))
        }
        if (assistantIdentityPattern.containsMatchIn(visibleOutput)) {
            add(RoleplayOutputSignal(RoleplayLeakRule.ASSISTANT_IDENTITY, "assistant identity phrase"))
        }
        if (metaPromptPattern.containsMatchIn(visibleOutput)) {
            add(RoleplayOutputSignal(RoleplayLeakRule.META_PROMPT_REFERENCE, "role-card or prompt reference"))
        }
        if (internalFieldLabelPattern.containsMatchIn(visibleOutput)) {
            add(RoleplayOutputSignal(RoleplayLeakRule.INTERNAL_FIELD_LABEL, "internal roleplay field label"))
        }
        if (templateTokenPattern.containsMatchIn(visibleOutput)) {
            add(RoleplayOutputSignal(RoleplayLeakRule.TEMPLATE_TOKEN, "template control token"))
        }
        if (selfNarrationPattern(characterName).containsMatchIn(visibleOutput)) {
            add(RoleplayOutputSignal(RoleplayLeakRule.POSSIBLE_SELF_NARRATION, "character-name narration pattern"))
        }
    }

    private val assistantIdentityPattern = Regex("作为(?:一个)?(?:AI|人工智能|语言模型|通用助手|助手)")
    private val metaPromptPattern = Regex(
        "角色卡|系统提示|提示词|开发者指令|\\bprompt\\b",
        RegexOption.IGNORE_CASE,
    )
    // Deliberately avoids bare “身份/资料/规则” so normal first-person
    // self-introductions are not classified as internal field leakage.
    private val internalFieldLabelPattern = Regex(
        "角色身份\\s*(?:[：:]|是|为)|角色边界|资料依据|本轮回答证据|" +
            "当前可确认(?:的)?随身物品|与自身有关(?:的)?背景|与此刻有关(?:的)?处境|可依据\\s*(?:[：:]|是|为)",
    )
    private val templateTokenPattern = Regex(
        // Android's ICU regex parser requires both closing braces to be escaped.
        // The previous `}}` form compiles on the desktop JDK but throws a
        // PatternSyntaxException on Android when this classifier is first used.
        "\\{\\{[^}]+\\}\\}|<\\s*/?\\s*(?:system|assistant|user)\\s*>|\\[\\s*(?:system|assistant|user)\\s*]",
        RegexOption.IGNORE_CASE,
    )

    private fun selfNarrationPattern(characterName: String): Regex = Regex(
        "${Regex.escape(characterName)}[，、 ]*(?:沉默|转身|抬眼|皱眉|心想|说道|说|答道|开口)",
    )

    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"
}
