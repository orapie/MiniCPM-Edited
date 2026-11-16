package com.example.minicpm_v_demo.harness.session

import com.example.minicpm_v_demo.harness.rag.RagSource
import com.example.minicpm_v_demo.harness.character.CharacterFollowUpTarget

/** 记录一次聊天关联的数据集、角色、剧情截止点与模型，并保存已经完成的回合。 */
data class ChatSession(
    val sessionId: String,
    val characterId: String,
    val characterPackId: String,
    val storyCutoff: String?,
    val selectedModelId: String,
    val turns: List<ChatTurn> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Missing fields mean legacy history, never an active database session. */
    val datasetId: String? = null,
    val datasetFingerprint: String? = null,
)

data class ChatTurn(
    val turnId: String,
    val userText: String,
    val assistantText: String,
    val ragMode: String,
    val sources: List<RagSource> = emptyList(),
    /** Opaque internal IDs selected for this turn; no fact or prompt body is stored. */
    val characterContextTargets: List<CharacterFollowUpTarget> = emptyList(),
    val renderedPrompt: String? = null,
    val characterId: String,
    val modelId: String,
    val createdAt: Long = System.currentTimeMillis(),
)
