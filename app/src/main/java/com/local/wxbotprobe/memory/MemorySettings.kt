package com.local.wxbotprobe.memory

object MemorySettings {
    const val DEFAULT_RETENTION_DAYS = 7
    const val PERMANENT_DAYS = 0
    val OPTIONS = listOf(1, 3, 7, 31, PERMANENT_DAYS)

    const val SUMMARY_MODE_DAYS = "days"
    const val SUMMARY_MODE_COUNT = "count"
    const val SUMMARY_MODE_PERMANENT = "permanent"
    const val DEFAULT_SUMMARY_RETENTION_MODE = SUMMARY_MODE_DAYS
    const val DEFAULT_SUMMARY_RETENTION_VALUE = 30

    data class SummaryRetentionOption(
        val mode: String,
        val value: Int,
        val label: String,
    )

    val SUMMARY_OPTIONS = listOf(
        SummaryRetentionOption(SUMMARY_MODE_COUNT, 10, "最近 10 份"),
        SummaryRetentionOption(SUMMARY_MODE_COUNT, 30, "最近 30 份"),
        SummaryRetentionOption(SUMMARY_MODE_COUNT, 50, "最近 50 份"),
        SummaryRetentionOption(SUMMARY_MODE_DAYS, 7, "最近 7 天"),
        SummaryRetentionOption(SUMMARY_MODE_DAYS, 30, "最近 30 天"),
        SummaryRetentionOption(SUMMARY_MODE_DAYS, 90, "最近 90 天"),
        SummaryRetentionOption(SUMMARY_MODE_PERMANENT, 0, "永久"),
    )

    fun normalise(days: Int): Int {
        return if (days in OPTIONS) days else DEFAULT_RETENTION_DAYS
    }

    fun label(days: Int): String {
        return if (days == PERMANENT_DAYS) "永久" else "$days 天"
    }

    fun normaliseSummaryRetention(mode: String?, value: Int): SummaryRetentionOption {
        val preset = SUMMARY_OPTIONS.firstOrNull {
            it.mode == mode && (it.mode == SUMMARY_MODE_PERMANENT || it.value == value)
        }
        if (preset != null) {
            return preset
        }
        if (value > 0 && (mode == SUMMARY_MODE_DAYS || mode == SUMMARY_MODE_COUNT)) {
            return SummaryRetentionOption(
                mode = mode,
                value = value,
                label = if (mode == SUMMARY_MODE_COUNT) {
                    "最近 $value 份"
                } else {
                    "最近 $value 天"
                },
            )
        }
        return SUMMARY_OPTIONS.first {
            it.mode == DEFAULT_SUMMARY_RETENTION_MODE &&
                it.value == DEFAULT_SUMMARY_RETENTION_VALUE
        }
    }

    fun summaryLabel(mode: String, value: Int): String {
        return normaliseSummaryRetention(mode, value).label
    }
}
