package com.example.minicpm_v_demo.harness.character.knowledge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class V2SqliteCapabilityProbeTest {
    @Test
    fun v2DatabaseOpensReadOnlyAndSupportsItsFts5TrigramQuery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseFile = File(context.cacheDir, "phase0-character-knowledge-v2.sqlite3")
        context.assets.open("phase0/character_knowledge_v2.sqlite3").use { input ->
            databaseFile.outputStream().use { output -> input.copyTo(output) }
        }

        try {
            val report = V2SqliteCapabilityProbe.probe(databaseFile)

            assertEquals("2.0.0", report.schemaVersion)
            assertTrue("SQLite probe failed: ${report.error}", report.error == null)
            assertTrue("claims_fts must be an FTS5 trigram table", report.fts5TrigramDeclared)
            assertTrue("FTS MATCH must return a known V2 claim", report.ftsMatchWorked)
            assertTrue("V2 database must be usable: $report", report.isUsable)
        } finally {
            databaseFile.delete()
        }
    }
}
