package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryHourlyBudgetTierTest {
    @Test
    fun `presets expose expected limits`() {
        assertEquals(6, MemoryHourlyBudgetTier.CONSERVATIVE.scopeLimit)
        assertEquals(30, MemoryHourlyBudgetTier.CONSERVATIVE.deviceLimit)
        assertEquals(12, MemoryHourlyBudgetTier.STANDARD.scopeLimit)
        assertEquals(60, MemoryHourlyBudgetTier.STANDARD.deviceLimit)
        assertEquals(30, MemoryHourlyBudgetTier.HIGH.scopeLimit)
        assertEquals(120, MemoryHourlyBudgetTier.HIGH.deviceLimit)
        assertNull(MemoryHourlyBudgetTier.UNLIMITED.scopeLimit)
        assertNull(MemoryHourlyBudgetTier.UNLIMITED.deviceLimit)
    }

    @Test
    fun `unknown stored tier falls back to standard`() {
        assertEquals(
            MemoryHourlyBudgetTier.STANDARD,
            MemoryHourlyBudgetTier.fromStorage(null),
        )
        assertEquals(
            MemoryHourlyBudgetTier.STANDARD,
            MemoryHourlyBudgetTier.fromStorage("legacy-value"),
        )
        assertEquals(
            MemoryHourlyBudgetTier.HIGH,
            MemoryHourlyBudgetTier.fromStorage("HIGH"),
        )
    }
}
