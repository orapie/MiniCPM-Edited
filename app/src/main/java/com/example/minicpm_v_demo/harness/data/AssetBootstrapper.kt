package com.example.minicpm_v_demo.harness.data

import android.content.Context
import com.example.minicpm_v_demo.harness.data.database.DatabaseRuntime
import java.io.File

object AssetBootstrapper {
    fun bootstrap(context: Context): HarnessDataPaths {
        val paths = HarnessDataPaths.from(context.applicationContext)
        paths.ensureRuntimeDirs()
        // Explicit program-resource allowlist. Knowledge is installed only by
        // DatabaseBundleInstaller, never copied from the old Harness tree.
        val programAssets = listOf("prompts/roleplay_system.prompt")
        programAssets.forEach { relative ->
            val target = File(context.filesDir, "program/$relative")
            check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            context.assets.open(relative).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        DatabaseRuntime.initialize(context)
        return paths
    }

}
