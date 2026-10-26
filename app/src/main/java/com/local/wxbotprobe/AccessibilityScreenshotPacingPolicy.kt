package com.local.wxbotprobe

object AccessibilityScreenshotPacingPolicy {
    const val BASE_INTERVAL_MS = 1_500L
    const val RATE_LIMITED_INTERVAL_MS = 3_500L

    fun afterRateLimit(currentIntervalMs: Long): Long {
        return maxOf(currentIntervalMs, RATE_LIMITED_INTERVAL_MS)
    }
}
