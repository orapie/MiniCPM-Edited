package com.example.minicpm_v_demo.harness.data.database

import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonArray
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import com.example.minicpm_v_demo.harness.session.jsonObject
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Same installation scenarios run on the host JVM and on Android's filesystem. */
class MemoryDatabaseSource private constructor(private val files: Map<String, ByteArray>) : DatabaseBundleSource {
    val manifest: DatabaseBundleManifest get() = DatabaseBundleManifest.parse(files.getValue("manifest.json").toString(Charsets.UTF_8))
    val requests = mutableListOf<String>()

    override fun open(relativePath: String): ByteArrayInputStream {
        requests += relativePath
        return ByteArrayInputStream(files.getValue(relativePath))
    }

    fun corrupt(relativePath: String): MemoryDatabaseSource = MemoryDatabaseSource(files + (relativePath to "broken".toByteArray()))

    fun variant(datasetId: String = manifest.datasetId, version: String = "upgrade-test", retiredFile: Boolean = false): MemoryDatabaseSource {
        val changed = files.toMutableMap()
        listOf("candidate_v2", "runtime_v2", "vector_index", "reviewed_facts").forEach { key ->
            val path = manifest.paths.getValue(key)
            val root = SimpleJsonParser(files.getValue(path).toString(Charsets.UTF_8)).parse().asJsonObject(key).toMutableMap()
            root["dataset_id"] = datasetId
            if ("dataset_version" in root) root["dataset_version"] = version
            if (key == "vector_index") {
                root["items"] = root["items"].asJsonArray("items").map { raw ->
                    val item = raw.asJsonObject("item").toMutableMap()
                    val chunk = item["chunk"].asJsonObject("chunk").toMutableMap()
                    chunk["metadata"] = chunk["metadata"].asJsonObject("metadata") +
                        mapOf("dataset_id" to datasetId, "dataset_version" to version)
                    item["chunk"] = chunk
                    item
                }
            }
            changed[path] = jsonObject(root).toByteArray(Charsets.UTF_8)
        }
        if (retiredFile) changed["retired-test-file.txt"] = "old controlled file".toByteArray()
        val fileHashes = changed.filterKeys { it != "manifest.json" }.mapValues { (_, bytes) -> sha256(bytes) }
        val next = manifest.copy(datasetId = datasetId, datasetVersion = version, files = fileHashes)
        val root = SimpleJsonParser(files.getValue("manifest.json").toString(Charsets.UTF_8)).parse().asJsonObject("manifest").toMutableMap()
        root["dataset_id"] = datasetId
        root["dataset_version"] = version
        root["files"] = next.files.map { (path, hash) -> mapOf("path" to path, "sha256" to hash) }
        root["dataset_fingerprint"] = next.computeFingerprint()
        changed["manifest.json"] = jsonObject(root).toByteArray(Charsets.UTF_8)
        return MemoryDatabaseSource(changed)
    }

    companion object {
        fun copyOf(source: DatabaseBundleSource): MemoryDatabaseSource {
            val manifest = source.open("manifest.json").use { it.readBytes() }
            val parsed = DatabaseBundleManifest.parse(manifest.toString(Charsets.UTF_8))
            val files = parsed.files.keys.associateWith { relative -> source.open(relative).use { it.readBytes() } }
            return MemoryDatabaseSource(files + ("manifest.json" to manifest))
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

class DatabaseInstallerScenarios(private val root: File, private val source: MemoryDatabaseSource) {
    private val installer get() = DatabaseBundleInstaller(root)
    private val active get() = File(root, "database")

    fun freshAndUnchanged() {
        val first = installer.install(source) as DatabaseInstallResult.Ready
        assertEquals(DatabaseInstallAction.INSTALLED, first.action)
        assertEquals(File(root.canonicalFile, "database"), first.bundle.paths.rootDir)
        assertEquals(source.manifest.datasetFingerprint, first.bundle.manifest.datasetFingerprint)
        assertEquals(3, first.bundle.validated.authorizedFactCount)
        source.requests.clear()
        val second = installer.install(source) as DatabaseInstallResult.Ready
        assertEquals(DatabaseInstallAction.UNCHANGED, second.action)
        assertEquals(first.bundle.manifest.datasetFingerprint, second.bundle.manifest.datasetFingerprint)
        assertEquals(listOf("manifest.json"), source.requests)
    }

    fun upgradeRemovesRetiredFilesAndPreservesOtherAppData() {
        val old = source.variant(version = "old-test", retiredFile = true)
        assertTrue(installer.install(old) is DatabaseInstallResult.Ready)
        assertTrue(File(active, "retired-test-file.txt").isFile)
        val sentinels = listOf("models/test.gguf", "harness/sessions/recent_session.json", "harness/memory/memories.jsonl")
        sentinels.forEach { path -> File(root, path).apply { requireNotNull(parentFile).mkdirs(); writeText("preserve-$path") } }
        val updated = installer.install(source) as DatabaseInstallResult.Ready
        assertEquals(DatabaseInstallAction.UPDATED, updated.action)
        assertFalse(File(active, "retired-test-file.txt").exists())
        assertFalse(File(root, ".database.previous").exists())
        sentinels.forEach { path -> assertEquals("preserve-$path", File(root, path).readText()) }
    }

    fun corruptSameDatasetRetainsVerifiedPreviousWithExplicitStatus() {
        installer.install(source)
        val candidate = source.variant().corrupt(source.manifest.paths.getValue("vector_index"))
        val result = installer.install(candidate) as DatabaseInstallResult.Ready
        assertEquals(DatabaseInstallAction.RETAINED_PREVIOUS, result.action)
        assertEquals("payload_validation_failed", result.reason)
        assertEquals(source.manifest.datasetFingerprint, result.bundle.manifest.datasetFingerprint)
        assertNotEquals(result.requestedFingerprint, result.bundle.manifest.datasetFingerprint)
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".database.install-") })
    }

    fun differentDatasetFailureDoesNotExposeOldHandle() {
        installer.install(source)
        val candidate = source.variant(datasetId = "other_test_dataset").corrupt(source.manifest.paths.getValue("runtime_v2"))
        val result = installer.install(candidate) as DatabaseInstallResult.Unavailable
        assertEquals("other_test_dataset", result.requestedDatasetId)
        assertEquals("payload_validation_failed", result.reason)
        assertEquals(source.manifest.datasetId, DatabaseBundleValidator.validate(DatabaseDataPaths(active)).manifest.datasetId)
    }

    fun successfulDatasetSwitchUsesOnlyNewIdentity() {
        installer.install(source)
        val next = source.variant(datasetId = "other_test_dataset")
        val result = installer.install(next) as DatabaseInstallResult.Ready
        assertEquals(DatabaseInstallAction.SWITCHED, result.action)
        assertEquals("other_test_dataset", result.bundle.manifest.datasetId)
        assertEquals(next.manifest.datasetFingerprint, result.bundle.manifest.datasetFingerprint)
    }

    fun damagedInstalledPayloadIsRepaired() {
        installer.install(source)
        File(active, source.manifest.paths.getValue("vector_index")).writeText("damaged")
        val result = installer.install(source) as DatabaseInstallResult.Ready
        assertEquals(DatabaseInstallAction.REPAIRED, result.action)
        assertEquals(source.manifest.datasetFingerprint, result.bundle.manifest.datasetFingerprint)
    }

    fun activationFailureRollsBack() {
        installer.install(source)
        val failing = DatabaseBundleInstaller(root) { from, to ->
            if (from.name.startsWith(".database.install-") && to.name == "database") false else from.renameTo(to)
        }
        val result = failing.install(source.variant()) as DatabaseInstallResult.Ready
        assertEquals(DatabaseInstallAction.RETAINED_PREVIOUS, result.action)
        assertEquals("activation_failed", result.reason)
        assertEquals(source.manifest.datasetFingerprint, result.bundle.manifest.datasetFingerprint)
        assertFalse(File(root, ".database.previous").exists())
    }

    fun interruptedRenameIsRecovered() {
        installer.install(source)
        assertTrue(active.renameTo(File(root, ".database.previous")))
        val result = installer.install(source) as DatabaseInstallResult.Ready
        assertEquals(DatabaseInstallAction.UNCHANGED, result.action)
        assertTrue(active.isDirectory)
        assertFalse(File(root, ".database.previous").exists())
    }

    fun unknownUserFilesArePreservedOutsideActiveBundle() {
        installer.install(source)
        File(active, "user-notes.txt").writeText("user original")
        val result = installer.install(source) as DatabaseInstallResult.Ready
        assertEquals(DatabaseInstallAction.REPAIRED, result.action)
        assertFalse(File(active, "user-notes.txt").exists())
        val preserved = root.listFiles().orEmpty().single { it.name.startsWith(".database.preserved-") }
        assertEquals("user original", File(preserved, "user-notes.txt").readText())
    }

    fun malformedCandidateCannotGuessSelectedDatasetOrReadLegacy() {
        installer.install(source)
        File(root, "harness/rag/index.json").apply { requireNotNull(parentFile).mkdirs(); writeText("legacy sentinel") }
        File(root, "character/manifest.json").apply { requireNotNull(parentFile).mkdirs(); writeText("legacy sentinel") }
        val candidate = source.corrupt("manifest.json")
        val result = installer.install(candidate) as DatabaseInstallResult.Unavailable
        assertNull(result.requestedDatasetId)
        assertEquals("manifest_unreadable", result.reason)
        assertEquals("legacy sentinel", File(root, "harness/rag/index.json").readText())
        assertEquals(listOf("manifest.json"), candidate.requests)
    }

    fun interruptedCopyIsRemovedWithoutReadingOldKnowledge() {
        val interrupted = File(root, ".database.install-${UUID.randomUUID()}").apply { mkdirs() }
        File(interrupted, ".database.staging-owner").writeText("database-installer-stage-v1\n")
        File(interrupted, "incomplete.json").writeText("partial bytes")
        val result = installer.install(source) as DatabaseInstallResult.Ready
        assertEquals(DatabaseInstallAction.INSTALLED, result.action)
        assertFalse(interrupted.exists())
    }

    fun concurrentInstancesSerializeInstallation() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map { executor.submit<DatabaseInstallResult> { installer.install(source) } }
            val results = attempts.map { it.get(30, TimeUnit.SECONDS) as DatabaseInstallResult.Ready }
            assertEquals(setOf(DatabaseInstallAction.INSTALLED, DatabaseInstallAction.UNCHANGED), results.map { it.action }.toSet())
            assertTrue(results.all { it.bundle.manifest.datasetFingerprint == source.manifest.datasetFingerprint })
        } finally {
            executor.shutdownNow()
        }
    }
}
