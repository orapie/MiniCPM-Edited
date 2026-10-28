package com.example.minicpm_v_demo.harness.data.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseSessionIsolationDeviceTest {
    private lateinit var root: File
    private lateinit var scenarios: DatabaseSessionScenarios
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "database-session-test-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
        val source = MemoryDatabaseSource.copyOf(ApkDatabaseBundleSource(context.assets))
        val template = File(root, "program/roleplay_system.prompt").apply {
            parentFile!!.mkdirs()
            context.assets.open("prompts/roleplay_system.prompt").use { input -> outputStream().use { input.copyTo(it) } }
        }
        scenarios = DatabaseSessionScenarios(root, source, template)
    }
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun datasetsAndLegacyHistory() = scenarios.switchingDatasetsPreservesLegacyAndArchives()
    @Test fun versionIsolation() = scenarios.sameDatasetNewFingerprintIsIsolated()
    @Test fun memoryIsolation() = scenarios.memoryAndSelectorFilterIdentityAndCharacter()
    @Test fun tampering() = scenarios.tamperedIdentityAndCutoffCannotRestore()
    @Test fun modelSwitch() = scenarios.modelSwitchPreservesCompatibleSession()
    @Test fun turnBoundary() = scenarios.activeTurnBlocksSwitchUntilCancellationCompletes()
    @Test fun staleConsumersAndTrace() = scenarios.oldCachedConsumerRejectsSwitchedBundleAndNewTraceIsBound()

    @Test fun runtimeRejectsActivationDuringRealLease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = DatabaseRuntime.requireBundle().manifest.datasetFingerprint
        val lease = requireNotNull(DatabaseRuntime.beginTurn())
        try {
            assertEquals("database_turn_active", runCatching { DatabaseRuntime.initialize(context) }.exceptionOrNull()?.message)
            assertEquals(original, DatabaseRuntime.requireBundle().manifest.datasetFingerprint)
        } finally { lease.close() }
        val result = DatabaseRuntime.initialize(context) as DatabaseInstallResult.Ready
        assertEquals(original, result.bundle.manifest.datasetFingerprint)
    }
}
