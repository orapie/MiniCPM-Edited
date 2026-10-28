package com.example.minicpm_v_demo.harness.session

import com.example.minicpm_v_demo.harness.data.database.DatabaseIdentity

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
