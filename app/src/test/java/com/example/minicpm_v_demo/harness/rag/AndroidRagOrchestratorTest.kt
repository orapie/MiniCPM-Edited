package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.character.CharacterPromptDebug
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleHandle
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleValidator
import com.example.minicpm_v_demo.harness.data.database.databaseTestSource
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AndroidRagOrchestratorTest {
    private fun orchestrator(): AndroidRagOrchestrator {
        val repo = if (File("settings.gradle.kts").isFile) File(".") else File("..")
        return AndroidRagOrchestrator.fromBundle(DatabaseBundleHandle(DatabaseBundleValidator.validate(databaseTestSource())),
            File(repo, "app/src/main/assets/prompts/roleplay_system.prompt"))
    }

    @Test fun offAndCurrentDatabaseRagModesHaveExpectedBoundaries() {
        val off = orchestrator().compile("普通聊天", RagMode.OFF)
        assertFalse(off.turnTrace.external.vectorSearchExecuted)
        assertEquals("off_mode", off.turnTrace.external.vectorBackend)
        val query = "大学社会学"
        val rag = orchestrator().compile(query, RagMode.RAG, modelFamily = HarnessModelFamily.QWEN)
        assertTrue(rag.sources.isNotEmpty())
        assertTrue(rag.sources.all { it.source.startsWith("database/") })
        assertTrue(rag.contextDecisions.all { it.selectedChars <= it.originalChars })
        assertNull(rag.characterDebug)
        assertEquals(listOf(RagPromptStage.RAG_CONTEXT_COMPILED, RagPromptStage.FINAL_RENDERED,
            RagPromptStage.BACKEND_REQUEST), rag.stageLogs.map { it.stage })
        assertEquals(rag.renderedPrompt, rag.stageLogs[1].snapshot?.renderedPrompt)
        assertEquals(rag.rendered.splitPrompt.userPrompt, rag.stageLogs.last().snapshot?.userPrompt)
        assertFalse(rag.turnTrace.toJsonLine().contains(query))
        assertFalse(rag.turnTrace.toJsonLine().contains("<检索资料>"))
    }

    @Test fun characterEvidenceAndTraceRespectPromptAndPrivacyBoundaries() {
        val query = "你以前研究什么，现在在大学教什么？"
        val result = orchestrator().compile(query, RagMode.CHARACTER_RAG, "luo_ji", "evt-001")
        val debug = result.characterDebug as CharacterPromptDebug
        assertTrue(result.rendered.splitPrompt.systemPrompt.contains("大学教社会学"))
        assertEquals(query, result.rendered.splitPrompt.userPrompt)
        assertTrue(result.sources.isEmpty())
        assertTrue(result.turnTrace.external.vectorSearchExecuted)
        assertTrue(result.turnTrace.external.vectorCollection.startsWith("database:"))
        assertTrue(debug.promptChars <= 900)
        assertFalse(result.turnTrace.toDiagnosticText().contains(query))
        assertFalse(result.turnTrace.toJsonLine().contains("大学教社会学"))
        assertEquals(RagPromptStage.CHARACTER_BASE, result.stageLogs.first().stage)
        assertEquals(RagPromptStage.BACKEND_REQUEST, result.stageLogs.last().stage)
        val folder = Files.createTempDirectory("rag-trace").toFile()
        try {
            val file = File(folder, "trace.jsonl")
            RagTurnTraceWriter(file).append(result.turnTrace)
            RagTurnTraceWriter(file).append(result.turnTrace)
            assertEquals(2, file.readLines().size)
            assertFalse(file.readText().contains(query))
        } finally { folder.deleteRecursively() }
    }
}
