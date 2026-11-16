package com.example.minicpm_v_demo.harness

import android.content.Context
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.ModelDownloadService
import com.example.minicpm_v_demo.ModelInfo

/** 统一接入模型下载实现和前台下载服务，两条入口使用相同的模型选择。 */
class LlamaDownloadManager(private val context: Context) {

    suspend fun downloadModel(model: ModelInfo, onProgress: (String) -> Unit) {
        LlamaEngine.downloadModels(context, onProgress, model)
    }

    fun startForegroundDownload(modelId: String) {
        ModelDownloadService.start(context, modelId)
    }

    fun cancelForegroundDownload() {
        ModelDownloadService.cancel(context)
    }
}
