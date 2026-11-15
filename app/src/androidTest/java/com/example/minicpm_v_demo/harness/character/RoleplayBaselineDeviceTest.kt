package com.example.minicpm_v_demo.harness.character

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.harness.ContentFingerprint
import com.example.minicpm_v_demo.harness.HarnessChatRequest
import com.example.minicpm_v_demo.harness.LlamaBackendAdapter
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
        val entry = instrumentation.context.assets.open("cases.jsonl").bufferedReader().useLines { lines ->
            lines.map(::JSONObject).first { it.getString("case_id") == caseId }
        }
        val context = instrumentation.targetContext
        assertTrue(AssetBootstrapper.awaitStartup(120_000))
        val model = LlamaEngine.getSelectedModel(context)
        val weights = File(LlamaEngine.modelDirFor(context, model), model.ggufFileName)
        val projector = model.mmprojFileName?.let { File(LlamaEngine.modelDirFor(context, model), it) }
        check(weights.isFile && (projector == null || projector.isFile)) { "selected model unavailable" }
        val engine = LlamaEngine.getInstance(context)
        val state = withTimeout(60_000) {
            engine.state.filter { it is LlamaState.Initialized || it is LlamaState.ModelReady || it is LlamaState.Error }.first()
        }
        check(state !is LlamaState.Error)
        if (state is LlamaState.Initialized) engine.loadModel(weights.absolutePath, projector?.absolutePath)
        val backend = LlamaBackendAdapter(context)
        val attributionTimeoutMs = args.getString("aTimeoutMs")?.toLong()
            ?: LocalAttributionRewriteService.DEFAULT_TIMEOUT_MILLIS
        val service = if (group == "refs_a") LocalAttributionRewriteService(
            context, { engine.state.value is LlamaState.ModelReady }, attributionTimeoutMs,
        ) else NoopFinalPromptRewriteService
        val bundle = DatabaseRuntime.requireBundle()
        val question = entry.getString("question")
        val characterId = entry.getString("character_id")
        val started = System.nanoTime()
        AndroidRagOrchestrator.fromBundle(
            bundle, File(context.filesDir, "program/prompts/roleplay_system.prompt"),
            VectorRetrievalFeatureFlags(alwaysOnVectorRetrieval = group != "no_refs"), service,
        ).use { orchestrator ->
            val compiled = orchestrator.compile(question, RagMode.CHARACTER_RAG, characterId,
                entry.getString("story_cutoff"), modelFamily = model.toHarnessSpec().family)
            val compileMs = (System.nanoTime() - started) / 1_000_000
            val identity = RoleplayCharacterIdentity.fromCards(characterId, orchestrator.availableCharacters())
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
            for (attempt in 0..RoleplayGenerationCoordinator.MAX_REPAIR_ATTEMPTS) {
                val raw = fixed ?: withTimeout(180_000) {
                    buildString {
                        backend.sendChatPrompt(HarnessChatRequest(systemPrompt = system, userPrompt = user), 512).collect {
                            if (firstTokenMs < 0) firstTokenMs = (System.nanoTime() - started) / 1_000_000
                            append(it)
                        }
                    }
                }
                when (val action = RoleplayGenerationCoordinator().resolve(attempt, raw, identity.name, question, identity.aliases, fixed != null)) {
                    is RoleplayGenerationAction.Deliver -> { visible = action.visibleText; outcome = if (fixed != null) "fixed" else if (attempt == 0) "generated" else "retry"; break }
                    is RoleplayGenerationAction.Fallback -> {
                        rejectedRules += action.ruleIds
                        visible = action.visibleText
                        outcome = "fallback"
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
                .put("rejected_rules", org.json.JSONArray(rejectedRules))
                .put("total_ms", (System.nanoTime() - started) / 1_000_000)
            Log.i("RoleplayBaseline", result.toString())
        }
        Unit
    }
}
