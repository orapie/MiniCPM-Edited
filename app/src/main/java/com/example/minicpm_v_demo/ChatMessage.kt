package com.example.minicpm_v_demo

import com.example.minicpm_v_demo.harness.rag.RagMode
import com.example.minicpm_v_demo.harness.rag.RagSource

/** 聊天列表支持的行类型。助手消息还携带来源和提示词诊断信息，供详情弹窗读取。 */
sealed class ChatMessage {
    abstract val id: Long

    data class UserMessage(
        override val id: Long,
        val text: String,
    ) : ChatMessage()

    data class AiMessage(
        override val id: Long,
        val text: String,
        val isGenerating: Boolean = false,
        val ragMode: RagMode? = null,
        val sources: List<RagSource> = emptyList(),
        val debugPrompt: String? = null,
        /** Content-free Phase 0 trace; intentionally not persisted with chat history. */
        val debugTrace: String? = null,
    ) : ChatMessage()

    data class WelcomeCard(
        override val id: Long = 0L,
    ) : ChatMessage()
}
