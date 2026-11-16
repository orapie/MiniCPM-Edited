package com.example.minicpm_v_demo.harness

import com.example.minicpm_v_demo.ModelInfo

data class HarnessModelEntry(
    val legacyModelInfo: ModelInfo,
    val spec: HarnessModelSpec
)

/** 按同一模型 ID 提供旧版 ModelInfo 和 Harness 规格，让现有界面与新后端使用同一份目录。 */
object HarnessModelRegistry {
    private val entries: List<HarnessModelEntry> = ModelInfo.AVAILABLE_MODELS.map { model ->
        HarnessModelEntry(
            legacyModelInfo = model,
            spec = model.toHarnessSpec()
        )
    }

    val defaultEntry: HarnessModelEntry = entries.first()

    fun availableEntries(): List<HarnessModelEntry> = entries

    fun availableModelInfos(): List<ModelInfo> = entries.map { it.legacyModelInfo }

    fun availableSpecs(): List<HarnessModelSpec> = entries.map { it.spec }

    fun findEntry(id: String): HarnessModelEntry? = entries.find { it.spec.id == id }

    fun findLegacyModel(id: String): ModelInfo? = findEntry(id)?.legacyModelInfo

    fun findSpec(id: String): HarnessModelSpec? = findEntry(id)?.spec
}
