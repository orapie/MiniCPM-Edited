package com.example.minicpm_v_demo.harness

import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.ModelInfo

enum class HarnessCapability {
    TEXT
}

enum class HarnessModelFamily {
    MINICPM_TEXT,
    LLAMA,
    QWEN,
    OTHER
}

enum class HarnessDownloadSourceType {
    MODELSCOPE
}

data class HarnessArtifact(
    val id: String,
    val fileName: String,
    val required: Boolean = true,
    val remotePath: String? = null,
    val md5: String? = null
)

data class HarnessDownloadSource(
    val artifactId: String,
    val type: HarnessDownloadSourceType,
    val repo: String? = null,
    val branch: String? = null,
    val remotePath: String? = null,
    val url: String? = null
)

data class HarnessRuntimeHints(
    val defaultPredictLength: Int = LlamaEngine.DEFAULT_PREDICT_LENGTH,
    val recommendedThreads: Int = 4,
    val contextSize: Int? = null,
    val supportsSystemPrompt: Boolean = true
)

/** 描述推理所需文件、下载来源、模型能力和运行时参数。 */
data class HarnessModelSpec(
    val id: String,
    val displayName: String,
    val family: HarnessModelFamily,
    val capabilities: Set<HarnessCapability>,
    val artifacts: List<HarnessArtifact>,
    val downloadSources: List<HarnessDownloadSource>,
    val runtimeHints: HarnessRuntimeHints
)

/** 用同一份模型配置描述文本能力、权重文件和下载来源。 */
fun ModelInfo.toHarnessSpec(): HarnessModelSpec = HarnessModelSpec(
    id = id,
    displayName = displayName,
    family = when {
        id.startsWith("minicpm5") -> HarnessModelFamily.MINICPM_TEXT
        id.startsWith("llama") -> HarnessModelFamily.LLAMA
        id.startsWith("qwen") -> HarnessModelFamily.QWEN
        else -> HarnessModelFamily.OTHER
    },
    capabilities = setOf(HarnessCapability.TEXT),
    artifacts = listOf(HarnessArtifact(id = "llm", fileName = ggufFileName, remotePath = ggufFileName)),
    downloadSources = listOf(
        HarnessDownloadSource(
            artifactId = "llm",
            type = HarnessDownloadSourceType.MODELSCOPE,
            repo = msRepo,
            branch = ModelInfo.MODELSCOPE_BRANCH,
            remotePath = ggufFileName,
            url = downloadUrl,
        )
    ),
    runtimeHints = HarnessRuntimeHints(contextSize = 4096),
)
