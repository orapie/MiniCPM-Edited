package com.example.minicpm_v_demo.harness.character

import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleHandle
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleValidator
import com.example.minicpm_v_demo.harness.data.database.databaseTestSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the compiler through the same validated database bundle that the
 * app installs. Do not restore the retired Harness character assets here.
 */
class CharacterPromptCompilerTest {
    private fun compiler(): CharacterPromptCompiler {
        val repository = if (File("settings.gradle.kts").isFile) File(".") else File("..")
        val bundle = DatabaseBundleHandle(DatabaseBundleValidator.validate(databaseTestSource()))
        return CharacterPromptCompiler.fromBundle(
            bundle,
            File(repository, "app/src/main/assets/prompts/roleplay_system.prompt"),
        )
    }

    @Test
    fun availableCharactersComeOnlyFromCurrentDatabaseBundle() {
        val characters = compiler().availableCharacters()

        assertEquals(setOf("ding_yi", "luo_ji", "shi_qiang", "zhang_beihai", "zhuang_yan"),
            characters.map { it.npcId }.toSet())
    }

    @Test
    fun compilerBuildsBaseAndUserMessagesWithoutRetiredPromptVariables() {
        val compiled = compiler().buildNpcPrompt(
            npcId = "luo_ji",
            userInput = "你以前研究什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-001", maxChars = 900),
        )

        assertEquals("system", compiled.messages[0].role)
        assertEquals("user", compiled.messages[1].role)
        assertEquals("你以前研究什么？", compiled.messages[1].content)
        assertTrue(compiled.messages[0].content.contains("你就是罗辑"))
        assertFalse(compiled.messages[0].content.contains("若玩家问起你自己，你自然记得："))
        assertFalse(compiled.messages[0].content.contains("你只按这些已知范围说话："))
        assertEquals("none", compiled.debug.dynamicEvidenceFingerprint)
        assertEquals(0, compiled.debug.profileInjectedChars)
        assertEquals(0, compiled.debug.knowledgeGraphInjectedChars)
        assertTrue(compiled.messages[0].content.length <= compiled.debug.maxChars)
    }

    @Test
    fun roleplayBaseUsesCardVoiceWithoutInjectingWorldviewOrInventingQuotes() {
        val compiler = compiler()
        val cards = compiler.availableCharacters()
        val prompts = cards.associate { card ->
            val compiled = compiler.buildNpcPrompt(
                card.npcId,
                "今天有点累，你会怎么安慰我？",
                RuntimeContext(storyCutoff = "evt-001", maxChars = 900),
            )
            val system = compiled.messages[0].content
            assertTrue(system.contains("我是${card.identity.name}。"))
            assertFalse(system.contains(card.identity.worldviewPosition))
            assertFalse(system.contains("此事我知道多少，便只说多少"))
            assertTrue(system.length <= 900)
            card.npcId to system
        }
        assertTrue(prompts.getValue("shi_qiang").contains("给出可执行建议"))
        assertTrue(prompts.getValue("zhang_beihai").contains("不容争辩的陈述句"))
        assertFalse(prompts.getValue("zhang_beihai").contains("失败主义"))
        assertFalse(prompts.getValue("luo_ji").contains("威慑体系"))
    }

    @Test
    fun reviewedPublicCareerOnlyAppliesToDirectSelfQuestionsAtTheCardCutoff() {
        val compiler = compiler()
        fun system(question: String, cutoff: String) = compiler.buildNpcPrompt(
            "zhang_beihai", question, RuntimeContext(storyCutoff = cutoff),
        ).messages[0].content
        val excerpt = "从海军转入太空军政治工作"
        val selfQuestion = "你以前做过什么工作？"
        val careerPrompt = system(selfQuestion, "evt-125")
        assertTrue(careerPrompt.contains("我$excerpt"))
        assertFalse(careerPrompt.contains("直接说“我是章北海"))
        assertFalse(system(selfQuestion, "evt-001").contains(excerpt))
        assertFalse(system("你知道罗辑以前做过什么工作？", "evt-125").contains(excerpt))
        assertFalse(system("你怎么看待责任？", "evt-125").contains(excerpt))
        assertFalse(system(selfQuestion, "evt-125").contains("失败主义"))
    }

    @Test
    fun currentBundleV2KnowledgeSelectionDoesNotInjectFactTextIntoRawPrompt() {
        val compiled = compiler().buildNpcPrompt(
            npcId = "luo_ji",
            userInput = "叶文洁提出的宇宙社会学公理是什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-002", maxChars = 900),
        )

        assertEquals("ready", compiled.debug.knowledgeGraphStatus)
        assertTrue(compiled.debug.selectedItems.any { it.id == "reviewed-luo-ji-civilization-axioms" })
        assertTrue(compiled.debug.knowledgeGraphDecisions.any { it.claimId == "reviewed-luo-ji-civilization-axioms" })
        assertFalse(compiled.messages[0].content.contains("叶文洁向罗辑提出"))
        assertEquals(0, compiled.debug.knowledgeGraphInjectedChars)
        assertFalse(compiled.debug.legacyStoryRetrievalExecuted)
    }

    @Test
    fun futureKnowledgeIsRejectedAtEarlierCutoff() {
        val compiled = compiler().buildNpcPrompt(
            npcId = "luo_ji",
            userInput = "叶文洁提出的宇宙社会学公理是什么？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-001", maxChars = 900),
        )

        assertTrue(compiled.debug.knowledgeGraphDecisions.any {
            it.claimId == "reviewed-luo-ji-civilization-axioms" &&
                it.reason.name == "ACQUIRED_AFTER_CUTOFF"
        })
        assertTrue(compiled.debug.selectedItems.none { it.id == "reviewed-luo-ji-civilization-axioms" })
        assertFalse(compiled.debug.legacyStoryRetrievalExecuted)
    }

    @Test
    fun profileFollowUpCarriesOnlyIdsAndNeverCopiesProfileTextIntoPrompt() {
        val first = compiler().buildNpcPrompt(
            npcId = "luo_ji",
            userInput = "你最想去哪里？",
            runtimeContext = RuntimeContext(storyCutoff = "evt-125", maxChars = 900),
        )
        val selectedId = first.debug.profileSelectedIds.first()
        val continued = compiler().buildNpcPrompt(
            npcId = "luo_ji",
            userInput = "还有吗？",
            runtimeContext = RuntimeContext(
                storyCutoff = "evt-125",
                maxChars = 900,
                followUpTarget = CharacterFollowUpTarget("relevant_profile", selectedId),
            ),
        )

        assertEquals("follow_up", continued.debug.profileRoute)
        assertEquals("accepted", continued.debug.followUpRoute)
        assertTrue(continued.debug.profileSelectedIds.isNotEmpty())
        assertEquals(0, continued.debug.profileInjectedChars)
        assertFalse(continued.messages[0].content.contains("保护庄颜和孩子"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun compilerRejectsUnknownCharacterIds() {
        compiler().buildNpcPrompt("lu_jiangxian", "你好", RuntimeContext(storyCutoff = "evt-001"))
    }
}
