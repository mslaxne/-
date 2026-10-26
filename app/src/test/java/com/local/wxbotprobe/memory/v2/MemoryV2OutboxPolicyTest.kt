package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2OutboxPolicyTest {
    @Test
    fun `outbox backoff grows and caps`() {
        assertEquals(5_000L, MemoryV2OutboxPolicy.backoffMillis(1))
        assertEquals(10_000L, MemoryV2OutboxPolicy.backoffMillis(2))
        assertEquals(20_000L, MemoryV2OutboxPolicy.backoffMillis(3))
        assertEquals(40_000L, MemoryV2OutboxPolicy.backoffMillis(4))
        assertEquals(40_000L, MemoryV2OutboxPolicy.backoffMillis(5))
    }

    @Test
    fun `outbox exhausts at configured attempts`() {
        assertFalse(MemoryV2OutboxPolicy.exhausted(MemoryV2OutboxPolicy.MAX_ATTEMPTS - 1))
        assertTrue(MemoryV2OutboxPolicy.exhausted(MemoryV2OutboxPolicy.MAX_ATTEMPTS))
        assertTrue(MemoryV2OutboxPolicy.exhausted(MemoryV2OutboxPolicy.MAX_ATTEMPTS + 1))
    }
}
