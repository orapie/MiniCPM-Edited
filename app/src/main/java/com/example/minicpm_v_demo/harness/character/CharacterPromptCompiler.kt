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
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleHandle
import com.example.minicpm_v_demo.harness.data.database.DatabaseCharacterCardAdapter
import com.example.minicpm_v_demo.harness.rag.RagPromptSnapshot
import com.example.minicpm_v_demo.harness.rag.RagPromptStage
import com.example.minicpm_v_demo.harness.rag.RagPromptStageLog
import java.io.File

/** 按角色与剧情截止点汇总可用档案、状态和剧情事实，再按优先级及字符预算填入角色系统模板。 */
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
    private val databaseBundle: DatabaseBundleHandle? = null,
) {
    private val events: List<StoryEvent> by lazy { loader.loadEvents() }
    private val eventsById: Map<String, StoryEvent> by lazy { events.associateBy { it.eventId } }
    private val template: String by lazy { loader.loadPromptTemplate() }

    fun availableCharacters(): List<CharacterCard> = loader.listCharacters()

    /** 解析本轮检索意图和截止点，再从可用知识源取证并记录每个候选的选择结果。 */
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
        val loadedNpc = loader.loadCharacter(npcId)
        val cutoff = runtimeContext.storyCutoff ?: loadedNpc.knowledgeScope.storyCutoff
        val npc = databaseBundle?.let { DatabaseCharacterCardAdapter(it.validated).atCutoff(loadedNpc, cutoff) } ?: loadedNpc
        require(eventsById.containsKey(cutoff)) { "unknown story cutoff: $cutoff" }
        val followUpTarget = runtimeContext.followUpTarget
        val queryPlan = planKnowledgeQuery(userInput, runtimeContext, followUpTarget,
            if (databaseBundle != null) npc.relationships.map { it.targetName } else emptyList())
        val graphRetrieval = if (queryPlan.retrieveFacts && (runtimeContext.knowledgeGraphEnabled || databaseBundle != null) && v2KnowledgeStore != null) {
            if (followUpTarget?.kind == "authorized_story_facts") {
                v2KnowledgeStore.retrieveByClaimIdSafely(npc.npcId, followUpTarget.id, cutoff)
            } else {
                v2KnowledgeStore.retrieveSafely(
                    V2KnowledgeQuery(
                        characterId = npc.npcId,
                        query = if (databaseBundle != null && CharacterQueryIntent.ORIGIN in queryPlan.analysis.intents &&
                            CharacterQuestionSubject.isSelfOrigin(userInput))
                            "$userInput 经历 背景 职业 专业 过去" else userInput,
                        storyCutoff = cutoff,
                        limit = queryPlan.factLimit,
                        candidateScores = runtimeContext.vectorCandidateScores,
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
        val useLegacyStoryFallback = databaseBundle == null && queryPlan.retrieveFacts && graphFacts.isEmpty() && v1ClaimFacts.isEmpty()
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
        } else if (databaseBundle != null) {
            selectAuthorizedCardRelationships(npc, userInput, followUpTarget)
        } else {
            selectRelationships(npc, userInput)
        }
        val definitionIntent = "definition_signal" in queryPlan.analysis.explanationCodes
        val primaryDefinitionEventId = if (definitionIntent) {
            authorized.firstOrNull()?.event?.eventId
        } else {
            null
        }
        val dynamicState = if (databaseBundle != null) npc.dynamicState else runtimeContext.dynamicState ?: npc.dynamicState
        val timedState = stateLoader?.currentState(npcId, cutoff)

        val publicCandidates = if (isDirectSelfProfileQuestion(npc, userInput)) {
            profileStore.publicMatches(npc, userInput)
        } else emptyList()
        // 先渲染固定的角色身份与行为约束，以此计算可选证据的剩余字符预算。
        val mandatoryBase = linkedMapOf(
            "global_summary" to compileGlobalSummary(npc),
            "character_name" to npc.identity.name,
            "self_answer_instruction" to "回答玩家“你是谁？”时，直接说“我是${npc.identity.name}。”；",
            "public_profile_facts" to "",
            "core_personality" to compileCorePersonality(npc),
            "speech_examples" to compileSpeechExamples(npc),
        )

        val mandatoryBaseChars = render(mandatoryBase).length
        val basePromptFingerprint = ContentFingerprint.sha256Short(render(mandatoryBase))
        require(mandatoryBaseChars <= runtimeContext.maxChars) {
            "mandatory character prompt exceeds maxChars without summary: " +
                "$mandatoryBaseChars > ${runtimeContext.maxChars}"
        }
        // Conversation history is no longer embedded in this shared character
        // template.  It is compressed by ConversationContextCompressor and
        // supplied separately with the user-side turn context.
        val publicFacts = mutableListOf<ProfileRecord>()
        val mandatory = mandatoryBase.toMutableMap()
        for (candidate in publicCandidates) {
            if (publicFacts.size >= queryPlan.profileLimit) break
            val proposed = (publicFacts + candidate).joinToString("\n") { it.statement }
            if (proposed.length > queryPlan.profileCharBudget) continue
            val section = "\n以下是你本轮可公开的本人事实。用“我”直接回答，只说其中与问题有关的内容：\n$proposed"
            val values = mandatory + mapOf("public_profile_facts" to section, "self_answer_instruction" to "")
            if (render(values).length > runtimeContext.maxChars) continue
            mandatory["public_profile_facts"] = section
            mandatory["self_answer_instruction"] = ""
            publicFacts += candidate
        }
        var content = render(mandatory)
        require(content.length <= runtimeContext.maxChars) {
            "mandatory character prompt exceeds maxChars: ${content.length} > ${runtimeContext.maxChars}"
        }
        val optionalItems = mutableListOf<PromptCandidate>()
        val graphPreDropped = mutableListOf<DroppedPromptItem>()
        val completePublicFact = completePublicProfileFact(npc, userInput, publicFacts)
        var profileChars = 0
        val profileCandidates = if (queryPlan.retrieveProfile) {
            profileCandidates(npc, userInput, queryPlan.profileLimit, queryPlan.analysis, followUpTarget)
        } else {
            emptyList()
        }
        val profileCandidateIds = (profileCandidates.map { it.id } + publicCandidates.map { it.id }).distinct()
        val profileRoute = when {
            publicCandidates.isNotEmpty() -> "public_match"
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
        if (databaseBundle == null) {
            relationships.forEach { relation ->
                optionalItems += PromptCandidate(
                    section = "relevant_relationships",
                    id = relation.targetName,
                    text = "${relation.targetName}是你的${relation.relationshipType}；${relation.attitude.take(58)}",
                    priority = if (definitionIntent) 1.65 else 2.0,
                )
            }
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
        // 跨提示词段落按优先级比较可选条目，每次放入候选后核对完整模板的渲染长度。
        val rankedItems = optionalItems.sortedWith(
            compareByDescending<PromptCandidate> {
                it.priority + ContextController.queryBoost(userInput, it.text)
            }.thenBy { it.id },
        )

        val publicFactIds = publicFacts.mapTo(hashSetOf()) { it.id }
        val selected = publicFacts.mapTo(mutableListOf()) { SelectedPromptItem("relevant_profile", it.id, 1.0) }
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
            if (item.section == "relevant_profile" && item.id in publicFactIds) continue
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
        // Legacy selections are ID-only. Public profile text is rendered separately above.
        val dynamicEvidenceFingerprint = "none"
        val selectedItemsFingerprint = selected
            .joinToString("\n") { item -> "${item.kind}:${item.id}" }
            .takeIf { it.isNotBlank() }
            ?.let(ContentFingerprint::sha256Short)
            ?: "none"
        val stageLogs = buildStageLogs(
            mandatory = mandatory,
            userInput = userInput,
            maxChars = runtimeContext.maxChars,
            finalContent = content,
            graphFacts = graphFacts.map { fact ->
                PromptCandidate(
                    section = "authorized_story_facts",
                    id = fact.claimId,
                    text = formatGraphFact(fact, npc),
                    priority = fact.score + 1.0,
                )
            }.withinChars(queryPlan.factCharBudget),
            v1ClaimFacts = v1ClaimFacts.map { fact ->
                PromptCandidate(
                    section = "authorized_story_facts",
                    id = fact.claimId,
                    text = formatV1ClaimFact(fact, npc),
                    priority = fact.score + 0.9,
                )
            }.withinChars(queryPlan.factCharBudget),
            v1Relationships = graphRelationships.map { relation ->
                PromptCandidate(
                    section = "relevant_relationships",
                    id = relation.relationId,
                    text = formatGraphRelationship(relation),
                    priority = relation.score + 1.0,
                )
            }.withinChars(queryPlan.relationCharBudget),
            legacyFacts = factCandidates.map { item ->
                PromptCandidate(
                    section = "authorized_story_facts",
                    id = item.event.eventId,
                    text = formatFact(item, npc),
                    priority = item.score + item.event.importance,
                )
            },
            legacyMemories = memories.map { item ->
                PromptCandidate(
                    section = "retrieved_memories",
                    id = item.event.eventId,
                    text = formatMemory(item, npc),
                    priority = item.score + 0.5,
                )
            },
            v2RetrievalStatus = graphRetrieval?.status?.name?.lowercase() ?: when {
                !queryPlan.retrieveFacts -> "not_requested"
                !runtimeContext.knowledgeGraphEnabled -> "disabled"
                else -> "not_configured"
            },
            v2SkipReason = graphRetrieval?.let { retrieval ->
                if (graphFacts.isEmpty()) "${retrieval.status.name.lowercase()}_no_authorized_facts" else null
            } ?: when {
                !queryPlan.retrieveFacts -> "not_requested"
                !runtimeContext.knowledgeGraphEnabled -> "disabled"
                else -> "not_configured"
            },
            v1ClaimRetrievalStatus = v1ClaimRetrieval?.status?.name?.lowercase() ?: when {
                graphFacts.isNotEmpty() -> "skipped_v2_authoritative"
                !queryPlan.retrieveFacts -> "not_requested"
                !runtimeContext.knowledgeGraphEnabled -> "disabled"
                else -> "not_configured"
            },
            v1ClaimSkipReason = v1ClaimRetrieval?.let { retrieval ->
                if (v1ClaimFacts.isEmpty()) "${retrieval.status.name.lowercase()}_no_authorized_facts" else null
            } ?: when {
                graphFacts.isNotEmpty() -> "skipped_v2_authoritative"
                !queryPlan.retrieveFacts -> "not_requested"
                !runtimeContext.knowledgeGraphEnabled -> "disabled"
                else -> "not_configured"
            },
            v1RelationshipRetrievalStatus = relationRetrieval?.status?.name?.lowercase() ?: when {
                !queryPlan.retrieveRelations -> "not_requested"
                !runtimeContext.knowledgeGraphEnabled -> "disabled"
                else -> "not_configured"
            },
            v1RelationshipSkipReason = relationRetrieval?.let { retrieval ->
                if (graphRelationships.isEmpty()) "${retrieval.status.name.lowercase()}_no_authorized_relations" else null
            } ?: when {
                !queryPlan.retrieveRelations -> "not_requested"
                !runtimeContext.knowledgeGraphEnabled -> "disabled"
                else -> "not_configured"
            },
            legacyRetrievalStatus = if (useLegacyStoryFallback) "executed" else "not_needed",
            legacySkipReason = if (!useLegacyStoryFallback) "not_needed_authorized_graph_facts_available" else null,
        )
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
                reservedDynamicEvidenceChars = 0,
                estimatedUserTokens = ContextController.estimateTokens(userInput),
                knowledgeGraphStatus = graphRetrieval?.status?.name?.lowercase()
                    ?: when {
                        !runtimeContext.knowledgeGraphEnabled -> "disabled"
                        !queryPlan.retrieveFacts -> "not_requested"
                        else -> "not_configured"
                    },
                knowledgeGraphFallbackReason = graphRetrieval?.fallbackReason,
                knowledgeGraphDecisions = graphRetrieval?.decisions.orEmpty(),
                // Phase 1 keeps legacy selection only as content-free routing
                // evidence. None of these legacy values are part of Raw Prompt.
                knowledgeGraphInjectedChars = 0,
                v1ClaimGraphStatus = v1ClaimRetrieval?.status?.name?.lowercase()
                    ?: when {
                        graphFacts.isNotEmpty() -> "skipped_v2_authoritative"
                        !runtimeContext.knowledgeGraphEnabled -> "disabled"
                        !queryPlan.retrieveFacts -> "not_requested"
                        else -> "not_configured"
                    },
                v1ClaimGraphFallbackReason = v1ClaimRetrieval?.fallbackReason,
                v1ClaimGraphDecisions = v1ClaimRetrieval?.decisions.orEmpty(),
                v1ClaimGraphInjectedChars = 0,
                v1RelationshipGraphStatus = relationRetrieval?.status?.name?.lowercase()
                    ?: when {
                        !runtimeContext.knowledgeGraphEnabled -> "disabled"
                        !queryPlan.retrieveRelations -> "not_requested"
                        else -> "not_configured"
                    },
                v1RelationshipGraphFallbackReason = relationRetrieval?.fallbackReason,
                v1RelationshipGraphDecisions = relationRetrieval?.decisions.orEmpty(),
                v1RelationshipGraphInjectedChars = 0,
                profileRoute = profileRoute,
                profileCandidateCount = profileCandidateIds.size,
                profileCandidateIds = profileCandidateIds,
                profileSelectedIds = selected.filter { it.kind == "relevant_profile" }.map { it.id },
                profileBudgetDroppedCount = dropped.count {
                    it.kind == "relevant_profile" && it.reason == "character_profile_budget"
                },
                profileInjectedChars = mandatory.getValue("public_profile_facts").length,
                publicProfileAnswersQuestion = completePublicFact != null,
                stateRoute = stateRoute,
                stateCandidateCount = stateCandidates.size,
                stateCandidateIds = stateCandidates.map { it.id },
                stateSelectedIds = selected.filter { it.kind == "relevant_state" }.map { it.id },
                stateBudgetDroppedCount = dropped.count {
                    it.kind == "relevant_state" && it.reason == "character_state_budget"
                },
                stateInjectedChars = 0,
                possessionRoute = possessionRoute,
                possessionCandidateCount = possessionCandidates.size,
                possessionSelectedCount = selected.count { it.kind == "relevant_possessions" },
                possessionBudgetDroppedCount = dropped.count {
                    it.kind == "relevant_possessions" && it.reason == "character_possession_budget"
                },
                possessionInjectedChars = 0,
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
                databaseCapabilities = if (databaseBundle == null) emptyList() else listOf(
                    "v1_unsupported", "timed_state_unsupported", "v2_relations_not_authorized",
                    "legacy_fallback_disabled", "profile_scoped_to_card_cutoff",
                ),
            ),
            stageLogs = stageLogs,
            publicProfileReply = completePublicFact?.statement?.takeIf { it.startsWith("我") },
            relationshipFacts = if (databaseBundle != null && completePublicFact == null) relationships else emptyList(),
        )
    }

    /**
     * Produces source-local previews without rerunning retrieval or changing the
     * final ranking. A preview includes only its source on top of the fixed
     * character base, while CHARACTER_PROMPT_COMPILED is the exact final system
     * prompt passed to the orchestrator.
     */
    private fun buildStageLogs(
        mandatory: Map<String, String>,
        userInput: String,
        maxChars: Int,
        finalContent: String,
        graphFacts: List<PromptCandidate>,
        v1ClaimFacts: List<PromptCandidate>,
        v1Relationships: List<PromptCandidate>,
        legacyFacts: List<PromptCandidate>,
        legacyMemories: List<PromptCandidate>,
        v2RetrievalStatus: String,
        v2SkipReason: String?,
        v1ClaimRetrievalStatus: String,
        v1ClaimSkipReason: String?,
        v1RelationshipRetrievalStatus: String,
        v1RelationshipSkipReason: String?,
        legacyRetrievalStatus: String,
        legacySkipReason: String?,
    ): List<RagPromptStageLog> {
        fun snapshot(system: String) = RagPromptSnapshot(systemPrompt = system, userPrompt = userInput)
        fun preview(candidates: List<PromptCandidate>): String? {
            if (candidates.isEmpty()) return null
            val linesBySection = mutableMapOf<String, MutableList<String>>()
            var preview = render(mandatory)
            candidates.forEach { candidate ->
                val candidateLines = linesBySection.getOrPut(candidate.section) { mutableListOf() }
                val proposedLines = candidateLines + candidate.text
                val proposedValues = mandatory.toMutableMap().apply {
                    linesBySection.forEach { (section, lines) ->
                        this[section] = if (section == candidate.section) {
                            proposedLines.joinToString("\n")
                        } else {
                            lines.joinToString("\n")
                        }
                    }
                }
                val proposed = render(proposedValues)
                if (proposed.length <= maxChars) {
                    candidateLines += candidate.text
                    preview = proposed
                }
            }
            return preview.takeIf { it != render(mandatory) }
        }
        fun sourceStage(
            stage: RagPromptStage,
            candidates: List<PromptCandidate>,
            skipReason: String?,
        ): RagPromptStageLog {
            val sourcePreview = preview(candidates)
            return if (sourcePreview == null) {
                RagPromptStageLog.skipped(stage, skipReason ?: "all_candidates_evicted_by_character_budget")
            } else {
                RagPromptStageLog.emitted(stage, snapshot(sourcePreview))
            }
        }
        fun retrievalStage(
            stage: RagPromptStage,
            source: String,
            status: String,
            candidates: List<PromptCandidate>,
        ): RagPromptStageLog = RagPromptStageLog.emitted(
            stage,
            RagPromptSnapshot(
                retrievalText = buildString {
                    append("source=").append(source).append('\n')
                    append("status=").append(status).append('\n')
                    append("authorizedCandidateCount=").append(candidates.size)
                    if (candidates.isEmpty()) {
                        append("\n(no authorized candidates)")
                    } else {
                        candidates.forEachIndexed { index, candidate ->
                            append("\n\n[").append(index + 1).append("] id=").append(candidate.id)
                            append(" section=").append(candidate.section).append('\n')
                            append(candidate.text)
                        }
                    }
                },
            ),
        )

        val base = render(mandatory)
        return buildList {
            add(RagPromptStageLog.emitted(RagPromptStage.CHARACTER_BASE, snapshot(base)))
            add(retrievalStage(RagPromptStage.V2_RETRIEVAL_RESULT, "V2 authorized story facts", v2RetrievalStatus, graphFacts))
            add(sourceStage(RagPromptStage.CHARACTER_AFTER_V2, graphFacts, v2SkipReason))
            add(retrievalStage(RagPromptStage.V1_CLAIM_RETRIEVAL_RESULT, "V1 claim fallback", v1ClaimRetrievalStatus, v1ClaimFacts))
            add(sourceStage(RagPromptStage.CHARACTER_AFTER_V1_CLAIM, v1ClaimFacts, v1ClaimSkipReason))
            add(retrievalStage(RagPromptStage.V1_RELATIONSHIP_RETRIEVAL_RESULT, "V1 relationships", v1RelationshipRetrievalStatus, v1Relationships))
            add(sourceStage(RagPromptStage.CHARACTER_AFTER_V1_RELATION, v1Relationships, v1RelationshipSkipReason))
            add(retrievalStage(RagPromptStage.LEGACY_RETRIEVAL_RESULT, "legacy story and memory fallback", legacyRetrievalStatus, legacyFacts + legacyMemories))
            add(sourceStage(RagPromptStage.CHARACTER_AFTER_LEGACY_FALLBACK, legacyFacts + legacyMemories, legacySkipReason))
            add(RagPromptStageLog.emitted(RagPromptStage.CHARACTER_PROMPT_COMPILED, snapshot(finalContent)))
        }
    }

    private fun List<PromptCandidate>.withinChars(maxChars: Int): List<PromptCandidate> {
        var used = 0
        return filter { candidate ->
            val accepted = used + candidate.text.length <= maxChars
            if (accepted) used += candidate.text.length
            accepted
        }
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

    private fun compileGlobalSummary(npc: CharacterCard): String {
        val identity = npc.identity
        val alias = identity.aliases.firstOrNull()?.let { "，亲近的人也会叫你$it" }.orEmpty()
        return "${identity.name}$alias。"
    }

    private fun isDirectSelfProfileQuestion(npc: CharacterCard, userInput: String): Boolean {
        if (npc.publicProfile.isEmpty() || "{{public_profile_facts}}" !in template) return false
        val question = userInput.trim().replace(Regex("^请问[，,\\s]*"), "")
        if (!question.startsWith("你") && !question.startsWith("您")) return false
        if (OtherSubjectQuestion.containsMatchIn(question)) return false
        return availableCharacters().none { card ->
            card.npcId != npc.npcId && (listOf(card.identity.name) + card.identity.aliases)
                .any { it.length >= 2 && it in question }
        }
    }

    private fun completePublicProfileFact(npc: CharacterCard, userInput: String, facts: List<ProfileRecord>): ProfileRecord? {
        if (facts.isEmpty()) return null
        // A substring is enough to select a fact, but only a complete match
        // can replace external context. A follow-up clause needs the normal RAG path.
        val question = userInput.trim().trimEnd('？', '?')
            .replaceFirst(PublicProfileQuestionPrefix, "")
        val core = question.replace(PublicProfileQuestionSuffix, "")
        return facts.singleOrNull { fact ->
            val path = fact.sourcePath ?: return@singleOrNull false
            npc.publicProfile[path].orEmpty().any { cue -> cue == question || cue == core }
        }
    }

    private fun compileCorePersonality(npc: CharacterCard): String {
        return npc.identity.speechStyle.asSequence()
            .map { it.trim().trimEnd('。', '；', '，') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
            .joinToString("；") { it.take(48) }
            .ifBlank { "用简洁、自然的口语回答。" }
    }

    private fun compileSpeechExamples(npc: CharacterCard): String {
        val examples = npc.identity.speechExamples.map(String::trim).filter(String::isNotBlank).take(2)
        if (examples.isEmpty()) return ""
        return "\n语气示例只用于节奏和措辞，不复述，也不作为事实：\n" +
            examples.joinToString("\n") { "“${it.take(54)}”" }
    }

    private fun compileDynamicState(state: DynamicState): String {
        if (databaseBundle != null && !databaseBundle.manifest.supports("timed_state")) {
            return "处境与情绪尚不明确。"
        }
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

    /** 把问题意图和追问目标转成具体检索选项，决定要访问的知识源及各类结果的上限。 */
    private fun planKnowledgeQuery(
        userInput: String,
        runtimeContext: RuntimeContext,
        followUpTarget: CharacterFollowUpTarget?,
        cardTargetNames: List<String>,
    ): KnowledgeQueryPlan {
        val analysis = queryAnalyzer.analyze(
            userInput = userInput,
            knownCharacterNames = relationshipTargetNames().values.flatten() + cardTargetNames,
            followUpTarget = followUpTarget,
        )
        val retrieveState = analysis.requests(CharacterKnowledgeSurface.STATE)
        val retrievePossessions = analysis.requests(CharacterKnowledgeSurface.POSSESSION)
        val thirdPartyOrigin = databaseBundle != null && CharacterQueryIntent.ORIGIN in analysis.intents &&
            !CharacterQuestionSubject.isSelfOrigin(userInput)
        val retrieveProfile = !analysis.isGreeting && !thirdPartyOrigin && analysis.requests(CharacterKnowledgeSurface.PROFILE)
        val retrieveRelations = analysis.requests(CharacterKnowledgeSurface.RELATIONSHIP)
        val retrieveFacts = analysis.requests(CharacterKnowledgeSurface.STORY_FACT) || (databaseBundle != null && !analysis.isGreeting)
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

    /** The scoped card already excludes relationships beyond its declared cutoff. */
    private fun selectAuthorizedCardRelationships(
        npc: CharacterCard,
        query: String,
        followUpTarget: CharacterFollowUpTarget?,
    ): List<CharacterRelationship> {
        val bundle = requireNotNull(databaseBundle).validated
        val sourceLineCounts = bundle.sourceLineCounts
        if (npc.relationships.isEmpty() || sourceLineCounts.isEmpty()) return emptyList()
        val cardSequence = bundle.cutoffs.getValue(npc.knowledgeScope.storyCutoff)
        val targetNames = relationshipTargetNames()
        val explicit = npc.relationships.filter { relation ->
            (relation.targetNpcId?.let(targetNames::get).orEmpty() + relation.targetName)
                .any { it.length >= 2 && it in query }
        }
        val candidates = if (explicit.isNotEmpty()) explicit else {
            if (targetNames.values.flatten().any { it.length >= 2 && it in query }) return emptyList()
            val priorId = followUpTarget?.takeIf { it.kind == "relevant_relationships" }?.id ?: return emptyList()
            listOfNotNull(npc.relationships.singleOrNull { it.targetId == priorId || it.targetName == priorId })
        }
        return candidates.filter { relation ->
            relation.targetName.isNotBlank() && relation.attitude.isNotBlank() &&
                relation.evidenceRefs.isNotEmpty() && relation.evidenceRefs.all { ref ->
                    val match = CardRelationshipEvidenceRef.matchEntire(ref) ?: return@all false
                    val (source, from, to) = match.destructured
                    val first = from.toIntOrNull() ?: return@all false
                    val last = if (to.isEmpty()) first else to.toIntOrNull() ?: return@all false
                    first <= last && last <= (sourceLineCounts[source] ?: 0) && last <= cardSequence
                }
        }
    }

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
        fun fromBundle(bundle: DatabaseBundleHandle, promptTemplate: File): CharacterPromptCompiler {
            fun path(key: String) = bundle.paths.resolve(bundle.manifest.paths.getValue(key))
            require(bundle.manifest.supports("v2")) { "database_v2_unsupported" }
            return CharacterPromptCompiler(
                loader = CharacterJsonLoader(path("characters"), path("story_events"), promptTemplate),
                v2KnowledgeStore = V2EpistemicKnowledgeStore(path("runtime_v2"), path("v2_database"), bundle.validated),
                databaseBundle = bundle,
            )
        }

        private val OtherSubjectQuestion = Regex(
            "^(?:你|您)(?:知道|听说|听过|了解|能告诉我|觉得|认为|和|跟|与|(?:的)?(?:朋友|家人|父亲|母亲|同学|同事|老师|搭档|孩子|亲人|伴侣))|[他她](?:们|的|以前|曾)",
        )
        private val PublicProfileQuestionPrefix = Regex("^(?:请问[，,\\s]*)?(?:你|您)(?:以前|过去|曾经|从前|之前)?的?(?:主要)?")
        private val PublicProfileQuestionSuffix = Regex("(?:是什么|吗|呢)$")
        private val CardRelationshipEvidenceRef = Regex("^(.+):L([1-9][0-9]*)(?:-L?([1-9][0-9]*))?$")

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

    }
}
