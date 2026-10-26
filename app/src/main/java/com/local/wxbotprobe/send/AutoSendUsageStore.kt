package com.local.wxbotprobe.send

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoSendUsageStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun sentToday(nowMillis: Long = System.currentTimeMillis()): Int {
        val date = localDate(nowMillis)
        return if (preferences.getString(KEY_DATE, "") == date) {
            preferences.getInt(KEY_COUNT, 0)
        } else {
            0
        }
    }

    @Synchronized
    fun increment(nowMillis: Long = System.currentTimeMillis()) {
        val date = localDate(nowMillis)
        val current = sentToday(nowMillis)
        preferences.edit()
            .putString(KEY_DATE, date)
            .putInt(KEY_COUNT, current + 1)
            .apply()
    }

    private fun localDate(nowMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(nowMillis))
    }

    companion object {
        private const val PREFS = "auto_send_usage"
        private const val KEY_DATE = "date"
        private const val KEY_COUNT = "count"
    }
}
