package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMessageBodyRetentionTest {
    private val completedAt = 1_800_000_000L

    @Test
    fun `body retention defaults to one day`() {
        assertEquals(
            MemoryMessageBodyRetention.ONE_DAY,
            MemoryMessageBodyRetention.fromStorage(null),
        )
        assertEquals(
            MemoryMessageBodyRetention.ONE_DAY,
            MemoryMessageBodyRetention.fromStorage("unknown"),
        )
        assertEquals(
            completedAt + 86_400L,
            MemoryV2CleanupPolicy.bodyDueAt(
                completedAt,
                MemoryMessageBodyRetention.ONE_DAY,
            ),
        )
    }

    @Test
    fun `max body retention is seven days`() {
        assertEquals(
            MemoryMessageBodyRetention.SEVEN_DAYS,
            MemoryMessageBodyRetention.fromStorage("SEVEN_DAYS"),
        )
        assertEquals(
            completedAt + 604_800L,
            MemoryV2CleanupPolicy.bodyDueAt(
                completedAt,
                MemoryMessageBodyRetention.SEVEN_DAYS,
            ),
        )
    }

    @Test
    fun `cleanup requires both extraction and summary terminal states`() {
        assertTrue(
            MemoryV2CleanupPolicy.isFullyProcessed(
                extractionState = "done",
                summaryState = "done",
                hasActiveJob = false,
            ),
        )
        assertTrue(
            MemoryV2CleanupPolicy.isFullyProcessed(
                extractionState = "skipped",
                summaryState = "gap",
                hasActiveJob = false,
            ),
        )
        assertFalse(
            MemoryV2CleanupPolicy.isFullyProcessed(
                extractionState = "done",
                summaryState = "pending",
                hasActiveJob = false,
            ),
        )
        assertFalse(
            MemoryV2CleanupPolicy.isFullyProcessed(
                extractionState = "done",
                summaryState = "done",
                hasActiveJob = true,
            ),
        )
    }

    @Test
    fun `metadata retention never falls below one day`() {
        assertEquals(
            completedAt + 86_400L,
            MemoryV2CleanupPolicy.metadataDueAt(
                completedAt,
                historySeconds = 60L,
            ),
        )
    }
}
