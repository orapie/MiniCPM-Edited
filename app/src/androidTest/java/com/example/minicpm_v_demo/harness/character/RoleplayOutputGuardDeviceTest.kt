package com.example.minicpm_v_demo.harness.character

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Ensures the classifier's regex is accepted by Android's ICU regex engine. */
@RunWith(AndroidJUnit4::class)
class RoleplayOutputGuardDeviceTest {
    @Test fun questionRepairAndQuotedSpeechWorkOnAndroid() {
        assertEquals(RoleplayOutputDecision.RetryWithRepair(listOf("unsolicited_question")),
            RoleplayOutputGuard.evaluate("我信任史强。你怎么看他？", "罗辑", "你怎么看史强？"))
        val quoted = "她问我“你要去哪儿？”，我没有回答。"
        assertEquals(RoleplayOutputDecision.Accept(quoted), RoleplayOutputGuard.evaluate(quoted, "罗辑", "她说了什么？"))
    }
    @Test fun narrationAndQuotedNamesWorkOnAndroid() {
        val retry = RoleplayOutputGuard.evaluate("罗辑一直在思考，他决心行动。", "罗辑", "问题")
        org.junit.Assert.assertTrue(retry is RoleplayOutputDecision.RetryWithRepair &&
            "self_third_person_narration" in retry.ruleIds)
        org.junit.Assert.assertTrue(RoleplayOutputGuard.evaluate("雪地工程很重要，罗辑全心投入其中。", "罗辑", "问题") is RoleplayOutputDecision.RetryWithRepair)
        val quoted = "她说过‘罗辑一直在思考’。"
        assertEquals(RoleplayOutputDecision.Accept(quoted), RoleplayOutputGuard.evaluate(quoted, "罗辑", "问题"))
    }
    @Test
    fun templateTokenIsClassifiedOnDevice() {
        val decision = RoleplayOutputGuard.evaluate(
            rawOutput = "{{relevant_profile}}",
            characterName = "陆江仙",
            userInput = "你是谁？",
        )

        assertEquals(
            RoleplayOutputDecision.RetryWithRepair(listOf("template_token")),
            decision,
        )
    }
}
