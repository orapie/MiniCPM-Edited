package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.attribution.AgentBPromptFormat
import com.example.minicpm_v_demo.harness.attribution.EvidenceRelation
import com.example.minicpm_v_demo.harness.attribution.FakeFinalPromptRewriteService
import com.example.minicpm_v_demo.harness.attribution.FinalPromptDraft
import com.example.minicpm_v_demo.harness.attribution.FinalPromptRewriteService
import com.example.minicpm_v_demo.harness.attribution.FocalRole
import com.example.minicpm_v_demo.harness.attribution.NoopFinalPromptRewriteService
import com.example.minicpm_v_demo.harness.attribution.Perspective
import com.example.minicpm_v_demo.harness.attribution.RawPromptPackage
import com.example.minicpm_v_demo.harness.attribution.ReferenceAttribution
import com.example.minicpm_v_demo.harness.attribution.ReferenceDisposition
import com.example.minicpm_v_demo.harness.character.CharacterPromptDebug
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleHandle
import com.example.minicpm_v_demo.harness.rag.VectorRetrievalFeatureFlags
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleValidator
import com.example.minicpm_v_demo.harness.data.database.databaseTestSource
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AndroidRagOrchestratorTest {
    @Test fun jsonIsSentOnlyAfterSuccessfulAttributionAndPreservesTheSystem() {
        val question = "大学社会学"
        orchestrator(FakeFinalPromptRewriteService(::validDraft)).use {
            val text = it.compile(question, RagMode.CHARACTER_RAG, "luo_ji", "evt-001")
            val json = it.compile(question, RagMode.CHARACTER_RAG, "luo_ji", "evt-001",
                agentBPromptFormat = AgentBPromptFormat.JSON)
            val backend = json.stageLogs.last { stage -> stage.stage == RagPromptStage.BACKEND_REQUEST }.snapshot
            assertEquals(AgentBPromptFormat.TEXT, text.attributionTrace?.finalPromptFormat)
            assertEquals(AgentBPromptFormat.JSON, json.attributionTrace?.finalPromptFormat)
            assertEquals(text.attributionTrace?.rawPromptFingerprint, json.attributionTrace?.rawPromptFingerprint)
            assertEquals(text.rendered.splitPrompt.systemPrompt, backend?.systemPrompt)
            assertEquals(json.rendered.splitPrompt.userPrompt, backend?.userPrompt)
            val user = SimpleJsonParser(checkNotNull(backend?.userPrompt)).parse().asJsonObject("backend")
            assertEquals(question, user["question"])
            assertEquals(text.sources, json.sources)
        }
        orchestrator().use {
            val text = it.compile(question, RagMode.CHARACTER_RAG, "luo_ji", "evt-001")
            val json = it.compile(question, RagMode.CHARACTER_RAG, "luo_ji", "evt-001",
                agentBPromptFormat = AgentBPromptFormat.JSON)
            assertEquals(text.rendered.splitPrompt, json.rendered.splitPrompt)
            assertEquals("disabled", json.attributionTrace?.status)
            assertEquals(AgentBPromptFormat.TEXT, json.attributionTrace?.finalPromptFormat)
        }
    }

    @Test fun completePublicCardQuestionDoesNotSendExternalReferencesOrRequestAttribution() {
        val question = "你以前做过什么工作？"
        val service = FakeFinalPromptRewriteService { error("Agent A should be skipped") }
        val enabled = orchestrator(service).use {
            it.compile(question, RagMode.CHARACTER_RAG, "zhang_beihai", "evt-125")
        }
        val disabled = orchestrator().use {
            it.compile(question, RagMode.CHARACTER_RAG, "zhang_beihai", "evt-125")
        }
        assertEquals(enabled.rendered.splitPrompt, disabled.rendered.splitPrompt)
        assertTrue(enabled.rendered.splitPrompt.systemPrompt.contains("我从海军转入太空军，从事政治工作"))
        assertEquals("我从海军转入太空军，从事政治工作。", enabled.publicProfileReply)
        assertEquals(question, enabled.rendered.splitPrompt.userPrompt)
        assertTrue(enabled.turnTrace.external.vectorSearchExecuted)
        assertFalse(enabled.turnTrace.external.externalInjectionRequested)
        assertTrue(enabled.sources.isEmpty())
        assertEquals("not_requested", enabled.attributionTrace?.status)
        assertEquals("public_profile_sufficient", enabled.attributionTrace?.reason)
        assertEquals("public_profile_sufficient", enabled.stageLogs
            .single { it.stage == RagPromptStage.EXTERNAL_CONTEXT_COMPILED }.reason)
    }

    @Test fun publicCardFactSurvivesAttributionSuccessAndFallback() {
        fun compile(service: FinalPromptRewriteService) = orchestrator(service).use {
            it.compile("你以前做过什么工作，又怎么看待责任？", RagMode.CHARACTER_RAG, "zhang_beihai", "evt-125")
        }

        val success = compile(FakeFinalPromptRewriteService { validDraft(it) })
        val fallback = compile(FakeFinalPromptRewriteService { null })
        val disabled = compile(NoopFinalPromptRewriteService)
        assertEquals("success", success.attributionTrace?.status)
        assertEquals("fallback", fallback.attributionTrace?.status)
        assertEquals("disabled", disabled.attributionTrace?.status)
        assertTrue(success.sources.isNotEmpty())
        assertNull(success.publicProfileReply)
        assertTrue(success.rendered.splitPrompt.userPrompt.contains("[参考资料 "))
        assertEquals(fallback.rendered.splitPrompt.userPrompt, disabled.rendered.splitPrompt.userPrompt)
        val system = success.rendered.splitPrompt.systemPrompt
        assertTrue(system.contains("我从海军转入太空军，从事政治工作"))
        assertTrue(system.contains("不从检索片段补充经历"))
        assertEquals(system, fallback.rendered.splitPrompt.systemPrompt)
        assertEquals(system, disabled.rendered.splitPrompt.systemPrompt)
    }

    private fun orchestrator(
        rewriteService: FinalPromptRewriteService = NoopFinalPromptRewriteService,
    ): AndroidRagOrchestrator {
        val repo = if (File("settings.gradle.kts").isFile) File(".") else File("..")
        return AndroidRagOrchestrator.fromBundle(DatabaseBundleHandle(DatabaseBundleValidator.validate(databaseTestSource())),
            File(repo, "app/src/main/assets/prompts/roleplay_system.prompt"), finalPromptRewriteService = rewriteService)
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
        assertFalse(result.rendered.splitPrompt.systemPrompt.contains("大学教社会学"))
        assertFalse(result.rendered.splitPrompt.systemPrompt.contains("不从检索片段补充经历"))
        assertTrue(result.rendered.splitPrompt.userPrompt.contains(query))
        assertTrue(result.sources.isNotEmpty())
        assertTrue(result.rendered.splitPrompt.userPrompt.contains("本轮检索资料"))
        assertEquals("disabled", result.attributionTrace?.status)
        assertEquals("disabled", result.attributionTrace?.reason)
        assertTrue(result.stageLogs.any { it.stage == RagPromptStage.RAW_PROMPT_COMPILED && it.status == RagPromptStageStatus.EMITTED })
        assertTrue(result.stageLogs.any { it.stage == RagPromptStage.AGENT_A_REQUEST && it.status == RagPromptStageStatus.SKIPPED && it.reason == "disabled" })
        assertTrue(result.stageLogs.any { it.stage == RagPromptStage.EXTERNAL_CONTEXT_COMPILED && it.status == RagPromptStageStatus.EMITTED })
        assertTrue(result.turnTrace.external.vectorSearchExecuted)
        assertTrue(result.turnTrace.external.vectorCollection.startsWith("database:"))
        assertTrue(result.turnTrace.external.indexVersion.isNotBlank())
        assertEquals(result.turnTrace.external.vectorCandidateIds.size, result.turnTrace.external.vectorCandidateScores.size)
        assertTrue(result.turnTrace.external.decisionReasonCodes.any { it.contains("parent=") && it.contains("chars=") })
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
    @Test fun characterReferenceChangesWithQuestionAndIsBounded() {
        val first = orchestrator().compile("罗輯现在的身份是什么", RagMode.CHARACTER_RAG, "shi_qiang", "evt-118")
        val second = orchestrator().compile("大学社会学", RagMode.CHARACTER_RAG, "luo_ji", "evt-001")
        for (result in listOf(first, second)) {
            assertTrue("No context: ${result.turnTrace.external.decisionReasonCodes}", result.contextText.isNotBlank())
            assertTrue(result.contextText.length <= 620) // includes separators between blocks
            assertTrue(result.sources.all { it.source.startsWith("database/") })
            assertTrue(result.turnTrace.external.injectedSourceIds.isNotEmpty())
            assertTrue(result.rendered.splitPrompt.userPrompt.contains(result.contextText))
            assertTrue(result.rendered.splitPrompt.systemPrompt.contains("不是角色亲身记忆"))
        }
        assertTrue(first.rendered.splitPrompt.userPrompt.contains("罗輯现在的身份是什么"))
        assertNotEquals(first.contextText, second.contextText)
    }

    @Test fun validFakeRewriteIsMaterializedOnlyForAuthorizedReferences() {
        val result = orchestrator(FakeFinalPromptRewriteService(::validDraft))
            .compile("大学社会学", RagMode.CHARACTER_RAG, "luo_ji", "evt-001")

        assertTrue(result.sources.isNotEmpty())
        assertTrue(result.rendered.splitPrompt.userPrompt.contains("[参考资料 "))
        assertTrue(result.rendered.splitPrompt.userPrompt.contains("当前角色=罗辑"))
        assertTrue(result.rendered.splitPrompt.userPrompt.contains(result.contextText.substringAfter("\n").substringBefore("\n")))
        assertEquals("success", result.attributionTrace?.status)
        assertEquals("fake", result.attributionTrace?.modelId)
        assertTrue(result.attributionTrace?.finalPromptFingerprint != "none")
        assertTrue(result.stageLogs.any { it.stage == RagPromptStage.FINAL_PROMPT_VALIDATED && it.status == RagPromptStageStatus.EMITTED })
    }

    @Test fun invalidAgentDraftFallsBackToRawPromptWithAReasonCode() {
        val result = orchestrator(FakeFinalPromptRewriteService { raw -> validDraft(raw).copy(userPrompt = "篡改的问题") })
            .compile("大学社会学", RagMode.CHARACTER_RAG, "luo_ji", "evt-001", agentBPromptFormat = AgentBPromptFormat.JSON)

        assertEquals("fallback", result.attributionTrace?.status)
        assertEquals("user_question_mutated", result.attributionTrace?.reason)
        assertEquals(AgentBPromptFormat.TEXT, result.attributionTrace?.finalPromptFormat)
        assertFalse(result.rendered.splitPrompt.userPrompt.contains("[参考资料 "))
        assertTrue(result.rendered.splitPrompt.userPrompt.contains("玩家：大学社会学"))
        assertTrue(result.stageLogs.any {
            it.stage == RagPromptStage.FINAL_PROMPT_VALIDATED && it.status == RagPromptStageStatus.SKIPPED &&
                it.reason == "user_question_mutated"
        })
    }

    @Test fun disablingVectorRetrievalLeavesUserMessageWithoutReferences() {
        val repo = if (File("settings.gradle.kts").isFile) File(".") else File("..")
        val bundle = DatabaseBundleHandle(DatabaseBundleValidator.validate(databaseTestSource()))
        val instance = AndroidRagOrchestrator.fromBundle(bundle,
            File(repo, "app/src/main/assets/prompts/roleplay_system.prompt"), VectorRetrievalFeatureFlags(false, false))
        val result = instance.compile("你的来历是什么？", RagMode.CHARACTER_RAG, "luo_ji", "evt-001",
            agentBPromptFormat = AgentBPromptFormat.JSON)
        assertFalse(result.turnTrace.external.vectorSearchExecuted)
        assertTrue(result.sources.isEmpty())
        assertTrue(result.contextText.isEmpty())
        assertEquals("你的来历是什么？", result.rendered.splitPrompt.userPrompt)
        assertTrue(result.stageLogs.any { it.stage == RagPromptStage.EXTERNAL_CONTEXT_COMPILED && it.status == RagPromptStageStatus.SKIPPED })
    }

    private fun validDraft(raw: RawPromptPackage) = FinalPromptDraft(
        schemaVersion = RawPromptPackage.SCHEMA_VERSION,
        rawPromptFingerprint = raw.fingerprint,
        systemPrompt = raw.systemPrompt,
        userPrompt = raw.userQuestion,
        references = raw.references.map { reference -> ReferenceAttribution(
            referenceId = reference.id,
            focalRole = FocalRole.UNKNOWN,
            perspective = Perspective.UNKNOWN,
            evidenceRelation = EvidenceRelation.UNKNOWN,
            disposition = ReferenceDisposition.CAUTION,
            confidence = 0.0,
        ) },
    )

}
