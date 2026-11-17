package com.example.minicpm_v_demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.noties.markwon.Markwon

/** 将用户消息、助手回复和欢迎卡片绑定到聊天列表。生成中的回复使用独立更新路径，避免每个片段都重新提交列表。 */
class ChatAdapter(
    private val markwon: Markwon
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_WELCOME = 0
        private const val TYPE_USER = 1
        private const val TYPE_AI = 2
    }

    private var onSuggestionClick: ((String) -> Unit)? = null
    private var onStopClick: (() -> Unit)? = null
    private var onSourcesClick: ((ChatMessage.AiMessage) -> Unit)? = null
    private var onPromptClick: ((ChatMessage.AiMessage) -> Unit)? = null

    private var activeAiHolder: AiMessageViewHolder? = null
    private var activeAiId: Long = -1L

    fun setOnSuggestionClick(listener: (String) -> Unit) {
        onSuggestionClick = listener
    }

    fun setOnStopClick(listener: () -> Unit) {
        onStopClick = listener
    }

    fun setOnSourcesClick(listener: (ChatMessage.AiMessage) -> Unit) {
        onSourcesClick = listener
    }

    fun setOnPromptClick(listener: (ChatMessage.AiMessage) -> Unit) {
        onPromptClick = listener
    }

    fun setActiveAiMessage(id: Long) {
        activeAiId = id
    }

    fun clearActiveAiMessage() {
        activeAiId = -1L
        activeAiHolder = null
    }

    /** 仅刷新当前生成消息所绑定的可见气泡。完整的消息状态由聊天页维护。 */
    fun updateStreamingText(id: Long, text: String) {
        if (id == activeAiId) {
            activeAiHolder?.updateText(text)
        }
    }

    fun setGeneratingDone(id: Long) {
        if (id == activeAiId) {
            activeAiHolder?.setStopButtonVisible(false)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ChatMessage.WelcomeCard -> TYPE_WELCOME
            is ChatMessage.UserMessage -> TYPE_USER
            is ChatMessage.AiMessage -> TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_WELCOME -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_welcome_card, parent, false)
                WelcomeViewHolder(view)
            }
            TYPE_USER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_user_message, parent, false)
                UserMessageViewHolder(view)
            }
            TYPE_AI -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_ai_message, parent, false)
                AiMessageViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatMessage.WelcomeCard -> (holder as WelcomeViewHolder).bind()
            is ChatMessage.UserMessage -> (holder as UserMessageViewHolder).bind(item)
            is ChatMessage.AiMessage -> {
                val aiHolder = holder as AiMessageViewHolder
                aiHolder.bind(item)
                if (item.id == activeAiId) {
                    activeAiHolder = aiHolder
                }
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is AiMessageViewHolder && holder == activeAiHolder) {
            activeAiHolder = null
        }
    }

    inner class WelcomeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnSuggestion1: MaterialButton = itemView.findViewById(R.id.btn_suggestion_1)
        private val btnSuggestion2: MaterialButton = itemView.findViewById(R.id.btn_suggestion_2)

        fun bind() {
            val s1 = btnSuggestion1.text.toString()
            val s2 = btnSuggestion2.text.toString()
            btnSuggestion1.setOnClickListener { onSuggestionClick?.invoke(s1) }
            btnSuggestion2.setOnClickListener { onSuggestionClick?.invoke(s2) }
        }
    }

    inner class UserMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tv_user_text)
        fun bind(item: ChatMessage.UserMessage) {
            tvText.text = item.text
        }
    }

    inner class AiMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tv_ai_text)
        private val btnStop: MaterialButton = itemView.findViewById(R.id.btn_stop_generating)
        private val layoutThinking: View = itemView.findViewById(R.id.layout_thinking)
        private val layoutThinkingHeader: View = itemView.findViewById(R.id.layout_thinking_header)
        private val tvThinkingArrow: TextView = itemView.findViewById(R.id.tv_thinking_arrow)
        private val tvThinkingLabel: TextView = itemView.findViewById(R.id.tv_thinking_label)
        private val tvThinkingText: TextView = itemView.findViewById(R.id.tv_thinking_text)
        private val dividerThinking: View = itemView.findViewById(R.id.divider_thinking)
        private val layoutRagActions: View = itemView.findViewById(R.id.layout_rag_actions)
        private val btnSources: MaterialButton = itemView.findViewById(R.id.btn_sources)
        private val btnPrompt: MaterialButton = itemView.findViewById(R.id.btn_prompt)

        private var thinkingExpanded = false
        private var streamingMinWidth = 0

        fun bind(item: ChatMessage.AiMessage) {
            if (!item.isGenerating) {
                streamingMinWidth = 0
                (tvText.parent as? ViewGroup)?.minimumWidth = 0
            }
            renderWithThinking(item.text, item.isGenerating)
            btnStop.visibility = if (item.isGenerating) View.VISIBLE else View.GONE
            btnStop.setOnClickListener {
                onStopClick?.invoke()
            }
            layoutRagActions.visibility =
                if (!item.isGenerating && (item.sources.isNotEmpty() || item.debugPrompt != null)) View.VISIBLE else View.GONE
            btnSources.visibility = if (item.sources.isNotEmpty()) View.VISIBLE else View.GONE
            btnPrompt.visibility = if (item.debugPrompt != null) View.VISIBLE else View.GONE
            btnSources.setOnClickListener { onSourcesClick?.invoke(item) }
            btnPrompt.setOnClickListener { onPromptClick?.invoke(item) }
        }

        fun updateText(text: String) {
            val contentLayout = tvText.parent as? ViewGroup
            if (contentLayout != null && contentLayout.width > streamingMinWidth) {
                streamingMinWidth = contentLayout.width
            }
            contentLayout?.minimumWidth = streamingMinWidth
            renderWithThinking(text, true)
        }

        fun setStopButtonVisible(visible: Boolean) {
            btnStop.visibility = if (visible) View.VISIBLE else View.GONE
        }

        private fun renderWithThinking(raw: String, isGenerating: Boolean) {
            val parsed = parseThinkingBlock(raw, isGenerating)

            if (parsed.thinkingText != null) {
                layoutThinking.visibility = View.VISIBLE

                if (parsed.isThinking) {
                    tvThinkingLabel.text = itemView.context.getString(R.string.thinking_in_progress)
                    thinkingExpanded = true
                } else {
                    tvThinkingLabel.text = itemView.context.getString(R.string.thinking_process)
                }

                tvThinkingArrow.text = if (thinkingExpanded) "▾" else "▸"
                tvThinkingText.visibility = if (thinkingExpanded) View.VISIBLE else View.GONE
                dividerThinking.visibility = if (!parsed.isThinking) View.VISIBLE else View.GONE

                markwon.setMarkdown(tvThinkingText,
                    MarkdownEscape.normalizeResponseText(parsed.thinkingText))

                layoutThinkingHeader.setOnClickListener {
                    thinkingExpanded = !thinkingExpanded
                    tvThinkingArrow.text = if (thinkingExpanded) "▾" else "▸"
                    tvThinkingText.visibility = if (thinkingExpanded) View.VISIBLE else View.GONE
                }
            } else {
                layoutThinking.visibility = View.GONE
            }

            val display = parsed.responseText
            if (display.isNotEmpty()) {
                tvText.visibility = View.VISIBLE
                markwon.setMarkdown(tvText, MarkdownEscape.normalizeResponseText(display))
            } else {
                tvText.visibility = View.GONE
            }
        }

        private fun parseThinkingBlock(text: String, isGenerating: Boolean): ParsedThinking {
            val thinkStart = text.indexOf("<think>")
            if (thinkStart < 0) {
                return ParsedThinking(null, text, false)
            }

            val contentAfterTag = text.substring(thinkStart + "<think>".length)
            val thinkEnd = contentAfterTag.indexOf("</think>")

            return if (thinkEnd < 0) {
                ParsedThinking(
                    thinkingText = contentAfterTag.trim(),
                    responseText = "",
                    isThinking = true
                )
            } else {
                ParsedThinking(
                    thinkingText = contentAfterTag.substring(0, thinkEnd).trim(),
                    responseText = contentAfterTag.substring(thinkEnd + "</think>".length).trimStart('\n'),
                    isThinking = false
                )
            }
        }
    }

    private data class ParsedThinking(
        val thinkingText: String?,
        val responseText: String,
        val isThinking: Boolean
    )

    class DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return when {
                oldItem is ChatMessage.UserMessage && newItem is ChatMessage.UserMessage ->
                    oldItem.text == newItem.text
                oldItem is ChatMessage.AiMessage && newItem is ChatMessage.AiMessage ->
                    oldItem.isGenerating == newItem.isGenerating &&
                            (oldItem.isGenerating || oldItem.text == newItem.text) &&
                            oldItem.ragMode == newItem.ragMode &&
                            oldItem.sources == newItem.sources &&
                            oldItem.debugPrompt == newItem.debugPrompt &&
                            oldItem.debugTrace == newItem.debugTrace
                oldItem is ChatMessage.WelcomeCard && newItem is ChatMessage.WelcomeCard -> true
                else -> false
            }
        }
    }
}
