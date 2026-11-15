package com.example.minicpm_v_demo.harness.data

import android.content.Context
import com.example.minicpm_v_demo.harness.data.database.DatabaseRuntime
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AssetBootstrapper {
    private val startupCompleted = CountDownLatch(1)
    @Volatile private var started = false

    /** Avoid blocking Application.onCreate on bundle copying and validation. */
    @Synchronized
    fun startAsync(context: Context) {
        if (started) return
        started = true
        val application = context.applicationContext
        Thread({
            try {
                bootstrap(application)
                DatabaseRuntime.installation?.let { Log.i("DatabaseInstallation", it.toLogLine()) }
            } catch (error: Exception) {
                Log.e("AssetBootstrapper", "Failed to initialize database assets", error)
            } finally {
                startupCompleted.countDown()
            }
        }, "database-bootstrap").start()
    }

    fun awaitStartup(timeoutMillis: Long): Boolean = startupCompleted.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun bootstrap(context: Context): HarnessDataPaths {
        val paths = HarnessDataPaths.from(context.applicationContext)
        paths.ensureRuntimeDirs()
        // Explicit program-resource allowlist. Knowledge is installed only by
        // DatabaseBundleInstaller, never copied from the old Harness tree.
        val programAssets = listOf(
            "prompts/roleplay_system.prompt",
            "prompts/roleplay_public_career.json",
            "prompts/final_prompt_rewriter_system.prompt",
        )
        programAssets.forEach { relative ->
            val target = File(context.filesDir, "program/$relative")
            check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            context.assets.open(relative).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        DatabaseRuntime.initialize(context)
        return paths
    }

}
