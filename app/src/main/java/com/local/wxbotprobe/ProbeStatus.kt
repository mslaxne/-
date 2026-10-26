package com.local.wxbotprobe

import android.content.Context
import android.content.Intent

object ProbeStatus {
    const val BROADCAST_ACTION = "com.local.wxbotprobe.STATUS_UPDATED"

    fun write(context: Context, message: String, screenshotPath: String? = null) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_TIME, System.currentTimeMillis())

        if (screenshotPath != null) {
            editor.putString(KEY_SCREENSHOT_PATH, screenshotPath)
        }
        editor.apply()
        context.sendBroadcast(Intent(BROADCAST_ACTION).setPackage(context.packageName))
    }

    fun message(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MESSAGE, "尚未测试")
            .orEmpty()
    }

    fun time(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_TIME, 0L)
    }

    fun screenshotPath(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCREENSHOT_PATH, null)
    }

    private const val PREFS = "probe_status"
    private const val KEY_MESSAGE = "status_message"
    private const val KEY_TIME = "status_time"
    private const val KEY_SCREENSHOT_PATH = "screenshot_path"
}
