package com.local.wxbotprobe.memory

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetentionPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDate.of(2026, 10, 19)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
    private val cutoff = now - 7 * 86_400_000L

    private fun event(
        date: String,
        status: String = "pending",
        resolved: Boolean = false,
        lastMentionedAt: Long = cutoff - 1,
    ): MemoryEvent {
        return MemoryEvent(
            id = "event",
            type = "todo",
            text = "事件",
            date = date,
            status = status,
            importance = 0.8,
            resolved = resolved,
            lastMentionedAt = lastMentionedAt,
            sourceSessionId = "session",
            sourceMessageIds = emptyList(),
        )
    }

    @Test
    fun `unfinished event survives retention even when old`() {
        assertTrue(
            MemoryRetentionPolicy.shouldKeepEvent(
                event("", resolved = false),
                cutoff,
                now,
            ),
        )
    }

    @Test
    fun `future event survives retention even when resolved`() {
        assertTrue(
            MemoryRetentionPolicy.shouldKeepEvent(
                event("2026-10-24", status = "cancelled", resolved = true),
                cutoff,
                now,
            ),
        )
    }

    @Test
    fun `resolved old event follows the normal cutoff`() {
        assertFalse(
            MemoryRetentionPolicy.shouldKeepEvent(
                event("2026-10-01", status = "done", resolved = true),
                cutoff,
                now,
            ),
        )
        assertTrue(
            MemoryRetentionPolicy.shouldKeepEvent(
                event("2026-10-01", status = "done", resolved = true, lastMentionedAt = now),
                cutoff,
                now,
            ),
        )
    }
}
