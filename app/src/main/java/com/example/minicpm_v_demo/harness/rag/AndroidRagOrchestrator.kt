package com.example.minicpm_v_demo.harness.rag

import android.content.Context
import com.example.minicpm_v_demo.harness.ContentFingerprint
import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.attribution.AttributionAgentRequestCompiler
import com.example.minicpm_v_demo.harness.attribution.AttributionAgentResult
import com.example.minicpm_v_demo.harness.attribution.AttributionRewriteTrace
import com.example.minicpm_v_demo.harness.attribution.FinalPromptMaterializer
import com.example.minicpm_v_demo.harness.attribution.FinalPromptRewriteService
import com.example.minicpm_v_demo.harness.attribution.FinalPromptValidation
import com.example.minicpm_v_demo.harness.attribution.FinalPromptValidator
import com.example.minicpm_v_demo.harness.attribution.FocalCharacterIdentity
import com.example.minicpm_v_demo.harness.attribution.NoopFinalPromptRewriteService
import com.example.minicpm_v_demo.harness.attribution.RawPromptPackage
import com.example.minicpm_v_demo.harness.character.CharacterCard
import com.example.minicpm_v_demo.harness.character.CharacterFollowUpTarget
import com.example.minicpm_v_demo.harness.character.CharacterPromptCompiler
import com.example.minicpm_v_demo.harness.character.HarnessChatMessage
import com.example.minicpm_v_demo.harness.character.RuntimeContext
import com.example.minicpm_v_demo.harness.chat.ChatTemplateRenderer
import com.example.minicpm_v_demo.harness.chat.RenderedChatPrompt
import com.example.minicpm_v_demo.harness.context.CompressedConversationContext
import com.example.minicpm_v_demo.harness.context.ConversationContextCompressor
import com.example.minicpm_v_demo.harness.context.ConversationContextIdentity
import com.example.minicpm_v_demo.harness.data.database.DatabaseRuntime
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleHandle
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleValidator
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleManifest
import com.example.minicpm_v_demo.harness.session.ChatSession
import java.io.File

enum class RagMode {
    OFF,
    RAG,
    CHARACTER_RAG,
}
data class CompiledAndroidPrompt(
    val renderedPrompt: String,
    val rendered: RenderedChatPrompt,
    val sources: List<RagSource>,
    val contextText: String,
    val characterDebug: Any? = null,
    val mode: RagMode,
    val contextDecisions: List<RagContextDecision> = emptyList(),
    val attributionTrace: AttributionRewriteTrace? = null,
    /** Raw-text, in-memory only Debug Logcat snapshots. Never persist these. */
    val stageLogs: List<RagPromptStageLog> = emptyList(),
    /** Content-free evidence-selection record for this compiled turn. */
    val turnTrace: RagTurnTrace,
)

class AndroidRagOrchestrator(
    private val ragSearchService: RagSearchService,
    private val characterCompiler: CharacterPromptCompiler,
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    private val characterRagPromptBuilder: CharacterRagPromptBuilder = CharacterRagPromptBuilder(),
    private val alwaysOnVectorRetrievalService: AlwaysOnVectorRetrievalService =
        AlwaysOnVectorRetrievalService(ragSearchService),
    private val vectorRetrievalFeatureFlags: VectorRetrievalFeatureFlags = VectorRetrievalFeatureFlags(),
    private val databaseBundle: DatabaseBundleHandle? = null,
    private val conversationContextCompressor: ConversationContextCompressor = ConversationContextCompressor(),
    private val finalPromptRewriteService: FinalPromptRewriteService = NoopFinalPromptRewriteService,
    private val attributionRequestCompiler: AttributionAgentRequestCompiler? = null,
) : AutoCloseable {
    override fun close() {
        runCatching { (finalPromptRewriteService as? AutoCloseable)?.close() }
        ragSearchService.close()
    }
    private val characterCutoffs by lazy { characterCompiler.availableCharacters().associate { it.npcId to it.knowledgeScope.storyCutoff } }
    fun availableCharacters(): List<CharacterCard> {
        requireCurrentBundle()
        return characterCompiler.availableCharacters()
    }

    private fun requireCurrentBundle() {
        databaseBundle?.let { bundle ->
            val current = DatabaseBundleManifest.parse(bundle.paths.manifestFile.readText(Charsets.UTF_8))
            require(current.datasetFingerprint == bundle.manifest.datasetFingerprint &&
                current.computeFingerprint() == current.datasetFingerprint) { "database_handle_stale" }
        }
    }

    fun compile(
        userInput: String,
        mode: RagMode = RagMode.OFF,
        characterId: String = databaseBundle?.manifest?.defaultCharacterId ?: "lu_jiangxian",
        storyCutoff: String? = null,
        topK: Int = 1,
        maxCharacterChars: Int = 900,
        conversationSession: ChatSession? = null,
        knowledgeGraphEnabled: Boolean = true,
        followUpTarget: CharacterFollowUpTarget? = null,
        modelFamily: HarnessModelFamily = HarnessModelFamily.MINICPM_TEXT,
    ): CompiledAndroidPrompt {
        requireCurrentBundle()
        if (mode == RagMode.CHARACTER_RAG && databaseBundle != null) {
            require(characterId in databaseBundle.validated.characterIds) { "database_unknown_character" }
            require(storyCutoff == null || storyCutoff in databaseBundle.validated.cutoffs) { "database_unknown_cutoff" }
        }
        if (mode == RagMode.OFF) {
            val vectorRetrieval = if (vectorRetrievalFeatureFlags.retrieveInOffMode) {
                alwaysOnVectorRetrievalService.searchAlways(
                    query = userInput,
                    candidateLimit = topK,
                    minScore = GENERAL_RAG_MIN_SCORE,
                )
            } else {
                AlwaysOnVectorRetrievalResult(VectorRetrievalAttempt.notRequested("off_mode"), emptyList())
            }
            val messages = listOf(
                com.example.minicpm_v_demo.harness.character.HarnessChatMessage("system", "你是一个本地助手。"),
                com.example.minicpm_v_demo.harness.character.HarnessChatMessage("user", userInput),
            )
            val rendered = ChatTemplateRenderer.forModelFamily(modelFamily).render(messages)
            return CompiledAndroidPrompt(
                renderedPrompt = rendered.renderedSinglePrompt,
                rendered = rendered,
                sources = emptyList(),
                contextText = "",
                mode = mode,
                stageLogs = emptyList(),
                turnTrace = RagTurnTrace.create(
                    mode = mode,
                    userInput = userInput,
                    modelFamily = modelFamily,
                    rendered = rendered,
                    contextText = "",
                    vectorRetrieval = vectorRetrieval.attempt,
                    externalInjectionRequested = false,
                    sources = emptyList(),
                    contextDecisions = emptyList(),
                    characterDebug = null,
                    databaseManifest = databaseBundle?.manifest,
                ),
            )
        }
        val renderer = ChatTemplateRenderer.forModelFamily(modelFamily)
        return if (mode == RagMode.RAG) {
            val vectorRetrieval = retrieveForActiveRagTurn(
                userInput = userInput,
                candidateLimit = topK,
                minScore = GENERAL_RAG_MIN_SCORE,
            )
            val results = vectorRetrieval.results
            val prompt = promptBuilder.build(userInput, results)
            val rendered = renderer.render(prompt.messages)
            val stageLogs = finalStageLogs(
                initial = listOf(
                    RagPromptStageLog.emitted(
                        RagPromptStage.RAG_CONTEXT_COMPILED,
                        RagPromptSnapshot(
                            systemPrompt = prompt.messages[0].content,
                            userPrompt = prompt.messages[1].content,
                        ),
                    ),
                ),
                rendered = rendered,
            )
            CompiledAndroidPrompt(
                renderedPrompt = rendered.renderedSinglePrompt,
                rendered = rendered,
                sources = prompt.sources,
                contextText = prompt.contextText,
                contextDecisions = prompt.contextDecisions,
                mode = mode,
                stageLogs = stageLogs,
                turnTrace = RagTurnTrace.create(
                    mode = mode,
                    userInput = userInput,
                    modelFamily = modelFamily,
                    rendered = rendered,
                    contextText = prompt.contextText,
                    vectorRetrieval = vectorRetrieval.attempt,
                    externalInjectionRequested = true,
                    sources = prompt.sources,
                    contextDecisions = prompt.contextDecisions,
                    characterDebug = null,
                    databaseManifest = databaseBundle?.manifest,
                ),
            )
        } else {
            // Reviewed facts remain character knowledge; vector hits are also
            // supplied separately as untrusted reference material for this turn.
            val maxSourceEnd = databaseBundle?.let { bundle ->
                val cutoff = storyCutoff ?: characterCutoffs[characterId] ?: error("database_unknown_cutoff")
                bundle.validated.cutoffs[cutoff] ?: error("database_unknown_cutoff")
            }
            val vectorRetrieval = retrieveForActiveRagTurn(
                userInput = userInput,
                candidateLimit = maxOf(topK * 8, 20),
                minScore = CHARACTER_RAG_MIN_SCORE,
                maxSourceEnd = maxSourceEnd,
            )
            val externalInjectionRequested = vectorRetrieval.attempt.executed
            val characterPrompt = characterCompiler.buildNpcPrompt(
                npcId = characterId,
                userInput = userInput,
                runtimeContext = RuntimeContext(
                    storyCutoff = storyCutoff,
                    maxChars = maxCharacterChars,
                    knowledgeGraphEnabled = knowledgeGraphEnabled,
                    followUpTarget = followUpTarget,
                    vectorCandidateScores = vectorRetrieval.results
                        .filter { it.chunk.metadata["record_kind"] == "reviewed_fact" }
                        .associate { it.chunk.id to it.score },
                ),
            )
            val references = CharacterReferenceFilter.filter(vectorRetrieval.results, databaseBundle?.validated,
                characterId, characterPrompt.debug.storyCutoff)
            val conversationContext = databaseBundle?.let { bundle ->
                conversationContextCompressor.compress(
                    session = conversationSession,
                    currentQuestion = userInput,
                    maxChars = CONVERSATION_CONTEXT_MAX_CHARS,
                    identity = ConversationContextIdentity(
                        datasetId = bundle.manifest.datasetId,
                        datasetFingerprint = bundle.manifest.datasetFingerprint,
                        characterId = characterId,
                        storyCutoff = characterPrompt.debug.storyCutoff,
                    ),
                )
            } ?: CompressedConversationContext.EMPTY
            val prompt = characterRagPromptBuilder.build(
                characterMessages = characterPrompt.messages,
                userInput = userInput,
                results = references.allowed,
                characterDebug = characterPrompt.debug,
                conversationContext = conversationContext,
            )
            val focal = requireNotNull(availableCharacters().firstOrNull { it.npcId == characterId })
            val rawPrompt = RawPromptPackage(
                focalCharacter = FocalCharacterIdentity(focal.npcId, focal.identity.name, focal.identity.aliases),
                systemPrompt = prompt.messages[0].content,
                userQuestion = userInput,
                conversationContext = conversationContext,
                references = prompt.references,
            )
            val attribution = rewriteFinalPrompt(rawPrompt, prompt.messages)
            val finalMessages = attribution.messages
            val referenceDecisions = references.rejected + prompt.contextDecisions
            val rendered = renderer.render(finalMessages)
            val externalStage = if (prompt.contextText.isNotBlank()) {
                RagPromptStageLog.emitted(RagPromptStage.EXTERNAL_CONTEXT_COMPILED,
                    RagPromptSnapshot(systemPrompt = finalMessages[0].content, userPrompt = finalMessages[1].content))
            } else {
                RagPromptStageLog.skipped(RagPromptStage.EXTERNAL_CONTEXT_COMPILED,
                    if (vectorRetrieval.attempt.executed) "no_reference_context" else "vector_retrieval_not_requested")
            }
            val stageLogs = finalStageLogs(characterPrompt.stageLogs + externalStage + attribution.stageLogs, rendered)
            CompiledAndroidPrompt(
                renderedPrompt = rendered.renderedSinglePrompt,
                rendered = rendered,
                sources = prompt.sources,
                contextText = prompt.contextText,
                characterDebug = prompt.characterDebug,
                mode = mode,
                contextDecisions = referenceDecisions,
                attributionTrace = attribution.trace,
                stageLogs = stageLogs,
                turnTrace = RagTurnTrace.create(
                    mode = mode,
                    userInput = userInput,
                    modelFamily = modelFamily,
                    rendered = rendered,
                    contextText = prompt.contextText,
                    vectorRetrieval = vectorRetrieval.attempt,
                    externalInjectionRequested = externalInjectionRequested,
                    sources = prompt.sources,
                    contextDecisions = referenceDecisions,
                    characterDebug = prompt.characterDebug,
                    databaseManifest = databaseBundle?.manifest,
                    attributionTrace = attribution.trace,
                ),
            )
        }
    }

    private data class AttributionResolution(
        val messages: List<HarnessChatMessage>,
        val trace: AttributionRewriteTrace,
        val stageLogs: List<RagPromptStageLog>,
    )

    private fun rewriteFinalPrompt(
        raw: RawPromptPackage,
        rawMessages: List<HarnessChatMessage>,
    ): AttributionResolution {
        if (raw.references.isEmpty()) {
            return AttributionResolution(
                messages = rawMessages,
                trace = AttributionRewriteTrace("not_requested", "no_authorized_references"),
                stageLogs = listOf(
                    RagPromptStageLog.skipped(RagPromptStage.RAW_PROMPT_COMPILED, "no_authorized_references"),
                    RagPromptStageLog.skipped(RagPromptStage.AGENT_A_REQUEST, "no_authorized_references"),
                    RagPromptStageLog.skipped(RagPromptStage.FINAL_PROMPT_VALIDATED, "no_authorized_references"),
                ),
            )
        }
        val baseTrace = AttributionRewriteTrace(
            status = "requested",
            rawPromptFingerprint = raw.fingerprint,
            referenceCount = raw.references.size,
        )
        val rawStage = RagPromptStageLog.emitted(
            RagPromptStage.RAW_PROMPT_COMPILED,
            RagPromptSnapshot(systemPrompt = rawMessages[0].content, userPrompt = rawMessages[1].content),
        )
        if (!finalPromptRewriteService.enabled) {
            return AttributionResolution(
                messages = rawMessages,
                trace = baseTrace.copy(status = "disabled", reason = "disabled"),
                stageLogs = listOf(
                    rawStage,
                    RagPromptStageLog.skipped(RagPromptStage.AGENT_A_REQUEST, "disabled"),
                    RagPromptStageLog.skipped(RagPromptStage.FINAL_PROMPT_VALIDATED, "disabled"),
                ),
            )
        }
        val compiler = attributionRequestCompiler
            ?: return AttributionResolution(
                messages = rawMessages,
                trace = baseTrace.copy(status = "fallback", reason = "agent_a_template_unavailable"),
                stageLogs = listOf(
                    rawStage,
                    RagPromptStageLog.skipped(RagPromptStage.AGENT_A_REQUEST, "agent_a_template_unavailable"),
                    RagPromptStageLog.skipped(RagPromptStage.FINAL_PROMPT_VALIDATED, "agent_a_template_unavailable"),
                ),
            )
        val request = runCatching { compiler.compile(raw) }.getOrElse { error ->
            return AttributionResolution(
                messages = rawMessages,
                trace = baseTrace.copy(status = "fallback", reason = "agent_a_request_compile_failed"),
                stageLogs = listOf(
                    rawStage,
                    RagPromptStageLog.skipped(RagPromptStage.AGENT_A_REQUEST, "agent_a_request_compile_failed"),
                    RagPromptStageLog.skipped(RagPromptStage.FINAL_PROMPT_VALIDATED, "agent_a_request_compile_failed"),
                ),
            )
        }
        val requestStage = RagPromptStageLog.emitted(
            RagPromptStage.AGENT_A_REQUEST,
            RagPromptSnapshot(systemPrompt = request.messages[0].content, userPrompt = request.messages[1].content),
        )
        val result = runCatching { finalPromptRewriteService.rewrite(request) }.getOrElse { error ->
            return AttributionResolution(
                messages = rawMessages,
                trace = baseTrace.copy(status = "fallback", reason = "agent_a_failed"),
                stageLogs = listOf(
                    rawStage,
                    requestStage,
                    RagPromptStageLog.skipped(RagPromptStage.FINAL_PROMPT_VALIDATED, "agent_a_failed"),
                ),
            )
        }
        return when (result) {
            is AttributionAgentResult.Fallback -> AttributionResolution(
                messages = rawMessages,
                trace = baseTrace.copy(status = "fallback", reason = result.reason),
                stageLogs = listOf(
                    rawStage,
                    requestStage,
                    RagPromptStageLog.skipped(RagPromptStage.FINAL_PROMPT_VALIDATED, result.reason),
                ),
            )
            is AttributionAgentResult.Draft -> when (val validation = FinalPromptValidator().validate(raw, result.draft)) {
                is FinalPromptValidation.Rejected -> AttributionResolution(
                    messages = rawMessages,
                    trace = baseTrace.copy(status = "fallback", reason = validation.code, modelId = result.modelId),
                    stageLogs = listOf(
                        rawStage,
                        requestStage,
                        RagPromptStageLog.skipped(RagPromptStage.FINAL_PROMPT_VALIDATED, validation.code),
                    ),
                )
                FinalPromptValidation.Accepted -> {
                    val materialized = FinalPromptMaterializer().materialize(raw, result.draft)
                    val messages = listOf(
                        HarnessChatMessage("system", materialized.systemPrompt),
                        HarnessChatMessage("user", materialized.userPrompt),
                    )
                    AttributionResolution(
                        messages = messages,
                        trace = baseTrace.copy(
                            status = "success",
                            modelId = result.modelId,
                            finalPromptFingerprint = ContentFingerprint.sha256Short(
                                materialized.systemPrompt + '\u0000' + materialized.userPrompt,
                            ),
                        ),
                        stageLogs = listOf(
                            rawStage,
                            requestStage,
                            RagPromptStageLog.emitted(
                                RagPromptStage.FINAL_PROMPT_VALIDATED,
                                RagPromptSnapshot(systemPrompt = messages[0].content, userPrompt = messages[1].content),
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun retrieveForActiveRagTurn(
        userInput: String,
        candidateLimit: Int,
        minScore: Double,
        maxSourceEnd: Long? = null,
    ): AlwaysOnVectorRetrievalResult {
        if (!vectorRetrievalFeatureFlags.alwaysOnVectorRetrieval) {
            return AlwaysOnVectorRetrievalResult(
                attempt = VectorRetrievalAttempt.notRequested("disabled_by_flag"),
                results = emptyList(),
            )
        }
        return alwaysOnVectorRetrievalService.searchAlways(userInput, candidateLimit, minScore, maxSourceEnd)
    }

    private fun finalStageLogs(
        initial: List<RagPromptStageLog>,
        rendered: RenderedChatPrompt,
    ): List<RagPromptStageLog> = initial + listOf(
        RagPromptStageLog.emitted(
            RagPromptStage.FINAL_RENDERED,
            RagPromptSnapshot(renderedPrompt = rendered.renderedSinglePrompt),
        ),
        RagPromptStageLog.emitted(
            RagPromptStage.BACKEND_REQUEST,
            RagPromptSnapshot(
                systemPrompt = rendered.splitPrompt.systemPrompt,
                userPrompt = rendered.splitPrompt.userPrompt,
            ),
        ),
    )

    companion object {
        private const val GENERAL_RAG_MIN_SCORE = 0.10
        private const val CHARACTER_RAG_MIN_SCORE = 0.10
        private const val CONVERSATION_CONTEXT_MAX_CHARS = 360

        fun fromContext(context: Context): AndroidRagOrchestrator {
            return fromBundle(DatabaseRuntime.requireBundle(),
                File(context.filesDir, "program/prompts/roleplay_system.prompt"),
                VectorRetrievalFeatureFlags.from(context.applicationContext))
        }

        fun fromBundle(bundle: DatabaseBundleHandle, promptTemplate: File,
            flags: VectorRetrievalFeatureFlags = VectorRetrievalFeatureFlags(),
            finalPromptRewriteService: FinalPromptRewriteService = NoopFinalPromptRewriteService): AndroidRagOrchestrator {
            require(DatabaseBundleValidator.validate(bundle.paths).manifest.datasetFingerprint ==
                bundle.manifest.datasetFingerprint) { "database_handle_identity_changed" }
            val attributionTemplate = File(promptTemplate.parentFile, "final_prompt_rewriter_system.prompt")
            return AndroidRagOrchestrator(RagSearchService.fromBundle(bundle, flags.hybridRetrieval),
                CharacterPromptCompiler.fromBundle(bundle, promptTemplate),
                vectorRetrievalFeatureFlags = flags, databaseBundle = bundle,
                finalPromptRewriteService = finalPromptRewriteService,
                attributionRequestCompiler = AttributionAgentRequestCompiler.fromFile(attributionTemplate))
        }
    }
}
