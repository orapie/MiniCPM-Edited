package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.context.ContextController
import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeStoreStatus
import com.example.minicpm_v_demo.harness.character.knowledge.V1GraphKnowledgeStore
import com.example.minicpm_v_demo.harness.character.knowledge.V1KnowledgeRelation
import com.example.minicpm_v_demo.harness.character.knowledge.V1RelationshipQuery
import com.example.minicpm_v_demo.harness.character.knowledge.V2EpistemicKnowledgeStore
import com.example.minicpm_v_demo.harness.character.knowledge.V2KnowledgeFact
import com.example.minicpm_v_demo.harness.character.knowledge.V2KnowledgeQuery
import com.example.minicpm_v_demo.harness.data.HarnessDataPaths

class CharacterPromptCompiler(
    private val loader: CharacterJsonLoader,
    private val retriever: KeywordStoryRetriever = KeywordStoryRetriever(),
    private val boundaryFilter: KnowledgeBoundaryFilter = KnowledgeBoundaryFilter(),
    private val memoryRetriever: MemoryRetriever = MemoryRetriever(),
    private val v2KnowledgeStore: V2EpistemicKnowledgeStore? = null,
    private val v1KnowledgeStore: V1GraphKnowledgeStore? = null,
) {
    private val events: List<StoryEvent> by lazy { loader.loadEvents() }
    private val eventsById: Map<String, StoryEvent> by lazy { events.associateBy { it.eventId } }
    private val template: String by lazy { loader.loadPromptTemplate() }

    fun availableCharacters(): List<CharacterCard> = loader.listCharacters()

    fun buildNpcPrompt(
        npcId: String,
        userInput: String,
        runtimeContext: RuntimeContext = RuntimeContext(),
    ): CompiledCharacterPrompt {
        require(userInput.isNotBlank()) { "userInput must not be empty" }
        require(runtimeContext.maxChars >= 400) { "maxChars must be at least 400" }
        require(runtimeContext.graphFactTopK >= 0) { "graphFactTopK must not be negative" }
        require(runtimeContext.graphRelationTopK >= 0) { "graphRelationTopK must not be negative" }
        require(runtimeContext.knowledgeGraphMaxChars >= 0) { "knowledgeGraphMaxChars must not be negative" }
        require(runtimeContext.knowledgeGraphRelationMaxChars >= 0) { "knowledgeGraphRelationMaxChars must not be negative" }
        val npc = loader.loadCharacter(npcId)
        val cutoff = runtimeContext.storyCutoff ?: npc.knowledgeScope.storyCutoff
        require(eventsById.containsKey(cutoff)) { "unknown story cutoff: $cutoff" }
        val queryPlan = planKnowledgeQuery(userInput, runtimeContext)
        val graphRetrieval = if (queryPlan.retrieveFacts && runtimeContext.knowledgeGraphEnabled && v2KnowledgeStore != null) {
            v2KnowledgeStore.retrieveSafely(
                V2KnowledgeQuery(
                    characterId = npc.npcId,
                    query = userInput,
                    storyCutoff = cutoff,
                    limit = queryPlan.factLimit,
                ),
            )
        } else {
            null
        }
        val graphFacts = graphRetrieval?.takeIf { it.status == KnowledgeStoreStatus.READY }
            ?.facts.orEmpty()
        val relationRetrieval = if (queryPlan.retrieveRelations && runtimeContext.knowledgeGraphEnabled && v1KnowledgeStore != null) {
            v1KnowledgeStore.retrieveSafely(
                V1RelationshipQuery(
                    characterId = npc.npcId,
                    query = userInput,
                    storyCutoff = cutoff,
                    targetNames = relationshipTargetNames(),
                    limit = queryPlan.relationLimit,
                ),
            )
        } else {
            null
        }
        val graphRelationships = relationRetrieval?.takeIf { it.status == KnowledgeStoreStatus.READY }
            ?.relations.orEmpty()
        // Old events are a fallback only.  Do not pay their retrieval and memory
        // cost when V2 already supplied authorized story facts or when a query is
        // intentionally handled by identity/relationship context alone.
        val useLegacyStoryFallback = queryPlan.retrieveFacts && graphFacts.isEmpty()
        val candidates = if (useLegacyStoryFallback) {
            retriever.retrieve(userInput, events, limit = maxOf(runtimeContext.topK * 2, 12))
        } else {
            emptyList()
        }
        val (authorizedAll, decisions) = if (useLegacyStoryFallback) {
            boundaryFilter.filter(candidates, npc, cutoff, events, userInput)
        } else {
            emptyList<RetrievedEvent>() to emptyList()
        }
        val authorized = authorizedAll.take(runtimeContext.topK)
        val memories = if (useLegacyStoryFallback) {
            memoryRetriever.retrieve(npc, authorized, events, cutoff, limit = 3)
        } else {
            emptyList()
        }
        val memoryIds = memories.map { it.event.eventId }.toSet()
        // V2 is the authoritative fact source when it finds permitted evidence.
        // The old story-events route remains a strict fallback only.
        val factCandidates = if (graphFacts.isEmpty()) authorized.filter { it.event.eventId !in memoryIds } else emptyList()
        // A ready V1 graph is authoritative for speaker-directed attitudes.
        // Character-card relationships stay as a fail-closed compatibility path.
        val relationships = if (!queryPlan.retrieveRelations || relationRetrieval?.status == KnowledgeStoreStatus.READY) {
            emptyList()
        } else {
            selectRelationships(npc, userInput)
        }
        val definitionIntent = isDefinitionQuery(userInput)
        val primaryDefinitionEventId = if (definitionIntent) {
            authorized.firstOrNull()?.event?.eventId
        } else {
            null
        }
        val dynamicState = runtimeContext.dynamicState ?: npc.dynamicState

        val mandatoryBase = linkedMapOf(
            "global_summary" to compileGlobalSummary(npc, cutoff),
            "core_personality" to compileCorePersonality(npc),
            "speech_examples" to compileSpeechExamples(npc),
            "story_cutoff" to formatCutoff(cutoff),
            "dynamic_state" to compileDynamicState(dynamicState),
            "relevant_relationships" to "无。",
            "authorized_story_facts" to "无；不得自行补全。",
            "retrieved_memories" to "无。",
            "conversation_summary" to "无。",
        )

        val mandatoryBaseChars = render(mandatoryBase).length
        require(mandatoryBaseChars <= runtimeContext.maxChars) {
            "mandatory character prompt exceeds maxChars without summary: " +
                "$mandatoryBaseChars > ${runtimeContext.maxChars}"
        }
        val summaryBudgetChars = runtimeContext.maxChars - mandatoryBaseChars
        val summary = runtimeContext.conversationSummary.trim()
        val selectedSummary = if (summary.isBlank() || summaryBudgetChars <= 0) {
            "无。"
        } else {
            ContextController.compress(summary, userInput, summaryBudgetChars).ifBlank { "无。" }
        }
        val mandatory = mandatoryBase.toMutableMap().apply {
            this["conversation_summary"] = selectedSummary
        }

        var content = render(mandatory)
        require(content.length <= runtimeContext.maxChars) {
            "mandatory character prompt exceeds maxChars: ${content.length} > ${runtimeContext.maxChars}"
        }
        val optionalItems = mutableListOf<PromptCandidate>()
        val graphPreDropped = mutableListOf<DroppedPromptItem>()
        relationships.forEach { relation ->
            optionalItems += PromptCandidate(
                section = "relevant_relationships",
                id = relation.targetName,
                text = "${relation.targetName}是你的${relation.relationshipType}；${relation.attitude.take(58)}",
                priority = if (definitionIntent) 1.65 else 2.0,
            )
        }
        var relationChars = 0
        graphRelationships.forEach { relation ->
            val text = formatGraphRelationship(relation)
            if (relationChars + text.length > queryPlan.relationCharBudget) {
                graphPreDropped += DroppedPromptItem("relevant_relationships", relation.relationId, "knowledge_graph_relation_budget")
            } else {
                relationChars += text.length
                optionalItems += PromptCandidate(
                    section = "relevant_relationships",
                    id = relation.relationId,
                    text = text,
                    priority = relation.score + 1.0,
                )
            }
        }
        var graphChars = 0
        graphFacts.forEach { item ->
            val text = formatGraphFact(item, npc)
            if (graphChars + text.length > queryPlan.factCharBudget) {
                graphPreDropped += DroppedPromptItem("authorized_story_facts", item.claimId, "knowledge_graph_budget")
            } else {
                graphChars += text.length
                optionalItems += PromptCandidate(
                    section = "authorized_story_facts",
                    id = item.claimId,
                    text = text,
                    priority = item.score + 1.0,
                )
            }
        }
        factCandidates.forEach { item ->
            optionalItems += PromptCandidate(
                section = "authorized_story_facts",
                id = item.event.eventId,
                text = formatFact(item, npc),
                priority = item.score + item.event.importance +
                    definitionBoost(item.event.eventId, primaryDefinitionEventId),
            )
        }
        memories.forEach { item ->
            optionalItems += PromptCandidate(
                section = "retrieved_memories",
                id = item.event.eventId,
                text = formatMemory(item, npc),
                priority = item.score + 0.5 +
                    definitionBoost(item.event.eventId, primaryDefinitionEventId),
            )
        }
        val rankedItems = optionalItems.sortedWith(
            compareByDescending<PromptCandidate> {
                it.priority + ContextController.queryBoost(userInput, it.text)
            }.thenBy { it.id },
        )

        val selected = mutableListOf<SelectedPromptItem>()
        val dropped = graphPreDropped
        val sectionLines = mutableMapOf(
            "relevant_relationships" to mutableListOf<String>(),
            "authorized_story_facts" to mutableListOf<String>(),
            "retrieved_memories" to mutableListOf<String>(),
        )
        for (item in rankedItems) {
            val proposedLines = sectionLines.mapValues { it.value.toMutableList() }.toMutableMap()
            proposedLines.getValue(item.section).add(item.text)
            val values = mandatory.toMutableMap()
            proposedLines.forEach { (section, lines) ->
                if (lines.isNotEmpty()) values[section] = lines.joinToString("\n")
            }
            val proposed = render(values)
            if (proposed.length <= runtimeContext.maxChars) {
                sectionLines.clear()
                sectionLines.putAll(proposedLines)
                content = proposed
                selected += SelectedPromptItem(
                    item.section,
                    item.id,
                    round5(item.priority + ContextController.queryBoost(userInput, item.text)),
                )
            } else {
                dropped += DroppedPromptItem(item.section, item.id, "character_budget")
            }
        }

        val mandatorySize = render(mandatory).length
        return CompiledCharacterPrompt(
            messages = listOf(
                HarnessChatMessage("system", content),
                HarnessChatMessage("user", userInput),
            ),
            debug = CharacterPromptDebug(
                npcId = npcId,
                storyCutoff = cutoff,
                maxChars = runtimeContext.maxChars,
                promptChars = content.length,
                mandatoryChars = mandatorySize,
                budgetExceededByMandatory = mandatorySize > runtimeContext.maxChars,
                selectedItems = selected,
                droppedItems = dropped,
                retrievalDecisions = decisions,
                estimatedPromptTokens = ContextController.estimateTokens(content),
                estimatedMandatoryTokens = ContextController.estimateTokens(render(mandatory)),
                conversationSummaryChars = selectedSummary.length,
                estimatedUserTokens = ContextController.estimateTokens(userInput),
                knowledgeGraphStatus = graphRetrieval?.status?.name?.lowercase()
                    ?: when {
                        !runtimeContext.knowledgeGraphEnabled -> "disabled"
                        !queryPlan.retrieveFacts -> "not_requested"
                        else -> "not_configured"
                    },
                knowledgeGraphFallbackReason = graphRetrieval?.fallbackReason,
                knowledgeGraphDecisions = graphRetrieval?.decisions.orEmpty(),
                knowledgeGraphInjectedChars = sectionLines.getValue("authorized_story_facts")
                    .filter { line -> graphFacts.any { formatGraphFact(it, npc) == line } }
                    .sumOf(String::length),
                v1RelationshipGraphStatus = relationRetrieval?.status?.name?.lowercase()
                    ?: when {
                        !runtimeContext.knowledgeGraphEnabled -> "disabled"
                        !queryPlan.retrieveRelations -> "not_requested"
                        else -> "not_configured"
                    },
                v1RelationshipGraphFallbackReason = relationRetrieval?.fallbackReason,
                v1RelationshipGraphDecisions = relationRetrieval?.decisions.orEmpty(),
                v1RelationshipGraphInjectedChars = sectionLines.getValue("relevant_relationships")
                    .filter { line -> graphRelationships.any { formatGraphRelationship(it) == line } }
                    .sumOf(String::length),
                knowledgeQueryIntent = queryPlan.intent,
                legacyStoryRetrievalExecuted = useLegacyStoryFallback,
                legacyMemoryRetrievalExecuted = useLegacyStoryFallback,
            ),
        )
    }

    private fun render(values: Map<String, String>): String {
        var rendered = template
        for ((key, value) in values) {
            rendered = rendered.replace("{{$key}}", value)
        }
        require(!rendered.contains("{{") && !rendered.contains("}}")) {
            "shared prompt contains an unresolved placeholder"
        }
        return rendered.trim()
    }

    private fun compileGlobalSummary(npc: CharacterCard, cutoff: String): String {
        val identity = npc.identity
        val alias = identity.aliases.firstOrNull()?.let { "，亲近的人也会叫你$it" }.orEmpty()
        return "${identity.name}$alias。${identity.worldviewPosition.take(58)}"
    }

    private fun compileCorePersonality(npc: CharacterCard): String {
        val identity = npc.identity
        return identity.speechStyle.take(2).joinToString("；") { it.take(52) }
            .ifBlank { "自然、简洁，像真实的人在交谈。" }
    }

    private fun compileSpeechExamples(npc: CharacterCard): String {
        return npc.identity.speechExamples.take(4)
            .joinToString("\n") { "“${it.take(54)}”" }
            .ifBlank { "“此事我知道多少，便只说多少。”" }
    }

    private fun compileDynamicState(state: DynamicState): String {
        val emotion = state.emotion
        return "${state.scene.take(46)}；你此刻${emotion.label}。"
    }

    private fun formatCutoff(cutoff: String): String {
        val event = eventsById.getValue(cutoff)
        return "$cutoff（叙事序号 ${event.timeIndex}）"
    }

    private fun formatFact(item: RetrievedEvent, npc: CharacterCard): String {
        val event = item.event
        val knowledge = event.knowledgeByCharacter.getValue(npc.npcId)
        val perspectiveFact = event.fact.replace(npc.identity.name, "你")
        return "${KnowledgeLeadIns.getValue(knowledge.source)}$perspectiveFact"
    }

    private fun formatMemory(item: RetrievedMemory, npc: CharacterCard): String {
        val perspectiveFact = item.event.fact.replace(npc.identity.name, "你")
        return "${KnowledgeLeadIns.getValue(item.memory.source)}$perspectiveFact"
    }

    private fun formatGraphFact(item: V2KnowledgeFact, npc: CharacterCard): String {
        val perspectiveFact = item.statement.replace(npc.identity.name, "你")
        val leadIn = KnowledgeLeadIns[item.acquisitionMethod] ?: "你所知的是："
        return if (item.confidence < 0.9 && item.acquisitionMethod !in setOf("reported", "inferred")) {
            "你大致确认：$perspectiveFact"
        } else {
            "$leadIn$perspectiveFact"
        }
    }

    private fun formatGraphRelationship(item: V1KnowledgeRelation): String =
        "${item.targetName}是你的${item.relationshipType}；${item.attitude.take(58)}"

    private fun relationshipTargetNames(): Map<String, List<String>> = availableCharacters().associate { character ->
        character.npcId to (listOf(character.identity.name) + character.identity.aliases).distinct()
    }

    private fun planKnowledgeQuery(userInput: String, runtimeContext: RuntimeContext): KnowledgeQueryPlan {
        val normalized = normalizeQuery(userInput)
        val targets = relationshipTargetNames().values.flatten()
        val hasNamedTarget = targets.any { name -> normalizeQuery(name).isNotBlank() && normalizeQuery(name) in normalized }
        val definitionIntent = isDefinitionQuery(userInput)
        val relationshipIntent = !definitionIntent &&
            (RelationshipSignals.any { signal -> signal in userInput } || (hasNamedTarget && AttitudeSignals.any { signal -> signal in userInput }))
        val casualOrIdentity = CasualOrIdentityQueries.any { it == normalized }
        val storyIntent = definitionIntent || StorySignals.any { signal -> signal in userInput }
        val retrieveRelations = !casualOrIdentity && relationshipIntent
        val retrieveFacts = !casualOrIdentity && (!relationshipIntent || storyIntent)
        val explicitRelationship = retrieveRelations && hasNamedTarget
        return KnowledgeQueryPlan(
            intent = when {
                casualOrIdentity -> "identity_or_chitchat"
                retrieveFacts && retrieveRelations -> "mixed_story_and_relationship"
                retrieveRelations -> "relationship"
                else -> "story"
            },
            retrieveFacts = retrieveFacts,
            retrieveRelations = retrieveRelations,
            factLimit = minOf(runtimeContext.graphFactTopK, 2),
            factCharBudget = minOf(runtimeContext.knowledgeGraphMaxChars, 180),
            relationLimit = minOf(runtimeContext.graphRelationTopK, if (explicitRelationship) 1 else 2),
            relationCharBudget = minOf(runtimeContext.knowledgeGraphRelationMaxChars, if (explicitRelationship) 96 else 160),
        )
    }

    private fun normalizeQuery(value: String): String = value.lowercase()
        .replace(Regex("[\\s，。！？、：；“”‘’（）《》【】,.!?;:'\\\"()\\[\\]]"), "")

    private fun isDefinitionQuery(query: String): Boolean = DefinitionSignals.any { it in query }

    private fun definitionBoost(eventId: String, primaryEventId: String?): Double =
        if (eventId == primaryEventId) 1.5 else 0.0

    private fun selectRelationships(npc: CharacterCard, query: String): List<CharacterRelationship> {
        val selected = npc.relationships.filter { it.targetName in query }
        if (selected.isNotEmpty()) return selected
        return if (listOf("关系", "怎么看", "他", "祂", "他们").any { it in query }) {
            npc.relationships.take(2)
        } else {
            emptyList()
        }
    }

    private fun round5(value: Double): Double = kotlin.math.round(value * 100000.0) / 100000.0

    private data class PromptCandidate(
        val section: String,
        val id: String,
        val text: String,
        val priority: Double,
    )

    private data class KnowledgeQueryPlan(
        val intent: String,
        val retrieveFacts: Boolean,
        val retrieveRelations: Boolean,
        val factLimit: Int,
        val factCharBudget: Int,
        val relationLimit: Int,
        val relationCharBudget: Int,
    )

    companion object {
        private val KnowledgeLeadIns = mapOf(
            "direct" to "你亲历并确认：",
            "witnessed" to "你亲眼见到：",
            "reported" to "你听人提及：",
            "inferred" to "你据现有线索推断：",
        )
        private val DefinitionSignals = listOf(
            "是什么", "究竟是什么", "到底是什么", "是谁", "何人", "何物",
            "什么关系", "有何关系", "有什么关系", "指什么",
        )
        private val CasualOrIdentityQueries = setOf(
            "你好", "您好", "嗨", "哈喽", "在吗", "谢谢", "再见", "晚安", "早上好",
            "你是谁", "你都知道什么", "你知道什么", "你能做什么",
        )
        private val RelationshipSignals = listOf("关系", "怎么看", "如何看", "态度")
        private val AttitudeSignals = listOf("看法", "想法", "评价", "信任", "讨厌", "喜欢", "恨", "怕")
        private val StorySignals = listOf("发生", "做过", "为何", "为什么", "原因", "真相", "计划", "事件", "经历", "过去", "后来", "何时", "哪里", "最后")

        private fun List<String>.takeJoined(limit: Int, itemChars: Int): String =
            take(limit)
                .joinToString("；") { it.take(itemChars) }
                .ifBlank { "无" }

        fun fromPaths(paths: HarnessDataPaths): CharacterPromptCompiler {
            return CharacterPromptCompiler(
                CharacterJsonLoader(
                    charactersDir = paths.charactersDir,
                    storyEventsFile = paths.storyEventsFile,
                    promptTemplateFile = java.io.File(paths.promptsDir, "roleplay_system.prompt"),
                ),
                v2KnowledgeStore = V2EpistemicKnowledgeStore(
                    fallbackFile = paths.v2CharacterKnowledgeFallbackFile,
                    sourceDatabaseFile = paths.v2CharacterKnowledgeDatabaseFile,
                ),
                v1KnowledgeStore = V1GraphKnowledgeStore(
                    manifestFile = paths.v1CharacterKnowledgeManifestFile,
                    relationsFile = java.io.File(paths.v1CharacterKnowledgeDir, "relations.jsonl"),
                ),
            )
        }
    }
}
