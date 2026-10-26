package com.local.wxbotprobe.memory

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object MemoryRetentionPolicy {
    fun shouldKeepEvent(
        event: MemoryEvent,
        cutoffMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!event.resolved) {
            return true
        }
        val eventDate = parseDate(event.date)
        if (eventDate != null && !eventDate.isBefore(today(nowMillis))) {
            return true
        }
        return event.lastMentionedAt >= cutoffMillis
    }

    fun today(
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): LocalDate {
        return Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    }

    private fun parseDate(value: String): LocalDate? {
        if (value.isBlank()) {
            return null
        }
        return runCatching { LocalDate.parse(value.trim()) }.getOrNull()
    }
}
