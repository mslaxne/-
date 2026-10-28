package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2ExtractionDeferralPolicyTest {
    @Test
    fun `open phase waits for a full new context window`() {
        assertTrue(
            MemoryV2ExtractionDeferralPolicy.shouldWaitForMoreSources(
                previousSourceIds = listOf("m1", "m2", "m3"),
                pendingSourceIds = listOf("m1", "m2", "m3", "m4"),
                minimumNewSources = 8,
                sessionOpen = true,
            ),
        )
    }

    @Test
    fun `enough new sources allow reevaluation`() {
        assertFalse(
            MemoryV2ExtractionDeferralPolicy.shouldWaitForMoreSources(
                previousSourceIds = listOf("m1", "m2", "m3"),
                pendingSourceIds = (1..11).map { "m$it" },
                minimumNewSources = 8,
                sessionOpen = true,
            ),
        )
    }

    @Test
    fun `closed session and completed previous coverage never defer`() {
        assertFalse(
            MemoryV2ExtractionDeferralPolicy.shouldWaitForMoreSources(
                previousSourceIds = listOf("m1", "m2"),
                pendingSourceIds = listOf("m1", "m2", "m3"),
                minimumNewSources = 8,
                sessionOpen = false,
            ),
        )
        assertFalse(
            MemoryV2ExtractionDeferralPolicy.shouldWaitForMoreSources(
                previousSourceIds = listOf("m1", "m2"),
                pendingSourceIds = listOf("m3"),
                minimumNewSources = 8,
                sessionOpen = true,
            ),
        )
    }
}
