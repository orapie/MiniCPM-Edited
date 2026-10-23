package com.example.minicpm_v_demo.harness.character

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Ensures the classifier's regex is accepted by Android's ICU regex engine. */
@RunWith(AndroidJUnit4::class)
class RoleplayOutputGuardDeviceTest {
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
