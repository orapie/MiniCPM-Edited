package com.example.minicpm_v_demo.harness.attribution

import com.example.minicpm_v_demo.harness.context.CompressedConversationContext
import com.example.minicpm_v_demo.harness.rag.CompiledReference
import com.example.minicpm_v_demo.harness.rag.RagSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalPromptContractsTest {
    @Test fun validatorAcceptsAnExactStructuredDraftAndMaterializerPreservesEvidence() {
        val raw = rawPrompt()
        val draft = validDraft(raw)

        assertEquals(FinalPromptValidation.Accepted, FinalPromptValidator().validate(raw, draft))
        val final = FinalPromptMaterializer().materialize(raw, draft)
        assertEquals(raw.systemPrompt, final.systemPrompt)
        assertTrue(final.userPrompt.contains("上下文中的代词指向甲"))
        assertTrue(final.userPrompt.contains("甲对罗辑说：你要继续执行计划。"))
        assertTrue(final.userPrompt.contains("参考资料 ref-1"))
        assertTrue(final.userPrompt.endsWith("玩家：那件事呢？"))
    }

    @Test fun validatorFailsClosedWhenRawQuestionEvidenceOrEntityChanges() {
        val raw = rawPrompt()
        assertEquals("user_question_mutated", (FinalPromptValidator().validate(raw,
            validDraft(raw).copy(userPrompt = "被改写的问题")) as FinalPromptValidation.Rejected).code)
        assertEquals("reference_ids_mismatch", (FinalPromptValidator().validate(raw,
            validDraft(raw).copy(references = listOf(validDraft(raw).references.single().copy(referenceId = "invented"))))
            as FinalPromptValidation.Rejected).code)
        assertEquals("unknown_entity", (FinalPromptValidator().validate(raw,
            validDraft(raw).copy(references = listOf(validDraft(raw).references.single().copy(speakerId = "invented"))))
            as FinalPromptValidation.Rejected).code)
    }

    @Test fun strictJsonCodecRejectsExtraFieldsAndKeepsEnumsStructured() {
        val encoded = FinalPromptDraftJsonCodec.encode(validDraft(rawPrompt()))
        val decoded = FinalPromptDraftJsonCodec.decode(encoded)
        assertEquals(FocalRole.ADDRESSEE, decoded.references.single().focalRole)
        assertFalse(decoded.references.single().confidence.isNaN())
        val malformed = encoded.dropLast(1) + ",\"untrusted\":true}"
        assertTrue(runCatching { FinalPromptDraftJsonCodec.decode(malformed) }.isFailure)
    }

    @Test fun requestCompilerKeepsRawPackageAsDataAndRequiresTheAgentContract() {
        val raw = rawPrompt()
        val compiler = AttributionAgentRequestCompiler(
            "你是角色资料归属处理器。只输出 FinalPromptDraft JSON。",
        )
        val request = compiler.compile(raw)

        assertEquals("system", request.messages[0].role)
        assertEquals("user", request.messages[1].role)
        assertTrue(request.messages[1].content.contains("<RAW_PROMPT_JSON>"))
        assertFalse(request.messages[1].content.contains(raw.fingerprint))
        assertFalse(request.messages[1].content.contains(raw.systemPrompt))
        assertTrue(request.messages[1].content.contains("甲对罗辑说：你要继续执行计划。"))
        assertFalse(request.messages[1].content.contains("database/test"))
        assertTrue(runCatching { AttributionAgentRequestCompiler("不合格模板") }.isFailure)
    }

    @Test fun compactWireResultKeepsTrustedFieldsAndRejectsMissingOrInvalidRows() {
        val raw = rawPrompt()
        val draft = AgentAAttributionCodec.decode("""["A2QU"]""", raw)
        assertEquals(FinalPromptValidation.Accepted, FinalPromptValidator().validate(raw, draft))
        assertEquals(raw.references.single().id, draft.references.single().referenceId)
        assertEquals(raw.focalCharacter.id, draft.references.single().addresseeId)
        assertEquals(Perspective.SECOND_PERSON, draft.references.single().perspective)
        assertEquals(ReferenceDisposition.CAUTION,
            AgentAAttributionCodec.decode("""["UUUU"]""", raw).references.single().disposition)
        listOf("""[]""", """["A2QU","UUUC"]""", """["X2QU"]""", """["A2"]""").forEach {
            assertTrue(runCatching { AgentAAttributionCodec.decode(it, raw) }.isFailure)
        }
    }

    private fun rawPrompt() = RawPromptPackage(
        focalCharacter = FocalCharacterIdentity("luo_ji", "罗辑", listOf("罗教授")),
        systemPrompt = "你就是罗辑。",
        userQuestion = "那件事呢？",
        conversationContext = CompressedConversationContext("上下文中的代词指向甲。", 2, 18, "context-hash"),
        references = listOf(CompiledReference(
            id = "ref-1",
            text = "甲对罗辑说：你要继续执行计划。",
            source = RagSource("database/test", 1, 2, 0.9),
        )),
    )

    private fun validDraft(raw: RawPromptPackage) = FinalPromptDraft(
        schemaVersion = RawPromptPackage.SCHEMA_VERSION,
        rawPromptFingerprint = raw.fingerprint,
        systemPrompt = raw.systemPrompt,
        userPrompt = raw.userQuestion,
        references = listOf(ReferenceAttribution(
            referenceId = "ref-1",
            speakerId = ReferenceAttribution.UNKNOWN_ENTITY,
            addresseeId = "luo_ji",
            focalRole = FocalRole.ADDRESSEE,
            perspective = Perspective.SECOND_PERSON,
            evidenceRelation = EvidenceRelation.DIRECT,
            disposition = ReferenceDisposition.USE,
            confidence = 0.9,
        )),
    )
}
