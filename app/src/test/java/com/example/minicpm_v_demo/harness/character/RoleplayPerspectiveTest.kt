package com.example.minicpm_v_demo.harness.character

import org.junit.Assert.*
import org.junit.Test

class RoleplayPerspectiveTest {
    private fun card(id: String, name: String, aliases: List<String>) = CharacterCard(
        npcId = id,
        identity = IdentityCore(name, aliases, "", emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        relationships = emptyList(), knowledgeScope = KnowledgeScope(emptySet(), "evt-001"),
        dynamicState = DynamicState("", EmotionState("", "", 0.0, ""), "", "", 0),
        episodicMemory = emptyList(),
    )

    @Test fun cardSwitchAndAmbiguousNamesAreScopedToEachTurn() {
        val cards = listOf(card("a", "阿明", listOf("小明", "共享")), card("b", "阿青", listOf("共享")))
        val a = RoleplayCharacterIdentity.fromCards("a", cards)
        val b = RoleplayCharacterIdentity.fromCards("b", cards)
        assertEquals(listOf("小明"), a.aliases)
        assertTrue(RoleplayOutputGuard.evaluate("小明一直在思考。", a.name, "问题", a.aliases) is RoleplayOutputDecision.RetryWithRepair)
        assertTrue(RoleplayOutputGuard.evaluate("小明一直在思考。", b.name, "问题", b.aliases) is RoleplayOutputDecision.Accept)
        assertTrue(RoleplayOutputGuard.evaluate("阿青决定离开。", b.name, "问题", b.aliases) is RoleplayOutputDecision.RetryWithRepair)
        assertTrue(RoleplayOutputGuard.evaluate("共享决定离开。", a.name, "问题", a.aliases) is RoleplayOutputDecision.Accept)
        assertTrue(RoleplayCharacterIdentity.fromCards("missing", cards).name.isEmpty())
    }

    @Test fun narrationIsRejectedWithoutBlockingSpeechAndQuotation() {
        val retry = RoleplayOutputGuard.evaluate("我知道了。罗辑一直在思考，他决心行动。", "罗辑", "问题")
            as RoleplayOutputDecision.RetryWithRepair
        assertTrue(retry.ruleIds.contains("self_third_person_narration"))
        val observed = "雪地工程似乎是为了某种特别的工程，罗辑全心投入其中。玩家询问其目的时，罗辑可能想探讨它的意义或目标。"
        assertTrue(RoleplayOutputGuard.evaluate(observed, "罗辑", "问题") is RoleplayOutputDecision.RetryWithRepair)
        for (text in listOf("我是罗辑。", "史强帮过我，他很可靠。", "她说过‘罗辑一直在思考’。", "你手上的芯片很重要。", "他决定离开。")) {
            assertEquals(text, (RoleplayOutputGuard.evaluate(text, "罗辑", "问题") as RoleplayOutputDecision.Accept).visibleText)
        }
        assertTrue(RoleplayOutputGuard.evaluate("“罗辑决定离开。", "罗辑", "问题") is RoleplayOutputDecision.Accept)
    }

    @Test fun retryIsBoundedAndFixedResponseCannotBypassGate() {
        val coordinator = RoleplayGenerationCoordinator()
        val text = "阿明决定离开。"
        assertTrue(coordinator.resolve(0, text, "阿明", "问题") is RoleplayGenerationAction.Retry)
        assertTrue(coordinator.resolve(1, text, "阿明", "问题") is RoleplayGenerationAction.Fallback)
        assertTrue(coordinator.resolve(0, text, "阿明", "问题", fixedResponse = true) is RoleplayGenerationAction.Fallback)
    }
}
