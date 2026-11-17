package com.example.minicpm_v_demo.harness.chat

import com.example.minicpm_v_demo.harness.HarnessModelFamily
import com.example.minicpm_v_demo.harness.character.HarnessChatMessage

data class SplitPrompt(
    val systemPrompt: String,
    val userPrompt: String,
)

/** 同时携带按模板拼接的诊断文本和供推理接口使用的分段消息。 */
data class RenderedChatPrompt(
    val messages: List<HarnessChatMessage>,
    val renderedSinglePrompt: String,
    val splitPrompt: SplitPrompt,
    val profile: String,
)

/** 校验消息顺序并同时保留分开的 system/user 文本和按模型模板拼接的预览。 */
class ChatTemplateRenderer(
    private val profile: String = "android_send_user_prompt_v1",
) {
    fun render(messages: List<HarnessChatMessage>): RenderedChatPrompt {
        require(messages.size == 2) { "Android renderer expects exactly system and user messages" }
        require(messages[0].role == "system" && messages[1].role == "user") {
            "messages must be ordered as system, user"
        }
        val system = messages[0].content
        val user = messages[1].content
        require(system.isNotBlank() && user.isNotBlank()) { "message content must be non-empty" }
        return RenderedChatPrompt(
            messages = messages,
            renderedSinglePrompt = renderSingle(system, user),
            splitPrompt = SplitPrompt(system, user),
            profile = profile,
        )
    }

    private fun renderSingle(system: String, user: String): String {
        return when (profile) {
            "llama" -> "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
                "$system<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
                "$user<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n"
            "qwen", "chatml" -> "<|im_start|>system\n$system<|im_end|>\n" +
                "<|im_start|>user\n$user<|im_end|>\n" +
                "<|im_start|>assistant\n"
            else -> "<|system|>\n$system\n\n<|user|>\n$user\n\n<|assistant|>\n"
        }
    }

    companion object {
        fun forModelFamily(family: HarnessModelFamily): ChatTemplateRenderer {
            val profile = when (family) {
                HarnessModelFamily.LLAMA -> "llama"
                HarnessModelFamily.QWEN -> "qwen"
                // Matches llama.cpp's safe fallback when a MiniCPM GGUF has
                // no embedded chat_template. Native Character+RAG calls send
                // system/user messages separately, so debug rendering must not
                // imply the obsolete raw-text layout.
                HarnessModelFamily.MINICPM_TEXT -> "chatml"
                else -> "android_send_user_prompt_v1"
            }
            return ChatTemplateRenderer(profile)
        }
    }
}

