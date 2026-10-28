package com.example.minicpm_v_demo.harness.data.database

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

enum class DatabaseInstallAction { INSTALLED, UNCHANGED, UPDATED, SWITCHED, REPAIRED, RETAINED_PREVIOUS }

class DatabaseBundleHandle internal constructor(val validated: ValidatedDatabaseBundle) {
    val paths: DatabaseDataPaths get() = validated.paths
    val manifest: DatabaseBundleManifest get() = validated.manifest
}

sealed interface DatabaseInstallResult {
    data class Ready(
        val bundle: DatabaseBundleHandle,
        val action: DatabaseInstallAction,
        val requestedFingerprint: String,
        val reason: String? = null,
    ) : DatabaseInstallResult

    /** Existing bytes may be preserved for recovery, but no consumer receives a handle. */
    data class Unavailable(val requestedDatasetId: String?, val reason: String) : DatabaseInstallResult

    fun toLogLine(): String = when (this) {
        is Ready -> "database status=$action dataset=${bundle.manifest.datasetId} " +
            "active=${bundle.manifest.datasetFingerprint} requested=$requestedFingerprint reason=${reason ?: "none"}"
        is Unavailable -> "database status=unavailable requestedDataset=${requestedDatasetId ?: "unknown"} reason=$reason"
    }
}

/**
 * A validated whole-directory replacement on the same filesystem. The fixed
 * previous directory is also a recovery journal for a process exit between the
 * two renames. Models, Harness sessions and old knowledge paths are never read.
 */
class DatabaseBundleInstaller(
    filesDirectory: File,
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    private val parent = filesDirectory.canonicalFile
    private val active = File(parent, "database")
    private val previous = File(parent, ".database.previous")

    fun install(source: DatabaseBundleSource): DatabaseInstallResult = synchronized(PROCESS_LOCK) {
        try {
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create database parent" }
            val lockFile = File(parent, ".database.install.lock")
            require(lockFile.canonicalFile == lockFile.absoluteFile) { "Unsafe install lock path" }
            RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.use { channel -> channel.lock().use { installLocked(source) } }
            }
        } catch (_: Exception) {
            DatabaseInstallResult.Unavailable(null, "installation_lock_or_recovery_failed")
        }
    }

    private fun installLocked(source: DatabaseBundleSource): DatabaseInstallResult {
        recoverPrevious()
        cleanInterruptedStaging()
        val installed = validateExisting(active)
        var requested: DatabaseBundleManifest? = null
        var staging: File? = null
        var reason = "manifest_unreadable"
        try {
            val manifestBytes = source.open("manifest.json").use { it.readBytes() }
            requested = DatabaseBundleManifest.parse(manifestBytes.toString(Charsets.UTF_8))
            reason = "manifest_invalid"
            require(requested.datasetId.matches(Regex("[a-z0-9_-]+"))) { "Invalid dataset ID" }
            require(requested.schemaVersion == "1.0" && requested.files.isNotEmpty() &&
                requested.computeFingerprint() == requested.datasetFingerprint) { "Invalid manifest" }
            if (installed?.manifest?.datasetFingerprint == requested.datasetFingerprint) {
                return DatabaseInstallResult.Ready(installed, DatabaseInstallAction.UNCHANGED, requested.datasetFingerprint)
            }
            staging = File(parent, ".database.install-${UUID.randomUUID()}")
            check(staging.mkdir()) { "Cannot create database staging directory" }
            writeSynced(File(staging, STAGING_OWNER), STAGING_SIGNATURE.toByteArray(Charsets.UTF_8))
            val paths = DatabaseDataPaths(staging)
            requested.paths.values.forEach(paths::resolve)
            requested.files.keys.forEach { relative ->
                require(relative != "manifest.json") { "Manifest cannot hash itself" }
                require(relative != STAGING_OWNER) { "Reserved installer filename" }
                paths.resolve(relative)
            }
            reason = "payload_copy_failed"
            writeSynced(paths.manifestFile, manifestBytes)
            requested.files.keys.sorted().forEach { relative ->
                val target = paths.resolve(relative)
                check(requireNotNull(target.parentFile).let { it.isDirectory || it.mkdirs() }) { "Cannot create payload parent" }
                source.open(relative).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
                }
            }
            check(File(staging, STAGING_OWNER).delete()) { "Cannot seal staging bundle" }
            reason = "payload_validation_failed"
            validateInstalled(staging)
            reason = "activation_failed"
            val hadPreviousBytes = active.exists()
            if (hadPreviousBytes) {
                require(active.canonicalFile == active.absoluteFile) { "Active database cannot be a symlink" }
                check(rename(active, previous)) { "Cannot preserve current database" }
            }
            val activated = try {
                check(rename(staging, active)) { "Cannot activate database" }
                staging = null
                validateInstalled(active)
            } catch (error: Exception) {
                if (active.exists()) preserve(active, ".database.rejected-")
                if (previous.exists()) check(rename(previous, active)) { "Cannot roll back database" }
                throw error
            }
            // Cleanup is after commit: a cleanup error must never roll back a
            // validated new bundle into a partially deleted previous directory.
            val cleanupDeferred = runCatching { discardOrPreserve(previous) }.isFailure
            val action = when {
                installed == null && hadPreviousBytes -> DatabaseInstallAction.REPAIRED
                installed == null -> DatabaseInstallAction.INSTALLED
                installed.manifest.datasetId != requested.datasetId -> DatabaseInstallAction.SWITCHED
                else -> DatabaseInstallAction.UPDATED
            }
            return DatabaseInstallResult.Ready(activated, action, requested.datasetFingerprint,
                if (cleanupDeferred) "previous_cleanup_deferred" else null)
        } catch (_: Exception) {
            val retained = validateExisting(active)
            return if (requested != null && retained != null && requested.datasetId == retained.manifest.datasetId) {
                DatabaseInstallResult.Ready(retained, DatabaseInstallAction.RETAINED_PREVIOUS,
                    requested.datasetFingerprint, reason)
            } else {
                DatabaseInstallResult.Unavailable(requested?.datasetId?.takeIf { it.matches(Regex("[a-z0-9_-]+")) }, reason)
            }
        } finally {
            staging?.let { runCatching { deleteOwnedTree(it) } }
        }
    }

    private fun recoverPrevious() {
        require(active.canonicalFile == active.absoluteFile) { "Unsafe active database directory" }
        require(previous.canonicalFile == previous.absoluteFile) { "Unsafe recovery directory" }
        if (!previous.exists()) return
        if (!active.exists()) {
            check(rename(previous, active)) { "Cannot recover database" }
        } else if (validateExisting(active) != null) {
            discardOrPreserve(previous)
        } else if (validateExisting(previous) != null) {
            preserve(active, ".database.rejected-")
            check(rename(previous, active)) { "Cannot recover valid database" }
        } else {
            preserve(previous, ".database.preserved-")
        }
    }

    private fun cleanInterruptedStaging() {
        parent.listFiles().orEmpty().filter { it.name.matches(Regex("\\.database\\.install-[0-9a-f-]{36}")) }.forEach { directory ->
            val marker = File(directory, STAGING_OWNER)
            val owned = directory.canonicalFile == directory.absoluteFile && directory.isDirectory &&
                marker.canonicalFile == marker.absoluteFile && marker.isFile && marker.readText() == STAGING_SIGNATURE
            if (owned || validateExisting(directory) != null) deleteOwnedTree(directory)
            else preserve(directory, ".database.preserved-")
        }
    }

    private fun validateExisting(directory: File): DatabaseBundleHandle? =
        runCatching { validateInstalled(directory) }.getOrNull()

    private fun validateInstalled(directory: File): DatabaseBundleHandle {
        require(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) { "Missing/unsafe database directory" }
        val bundle = DatabaseBundleValidator.validate(DatabaseDataPaths(directory))
        val actualFiles = mutableSetOf<String>()
        fun visit(file: File) {
            require(file.canonicalFile == file.absoluteFile) { "Symlink in installed database" }
            if (file.isDirectory) {
                requireNotNull(file.listFiles()).forEach(::visit)
            } else {
                require(file.isFile) { "Invalid database entry" }
                actualFiles += file.relativeTo(directory).invariantSeparatorsPath
            }
        }
        visit(directory)
        require(actualFiles == bundle.manifest.files.keys + "manifest.json") { "Unexpected database payload files" }
        return DatabaseBundleHandle(bundle)
    }

    private fun discardOrPreserve(directory: File) {
        if (!directory.exists()) return
        if (validateExisting(directory) != null) deleteOwnedTree(directory)
        else preserve(directory, ".database.preserved-")
    }

    private fun preserve(directory: File, prefix: String) {
        check(rename(directory, File(parent, "$prefix${UUID.randomUUID()}"))) { "Cannot isolate previous database bytes" }
    }

    /** Called only for this installer's own staging or validated previous bundle. */
    private fun deleteOwnedTree(directory: File) {
        require(directory.parentFile?.canonicalFile == parent && directory != active) { "Unsafe cleanup target" }
        fun remove(file: File) {
            if (file.canonicalFile == file.absoluteFile && file.isDirectory) {
                requireNotNull(file.listFiles()).forEach(::remove)
            }
            check(file.delete() || !file.exists()) { "Cannot remove installer-owned entry" }
        }
        remove(directory)
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }

    companion object {
        private val PROCESS_LOCK = Any()
        private const val STAGING_OWNER = ".database.staging-owner"
        private const val STAGING_SIGNATURE = "database-installer-stage-v1\n"
    }
}
