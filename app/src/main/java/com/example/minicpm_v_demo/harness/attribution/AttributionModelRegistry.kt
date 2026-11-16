package com.example.minicpm_v_demo.harness.attribution

import com.example.minicpm_v_demo.ModelInfo
import com.example.minicpm_v_demo.harness.HarnessCapability
import com.example.minicpm_v_demo.harness.HarnessModelSpec
import com.example.minicpm_v_demo.harness.toHarnessSpec

/**
 * Agent A owns a separate registry from Agent B.  The initial entries are
 * intentionally copied, not referenced, from ModelInfo.AVAILABLE_MODELS so
 * later Agent-A-only additions do not mutate the chat-model list.
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
    /** Null means this mirrored Agent-B model is intentionally ineligible for Agent A. */
    val attributionSpec: AttributionModelSpec?,
)

/** 登记归因模型目录和使用约束，具有归因规格的条目才可供 Agent A 选择。 */
object AttributionModelRegistry {
    // Keep Agent A's catalog independent of changes to Agent B's lineup.
    private val mirroredModels: List<ModelInfo> = listOf(
        ModelInfo(
            id = "minicpm-v-4",
            displayName = "MiniCPM-V-4 (Q4_K_M)",
            descriptionResName = "model_desc_v4",
            ggufFileName = "ggml-model-Q4_K_M.gguf",
            mmprojFileName = "mmproj-model-f16.gguf",
            hfRepo = "openbmb/MiniCPM-V-4-gguf",
            msRepo = "OpenBMB/MiniCPM-V-4-gguf",
        ),
        ModelInfo(
            id = "minicpm-v-4_6-instruct",
            displayName = "MiniCPM-V-4.6 (Q4_K_M)",
            descriptionResName = "model_desc_v46",
            ggufFileName = "MiniCPM-V-4_6-Q4_K_M.gguf",
            mmprojFileName = "mmproj-model-f16.gguf",
            hfRepo = "openbmb/MiniCPM-V-4.6-gguf",
            msRepo = "OpenBMB/MiniCPM-V-4.6-gguf",
            ggufMd5 = "fd778481dd56b6036dd8f9cf7c1519cf",
            mmprojMd5 = "54aea6e04d752f47309a48f12795a1a3",
        ),
        ModelInfo(
            id = "minicpm5-0.9b",
            displayName = "MiniCPM5-1B (Q4_K_M)",
            descriptionResName = "model_desc_minicpm5",
            ggufFileName = "MiniCPM5-1B-Q4_K_M.gguf",
            hfRepo = "openbmb/MiniCPM5-1B-GGUF",
            msRepo = "OpenBMB/MiniCPM5-1B-GGUF",
        ),
        ModelInfo(
            id = "minicpm5-2b",
            displayName = "MiniCPM5-2B (Q4_K_M)",
            descriptionResName = "model_desc_minicpm5_2b",
            ggufFileName = "MiniCPM5-2B-Q4_K_M.gguf",
            msRepo = "OpenBMB/MiniCPM5-2B-gguf",
            enableThinking = false,
        ),
        ModelInfo(
            id = "voxcpm2",
            displayName = "VoxCPM2",
            descriptionResName = "model_desc_voxcpm2",
            ggufFileName = "VoxCPM2-BaseLM-Q4_K_M.gguf",
            acousticFileName = "VoxCPM2-Acoustic-F16.gguf",
            hfRepo = "tc-mb/MiniCPM-V-Apps-gguf",
            directGgufUrl = "https://huggingface.co/tc-mb/MiniCPM-V-Apps-gguf/resolve/main/VoxCPM2-BaseLM-Q4_K_M.gguf",
            directAcousticUrl = "https://huggingface.co/tc-mb/MiniCPM-V-Apps-gguf/resolve/main/VoxCPM2-Acoustic-F16.gguf",
            ggufMd5 = "d8cd571526464d225187d326caa289be",
            acousticMd5 = "0f16229cfffe935102d21433f6969f8b",
        ),
        ModelInfo(
            id = "llama-3.2-1b-instruct",
            displayName = "Llama 3.2 1B Instruct (Q4_K_M)",
            descriptionResName = "model_desc_llama32_1b",
            ggufFileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            directGgufUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        ),
        ModelInfo(
            id = "qwen3-0.6b",
            displayName = "qwen3-0.6b",
            descriptionResName = "验证模型",
            ggufFileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
            hfRepo = "unsloth/Qwen3.5-0.8B-GGUF",
            msRepo = "unsloth/Qwen3.5-0.8B-GGUF",
        ),
    ) + ModelInfo.POPULAR_TEXT_MODELS.map { it.copy() }

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
