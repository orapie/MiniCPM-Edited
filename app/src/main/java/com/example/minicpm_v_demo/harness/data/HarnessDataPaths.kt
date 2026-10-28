package com.example.minicpm_v_demo.harness.data

import android.content.Context
import java.io.File

data class HarnessDataPaths(val rootDir: File) {
    val sessionsDir: File get() = File(rootDir, "sessions")
    val memoryDir: File get() = File(rootDir, "memory")
    val settingsDir: File get() = File(rootDir, "settings")
    /** Developer-only, content-free RAG selection traces. */
    val diagnosticsDir: File get() = File(rootDir, "diagnostics")
    val ragTurnTraceFile: File get() = File(diagnosticsDir, "rag_turn_trace.jsonl")

    fun ensureRuntimeDirs() {
        listOf(rootDir, sessionsDir, memoryDir, settingsDir, diagnosticsDir).forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "Failed to create Harness directory: ${directory.absolutePath}"
            }
        }
    }

    companion object {
        fun from(context: Context): HarnessDataPaths =
            HarnessDataPaths(File(context.filesDir, "harness"))
    }
}
