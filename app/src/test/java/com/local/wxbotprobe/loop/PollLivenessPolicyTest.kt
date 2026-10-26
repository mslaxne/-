package com.local.wxbotprobe.loop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollLivenessPolicyTest {
    @Test
    fun `stopped agent never restarts the worker`() {
        assertFalse(
            PollLivenessPolicy.shouldRestartWorker(
                running = false,
                pollInFlight = false,
                sinceLastPollMs = 60_000L,
            ),
        )
    }

    @Test
    fun `active poll is not duplicated as stalled`() {
        assertFalse(
            PollLivenessPolicy.shouldRestartWorker(
                running = true,
                pollInFlight = true,
                sinceLastPollMs = 60_000L,
            ),
        )
    }

    @Test
    fun `idle running worker restarts at the stall boundary`() {
        assertFalse(
            PollLivenessPolicy.shouldRestartWorker(
                running = true,
                pollInFlight = false,
                sinceLastPollMs = PollLivenessPolicy.STALL_THRESHOLD_MS - 1,
            ),
        )
        assertTrue(
            PollLivenessPolicy.shouldRestartWorker(
                running = true,
                pollInFlight = false,
                sinceLastPollMs = PollLivenessPolicy.STALL_THRESHOLD_MS,
            ),
        )
    }
}
