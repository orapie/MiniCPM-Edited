package com.example.minicpm_v_demo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

internal class AttributionDeadlineExceeded : RuntimeException()

/**
 * Agent A's text-only GGUF runtime.
 *
 * Native calls are served by `libminicpm_attribution.so`, a second JNI shared
 * object that has its own model/context/KV/sampler globals. It must be used
 * only after Agent B has initialized llama.cpp's process-wide backend.
 */
class AttributionLlamaEngine private constructor(context: Context) {
    private val appContext = context.applicationContext

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var initialized = false
    private val _loadedModelPath = MutableStateFlow<String?>(null)
    val loadedModelPath = _loadedModelPath.asStateFlow()

    suspend fun loadTextModel(model: ModelInfo, modelFile: File) {
        awaitBackend()
        withContext(dispatcher) {
            loadTextModelOnDispatcher(model, modelFile, reload = true)
        }
    }

    private fun loadTextModelOnDispatcher(model: ModelInfo, modelFile: File, reload: Boolean) {
        require(modelFile.isFile && modelFile.canRead()) { "attribution_gguf_unavailable" }
        ensureInitialized()
        if (!reload && _loadedModelPath.value == modelFile.absolutePath) return
        unloadLoadedModel()
        check(load(modelFile.absolutePath) == 0) { "attribution_model_load_failed" }
        setEnableThinkingNative(model.enableThinking)
        check(prepare() == 0) { "attribution_model_prepare_failed" }
        _loadedModelPath.value = modelFile.absolutePath
        Log.i(TAG, "Agent A GGUF ready: ${model.id}")
    }

    /** 在同一串行任务中加载模型并完成归因，避免管理页的操作跨入生成过程。 */
    suspend fun generate(
        model: ModelInfo,
        modelFile: File,
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
        grammar: String,
        deadlineNanos: Long,
    ): String = withContext(dispatcher) {
        loadTextModelOnDispatcher(model, modelFile, reload = false)
        require(systemPrompt.isNotBlank() && userPrompt.isNotBlank()) { "attribution_prompt_required" }
        require(maxOutputTokens in 1..1_024) { "attribution_output_limit_invalid" }
        val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000
        if (remainingMillis <= 0) throw AttributionDeadlineExceeded()
        try {
            configureRequest(remainingMillis, grammar)
            fullReset()
            val systemStatus = processSystemPrompt(systemPrompt)
            if (requestAborted()) throw AttributionDeadlineExceeded()
            check(systemStatus == 0) { "attribution_system_prefill_failed" }
            val userStatus = processUserPrompt(userPrompt, maxOutputTokens)
            if (requestAborted()) throw AttributionDeadlineExceeded()
            check(userStatus == 0) { "attribution_user_prefill_failed" }
            buildString {
                while (true) {
                    coroutineContext.ensureActive()
                    if (requestAborted()) throw AttributionDeadlineExceeded()
                    val token = generateNextToken() ?: break
                    append(token)
                }
                if (requestAborted()) throw AttributionDeadlineExceeded()
            }
        } catch (error: CancellationException) {
            nativeCancelGeneration()
            throw error
        } finally {
            finishRequest()
        }
    }

    fun cancelGeneration() {
        if (!initialized) return
        nativeCancelGeneration()
    }

    suspend fun unloadModel() = withContext(dispatcher) { unloadLoadedModel() }

    suspend fun deleteModelFile(modelFile: File): Boolean = withContext(dispatcher) {
        if (_loadedModelPath.value == modelFile.absolutePath) unloadLoadedModel()
        modelFile.delete()
    }

    private fun unloadLoadedModel() {
        if (_loadedModelPath.value != null) {
            unload()
            _loadedModelPath.value = null
        }
    }

    private suspend fun awaitBackend() {
        val state = LlamaEngine.getInstance(appContext).state.first {
            it !is LlamaState.Uninitialized && it !is LlamaState.Initializing
        }
        if (state is LlamaState.Error) throw state.exception
    }

    private fun ensureInitialized() {
        if (initialized) return
        loadNativeLibrary()
        val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
        require(nativeLibDir.isNotBlank()) { "attribution_native_library_path_missing" }
        init(nativeLibDir)
        initialized = true
    }

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun prepare(): Int
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun fullReset()
    private external fun nativeCancelGeneration()
    private external fun configureRequest(remainingMillis: Long, grammar: String)
    private external fun requestAborted(): Boolean
    private external fun finishRequest()
    private external fun setEnableThinkingNative(enabled: Boolean)
    private external fun unload()

    companion object {
        private const val TAG = "AttributionLlamaEngine"

        @Volatile private var instance: AttributionLlamaEngine? = null

        fun getInstance(context: Context): AttributionLlamaEngine =
            instance ?: synchronized(this) {
                instance ?: AttributionLlamaEngine(context.applicationContext).also { instance = it }
            }

        private fun loadNativeLibrary() {
            // Ensure the foreground JNI DSO and its llama.cpp dependencies are
            // resident before loading the Agent-A bridge.
            System.loadLibrary("minicpm_v_demo")
            System.loadLibrary("minicpm_attribution")
        }
    }
}
