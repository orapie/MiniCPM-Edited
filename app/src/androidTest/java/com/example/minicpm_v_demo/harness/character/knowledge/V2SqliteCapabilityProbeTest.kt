package com.example.minicpm_v_demo.harness.character.knowledge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class V2SqliteCapabilityProbeTest {
    @Test
    fun v2DatabaseOpensReadOnlyAndSupportsItsFts5TrigramQuery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val databaseFile = File(targetContext.cacheDir, "phase0-character-knowledge-v2.sqlite3")
        instrumentation.context.assets.open("phase0/character_knowledge_v2.sqlite3").use { input ->
            databaseFile.outputStream().use { output -> input.copyTo(output) }
        }

        try {
            val report = V2SqliteCapabilityProbe.probe(databaseFile)

            assertEquals("2.0.0", report.schemaVersion)
            assertTrue("SQLite probe failed: ${report.error}", report.error == null)
            assertTrue("claims_fts must be an FTS5 trigram table", report.fts5TrigramDeclared)
            Log.i("CharacterKnowledgeProbe", "V2 SQLite capability: $report")
        } finally {
            databaseFile.delete()
        }
    }
}
