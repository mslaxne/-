package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BubbleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        if (!BubbleSettings.enabled(context) || !Settings.canDrawOverlays(context)) {
            return
        }
        context.startForegroundService(
            Intent(context, AgentBubbleService::class.java).setAction(
                AgentBubbleService.ACTION_SHOW,
            ),
        )
    }
}
