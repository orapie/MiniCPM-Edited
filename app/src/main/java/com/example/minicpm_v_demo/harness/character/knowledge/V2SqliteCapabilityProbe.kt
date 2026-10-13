package com.example.minicpm_v_demo.harness.character.knowledge

import android.database.sqlite.SQLiteDatabase
import java.io.File

data class V2SqliteCapabilityReport(
    val schemaVersion: String?,
    val sqliteVersion: String?,
    val cutoffCount: Int,
    val fts5TrigramDeclared: Boolean,
    val ftsMatchWorked: Boolean,
    val error: String? = null,
) {
    val isUsable: Boolean
        get() = error == null && schemaVersion?.startsWith("2.") == true &&
            cutoffCount > 0 && fts5TrigramDeclared && ftsMatchWorked
}

/**
 * Android-only Phase 0 probe for a prebuilt V2 knowledge database.
 *
 * It does not mutate the database and returns diagnostics instead of treating a
 * missing FTS5/trigram module as a successful fallback. Phase 1 must use this
 * result to choose SQLite or the portable exported-JSON backend.
 */
object V2SqliteCapabilityProbe {
    fun probe(databaseFile: File): V2SqliteCapabilityReport {
        if (!databaseFile.isFile || databaseFile.length() == 0L) {
            return V2SqliteCapabilityReport(null, null, 0, false, false, "database_missing")
        }
        return try {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                val schemaVersion = database.singleString(
                    "SELECT value FROM metadata WHERE key='schema_version'",
                )
                val sqliteVersion = database.singleString("SELECT sqlite_version()")
                val cutoffCount = database.singleInt("SELECT count(*) FROM cutoffs")
                val ftsDefinition = database.singleString(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='claims_fts'",
                ).orEmpty().lowercase()
                val fts5TrigramDeclared = "fts5" in ftsDefinition && "trigram" in ftsDefinition
                val ftsMatchWorked = database.ftsMatchWorked()
                V2SqliteCapabilityReport(
                    schemaVersion = schemaVersion,
                    sqliteVersion = sqliteVersion,
                    cutoffCount = cutoffCount,
                    fts5TrigramDeclared = fts5TrigramDeclared,
                    ftsMatchWorked = ftsMatchWorked,
                )
            }
        } catch (error: Exception) {
            V2SqliteCapabilityReport(null, null, 0, false, false, error.javaClass.simpleName)
        }
    }

    private fun SQLiteDatabase.ftsMatchWorked(): Boolean = try {
        rawQuery(
            "SELECT count(*) FROM claims_fts WHERE claims_fts MATCH ?",
            arrayOf("\"陆江仙\""),
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }
    } catch (_: Exception) {
        false
    }

    private fun SQLiteDatabase.singleString(sql: String): String? = rawQuery(sql, emptyArray()).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }

    private fun SQLiteDatabase.singleInt(sql: String): Int = rawQuery(sql, emptyArray()).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }
}
