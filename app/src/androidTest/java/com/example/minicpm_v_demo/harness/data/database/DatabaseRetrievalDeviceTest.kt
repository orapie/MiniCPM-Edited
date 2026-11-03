package com.example.minicpm_v_demo.harness.data.database

import android.os.FileObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.harness.rag.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper

@RunWith(AndroidJUnit4::class)
class DatabaseRetrievalDeviceTest {
    private lateinit var root: File
    private lateinit var scenarios: DatabaseRetrievalScenarios
    @Before fun setup() {
        assertTrue("Database startup timed out", AssetBootstrapper.awaitStartup(120_000))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "database-retrieval-test-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
        val bundle = (DatabaseBundleInstaller(root).install(ApkDatabaseBundleSource(context.assets)) as DatabaseInstallResult.Ready).bundle
        val template = File(root, "program/roleplay_system.prompt").apply {
            parentFile!!.mkdirs()
            context.assets.open("prompts/roleplay_system.prompt").use { input -> outputStream().use { input.copyTo(it) } }
        }
        scenarios = DatabaseRetrievalScenarios(bundle, template)
    }
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun profession() = scenarios.currentProfessionReachesFinalPrompt()
    @Test fun dynamicFacts() = scenarios.questionsSelectDifferentFacts()
    @Test fun authorization() = scenarios.reviewedFactsRespectCutoffAndCharacter()
    @Test fun futureRelevance() = scenarios.futureQuestionIsNotMaskedByWeakNameMatch()
    @Test fun rawScenesAndState() = scenarios.untimedStateNeverBecomesCharacterMemory()
    @Test fun origin() = scenarios.originUsesCurrentDatabaseAndDoesNotLeakFullBiography()
    @Test fun thirdPartyOrigin() = scenarios.otherPersonsOriginDoesNotBorrowSpeakerProfile()
    @Test fun modesAndFlags() = scenarios.everyModeAndFlagRemainsBoundToSelectedDatabase()
    @Test fun ragAndKeywords() = scenarios.ragReadsCurrentIndexAndOldKeywordsHaveNoSpecialRoute()
    @Test fun followUp() = scenarios.followUpIdsAreReauthorized()
    @Test fun storyIntent() = scenarios.pureStoryIntentIsObservable()
    @Test fun vectorAuthorization() = scenarios.vectorOnlyCandidateStillRequiresAuthorization()
    @Test fun relationCutoff() = scenarios.directedCardRelationsRespectCardCutoff()
    @Test fun damagedPayload() = scenarios.changedInstalledPayloadCannotCreateNewConsumers()
    @Test fun unknownIdentity() = scenarios.foreignCharacterOrTraversalCannotSelectAnotherRoot()

    @Test fun referenceTimeAndBudget() {
        val orchestrator = scenarios.orchestrator()
        val early = orchestrator.compile("你知道我的手机密码吗？", RagMode.CHARACTER_RAG, "luo_ji", "evt-001")
        val cutoff = scenarios.bundle.validated.cutoffs.getValue("evt-001")
        assertTrue(early.sources.all { it.end.toLong() <= cutoff })
        assertTrue(early.contextDecisions.any { it.reason == "reference_after_or_crosses_cutoff" })
        assertTrue(early.contextText.length <= 600)
        val identity = orchestrator.compile("罗辑现在的身份是什么", RagMode.CHARACTER_RAG, "shi_qiang", "evt-118")
        assertTrue(identity.contextText.length <= 600)
        assertTrue(identity.contextDecisions.filter { it.decision != "dropped" }.all { it.selectedChars >= 3 })
        assertTrue(identity.rendered.splitPrompt.userPrompt.contains("罗辑现在的身份是什么"))
        assertTrue(identity.turnTrace.external.vectorCandidateScoreDetails.isNotEmpty())
        assertTrue(identity.turnTrace.external.vectorCandidateScoreDetails.all { it.split(':').size == 5 })
    }

    @Test fun legacyBaitFilesHaveZeroOpenEvents() {
        val files = listOf("harness/rag/index.json", "character/manifest.json", "harness/characters/characters/luo_ji.json")
            .map { File(root, it).apply { parentFile!!.mkdirs(); writeText("大学社会学 世界杯 LEGACY_BAIT") } }
        val opens = AtomicInteger()
        val control = CountDownLatch(1)
        val observers = files.map { file -> object : FileObserver(file, OPEN) {
            override fun onEvent(event: Int, path: String?) { opens.incrementAndGet(); control.countDown() }
        } }
        observers.forEach { it.startWatching() }
        try {
            scenarios.everyModeAndFlagRemainsBoundToSelectedDatabase()
            scenarios.ragReadsCurrentIndexAndOldKeywordsHaveNoSpecialRoute()
            assertFalse("Legacy file was opened", control.await(150, TimeUnit.MILLISECONDS))
            assertEquals(0, opens.get())
            // Positive control proves this OS observer reports an actual read.
            files.first().readText()
            assertTrue("File observer did not report control read", control.await(2, TimeUnit.SECONDS))
        } finally { observers.forEach { it.stopWatching() } }
    }
}
