package com.example.minicpm_v_demo.harness.data.database

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DatabaseRetrievalTest {
    private lateinit var root: File
    private lateinit var scenarios: DatabaseRetrievalScenarios
    @Before fun setup() {
        root = Files.createTempDirectory("database-retrieval-test").toFile().canonicalFile
        val bundle = (DatabaseBundleInstaller(root).install(DirectoryDatabaseBundleSource(databaseTestSource().rootDir))
            as DatabaseInstallResult.Ready).bundle
        val repo = if (File("settings.gradle.kts").isFile) File(".") else File("..")
        scenarios = DatabaseRetrievalScenarios(bundle, File(repo, "app/src/main/assets/prompts/roleplay_system.prompt"))
    }
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun profession() = scenarios.currentProfessionReachesFinalPrompt()
    @Test fun dynamicFacts() = scenarios.questionsSelectDifferentFacts()
    @Test fun authorization() = scenarios.cutoffAndCharacterAuthorizationCannotBeBypassed()
    @Test fun futureRelevance() = scenarios.futureQuestionIsNotMaskedByWeakNameMatch()
    @Test fun rawScenesAndState() = scenarios.rawScenesAndUntimedStateNeverEnterCharacterPrompt()
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
}
