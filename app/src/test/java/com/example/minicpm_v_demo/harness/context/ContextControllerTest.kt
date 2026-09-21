package com.example.minicpm_v_demo.harness.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextControllerTest {
    @Test
    fun queryTermsIncreaseRelevantCandidatePriority() {
        val relevant = ContextController.queryBoost("元府三司如何建立", "元府三司由三位成员建立")
        val unrelated = ContextController.queryBoost("元府三司如何建立", "今日天气晴朗")

        assertTrue(relevant > unrelated)
        assertTrue(relevant > 0.0)
    }

    @Test
    fun compressionKeepsQueryRelevantCompleteUnits() {
        val compressed = ContextController.compress(
            text = "无关背景很长很长。元府三司由三位成员建立，后来形成新的秩序。其他补充内容不应优先保留。",
            query = "元府三司如何建立",
            maxChars = 30,
        )

        assertTrue(
            "boost=${ContextController.queryBoost("元府三司如何建立", "元府三司由三位成员建立，后来形成新的秩序。")}",
            ContextController.queryBoost("元府三司如何建立", "元府三司由三位成员建立，后来形成新的秩序。") > 0.0,
        )
        assertTrue("compressed=$compressed", compressed.contains("元府三司"))
        assertTrue(compressed.length <= 30)
        assertTrue(compressed.endsWith("。") || compressed.endsWith("秩序"))
    }

    @Test
    fun tokenEstimateIsDeterministicAndNonZero() {
        assertEquals(ContextController.estimateTokens("玄谙是什么？"), ContextController.estimateTokens("玄谙是什么？"))
        assertTrue(ContextController.estimateTokens("玄谙是什么？") > 0)
        assertTrue(ContextController.estimateTokens("plain English words") > 0)
    }
}
