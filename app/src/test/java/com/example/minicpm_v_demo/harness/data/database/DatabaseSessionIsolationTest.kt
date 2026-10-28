package com.example.minicpm_v_demo.harness.data.database

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DatabaseSessionIsolationTest {
    private lateinit var root: File
    private lateinit var scenarios: DatabaseSessionScenarios
    @Before fun setup() {
        root = Files.createTempDirectory("database-session-test").toFile().canonicalFile
        val source = MemoryDatabaseSource.copyOf(DirectoryDatabaseBundleSource(databaseTestSource().rootDir))
        val repo = if (File("settings.gradle.kts").isFile) File(".") else File("..")
        scenarios = DatabaseSessionScenarios(root, source, File(repo, "app/src/main/assets/prompts/roleplay_system.prompt"))
    }
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun datasetsAndLegacyHistory() = scenarios.switchingDatasetsPreservesLegacyAndArchives()
    @Test fun versionIsolation() = scenarios.sameDatasetNewFingerprintIsIsolated()
    @Test fun memoryIsolation() = scenarios.memoryAndSelectorFilterIdentityAndCharacter()
    @Test fun tampering() = scenarios.tamperedIdentityAndCutoffCannotRestore()
    @Test fun modelSwitch() = scenarios.modelSwitchPreservesCompatibleSession()
    @Test fun turnBoundary() = scenarios.activeTurnBlocksSwitchUntilCancellationCompletes()
    @Test fun staleConsumersAndTrace() = scenarios.oldCachedConsumerRejectsSwitchedBundleAndNewTraceIsBound()
}
