package com.example.minicpm_v_demo.harness.attribution

import android.content.Context

/** Explicit opt-in: a second local GGUF has material memory and latency cost. */
class AttributionFeatureFlags(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val localAgentAEnabled: Boolean
        get() = preferences.getBoolean(KEY_LOCAL_AGENT_A_ENABLED, false)

    val agentBPromptFormat: AgentBPromptFormat
        get() = if (preferences.getBoolean("agent_b_json_prompt", false)) AgentBPromptFormat.JSON
            else AgentBPromptFormat.TEXT

    /** 保存本地 Agent A 的显式启用状态，归因服务在请求时读取。 */
    fun setLocalAgentAEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_LOCAL_AGENT_A_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "rag_attribution_flags"
        private const val KEY_LOCAL_AGENT_A_ENABLED = "double_agent_attribution"
    }
}
