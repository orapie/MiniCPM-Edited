package com.example.minicpm_v_demo.harness.data.database

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DatabaseBundleInstallerTest {
    private lateinit var root: File
    private lateinit var scenarios: DatabaseInstallerScenarios

    @Before fun setup() {
        root = Files.createTempDirectory("database-installer-test").toFile().canonicalFile
        val source = MemoryDatabaseSource.copyOf(DirectoryDatabaseBundleSource(databaseTestSource().rootDir))
        scenarios = DatabaseInstallerScenarios(root, source)
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
}
