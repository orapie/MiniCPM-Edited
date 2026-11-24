package com.example.minicpm_v_demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.minicpm_v_demo.harness.HarnessModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class LlamaState {
    object Uninitialized : LlamaState()
    object Initializing : LlamaState()
    object Initialized : LlamaState()
    object LoadingModel : LlamaState()
    object ModelReady : LlamaState()
    object ProcessingSystemPrompt : LlamaState()
    object ProcessingUserPrompt : LlamaState()
    object Generating : LlamaState()
    object UnloadingModel : LlamaState()
    data class Error(val exception: Exception) : LlamaState()
}

/** 管理主聊天模型的 JNI 生命周期。加载、预填充和生成在专用协程调度器执行，状态变化供界面订阅。 */
class LlamaEngine private constructor(
    private val context: Context,
    private val nativeLibDir: String
) {

    companion object {
        private val TAG = LlamaEngine::class.java.simpleName

        @Volatile
        private var instance: LlamaEngine? = null

        /**
         * Sentinel forwarded to JNI for generation that stops only at the
         * model's EOG token or when the user presses Stop.  Native generation
         * shifts the KV cache as needed, so this is not limited by one
         * context-window's remaining tokens.
         */
        const val UNLIMITED_PREDICT_LENGTH = -1

        // A finite mobile default prevents a missing EOG token from turning a
        // short chat turn into minutes of generation. Callers may choose a
        // smaller mode-specific cap or explicitly request unlimited output.
        const val DEFAULT_PREDICT_LENGTH = 512

        const val MODEL_SUBDIR = "models"

        private const val PREFS_NAME = "model_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model_id"
        private const val KEY_MODEL_SWITCHED = "model_switched"

        fun getInstance(context: Context): LlamaEngine =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }
                LlamaEngine(context, nativeLibDir).also { instance = it }
            }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getSelectedModel(context: Context): ModelInfo {
            val defaultModelId = HarnessModelRegistry.defaultEntry.spec.id
            val modelId = prefs(context).getString(KEY_SELECTED_MODEL, defaultModelId) ?: defaultModelId
            return HarnessModelRegistry.findLegacyModel(modelId) ?: HarnessModelRegistry.defaultEntry.legacyModelInfo
        }

        fun setSelectedModel(context: Context, modelId: String) {
            prefs(context).edit().putString(KEY_SELECTED_MODEL, modelId).apply()
        }

        fun markModelSwitched(context: Context) {
            prefs(context).edit().putBoolean(KEY_MODEL_SWITCHED, true).apply()
        }

        fun consumeModelSwitched(context: Context): Boolean {
            val switched = prefs(context).getBoolean(KEY_MODEL_SWITCHED, false)
            if (switched) {
                prefs(context).edit().putBoolean(KEY_MODEL_SWITCHED, false).apply()
            }
            return switched
        }

        fun modelDir(context: Context): String =
            File(context.filesDir, MODEL_SUBDIR).absolutePath

        fun modelDirFor(context: Context, model: ModelInfo): String =
            File(modelDir(context), model.id).absolutePath

        fun modelFile(context: Context, model: ModelInfo): File =
            File(modelDirFor(context, model), model.ggufFileName)

        /** Move older text weights into the current per-model directories. */
        fun migrateLegacyLayoutIfNeeded(context: Context) {
            val rootDir = File(modelDir(context))
            if (!rootDir.isDirectory) return

            for (model in HarnessModelRegistry.availableModelInfos()) {
                val targetDir = File(rootDir, model.id)
                val target = File(targetDir, model.ggufFileName)
                val flatFile = File(rootDir, model.ggufFileName)
                if (flatFile.isFile && !target.exists()) {
                    targetDir.mkdirs()
                    if (flatFile.renameTo(target)) {
                        Log.i(TAG, "Migrated legacy ${model.ggufFileName} into ${model.id}/")
                    }
                }
            }

            val modelDir = File(rootDir, "minicpm5-0.9b")
            val oldFile = File(modelDir, "MiniCPM5-0.9B-Q4_K_M.gguf")
            val currentFile = File(modelDir, "MiniCPM5-1B-Q4_K_M.gguf")
            if (oldFile.isFile && !currentFile.exists() && oldFile.renameTo(currentFile)) {
                Log.i(TAG, "Renamed legacy ${oldFile.name} to ${currentFile.name}")
            }
        }

        /** Download the selected text model from ModelScope. */
        suspend fun downloadModels(
            context: Context,
            onProgress: (String) -> Unit,
            modelOverride: ModelInfo? = null,
        ) = withContext(Dispatchers.IO) {
            val model = modelOverride ?: getSelectedModel(context)
            val dir = File(modelDirFor(context, model))
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create model directory: $dir" }
            downloadFile(context, dir, model.ggufFileName, URL(model.downloadUrl), onProgress)
            onProgress(context.getString(R.string.download_all_complete))
        }

        // Downloads a single file with HTTP Range-based resume. The on-disk
        // staging file is `<fileName>.tmp`; if it already exists from a prior
        // (interrupted) attempt we re-issue the request with `Range: bytes=N-`
        // so the server only sends the missing tail. The .tmp file is
        // deliberately preserved across cancellations / process death so the
        // user can resume seamlessly the next time `downloadModels` runs.
        //
        // Server response handling:
        //   - 200 OK  : server ignored Range (or .tmp was empty) -> overwrite from 0
        //   - 206 Partial Content : append missing tail to existing .tmp
        //   - 416 Range Not Satisfiable : .tmp is already the full file -> finalize
        private suspend fun downloadFile(
            context: Context,
            dir: File,
            fileName: String,
            url: URL,
            onProgress: (String) -> Unit
        ) {
            val targetFile = File(dir, fileName)
            val tmpFile = File(dir, "$fileName.tmp")

            val source = "ModelScope"
            if (targetFile.isFile) {
                onProgress(context.getString(R.string.download_file_ready, fileName))
                return
            }

            val resumeFrom = if (tmpFile.exists()) tmpFile.length() else 0L
            if (resumeFrom > 0) {
                val mb = resumeFrom / (1024 * 1024)
                onProgress(context.getString(R.string.download_resuming_src, source, fileName, mb.toInt()))
                Log.i(TAG, "Resuming $fileName from $resumeFrom bytes ($source: $url)")
            } else {
                onProgress(context.getString(R.string.download_fetching_file, source, fileName))
                Log.i(TAG, "Downloading $fileName from $source: $url")
            }

            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 120000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MiniCPM-Edited")
                setRequestProperty("Accept-Encoding", "identity")
                if (resumeFrom > 0) {
                    setRequestProperty("Range", "bytes=$resumeFrom-")
                }
            }

            try {
                val responseCode = conn.responseCode

                // 416: server thinks we already have everything. Verify size
                // matches Content-Range total before treating .tmp as complete.
                if (responseCode == 416 /* RANGE_NOT_SATISFIABLE */) {
                    val totalLen = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                    if (totalLen != null && totalLen > 0 && tmpFile.length() == totalLen) {
                        Log.i(TAG, "$fileName: server reports complete (HTTP 416, size=$totalLen)")
                    } else {
                        // The partial file no longer matches the server's size.
                        Log.w(TAG, "$fileName: HTTP 416 but local size ${tmpFile.length()} != server $totalLen; restarting")
                        tmpFile.delete()
                        throw RuntimeException(context.getString(R.string.download_416_mismatch, source))
                    }
                } else {
                    val acceptedResume = responseCode == HttpURLConnection.HTTP_PARTIAL // 206
                    val isOk = responseCode == HttpURLConnection.HTTP_OK // 200
                    if (!acceptedResume && !isOk) {
                        throw RuntimeException("$source returned $responseCode for $fileName")
                    }

                    // 200 means the server didn't honor Range - either we asked
                    // for bytes=0- (then 200/206 are equivalent), or the server
                    // doesn't support ranges. Either way, restart from 0.
                    if (isOk && resumeFrom > 0) {
                        Log.w(TAG, "$fileName: server returned 200 instead of 206; restarting from 0")
                        if (tmpFile.exists()) tmpFile.delete()
                    }

                    val effectiveStart = if (acceptedResume) resumeFrom else 0L
                    val contentRange = conn.getHeaderField("Content-Range")
                    if (acceptedResume && contentRange?.startsWith("bytes $resumeFrom-") != true) {
                        throw IOException("Unexpected ModelScope Content-Range for $fileName: $contentRange")
                    }
                    val contentLength = conn.contentLengthLong
                    val totalSize = if (acceptedResume) parseContentRangeTotal(contentRange) else null
                    val expectedSize = totalSize ?: if (contentLength >= 0) contentLength + effectiveStart else -1L

                    conn.inputStream.use { input ->
                        // append=true only when we actually got 206 and are
                        // continuing the existing .tmp; otherwise truncate.
                        FileOutputStream(tmpFile, acceptedResume && resumeFrom > 0).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var totalRead = effectiveStart
                            var lastProgressTime = 0L

                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                totalRead += read

                                val now = System.currentTimeMillis()
                                if (now - lastProgressTime > 500) {
                                    lastProgressTime = now
                                    val progress = if (expectedSize > 0) {
                                        val pct = totalRead * 100 / expectedSize
                                        val mb = totalRead / (1024 * 1024)
                                        val totalMb = expectedSize / (1024 * 1024)
                                        "$fileName: $pct% ($mb/$totalMb MB)"
                                    } else {
                                        val mb = totalRead / (1024 * 1024)
                                        "$fileName: $mb MB downloaded"
                                    }
                                    onProgress(progress)
                                }
                            }
                            output.flush()
                            // fd.sync() ensures bytes hit the storage device
                            // before we trust the .tmp size for the next
                            // resume attempt. It's cheap relative to a 1GB
                            // download but, on some Android devices/FS
                            // combinations, can throw SyncFailedException -
                            // wrap it so a fsync hiccup doesn't fail an
                            // otherwise-fine download.
                            try {
                                output.fd.sync()
                            } catch (e: Throwable) {
                                Log.w(TAG, "fsync failed for $fileName (continuing): ${e.message}")
                            }
                        }
                    }
                    if (tmpFile.length() == 0L || (expectedSize >= 0 && tmpFile.length() != expectedSize)) {
                        throw IOException("Incomplete ModelScope download for $fileName")
                    }
                }

                currentCoroutineContext().ensureActive()
                if (!tmpFile.renameTo(targetFile)) {
                    throw IOException("Cannot save model to ${targetFile.absolutePath}")
                }

                onProgress(context.getString(R.string.download_file_complete, fileName, (targetFile.length() / (1024 * 1024)).toInt()))
                Log.i(TAG, "$fileName saved to ${targetFile.absolutePath}")
            } finally {
                conn.disconnect()
                // Note: we deliberately do NOT delete tmpFile in catch-all -
                // a transient network error / process kill should leave the
                // partial bytes on disk so the next call can resume.
            }
        }

        // Parse the "/<total>" suffix out of a Content-Range header.
        // Returns null if header is missing or malformed.
        private fun parseContentRangeTotal(header: String?): Long? {
            if (header.isNullOrEmpty()) return null
            val slash = header.lastIndexOf('/')
            if (slash < 0 || slash == header.length - 1) return null
            return header.substring(slash + 1).trim().toLongOrNull()
        }
    }

    @ExperimentalCoroutinesApi
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    private val _state = MutableStateFlow<LlamaState>(LlamaState.Uninitialized)
    val state: StateFlow<LlamaState> = _state.asStateFlow()

    @Volatile
    private var _cancelGeneration = false

    @Volatile
    private var _readyForSystemPrompt = false

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    // Controls whether a text model's chat template enters its reasoning
    // section. Mobile catalog entries default to direct answers so short
    // generations do not exhaust their token budget inside <think>.
    private external fun setEnableThinkingNative(enabled: Boolean)
    private external fun prepare(): Int
    private external fun systemInfo(): String
    /** Counts tokens with the loaded model tokenizer without mutating KV state. */
    private external fun countTokens(prompt: String, addSpecial: Boolean): Int
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun fullReset()
    private external fun nativeCancelGeneration()
    private external fun unload()
    private external fun shutdown()

    init {
        llamaScope.launch {
            try {
                check(_state.value is LlamaState.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = LlamaState.Initializing
                Log.i(TAG, "Loading native library...")
                System.loadLibrary("minicpm_v_demo")
                init(nativeLibDir)
                _state.value = LlamaState.Initialized
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                _state.value = LlamaState.Error(e)
                throw e
            }
        }
    }

    /** 加载 GGUF 并准备原生文本推理上下文。 */
    suspend fun loadModel(pathToModel: String) =
        withContext(llamaDispatcher) {
            check(_state.value is LlamaState.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }
            try {
                Log.i(TAG, "Checking access to model file... \n$pathToModel")
                File(pathToModel).let {
                    require(it.exists()) { "File not found: $pathToModel" }
                    require(it.isFile) { "Not a valid file: $pathToModel" }
                    require(it.canRead()) { "Cannot read file: $pathToModel" }
                }

                Log.i(TAG, "Loading model... \n$pathToModel")
                _readyForSystemPrompt = false
                _state.value = LlamaState.LoadingModel
                load(pathToModel).let {
                    if (it != 0) throw RuntimeException("Failed to load model (code: $it)")
                }

                val selectedModel = getSelectedModel(context)
                setEnableThinkingNative(selectedModel.enableThinking)
                Log.i(TAG, "Chat thinking enabled=${selectedModel.enableThinking} for ${selectedModel.id}")

                prepare().let {
                    if (it != 0) throw RuntimeException("Failed to prepare resources (code: $it)")
                }
                Log.i(TAG, "Model loaded!")
                _readyForSystemPrompt = true
                _cancelGeneration = false
                _state.value = LlamaState.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, (e.message ?: "Error loading model") + "\n" + pathToModel, e)
                _state.value = LlamaState.Error(e)
                throw e
            }
        }

    /** 在原生上下文允许写入系统消息时预填充系统提示，并将引擎恢复到可继续接收输入的状态。 */
    suspend fun setSystemPrompt(prompt: String) =
        withContext(llamaDispatcher) {
            require(prompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set RIGHT AFTER model loaded!" }
            check(_state.value is LlamaState.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            val startedAt = System.nanoTime()
            Log.i(TAG, "Sending system prompt...")
            _readyForSystemPrompt = false
            _state.value = LlamaState.ProcessingSystemPrompt
            processSystemPrompt(prompt).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = LlamaState.Error(it)
                        throw it
                    }
                }
            }
            Log.i(
                TAG,
                "System prompt processed! elapsed_ms=${elapsedMillis(startedAt)}; " +
                    "awaiting user prompt...",
            )
            _state.value = LlamaState.ModelReady
        }

    /**
     * Returns the exact token count for the loaded model tokenizer. This is a
     * read-only operation and does not alter the active KV cache.
     */
    suspend fun countPromptTokens(prompt: String, addSpecial: Boolean = true): Int =
        withContext(llamaDispatcher) {
            require(prompt.isNotEmpty()) { "Prompt must not be empty!" }
            check(_state.value !is LlamaState.Uninitialized && _state.value !is LlamaState.Error) {
                "Cannot count tokens before the native model is available"
            }
            countTokens(prompt, addSpecial).also { count ->
                check(count >= 0) { "Native tokenizer is not ready" }
            }
        }

    /** 清空原生侧上下文，为重置会话或下一次系统提示预填充做准备。 */
    suspend fun clearContext() =
        withContext(llamaDispatcher) {
            check(_state.value is LlamaState.ModelReady) {
                "Cannot clear context in ${_state.value.javaClass.simpleName}"
            }
            fullReset()
            _readyForSystemPrompt = true
            Log.i(TAG, "Context reset - KV cache cleared and allocation reused for the next conversation")
        }

    /** 在推理调度器上预填充用户输入，再按生成顺序发出非空文本片段。完成、取消或失败时恢复模型就绪状态。 */
    fun sendUserPrompt(
        message: String,
        predictLength: Int = DEFAULT_PREDICT_LENGTH
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt must not be empty!" }
        check(_state.value is LlamaState.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            _cancelGeneration = false
            val startedAt = System.nanoTime()
            var firstTokenLogged = false
            var sampledTokens = 0
            var outputChars = 0
            Log.i(TAG, "Sending user prompt...")
            _readyForSystemPrompt = false
            _state.value = LlamaState.ProcessingUserPrompt

            processUserPrompt(message, predictLength).let { result ->
                if (result != 0) {
                    Log.e(TAG, "Failed to process user prompt: $result")
                    _state.value = LlamaState.ModelReady
                    throw RuntimeException("Failed to process user prompt: $result")
                }
            }

            val prefillMillis = elapsedMillis(startedAt)
            Log.i(
                TAG,
                "User prompt processed. prefill_ms=$prefillMillis; " +
                    "generating assistant prompt...",
            )
            val generationStartedAt = System.nanoTime()
            _state.value = LlamaState.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    sampledTokens++
                    if (utf8token.isNotEmpty()) {
                        outputChars += utf8token.length
                        if (!firstTokenLogged) {
                            firstTokenLogged = true
                            Log.i(TAG, "First assistant token received. ttft_ms=${elapsedMillis(startedAt)}")
                        }
                        emit(utf8token)
                    }
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            val generationMillis = elapsedMillis(generationStartedAt)
            val tokensPerSecond = if (generationMillis > 0) {
                sampledTokens * 1_000.0 / generationMillis
            } else {
                0.0
            }
            Log.i(
                TAG,
                "GENERATION_PERF predict_limit=$predictLength prefill_ms=$prefillMillis " +
                    "generation_ms=$generationMillis total_ms=${elapsedMillis(startedAt)} " +
                    "sampled_tokens=$sampledTokens output_chars=$outputChars " +
                    "decode_tokens_per_second=$tokensPerSecond cancelled=$_cancelGeneration",
            )
            _state.value = LlamaState.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            _state.value = LlamaState.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = LlamaState.ModelReady
            throw e
        }
    }.flowOn(llamaDispatcher)

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    fun cancelGeneration() {
        _cancelGeneration = true
        llamaScope.launch {
            nativeCancelGeneration()
        }
    }

    suspend fun unloadModel() = withContext(llamaDispatcher) {
        if (_state.value is LlamaState.ModelReady) {
            Log.i(TAG, "Unloading model...")
            _readyForSystemPrompt = false
            _state.value = LlamaState.UnloadingModel
            unload()
            _state.value = LlamaState.Initialized
            Log.i(TAG, "Model unloaded")
        }
    }

    fun resetToInitialized() {
        _readyForSystemPrompt = false
        _cancelGeneration = false
        _state.value = LlamaState.Initialized
    }

    fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is LlamaState.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources...")
                    _readyForSystemPrompt = false
                    _state.value = LlamaState.UnloadingModel
                    unload()
                    _state.value = LlamaState.Initialized
                    Log.i(TAG, "Model unloaded!")
                }
                is LlamaState.Error -> {
                    Log.i(TAG, "Resetting error states...")
                    _state.value = LlamaState.Initialized
                }
                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when (_state.value) {
                is LlamaState.Uninitialized -> {}
                is LlamaState.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }

}
