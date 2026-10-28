package com.example.minicpm_v_demo.harness.character

/**
 * Deterministic, content-free analysis of the answer surface requested by a
 * player.  It routes a question; it never grants knowledge or bypasses the
 * character/time/verification checks in the knowledge stores.
 */
class CharacterQueryAnalyzer {
    fun analyze(
        userInput: String,
        knownCharacterNames: Collection<String>,
        followUpTarget: CharacterFollowUpTarget? = null,
    ): CharacterQueryAnalysis {
        require(userInput.isNotBlank()) { "userInput must not be empty" }
        val normalized = normalize(userInput)
        val explanationCodes = linkedSetOf<String>()
        val intents = linkedSetOf<CharacterQueryIntent>()
        val surfaces = linkedSetOf<CharacterKnowledgeSurface>()
        val isGreeting = normalized in greetings
        val isIdentity = normalized in identityQueries
        val hasNamedTarget = knownCharacterNames.any { name ->
            val normalizedName = normalize(name)
            normalizedName.isNotBlank() && normalizedName in normalized
        }

        if (isGreeting) {
            intents += CharacterQueryIntent.CHITCHAT
            explanationCodes += "exact_greeting"
        }
        if (isIdentity) {
            intents += CharacterQueryIntent.IDENTITY
            surfaces += CharacterKnowledgeSurface.PROFILE
            explanationCodes += "exact_identity"
        }
        if (originSignals.any { it in userInput }) {
            intents += CharacterQueryIntent.ORIGIN
            surfaces += CharacterKnowledgeSurface.PROFILE
            explanationCodes += "origin_signal"
        }

        val currentGoalQuestion = currentGoalSignals.any { it in userInput } ||
            ("现在" in userInput && currentMovementSignals.any { it in userInput })
        if (currentGoalQuestion) {
            intents += CharacterQueryIntent.STATE_GOAL
            surfaces += CharacterKnowledgeSurface.STATE
            explanationCodes += "current_goal_signal"
        }
        if (stateSceneSignals.any { it in userInput }) {
            intents += CharacterQueryIntent.STATE_SCENE
            surfaces += CharacterKnowledgeSurface.STATE
            explanationCodes += "state_scene_signal"
        }
        if (stateQuestSignals.any { it in userInput }) {
            intents += CharacterQueryIntent.STATE_QUEST
            surfaces += CharacterKnowledgeSurface.STATE
            explanationCodes += "state_quest_signal"
        }
        if (possessionSignals.any { it in userInput }) {
            intents += CharacterQueryIntent.POSSESSION
            surfaces += CharacterKnowledgeSurface.POSSESSION
            explanationCodes += "possession_signal"
        }

        // “你为什么”既可能是在问角色长期动机，也可能是在追问一件已经发生的
        // 具体行为。后者必须走剧情事实检索，不能被 profile 路由吞掉。
        val specificStoryActionQuestion = selfMotivationSignals.any { it in userInput } &&
            specificStoryActionSignals.any { it in userInput }
        val selfMotivationQuestion = selfMotivationSignals.any { it in userInput } &&
            !specificStoryActionQuestion
        if (preferenceSignals.any { it in userInput } && !currentGoalQuestion) {
            intents += CharacterQueryIntent.PREFERENCE
            surfaces += CharacterKnowledgeSurface.PROFILE
            explanationCodes += "preference_signal"
        }
        if (selfMotivationQuestion) {
            intents += CharacterQueryIntent.MOTIVATION
            surfaces += CharacterKnowledgeSurface.PROFILE
            explanationCodes += "self_motivation_signal"
        }
        if (valueSignals.any { it in userInput }) {
            intents += CharacterQueryIntent.VALUES
            surfaces += CharacterKnowledgeSurface.PROFILE
            explanationCodes += "value_signal"
        }
        if (traitSignals.any { it in userInput }) {
            intents += CharacterQueryIntent.TRAITS
            surfaces += CharacterKnowledgeSurface.PROFILE
            explanationCodes += "trait_signal"
        }

        val definitionQuestion = definitionSignals.any { it in userInput } && !isIdentity
        val relationshipQuestion = !definitionQuestion &&
            (relationshipSignals.any { it in userInput } || (hasNamedTarget && attitudeSignals.any { it in userInput }))
        if (relationshipQuestion) {
            intents += CharacterQueryIntent.RELATIONSHIP
            surfaces += CharacterKnowledgeSurface.RELATIONSHIP
            explanationCodes += if (hasNamedTarget) "named_relationship_signal" else "relationship_signal"
        }

        val storyQuestion = definitionQuestion ||
            ((storySignals.any { it in userInput } || specificStoryActionQuestion) &&
                !selfMotivationQuestion &&
                CharacterQueryIntent.PREFERENCE !in intents &&
                CharacterQueryIntent.ORIGIN !in intents &&
                CharacterKnowledgeSurface.STATE !in surfaces &&
                CharacterKnowledgeSurface.POSSESSION !in surfaces)
        if (storyQuestion) {
            intents += CharacterQueryIntent.STORY_EVENT
            surfaces += CharacterKnowledgeSurface.STORY_FACT
            explanationCodes += when {
                definitionQuestion -> "definition_signal"
                specificStoryActionQuestion -> "specific_story_action_signal"
                else -> "story_signal"
            }
        }

        when (followUpTarget?.kind) {
            "relevant_profile" -> {
                intents += CharacterQueryIntent.FOLLOW_UP
                surfaces += CharacterKnowledgeSurface.PROFILE
                explanationCodes += "follow_up_profile"
            }
            "relevant_possessions" -> {
                intents += CharacterQueryIntent.FOLLOW_UP
                surfaces += CharacterKnowledgeSurface.POSSESSION
                explanationCodes += "follow_up_possession"
            }
            "relevant_relationships" -> {
                intents += CharacterQueryIntent.FOLLOW_UP
                surfaces += CharacterKnowledgeSurface.RELATIONSHIP
                explanationCodes += "follow_up_relationship"
            }
            "authorized_story_facts" -> {
                intents += CharacterQueryIntent.FOLLOW_UP
                surfaces += CharacterKnowledgeSurface.STORY_FACT
                explanationCodes += "follow_up_story_fact"
            }
        }

        if (intents.isEmpty()) {
            intents += CharacterQueryIntent.UNKNOWN
            explanationCodes += "no_supported_signal"
        }
        return CharacterQueryAnalysis(
            normalizedQuery = normalized,
            intents = intents,
            requestedSurfaces = surfaces,
            hasNamedTarget = hasNamedTarget,
            isGreeting = isGreeting,
            explanationCodes = explanationCodes,
        )
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[\\s，。！？、：；“”‘’（）《》【】,.!?;:'\\\"()\\[\\]]"), "")

    companion object {
        private val greetings = setOf("你好", "您好", "嗨", "哈喽", "在吗", "谢谢", "再见", "晚安", "早上好")
        private val identityQueries = setOf("你是谁", "你都知道什么", "你知道什么", "你能做什么")
        private val originSignals = listOf("来历", "出身", "从哪里", "从何", "来自", "何处而来")
        private val preferenceSignals = listOf("最想", "想去", "更愿意", "宁愿", "偏好", "喜欢什么", "想要什么")
        private val selfMotivationSignals = listOf(
            "你为什么", "你为何", "你的目的", "你有何目的", "你有什么目的", "你的动机",
            "你打算", "你计划", "你想要什么", "你想做什么",
        )
        private val specificStoryActionSignals = listOf(
            "停止", "拼合", "拒绝", "离开", "建立", "毁掉", "杀", "救", "背叛", "隐瞒",
        )
        private val valueSignals = listOf("原则", "立场", "底线", "在乎", "同意", "拒绝")
        private val traitSignals = listOf("性格", "怎样的人", "习惯", "害怕", "讨厌", "喜欢")
        private val stateSceneSignals = listOf("此刻", "在哪里", "何处", "位置", "身在", "场景")
        private val stateQuestSignals = listOf("正在做什么", "在做什么", "任务", "进展", "状态")
        private val currentGoalSignals = listOf("接下来", "下一步", "当前目标", "准备", "要做", "想做")
        private val currentMovementSignals = listOf("想去", "要去", "准备去")
        private val possessionSignals = listOf("手里", "手中", "随身", "携带", "带着", "带了", "拿着", "持有", "持着")
        private val relationshipSignals = listOf("关系", "怎么看", "如何看", "态度")
        private val attitudeSignals = listOf("看法", "想法", "评价", "信任", "讨厌", "喜欢", "恨", "怕")
        private val definitionSignals = listOf(
            "是什么", "究竟是什么", "到底是什么", "是谁", "何人", "何物",
            "什么关系", "有何关系", "有什么关系", "指什么",
        )
        private val storySignals = listOf(
            "发生", "做过", "为何", "为什么", "原因", "真相", "事件", "经历", "过去", "后来", "何时", "哪里", "最后",
            "建立", "成员", "哪些", "如何",
        )
    }
}

data class CharacterQueryAnalysis(
    val normalizedQuery: String,
    val intents: Set<CharacterQueryIntent>,
    val requestedSurfaces: Set<CharacterKnowledgeSurface>,
    val hasNamedTarget: Boolean,
    val isGreeting: Boolean,
    val explanationCodes: Set<String>,
) {
    fun hasIntent(intent: CharacterQueryIntent): Boolean = intent in intents

    fun requests(surface: CharacterKnowledgeSurface): Boolean = surface in requestedSurfaces

    fun stableIntentLabel(): String = when {
        isGreeting -> "chitchat"
        CharacterQueryIntent.IDENTITY in intents -> "identity_or_chitchat"
        CharacterKnowledgeSurface.STORY_FACT in requestedSurfaces &&
            CharacterKnowledgeSurface.RELATIONSHIP in requestedSurfaces -> "mixed_story_and_relationship"
        CharacterKnowledgeSurface.RELATIONSHIP in requestedSurfaces -> "relationship"
        CharacterKnowledgeSurface.STATE in requestedSurfaces &&
            CharacterKnowledgeSurface.PROFILE in requestedSurfaces -> "mixed_state_and_profile"
        CharacterKnowledgeSurface.STATE in requestedSurfaces -> "state"
        CharacterKnowledgeSurface.POSSESSION in requestedSurfaces -> "possession"
        CharacterKnowledgeSurface.PROFILE in requestedSurfaces -> "profile"
        CharacterKnowledgeSurface.STORY_FACT in requestedSurfaces -> "story_fact"
        else -> "unknown"
    }
}

enum class CharacterQueryIntent {
    IDENTITY,
    ORIGIN,
    PREFERENCE,
    MOTIVATION,
    VALUES,
    TRAITS,
    STATE_SCENE,
    STATE_QUEST,
    STATE_GOAL,
    POSSESSION,
    RELATIONSHIP,
    STORY_EVENT,
    FOLLOW_UP,
    CHITCHAT,
    UNKNOWN,
}

enum class CharacterKnowledgeSurface {
    PROFILE,
    STATE,
    POSSESSION,
    RELATIONSHIP,
    STORY_FACT,
}
