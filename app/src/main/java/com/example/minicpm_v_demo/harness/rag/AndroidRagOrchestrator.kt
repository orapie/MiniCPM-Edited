package com.example.minicpm_v_demo.harness.rag

import android.content.Context
import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.character.CharacterCard
import com.example.minicpm_v_demo.harness.character.CharacterFollowUpTarget
import com.example.minicpm_v_demo.harness.character.CharacterPromptCompiler
import com.example.minicpm_v_demo.harness.character.RuntimeContext
import com.example.minicpm_v_demo.harness.chat.ChatTemplateRenderer
import com.example.minicpm_v_demo.harness.chat.RenderedChatPrompt
import com.example.minicpm_v_demo.harness.data.database.DatabaseRuntime
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleHandle
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleValidator
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleManifest
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
) {
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
        conversationSummary: String = "",
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
            val vectorRetrieval = retrieveForActiveRagTurn(
                userInput = userInput,
                candidateLimit = maxOf(topK * 8, 20),
                minScore = CHARACTER_RAG_MIN_SCORE,
            )
            val externalInjectionRequested = vectorRetrieval.attempt.executed
            val characterPrompt = characterCompiler.buildNpcPrompt(
                npcId = characterId,
                userInput = userInput,
                runtimeContext = RuntimeContext(
                    storyCutoff = storyCutoff,
                    maxChars = maxCharacterChars,
                    conversationSummary = conversationSummary,
                    knowledgeGraphEnabled = knowledgeGraphEnabled,
                    followUpTarget = followUpTarget,
                    vectorCandidateScores = vectorRetrieval.results
                        .filter { it.chunk.metadata["record_kind"] == "reviewed_fact" }
                        .associate { it.chunk.id to it.score },
                ),
            )
            val prompt = characterRagPromptBuilder.build(
                characterMessages = characterPrompt.messages,
                userInput = userInput,
                results = vectorRetrieval.results,
                characterDebug = characterPrompt.debug,
            )
            val rendered = renderer.render(prompt.messages)
            val externalStage = if (prompt.contextText.isNotBlank()) {
                RagPromptStageLog.emitted(RagPromptStage.EXTERNAL_CONTEXT_COMPILED,
                    RagPromptSnapshot(systemPrompt = prompt.messages[0].content, userPrompt = prompt.messages[1].content))
            } else {
                RagPromptStageLog.skipped(RagPromptStage.EXTERNAL_CONTEXT_COMPILED,
                    if (vectorRetrieval.attempt.executed) "no_reference_context" else "vector_retrieval_not_requested")
            }
            val stageLogs = finalStageLogs(characterPrompt.stageLogs + externalStage, rendered)
            CompiledAndroidPrompt(
                renderedPrompt = rendered.renderedSinglePrompt,
                rendered = rendered,
                sources = prompt.sources,
                contextText = prompt.contextText,
                characterDebug = prompt.characterDebug,
                mode = mode,
                contextDecisions = prompt.contextDecisions,
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
                    contextDecisions = prompt.contextDecisions,
                    characterDebug = prompt.characterDebug,
                    databaseManifest = databaseBundle?.manifest,
                ),
            )
        }
    }

    private fun retrieveForActiveRagTurn(
        userInput: String,
        candidateLimit: Int,
        minScore: Double,
    ): AlwaysOnVectorRetrievalResult {
        if (!vectorRetrievalFeatureFlags.alwaysOnVectorRetrieval) {
            return AlwaysOnVectorRetrievalResult(
                attempt = VectorRetrievalAttempt.notRequested("disabled_by_flag"),
                results = emptyList(),
            )
        }
        return alwaysOnVectorRetrievalService.searchAlways(userInput, candidateLimit, minScore)
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

        fun fromContext(context: Context): AndroidRagOrchestrator {
            return fromBundle(DatabaseRuntime.requireBundle(),
                File(context.filesDir, "program/prompts/roleplay_system.prompt"),
                VectorRetrievalFeatureFlags.from(context.applicationContext))
        }

        fun fromBundle(bundle: DatabaseBundleHandle, promptTemplate: File,
            flags: VectorRetrievalFeatureFlags = VectorRetrievalFeatureFlags()): AndroidRagOrchestrator {
            require(DatabaseBundleValidator.validate(bundle.paths).manifest.datasetFingerprint ==
                bundle.manifest.datasetFingerprint) { "database_handle_identity_changed" }
            return AndroidRagOrchestrator(RagSearchService.fromBundle(bundle),
                CharacterPromptCompiler.fromBundle(bundle, promptTemplate),
                vectorRetrievalFeatureFlags = flags, databaseBundle = bundle)
        }
    }
}
