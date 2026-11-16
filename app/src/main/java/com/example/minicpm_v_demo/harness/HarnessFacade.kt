package com.example.minicpm_v_demo.harness

import android.content.Context
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 把模型存储、下载和推理后端组合成界面使用的入口。 */
class HarnessFacade private constructor(
    private val modelStore: LlamaModelStore,
    private val backend: HarnessBackend,
    private val downloadManager: LlamaDownloadManager
) {

    companion object {
        fun getInstance(context: Context): HarnessFacade =
            HarnessFacade(
                modelStore = LlamaModelStore(context.applicationContext),
                backend = LlamaBackendAdapter(context.applicationContext),
                downloadManager = LlamaDownloadManager(context.applicationContext)
            )
    }

    val state: StateFlow<LlamaState>
        get() = backend.state

    val isVisionSupported: Boolean
        get() = backend.isVisionSupported

    val isVideoUnderstandingSupported: Boolean
        get() = backend.isVideoUnderstandingSupported

    fun migrateLegacyLayoutIfNeeded() {
        modelStore.migrateLegacyLayoutIfNeeded()
    }

    fun getSelectedModel(): ModelInfo = modelStore.getSelectedModel()

    fun getSelectedModelSpec(): HarnessModelSpec = modelStore.getSelectedModelSpec()

    fun availableModels(): List<ModelInfo> = HarnessModelRegistry.availableModelInfos()

    fun availableModelSpecs(): List<HarnessModelSpec> = HarnessModelRegistry.availableSpecs()

    fun setSelectedModel(modelId: String) {
        modelStore.setSelectedModel(modelId)
    }

    fun markModelSwitched() {
        modelStore.markModelSwitched()
    }

    fun consumeModelSwitched(): Boolean = modelStore.consumeModelSwitched()

    fun isSelectedModelDownloaded(): Boolean = modelStore.isSelectedModelDownloaded()

    fun getImageMaxSliceNums(): Int = modelStore.getImageMaxSliceNums()

    suspend fun setImageMaxSliceNums(n: Int) {
        backend.setImageMaxSliceNums(n)
    }

    fun getSelectedModelAvailability(): HarnessModelAvailability =
        modelStore.getSelectedModelAvailability()

    /** 将所选模型的本地文件解析为后端加载参数，包括可选的视觉投影器。 */
    suspend fun loadSelectedModel() {
        val files = modelStore.getSelectedModelFiles()
        val llmFile = files.artifactFiles["llm"]
            ?: throw IllegalStateException("Missing llm artifact path for ${files.model.id}")
        require(llmFile.exists()) { "File not found: ${llmFile.absolutePath}" }
        val mmprojArg = files.artifactFiles["vision_projector"]?.takeIf { it.exists() }?.absolutePath
        backend.loadModel(llmFile.absolutePath, mmprojArg)
    }

    suspend fun unloadModel() {
        backend.unloadModel()
    }

    suspend fun clearContext() {
        backend.clearContext()
    }

    suspend fun prefillImage(imageData: ByteArray) {
        backend.prefillImage(imageData)
    }

    suspend fun prefillVideoFrames(
        frames: List<ByteArray>,
        onProgress: suspend (current: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        backend.prefillVideoFrames(frames, onProgress)
    }

    suspend fun setSystemPrompt(prompt: String) {
        backend.setSystemPrompt(prompt)
    }

    fun sendUserPrompt(
        message: String,
        predictLength: Int = LlamaEngine.DEFAULT_PREDICT_LENGTH
    ): Flow<String> = backend.sendUserPrompt(message, predictLength)

    fun sendChatPrompt(
        request: HarnessChatRequest,
        predictLength: Int = LlamaEngine.DEFAULT_PREDICT_LENGTH,
    ): Flow<String> = backend.sendChatPrompt(request, predictLength)

    fun cancelGeneration() {
        backend.cancelGeneration()
    }

    fun resetToInitialized() {
        backend.resetToInitialized()
    }

    fun destroy() {
        backend.destroy()
    }

    fun startDownloadService(modelId: String = getSelectedModel().id) {
        downloadManager.startForegroundDownload(modelId)
    }

    fun selectedModelArtifactNames(): List<String> =
        modelStore.selectedModelArtifactNames()

    fun deleteSelectedModelFiles(): Boolean = modelStore.deleteSelectedModelFiles()
}
