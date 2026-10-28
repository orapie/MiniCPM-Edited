package com.example.minicpm_v_demo.harness.data.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestrator
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseBundleInstallerDeviceTest {
    private lateinit var root: File
    private lateinit var scenarios: DatabaseInstallerScenarios

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "database-install-test-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
        scenarios = DatabaseInstallerScenarios(root, MemoryDatabaseSource.copyOf(ApkDatabaseBundleSource(context.assets)))
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun freshAndUnchanged() = scenarios.freshAndUnchanged()
    @Test fun upgradeAndAppDataPreservation() = scenarios.upgradeRemovesRetiredFilesAndPreservesOtherAppData()
    @Test fun corruptCandidateRetainsSameDataset() = scenarios.corruptSameDatasetRetainsVerifiedPreviousWithExplicitStatus()
    @Test fun failedSwitchIsUnavailable() = scenarios.differentDatasetFailureDoesNotExposeOldHandle()
    @Test fun successfulSwitch() = scenarios.successfulDatasetSwitchUsesOnlyNewIdentity()
    @Test fun damagedPayloadRepair() = scenarios.damagedInstalledPayloadIsRepaired()
    @Test fun activationRollback() = scenarios.activationFailureRollsBack()
    @Test fun interruptedRenameRecovery() = scenarios.interruptedRenameIsRecovered()
    @Test fun userFilePreservation() = scenarios.unknownUserFilesArePreservedOutsideActiveBundle()
    @Test fun malformedManifestNoLegacyFallback() = scenarios.malformedCandidateCannotGuessSelectedDatasetOrReadLegacy()
    @Test fun interruptedCopyCleanup() = scenarios.interruptedCopyIsRemovedWithoutReadingOldKnowledge()
    @Test fun concurrentInstallation() = scenarios.concurrentInstancesSerializeInstallation()

    @Test fun applicationStartupPublishesInstalledBundleAndProgramTemplate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = DatabaseRuntime.installation as DatabaseInstallResult.Ready
        assertEquals(File(context.filesDir, "database").canonicalFile, ready.bundle.paths.rootDir)
        assertEquals("three_body_2", ready.bundle.manifest.datasetId)
        assertTrue(File(context.filesDir, "program/prompts/roleplay_system.prompt").isFile)
        assertEquals(3, ready.bundle.validated.authorizedFactCount)
    }

    @Test fun productionFactoryUsesInstalledBundle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val orchestrator = AndroidRagOrchestrator.fromContext(context)
        assertEquals(setOf("luo_ji", "shi_qiang", "ding_yi", "zhang_beihai", "zhuang_yan"),
            orchestrator.availableCharacters().map { it.npcId }.toSet())
    }
}
