package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import org.json.JSONObject

class DebugBaselineOnlyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) {
            return
        }
        val phase = intent.getStringExtra(EXTRA_PHASE).orEmpty()
        val prefs = context.getSharedPreferences(
            BotLoopService.DEBUG_BASELINE_PREFS,
            Context.MODE_PRIVATE,
        )
        val enabled = when (phase) {
            PHASE_ENABLE -> true
            PHASE_DISABLE -> false
            PHASE_STATUS -> prefs.getBoolean(
                BotLoopService.DEBUG_BASELINE_ONLY,
                false,
            )
            else -> error("unknown baseline-only phase: $phase")
        }
        if (phase != PHASE_STATUS) {
            prefs.edit()
                .putBoolean(BotLoopService.DEBUG_BASELINE_ONLY, enabled)
                .apply()
        }
        val output = JSONObject()
            .put("status", "passed")
            .put("phase", phase)
            .put("baseline_only", enabled)
        val file = File(context.filesDir, "debug/baseline-only.json")
        file.parentFile?.mkdirs()
        file.writeText(output.toString(2), Charsets.UTF_8)
    }

    companion object {
        const val ACTION = "com.local.wxbotprobe.DEBUG_BASELINE_ONLY"
        const val EXTRA_PHASE = "phase"
        const val PHASE_ENABLE = "enable"
        const val PHASE_DISABLE = "disable"
        const val PHASE_STATUS = "status"
    }
}
