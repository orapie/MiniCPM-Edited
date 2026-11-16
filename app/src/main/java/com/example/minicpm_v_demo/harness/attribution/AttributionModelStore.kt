package com.example.minicpm_v_demo.harness.attribution

import android.content.Context
import com.example.minicpm_v_demo.LlamaEngine
import java.io.File

data class AttributionModelFiles(
    val entry: AttributionModelEntry,
    val llmFile: File,
)

data class AttributionModelAvailability(
    val entry: AttributionModelEntry,
    val ggufMissing: Boolean,
) {
    val isReady: Boolean get() = !ggufMissing
}

/**
 * Independent Agent-A model choice. It intentionally shares downloaded GGUF
 * files with Agent B by model ID, but never reads or writes Agent B's selected
 * model preference.
 */
class AttributionModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedEntry(): AttributionModelEntry = resolveSelected(
        preferences.getString(KEY_SELECTED_MODEL, null),
    )

    fun setSelectedModel(modelId: String) {
        require(AttributionModelRegistry.findEligibleEntry(modelId) != null) {
            "attribution_model_not_eligible"
        }
        preferences.edit().putString(KEY_SELECTED_MODEL, modelId).apply()
    }

    /** 按独立的 Agent A 选择解析 GGUF 路径，供下载状态检查和归因引擎使用。 */
    fun getSelectedModelFiles(): AttributionModelFiles {
        val entry = getSelectedEntry()
        val path = File(LlamaEngine.modelDirFor(appContext, entry.model), entry.model.ggufFileName)
        return AttributionModelFiles(entry, path)
    }

    fun getSelectedModelAvailability(): AttributionModelAvailability {
        val files = getSelectedModelFiles()
        return AttributionModelAvailability(files.entry, ggufMissing = !files.llmFile.isFile)
    }

    fun isSelectedModelDownloaded(): Boolean = getSelectedModelAvailability().isReady

    companion object {
        private const val PREFS_NAME = "attribution_model_prefs"
        private const val KEY_SELECTED_MODEL = "selected_attribution_model_id"

        /** Pure resolver retained for JVM tests and migration-safe preference recovery. */
        fun resolveSelected(modelId: String?): AttributionModelEntry =
            modelId?.let(AttributionModelRegistry::findEligibleEntry)
                ?: requireNotNull(AttributionModelRegistry.eligibleEntries().firstOrNull()) {
                    "no_eligible_attribution_model"
                }
    }
}
