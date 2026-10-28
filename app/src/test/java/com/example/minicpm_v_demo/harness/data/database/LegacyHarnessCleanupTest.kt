package com.example.minicpm_v_demo.harness.data.database

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class LegacyHarnessCleanupTest {
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test fun onlyVerifiedFilesAreRemovedAfterDatabaseIsReady() {
        val root = Files.createTempDirectory("legacy-cleanup").toFile()
        try {
            val original = File(root, "harness/rag/index.json").apply { parentFile!!.mkdirs(); writeText("old") }
            val modified = File(root, "harness/rag/custom.txt").apply { writeText("user content") }
            val sessions = File(root, "harness/sessions/recent_session.json").apply { parentFile!!.mkdirs(); writeText("session") }
            val model = File(root, "models/model.gguf").apply { parentFile!!.mkdirs(); writeText("model") }
            val cleanup = LegacyHarnessCleanup(root, mapOf("rag/index.json" to hash("old"), "rag/custom.txt" to hash("old")))
            cleanup.run(false)
            assertTrue(original.exists())
            assertFalse(File(root, ".legacy-harness-cleanup-v1").exists())
            cleanup.run(true)
            assertFalse(original.exists())
            assertEquals("user content", modified.readText())
            assertEquals("session", sessions.readText())
            assertEquals("model", model.readText())
            original.writeText("old")
            cleanup.run(true)
            assertTrue(original.exists()) // marker makes migration one-time
        } finally { root.deleteRecursively() }
    }

    @Test fun symlinkedLegacyFileIsNeverFollowedOrDeleted() {
        val root = Files.createTempDirectory("legacy-symlink").toFile()
        try {
            val target = File(root, "user.txt").apply { writeText("old") }
            val legacy = File(root, "harness/rag/index.json").apply { parentFile!!.mkdirs() }
            Files.createSymbolicLink(legacy.toPath(), target.toPath())
            LegacyHarnessCleanup(root, mapOf("rag/index.json" to hash("old"))).run(true)
            assertEquals("old", target.readText())
            assertTrue(Files.isSymbolicLink(legacy.toPath()))
        } finally { root.deleteRecursively() }
    }
}
