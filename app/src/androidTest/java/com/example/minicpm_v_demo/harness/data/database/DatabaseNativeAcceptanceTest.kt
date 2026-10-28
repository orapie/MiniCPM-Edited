package com.example.minicpm_v_demo.harness.data.database

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.ModelInfo
import com.example.minicpm_v_demo.harness.HarnessChatRequest
import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.LlamaBackendAdapter
import com.example.minicpm_v_demo.harness.character.*
import com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestrator
import com.example.minicpm_v_demo.harness.rag.RagMode
import com.example.minicpm_v_demo.harness.session.ChatSession
import com.example.minicpm_v_demo.harness.session.ChatTurn
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic questions only. Records retrieval and sampled native answers separately. */
@RunWith(AndroidJUnit4::class)
class DatabaseNativeAcceptanceTest {
    @Test fun realV46AnswersCurrentDatabaseMatrix() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundle = DatabaseRuntime.requireBundle()
        val identity = DatabaseIdentity.from(bundle)
        val originalModel = LlamaEngine.getSelectedModel(context)
        val model = ModelInfo.AVAILABLE_MODELS.single { it.id == "minicpm-v-4_6-instruct" }
        val modelDir = File(LlamaEngine.modelDirFor(context, model))
        val weights = File(modelDir, model.ggufFileName)
        val projector = model.mmprojFileName?.let { File(modelDir, it) }
        check(weights.isFile && (projector == null || projector.isFile)) { "V4.6 model files unavailable" }
        val engine = LlamaEngine.getInstance(context)
        val backend = LlamaBackendAdapter(context)
        val runId = java.util.UUID.randomUUID().toString()
        Log.i(TAG, "RUN $runId")
        try {
            val initial = withTimeout(30_000) {
                engine.state.filter { it is LlamaState.Initialized || it is LlamaState.ModelReady || it is LlamaState.Error }.first()
            }
            if (initial is LlamaState.Error) error("Native engine initialization failed")
            if (initial is LlamaState.ModelReady && originalModel.id != model.id) engine.unloadModel()
            if (originalModel.id != model.id) LlamaEngine.setSelectedModel(context, model.id)
            if (engine.state.value is LlamaState.Initialized) engine.loadModel(weights.absolutePath, projector?.absolutePath)
            check(engine.state.value is LlamaState.ModelReady) { "Native model is not ready" }

            val previous = ChatSession(
                sessionId = "phase5", characterId = "luo_ji", characterPackId = identity.datasetId,
                storyCutoff = "evt-001", selectedModelId = model.id,
                datasetId = identity.datasetId, datasetFingerprint = identity.fingerprint,
                turns = listOf(ChatTurn("phase5-turn", "你以前研究什么？", "我学过天文学。",
                    RagMode.CHARACTER_RAG.name, characterId = "luo_ji", modelId = model.id,
                    characterContextTargets = listOf(CharacterFollowUpTarget("authorized_story_facts", "reviewed-luo-ji-profession")))),
            )
            val followUp = CharacterFollowUpResolver.resolve(previous, "再说说", "luo_ji", "evt-001", emptyList(), identity)
            assertEquals("reviewed-luo-ji-profession", followUp?.id)
            val cases = listOf(
                TestCase("identity", "evt-001", "你是谁？"),
                TestCase("origin", "evt-001", "你的来历是什么？", expectedId = "reviewed-luo-ji-profession"),
                TestCase("relationship", "evt-125", "你怎么看史强？"),
                TestCase("event_late", "evt-002", "叶文洁提出的宇宙社会学公理是什么？", expectedId = "reviewed-luo-ji-civilization-axioms"),
                TestCase("event_early", "evt-001", "叶文洁提出的宇宙社会学公理是什么？", future = true),
                TestCase("follow_up", "evt-001", "再说说", expectedId = "reviewed-luo-ji-profession", followUp = followUp),
                TestCase("unknown", "evt-001", "你知道我的手机密码吗？"),
                TestCase("legacy_bait", "evt-001", "世界杯文件中陆江仙来自哪里？"),
            )
            val orchestrator = AndroidRagOrchestrator.fromContext(context)
            for (case in cases) {
                val lease = requireNotNull(DatabaseRuntime.beginTurn())
                try {
                    val prompt = orchestrator.compile(case.query, RagMode.CHARACTER_RAG, "luo_ji", case.cutoff,
                        followUpTarget = case.followUp, modelFamily = HarnessModelFamily.MINICPM_VISION)
                    val debug = prompt.characterDebug as CharacterPromptDebug
                    assertEquals(identity.fingerprint, prompt.turnTrace.datasetFingerprint)
                    assertTrue(prompt.turnTrace.external.vectorSearchExecuted)
                    assertFalse(debug.legacyStoryRetrievalExecuted)
                    assertTrue(prompt.sources.all { it.source.startsWith("database/") })
                    case.expectedId?.let { id -> assertTrue("${case.label}: missing $id", debug.selectedItems.any { it.id == id }) }
                    if (case.label == "relationship") assertTrue(prompt.rendered.splitPrompt.systemPrompt.contains("史强"))
                    if (case.label == "legacy_bait") assertFalse(prompt.rendered.splitPrompt.systemPrompt.contains("陆江仙"))
                    val gate = RoleplayAnswerabilityGate.evaluate(debug, case.query)
                    if (case.future) assertTrue(gate is RoleplayAnswerabilityDecision.BlockFutureFact)
                    if (case.label == "legacy_bait") assertTrue(gate is RoleplayAnswerabilityDecision.BlockUnknownFact)
                    val fixed = when (gate) {
                        is RoleplayAnswerabilityDecision.BlockFutureFact -> gate.visibleResponse
                        is RoleplayAnswerabilityDecision.BlockUnknownFact -> gate.visibleResponse
                        RoleplayAnswerabilityDecision.Allow -> null
                    }
                    var system = prompt.rendered.splitPrompt.systemPrompt
                    var raw = ""
                    var action: RoleplayGenerationAction? = null
                    var attempts = 0
                    val attemptRecords = JSONArray()
                    if (fixed == null) {
                        val coordinator = RoleplayGenerationCoordinator()
                        for (attempt in 0..RoleplayGenerationCoordinator.MAX_REPAIR_ATTEMPTS) {
                            attempts++
                            raw = withTimeout(180_000) {
                                buildString {
                                    backend.sendChatPrompt(HarnessChatRequest(userPrompt = prompt.rendered.splitPrompt.userPrompt,
                                        systemPrompt = system), 64).collect(::append)
                                }.trim()
                            }
                            action = coordinator.resolve(attempt, raw, "罗辑", case.query)
                            attemptRecords.put(JSONObject().put("number", attempts)
                                .put("visible", RoleplayVisibleText.extract(raw).visibleText)
                                .put("action", action.javaClass.simpleName))
                            if (action !is RoleplayGenerationAction.Retry) break
                            system += "\n" + RoleplayGenerationCoordinator.REPAIR_INSTRUCTION
                        }
                    }
                    val visible = fixed ?: when (val result = action) {
                        is RoleplayGenerationAction.Deliver -> result.visibleText
                        is RoleplayGenerationAction.Fallback -> result.visibleText
                        else -> error("No visible answer")
                    }
                    assertTrue("${case.label}: blank visible answer", visible.isNotBlank())
                    assertFalse(visible.contains("<|system|>"))
                    Log.i(TAG, "CASE " + JSONObject().apply {
                        put("runId", runId); put("label", case.label); put("question", case.query); put("cutoff", case.cutoff)
                        put("dataset", identity.datasetId); put("fingerprint", identity.fingerprint)
                        put("indexFingerprint", prompt.turnTrace.indexFingerprint)
                        put("vectorCandidates", prompt.turnTrace.external.vectorCandidateIds.joinToString(","))
                        put("authorized", prompt.turnTrace.authorizedFactIds.joinToString(","))
                        put("selected", debug.selectedItems.joinToString(",") { "${it.kind}:${it.id}" })
                        put("rejected", prompt.turnTrace.rejectedFactReasons.joinToString(","))
                        put("gate", when (gate) {
                            is RoleplayAnswerabilityDecision.BlockFutureFact -> "blocked_future"
                            is RoleplayAnswerabilityDecision.BlockUnknownFact -> "blocked_unknown_origin"
                            RoleplayAnswerabilityDecision.Allow -> "allow"
                        })
                        put("attempts", attempts); put("action", action?.javaClass?.simpleName ?: "fixed")
                        put("rawChars", raw.length); put("attemptRecords", attemptRecords); put("visible", visible)
                    }.toString())
                } finally { lease.close() }
            }
        } finally {
            if (engine.state.value is LlamaState.ModelReady) engine.unloadModel()
            if (originalModel.id != model.id) LlamaEngine.setSelectedModel(context, originalModel.id)
        }
    }

    private data class TestCase(val label: String, val cutoff: String, val query: String,
        val expectedId: String? = null, val future: Boolean = false, val followUp: CharacterFollowUpTarget? = null)
    companion object { private const val TAG = "DatabasePhase5" }
}
