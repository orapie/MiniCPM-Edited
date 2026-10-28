package com.example.minicpm_v_demo.harness.session

import com.example.minicpm_v_demo.harness.data.HarnessDataPaths
import com.example.minicpm_v_demo.harness.character.CharacterFollowUpTarget
import com.example.minicpm_v_demo.harness.data.database.DatabaseIdentity
import com.example.minicpm_v_demo.harness.rag.RagSource
import com.example.minicpm_v_demo.harness.rag.SimpleJsonParser
import com.example.minicpm_v_demo.harness.rag.asJsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ChatSessionStore(
    private val paths: HarnessDataPaths,
    private val summaryBuilder: ConversationSummaryBuilder = ConversationSummaryBuilder(),
    private val datasetIdentity: DatabaseIdentity? = null,
    private val allowedCharacterIds: Set<String>? = null,
    private val allowedCutoffs: Set<String>? = null,
) {
    private val legacyRecentSessionFile = File(paths.sessionsDir, "recent_session.json")
    private val scopedDirectory: File? = datasetIdentity?.let {
        File(paths.sessionsDir, "by-dataset/${it.datasetId}/${it.fingerprint}")
    }
    private val recentSessionFile: File = scopedDirectory?.let { File(it, "recent_session.json") } ?: legacyRecentSessionFile

    fun create(
        characterId: String,
        storyCutoff: String?,
        selectedModelId: String,
        characterPackId: String = "character_system",
    ): ChatSession {
        return ChatSession(
            sessionId = UUID.randomUUID().toString().replace("-", ""),
            characterId = characterId,
            storyCutoff = storyCutoff,
            selectedModelId = selectedModelId,
            datasetId = datasetIdentity?.datasetId,
            datasetFingerprint = datasetIdentity?.fingerprint,
            characterPackId = datasetIdentity?.datasetId ?: characterPackId,
        ).also { save(it) }
    }

    fun loadRecentSession(): ChatSession? {
        if (!recentSessionFile.isFile) return null
        return runCatching {
            parseSession(SimpleJsonParser(recentSessionFile.readText(Charsets.UTF_8)).parse().asJsonObject("session"))
        }.getOrNull()?.takeIf(::isCompatible)
    }

    fun appendTurn(session: ChatSession, turn: ChatTurn): ChatSession {
        val turns = session.turns + turn
        val updated = session.copy(
            turns = turns,
            conversationSummary = summaryBuilder.build(turns),
            selectedModelId = turn.modelId,
            updatedAt = System.currentTimeMillis(),
        )
        save(updated)
        return updated
    }

    fun save(session: ChatSession) {
        paths.ensureRuntimeDirs()
        require(isCompatible(session)) { "session_dataset_or_character_mismatch" }
        require(session.sessionId.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid session ID" }
        val text = serialize(session)
        scopedDirectory?.let { atomicWrite(File(it, "history/${session.sessionId}.json"), text) }
        atomicWrite(recentSessionFile, text)
    }

    /** Read-only browsing. These records are not eligible for prompt context. */
    fun listHistory(): List<ChatSession> {
        val root = File(paths.sessionsDir, "by-dataset")
        val files = mutableListOf(legacyRecentSessionFile)
        val datasetDirectories = if (root.canonicalFile.parentFile == paths.sessionsDir.canonicalFile) root.listFiles().orEmpty() else emptyArray()
        datasetDirectories.filter { it.isDirectory && it.canonicalFile.parentFile == root.canonicalFile }
            .forEach { dataset -> dataset.listFiles().orEmpty()
                .filter { it.isDirectory && it.canonicalFile.parentFile == dataset.canonicalFile }
                .forEach { version ->
                    val history = File(version, "history")
                    if (history.canonicalFile.parentFile == version.canonicalFile) {
                        files += history.listFiles().orEmpty().filter { it.isFile && it.extension == "json" && it.canonicalFile.parentFile == history.canonicalFile }
                    }
                }
            }
        return files.filter(File::isFile).mapNotNull { file ->
            runCatching { parseSession(SimpleJsonParser(file.readText(Charsets.UTF_8)).parse().asJsonObject("session")) }.getOrNull()
        }.distinctBy { Triple(it.sessionId, it.datasetId, it.datasetFingerprint) }.sortedByDescending { it.updatedAt }
    }

    private fun isCompatible(session: ChatSession): Boolean {
        if (datasetIdentity == null) return true // legacy fixture / explicit history access
        return session.datasetId == datasetIdentity.datasetId && session.datasetFingerprint == datasetIdentity.fingerprint &&
            session.characterPackId == datasetIdentity.datasetId &&
            (allowedCharacterIds == null || session.characterId in allowedCharacterIds) &&
            (allowedCutoffs == null || (session.storyCutoff != null && session.storyCutoff in allowedCutoffs)) &&
            session.turns.all { it.characterId == session.characterId }
    }

    private fun atomicWrite(file: File, text: String) {
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create session directory" }
        val temp = File(parent, ".session-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(temp.renameTo(file)) { "Cannot replace session file" }
        } finally { if (temp.exists()) temp.delete() }
    }

    fun clearRecentSession() {
        if (recentSessionFile.isFile) recentSessionFile.delete()
    }

    private fun serialize(session: ChatSession): String {
        return jsonObject(
            mapOf(
                "session_id" to session.sessionId,
                "character_id" to session.characterId,
                "character_pack_id" to session.characterPackId,
                "dataset_id" to session.datasetId,
                "dataset_fingerprint" to session.datasetFingerprint,
                "story_cutoff" to session.storyCutoff,
                "selected_model_id" to session.selectedModelId,
                "turns" to session.turns.map { serializeTurnMap(it) },
                "conversation_summary" to session.conversationSummary,
                "created_at" to session.createdAt,
                "updated_at" to session.updatedAt,
            ),
        )
    }

    private fun serializeTurnMap(turn: ChatTurn): Map<String, Any?> {
        return mapOf(
            "turn_id" to turn.turnId,
            "user_text" to turn.userText,
            "assistant_text" to turn.assistantText,
            "rag_mode" to turn.ragMode,
            "sources" to turn.sources.map {
                mapOf(
                    "source" to it.source,
                    "start" to it.start,
                    "end" to it.end,
                    "score" to it.score,
                )
            },
            "character_context_targets" to turn.characterContextTargets.map {
                mapOf("kind" to it.kind, "id" to it.id)
            },
            "rendered_prompt" to turn.renderedPrompt,
            "character_id" to turn.characterId,
            "model_id" to turn.modelId,
            "created_at" to turn.createdAt,
        )
    }

    private fun parseSession(value: Map<String, Any?>): ChatSession {
        return ChatSession(
            sessionId = value.optionalString("session_id").orEmpty(),
            characterId = value.optionalString("character_id").orEmpty(),
            characterPackId = value.optionalString("character_pack_id") ?: "character_system",
            storyCutoff = value.optionalString("story_cutoff"),
            selectedModelId = value.optionalString("selected_model_id").orEmpty(),
            turns = (value["turns"] as? List<*>).orEmpty().mapNotNull { raw ->
                (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.let(::parseTurn)
            },
            conversationSummary = value.optionalString("conversation_summary").orEmpty(),
            createdAt = value.optionalLong("created_at") ?: 0L,
            updatedAt = value.optionalLong("updated_at") ?: 0L,
            datasetId = value.optionalString("dataset_id"),
            datasetFingerprint = value.optionalString("dataset_fingerprint"),
        )
    }

    private fun parseTurn(value: Map<String, Any?>): ChatTurn {
        return ChatTurn(
            turnId = value.optionalString("turn_id").orEmpty(),
            userText = value.optionalString("user_text").orEmpty(),
            assistantText = value.optionalString("assistant_text").orEmpty(),
            ragMode = value.optionalString("rag_mode").orEmpty(),
            sources = (value["sources"] as? List<*>).orEmpty().mapNotNull { raw ->
                (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.let(::parseSource)
            },
            characterContextTargets = (value["character_context_targets"] as? List<*>).orEmpty().mapNotNull { raw ->
                (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.let { target ->
                    val kind = target.optionalString("kind")
                    val id = target.optionalString("id")
                    if (kind.isNullOrBlank() || id.isNullOrBlank()) null else CharacterFollowUpTarget(kind, id)
                }
            },
            renderedPrompt = value.optionalString("rendered_prompt"),
            characterId = value.optionalString("character_id").orEmpty(),
            modelId = value.optionalString("model_id").orEmpty(),
            createdAt = value.optionalLong("created_at") ?: 0L,
        )
    }

    private fun parseSource(value: Map<String, Any?>): RagSource {
        return RagSource(
            source = value.optionalString("source").orEmpty(),
            start = value.optionalLong("start")?.toInt() ?: 0,
            end = value.optionalLong("end")?.toInt() ?: 0,
            score = value.optionalDouble("score") ?: 0.0,
        )
    }
}
