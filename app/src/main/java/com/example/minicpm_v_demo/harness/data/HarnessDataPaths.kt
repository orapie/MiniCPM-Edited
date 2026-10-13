package com.example.minicpm_v_demo.harness.data

import android.content.Context
import java.io.File

data class HarnessDataPaths(val rootDir: File) {
    val manifestFile: File get() = File(rootDir, "manifest.json")
    val ragDir: File get() = File(rootDir, "rag")
    val ragIndexFile: File get() = File(ragDir, "index.json")
    val charactersRootDir: File get() = File(rootDir, "characters")
    val charactersDir: File get() = File(charactersRootDir, "characters")
    val storyEventsFile: File get() = File(charactersRootDir, "story/story_events.jsonl")
    val promptsDir: File get() = File(charactersRootDir, "prompts")
    val characterKnowledgeDir: File get() = File(charactersRootDir, "knowledge")
    val v1CharacterKnowledgeDir: File get() = File(characterKnowledgeDir, "v1")
    val v2CharacterKnowledgeDir: File get() = File(characterKnowledgeDir, "v2")
    val v1CharacterKnowledgeManifestFile: File get() = File(v1CharacterKnowledgeDir, "manifest.json")
    val v2CharacterKnowledgeManifestFile: File get() = File(v2CharacterKnowledgeDir, "manifest.json")
    val v2CharacterKnowledgeDatabaseFile: File get() = File(v2CharacterKnowledgeDir, "character_knowledge_v2.sqlite3")
    val v2CharacterKnowledgeFallbackFile: File get() = File(v2CharacterKnowledgeDir, "knowledge_fallback.json")
    val sessionsDir: File get() = File(rootDir, "sessions")
    val memoryDir: File get() = File(rootDir, "memory")
    val settingsDir: File get() = File(rootDir, "settings")

    fun ensureRuntimeDirs() {
        listOf(rootDir, sessionsDir, memoryDir, settingsDir).forEach { directory ->
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

fun harnessAssetsDir(): File {
    val candidates = listOf(
        File("app/src/main/assets/harness"),
        File("src/main/assets/harness"),
    )
    return candidates.firstOrNull { it.isDirectory }
        ?: error("Cannot locate test harness assets directory")
}
