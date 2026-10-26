package com.local.wxbotprobe.memory.v2

import android.content.Context

enum class MemoryMessageBodyRetention(
    val label: String,
    val seconds: Long,
) {
    ONE_HOUR("1 小时", 3_600L),
    ONE_DAY("24 小时", 86_400L),
    THREE_DAYS("72 小时", 259_200L),
    SEVEN_DAYS("7 天", 604_800L),
    ;

    companion object {
        fun fromStorage(value: String?): MemoryMessageBodyRetention {
            return entries.firstOrNull { it.name == value } ?: ONE_DAY
        }
    }
}

object MemoryCleanupSettings {
    private const val PREFS = "memory_cleanup_settings"
    private const val KEY_BODY_RETENTION = "body_retention"

    fun bodyRetention(context: Context): MemoryMessageBodyRetention {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BODY_RETENTION, null)
        return MemoryMessageBodyRetention.fromStorage(stored)
    }

    fun setBodyRetention(
        context: Context,
        retention: MemoryMessageBodyRetention,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BODY_RETENTION, retention.name)
            .apply()
    }
}

object MemoryV2CleanupPolicy {
    private val extractionTerminal = setOf("done", "skipped", "gap")
    private val summaryTerminal = setOf("done", "gap")

    fun isFullyProcessed(
        extractionState: String,
        summaryState: String,
        hasActiveJob: Boolean,
    ): Boolean {
        return !hasActiveJob &&
            extractionState in extractionTerminal &&
            summaryState in summaryTerminal
    }

    fun bodyDueAt(
        completedAt: Long,
        retention: MemoryMessageBodyRetention,
    ): Long {
        return completedAt + retention.seconds
    }

    fun metadataDueAt(
        completedAt: Long,
        historySeconds: Long,
    ): Long {
        return completedAt + historySeconds.coerceAtLeast(86_400L)
    }
}
