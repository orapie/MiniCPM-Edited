package com.example.minicpm_v_demo.harness.data.database

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream

/** 安装器从统一的相对路径读取字节流，数据可以来自 APK 资源或显式指定的目录。 */
fun interface DatabaseBundleSource {
    fun open(relativePath: String): InputStream
}

/** The only APK knowledge namespace. It never probes assets/harness. */
class ApkDatabaseBundleSource(private val assets: AssetManager) : DatabaseBundleSource {
    override fun open(relativePath: String): InputStream {
        require(relativePath.isNotBlank() && relativePath.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
            '\\' !in relativePath && ':' !in relativePath) { "Unsafe APK database path" }
        return assets.open("database/$relativePath")
    }
}

/** Explicit directory adapter for offline validation and installation tests. */
class DirectoryDatabaseBundleSource(root: File) : DatabaseBundleSource {
    private val paths = DatabaseDataPaths(root)
    override fun open(relativePath: String): InputStream = paths.resolve(relativePath).inputStream()
}
