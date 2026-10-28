package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.data.HarnessDataPaths
import com.example.minicpm_v_demo.harness.data.harnessAssetsDir
import com.example.minicpm_v_demo.harness.character.knowledge.KnowledgeDecisionReason
import com.example.minicpm_v_demo.harness.character.knowledge.V1ClaimKnowledgeStore
import com.example.minicpm_v_demo.harness.character.knowledge.V1GraphKnowledgeStore
import com.example.minicpm_v_demo.harness.character.knowledge.V2EpistemicKnowledgeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CharacterPromptCompilerTest {
    @Test
    fun loaderReadsSixCharactersAndTwentyEightEvents() {
        val loader = testLoader()

        val characters = File(harnessAssetsDir(), "characters/characters")
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .map { loader.loadCharacter(it.nameWithoutExtension) }

        assertEquals(6, characters.size)
        assertEquals(28, loader.loadEvents().size)
        assertTrue(characters.any { it.npcId == "lu_jiangxian" })
        assertTrue(characters.any { it.npcId == "bai_junyi" })
    }

    @Test
    fun compilerBuildsSystemAndUserMessages() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙究竟是什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010"),
        )

        assertEquals("system", compiled.messages[0].role)
        assertEquals("user", compiled.messages[1].role)
        assertEquals("玄谙究竟是什么？", compiled.messages[1].content)
        assertTrue(compiled.messages[0].content.contains("你就是陆江仙"))
        assertTrue(compiled.messages[0].content.contains("前面的对话只能帮你听懂代词或明确承接"))
        assertTrue(compiled.messages[0].content.contains("玄谙"))
        assertTrue(compiled.messages[0].content.length <= compiled.debug.maxChars)
        assertFalse(compiled.debug.budgetExceededByMandatory)
        assertEquals("lu_jiangxian", compiled.debug.npcId)
        assertEquals("evt-010", compiled.debug.storyCutoff)
    }

    @Test
    fun compilerKeepsFutureEventsOutOfEarlierCutoff() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙最后会消散吗？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010"),
        )

        assertFalse(compiled.messages[0].content.contains("自行消散"))
        assertTrue(compiled.debug.retrievalDecisions.any { !it.allowed && it.reason == "future_event" })
    }

    @Test
    fun compilerRejectsMetaQuestionsFromStoryFacts() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "xuan_an",
            userInput = "作者为什么这样写这个角色？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-018"),
        )

        assertTrue(compiled.messages[0].content.contains("无；不得自行补全"))
        assertTrue(compiled.debug.retrievalDecisions.all { !it.allowed && it.reason == "meta_or_author_topic" })
    }

    @Test
    fun compactPromptLeavesRoomForRelevantYuanfuStoryFact() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "当年的元府是如何建立的？主要成员有哪些？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        assertTrue(compiled.messages[0].content.length <= 900)
        assertFalse(compiled.debug.budgetExceededByMandatory)
        assertTrue(compiled.debug.selectedItems.any { it.id == "evt-004" })
        assertTrue(compiled.messages[0].content.contains("元府三司"))
    }

    @Test
    fun compactPromptExplicitlyForbidsStructuredOrCardLikeOutput() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你是谁",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        val system = compiled.messages[0].content
        assertTrue(system.contains("只说角色此刻会亲口说的话"))
        assertTrue(system.contains("通常一至三句、少于120字"))
        assertTrue(system.contains("同一事实、理由或结论只说一次"))
        assertTrue(system.contains("不要换词、换句式或换顺序重复"))
        assertTrue(system.contains("不要旁白、分析、标题、列表、资料摘要"))
        assertTrue(system.contains("任何尖括号标签"))
        assertTrue(system.contains("把这些短句当作语气标尺"))
        assertTrue(system.length <= 900)
    }

    @Test
    fun relevantCharacterProfileIsAvailableWithoutOpeningStoryKnowledge() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你从哪里来？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        assertTrue(compiled.messages[0].content.contains("你的来历：初入此世时曾长期藏于鉴身"))
        assertTrue(compiled.debug.selectedItems.any { it.kind == "relevant_profile" })
        assertEquals("explicit", compiled.debug.profileRoute)
        assertTrue(compiled.debug.profileCandidateIds.contains("profile_background_0"))
        assertTrue(compiled.debug.profileSelectedIds.contains("profile_background_0"))
        assertTrue(compiled.debug.profileInjectedChars > 0)
    }

    @Test
    fun compilerUsesNaturalEvidencePhrasesInsteadOfInternalFieldHeadings() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你从哪里来？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        val system = compiled.messages[0].content
        assertTrue(system.contains("若玩家问起你自己，你自然记得："))
        assertTrue(system.contains("你的来历：初入此世时曾长期藏于鉴身"))
        assertTrue(system.contains("你只按这些已知范围说话："))
        assertFalse(system.contains("不可逾越的角色边界"))
        assertFalse(system.contains("与自身有关的背景、动机、性格、原则或倾向可依据"))
        assertFalse(system.contains("回答剧情问题的唯一依据"))
        assertTrue(system.length <= 900)
    }

    @Test
    fun longTermDestinationQuestionSelectsMotivationProfileInsteadOfStoryOrCurrentState() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你最想去哪里？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        assertEquals("profile", compiled.debug.knowledgeQueryIntent)
        assertTrue(compiled.debug.queryIntents.contains("PREFERENCE"))
        assertEquals(listOf("PROFILE"), compiled.debug.requestedKnowledgeSurfaces)
        assertTrue(compiled.debug.queryExplanationCodes.contains("preference_signal"))
        assertTrue(compiled.debug.profileSelectedIds.contains("profile_motivation_0"))
        assertEquals("not_requested", compiled.debug.stateRoute)
        assertTrue(compiled.debug.selectedItems.none { it.kind == "authorized_story_facts" })
    }

    @Test
    fun profileContinuationRetrievesASiblingAtomicRecordInsteadOfUsingConversationSummary() {
        val first = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你从哪里来？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )
        val selectedId = first.debug.profileSelectedIds.first()
        val continued = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "其他的还有吗？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-010",
                maxChars = 900,
                followUpTarget = CharacterFollowUpTarget("relevant_profile", selectedId),
                conversationSummary = "玩家：进一步说明你的来历",
            ),
        )

        assertEquals("follow_up", continued.debug.profileRoute)
        assertTrue(continued.debug.queryExplanationCodes.contains("follow_up_profile"))
        assertTrue(continued.debug.profileSelectedIds.isNotEmpty())
        assertTrue(continued.debug.profileSelectedIds.first() != selectedId)
        assertTrue(continued.messages[0].content.contains("你的来历："))
    }

    @Test
    fun stableRoleConstraintsRemainWhenGreetingSkipsProfile() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "晚安。",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        assertEquals("skipped_greeting", compiled.debug.profileRoute)
        assertEquals(0, compiled.debug.profileCandidateCount)
        assertTrue(compiled.debug.profileSelectedIds.isEmpty())
        assertTrue(compiled.messages[0].content.contains("你只按这些已知范围说话：保持陆江仙的身份与当前知识边界"))
        assertTrue(compiled.messages[0].content.length <= 900)
    }

    @Test
    fun profileBudgetDropIsAuditableWithoutLeakingProfileText() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你从哪里来？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-010",
                maxChars = 900,
                profileMaxChars = 1,
            ),
        )

        assertEquals("explicit", compiled.debug.profileRoute)
        assertTrue(compiled.debug.profileCandidateCount > 0)
        assertTrue(compiled.debug.profileBudgetDroppedCount > 0)
        assertTrue(compiled.debug.profileSelectedIds.isEmpty())
        assertEquals(0, compiled.debug.profileInjectedChars)
    }

    @Test
    fun conversationSummaryCannotEvictCurrentOriginEvidence() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你从哪里来？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-010",
                maxChars = 900,
                conversationSummary = "此前闲聊内容。".repeat(200),
            ),
        )

        assertTrue(compiled.debug.reservedDynamicEvidenceChars > 0)
        assertTrue(compiled.debug.profileSelectedIds.contains("profile_background_0"))
        assertTrue(compiled.messages[0].content.contains("你的来历：初入此世时曾长期藏于鉴身"))
        assertTrue(compiled.messages[0].content.length <= 900)
    }

    @Test
    fun currentLocationSelectsOnlyTheRelevantStateScene() {
        val query = "你现在在哪里？"
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = query,
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        assertEquals(query, compiled.messages[1].content)
        assertEquals("explicit", compiled.debug.stateRoute)
        assertEquals("not_requested", compiled.debug.profileRoute)
        assertTrue(compiled.debug.stateCandidateIds.contains("state_scene"))
        assertEquals(listOf("state_scene"), compiled.debug.stateSelectedIds)
        assertTrue(compiled.messages[0].content.contains("你当前处境：洞华天青铜门外的星辰天地"))
        assertTrue(compiled.debug.stateInjectedChars > 0)
        assertTrue(compiled.messages[0].content.length <= 900)
    }

    @Test
    fun nextStepSelectsTheRelevantCurrentGoal() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你接下来打算做什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        assertEquals("explicit", compiled.debug.stateRoute)
        assertEquals(listOf("state_goal"), compiled.debug.stateSelectedIds)
        assertTrue(compiled.messages[0].content.contains("你当前目标：维持洞华天内的权柄平衡"))
        assertTrue(compiled.messages[0].content.length <= 900)
    }

    @Test
    fun originQuestionDoesNotInjectCurrentState() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你从何而来？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        assertEquals("not_requested", compiled.debug.stateRoute)
        assertEquals(0, compiled.debug.stateCandidateCount)
        assertTrue(compiled.debug.stateSelectedIds.isEmpty())
        assertEquals(0, compiled.debug.stateInjectedChars)
        assertFalse(compiled.messages[0].content.contains("你当前任务状态："))
        assertFalse(compiled.messages[0].content.contains("你当前目标："))
    }

    @Test
    fun stateBudgetDropIsAuditableWithoutLeakingStateText() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你现在在哪里？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-010",
                maxChars = 900,
                stateMaxChars = 1,
            ),
        )

        assertEquals("explicit", compiled.debug.stateRoute)
        assertTrue(compiled.debug.stateCandidateCount > 0)
        assertTrue(compiled.debug.stateBudgetDroppedCount > 0)
        assertTrue(compiled.debug.stateSelectedIds.isEmpty())
        assertEquals(0, compiled.debug.stateInjectedChars)
    }

    @Test
    fun futureStateCannotClaimACurrentPossessionBeforeItsCutoff() {
        val compiled = testCompilerWithBundledState().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你手里有什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        assertEquals("no_current_state", compiled.debug.possessionRoute)
        assertEquals(0, compiled.debug.possessionCandidateCount)
        assertEquals(0, compiled.debug.possessionInjectedChars)
        assertTrue(compiled.messages[0].content.contains("当前资料未记录可确认的随身物品。"))
    }

    @Test
    fun notCarriedHistoricalItemIsNotInjectedAsAHandheldPossession() {
        val compiled = testCompilerWithBundledState().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你手里有什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-018", maxChars = 900),
        )

        assertEquals("no_carried_items", compiled.debug.possessionRoute)
        assertTrue(compiled.debug.selectedItems.none { it.kind == "relevant_possessions" })
        assertFalse(compiled.messages[0].content.contains("青诣元心仪；仍与太阴余位"))
        assertTrue(compiled.messages[0].content.contains("当前资料未记录可确认的随身物品。"))
    }

    @Test
    fun carriedPossessionIsInjectedOnlyFromTheActiveStateAsset() {
        val query = "你手里有什么？"
        val compiled = testCompilerWithState(
            """
            {"schema_version":"1.0","character_id":"lu_jiangxian","states":[
              {"state_id":"fixture-018","valid_from":"evt-018","valid_to":null,
               "scene":"洞华天青铜门外。","quest_state":"正在收束局势。","current_goal":"守住明阳。",
               "evidence_refs":["fixture:L1"],"possessions":[
                 {"item_id":"fixture-token","name":"验收令牌","status":"carried","description":"当前可确认由你随身持有。","keywords":["手里","令牌"],"evidence_refs":["fixture:L2"]}
               ]}
            ]}
            """.trimIndent(),
        ).buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = query,
            runtimeContext = RuntimeContext(storyCutoff = "evt-018", maxChars = 900),
        )

        assertEquals(query, compiled.messages[1].content)
        assertEquals("explicit", compiled.debug.possessionRoute)
        assertEquals(1, compiled.debug.possessionCandidateCount)
        assertEquals(1, compiled.debug.possessionSelectedCount)
        assertTrue(compiled.messages[0].content.contains("当前可确认随身物品：验收令牌"))
        assertTrue(compiled.debug.possessionInjectedChars > 0)
    }

    @Test
    fun possessionBudgetDropIsAuditableWithoutLeakingPossessionText() {
        val compiled = testCompilerWithState(
            possessionFixtureJson(),
        ).buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你手里有什么？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-018",
                maxChars = 900,
                possessionMaxChars = 1,
            ),
        )

        assertEquals("explicit", compiled.debug.possessionRoute)
        assertEquals(1, compiled.debug.possessionCandidateCount)
        assertEquals(0, compiled.debug.possessionSelectedCount)
        assertTrue(compiled.debug.possessionBudgetDroppedCount > 0)
        assertEquals(0, compiled.debug.possessionInjectedChars)
    }

    @Test
    fun pronounFollowUpRevalidatesAndReusesThePriorAuthorizedV2Fact() {
        val query = "他为何这样做？"
        val compiled = testCompilerWithGraph().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = query,
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-018",
                maxChars = 900,
                followUpTarget = CharacterFollowUpTarget("authorized_story_facts", "v2-claim-evt-012"),
            ),
        )

        assertEquals(query, compiled.messages[1].content)
        assertEquals("accepted", compiled.debug.followUpRoute)
        assertTrue(compiled.debug.selectedItems.any { it.id == "v2-claim-evt-012" })
        assertTrue(compiled.messages[0].content.contains("拒绝玄谙以生存和能力为理由的取代方案"))
    }

    @Test
    fun followUpFactIsStillRejectedAtAnEarlierCutoff() {
        val compiled = testCompilerWithGraph().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "他为何这样做？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-010",
                maxChars = 900,
                followUpTarget = CharacterFollowUpTarget("authorized_story_facts", "v2-claim-evt-012"),
            ),
        )

        assertTrue(compiled.debug.selectedItems.none { it.id == "v2-claim-evt-012" })
        assertTrue(compiled.debug.knowledgeGraphDecisions.any {
            it.claimId == "v2-claim-evt-012" && it.reason.name == "ACQUIRED_AFTER_CUTOFF"
        })
        assertTrue(RoleplayAnswerabilityGate.evaluate(compiled.debug) is RoleplayAnswerabilityDecision.BlockFutureFact)
    }

    @Test
    fun possessionFollowUpUsesOnlyThePriorCarriedItemId() {
        val compiled = testCompilerWithState(possessionFixtureJson()).buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "那件呢？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-018",
                maxChars = 900,
                followUpTarget = CharacterFollowUpTarget("relevant_possessions", "fixture-token"),
            ),
        )

        assertEquals("accepted", compiled.debug.followUpRoute)
        assertEquals("explicit", compiled.debug.possessionRoute)
        assertTrue(compiled.messages[0].content.contains("当前可确认随身物品：验收令牌"))
    }

    @Test
    fun properNounQuestionsRecallTheSpecificStoryEvent() {
        val retriever = KeywordStoryRetriever()
        val events = testLoader().loadEvents()
        val cases = listOf(
            "玄谙究竟是什么？" to "evt-009",
            "道皓清阳玄玉真君做过什么？" to "evt-025",
            "薜荔剑和李江群有什么关系？" to "evt-028",
            "太虚元序玄司营造法进展如何？" to "evt-021",
        )

        cases.forEach { (query, expectedEventId) ->
            val results = retriever.retrieve(query, events, limit = 5)
            assertTrue("$query did not recall $expectedEventId", results.any { it.event.eventId == expectedEventId })
            assertEquals("$query ranked the wrong event first", expectedEventId, results.first().event.eventId)
        }
    }

    @Test
    fun definitionQuestionPrioritizesAnUnambiguousKnownFact() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙究竟是什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        val system = compiled.messages[0].content
        assertEquals("evt-009", compiled.debug.selectedItems.first().id)
        assertTrue(system.contains("你听人提及：青诣元心仪是玄鉴的鉴身权柄"))
        assertTrue(system.contains("玄谙是洞华天中一块青铜残片"))
        assertFalse(system.contains("自己是洞华天"))
    }

    @Test
    fun promptKeepsRulesOutOfIdentityAndFocusesTheLatestQuestion() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄明之气到底有什么用途？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-010",
                maxChars = 900,
                conversationSummary = "玩家上一轮问过元府三司。",
            ),
        )

        val system = compiled.messages[0].content
        assertFalse(system.contains("你绝不会自称别人"))
        assertTrue(system.contains("只回答玩家最后这一问"))
        assertTrue(system.contains("不补答上一轮"))
        assertTrue(system.contains("此事我尚不清楚"))
        assertTrue(system.contains("不能编造具体经历"))
    }

    @Test
    fun conversationSummaryUsesRemainingBudgetInsteadOfFixedCharacterCutoff() {
        val compiled = testCompiler().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙曾经见过什么？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-010",
                maxChars = 1100,
                conversationSummary = "玄谙曾在大黎山见过命阳白玉剑。".repeat(30),
            ),
        )

        assertTrue(compiled.debug.conversationSummaryChars > 100)
        assertTrue(compiled.debug.promptChars <= 1100)
    }

    @Test
    fun everyBundledCharacterFitsTheAndroidPromptBudget() {
        val compiler = testCompiler()

        compiler.availableCharacters().forEach { character ->
            val compiled = compiler.buildNpcPrompt(
                npcId = character.npcId,
                userInput = "你是谁",
                runtimeContext = RuntimeContext(
                    storyCutoff = character.knowledgeScope.storyCutoff,
                    maxChars = 900,
                ),
            )

            assertTrue(
                "${character.npcId} prompt was ${compiled.messages[0].content.length} chars",
                compiled.messages[0].content.length <= 900,
            )
            assertFalse(compiled.debug.budgetExceededByMandatory)
        }
    }

    @Test
    fun compilerReportsUnknownNpcAndCutoff() {
        val compiler = testCompiler()

        assertTrue(runCatching {
            compiler.buildNpcPrompt("missing", "你好")
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            compiler.buildNpcPrompt("lu_jiangxian", "你好", RuntimeContext(storyCutoff = "evt-999"))
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun graphFactIsPlacedOnlyInSystemMessageWhenAuthorized() {
        val query = "玄谙为何还要毁掉明阳"
        val compiled = testCompilerWithGraph().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = query,
            runtimeContext = RuntimeContext(storyCutoff = "evt-018", maxChars = 1_500),
        )

        assertEquals(query, compiled.messages[1].content)
        assertTrue(compiled.messages[0].content.contains("拒绝玄谙以生存和能力为理由的取代方案"))
        assertTrue(compiled.messages[0].content.contains("能作答就先说结论，不得猜测或反问"))
        assertEquals("ready", compiled.debug.knowledgeGraphStatus)
        assertEquals("skipped_v2_authoritative", compiled.debug.v1ClaimGraphStatus)
        assertTrue(compiled.debug.selectedItems.any { it.id == "v2-claim-evt-012" })
        assertTrue(compiled.debug.knowledgeGraphInjectedChars > 0)
        assertFalse(compiled.debug.legacyStoryRetrievalExecuted)
        assertFalse(compiled.debug.legacyMemoryRetrievalExecuted)
    }

    @Test
    fun unavailableGraphFallsBackToExistingStoryEvents() {
        val root = harnessAssetsDir()
        val compiler = CharacterPromptCompiler(
            loader = testLoader(),
            v2KnowledgeStore = V2EpistemicKnowledgeStore(File(root, "missing-knowledge.json")),
        )
        val compiled = compiler.buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙究竟是什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        assertEquals("unavailable", compiled.debug.knowledgeGraphStatus)
        assertEquals("v2_store_unavailable", compiled.debug.knowledgeGraphFallbackReason)
        assertTrue(compiled.debug.selectedItems.any { it.id == "evt-009" })
        assertTrue(compiled.messages[0].content.contains("玄谙是洞华天中一块青铜残片"))
    }

    @Test
    fun v1StoryClaimsCoverAnUnavailableV2WithoutRunningTheLegacyRetriever() {
        val root = harnessAssetsDir()
        val compiler = CharacterPromptCompiler(
            loader = testLoader(),
            v2KnowledgeStore = V2EpistemicKnowledgeStore(File(root, "missing-knowledge.json")),
            v1ClaimKnowledgeStore = V1ClaimKnowledgeStore(
                manifestFile = File(root, "characters/knowledge/v1/manifest.json"),
                claimsFile = File(root, "characters/knowledge/v1/claims.jsonl"),
            ),
        )

        val compiled = compiler.buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙究竟是什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 900),
        )

        assertEquals("unavailable", compiled.debug.knowledgeGraphStatus)
        assertEquals("ready", compiled.debug.v1ClaimGraphStatus)
        assertTrue(compiled.debug.selectedItems.any { it.id == "event-evt-009" })
        assertTrue(compiled.debug.v1ClaimGraphInjectedChars > 0)
        assertFalse(compiled.debug.legacyStoryRetrievalExecuted)
        assertTrue(compiled.messages[0].content.contains("自己是洞华天中一块青铜残片"))
    }

    @Test
    fun v1StoryClaimsKeepTheSameCharacterKnowledgeCutoff() {
        val root = harnessAssetsDir()
        val compiler = CharacterPromptCompiler(
            loader = testLoader(),
            v2KnowledgeStore = V2EpistemicKnowledgeStore(File(root, "missing-knowledge.json")),
            v1ClaimKnowledgeStore = V1ClaimKnowledgeStore(
                manifestFile = File(root, "characters/knowledge/v1/manifest.json"),
                claimsFile = File(root, "characters/knowledge/v1/claims.jsonl"),
            ),
        )

        val compiled = compiler.buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙究竟是什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-008", maxChars = 900),
        )

        assertEquals("ready", compiled.debug.v1ClaimGraphStatus)
        assertFalse(compiled.messages[0].content.contains("玄谙是洞华天中一块青铜残片"))
        assertTrue(compiled.debug.v1ClaimGraphDecisions.any {
            it.claimId == "event-evt-009" && it.reason == KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF
        })
    }

    @Test
    fun graphDoesNotBypassFutureKnowledgeBoundary() {
        val compiled = testCompilerWithGraph().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙为何还要毁掉明阳",
            runtimeContext = RuntimeContext(storyCutoff = "evt-010", maxChars = 1_500),
        )

        assertFalse(compiled.messages[0].content.contains("拒绝玄谙以生存和能力为理由的取代方案"))
        assertTrue(compiled.debug.knowledgeGraphDecisions.any {
            it.claimId == "v2-claim-evt-012" && it.reason == KnowledgeDecisionReason.ACQUIRED_AFTER_CUTOFF
        })
    }

    @Test
    fun v1RelationshipGraphInjectsOnlyTheCurrentNpcsAttitude() {
        val root = harnessAssetsDir()
        val compiler = CharacterPromptCompiler(
            loader = testLoader(),
            v1KnowledgeStore = V1GraphKnowledgeStore(
                manifestFile = File(root, "characters/knowledge/v1/manifest.json"),
                relationsFile = File(root, "characters/knowledge/v1/relations.jsonl"),
            ),
        )

        val compiled = compiler.buildNpcPrompt(
            npcId = "bai_junyi",
            userInput = "你怎么看玄谙？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-024", maxChars = 1_200),
        )

        assertEquals("ready", compiled.debug.v1RelationshipGraphStatus)
        assertTrue(compiled.debug.selectedItems.any { it.id == "rel-84a3d8143c5b7647" })
        assertTrue(compiled.messages[0].content.contains("玄谙是你的长期上位者与差遣者"))
        assertTrue(compiled.messages[0].content.contains("敬畏并服从其长期安排"))
        assertFalse(compiled.messages[0].content.contains("元府同僚与怀疑对象"))
        assertTrue(compiled.debug.v1RelationshipGraphInjectedChars > 0)
        assertEquals("not_requested", compiled.debug.knowledgeGraphStatus)
        assertFalse(compiled.debug.legacyStoryRetrievalExecuted)
    }

    @Test
    fun identityAndChitchatSkipAllKnowledgeRetrieval() {
        val compiled = testCompilerWithGraph().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "你是谁？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-018", maxChars = 900),
        )

        assertEquals("identity_or_chitchat", compiled.debug.knowledgeQueryIntent)
        assertEquals("not_requested", compiled.debug.knowledgeGraphStatus)
        assertEquals("not_requested", compiled.debug.v1RelationshipGraphStatus)
        assertFalse(compiled.debug.legacyStoryRetrievalExecuted)
        assertFalse(compiled.debug.legacyMemoryRetrievalExecuted)
        assertTrue(compiled.messages[0].content.contains("你就是陆江仙"))
    }

    @Test
    fun graphSwitchOffUsesLegacyStoryFallback() {
        val compiled = testCompilerWithGraph().buildNpcPrompt(
            npcId = "lu_jiangxian",
            userInput = "玄谙究竟是什么？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-010",
                maxChars = 900,
                knowledgeGraphEnabled = false,
            ),
        )

        assertEquals("disabled", compiled.debug.knowledgeGraphStatus)
        assertTrue(compiled.debug.legacyStoryRetrievalExecuted)
        assertTrue(compiled.debug.selectedItems.any { it.id == "evt-009" })
        assertTrue(compiled.messages[0].content.contains("玄谙是洞华天中一块青铜残片"))
    }

    private fun testCompiler(): CharacterPromptCompiler = CharacterPromptCompiler(testLoader())

    private fun testCompilerWithBundledState(): CharacterPromptCompiler {
        val root = harnessAssetsDir()
        val loader = testLoader()
        return CharacterPromptCompiler(
            loader = loader,
            stateLoader = CharacterStateLoader(File(root, "characters/state"), loader.loadEvents()),
        )
    }

    private fun testCompilerWithState(stateJson: String): CharacterPromptCompiler {
        val stateDirectory = Files.createTempDirectory("character-state-fixture").toFile()
        File(stateDirectory, "lu_jiangxian.json").writeText(stateJson, Charsets.UTF_8)
        stateDirectory.deleteOnExit()
        val loader = testLoader()
        return CharacterPromptCompiler(
            loader = loader,
            stateLoader = CharacterStateLoader(stateDirectory, loader.loadEvents()),
        )
    }

    private fun possessionFixtureJson(): String = """
        {"schema_version":"1.0","character_id":"lu_jiangxian","states":[
          {"state_id":"fixture-018","valid_from":"evt-018","valid_to":null,
           "scene":"洞华天青铜门外。","quest_state":"正在收束局势。","current_goal":"守住明阳。",
           "evidence_refs":["fixture:L1"],"possessions":[
             {"item_id":"fixture-token","name":"验收令牌","status":"carried","description":"当前可确认由你随身持有。","keywords":["手里","令牌"],"evidence_refs":["fixture:L2"]}
           ]}
        ]}
    """.trimIndent()

    private fun testCompilerWithGraph(): CharacterPromptCompiler {
        val root = harnessAssetsDir()
        return CharacterPromptCompiler(
            loader = testLoader(),
            v2KnowledgeStore = V2EpistemicKnowledgeStore(
                fallbackFile = File(root, "characters/knowledge/v2/knowledge_fallback.json"),
                sourceDatabaseFile = File(root, "characters/knowledge/v2/character_knowledge_v2.sqlite3"),
            ),
            v1ClaimKnowledgeStore = V1ClaimKnowledgeStore(
                manifestFile = File(root, "characters/knowledge/v1/manifest.json"),
                claimsFile = File(root, "characters/knowledge/v1/claims.jsonl"),
            ),
        )
    }

    private fun testLoader(): CharacterJsonLoader {
        val root = harnessAssetsDir()
        return CharacterJsonLoader(
            charactersDir = File(root, "characters/characters"),
            storyEventsFile = File(root, "characters/story/story_events.jsonl"),
            promptTemplateFile = File(root.parentFile, "prompts/roleplay_system.prompt"),
        )
    }
}
