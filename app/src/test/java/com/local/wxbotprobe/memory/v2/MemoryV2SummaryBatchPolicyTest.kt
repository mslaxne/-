package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryV2SummaryBatchPolicyTest {
    @Test
    fun `first summary uses the configured maximum`() {
        assertEquals(
            32,
            MemoryV2SummaryBatchPolicy.nextBatchLimit(null, 32),
        )
    }

    @Test
    fun `zero maximum asks the caller to use all available sources`() {
        assertEquals(
            0,
            MemoryV2SummaryBatchPolicy.nextBatchLimit(null, 0),
        )
        assertEquals(
            32,
            MemoryV2SummaryBatchPolicy.nextBatchLimit(
                MemorySummaryJobSnapshot(
                    "failed",
                    MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED.name,
                    64,
                ),
                0,
            ),
        )
    }

    @Test
    fun `saved summary keeps its proven batch size`() {
        assertEquals(
            4,
            MemoryV2SummaryBatchPolicy.nextBatchLimit(
                MemorySummaryJobSnapshot("saved", null, 4),
                32,
            ),
        )
    }

    @Test
    fun `context overflow halves the batch until one source`() {
        assertEquals(
            16,
            MemoryV2SummaryBatchPolicy.nextBatchLimit(
                MemorySummaryJobSnapshot(
                    "failed",
                    MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED.name,
                    32,
                ),
                32,
            ),
        )
        assertEquals(
            1,
            MemoryV2SummaryBatchPolicy.nextBatchLimit(
                MemorySummaryJobSnapshot(
                    "cancelled",
                    MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED.name,
                    1,
                ),
                32,
            ),
        )
    }

    @Test
    fun `non budget failures do not auto retry`() {
        assertNull(
            MemoryV2SummaryBatchPolicy.nextBatchLimit(
                MemorySummaryJobSnapshot(
                    "failed",
                    MemoryV2ErrorCode.SCHEMA_INVALID.name,
                    8,
                ),
                32,
            ),
        )
    }

    @Test
    fun `migration restart uses the configured maximum`() {
        assertEquals(
            32,
            MemoryV2SummaryBatchPolicy.nextBatchLimit(
                MemorySummaryJobSnapshot(
                    "cancelled",
                    MemoryV2SummaryBatchPolicy.MIGRATION_ERROR,
                    32,
                ),
                32,
            ),
        )
    }

    @Test
    fun `stale cancellation restarts from the current working version`() {
        assertEquals(
            32,
            MemoryV2SummaryBatchPolicy.nextBatchLimit(
                MemorySummaryJobSnapshot(
                    "cancelled",
                    MemoryV2ErrorCode.STALE_SUMMARY.name,
                    4,
                ),
                32,
            ),
        )
        assertEquals(
            32,
            MemoryV2SummaryBatchPolicy.nextBatchLimit(
                MemorySummaryJobSnapshot(
                    "cancelled",
                    MemoryV2ErrorCode.STALE_EPOCH.name,
                    4,
                ),
                32,
            ),
        )
    }
}
