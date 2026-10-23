package com.example.minicpm_v_demo.harness.character

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Verifies that query-aware character-card context reaches a real V4.6 turn. */
@RunWith(AndroidJUnit4::class)
class CharacterProfileNativeInferenceTest {
    @Test
    fun v46AnswersCharacterOriginFromRelevantProfile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AssetBootstrapper.bootstrap(context)
        LlamaEngine.setSelectedModel(context, "minicpm-v-4_6-instruct")
        val engine = LlamaEngine.getInstance(context)
        awaitReadyOrLoad(engine, context)

        val prompt = AndroidRagOrchestrator.fromContext(context).compile(
            userInput = "你从何而来？",
            mode = RagMode.CHARACTER_RAG,
            characterId = "lu_jiangxian",
            storyCutoff = "evt-010",
            maxCharacterChars = 900,
            knowledgeGraphEnabled = true,
            modelFamily = HarnessModelFamily.MINICPM_VISION,
        )
        val debug = prompt.characterDebug as CharacterPromptDebug
        assertTrue(debug.profileInjectedChars > 0)
        assertTrue(prompt.rendered.splitPrompt.systemPrompt.contains("你的来历："))

        engine.clearContext()
        engine.setSystemPrompt(prompt.rendered.splitPrompt.systemPrompt)
        val output = buildString {
            engine.sendUserPrompt(prompt.rendered.splitPrompt.userPrompt, predictLength = 64).collect(::append)
        }.trim()

        assertTrue("empty profile answer", output.isNotBlank())
        assertFalse("profile answer fell back to unknown: $output", output.contains("尚不清楚"))
        assertFalse("profile answer leaked a system token: $output", output.contains("<|system|>"))
        assertTrue("profile answer did not use origin context: $output", output.contains("鉴") || output.contains("太阴"))
    }

    @Test
    fun v46UsesCurrentStateAndKeepsUnrecordedPossessionsConservative() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AssetBootstrapper.bootstrap(context)
        LlamaEngine.setSelectedModel(context, "minicpm-v-4_6-instruct")
        val engine = LlamaEngine.getInstance(context)
        awaitReadyOrLoad(engine, context)
        val orchestrator = AndroidRagOrchestrator.fromContext(context)

        val statePrompt = orchestrator.compile(
            userInput = "你现在在哪里？",
            mode = RagMode.CHARACTER_RAG,
            characterId = "lu_jiangxian",
            storyCutoff = "evt-018",
            maxCharacterChars = 900,
            modelFamily = HarnessModelFamily.MINICPM_VISION,
        )
        assertTrue((statePrompt.characterDebug as CharacterPromptDebug).stateInjectedChars > 0)
        assertTrue(statePrompt.rendered.splitPrompt.systemPrompt.contains("你当前处境："))

        engine.clearContext()
        engine.setSystemPrompt(statePrompt.rendered.splitPrompt.systemPrompt)
        val stateOutput = buildString {
            engine.sendUserPrompt(statePrompt.rendered.splitPrompt.userPrompt, predictLength = 64).collect(::append)
        }.trim()
        assertTrue("empty state answer", stateOutput.isNotBlank())
        assertFalse("state answer leaked a system token: $stateOutput", stateOutput.contains("<|system|>"))

        val possessionPrompt = orchestrator.compile(
            userInput = "你手里有什么？",
            mode = RagMode.CHARACTER_RAG,
            characterId = "lu_jiangxian",
            storyCutoff = "evt-018",
            maxCharacterChars = 900,
            modelFamily = HarnessModelFamily.MINICPM_VISION,
        )
        val possessionDebug = possessionPrompt.characterDebug as CharacterPromptDebug
        assertEquals("no_carried_items", possessionDebug.possessionRoute)
        assertTrue(possessionPrompt.rendered.splitPrompt.systemPrompt.contains("当前资料未记录可确认的随身物品。"))

        engine.clearContext()
        engine.setSystemPrompt(possessionPrompt.rendered.splitPrompt.systemPrompt)
        val possessionOutput = buildString {
            engine.sendUserPrompt(possessionPrompt.rendered.splitPrompt.userPrompt, predictLength = 64).collect(::append)
        }.trim()
        assertTrue("empty possession answer", possessionOutput.isNotBlank())
        assertFalse("possession answer leaked a system token: $possessionOutput", possessionOutput.contains("<|system|>"))
    }

    private suspend fun awaitReadyOrLoad(engine: LlamaEngine, context: android.content.Context) {
        val initial = withTimeout(30_000) {
            engine.state.filter { it is LlamaState.Initialized || it is LlamaState.ModelReady || it is LlamaState.Error }.first()
        }
        if (initial is LlamaState.Error) error("Native engine failed to initialize: ${initial.exception.message}")
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
}
