package com.example.minicpm_v_demo.harness.attribution

import com.example.minicpm_v_demo.ModelInfo
import com.example.minicpm_v_demo.harness.HarnessCapability
import com.example.minicpm_v_demo.harness.HarnessModelSpec
import com.example.minicpm_v_demo.harness.toHarnessSpec

/**
 * Agent A owns a separate registry from Agent B.  The initial entries are
 * copied from the shared model catalog with Agent A's runtime options.
 */
data class AttributionModelSpec(
    val model: ModelInfo,
    val harnessSpec: HarnessModelSpec,
    val maxOutputTokens: Int = 384,
    val maxContextChars: Int = 6_000,
) {
    init {
        require(maxOutputTokens in 32..1_024) { "invalid_attribution_output_budget" }
        require(maxContextChars >= 1_024) { "invalid_attribution_context_budget" }
        require(isEligible(harnessSpec)) { "attribution_model_must_be_text_only_gguf" }
    }

    companion object {
        fun isEligible(spec: HarnessModelSpec): Boolean =
            spec.capabilities == setOf(HarnessCapability.TEXT) &&
                spec.artifacts.size == 1 &&
                spec.artifacts.single().id == "llm" &&
                spec.artifacts.single().fileName.endsWith(".gguf", ignoreCase = true)
    }
}

data class AttributionModelEntry(
    val model: ModelInfo,
    val harnessSpec: HarnessModelSpec,
    val attributionSpec: AttributionModelSpec?,
)

/** 登记归因模型目录和使用约束，具有归因规格的条目才可供 Agent A 选择。 */
object AttributionModelRegistry {
    // Both agents use the same files. Agent A keeps its own thinking setting.
    private val mirroredModels = ModelInfo.AVAILABLE_MODELS.map { model ->
        model.copy(enableThinking = model.enableThinking && model.id != "minicpm5-2b")
    }

    private val entries: List<AttributionModelEntry> = mirroredModels.map { model ->
        val harnessSpec = model.toHarnessSpec()
        AttributionModelEntry(
            model = model,
            harnessSpec = harnessSpec,
            attributionSpec = if (AttributionModelSpec.isEligible(harnessSpec)) {
                AttributionModelSpec(model, harnessSpec)
            } else {
                null
            },
        )
    }

    init {
        require(mirroredModels.map { it.id }.distinct().size == mirroredModels.size) { "duplicate_attribution_model_id" }
    }

    fun availableEntries(): List<AttributionModelEntry> = entries

    fun availableModelInfos(): List<ModelInfo> = entries.map { it.model }

    fun eligibleEntries(): List<AttributionModelEntry> = entries.filter { it.attributionSpec != null }

    fun findEntry(id: String): AttributionModelEntry? = entries.find { it.model.id == id }

    fun findEligibleEntry(id: String): AttributionModelEntry? = findEntry(id)?.takeIf { it.attributionSpec != null }
}
