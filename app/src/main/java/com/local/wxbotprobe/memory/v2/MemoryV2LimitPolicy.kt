package com.local.wxbotprobe.memory.v2

internal object MemoryV2LimitPolicy {
    fun sqliteLimit(limit: Int): Int {
        require(limit >= 0) { "limit must not be negative" }
        return limit.takeIf { it > 0 } ?: -1
    }

    fun <T> takeWithinLimit(items: List<T>, limit: Int): List<T> {
        require(limit >= 0) { "limit must not be negative" }
        return if (limit > 0) items.take(limit) else items
    }

    fun reachedFlushThreshold(size: Int, limit: Int): Boolean {
        return limit > 0 && size >= limit
    }
}
