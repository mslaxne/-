package com.local.wxbotprobe

import android.content.Context

class AgentStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AgentRunState {
        val stored = preferences.getString(KEY_RUN_STATE, null) ?: return AgentRunState.OFF
        return AgentRunState.entries.firstOrNull { it.name == stored } ?: AgentRunState.OFF
    }

    fun save(state: AgentRunState) {
        preferences.edit().putString(KEY_RUN_STATE, state.name).apply()
    }

    companion object {
        private const val PREFS = "agent_run_state"
        private const val KEY_RUN_STATE = "state"
    }
}
