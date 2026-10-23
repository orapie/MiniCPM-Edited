package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.ContentFingerprint
import com.example.minicpm_v_demo.harness.context.ContextController
import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeStoreStatus
import com.example.minicpm_v_demo.harness.character.knowledge.V1ClaimKnowledgeStore
import com.example.minicpm_v_demo.harness.character.knowledge.V1ClaimQuery
import com.example.minicpm_v_demo.harness.character.knowledge.V1KnowledgeFact
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
    private val v1ClaimKnowledgeStore: V1ClaimKnowledgeStore? = null,
    private val stateLoader: CharacterStateLoader? = null,
    private val queryAnalyzer: CharacterQueryAnalyzer = CharacterQueryAnalyzer(),
    private val profileStore: CharacterProfileStore = CharacterProfileStore(),
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
        require(runtimeContext.profileTopK >= 0) { "profileTopK must not be negative" }
        require(runtimeContext.profileMaxChars >= 0) { "profileMaxChars must not be negative" }
        require(runtimeContext.stateTopK >= 0) { "stateTopK must not be negative" }
        require(runtimeContext.stateMaxChars >= 0) { "stateMaxChars must not be negative" }
        require(runtimeContext.possessionTopK >= 0) { "possessionTopK must not be negative" }
        require(runtimeContext.possessionMaxChars >= 0) { "possessionMaxChars must not be negative" }
        val npc = loader.loadCharacter(npcId)
        val cutoff = runtimeContext.storyCutoff ?: npc.knowledgeScope.storyCutoff
        require(eventsById.containsKey(cutoff)) { "unknown story cutoff: $cutoff" }
        val followUpTarget = runtimeContext.followUpTarget
        val queryPlan = planKnowledgeQuery(userInput, runtimeContext, followUpTarget)
        val graphRetrieval = if (queryPlan.retrieveFacts && runtimeContext.knowledgeGraphEnabled && v2KnowledgeStore != null) {
            if (followUpTarget?.kind == "authorized_story_facts") {
                v2KnowledgeStore.retrieveByClaimIdSafely(npc.npcId, followUpTarget.id, cutoff)
            } else {
                v2KnowledgeStore.retrieveSafely(
                    V2KnowledgeQuery(
                        characterId = npc.npcId,
                        query = userInput,
                        storyCutoff = cutoff,
                        limit = queryPlan.factLimit,
                    ),
                )
            }
        } else {
            null
        }
        val graphFacts = graphRetrieval?.takeIf { it.status == KnowledgeStoreStatus.READY }
            ?.facts.orEmpty()
        // V2 is the authoritative source for story/epistemic facts.  V1
        // claims are a strict compatibility fallback, never a second fact
        // source beside a V2 hit, which prevents duplicate prompt injection.
        val v1ClaimRetrieval = if (queryPlan.retrieveFacts && graphFacts.isEmpty() &&
            runtimeContext.knowledgeGraphEnabled && v1ClaimKnowledgeStore != null
        ) {
            if (followUpTarget?.kind == "authorized_story_facts") {
                v1ClaimKnowledgeStore.retrieveByClaimIdSafely(npc.npcId, followUpTarget.id, cutoff)
            } else {
                v1ClaimKnowledgeStore.retrieveSafely(
                    V1ClaimQuery(
                        characterId = npc.npcId,
                        query = userInput,
                        storyCutoff = cutoff,
                        limit = queryPlan.factLimit,
                    ),
                )
            }
        } else {
            null
        }
        val v1ClaimFacts = v1ClaimRetrieval?.takeIf { it.status == KnowledgeStoreStatus.READY }
            ?.facts.orEmpty()
        val relationRetrieval = if (queryPlan.retrieveRelations && runtimeContext.knowledgeGraphEnabled && v1KnowledgeStore != null) {
            v1KnowledgeStore.retrieveSafely(
                V1RelationshipQuery(
                    characterId = npc.npcId,
                    query = userInput,
                    storyCutoff = cutoff,
                    targetNames = relationshipTargetNames(),
                    limit = queryPlan.relationLimit,
                    preferredRelationId = followUpTarget?.takeIf { it.kind == "relevant_relationships" }?.id,
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
        val useLegacyStoryFallback = queryPlan.retrieveFacts && graphFacts.isEmpty() && v1ClaimFacts.isEmpty()
        val candidates = if (useLegacyStoryFallback) {
            val directEvent = followUpTarget
                ?.takeIf { it.kind == "authorized_story_facts" }
                ?.id
                ?.let(eventsById::get)
            if (directEvent != null) {
                listOf(RetrievedEvent(directEvent, score = 1.0, matchedTerms = emptyList()))
            } else {
                retriever.retrieve(userInput, events, limit = maxOf(runtimeContext.topK * 2, 12))
            }
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
        val factCandidates = if (graphFacts.isEmpty() && v1ClaimFacts.isEmpty()) {
            authorized.filter { it.event.eventId !in memoryIds }
        } else {
            emptyList()
        }
        // A ready V1 graph is authoritative for speaker-directed attitudes.
        // Character-card relationships stay as a fail-closed compatibility path.
        val relationships = if (!queryPlan.retrieveRelations || relationRetrieval?.status == KnowledgeStoreStatus.READY) {
            emptyList()
        } else {
            selectRelationships(npc, userInput)
        }
        val definitionIntent = "definition_signal" in queryPlan.analysis.explanationCodes
        val primaryDefinitionEventId = if (definitionIntent) {
            authorized.firstOrNull()?.event?.eventId
        } else {
            null
        }
        val dynamicState = runtimeContext.dynamicState ?: npc.dynamicState
        val timedState = stateLoader?.currentState(npcId, cutoff)

        val mandatoryBase = linkedMapOf(
            "global_summary" to compileGlobalSummary(npc, cutoff),
            "core_personality" to compileCorePersonality(npc),
            "speech_examples" to compileSpeechExamples(npc),
            "story_cutoff" to formatCutoff(cutoff),
            "dynamic_state" to compileDynamicState(dynamicState),
            "role_constraints" to compileRoleConstraints(npc),
            "relevant_profile" to "无。",
            "relevant_state" to "无。",
            "relevant_possessions" to if (queryPlan.retrievePossessions) "当前资料未记录可确认的随身物品。" else "无。",
            "relevant_relationships" to "无。",
            "authorized_story_facts" to "无；不得自行补全。",
            "retrieved_memories" to "无。",
            "conversation_summary" to "无。",
        )

        val mandatoryBaseChars = render(mandatoryBase).length
        val basePromptFingerprint = ContentFingerprint.sha256Short(render(mandatoryBase))
        require(mandatoryBaseChars <= runtimeContext.maxChars) {
            "mandatory character prompt exceeds maxChars without summary: " +
                "$mandatoryBaseChars > ${runtimeContext.maxChars}"
        }
        val remainingAfterBaseChars = runtimeContext.maxChars - mandatoryBaseChars
        // Conversation history is helpful for resolving a pronoun, but it must
        // never consume all of the space needed by evidence chosen for the
        // player's current question.  The old allocation did exactly that on
        // multi-turn chats: summary compression filled the whole remainder and
        // every optional profile/state/fact item then failed the final budget
        // check.  Reserve before compressing history so the current turn wins.
        val reservedDynamicEvidenceChars = if (
            queryPlan.retrieveProfile || queryPlan.retrieveState || queryPlan.retrievePossessions ||
                queryPlan.retrieveRelations || queryPlan.retrieveFacts
        ) {
            minOf(DYNAMIC_EVIDENCE_RESERVE_CHARS, remainingAfterBaseChars)
        } else {
            0
        }
        val summaryBudgetChars = remainingAfterBaseChars - reservedDynamicEvidenceChars
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
        var profileChars = 0
        val profileCandidates = if (queryPlan.retrieveProfile) {
            profileCandidates(npc, userInput, queryPlan.profileLimit, queryPlan.analysis, followUpTarget)
        } else {
            emptyList()
        }
        val profileRoute = when {
            !queryPlan.retrieveProfile && queryPlan.analysis.isGreeting -> "skipped_greeting"
            !queryPlan.retrieveProfile -> "not_requested"
            followUpTarget?.kind == "relevant_profile" -> "follow_up"
            profileCandidates.isEmpty() -> "no_candidates"
            queryPlan.analysis.explanationCodes.any { it.endsWith("_signal") || it == "exact_identity" } -> "explicit"
            else -> "contextual"
        }
        profileCandidates.forEach { item ->
            if (profileChars + item.text.length > queryPlan.profileCharBudget) {
                graphPreDropped += DroppedPromptItem("relevant_profile", item.id, "character_profile_budget")
            } else {
                profileChars += item.text.length
                optionalItems += item
            }
        }
        var stateChars = 0
        val stateCandidates = if (queryPlan.retrieveState) {
            stateCandidates(dynamicState, userInput, queryPlan.stateLimit, queryPlan.analysis)
        } else {
            emptyList()
        }
        val stateRoute = when {
            !queryPlan.retrieveState -> "not_requested"
            stateCandidates.isEmpty() -> "no_candidates"
            else -> "explicit"
        }
        stateCandidates.forEach { item ->
            if (stateChars + item.text.length > queryPlan.stateCharBudget) {
                graphPreDropped += DroppedPromptItem("relevant_state", item.id, "character_state_budget")
            } else {
                stateChars += item.text.length
                optionalItems += item
            }
        }
        var possessionChars = 0
        val possessionCandidates = if (queryPlan.retrievePossessions && timedState != null) {
            possessionCandidates(timedState, userInput, queryPlan.possessionLimit, followUpTarget)
        } else {
            emptyList()
        }
        val possessionRoute = when {
            !queryPlan.retrievePossessions -> "not_requested"
            timedState == null -> "no_current_state"
            possessionCandidates.isEmpty() -> "no_carried_items"
            else -> "explicit"
        }
        possessionCandidates.forEach { item ->
            if (possessionChars + item.text.length > queryPlan.possessionCharBudget) {
                graphPreDropped += DroppedPromptItem("relevant_possessions", item.id, "character_possession_budget")
            } else {
                possessionChars += item.text.length
                optionalItems += item
            }
        }
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
        v1ClaimFacts.forEach { item ->
            val text = formatV1ClaimFact(item, npc)
            if (graphChars + text.length > queryPlan.factCharBudget) {
                graphPreDropped += DroppedPromptItem("authorized_story_facts", item.claimId, "knowledge_graph_budget")
            } else {
                graphChars += text.length
                optionalItems += PromptCandidate(
                    section = "authorized_story_facts",
                    id = item.claimId,
                    text = text,
                    priority = item.score + 0.9,
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
            "relevant_profile" to mutableListOf<String>(),
            "relevant_state" to mutableListOf<String>(),
            "relevant_possessions" to mutableListOf<String>(),
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
        val dynamicEvidence = sectionLines.toSortedMap()
            .entries
            .filter { (_, lines) -> lines.isNotEmpty() }
            .joinToString("\n") { (section, lines) -> "$section=${lines.joinToString("\n")}" }
        val dynamicEvidenceFingerprint = dynamicEvidence
            .takeIf { it.isNotBlank() }
            ?.let(ContentFingerprint::sha256Short)
            ?: "none"
        val selectedItemsFingerprint = selected
            .joinToString("\n") { item -> "${item.kind}:${item.id}" }
            .takeIf { it.isNotBlank() }
            ?.let(ContentFingerprint::sha256Short)
            ?: "none"
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
                reservedDynamicEvidenceChars = reservedDynamicEvidenceChars,
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
                v1ClaimGraphStatus = v1ClaimRetrieval?.status?.name?.lowercase()
                    ?: when {
                        graphFacts.isNotEmpty() -> "skipped_v2_authoritative"
                        !runtimeContext.knowledgeGraphEnabled -> "disabled"
                        !queryPlan.retrieveFacts -> "not_requested"
                        else -> "not_configured"
                    },
                v1ClaimGraphFallbackReason = v1ClaimRetrieval?.fallbackReason,
                v1ClaimGraphDecisions = v1ClaimRetrieval?.decisions.orEmpty(),
                v1ClaimGraphInjectedChars = sectionLines.getValue("authorized_story_facts")
                    .filter { line -> v1ClaimFacts.any { formatV1ClaimFact(it, npc) == line } }
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
                profileRoute = profileRoute,
                profileCandidateCount = profileCandidates.size,
                profileCandidateIds = profileCandidates.map { it.id },
                profileSelectedIds = selected.filter { it.kind == "relevant_profile" }.map { it.id },
                profileBudgetDroppedCount = dropped.count {
                    it.kind == "relevant_profile" && it.reason == "character_profile_budget"
                },
                profileInjectedChars = sectionLines.getValue("relevant_profile").sumOf(String::length),
                stateRoute = stateRoute,
                stateCandidateCount = stateCandidates.size,
                stateCandidateIds = stateCandidates.map { it.id },
                stateSelectedIds = selected.filter { it.kind == "relevant_state" }.map { it.id },
                stateBudgetDroppedCount = dropped.count {
                    it.kind == "relevant_state" && it.reason == "character_state_budget"
                },
                stateInjectedChars = sectionLines.getValue("relevant_state").sumOf(String::length),
                possessionRoute = possessionRoute,
                possessionCandidateCount = possessionCandidates.size,
                possessionSelectedCount = selected.count { it.kind == "relevant_possessions" },
                possessionBudgetDroppedCount = dropped.count {
                    it.kind == "relevant_possessions" && it.reason == "character_possession_budget"
                },
                possessionInjectedChars = sectionLines.getValue("relevant_possessions").sumOf(String::length),
                followUpRoute = if (followUpTarget == null) "not_requested" else "accepted",
                knowledgeQueryIntent = queryPlan.intent,
                queryIntents = queryPlan.analysis.intents.map(CharacterQueryIntent::name).sorted(),
                requestedKnowledgeSurfaces = queryPlan.analysis.requestedSurfaces
                    .map(CharacterKnowledgeSurface::name)
                    .sorted(),
                queryExplanationCodes = queryPlan.analysis.explanationCodes.sorted(),
                legacyStoryRetrievalExecuted = useLegacyStoryFallback,
                legacyMemoryRetrievalExecuted = useLegacyStoryFallback,
                basePromptFingerprint = basePromptFingerprint,
                dynamicEvidenceFingerprint = dynamicEvidenceFingerprint,
                selectedItemsFingerprint = selectedItemsFingerprint,
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
        // Reserve enough of the fixed 900-character Android budget for
        // per-turn evidence after adding stable role-boundary and state context.
        return npc.identity.speechExamples.take(2)
            .joinToString("\n") { "“${it.take(54)}”" }
            .ifBlank { "“此事我知道多少，便只说多少。”" }
    }

    private fun compileDynamicState(state: DynamicState): String {
        val emotion = state.emotion
        return "你此刻${emotion.label}。"
    }

    /**
     * The template already contains the full shared no-invention, no-leak,
     * no-meta and no-future contract.  Keep this card-specific reinforcement
     * short enough that it cannot evict the current turn's evidence under the
     * Android 900-character budget.
     */
    private fun compileRoleConstraints(npc: CharacterCard): String =
        "保持${npc.identity.name}的身份与当前知识边界，不泄露内部资料。"

    /**
     * Character-card material is trusted self-knowledge.  It stays separate
     * from V2 profile claims so this broader answer surface cannot weaken
     * story cutoff or character-to-character epistemic filtering.
     */
    private fun profileCandidates(
        npc: CharacterCard,
        userInput: String,
        limit: Int,
        analysis: CharacterQueryAnalysis,
        followUpTarget: CharacterFollowUpTarget?,
    ): List<PromptCandidate> = profileStore.retrieve(
        card = npc,
        userInput = userInput,
        analysis = analysis,
        limit = limit,
        preferredRecordId = followUpTarget?.takeIf { it.kind == "relevant_profile" }?.id,
    ).matches
        .map { match ->
            PromptCandidate(
                section = "relevant_profile",
                id = match.record.id,
                text = "${match.record.label}：${match.record.statement.take(110)}",
                priority = match.score,
            )
        }

    /**
     * DynamicState is trusted, current character-card context.  It deliberately
     * selects one answer-shaped field instead of always attaching scene, task
     * and goal, so the system prompt changes with the user's present question.
     */
    private fun stateCandidates(
        state: DynamicState,
        userInput: String,
        limit: Int,
        analysis: CharacterQueryAnalysis,
    ): List<PromptCandidate> {
        val candidates = listOf(
            "state_scene" to stateLine("你当前处境", state.scene),
            "state_quest" to stateLine("你当前任务状态", state.questState),
            "state_goal" to stateLine("你当前目标", state.currentGoal),
        ).filter { (_, text) -> text.isNotBlank() }
        return candidates.map { (id, text) ->
            PromptCandidate(
                section = "relevant_state",
                id = id,
                text = text,
                priority = statePriority(id, userInput, text, analysis),
            )
        }.sortedWith(compareByDescending<PromptCandidate> { it.priority }.thenBy { it.id })
            .take(limit)
    }

    private fun stateLine(label: String, value: String): String =
        value.trim().take(110)
            .takeIf { it.isNotBlank() }
            ?.let { "$label：$it" }
            .orEmpty()

    private fun statePriority(
        id: String,
        userInput: String,
        text: String,
        analysis: CharacterQueryAnalysis,
    ): Double {
        val categorySignals = when (id) {
            "state_scene" -> listOf("此刻", "现在", "在哪里", "何处", "位置", "身在", "场景")
            "state_quest" -> listOf("正在", "做什么", "在做", "任务", "进展", "状态")
            "state_goal" -> listOf("接下来", "打算", "下一步", "目标", "计划", "准备", "想做", "要做")
            else -> emptyList()
        }
        val signalBoost = categorySignals.count { it in userInput } * 3.0
        val intentBoost = when (id) {
            "state_scene" -> if (analysis.hasIntent(CharacterQueryIntent.STATE_SCENE)) 3.0 else 0.0
            "state_quest" -> if (analysis.hasIntent(CharacterQueryIntent.STATE_QUEST)) 3.0 else 0.0
            "state_goal" -> if (analysis.hasIntent(CharacterQueryIntent.STATE_GOAL)) 3.0 else 0.0
            else -> 0.0
        }
        return 0.5 + signalBoost + intentBoost + ContextController.queryBoost(userInput, text)
    }

    /** Only carried entries from the active, time-bounded state are eligible. */
    private fun possessionCandidates(
        state: TimedCharacterState,
        userInput: String,
        limit: Int,
        followUpTarget: CharacterFollowUpTarget?,
    ): List<PromptCandidate> = state.possessions
        .filter { it.status == PossessionStatus.CARRIED }
        .filter { possession ->
            followUpTarget?.takeIf { it.kind == "relevant_possessions" }?.id
                ?.let { it == possession.itemId }
                ?: true
        }
        .map { possession ->
            PromptCandidate(
                section = "relevant_possessions",
                id = possession.itemId,
                text = "当前可确认随身物品：${possession.name}；${possession.description.take(86)}",
                priority = 3.5 + ContextController.queryBoost(userInput, possession.name + " " + possession.description + " " + possession.keywords.joinToString(" ")),
            )
        }
        .sortedWith(compareByDescending<PromptCandidate> { it.priority }.thenBy { it.id })
        .take(limit)

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

    private fun formatV1ClaimFact(item: V1KnowledgeFact, npc: CharacterCard): String {
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

    private fun planKnowledgeQuery(
        userInput: String,
        runtimeContext: RuntimeContext,
        followUpTarget: CharacterFollowUpTarget?,
    ): KnowledgeQueryPlan {
        val analysis = queryAnalyzer.analyze(
            userInput = userInput,
            knownCharacterNames = relationshipTargetNames().values.flatten(),
            followUpTarget = followUpTarget,
        )
        val retrieveState = analysis.requests(CharacterKnowledgeSurface.STATE)
        val retrievePossessions = analysis.requests(CharacterKnowledgeSurface.POSSESSION)
        val retrieveProfile = !analysis.isGreeting && analysis.requests(CharacterKnowledgeSurface.PROFILE)
        val retrieveRelations = analysis.requests(CharacterKnowledgeSurface.RELATIONSHIP)
        val retrieveFacts = analysis.requests(CharacterKnowledgeSurface.STORY_FACT)
        val explicitRelationship = retrieveRelations && analysis.hasNamedTarget
        return KnowledgeQueryPlan(
            intent = analysis.stableIntentLabel(),
            analysis = analysis,
            retrieveFacts = retrieveFacts,
            retrieveRelations = retrieveRelations,
            retrieveProfile = retrieveProfile,
            retrieveState = retrieveState,
            retrievePossessions = retrievePossessions,
            factLimit = minOf(runtimeContext.graphFactTopK, 2),
            factCharBudget = minOf(runtimeContext.knowledgeGraphMaxChars, 180),
            profileLimit = minOf(runtimeContext.profileTopK, 2),
            profileCharBudget = minOf(runtimeContext.profileMaxChars, 180),
            stateLimit = minOf(runtimeContext.stateTopK, 1),
            stateCharBudget = minOf(runtimeContext.stateMaxChars, 96),
            possessionLimit = minOf(runtimeContext.possessionTopK, 2),
            possessionCharBudget = minOf(runtimeContext.possessionMaxChars, 160),
            relationLimit = minOf(runtimeContext.graphRelationTopK, if (explicitRelationship) 1 else 2),
            relationCharBudget = minOf(runtimeContext.knowledgeGraphRelationMaxChars, if (explicitRelationship) 96 else 160),
        )
    }

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
        val analysis: CharacterQueryAnalysis,
        val retrieveFacts: Boolean,
        val retrieveRelations: Boolean,
        val retrieveProfile: Boolean,
        val retrieveState: Boolean,
        val retrievePossessions: Boolean,
        val factLimit: Int,
        val factCharBudget: Int,
        val profileLimit: Int,
        val profileCharBudget: Int,
        val stateLimit: Int,
        val stateCharBudget: Int,
        val possessionLimit: Int,
        val possessionCharBudget: Int,
        val relationLimit: Int,
        val relationCharBudget: Int,
    )

    companion object {
        /** Minimum protected slice for prompt evidence when the Android cap is 900 characters. */
        private const val DYNAMIC_EVIDENCE_RESERVE_CHARS = 220
        private val KnowledgeLeadIns = mapOf(
            "direct" to "你亲历并确认：",
            "witnessed" to "你亲眼见到：",
            "reported" to "你听人提及：",
            "inferred" to "你据现有线索推断：",
        )
        private fun List<String>.takeJoined(limit: Int, itemChars: Int): String =
            take(limit)
                .joinToString("；") { it.take(itemChars) }
                .ifBlank { "无" }

        fun fromPaths(paths: HarnessDataPaths): CharacterPromptCompiler {
            val loader = CharacterJsonLoader(
                charactersDir = paths.charactersDir,
                storyEventsFile = paths.storyEventsFile,
                promptTemplateFile = java.io.File(paths.promptsDir, "roleplay_system.prompt"),
            )
            return CharacterPromptCompiler(
                loader = loader,
                v2KnowledgeStore = V2EpistemicKnowledgeStore(
                    fallbackFile = paths.v2CharacterKnowledgeFallbackFile,
                    sourceDatabaseFile = paths.v2CharacterKnowledgeDatabaseFile,
                ),
                v1KnowledgeStore = V1GraphKnowledgeStore(
                    manifestFile = paths.v1CharacterKnowledgeManifestFile,
                    relationsFile = java.io.File(paths.v1CharacterKnowledgeDir, "relations.jsonl"),
                ),
                v1ClaimKnowledgeStore = V1ClaimKnowledgeStore(
                    manifestFile = paths.v1CharacterKnowledgeManifestFile,
                    claimsFile = java.io.File(paths.v1CharacterKnowledgeDir, "claims.jsonl"),
                ),
                stateLoader = CharacterStateLoader(
                    stateDir = paths.characterStateDir,
                    events = loader.loadEvents(),
                    knownCharacterIds = loader.listCharacters().map(CharacterCard::npcId).toSet(),
                ),
            )
        }
    }
}
