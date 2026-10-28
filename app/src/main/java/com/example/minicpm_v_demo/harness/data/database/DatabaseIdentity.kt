package com.example.minicpm_v_demo.harness.data.database

/** Stable identity of the exact data bundle, independent of the model. */
data class DatabaseIdentity(val datasetId: String, val fingerprint: String) {
    init {
        require(datasetId.matches(Regex("[a-z0-9_-]+"))) { "Invalid dataset ID" }
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Invalid dataset fingerprint" }
    }

    companion object {
        fun from(bundle: DatabaseBundleHandle) = DatabaseIdentity(
            bundle.manifest.datasetId, bundle.manifest.datasetFingerprint,
        )
    }
}

/** A lease pins the selected bundle until generation, retries and persistence finish. */
class DatabaseTurnGate {
    private var active = 0
    private var switching = false

    @Synchronized fun acquire(): AutoCloseable {
        check(!switching) { "database_switch_in_progress" }
        active++
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (!closed) { closed = true; active-- }
            }
        }
    }

    @Synchronized fun <T> atBoundary(action: () -> T): T {
        check(!switching && active == 0) { "database_turn_active" }
        switching = true
        try { return action() } finally { switching = false }
    }
}
