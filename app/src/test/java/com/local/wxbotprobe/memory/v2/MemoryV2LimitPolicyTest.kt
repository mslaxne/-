package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2LimitPolicyTest {
    @Test
    fun `zero means all rows and no SQLite limit`() {
        assertEquals(-1, MemoryV2LimitPolicy.sqliteLimit(0))
        assertEquals(
            listOf("a", "b", "c"),
            MemoryV2LimitPolicy.takeWithinLimit(listOf("a", "b", "c"), 0),
        )
        assertFalse(MemoryV2LimitPolicy.reachedFlushThreshold(100, 0))
    }

    @Test
    fun `positive limits retain their existing behavior`() {
        assertEquals(2, MemoryV2LimitPolicy.sqliteLimit(2))
        assertEquals(
            listOf("a", "b"),
            MemoryV2LimitPolicy.takeWithinLimit(listOf("a", "b", "c"), 2),
        )
        assertTrue(MemoryV2LimitPolicy.reachedFlushThreshold(2, 2))
    }
}
