package com.example.minicpm_v_demo.harness.data.database

import com.example.minicpm_v_demo.harness.character.CharacterFollowUpResolver
import com.example.minicpm_v_demo.harness.character.CharacterFollowUpTarget
import com.example.minicpm_v_demo.harness.character.CharacterPromptDebug
import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.data.HarnessDataPaths
import com.example.minicpm_v_demo.harness.rag.AndroidRagOrchestrator
import com.example.minicpm_v_demo.harness.rag.RagMode
import com.example.minicpm_v_demo.harness.session.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the same actual file and bundle contracts on JVM and Android. */
class DatabaseSessionScenarios(
    private val root: File,
    private val source: MemoryDatabaseSource,
    private val template: File,
) {
    private val paths = HarnessDataPaths(File(root, "harness"))
    private val installer = DatabaseBundleInstaller(root)
    private val target = CharacterFollowUpTarget("authorized_story_facts", "reviewed-luo-ji-profession")

    private fun install(input: MemoryDatabaseSource = source) =
        (installer.install(input) as DatabaseInstallResult.Ready).bundle
    private fun store(bundle: DatabaseBundleHandle) = ChatSessionStore(
        paths, datasetIdentity = DatabaseIdentity.from(bundle),
        allowedCharacterIds = bundle.validated.characterIds, allowedCutoffs = bundle.validated.cutoffs.keys,
    )
    private fun turn(text: String = "职业是什么？", model: String = "model-a") = ChatTurn(
        turnId = "turn-1", userText = text, assistantText = "我教社会学。", ragMode = RagMode.CHARACTER_RAG.name,
        characterId = "luo_ji", modelId = model, characterContextTargets = listOf(target),
    )
    private fun follow(session: ChatSession?, identity: DatabaseIdentity) = CharacterFollowUpResolver.resolve(
        session, "再说说", "luo_ji", "evt-001", emptyList(), identity,
    )

    fun switchingDatasetsPreservesLegacyAndArchives() {
        val legacy = ChatSessionStore(paths)
        val old = legacy.create("lu_jiangxian", "evt-010", "old-model")
        val oldFile = File(paths.sessionsDir, "recent_session.json")
        val oldBytes = oldFile.readBytes()
        val bundleA = install()
        val identityA = DatabaseIdentity.from(bundleA)
        val a = store(bundleA)
        assertNull(a.loadRecentSession())
        val aSession = a.appendTurn(a.create("luo_ji", "evt-001", "model-a"), turn())
        assertTrue(aSession.conversationSummary.contains("职业是什么"))
        assertEquals(identityA.datasetId, a.loadRecentSession()?.datasetId)
        assertEquals(target, follow(a.loadRecentSession(), identityA))
        val bundleB = install(source.variant(datasetId = "other_test_dataset"))
        val identityB = DatabaseIdentity.from(bundleB)
        val b = store(bundleB)
        assertNull(b.loadRecentSession())
        assertNull(follow(aSession, identityB))
        val bSession = b.appendTurn(b.create("luo_ji", "evt-001", "model-b"), turn("另一库的问题", "model-b"))
        assertTrue(bSession.conversationSummary.contains("另一库的问题"))
        assertFalse(bSession.conversationSummary.contains("职业是什么"))
        assertNotEquals(aSession.sessionId, bSession.sessionId)
        assertEquals(target, follow(b.loadRecentSession(), identityB))
        assertArrayEquals(oldBytes, oldFile.readBytes())
        assertEquals(setOf(old.sessionId, aSession.sessionId, bSession.sessionId), b.listHistory().map { it.sessionId }.toSet())
        val againA = install()
        assertEquals(aSession.sessionId, store(againA).loadRecentSession()?.sessionId)
        assertEquals(target, follow(store(againA).loadRecentSession(), identityA))
        b.clearRecentSession()
        assertTrue(b.listHistory().any { it.sessionId == bSession.sessionId })
    }

    fun sameDatasetNewFingerprintIsIsolated() {
        val a = install()
        val storeA = store(a)
        val original = storeA.appendTurn(storeA.create("luo_ji", "evt-001", "model-a"), turn())
        val b = install(source.variant(version = "new-version"))
        val identityB = DatabaseIdentity.from(b)
        assertEquals(original.datasetId, identityB.datasetId)
        assertNotEquals(original.datasetFingerprint, identityB.fingerprint)
        assertNull(store(b).loadRecentSession())
        assertNull(follow(original, identityB))
        assertTrue(store(b).listHistory().any { it.sessionId == original.sessionId })
    }

    fun memoryAndSelectorFilterIdentityAndCharacter() {
        val a = install()
        val storeA = store(a)
        val sessionA = storeA.appendTurn(storeA.create("luo_ji", "evt-001", "model-a"), turn())
        val memories = MemoryStore(paths)
        val recordA = memories.observeTurn(sessionA, turn())
        val b = install(source.variant(datasetId = "other_test_dataset"))
        val identityB = DatabaseIdentity.from(b)
        val sessionB = store(b).create("luo_ji", "evt-001", "model-b")
        val recordB = memories.observeTurn(sessionB, turn())
        assertEquals(listOf(recordA.memoryId), memories.search("社会学", DatabaseIdentity.from(a), "luo_ji").map { it.record.memoryId })
        assertEquals(listOf(recordB.memoryId), memories.search("社会学", identityB, "luo_ji").map { it.record.memoryId })
        assertTrue(memories.search("社会学", identityB, "shi_qiang").isEmpty())
        val old = recordA.copy(scope = "character", kind = "profile_memory", promptSafe = true,
            datasetId = null, datasetFingerprint = null)
        val same = recordB.copy(scope = "character", kind = "profile_memory", promptSafe = true)
        val otherCharacter = same.copy(memoryId = "other", characterId = "shi_qiang")
        assertEquals(listOf(same), MemoryPromptSelector().selectPromptSafeLongTerm(listOf(old, same, otherCharacter), identityB, "luo_ji"))
    }

    fun tamperedIdentityAndCutoffCannotRestore() {
        val bundle = install()
        val scoped = store(bundle)
        val session = scoped.create("luo_ji", "evt-001", "model-a")
        assertTrue(runCatching { scoped.save(session.copy(datasetFingerprint = "f".repeat(64))) }.isFailure)
        assertTrue(runCatching { scoped.save(session.copy(storyCutoff = "evt-foreign")) }.isFailure)
        assertTrue(runCatching { scoped.save(session.copy(characterId = "missing")) }.isFailure)
        val file = File(paths.sessionsDir, "by-dataset/${bundle.manifest.datasetId}/${bundle.manifest.datasetFingerprint}/recent_session.json")
        file.writeText(file.readText().replace(bundle.manifest.datasetFingerprint, "f".repeat(64)))
        assertNull(scoped.loadRecentSession())
    }

    fun modelSwitchPreservesCompatibleSession() {
        val bundle = install()
        val scoped = store(bundle)
        val first = scoped.appendTurn(scoped.create("luo_ji", "evt-001", "model-a"), turn())
        val second = scoped.appendTurn(first, turn("再说说", "model-b").copy(turnId = "turn-2"))
        val restored = scoped.loadRecentSession()!!
        assertEquals(second.sessionId, restored.sessionId)
        assertEquals("model-b", restored.selectedModelId)
        assertEquals(DatabaseIdentity.from(bundle).fingerprint, restored.datasetFingerprint)
        assertEquals(2, restored.turns.size)
        assertEquals(target, follow(restored, DatabaseIdentity.from(bundle)))
        val orchestrator = AndroidRagOrchestrator.fromBundle(bundle, template)
        val prompts = listOf(HarnessModelFamily.MINICPM_TEXT, HarnessModelFamily.QWEN).map { family ->
            orchestrator.compile("你以前研究什么，现在在大学教什么？", RagMode.CHARACTER_RAG,
                "luo_ji", "evt-001", modelFamily = family)
        }
        assertTrue(prompts.all { it.turnTrace.datasetFingerprint == restored.datasetFingerprint })
        assertTrue(prompts.all { it.rendered.splitPrompt.systemPrompt.contains("大学教社会学") })
        assertNotEquals(prompts[0].turnTrace.rendererProfile, prompts[1].turnTrace.rendererProfile)
    }

    fun activeTurnBlocksSwitchUntilCancellationCompletes() = runBlocking {
        val gate = DatabaseTurnGate()
        val acquired = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = launch(Dispatchers.Default) {
            gate.acquire().use {
                acquired.countDown()
                try { awaitCancellation() } finally { finished.countDown() }
            }
        }
        assertTrue(acquired.await(3, TimeUnit.SECONDS))
        assertEquals("database_turn_active", runCatching { gate.atBoundary { "switch" } }.exceptionOrNull()?.message)
        worker.cancelAndJoin()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertEquals("switch", gate.atBoundary { "switch" })
        val lease = gate.acquire()
        lease.close()
        lease.close() // Completion callbacks and finally may both release a lease.
        assertEquals("ready", gate.atBoundary { "ready" })
    }

    fun oldCachedConsumerRejectsSwitchedBundleAndNewTraceIsBound() {
        val a = install()
        val old = AndroidRagOrchestrator.fromBundle(a, template)
        val query = "你以前研究什么，现在在大学教什么？"
        val original = old.compile(query, RagMode.CHARACTER_RAG, "luo_ji", "evt-001")
        assertTrue((original.characterDebug as CharacterPromptDebug).knowledgeGraphInjectedChars > 0)
        val b = install(source.variant(datasetId = "other_test_dataset"))
        assertEquals("database_handle_stale", runCatching {
            old.compile(query, RagMode.CHARACTER_RAG, "luo_ji", "evt-001")
        }.exceptionOrNull()?.message)
        assertEquals("database_handle_stale", runCatching { old.availableCharacters() }.exceptionOrNull()?.message)
        val updated = AndroidRagOrchestrator.fromBundle(b, template).compile(query, RagMode.CHARACTER_RAG, "luo_ji", "evt-001")
        val trace = updated.turnTrace
        assertEquals("other_test_dataset", trace.datasetId)
        assertEquals(b.manifest.datasetFingerprint, trace.datasetFingerprint)
        assertEquals(b.manifest.files[b.manifest.paths["vector_index"]], trace.indexFingerprint)
        assertTrue(trace.authorizedFactIds.contains("reviewed-luo-ji-profession"))
        assertTrue(trace.external.vectorCandidateIds.contains("reviewed-luo-ji-profession"))
        assertTrue(trace.character!!.selectedItemIds.any { it.endsWith("reviewed-luo-ji-profession") })
        assertTrue(trace.rejectedFactReasons.isNotEmpty())
        assertFalse(trace.toLogLine().contains(query))
        assertFalse(trace.toJsonLine().contains("大学教社会学"))
    }
}
