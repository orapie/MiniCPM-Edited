package com.example.minicpm_v_demo.harness.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterQueryAnalyzerTest {
    private val analyzer = CharacterQueryAnalyzer()
    private val knownNames = listOf("玄谙", "白君意")

    @Test
    fun routesIdentityAndOriginToProfileWithoutStoryFacts() {
        val identity = analyze("你是谁")
        val origin = analyze("你来自哪里？")

        assertTrue(identity.hasIntent(CharacterQueryIntent.IDENTITY))
        assertTrue(identity.requests(CharacterKnowledgeSurface.PROFILE))
        assertFalse(identity.requests(CharacterKnowledgeSurface.STORY_FACT))
        assertTrue(origin.hasIntent(CharacterQueryIntent.ORIGIN))
        assertTrue(origin.requests(CharacterKnowledgeSurface.PROFILE))
        assertFalse(origin.requests(CharacterKnowledgeSurface.STORY_FACT))
    }

    @Test
    fun routesLongTermPreferenceDifferentlyFromCurrentDestination() {
        val preference = analyze("你最想去哪里？")
        val currentGoal = analyze("你现在想去哪里？")

        assertTrue(preference.hasIntent(CharacterQueryIntent.PREFERENCE))
        assertTrue(preference.requests(CharacterKnowledgeSurface.PROFILE))
        assertFalse(preference.requests(CharacterKnowledgeSurface.STATE))
        assertFalse(preference.requests(CharacterKnowledgeSurface.STORY_FACT))
        assertTrue(currentGoal.hasIntent(CharacterQueryIntent.STATE_GOAL))
        assertTrue(currentGoal.requests(CharacterKnowledgeSurface.STATE))
        assertFalse(currentGoal.hasIntent(CharacterQueryIntent.PREFERENCE))
    }

    @Test
    fun routesPossessionRelationshipStoryAndFollowUpToTheirOwnSurfaces() {
        val possession = analyze("你手里有什么？")
        val relationship = analyze("你怎么看玄谙？")
        val story = analyze("玄谙究竟是什么？")
        val otherCharacterPlan = analyze("玄谙的计划是什么？")
        val followUp = analyzer.analyze(
            userInput = "那件事呢？",
            knownCharacterNames = knownNames,
            followUpTarget = CharacterFollowUpTarget("authorized_story_facts", "v2-claim-evt-012"),
        )

        assertEquals(setOf(CharacterKnowledgeSurface.POSSESSION), possession.requestedSurfaces)
        assertEquals(setOf(CharacterKnowledgeSurface.RELATIONSHIP), relationship.requestedSurfaces)
        assertEquals(setOf(CharacterKnowledgeSurface.STORY_FACT), story.requestedSurfaces)
        assertEquals(setOf(CharacterKnowledgeSurface.STORY_FACT), otherCharacterPlan.requestedSurfaces)
        assertTrue(followUp.hasIntent(CharacterQueryIntent.FOLLOW_UP))
        assertTrue(followUp.requests(CharacterKnowledgeSurface.STORY_FACT))
        assertTrue(followUp.explanationCodes.contains("follow_up_story_fact"))

        val profileFollowUp = analyzer.analyze(
            userInput = "其他的还有吗？",
            knownCharacterNames = knownNames,
            followUpTarget = CharacterFollowUpTarget("relevant_profile", "profile_background_0"),
        )
        assertTrue(profileFollowUp.hasIntent(CharacterQueryIntent.FOLLOW_UP))
        assertTrue(profileFollowUp.requests(CharacterKnowledgeSurface.PROFILE))
        assertTrue(profileFollowUp.explanationCodes.contains("follow_up_profile"))
    }

    @Test
    fun routesSpecificPastSelfActionToStoryFactsInsteadOfProfileMotivation() {
        val analysis = analyze("你为什么停止拼合七枚鉴身碎片？")

        assertTrue(analysis.hasIntent(CharacterQueryIntent.STORY_EVENT))
        assertTrue(analysis.requests(CharacterKnowledgeSurface.STORY_FACT))
        assertFalse(analysis.requests(CharacterKnowledgeSurface.PROFILE))
        assertTrue(analysis.explanationCodes.contains("specific_story_action_signal"))
    }

    @Test
    fun unknownQuestionIsExplicitAndDoesNotPretendToRequestStoryEvidence() {
        val analysis = analyze("嗯……")

        assertEquals(setOf(CharacterQueryIntent.UNKNOWN), analysis.intents)
        assertTrue(analysis.requestedSurfaces.isEmpty())
        assertTrue(analysis.explanationCodes.contains("no_supported_signal"))
        assertEquals("unknown", analysis.stableIntentLabel())
    }

    private fun analyze(query: String): CharacterQueryAnalysis = analyzer.analyze(
        userInput = query,
        knownCharacterNames = knownNames,
    )
}
