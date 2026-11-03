package com.example.minicpm_v_demo.harness.data.database

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.harness.HarnessChatRequest
import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.LlamaBackendAdapter
import com.example.minicpm_v_demo.harness.character.*
import com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestrator
import com.example.minicpm_v_demo.harness.rag.RagMode
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fixed public-data diagnostic samples; does not modify model selection or user sessions. */
@RunWith(AndroidJUnit4::class)
class DatabaseRelevanceDeviceTest {
    @Test fun currentModelAnswersRelevanceSamples() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LlamaEngine.getSelectedModel(context)
        assumeTrue("This diagnostic uses the already-selected V4.6 model", model.id == "minicpm-v-4_6-instruct")
        val weights = File(LlamaEngine.modelDirFor(context, model), model.ggufFileName)
        val projector = model.mmprojFileName?.let { File(LlamaEngine.modelDirFor(context, model), it) }
        assumeTrue("Selected model files must already be present", weights.isFile && (projector == null || projector.isFile))
        val engine = LlamaEngine.getInstance(context)
        val initial = withTimeout(30_000) { engine.state.filter {
            it is LlamaState.Initialized || it is LlamaState.ModelReady || it is LlamaState.Error
        }.first() }
        check(initial !is LlamaState.Error)
        val didLoad = initial is LlamaState.Initialized
        val runId = java.util.UUID.randomUUID().toString()
        Log.i("RagQualityDevice", "RUN $runId")
        try {
            if (didLoad) engine.loadModel(weights.absolutePath, projector?.absolutePath)
            check(engine.state.value is LlamaState.ModelReady)
            val backend = LlamaBackendAdapter(context)
            val orch = AndroidRagOrchestrator.fromContext(context)
            val cases = listOf(
                Case("identity-traditional", "shi_qiang", "evt-118", "罗輯现在的身份是什么"),
                Case("identity-simplified", "shi_qiang", "evt-118", "罗辑现在的身份是什么"),
                Case("profession", "luo_ji", "evt-001", "你以前在大学教什么？"),
                Case("unknown", "luo_ji", "evt-001", "你知道我的手机密码吗？"),
            )
            for (case in cases) {
                val start = System.nanoTime()
                val compiled = orch.compile(case.query, RagMode.CHARACTER_RAG, case.character, case.cutoff,
                    modelFamily = HarnessModelFamily.MINICPM_VISION)
                val compileMs = (System.nanoTime() - start) / 1_000_000
                val debug = compiled.characterDebug as CharacterPromptDebug
                val gate = RoleplayAnswerabilityGate.evaluate(debug, case.query)
                val fixed = when (gate) {
                    is RoleplayAnswerabilityDecision.BlockFutureFact -> gate.visibleResponse
                    is RoleplayAnswerabilityDecision.BlockUnknownFact -> gate.visibleResponse
                    RoleplayAnswerabilityDecision.Allow -> null
                }
                var answer = fixed
                var action = "fixed"
                if (answer == null) {
                    var system = compiled.rendered.splitPrompt.systemPrompt
                    val coordinator = RoleplayGenerationCoordinator()
                    for (attempt in 0..RoleplayGenerationCoordinator.MAX_REPAIR_ATTEMPTS) {
                        val raw = withTimeout(180_000) { buildString {
                            backend.sendChatPrompt(HarnessChatRequest(userPrompt = compiled.rendered.splitPrompt.userPrompt,
                                systemPrompt = system), 64).collect(::append)
                        }.trim() }
                        when (val result = coordinator.resolve(attempt, raw,
                            if (case.character == "shi_qiang") "史强" else "罗辑", case.query)) {
                            is RoleplayGenerationAction.Deliver -> { answer = result.visibleText; action = "deliver"; break }
                            is RoleplayGenerationAction.Fallback -> { answer = result.visibleText; action = "fallback"; break }
                            is RoleplayGenerationAction.Retry -> system += "\n" + RoleplayGenerationCoordinator.REPAIR_INSTRUCTION
                        }
                    }
                }
                check(!answer.isNullOrBlank())
                val trace = compiled.turnTrace
                val result = JSONObject().put("run", runId).put("case", case.id).put("question", case.query)
                    .put("dataset", trace.datasetId).put("fingerprint", trace.datasetFingerprint)
                    .put("indexVersion", trace.external.indexVersion).put("indexFingerprint", trace.indexFingerprint)
                    .put("vectorCandidates", trace.external.vectorCandidateIds.joinToString(","))
                    .put("decisions", trace.external.decisionReasonCodes.filter {
                        it.contains(":selected:") || it.contains(":compressed:")
                    }.joinToString(" | "))
                    .put("droppedReasons", compiled.contextDecisions.filter { it.decision == "dropped" }
                        .groupingBy { it.reason }.eachCount().toString())
                    .put("context", compiled.contextText).put("compileMs", compileMs)
                    .put("gate", gate.javaClass.simpleName).put("action", action).put("answer", answer)
                File(context.cacheDir, "rag-relevance-$runId.jsonl").appendText(result.toString() + "\n", Charsets.UTF_8)
                Log.i("RagQualityDevice", "CASE_META run=$runId case=${case.id} gate=${gate.javaClass.simpleName} action=$action")
            }
        } finally {
            if (didLoad && engine.state.value is LlamaState.ModelReady) engine.unloadModel()
        }
    }
    private data class Case(val id: String, val character: String, val cutoff: String, val query: String)
}
