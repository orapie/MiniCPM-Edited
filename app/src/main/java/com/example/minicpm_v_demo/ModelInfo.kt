package com.example.minicpm_v_demo

import android.content.Context

/** A text model distributed as a single GGUF file on ModelScope. */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val descriptionResName: String,
    val ggufFileName: String,
    val msRepo: String,
    val enableThinking: Boolean = true,
) {
    fun getDescription(context: Context): String {
        val resId = context.resources.getIdentifier(descriptionResName, "string", context.packageName)
        return if (resId != 0) context.getString(resId) else descriptionResName
    }

    val downloadUrl: String
        get() = "https://www.modelscope.cn/models/$msRepo/resolve/$MODELSCOPE_BRANCH/$ggufFileName"

    companion object {
        const val MODELSCOPE_BRANCH = "master"

        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                // Retain the original directory and preference key when upgrading.
                id = "minicpm5-0.9b",
                displayName = "MiniCPM5-1B (Q4_K_M)",
                descriptionResName = "model_desc_minicpm5",
                ggufFileName = "MiniCPM5-1B-Q4_K_M.gguf",
                msRepo = "OpenBMB/MiniCPM5-1B-GGUF",
            ),
            ModelInfo(
                id = "minicpm5-2b",
                displayName = "MiniCPM5-2B (Q4_K_M)",
                descriptionResName = "model_desc_minicpm5_2b",
                ggufFileName = "MiniCPM5-2B-Q4_K_M.gguf",
                msRepo = "OpenBMB/MiniCPM5-2B-gguf",
            ),
            ModelInfo(
                id = "llama-3.2-1b-instruct",
                displayName = "Llama 3.2 1B Instruct (Q4_K_M)",
                descriptionResName = "model_desc_llama32_1b",
                ggufFileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                msRepo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            ),
            ModelInfo(
                id = "qwen3-0.6b",
                displayName = "Qwen3.5 0.8B (Q4_K_M)",
                descriptionResName = "model_desc_qwen35_08b",
                ggufFileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
                msRepo = "unsloth/Qwen3.5-0.8B-GGUF",
            ),
            ModelInfo(
                id = "qwen3.5-2b",
                displayName = "Qwen3.5 2B (Q4_K_M)",
                descriptionResName = "model_desc_qwen35_2b",
                ggufFileName = "Qwen3.5-2B-Q4_K_M.gguf",
                msRepo = "unsloth/Qwen3.5-2B-GGUF",
                enableThinking = false,
            ),
            ModelInfo(
                id = "llama-3.2-3b-instruct",
                displayName = "Llama 3.2 3B Instruct (Q4_K_M)",
                descriptionResName = "model_desc_llama32_3b",
                ggufFileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                msRepo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            ),
            ModelInfo(
                id = "qwen3.5-4b",
                displayName = "Qwen3.5 4B (Q4_K_M)",
                descriptionResName = "model_desc_qwen35_4b",
                ggufFileName = "Qwen3.5-4B-Q4_K_M.gguf",
                msRepo = "unsloth/Qwen3.5-4B-GGUF",
                enableThinking = false,
            ),
        )

        val DEFAULT_MODEL = AVAILABLE_MODELS.first()
    }
}
