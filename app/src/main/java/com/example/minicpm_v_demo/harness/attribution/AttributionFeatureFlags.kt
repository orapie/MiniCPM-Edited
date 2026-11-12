package com.example.minicpm_v_demo.harness.attribution

import android.content.Context

/** Explicit opt-in: a second local GGUF has material memory and latency cost. */
class AttributionFeatureFlags(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val localAgentAEnabled: Boolean
        get() = preferences.getBoolean(KEY_LOCAL_AGENT_A_ENABLED, false)

    fun setLocalAgentAEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_LOCAL_AGENT_A_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "rag_attribution_flags"
        private const val KEY_LOCAL_AGENT_A_ENABLED = "double_agent_attribution"
    }
}
