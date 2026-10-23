package com.example.minicpm_v_demo.harness.rag

import com.example.minicpm_v_demo.harness.ContentFingerprint
import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.character.CharacterPromptDebug
import com.example.minicpm_v_demo.harness.chat.RenderedChatPrompt
import java.io.File
import java.io.FileWriter

/**
 * Content-free audit record for one prompt compilation.
 *
 * This is deliberately metadata rather than a transcript: it contains hashes,
 * stable internal IDs, counts and reason codes, but never the user query or a
 * character/database statement.  It lets device logs distinguish no lookup,
 * no selected evidence, budget eviction and a model-side answer failure.
 */
data class RagTurnTrace(
    val mode: RagMode,
    val queryFingerprint: String,
    val modelFamily: String,
    val rendererProfile: String,
    val renderedFingerprint: String,
    val systemFingerprint: String,
    val userFingerprint: String,
    val renderedChars: Int,
    val systemChars: Int,
    val userChars: Int,
    val contextChars: Int,
    val external: ExternalRagTrace,
    val character: CharacterRagTrace? = null,
) {
    fun toLogLine(): String = buildString {
        append("RAG_TRACE")
        append(" mode=").append(mode.name)
        append(" querySha=").append(queryFingerprint)
        append(" model=").append(modelFamily)
        append(" template=").append(rendererProfile)
        append(" promptSha=").append(renderedFingerprint)
        append(" systemSha=").append(systemFingerprint)
        append(" userSha=").append(userFingerprint)
        append(" chars=").append(renderedChars).append('/').append(systemChars).append('/').append(userChars)
        append(" external=").append(external.compact())
        character?.let { append(" character=").append(it.compact()) }
    }

    /** Suitable for the existing developer prompt dialog; it contains no prompt text. */
    fun toDiagnosticText(): String = buildString {
        append("RAG Phase 0 trace (不含用户问题和资料正文)\n")
        append("mode: ").append(mode.name).append('\n')
        append("querySha: ").append(queryFingerprint).append('\n')
        append("model/template: ").append(modelFamily).append(" / ").append(rendererProfile).append('\n')
        append("promptSha: ").append(renderedFingerprint).append('\n')
        append("systemSha: ").append(systemFingerprint).append(" (chars=").append(systemChars).append(")\n")
        append("userSha: ").append(userFingerprint).append(" (chars=").append(userChars).append(")\n")
        append("renderedChars: ").append(renderedChars).append(", contextChars: ").append(contextChars).append('\n')
        append("external: ").append(external.compact()).append('\n')
        character?.let { trace ->
            append("character: ").append(trace.compact()).append('\n')
            append("selected: ").append(trace.selectedItemIds.ifEmpty { listOf("none") }.joinToString()).append('\n')
            append("dropped: ").append(trace.droppedItemReasons.ifEmpty { listOf("none") }.joinToString()).append('\n')
            append("profile/state/possession: ")
                .append(trace.profileRoute).append('/').append(trace.stateRoute).append('/').append(trace.possessionRoute)
                .append('\n')
            append("intents/surfaces: ")
                .append(trace.queryIntents.ifEmpty { listOf("none") }.joinToString("+"))
                .append(" / ")
                .append(trace.requestedKnowledgeSurfaces.ifEmpty { listOf("none") }.joinToString("+"))
                .append('\n')
            append("routeCodes: ")
                .append(trace.queryExplanationCodes.ifEmpty { listOf("none") }.joinToString("+"))
                .append('\n')
            append("evidenceSha: ").append(trace.dynamicEvidenceFingerprint)
                .append(", selectedIdsSha: ").append(trace.selectedItemsFingerprint)
                .append(", reservedChars: ").append(trace.reservedDynamicEvidenceChars).append('\n')
        }
    }

    /** One JSONL record for local debug diagnostics; no raw prompt or query is serialized. */
    fun toJsonLine(): String = buildString {
        append('{')
        jsonField("mode", mode.name)
        append(',')
        jsonField("querySha256", queryFingerprint)
        append(',')
        jsonField("modelFamily", modelFamily)
        append(',')
        jsonField("rendererProfile", rendererProfile)
        append(',')
        jsonField("renderedSha256", renderedFingerprint)
        append(',')
        jsonField("systemSha256", systemFingerprint)
        append(',')
        jsonField("userSha256", userFingerprint)
        append(",\"renderedChars\":").append(renderedChars)
        append(",\"systemChars\":").append(systemChars)
        append(",\"userChars\":").append(userChars)
        append(",\"contextChars\":").append(contextChars)
        append(",\"external\":")
        external.appendJson(this)
        append(",\"character\":")
        character?.appendJson(this) ?: append("null")
        append('}')
    }

    companion object {
        fun create(
            mode: RagMode,
            userInput: String,
            modelFamily: HarnessModelFamily,
            rendered: RenderedChatPrompt,
            contextText: String,
            vectorRetrieval: VectorRetrievalAttempt,
            externalInjectionRequested: Boolean,
            sources: List<RagSource>,
            contextDecisions: List<RagContextDecision>,
            characterDebug: CharacterPromptDebug?,
        ): RagTurnTrace = RagTurnTrace(
            mode = mode,
            queryFingerprint = ContentFingerprint.sha256Short(userInput),
            modelFamily = modelFamily.name,
            rendererProfile = rendered.profile,
            renderedFingerprint = ContentFingerprint.sha256Short(rendered.renderedSinglePrompt),
            systemFingerprint = ContentFingerprint.sha256Short(rendered.splitPrompt.systemPrompt),
            userFingerprint = ContentFingerprint.sha256Short(rendered.splitPrompt.userPrompt),
            renderedChars = rendered.renderedSinglePrompt.length,
            systemChars = rendered.splitPrompt.systemPrompt.length,
            userChars = rendered.splitPrompt.userPrompt.length,
            contextChars = contextText.length,
            external = ExternalRagTrace(
                vectorSearchExecuted = vectorRetrieval.executed,
                vectorCollection = vectorRetrieval.collection,
                vectorCandidateLimit = vectorRetrieval.candidateLimit,
                vectorCandidateIds = vectorRetrieval.candidateIds,
                vectorElapsedMs = vectorRetrieval.elapsedMs,
                vectorBackend = vectorRetrieval.backend,
                vectorFailureCode = vectorRetrieval.failureCode,
                externalInjectionRequested = externalInjectionRequested,
                injectedSourceIds = sources.map { source -> source.stableId() },
                decisionReasonCodes = contextDecisions.map { decision ->
                    "${decision.stableId()}:${decision.decision}:${decision.reason}"
                },
            ),
            character = characterDebug?.let(CharacterRagTrace::from),
        )
    }
}

/** Debug-only append-only writer for content-free per-turn JSONL audit records. */
class RagTurnTraceWriter(
    private val outputFile: File,
) {
    @Synchronized
    fun append(trace: RagTurnTrace) {
        outputFile.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Failed to create RAG trace directory" }
        }
        FileWriter(outputFile, true).use { writer ->
            writer.append(trace.toJsonLine())
            writer.append('\n')
        }
    }
}

data class ExternalRagTrace(
    val vectorSearchExecuted: Boolean,
    val vectorCollection: String,
    val vectorCandidateLimit: Int,
    val vectorCandidateIds: List<String>,
    val vectorElapsedMs: Long,
    val vectorBackend: String,
    val vectorFailureCode: String?,
    val externalInjectionRequested: Boolean,
    val injectedSourceIds: List<String>,
    val decisionReasonCodes: List<String>,
) {
    fun compact(): String = "vectorExecuted=$vectorSearchExecuted,collection=$vectorCollection," +
        "candidates=${vectorCandidateIds.size}/$vectorCandidateLimit,backend=$vectorBackend," +
        "elapsedMs=$vectorElapsedMs,failure=${vectorFailureCode ?: "none"}," +
        "injectRequested=$externalInjectionRequested," +
        "injected=${injectedSourceIds.ifEmpty { listOf("none") }.joinToString("+")}," +
        "decisions=${decisionReasonCodes.size}"

    internal fun appendJson(out: StringBuilder) {
        out.append('{')
        out.append("\"vectorSearchExecuted\":").append(vectorSearchExecuted)
        out.append(',')
        out.jsonField("vectorCollection", vectorCollection)
        out.append(",\"vectorCandidateLimit\":").append(vectorCandidateLimit)
        out.append(",\"vectorCandidateIds\":")
        out.jsonArray(vectorCandidateIds)
        out.append(",\"vectorElapsedMs\":").append(vectorElapsedMs)
        out.append(',')
        out.jsonField("vectorBackend", vectorBackend)
        out.append(',')
        if (vectorFailureCode == null) {
            out.append("\"vectorFailureCode\":null")
        } else {
            out.jsonField("vectorFailureCode", vectorFailureCode)
        }
        out.append(",\"externalInjectionRequested\":").append(externalInjectionRequested)
        out.append(",\"injectedSourceIds\":")
        out.jsonArray(injectedSourceIds)
        out.append(",\"decisionReasonCodes\":")
        out.jsonArray(decisionReasonCodes)
        out.append('}')
    }
}

data class CharacterRagTrace(
    val characterId: String,
    val storyCutoff: String,
    val intent: String,
    val followUpRoute: String,
    val selectedItemIds: List<String>,
    val droppedItemReasons: List<String>,
    val profileRoute: String,
    val stateRoute: String,
    val possessionRoute: String,
    val queryIntents: List<String>,
    val requestedKnowledgeSurfaces: List<String>,
    val queryExplanationCodes: List<String>,
    val v2Status: String,
    val v1ClaimStatus: String,
    val v1RelationshipStatus: String,
    val legacyStoryExecuted: Boolean,
    val basePromptFingerprint: String,
    val dynamicEvidenceFingerprint: String,
    val selectedItemsFingerprint: String,
    val reservedDynamicEvidenceChars: Int,
) {
    fun compact(): String = "npc=$characterId,cutoff=$storyCutoff,intent=$intent,followUp=$followUpRoute," +
        "selected=${selectedItemIds.size},dropped=${droppedItemReasons.size}," +
        "v2=$v2Status,v1=$v1ClaimStatus,relations=$v1RelationshipStatus,legacy=$legacyStoryExecuted," +
        "intents=${queryIntents.joinToString("+")},surfaces=${requestedKnowledgeSurfaces.joinToString("+")}," +
        "baseSha=$basePromptFingerprint,dynamicSha=$dynamicEvidenceFingerprint," +
        "reservedChars=$reservedDynamicEvidenceChars"

    companion object {
        fun from(debug: CharacterPromptDebug): CharacterRagTrace = CharacterRagTrace(
            characterId = debug.npcId,
            storyCutoff = debug.storyCutoff,
            intent = debug.knowledgeQueryIntent,
            followUpRoute = debug.followUpRoute,
            selectedItemIds = debug.selectedItems.map { item -> "${item.kind}:${item.id}" },
            droppedItemReasons = debug.droppedItems.map { item -> "${item.kind}:${item.id}:${item.reason}" },
            profileRoute = debug.profileRoute,
            stateRoute = debug.stateRoute,
            possessionRoute = debug.possessionRoute,
            queryIntents = debug.queryIntents,
            requestedKnowledgeSurfaces = debug.requestedKnowledgeSurfaces,
            queryExplanationCodes = debug.queryExplanationCodes,
            v2Status = debug.knowledgeGraphStatus,
            v1ClaimStatus = debug.v1ClaimGraphStatus,
            v1RelationshipStatus = debug.v1RelationshipGraphStatus,
            legacyStoryExecuted = debug.legacyStoryRetrievalExecuted,
            basePromptFingerprint = debug.basePromptFingerprint,
            dynamicEvidenceFingerprint = debug.dynamicEvidenceFingerprint,
            selectedItemsFingerprint = debug.selectedItemsFingerprint,
            reservedDynamicEvidenceChars = debug.reservedDynamicEvidenceChars,
        )
    }

    internal fun appendJson(out: StringBuilder) {
        out.append('{')
        out.jsonField("characterId", characterId)
        out.append(',')
        out.jsonField("storyCutoff", storyCutoff)
        out.append(',')
        out.jsonField("intent", intent)
        out.append(',')
        out.jsonField("followUpRoute", followUpRoute)
        out.append(",\"selectedItemIds\":")
        out.jsonArray(selectedItemIds)
        out.append(",\"droppedItemReasons\":")
        out.jsonArray(droppedItemReasons)
        out.append(',')
        out.jsonField("profileRoute", profileRoute)
        out.append(',')
        out.jsonField("stateRoute", stateRoute)
        out.append(',')
        out.jsonField("possessionRoute", possessionRoute)
        out.append(",\"queryIntents\":")
        out.jsonArray(queryIntents)
        out.append(",\"requestedKnowledgeSurfaces\":")
        out.jsonArray(requestedKnowledgeSurfaces)
        out.append(",\"queryExplanationCodes\":")
        out.jsonArray(queryExplanationCodes)
        out.append(',')
        out.jsonField("v2Status", v2Status)
        out.append(',')
        out.jsonField("v1ClaimStatus", v1ClaimStatus)
        out.append(',')
        out.jsonField("v1RelationshipStatus", v1RelationshipStatus)
        out.append(",\"legacyStoryExecuted\":").append(legacyStoryExecuted)
        out.append(',')
        out.jsonField("basePromptSha256", basePromptFingerprint)
        out.append(',')
        out.jsonField("dynamicEvidenceSha256", dynamicEvidenceFingerprint)
        out.append(',')
        out.jsonField("selectedItemsSha256", selectedItemsFingerprint)
        out.append(",\"reservedDynamicEvidenceChars\":").append(reservedDynamicEvidenceChars)
        out.append('}')
    }
}

private fun RagSource.stableId(): String = "$source@$start:$end"

private fun RagContextDecision.stableId(): String = "$source@$start:$end"

private fun StringBuilder.jsonField(name: String, value: String) {
    append('"').append(name.jsonEscaped()).append("\":\"").append(value.jsonEscaped()).append('"')
}

private fun StringBuilder.jsonArray(values: List<String>) {
    append('[')
    values.forEachIndexed { index, value ->
        if (index > 0) append(',')
        append('"').append(value.jsonEscaped()).append('"')
    }
    append(']')
}

private fun String.jsonEscaped(): String = buildString(length) {
    for (char in this@jsonEscaped) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}
