package com.example.minicpm_v_demo.harness.character

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.harness.ContentFingerprint
import com.example.minicpm_v_demo.harness.HarnessChatRequest
import com.example.minicpm_v_demo.harness.LlamaBackendAdapter
import com.example.minicpm_v_demo.harness.attribution.AgentBPromptFormat
import com.example.minicpm_v_demo.harness.attribution.AttributionAgentRequest
import com.example.minicpm_v_demo.harness.attribution.AttributionAgentResult
import com.example.minicpm_v_demo.harness.attribution.FinalPromptDraftJsonCodec
import com.example.minicpm_v_demo.harness.attribution.FinalPromptRewriteService
import com.example.minicpm_v_demo.harness.attribution.LocalAttributionRewriteService
import com.example.minicpm_v_demo.harness.attribution.NoopFinalPromptRewriteService
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper
import com.example.minicpm_v_demo.harness.data.database.DatabaseRuntime
import com.example.minicpm_v_demo.harness.toHarnessSpec
import com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestrator
import com.example.minicpm_v_demo.harness.rag.RagMode
import com.example.minicpm_v_demo.harness.rag.VectorRetrievalFeatureFlags
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic cases only; creates no ChatSession and never writes model reasoning to logs. */
@RunWith(AndroidJUnit4::class)
class RoleplayBaselineDeviceTest {
    @Test fun evaluateCase() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val caseId = args.getString("caseId") ?: error("caseId required")
        val group = args.getString("group") ?: error("group required")
        require(group in setOf("no_refs", "refs_no_a", "refs_a"))
        val pair = args.getString("formatPair") == "true"
        require(!pair || group == "refs_a")
        val formats = if (pair) {
            val first = if (args.getString("jsonFirst") == "true") AgentBPromptFormat.JSON else AgentBPromptFormat.TEXT
            val second = if (first == AgentBPromptFormat.TEXT) AgentBPromptFormat.JSON else AgentBPromptFormat.TEXT
            listOf(first, second, second, first)
        } else listOf(AgentBPromptFormat.TEXT)
        val entry = instrumentation.context.assets.open("cases.jsonl").bufferedReader().useLines { lines ->
            lines.map(::JSONObject).first { it.getString("case_id") == caseId }
        }
        val context = instrumentation.targetContext
        assertTrue(AssetBootstrapper.awaitStartup(120_000))
        val model = LlamaEngine.getSelectedModel(context)
        val weights = File(LlamaEngine.modelDirFor(context, model), model.ggufFileName)
        check(weights.isFile) { "selected model unavailable" }
        val engine = LlamaEngine.getInstance(context)
        val state = withTimeout(60_000) {
            engine.state.filter { it is LlamaState.Initialized || it is LlamaState.ModelReady || it is LlamaState.Error }.first()
        }
        check(state !is LlamaState.Error)
        if (state is LlamaState.Initialized) engine.loadModel(weights.absolutePath)
        val backend = LlamaBackendAdapter(context)
        val attributionTimeoutMs = args.getString("aTimeoutMs")?.toLong()
            ?: LocalAttributionRewriteService.DEFAULT_TIMEOUT_MILLIS
        var frozen: AttributionAgentResult? = null
        var frozenRawFingerprint: String? = null
        var liveCalls = 0
        val service = if (group == "refs_a") object : FinalPromptRewriteService, AutoCloseable {
            val local = LocalAttributionRewriteService(context, { engine.state.value is LlamaState.ModelReady }, attributionTimeoutMs)
            override val enabled: Boolean get() = local.enabled
            override fun rewrite(request: AttributionAgentRequest): AttributionAgentResult {
                frozen?.let {
                    check(frozenRawFingerprint == request.raw.fingerprint) { "paired_raw_prompt_changed" }
                    return it
                }
                frozenRawFingerprint = request.raw.fingerprint
                liveCalls++
                return local.rewrite(request).also { frozen = it }
            }
            override fun close() = local.close()
        } else NoopFinalPromptRewriteService
        val bundle = DatabaseRuntime.requireBundle()
        val question = entry.getString("question")
        val characterId = entry.getString("character_id")
        val started = System.nanoTime()
        AndroidRagOrchestrator.fromBundle(
            bundle, File(context.filesDir, "program/prompts/roleplay_system.prompt"),
            VectorRetrievalFeatureFlags(alwaysOnVectorRetrieval = group != "no_refs"), service,
        ).use { orchestrator ->
            val compilations = formats.distinct().associateWith { format ->
                val compileStarted = System.nanoTime()
                val compiled = orchestrator.compile(question, RagMode.CHARACTER_RAG, characterId,
                    entry.getString("story_cutoff"), modelFamily = model.toHarnessSpec().family,
                    agentBPromptFormat = format)
                compiled to (System.nanoTime() - compileStarted) / 1_000_000
            }
            if (pair) {
                check(liveCalls == 1 && frozen is AttributionAgentResult.Draft) {
                    "paired_agent_a_failed: ${compilations.values.first().first.attributionTrace}"
                }
                val prompts = compilations.values.map { it.first }
                check(prompts.all { it.attributionTrace?.status == "success" })
                check(prompts.map { it.rendered.splitPrompt.systemPrompt }.distinct().size == 1)
                check(prompts.map { it.contextText }.distinct().size == 1)
                check(compilations.all { (format, result) -> result.first.attributionTrace?.finalPromptFormat == format })
            }
            val identity = RoleplayCharacterIdentity.fromCards(characterId, orchestrator.availableCharacters())
            val draftHash = (frozen as? AttributionAgentResult.Draft)?.let {
                ContentFingerprint.sha256Short(FinalPromptDraftJsonCodec.encode(it.draft))
            }
            for ((sample, format) in formats.withIndex()) {
                val (compiled, compileMs) = compilations.getValue(format)
                val gate = RoleplayAnswerabilityGate.evaluate(compiled.characterDebug as? CharacterPromptDebug, question)
                val fixed = when (gate) {
                    is RoleplayAnswerabilityDecision.BlockFutureFact -> gate.visibleResponse
                    is RoleplayAnswerabilityDecision.BlockUnknownFact -> gate.visibleResponse
                    RoleplayAnswerabilityDecision.Allow -> null
                }
                var system = compiled.rendered.splitPrompt.systemPrompt
                val user = compiled.rendered.splitPrompt.userPrompt
                var visible = ""
                var outcome = ""
                var firstTokenMs = -1L
                val rejectedRules = mutableListOf<List<String>>()
                val promptTokens = listOf(backend.countPromptTokens(system), backend.countPromptTokens(user))
                if (pair) Log.i("RoleplayBaseline", "RUN case=$caseId sample=$sample format=$format")
                val generationStarted = System.nanoTime()
                var generationFirstTokenMs = -1L
                for (attempt in 0..RoleplayGenerationCoordinator.MAX_REPAIR_ATTEMPTS) {
                    val raw = fixed ?: withTimeout(180_000) {
                        buildString {
                            backend.sendChatPrompt(HarnessChatRequest(systemPrompt = system, userPrompt = user), 512).collect {
                                if (firstTokenMs < 0) {
                                    val now = System.nanoTime()
                                    firstTokenMs = (now - started) / 1_000_000
                                    generationFirstTokenMs = (now - generationStarted) / 1_000_000
                                }
                                append(it)
                            }
                        }
                    }
                    when (val action = RoleplayGenerationCoordinator().resolve(
                        attempt, raw, identity.name, question, identity.aliases,
                        fixedResponse = fixed != null, publicProfileReply = compiled.publicProfileReply,
                    )) {
                        is RoleplayGenerationAction.Deliver -> { visible = action.visibleText; outcome = if (fixed != null) "fixed" else if (attempt == 0) "generated" else "retry"; break }
                        is RoleplayGenerationAction.Fallback -> {
                            rejectedRules += action.ruleIds
                            visible = action.visibleText
                            outcome = if (RoleplayGenerationCoordinator.PUBLIC_PROFILE_FALLBACK in action.ruleIds) {
                                "public_profile_fallback"
                            } else {
                                "fallback"
                            }
                            break
                        }
                        is RoleplayGenerationAction.Retry -> {
                            rejectedRules += action.ruleIds
                            system += "\n\n" + RoleplayGenerationCoordinator.repairInstruction(action.ruleIds)
                        }
                    }
                }
                val trace = compiled.attributionTrace
                val result = JSONObject().put("case_id", caseId).put("group", group).put("model", model.id)
                    .put("database", bundle.manifest.datasetFingerprint).put("a_status", trace?.status)
                    .put("a_reason", trace?.reason).put("a_model", trace?.modelId)
                    .put("a_timeout_ms", attributionTimeoutMs).put("compile_ms", compileMs)
                    .put("reference_count", trace?.referenceCount ?: 0)
                    .put("context_hash", ContentFingerprint.sha256Short(compiled.contextText))
                    .put("system_hash", ContentFingerprint.sha256Short(compiled.rendered.splitPrompt.systemPrompt))
                    .put("user_hash", ContentFingerprint.sha256Short(user)).put("gate", gate.javaClass.simpleName)
                    .put("system_tokens", promptTokens[0]).put("user_tokens", promptTokens[1])
                    .put("output", visible).put("outcome", outcome).put("first_token_ms", firstTokenMs)
                    .put("format", trace?.finalPromptFormat?.name).put("format_schema", trace?.finalPromptSchemaVersion)
                    .put("sample", sample).put("a_live_calls", liveCalls).put("draft_hash", draftHash)
                    .put("b_first_token_ms", generationFirstTokenMs)
                    .put("b_total_ms", (System.nanoTime() - generationStarted) / 1_000_000)
                    .put("rejected_rules", org.json.JSONArray(rejectedRules))
                    .put("total_ms", (System.nanoTime() - started) / 1_000_000)
                Log.i("RoleplayBaseline", result.toString())
            }
            if (pair && args.getString("verifyJsonCancellation") == "true") {
                val prompt = compilations.getValue(AgentBPromptFormat.JSON).first.rendered.splitPrompt
                var receivedToken = false
                withTimeout(60_000) {
                    backend.sendChatPrompt(HarnessChatRequest(prompt.userPrompt, prompt.systemPrompt), 512).collect {
                        receivedToken = true
                        backend.cancelGeneration()
                    }
                }
                check(receivedToken && backend.state.value is LlamaState.ModelReady)
                Log.i("RoleplayBaseline", "CANCEL case=$caseId format=JSON state=ModelReady")
            }
        }
        Unit
    }
}
