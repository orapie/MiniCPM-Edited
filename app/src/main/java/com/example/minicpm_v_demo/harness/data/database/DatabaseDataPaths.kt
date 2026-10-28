package com.example.minicpm_v_demo.harness.data.database

import java.io.File

/** One explicit knowledge root; it never discovers legacy character/Harness paths. */
class DatabaseDataPaths(root: File) {
    val rootDir: File = root.canonicalFile
    val manifestFile: File get() = resolve("manifest.json")

    fun resolve(relativePath: String): File {
        require(relativePath.isNotEmpty() && !relativePath.startsWith("/") &&
            '\\' !in relativePath && ':' !in relativePath &&
            relativePath.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        ) { "Unsafe database path: $relativePath" }
        val resolved = File(rootDir, relativePath).canonicalFile
        require(resolved.path.startsWith(rootDir.path + File.separator)) {
            "Database path escapes root: $relativePath"
        }
        return resolved
    }

    companion object {
        fun fromFilesDir(filesDir: File): DatabaseDataPaths = DatabaseDataPaths(File(filesDir, "database"))
        fun fromRepositoryRoot(repositoryRoot: File): DatabaseDataPaths = DatabaseDataPaths(File(repositoryRoot, "database"))
    }
}
