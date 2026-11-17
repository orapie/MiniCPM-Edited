package com.example.minicpm_v_demo.harness.character

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.harness.HarnessChatRequest
import com.example.minicpm_v_demo.harness.LlamaBackendAdapter
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper
import com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestrator
import com.example.minicpm_v_demo.harness.rag.RagMode
import com.example.minicpm_v_demo.harness.rag.RagPromptStage
import com.example.minicpm_v_demo.harness.toHarnessSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoleplayRelationshipNativeSmokeTest {
    @Test fun actualAgentBRequestRespectsTheCardCutoff() = runBlocking {
        assertTrue(AssetBootstrapper.awaitStartup(120_000))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LlamaEngine.getSelectedModel(context)
        val weights = LlamaEngine.modelFile(context, model)
        check(weights.isFile) { "Selected model unavailable: ${model.ggufFileName}" }
        val engine = LlamaEngine.getInstance(context)
        val state = withTimeout(30_000) {
            engine.state.first { it is LlamaState.Initialized || it is LlamaState.ModelReady || it is LlamaState.Error }
        }
        check(state !is LlamaState.Error) { state.toString() }
        if (state is LlamaState.Initialized) engine.loadModel(weights.absolutePath)
        check(engine.state.value is LlamaState.ModelReady)

        val question = "你怎么看史强？"
        val fact = "高度信任，依赖其现实判断"
        val evidence = "three_body_2.txt:L2921"
        val backend = LlamaBackendAdapter(context)
        val coordinator = RoleplayGenerationCoordinator()
        AndroidRagOrchestrator.fromContext(context).use { orchestrator ->
            for ((cutoff, expected) in listOf("evt-001" to false, "evt-125" to true)) {
                val compiled = orchestrator.compile(question, RagMode.CHARACTER_RAG, "luo_ji", cutoff,
                    modelFamily = model.toHarnessSpec().family)
                val (system, user) = compiled.rendered.splitPrompt
                val trace = checkNotNull(compiled.stageLogs.single { it.stage == RagPromptStage.BACKEND_REQUEST }.snapshot)
                assertEquals(system, trace.systemPrompt)
                assertEquals(user, trace.userPrompt)
                assertFalse(system.contains(fact))
                assertEquals(expected, user.contains(fact))
                assertEquals(expected, user.contains(evidence))
                assertTrue(RoleplayAnswerabilityGate.evaluate(compiled.characterDebug as? CharacterPromptDebug, question)
                    is RoleplayAnswerabilityDecision.Allow)

                var request = HarnessChatRequest(userPrompt = user, systemPrompt = system, observationId = "relation-$cutoff")
                for (attempt in 0..RoleplayGenerationCoordinator.MAX_REPAIR_ATTEMPTS) {
                    Log.i(TAG, "BACKEND_REQUEST model=${model.id} cutoff=$cutoff attempt=$attempt\nsystem=${request.systemPrompt}\nuser=${request.userPrompt}")
                    val reply = withTimeout(240_000) {
                        buildString { backend.sendChatPrompt(request, 384).collect { append(it) } }.trim()
                    }
                    assertTrue("Empty model reply at $cutoff", reply.isNotBlank())
                    val action = coordinator.resolve(attempt, reply, "罗辑", question)
                    Log.i(TAG, "RESULT cutoff=$cutoff attempt=$attempt\nraw=$reply\naction=$action")
                    if (action !is RoleplayGenerationAction.Retry) break
                    request = request.copy(
                        systemPrompt = request.systemPrompt + "\n\n" + RoleplayGenerationCoordinator.repairInstruction(action.ruleIds),
                        observationId = "relation-$cutoff-retry",
                    )
                }
            }
        }
    }

    companion object { private const val TAG = "RelationshipNativeSmoke" }
}
