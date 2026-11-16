package com.example.minicpm_v_demo.harness.attribution

import com.example.minicpm_v_demo.ModelInfo
import com.example.minicpm_v_demo.harness.HarnessCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionModelRegistryTest {
    @Test fun catalogsShareArtifactMetadataAndKeepIndependentRuntimeOptions() {
        val agentB = ModelInfo.AVAILABLE_MODELS
        val agentA = AttributionModelRegistry.availableModelInfos()

        val expected = agentB.map { model ->
            if (model.id == "minicpm5-2b") model.copy(enableThinking = false) else model
        }
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
        assertFalse(AttributionModelRegistry.availableEntries().all { it.attributionSpec != null })
    }
}
