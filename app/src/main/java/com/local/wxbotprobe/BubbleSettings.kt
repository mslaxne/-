package com.local.wxbotprobe

import android.content.Context

object BubbleSettings {
    private const val PREFS = "bubble_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SIDE = "side"
    private const val KEY_Y = "y"

    fun enabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun side(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SIDE, "right")
            .orEmpty()
            .takeIf { it == "left" || it == "right" }
            ?: "right"
    }

    fun setSide(context: Context, side: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SIDE, if (side == "left") "left" else "right")
            .apply()
    }

    fun y(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_Y, -1)
    }

    fun setY(context: Context, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_Y, y)
            .apply()
    }
}
