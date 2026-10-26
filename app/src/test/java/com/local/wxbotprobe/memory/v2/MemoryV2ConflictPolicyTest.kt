package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2ConflictPolicyTest {
    @Test
    fun `A0 proposal requires explicit confirmation`() {
        assertFalse(
            MemoryV2ConflictPolicy.canAcceptCandidate(
                existingTier = "A0",
                candidateTier = "A0",
                a0Confirmed = false,
            ),
        )
        assertTrue(
            MemoryV2ConflictPolicy.canAcceptCandidate(
                existingTier = "A0",
                candidateTier = "A0",
                a0Confirmed = true,
            ),
        )
    }

    @Test
    fun `A0 confirmation cannot downgrade the candidate tier`() {
        assertFalse(
            MemoryV2ConflictPolicy.canAcceptCandidate(
                existingTier = "A0",
                candidateTier = "A1",
                a0Confirmed = true,
            ),
        )
    }

    @Test
    fun `A1 conflict does not require A0 confirmation`() {
        assertTrue(
            MemoryV2ConflictPolicy.canAcceptCandidate(
                existingTier = "A1",
                candidateTier = "A1",
                a0Confirmed = false,
            ),
        )
    }
}
