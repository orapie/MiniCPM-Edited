package com.example.minicpm_v_demo.harness.data.database

import android.content.Context
import android.util.Log

/** Publishes only the explicitly selected, validated installed bundle. */
object DatabaseRuntime {
    private val turnGate = DatabaseTurnGate()
    @Volatile
    var installation: DatabaseInstallResult? = null
        private set

    @Synchronized
    fun initialize(context: Context): DatabaseInstallResult = turnGate.atBoundary {
        installation = null
        DatabaseBundleInstaller(context.filesDir).install(ApkDatabaseBundleSource(context.assets))
            .also { result ->
                installation = result
                if (result is DatabaseInstallResult.Ready) {
                    runCatching { LegacyHarnessCleanup(context.filesDir).run(databaseReady = true) }
                        .onFailure { Log.w("DatabaseRuntime", "Legacy asset cleanup deferred", it) }
                }
            }
    }

    /** Acquired before a turn starts and closed after its retries/persistence. */
    @Synchronized
    fun beginTurn(): DatabaseTurnLease? {
        val bundle = (installation as? DatabaseInstallResult.Ready)?.bundle ?: return null
        val lease = turnGate.acquire()
        return DatabaseTurnLease(bundle, lease)
    }

    fun requireBundle(): DatabaseBundleHandle =
        (installation as? DatabaseInstallResult.Ready)?.bundle
            ?: error("Current database is unavailable or has not been initialized")
}

class DatabaseTurnLease internal constructor(
    val bundle: DatabaseBundleHandle,
    private val lease: AutoCloseable,
) : AutoCloseable {
    val identity = DatabaseIdentity.from(bundle)
    override fun close() = lease.close()
}
