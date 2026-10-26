package com.local.wxbotprobe

import android.content.Context

object SendSettings {
    private const val PREFS = "send_settings"
    private const val KEY_AUTO_SEND = "auto_send"
    private const val KEY_ORIGINAL_IME = "original_ime"
    private const val KEY_WHITELIST = "send_whitelist"
    private const val KEY_DAILY_MESSAGE_LIMIT = "daily_message_limit"
    private const val KEY_DAILY_COST_LIMIT = "daily_cost_limit"
    private const val KEY_QUIET_ENABLED = "quiet_enabled"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"

    const val DEFAULT_DAILY_MESSAGE_LIMIT = 50
    const val DEFAULT_DAILY_COST_LIMIT_USD = 1.0f
    const val DEFAULT_QUIET_START_MINUTE = 23 * 60
    const val DEFAULT_QUIET_END_MINUTE = 7 * 60
    const val UNLIMITED_MESSAGE_LIMIT = Int.MAX_VALUE
    val UNLIMITED_DAILY_COST_LIMIT_USD = Double.POSITIVE_INFINITY
    val MESSAGE_LIMIT_OPTIONS = listOf(
        10,
        20,
        50,
        100,
        200,
        UNLIMITED_MESSAGE_LIMIT,
    )
    val COST_LIMIT_OPTIONS = listOf(
        0.1f,
        0.3f,
        1.0f,
        3.0f,
        5.0f,
        Float.POSITIVE_INFINITY,
    )

    fun autoSend(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SEND, false)
    }

    fun setAutoSend(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SEND, enabled)
            .apply()
    }

    fun originalIme(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORIGINAL_IME, null)
    }

    fun setOriginalIme(context: Context, ime: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ORIGINAL_IME, ime)
            .apply()
    }

    fun whitelist(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_WHITELIST, emptySet())
            .orEmpty()
            .toSet()
    }

    fun setWhitelist(context: Context, values: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_WHITELIST, values)
            .apply()
    }

    fun dailyMessageLimit(context: Context): Int {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DAILY_MESSAGE_LIMIT, DEFAULT_DAILY_MESSAGE_LIMIT)
        return if (value == UNLIMITED_MESSAGE_LIMIT) value else value.coerceAtLeast(1)
    }

    fun setDailyMessageLimit(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(
                KEY_DAILY_MESSAGE_LIMIT,
                if (value == UNLIMITED_MESSAGE_LIMIT) value else value.coerceAtLeast(1),
            )
            .apply()
    }

    fun dailyCostLimitUsd(context: Context): Double {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_DAILY_COST_LIMIT, DEFAULT_DAILY_COST_LIMIT_USD)
            .toDouble()
        return if (value.isInfinite()) value else value.coerceAtLeast(0.01)
    }

    fun setDailyCostLimitUsd(context: Context, value: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(
                KEY_DAILY_COST_LIMIT,
                if (value.isInfinite()) {
                    Float.POSITIVE_INFINITY
                } else {
                    value.coerceAtLeast(0.01).toFloat()
                },
            )
            .apply()
    }

    fun quietHoursEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_QUIET_ENABLED, true)
    }

    fun setQuietHoursEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_QUIET_ENABLED, enabled)
            .apply()
    }

    fun quietStartMinute(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_QUIET_START, DEFAULT_QUIET_START_MINUTE)
            .coerceIn(0, MINUTES_PER_DAY - 1)
    }

    fun quietEndMinute(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_QUIET_END, DEFAULT_QUIET_END_MINUTE)
            .coerceIn(0, MINUTES_PER_DAY - 1)
    }

    fun setQuietRange(context: Context, startMinute: Int, endMinute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_QUIET_START, startMinute.coerceIn(0, MINUTES_PER_DAY - 1))
            .putInt(KEY_QUIET_END, endMinute.coerceIn(0, MINUTES_PER_DAY - 1))
            .apply()
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
