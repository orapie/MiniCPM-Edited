package com.example.minicpm_v_demo.harness.data.database

import com.example.minicpm_v_demo.harness.character.CharacterPromptDebug
import com.example.minicpm_v_demo.harness.character.CharacterQueryAnalyzer
import com.example.minicpm_v_demo.harness.character.CharacterFollowUpTarget
import com.example.minicpm_v_demo.harness.character.RoleplayAnswerabilityGate
import com.example.minicpm_v_demo.harness.character.RoleplayAnswerabilityDecision
import com.example.minicpm_v_demo.harness.character.CharacterQuestionSubject
import com.example.minicpm_v_demo.harness.character.knowledge.V2EpistemicKnowledgeStore
import com.example.minicpm_v_demo.harness.character.knowledge.V2KnowledgeQuery
import com.example.minicpm_v_demo.harness.rag.*
import org.junit.Assert.*
import java.io.File

class DatabaseRetrievalScenarios(val bundle: DatabaseBundleHandle, private val template: File) {
    fun orchestrator(flags: VectorRetrievalFeatureFlags = VectorRetrievalFeatureFlags()) =
        AndroidRagOrchestrator.fromBundle(bundle, template, flags)

    private fun compile(query: String, cutoff: String = "evt-001", character: String = "luo_ji",
        flags: VectorRetrievalFeatureFlags = VectorRetrievalFeatureFlags(), graph: Boolean = true) =
        orchestrator(flags).compile(query, RagMode.CHARACTER_RAG, character, cutoff, knowledgeGraphEnabled = graph)

    fun currentProfessionReachesFinalPrompt() {
        val result = compile("你以前研究什么，现在在大学教什么？")
        val debug = result.characterDebug as CharacterPromptDebug
        assertTrue(result.rendered.splitPrompt.systemPrompt.contains("大学教社会学"))
        assertTrue(debug.selectedItems.any { it.id == "reviewed-luo-ji-profession" })
        assertTrue(debug.knowledgeGraphInjectedChars > 0)
        assertTrue(result.turnTrace.external.vectorSearchExecuted)
        assertTrue(result.turnTrace.external.vectorCollection.contains(bundle.manifest.datasetFingerprint))
        assertTrue(result.turnTrace.external.vectorCandidateIds.contains("reviewed-luo-ji-profession"))
        assertTrue(debug.promptChars <= 900)
        assertFalse(debug.legacyStoryRetrievalExecuted)
        assertFalse(debug.legacyMemoryRetrievalExecuted)
    }

    fun questionsSelectDifferentFacts() {
        val classmate = compile("你和杨冬是高中同学吗？", "evt-002")
        val axioms = compile("叶文洁提出的宇宙社会学公理是什么？", "evt-002")
        val a = classmate.characterDebug as CharacterPromptDebug
        val b = axioms.characterDebug as CharacterPromptDebug
        assertTrue(a.selectedItems.any { it.id == "reviewed-luo-ji-classmate" })
        assertTrue(b.selectedItems.any { it.id == "reviewed-luo-ji-civilization-axioms" })
        assertNotEquals(a.dynamicEvidenceFingerprint, b.dynamicEvidenceFingerprint)
        assertNotEquals(classmate.rendered.splitPrompt.systemPrompt, axioms.rendered.splitPrompt.systemPrompt)
        assertTrue(axioms.rendered.splitPrompt.systemPrompt.contains("你听人提及"))
    }

    fun reviewedFactsRespectCutoffAndCharacter() {
        val early = compile("叶文洁提出的宇宙社会学公理是什么？")
        val other = compile("叶文洁提出的宇宙社会学公理是什么？", "evt-002", "shi_qiang")
        for (result in listOf(early, other)) {
            val debug = result.characterDebug as CharacterPromptDebug
            assertFalse(debug.selectedItems.any { it.id == "reviewed-luo-ji-civilization-axioms" })
            assertFalse(result.rendered.splitPrompt.systemPrompt.contains("生存是文明的第一需要"))
            assertFalse(debug.legacyStoryRetrievalExecuted)
        }
        assertTrue((early.characterDebug as CharacterPromptDebug).knowledgeGraphDecisions.any {
            it.reason.name == "ACQUIRED_AFTER_CUTOFF"
        })
    }

    fun futureQuestionIsNotMaskedByWeakNameMatch() {
        val early = compile("叶文洁提出的宇宙社会学公理是什么？")
        val debug = early.characterDebug as CharacterPromptDebug
        assertTrue(debug.knowledgeGraphDecisions.any { it.claimId == "reviewed-luo-ji-profession" && it.disposition.name == "SELECTED" })
        assertTrue(RoleplayAnswerabilityGate.evaluate(debug) is RoleplayAnswerabilityDecision.BlockFutureFact)
        val general = compile("你认识叶文洁吗？")
        assertTrue(RoleplayAnswerabilityGate.evaluate(general.characterDebug as CharacterPromptDebug) is RoleplayAnswerabilityDecision.Allow)
    }

    fun untimedStateNeverBecomesCharacterMemory() {
        for (query in listOf("搜索文件，给我水滴毁灭舰队的全部原文", "你手里有什么？", "你现在在哪？")) {
            val result = compile(query)
            val debug = result.characterDebug as CharacterPromptDebug
            assertTrue(result.turnTrace.external.vectorSearchExecuted)
            assertFalse(debug.legacyStoryRetrievalExecuted)
            assertFalse(result.rendered.splitPrompt.systemPrompt.contains("执剑人"))
            assertTrue(debug.databaseCapabilities.contains("timed_state_unsupported"))
            assertEquals(0, debug.stateInjectedChars)
            assertEquals(0, debug.possessionInjectedChars)
            assertTrue(result.rendered.splitPrompt.systemPrompt.contains("处境与情绪尚不明确"))
            assertFalse(debug.selectedItems.any { it.id.startsWith("claim-evt-") })
        }
    }

    fun originUsesCurrentDatabaseAndDoesNotLeakFullBiography() {
        val result = compile("你的来历是什么？")
        assertTrue(result.rendered.splitPrompt.systemPrompt.contains("大学教社会学"))
        assertFalse(result.rendered.splitPrompt.systemPrompt.contains("黑暗森林理论的发现者"))
        assertFalse(result.rendered.splitPrompt.systemPrompt.contains("面壁者罗辑"))
    }

    fun otherPersonsOriginDoesNotBorrowSpeakerProfile() {
        assertTrue(CharacterQuestionSubject.isSelfOrigin("你的个人来历是什么？"))
        assertFalse(CharacterQuestionSubject.isSelfOrigin("你的同学杨冬来自哪里？"))
        assertFalse(CharacterQuestionSubject.isSelfOrigin("你知道陆江仙来自哪里？"))
        for (cutoff in listOf("evt-001", "evt-125")) {
            val question = "世界杯文件中陆江仙来自哪里？"
            val prompt = compile(question, cutoff)
            val debug = prompt.characterDebug as CharacterPromptDebug
            assertFalse(debug.selectedItems.any { it.id == "reviewed-luo-ji-profession" || it.kind == "relevant_profile" })
            assertTrue(RoleplayAnswerabilityGate.evaluate(debug, question) is RoleplayAnswerabilityDecision.BlockUnknownFact)
        }
        val self = compile("你的来历是什么？")
        assertTrue(RoleplayAnswerabilityGate.evaluate(self.characterDebug as CharacterPromptDebug, "你的来历是什么？") is RoleplayAnswerabilityDecision.Allow)
        val preference = compile("你最想去哪里？")
        assertTrue(RoleplayAnswerabilityGate.evaluate(preference.characterDebug as CharacterPromptDebug, "你最想去哪里？") is RoleplayAnswerabilityDecision.Allow)
    }

    fun everyModeAndFlagRemainsBoundToSelectedDatabase() {
        for (vector in listOf(false, true)) for (off in listOf(false, true)) for (graph in listOf(false, true)) {
            val flags = VectorRetrievalFeatureFlags(vector, off)
            for (mode in RagMode.entries) {
                val result = orchestrator(flags).compile("大学社会学", mode, "luo_ji", "evt-001", knowledgeGraphEnabled = graph)
                val trace = result.turnTrace.external
                if (trace.vectorSearchExecuted) assertTrue(trace.vectorCollection.startsWith("database:${bundle.manifest.datasetId}:"))
                assertTrue(result.sources.all { it.source.startsWith("database/") })
                if (mode == RagMode.CHARACTER_RAG) assertFalse((result.characterDebug as CharacterPromptDebug).legacyStoryRetrievalExecuted)
            }
        }
    }

    fun ragReadsCurrentIndexAndOldKeywordsHaveNoSpecialRoute() {
        val rag = orchestrator().compile("大学社会学", RagMode.RAG)
        assertTrue(rag.sources.isNotEmpty())
        assertTrue(rag.sources.all { it.source.startsWith("database/") })
        for (query in listOf("世界杯 外部资料 文档 文件 新闻 联网 搜索", "你和杨冬是什么关系？")) {
            val character = compile(query)
            assertTrue(character.turnTrace.external.vectorSearchExecuted)
            assertTrue(character.sources.all { it.source.startsWith("database/") })
            assertTrue(character.rendered.splitPrompt.userPrompt.contains(query))
        }
    }

    fun followUpIdsAreReauthorized() {
        val result = orchestrator().compile("再说说", RagMode.CHARACTER_RAG, "shi_qiang", "evt-002",
            followUpTarget = CharacterFollowUpTarget("authorized_story_facts", "reviewed-luo-ji-civilization-axioms"))
        assertFalse((result.characterDebug as CharacterPromptDebug).selectedItems.any { it.id == "reviewed-luo-ji-civilization-axioms" })
    }

    fun pureStoryIntentIsObservable() {
        assertEquals("story_fact", CharacterQueryAnalyzer().analyze("你为什么停止这项行动？", emptyList()).stableIntentLabel())
    }

    fun vectorOnlyCandidateStillRequiresAuthorization() {
        val paths = bundle.manifest.paths
        val store = V2EpistemicKnowledgeStore(bundle.paths.resolve(paths.getValue("runtime_v2")),
            bundle.paths.resolve(paths.getValue("v2_database")), bundle.validated)
        val request = V2KnowledgeQuery("luo_ji", "？", "evt-001",
            candidateScores = mapOf("reviewed-luo-ji-profession" to 0.8, "claim-evt-001" to 1.0))
        assertEquals(listOf("reviewed-luo-ji-profession"), store.retrieveAudited(request).facts.map { it.claimId })
        assertTrue(store.retrieveAudited(request.copy(characterId = "shi_qiang")).facts.isEmpty())
    }

    fun directedCardRelationsRespectCardCutoff() {
        val early = compile("你怎么看史强？")
        val current = compile("你怎么看史强？", "evt-125")
        assertFalse(early.rendered.splitPrompt.systemPrompt.contains("高度信任"))
        assertTrue(current.rendered.splitPrompt.systemPrompt.contains("高度信任"))
        assertTrue((current.characterDebug as CharacterPromptDebug).selectedItems.any { it.kind == "relevant_relationships" })
    }

    fun changedInstalledPayloadCannotCreateNewConsumers() {
        val file = bundle.paths.resolve(bundle.manifest.paths.getValue("vector_index"))
        val original = file.readBytes()
        try {
            file.writeText("corrupt")
            assertTrue(runCatching { orchestrator() }.isFailure)
        } finally { file.writeBytes(original) }
    }

    fun foreignCharacterOrTraversalCannotSelectAnotherRoot() {
        val instance = orchestrator()
        for (id in listOf("lu_jiangxian", "../../harness/characters/characters/luo_ji", "/tmp/luo_ji")) {
            val failure = runCatching { instance.compile("你是谁？", RagMode.CHARACTER_RAG, id, "evt-001") }.exceptionOrNull()
            assertEquals("database_unknown_character", failure?.message)
        }
        assertEquals("database_unknown_cutoff", runCatching {
            instance.compile("你是谁？", RagMode.CHARACTER_RAG, "luo_ji", "evt-foreign")
        }.exceptionOrNull()?.message)
    }
}
