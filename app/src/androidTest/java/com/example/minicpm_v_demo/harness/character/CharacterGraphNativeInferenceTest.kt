package com.example.minicpm_v_demo.harness.character

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper
import com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestrator
import com.example.minicpm_v_demo.harness.rag.RagMode
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A real-device acceptance probe for the graph A/B path.  It deliberately
 * uses the installed model and the exact system/user split used by
 * [MainActivity], but does not write a conversation or change graph assets.
 *
 * Run only after preserving or restoring the model files.  Do not use
 * `connectedDebugAndroidTest` against a device whose model lives in this
 * app's private files directory: AGP may reinstall the target APK and erase
 * that directory. Build the test APK, install it with `adb install -r`, then
 * invoke this class through `adb shell am instrument` instead.
 */
@RunWith(AndroidJUnit4::class)
class CharacterGraphNativeInferenceTest {
    @Test
    fun v46GraphOnOffUsesSameQuestionAndProducesVisibleAnswers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AssetBootstrapper.bootstrap(context)
        // Instrumentation may start in a separate process with fresh model
        // preferences.  Pin the already-installed model so the benchmark is
        // never silently run against the registry default.
        LlamaEngine.setSelectedModel(context, "minicpm-v-4_6-instruct")
        val engine = LlamaEngine.getInstance(context)
        awaitReadyOrLoad(engine, context)

        val model = LlamaEngine.getSelectedModel(context)
        assertEquals("minicpm-v-4_6-instruct", model.id)
        val orchestrator = AndroidRagOrchestrator.fromContext(context)
        // This is a true causal question whose V2 fact directly states the
        // reason, unlike a rhetorical question that only records a challenge.
        val query = "你为什么停止拼合七枚鉴身碎片？"

        val graphOn = orchestrator.compile(
            userInput = query,
            mode = RagMode.CHARACTER_RAG,
            characterId = "xuan_an",
            storyCutoff = "evt-018",
            maxCharacterChars = 900,
            knowledgeGraphEnabled = true,
            modelFamily = HarnessModelFamily.MINICPM_VISION,
        )
        val graphOff = orchestrator.compile(
            userInput = query,
            mode = RagMode.CHARACTER_RAG,
            characterId = "xuan_an",
            storyCutoff = "evt-018",
            maxCharacterChars = 900,
            knowledgeGraphEnabled = false,
            modelFamily = HarnessModelFamily.MINICPM_VISION,
        )

        val onDebug = graphOn.characterDebug as CharacterPromptDebug
        val offDebug = graphOff.characterDebug as CharacterPromptDebug
        assertEquals("story", onDebug.knowledgeQueryIntent)
        assertEquals("ready", onDebug.knowledgeGraphStatus)
        assertEquals("disabled", offDebug.knowledgeGraphStatus)
        assertTrue(offDebug.legacyStoryRetrievalExecuted)

        val onTrials = (1..3).map { index ->
            runTrial(engine, "graph_on_$index", graphOn, onDebug)
        }
        val offTrials = (1..3).map { index ->
            runTrial(engine, "graph_off_$index", graphOff, offDebug)
        }
        assertTrue(onTrials.all { it.response.isNotBlank() })
        assertTrue(offTrials.all { it.response.isNotBlank() })
        // Phase 4C acceptance: with an authorized, directly relevant V2 fact,
        // most sampled replies must state the fact instead of evading it with a
        // generic possibility or a question back to the player.
        assertTrue(
            "graph-on replies did not directly use the authorized answer: ${onTrials.map { it.response }}",
            onTrials.count(::usesAuthorizedDirectAnswer) >= 2,
        )
        // The model output is measured and reported; this records a leak signal
        // rather than making a sampling-dependent quality assertion.
        assertTrue(onTrials.none { it.response.contains("<|system|>") })
        assertTrue(offTrials.none { it.response.contains("<|system|>") })
    }

    private fun usesAuthorizedDirectAnswer(result: TrialResult): Boolean {
        val text = result.response
        val hasGroundedReason = listOf("避免", "消亡", "归位", "碎片", "求活").any(text::contains)
        val isEvasive = listOf("可能", "怎么看", "想知道真相吗", "需要仔细").any(text::contains)
        return hasGroundedReason && !isEvasive
    }

    private suspend fun awaitReadyOrLoad(engine: LlamaEngine, context: android.content.Context) {
        val initial = withTimeout(30_000) {
            engine.state.filter { it is LlamaState.Initialized || it is LlamaState.ModelReady || it is LlamaState.Error }.first()
        }
        if (initial is LlamaState.Error) {
            error("Native engine failed to initialize: ${initial.exception.message}")
        }
        if (initial is LlamaState.Initialized) {
            val model = LlamaEngine.getSelectedModel(context)
            val modelPath = File(LlamaEngine.modelDirFor(context, model), model.ggufFileName)
            val mmprojPath = model.mmprojFileName?.let { File(modelPath.parentFile, it) }
            check(modelPath.isFile) { "Missing installed model: $modelPath" }
            check(mmprojPath == null || mmprojPath.isFile) { "Missing installed mmproj: $mmprojPath" }
            engine.loadModel(modelPath.absolutePath, mmprojPath?.absolutePath)
        }
        check(engine.state.value is LlamaState.ModelReady) { "Engine not ready: ${engine.state.value}" }
    }

    private suspend fun runTrial(
        engine: LlamaEngine,
        label: String,
        prompt: com.example.minicpm_v_demo.harness.rag.CompiledAndroidPrompt,
        debug: CharacterPromptDebug,
    ): TrialResult {
        engine.clearContext()
        val systemTokens = engine.countPromptTokens(prompt.rendered.splitPrompt.systemPrompt)
        val userTokens = engine.countPromptTokens(prompt.rendered.splitPrompt.userPrompt)
        val startedAt = System.nanoTime()
        engine.setSystemPrompt(prompt.rendered.splitPrompt.systemPrompt)
        val systemMs = elapsedMs(startedAt)
        val userStartedAt = System.nanoTime()
        var firstTokenMs: Long? = null
        val output = buildString {
            engine.sendUserPrompt(prompt.rendered.splitPrompt.userPrompt, predictLength = 64).collect { token ->
                if (firstTokenMs == null) firstTokenMs = elapsedMs(userStartedAt)
                append(token)
            }
        }
        val totalUserMs = elapsedMs(userStartedAt)
        val result = TrialResult(output.trim(), systemMs, firstTokenMs ?: totalUserMs, totalUserMs)
        Log.i(
            TAG,
            "native_graph_ab label=$label intent=${debug.knowledgeQueryIntent} " +
                "v2=${debug.knowledgeGraphStatus}/${debug.knowledgeGraphInjectedChars} " +
                "legacyStory=${debug.legacyStoryRetrievalExecuted} " +
                "promptChars=${prompt.rendered.splitPrompt.systemPrompt.length}/${prompt.rendered.splitPrompt.userPrompt.length} " +
                "tokens=$systemTokens/$userTokens systemMs=${result.systemMs} " +
                "ttftMs=${result.ttftMs} userTotalMs=${result.userTotalMs} " +
                "output=${result.response.replace("\n", "\\n")}",
        )
        return result
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private data class TrialResult(
        val response: String,
        val systemMs: Long,
        val ttftMs: Long,
        val userTotalMs: Long,
    )

    private companion object {
        const val TAG = "CharacterGraphNative"
    }
}
