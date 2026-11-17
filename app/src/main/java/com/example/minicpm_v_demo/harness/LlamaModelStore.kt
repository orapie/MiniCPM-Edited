package com.example.minicpm_v_demo.harness

import android.content.Context
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.ModelInfo
import java.io.File

data class HarnessModelAvailability(
    val model: ModelInfo,
    val ggufMissing: Boolean
)

data class LlamaModelFiles(
    val model: ModelInfo,
    val spec: HarnessModelSpec,
    val artifactFiles: Map<String, File>
)

/** 把已选模型及其文件路径、可用性和本地删除操作统一暴露给 Harness。 */
class LlamaModelStore(private val context: Context) {

    fun migrateLegacyLayoutIfNeeded() {
        LlamaEngine.migrateLegacyLayoutIfNeeded(context)
    }

    fun getSelectedModel(): ModelInfo = LlamaEngine.getSelectedModel(context)

    fun setSelectedModel(modelId: String) {
        LlamaEngine.setSelectedModel(context, modelId)
    }

    fun markModelSwitched() {
        LlamaEngine.markModelSwitched(context)
    }

    fun consumeModelSwitched(): Boolean = LlamaEngine.consumeModelSwitched(context)

    fun getSelectedModelSpec(): HarnessModelSpec =
        HarnessModelRegistry.findSpec(getSelectedModel().id) ?: HarnessModelRegistry.defaultEntry.spec

    fun getSelectedModelFiles(): LlamaModelFiles {
        val model = getSelectedModel()
        val spec = getSelectedModelSpec()
        val artifactFiles = mapOf("llm" to LlamaEngine.modelFile(context, model))
        return LlamaModelFiles(
            model = model,
            spec = spec,
            artifactFiles = artifactFiles
        )
    }

    /** 检查所选文本模型的 GGUF 文件是否可加载。 */
    fun getSelectedModelAvailability(): HarnessModelAvailability {
        val files = getSelectedModelFiles()
        val ggufMissing = files.artifactFiles["llm"]?.isFile != true
        return HarnessModelAvailability(model = files.model, ggufMissing = ggufMissing)
    }

    fun isSelectedModelDownloaded(): Boolean {
        val files = getSelectedModelFiles()
        val requiredArtifacts = files.spec.artifacts.filter { it.required }
        return requiredArtifacts.all { artifact ->
            files.artifactFiles[artifact.id]?.isFile == true
        }
    }

    fun selectedModelArtifactNames(): List<String> =
        getSelectedModelSpec().artifacts.map { it.fileName }

    fun deleteSelectedModelFiles(): Boolean {
        val files = getSelectedModelFiles()
        var deleted = false
        for (file in files.artifactFiles.values) {
            if (file.exists() && file.delete()) {
                deleted = true
            }
        }
        return deleted
    }
}
