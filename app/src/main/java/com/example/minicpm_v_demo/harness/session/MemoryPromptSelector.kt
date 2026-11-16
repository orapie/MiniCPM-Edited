package com.example.minicpm_v_demo.harness.session

import com.example.minicpm_v_demo.harness.data.database.DatabaseIdentity

/** 筛选允许进入提示词的长期记忆。记录必须匹配当前数据指纹和角色，并具有可用的类型与范围。 */
class MemoryPromptSelector {
    fun selectPromptSafeLongTerm(records: List<MemoryRecord>, datasetIdentity: DatabaseIdentity, characterId: String): List<MemoryRecord> {
        return records.filter { record ->
            record.datasetId == datasetIdentity.datasetId &&
                record.datasetFingerprint == datasetIdentity.fingerprint &&
                record.characterId == characterId &&
            record.promptSafe &&
                record.scope != "session" &&
                record.kind in setOf("summary_memory", "profile_memory", "character_memory")
        }
    }
}
