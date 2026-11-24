package com.example.minicpm_v_demo.harness.attribution

import com.example.minicpm_v_demo.ModelInfo
import com.example.minicpm_v_demo.harness.HarnessCapability
import com.example.minicpm_v_demo.harness.HarnessDownloadSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionModelRegistryTest {
    @Test fun catalogsShareArtifactMetadataAndKeepIndependentRuntimeOptions() {
        val agentB = ModelInfo.AVAILABLE_MODELS
        val agentA = AttributionModelRegistry.availableModelInfos()

        val expected = agentB.map { model -> model.copy(enableThinking = false) }
        assertEquals(expected, agentA)
        assertNotSame(agentB, agentA)
        agentB.indices.forEach { index -> assertNotSame(agentB[index], agentA[index]) }
    }

    @Test fun onlyTextOnlySingleGgufModelsCanBeSelectedForAttribution() {
        val eligible = AttributionModelRegistry.eligibleEntries()

        assertEquals(
            listOf(
                "minicpm5-0.9b", "minicpm5-2b", "llama-3.2-1b-instruct", "qwen3-0.6b",
                "qwen3.5-2b", "llama-3.2-3b-instruct", "qwen3.5-4b",
            ),
            eligible.map { it.model.id },
        )
        assertTrue(eligible.all { entry ->
            entry.harnessSpec.capabilities == setOf(HarnessCapability.TEXT) &&
                entry.harnessSpec.artifacts.single().id == "llm" &&
                entry.harnessSpec.artifacts.single().fileName.endsWith(".gguf") &&
                entry.attributionSpec != null
        })
        assertNull(AttributionModelRegistry.findEligibleEntry("minicpm-v-4"))
        assertNull(AttributionModelRegistry.findEligibleEntry("voxcpm2"))
        assertEquals(AttributionModelRegistry.availableEntries(), eligible)
        eligible.forEach { entry ->
            val source = entry.harnessSpec.downloadSources.single()
            assertEquals(HarnessDownloadSourceType.MODELSCOPE, source.type)
            assertEquals(entry.model.downloadUrl, source.url)
            assertTrue(entry.model.downloadUrl.startsWith("https://www.modelscope.cn/models/"))
        }
    }
}
