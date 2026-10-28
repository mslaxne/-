package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryV2CandidateTierPolicyTest {
    @Test
    fun `new prompts require an explicit valid candidate tier`() {
        assertEquals(
            "A0",
            MemoryV2CandidateTierPolicy.resolve(
                explicitTier = "a0",
                promptVersion = "extract-2.2.0",
                reason = "durable_preference",
                requireRecentEvent = false,
            ),
        )
        assertNull(
            MemoryV2CandidateTierPolicy.resolve(
                explicitTier = null,
                promptVersion = "extract-2.2.0",
                reason = "durable_preference",
                requireRecentEvent = false,
            ),
        )
        assertNull(
            MemoryV2CandidateTierPolicy.resolve(
                explicitTier = "A3",
                promptVersion = "extract-2.2.0",
                reason = "durable_preference",
                requireRecentEvent = false,
            ),
        )
    }

    @Test
    fun `summary events must use A2`() {
        assertEquals(
            "A2",
            MemoryV2CandidateTierPolicy.resolve(
                explicitTier = "A2",
                promptVersion = "summary-2.2.0",
                reason = "recent_event",
                requireRecentEvent = true,
            ),
        )
        assertNull(
            MemoryV2CandidateTierPolicy.resolve(
                explicitTier = "A1",
                promptVersion = "summary-2.2.0",
                reason = "durable_preference",
                requireRecentEvent = true,
            ),
        )
    }

    @Test
    fun `legacy prompt versions retain their previous tier mapping`() {
        assertEquals(
            "A1",
            MemoryV2CandidateTierPolicy.resolve(
                explicitTier = null,
                promptVersion = "extract-2.1.2",
                reason = "durable_preference",
                requireRecentEvent = false,
            ),
        )
        assertEquals(
            "A2",
            MemoryV2CandidateTierPolicy.resolve(
                explicitTier = null,
                promptVersion = "extract-2.1.2",
                reason = "recent_event",
                requireRecentEvent = false,
            ),
        )
    }
}
