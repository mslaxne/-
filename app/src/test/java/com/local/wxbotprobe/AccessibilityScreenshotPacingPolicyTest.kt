package com.local.wxbotprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityScreenshotPacingPolicyTest {
    @Test
    fun `base interval matches the validated device pacing`() {
        assertEquals(
            1_500L,
            AccessibilityScreenshotPacingPolicy.BASE_INTERVAL_MS,
        )
    }

    @Test
    fun `rate limit raises the base interval`() {
        assertEquals(
            AccessibilityScreenshotPacingPolicy.RATE_LIMITED_INTERVAL_MS,
            AccessibilityScreenshotPacingPolicy.afterRateLimit(
                AccessibilityScreenshotPacingPolicy.BASE_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `rate limit never lowers a slower interval`() {
        assertEquals(
            5_000L,
            AccessibilityScreenshotPacingPolicy.afterRateLimit(5_000L),
        )
    }
}
