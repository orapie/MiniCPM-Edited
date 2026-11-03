package com.example.minicpm_v_demo.harness.data.database

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper
import com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestrator
import com.example.minicpm_v_demo.harness.rag.RagMode
import com.example.minicpm_v_demo.harness.rag.VectorRetrievalFeatureFlags
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Small real-device smoke check; no conversation model or session is modified. */
@RunWith(AndroidJUnit4::class)
class BgeRetrievalSmokeDeviceTest {
    @Test fun semanticAndHybridReachPrompt() {
        assertTrue(AssetBootstrapper.awaitStartup(120_000))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundle = DatabaseRuntime.requireBundle()
        val template = File(context.filesDir, "program/prompts/roleplay_system.prompt")
        for (hybrid in listOf(false, true)) {
            AndroidRagOrchestrator.fromBundle(bundle, template,
                VectorRetrievalFeatureFlags(hybridRetrieval = hybrid)).use { orchestrator ->
                val general = orchestrator.compile("罗辑的职业是什么？", RagMode.RAG, topK = 3)
                assertTrue("vector failure: ${general.turnTrace.external.vectorFailureCode}", general.turnTrace.external.vectorSearchExecuted)
                assertEquals("bge_onnx", general.turnTrace.external.vectorBackend)
                assertEquals(if (hybrid) "hybrid_rrf" else "semantic_or_hash", general.turnTrace.external.retrievalMode)
                assertTrue(general.turnTrace.external.vectorCandidateIds.isNotEmpty())
                assertTrue(general.sources.isNotEmpty())
                if (hybrid) assertTrue(general.turnTrace.external.vectorCandidateScoreDetails.any {
                    it.contains("lexicalRank=") && !it.contains("lexicalRank=none")
                })
                Log.i("BgeRetrievalSmoke", "general ${general.turnTrace.toLogLine()}")
                val character = orchestrator.compile("罗辑的职业是什么？", RagMode.CHARACTER_RAG,
                    characterId = "shi_qiang", storyCutoff = "evt-118", topK = 3)
                assertTrue("vector failure: ${character.turnTrace.external.vectorFailureCode}", character.turnTrace.external.vectorSearchExecuted)
                assertTrue(character.turnTrace.external.vectorCandidateIds.isNotEmpty())
                val cutoff = bundle.validated.cutoffs.getValue("evt-118")
                assertTrue(character.sources.isNotEmpty())
                assertTrue(character.sources.all { it.end.toLong() <= cutoff })
                Log.i("BgeRetrievalSmoke", "character ${character.turnTrace.toLogLine()}")
                assertTrue(character.rendered.splitPrompt.userPrompt.contains("罗辑的职业是什么"))
            }
        }
    }
}
