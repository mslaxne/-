package com.local.wxbotprobe.chat

class BurstCollector(
    private val waitMillis: Long = 4_000L,
    private val maxAgeMillis: Long = 12_000L,
) {
    private val pending = mutableListOf<ScreenItem>()
    private var dueAt: Long? = null
    private var firstAddedAt: Long? = null

    val size: Int
        get() = pending.size

    val readyAt: Long?
        get() {
            val quietDue = dueAt ?: return null
            val ageDue = firstAddedAt?.plus(maxAgeMillis)
            return if (ageDue != null) minOf(quietDue, ageDue) else quietDue
        }

    fun add(items: List<ScreenItem>, nowMillis: Long): Int {
        if (items.isEmpty()) {
            return pending.size
        }
        if (pending.isEmpty()) {
            firstAddedAt = nowMillis
        }
        pending += items
        dueAt = nowMillis + waitMillis
        return pending.size
    }

    fun isReady(nowMillis: Long): Boolean {
        val due = readyAt ?: return false
        return pending.isNotEmpty() && nowMillis >= due
    }

    fun drain(): List<ScreenItem> {
        if (pending.isEmpty()) {
            return emptyList()
        }
        val result = pending.toList()
        pending.clear()
        dueAt = null
        firstAddedAt = null
        return result
    }

    fun clear() {
        pending.clear()
        dueAt = null
        firstAddedAt = null
    }
}
