package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2ReadPathPolicyTest {
    @Test
    fun `activation requires a committed import with valid imported targets`() {
        assertTrue(
            MemoryV2ActivationPolicy.canActivate(
                MemoryV2ActivationEvidence(
                    runState = "committed",
                    importedItems = 3,
                    missingTargets = 0,
                    scopeBarriers = 0,
                ),
            ),
        )
    }

    @Test
    fun `activation is blocked for stale or incomplete migration`() {
        val invalid = listOf(
            MemoryV2ActivationEvidence("pending", 3, 0, 0),
            MemoryV2ActivationEvidence("committed", 0, 0, 0),
            MemoryV2ActivationEvidence("committed", 3, 1, 0),
            MemoryV2ActivationEvidence("committed", 3, 0, 1),
        )
        invalid.forEach {
            assertFalse(MemoryV2ActivationPolicy.canActivate(it))
        }
    }
}
