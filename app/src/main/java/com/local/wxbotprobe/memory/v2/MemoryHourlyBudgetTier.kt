package com.local.wxbotprobe.memory.v2

import android.content.Context

enum class MemoryHourlyBudgetTier(
    val label: String,
    val scopeLimit: Int?,
    val deviceLimit: Int?,
) {
    CONSERVATIVE("节省", 6, 30),
    STANDARD("标准", 12, 60),
    HIGH("高频", 30, 120),
    UNLIMITED("不限小时", null, null),
    ;

    companion object {
        fun fromStorage(value: String?): MemoryHourlyBudgetTier {
            return entries.firstOrNull { it.name == value } ?: STANDARD
        }
    }
}

object MemoryBudgetSettings {
    private const val PREFS = "memory_budget_settings"
    private const val KEY_HOURLY_TIER = "hourly_tier"

    fun hourlyTier(context: Context): MemoryHourlyBudgetTier {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOURLY_TIER, null)
        return MemoryHourlyBudgetTier.fromStorage(stored)
    }

    fun setHourlyTier(
        context: Context,
        tier: MemoryHourlyBudgetTier,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOURLY_TIER, tier.name)
            .apply()
    }
}
