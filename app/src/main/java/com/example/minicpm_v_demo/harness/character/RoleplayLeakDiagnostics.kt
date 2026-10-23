package com.example.minicpm_v_demo.harness.character

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Raw model output can contain user text and prompt fragments.  Keep its
 * Logcat recording opt-in even for Debug builds; ordinary observations contain
 * only hashes, rule IDs and lengths.
 */
object RoleplayLeakDiagnostics {
    private const val PREFS_NAME = "roleplay_leak_diagnostics"
    private const val KEY_RAW_OUTPUT_LOGGING = "raw_output_logging"

    fun isRawOutputLoggingEnabled(context: Context): Boolean {
        val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return isDebuggable && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RAW_OUTPUT_LOGGING, false)
    }

    /** Intended for an explicit developer-only toggle; it has no effect in Release builds. */
    fun setRawOutputLoggingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RAW_OUTPUT_LOGGING, enabled)
            .apply()
    }
}
