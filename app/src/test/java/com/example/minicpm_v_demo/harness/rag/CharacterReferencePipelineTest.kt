package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.character.CharacterPromptDebug
import com.example.minicpm_v_demo.harness.character.HarnessChatMessage
import com.example.minicpm_v_demo.harness.data.database.DatabaseBundleValidator
import com.example.minicpm_v_demo.harness.data.database.databaseTestSource
import org.junit.Assert.*
import org.junit.Test

class CharacterReferencePipelineTest {
    @Test fun cutoffFiltersFutureCrossingUnknownAndUnreviewedFacts() {
        val bundle = DatabaseBundleValidator.validate(databaseTestSource())
        val cutoff = bundle.cutoffs.getValue("evt-001").toInt()
        val source = "database/" + bundle.manifest.paths.getValue("v2_database")
        val parent = bundle.candidateFactIds.first()
        fun hit(id: String, start: Int, end: Int, claim: String = parent, kind: String = "scene_candidate") =
            SearchResult(RagChunk(id, "可核对的测试资料。", source, start, end,
                mapOf("dataset_id" to bundle.manifest.datasetId, "record_kind" to kind, "claim_id" to claim)), 0.5)
        val items = listOf(hit("before", 1, cutoff), hit("future", cutoff + 1, cutoff + 2),
            hit("cross", cutoff, cutoff + 1), hit("unknown", 1, 2, "unknown"),
            hit("other-fact", 1, 2, "reviewed-luo-ji-profession", "reviewed_fact"))
        val result = CharacterReferenceFilter.filter(items, bundle, "shi_qiang", "evt-001")
        assertEquals(listOf("before"), result.allowed.map { it.chunk.id })
        assertEquals(setOf("future", "cross", "unknown", "other-fact"), result.rejected.map { it.chunkId }.toSet())
        assertTrue(result.rejected.all { it.selectedChars == 0 })
        assertTrue(CharacterReferenceFilter.filter(items, bundle, "shi_qiang", "missing").allowed.isEmpty())
    }

    @Test fun budgetNeverCountsPunctuationOrHeaderAsEvidence() {
        val debug = CharacterPromptDebug("npc", "evt-001", 900, 0, 0, false, emptyList(), emptyList(), emptyList())
        val messages = listOf(HarnessChatMessage("system", "角色规则"), HarnessChatMessage("user", "问题"))
        fun hit(id: String, text: String, score: Double) = SearchResult(
            RagChunk(id, text, "database/test", 1, 1, mapOf("claim_id" to id)), score)
        val results = listOf(hit("punct", "。！？", 0.99)) + (1..12).map {
            hit("fact$it", "这是第${it}条完整且有意义的证据句子。第二句说明额外背景。", 0.9 - it * 0.01)
        }
        val prompt = CharacterRagPromptBuilder(500).build(messages, "问题", results, debug)
        assertTrue(prompt.contextText.length <= 500)
        assertTrue(prompt.messages[1].content.endsWith("玩家：问题"))
        assertTrue(prompt.contextDecisions.any { it.chunkId == "punct" && it.decision == "dropped" })
        assertTrue(prompt.contextDecisions.filter { it.decision != "dropped" }.all { it.selectedChars >= 3 })
        assertEquals(prompt.sources.size, prompt.contextDecisions.count { it.decision != "dropped" })
    }

    @Test fun questionExcerptKeepsFollowingAnswerAndAttributionWithinBudget() {
        val debug = CharacterPromptDebug("npc", "evt-001", 900, 0, 0, false, emptyList(), emptyList(), emptyList())
        val messages = listOf(HarnessChatMessage("system", "角色规则"), HarnessChatMessage("user", "问题"))
        val text = "路人向远处望去。阿明问现在该做什么？阿明转头看向她。她回答：你应去北方寻找港口。"
        val hit = SearchResult(RagChunk("scene", text, "database/test", 1, 1, mapOf("claim_id" to "scene")), 0.5)
        val compiled = CharacterRagPromptBuilder(500).build(messages, "阿明现在该做什么？", listOf(hit), debug)
        assertTrue(compiled.contextText.contains("阿明问现在该做什么？"))
        assertTrue(compiled.contextText.contains("她回答：你应去北方寻找港口。"))
        assertFalse(compiled.contextText.contains("路人向远处望去。"))
        assertTrue(compiled.contextText.length <= 500)
        assertTrue(compiled.messages.last().content.endsWith("玩家：阿明现在该做什么？"))
        val spans = compiled.contextDecisions.single().selectedChunkSpans
        assertEquals(listOf("阿明问现在该做什么？", "阿明转头看向她。", "她回答：你应去北方寻找港口。"),
            spans.map { (start, end) -> text.substring(start, end) })
        assertFalse(compiled.contextDecisions.single().selectedChunkSpans.isEmpty())
        val decision = compiled.contextDecisions.single()
        assertTrue(decision.selectionBoost!! > 0.0)
        assertEquals(0.5 + decision.selectionBoost!!, decision.selectionScore!!, 0.000001)
    }

    @Test fun realSceneQuestionKeepsFollowingReply() {
        val debug = CharacterPromptDebug("npc", "evt-001", 900, 0, 0, false, emptyList(), emptyList(), emptyList())
        val messages = listOf(HarnessChatMessage("system", "角色规则"), HarnessChatMessage("user", "问题"))
        // The parent's selected fragment can change with vector ranking; use
        // the indexed source fragment containing the question for this excerpt test.
        val indexJson = databaseTestSource().resolve("runtime/android/vector/index.json").readText()
        val entries = SimpleJsonParser(indexJson).parse().asJsonObject("index")["items"].asJsonArray("items")
        val chunk = entries.map { it.asJsonObject("item")["chunk"].asJsonObject("chunk") }
            .first { it.stringValue("text").contains("那我现在该做什么") && it.stringValue("text").contains("您是面壁者") }
        val fragment = SearchResult(RagChunk(chunk.stringValue("id"), chunk.stringValue("text"),
            "database/test", 1, 1, mapOf("claim_id" to "claim-evt-115")), 0.5)
        val prompt = CharacterRagPromptBuilder().build(messages, "那我现在该做什么", listOf(fragment), debug)
        assertTrue(prompt.contextText.contains("您是面壁者"))
        assertTrue(prompt.contextText.length <= 600)
    }
}
