package com.example.minicpm_v_demo.harness.character

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.harness.HarnessChatRequest
import com.example.minicpm_v_demo.harness.toHarnessSpec
import com.example.minicpm_v_demo.harness.LlamaBackendAdapter
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper
import com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestrator
import com.example.minicpm_v_demo.harness.rag.RagMode
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** One synthetic question against the currently selected model, without changing sessions. */
@RunWith(AndroidJUnit4::class)
class RoleplayPerspectiveNativeSmokeTest {
    @Test fun currentModelAnswersFromCharacterPerspective() = runBlocking {
        assertTrue(AssetBootstrapper.awaitStartup(120_000))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LlamaEngine.getSelectedModel(context)
        val weights = File(LlamaEngine.modelDirFor(context, model), model.ggufFileName)
        check(weights.isFile) { "selected model unavailable" }
        val engine = LlamaEngine.getInstance(context)
        val state = withTimeout(30_000) {
            engine.state.filter { it is LlamaState.Initialized || it is LlamaState.ModelReady || it is LlamaState.Error }.first()
        }
        check(state !is LlamaState.Error)
        if (state is LlamaState.Initialized) engine.loadModel(weights.absolutePath)
        check(engine.state.value is LlamaState.ModelReady)
        AndroidRagOrchestrator.fromContext(context).use { orchestrator ->
            val question = "雪地工程的目的是什么？"
            val compiled = orchestrator.compile(question, RagMode.CHARACTER_RAG, characterId = "luo_ji",
                storyCutoff = "evt-125", modelFamily = model.toHarnessSpec().family)
            assertTrue(compiled.turnTrace.external.vectorSearchExecuted)
            assertTrue(RoleplayAnswerabilityGate.evaluate(compiled.characterDebug as? CharacterPromptDebug, question) is RoleplayAnswerabilityDecision.Allow)
            val repair = InstrumentationRegistry.getArguments().getString("repair") == "true"
            val system = compiled.rendered.splitPrompt.systemPrompt + if (repair) "\n" + RoleplayGenerationCoordinator.REPAIR_INSTRUCTION else ""
            val user = compiled.rendered.splitPrompt.userPrompt
            Log.i(TAG, "BACKEND_REQUEST system=$system user=$user")
            val raw = withTimeout(180_000) {
                buildString { LlamaBackendAdapter(context).sendChatPrompt(HarnessChatRequest(systemPrompt = system, userPrompt = user), 96)
                    .collect { append(it) } }.trim()
            }
            val action = RoleplayGenerationCoordinator().resolve(if (repair) 1 else 0, raw, "罗辑", question)
            val visible = when (action) {
                is RoleplayGenerationAction.Deliver -> action.visibleText
                is RoleplayGenerationAction.Fallback -> action.visibleText
                is RoleplayGenerationAction.Retry -> "retry_pending"
            }
            Log.i(TAG, "RESULT model=${model.id} repair=$repair raw=$raw decision=${action.javaClass.simpleName} visible=$visible")
            assertTrue(raw.isNotBlank())
        }
    }
    companion object { private const val TAG = "PerspectiveNativeSmoke" }
}
