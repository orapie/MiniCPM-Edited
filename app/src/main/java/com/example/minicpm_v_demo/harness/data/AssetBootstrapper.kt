package com.example.minicpm_v_demo.harness.data

import android.content.Context
import com.example.minicpm_v_demo.harness.character.knowledge.CharacterKnowledgeContractValidator
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object AssetBootstrapper {
    private const val ASSET_ROOT = "harness"
    private const val KNOWLEDGE_ASSET_ROOT = "$ASSET_ROOT/characters/knowledge"
    private const val INSTALL_HASHES_FILE = ".asset_hashes"

    private data class KnowledgeBundleSpec(
        val relativePath: String,
        val targetDir: (HarnessDataPaths) -> File,
        val files: List<String>,
        val validate: (File) -> Unit,
    )

    private val knowledgeBundles = listOf(
        KnowledgeBundleSpec(
            relativePath = "v1",
            targetDir = HarnessDataPaths::v1CharacterKnowledgeDir,
            files = listOf("manifest.json", "claims.jsonl", "relations.jsonl"),
            validate = { CharacterKnowledgeContractValidator.validateV1(it) },
        ),
        KnowledgeBundleSpec(
            relativePath = "v2",
            targetDir = HarnessDataPaths::v2CharacterKnowledgeDir,
            files = listOf("manifest.json", "character_knowledge_v2.sqlite3", "knowledge_fallback.json"),
            validate = { CharacterKnowledgeContractValidator.validateV2(it) },
        ),
    )

    fun bootstrap(context: Context): HarnessDataPaths {
        val paths = HarnessDataPaths.from(context.applicationContext)
        paths.ensureRuntimeDirs()
        copyAssetDirectory(context, ASSET_ROOT, paths.rootDir, skipKnowledgeBundles = true)
        installKnowledgeBundles(paths) { relativePath ->
            context.assets.open("$KNOWLEDGE_ASSET_ROOT/$relativePath").use { it.readBytes() }
        }
        return paths
    }

    fun bootstrapFromDirectory(sourceRoot: File, paths: HarnessDataPaths) {
        require(sourceRoot.isDirectory) { "Harness asset directory does not exist: ${sourceRoot.absolutePath}" }
        paths.ensureRuntimeDirs()
        sourceRoot.walkTopDown().forEach { source ->
            val relative = source.relativeTo(sourceRoot).path
            if (relative.isEmpty()) return@forEach
            if (relative == "characters/knowledge" || relative.startsWith("characters/knowledge${File.separator}")) {
                return@forEach
            }
            val target = File(paths.rootDir, relative)
            if (source.isDirectory) {
                check(target.isDirectory || target.mkdirs()) { "Failed to create ${target.absolutePath}" }
            } else {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        }
        val sourceKnowledgeDir = File(sourceRoot, "characters/knowledge")
        installKnowledgeBundles(paths) { relativePath ->
            File(sourceKnowledgeDir, relativePath).readBytes()
        }
    }

    private fun copyAssetDirectory(
        context: Context,
        assetPath: String,
        targetDir: File,
        skipKnowledgeBundles: Boolean,
    ) {
        val children = context.assets.list(assetPath).orEmpty()
        check(targetDir.isDirectory || targetDir.mkdirs()) { "Failed to create ${targetDir.absolutePath}" }
        for (name in children) {
            val childAssetPath = "$assetPath/$name"
            if (skipKnowledgeBundles && childAssetPath == KNOWLEDGE_ASSET_ROOT) continue
            val childNames = context.assets.list(childAssetPath).orEmpty()
            if (childNames.isNotEmpty()) {
                copyAssetDirectory(context, childAssetPath, File(targetDir, name), skipKnowledgeBundles)
            } else {
                val target = File(targetDir, name)
                target.parentFile?.mkdirs()
                context.assets.open(childAssetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun installKnowledgeBundles(
        paths: HarnessDataPaths,
        sourceBytes: (String) -> ByteArray,
    ) {
        knowledgeBundles.forEach { spec ->
            val expectedHashes = spec.files.associateWith { file ->
                sha256(sourceBytes("${spec.relativePath}/$file"))
            }
            val targetDir = spec.targetDir(paths)
            if (isCurrent(targetDir, expectedHashes)) return@forEach

            val parent = targetDir.parentFile
                ?: error("Knowledge bundle has no parent: ${targetDir.absolutePath}")
            check(parent.isDirectory || parent.mkdirs()) { "Failed to create ${parent.absolutePath}" }
            val temporaryDir = File(parent, ".${targetDir.name}.install-${UUID.randomUUID()}")
            check(temporaryDir.mkdirs()) { "Failed to create ${temporaryDir.absolutePath}" }
            try {
                spec.files.forEach { file ->
                    val target = File(temporaryDir, file)
                    target.parentFile?.mkdirs()
                    target.writeBytes(sourceBytes("${spec.relativePath}/$file"))
                }
                spec.validate(temporaryDir)
                writeHashes(File(temporaryDir, INSTALL_HASHES_FILE), expectedHashes)
                replaceDirectory(targetDir, temporaryDir)
            } catch (error: Exception) {
                temporaryDir.deleteRecursively()
                throw error
            }
        }
    }

    private fun isCurrent(targetDir: File, expectedHashes: Map<String, String>): Boolean {
        if (!targetDir.isDirectory || !expectedHashes.keys.all { File(targetDir, it).isFile }) return false
        val stateFile = File(targetDir, INSTALL_HASHES_FILE)
        if (!stateFile.isFile) return false
        if (readHashes(stateFile) != expectedHashes) return false
        return expectedHashes.all { (path, expectedHash) ->
            sha256(File(targetDir, path)) == expectedHash
        }
    }

    private fun replaceDirectory(targetDir: File, temporaryDir: File) {
        val parent = targetDir.parentFile ?: error("Knowledge bundle has no parent")
        val backupDir = File(parent, ".${targetDir.name}.backup-${UUID.randomUUID()}")
        var movedCurrent = false
        try {
            if (targetDir.exists()) {
                check(targetDir.renameTo(backupDir)) {
                    "Failed to preserve current knowledge bundle: ${targetDir.absolutePath}"
                }
                movedCurrent = true
            }
            check(temporaryDir.renameTo(targetDir)) {
                "Failed to activate validated knowledge bundle: ${targetDir.absolutePath}"
            }
            if (movedCurrent) backupDir.deleteRecursively()
        } catch (error: Exception) {
            if (!targetDir.exists() && movedCurrent && backupDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            throw error
        }
    }

    private fun writeHashes(file: File, hashes: Map<String, String>) {
        file.writeText(hashes.entries.sortedBy { it.key }.joinToString("\n") { (path, hash) -> "$path=$hash" })
    }

    private fun readHashes(file: File): Map<String, String> = file.readLines()
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0 || separator == line.lastIndex) null
            else line.substring(0, separator) to line.substring(separator + 1)
        }
        .toMap()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
