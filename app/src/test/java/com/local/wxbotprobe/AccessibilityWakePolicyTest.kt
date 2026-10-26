package com.local.wxbotprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityWakePolicyTest {
    private val wechat = "com.tencent.mm"

    @Test
    fun `wechat window state wakes polling`() {
        assertTrue(
            AccessibilityWakePolicy.shouldWake(
                eventPackage = wechat,
                expectedPackage = wechat,
                lastForegroundPackage = "",
                windowStateChanged = true,
                contentChanged = false,
            ),
        )
    }

    @Test
    fun `wechat content change wakes only while wechat is foreground`() {
        assertTrue(
            AccessibilityWakePolicy.shouldWake(
                eventPackage = wechat,
                expectedPackage = wechat,
                lastForegroundPackage = wechat,
                windowStateChanged = false,
                contentChanged = true,
            ),
        )
        assertFalse(
            AccessibilityWakePolicy.shouldWake(
                eventPackage = wechat,
                expectedPackage = wechat,
                lastForegroundPackage = "com.bbk.launcher2",
                windowStateChanged = false,
                contentChanged = true,
            ),
        )
    }

    @Test
    fun `other package events do not wake polling`() {
        assertFalse(
            AccessibilityWakePolicy.shouldWake(
                eventPackage = "com.android.systemui",
                expectedPackage = wechat,
                lastForegroundPackage = wechat,
                windowStateChanged = false,
                contentChanged = true,
            ),
        )
    }

    @Test
    fun `wake throttle blocks events inside the cooldown`() {
        assertTrue(
            AccessibilityWakePolicy.shouldThrottle(
                nowMillis = 1_250L,
                lastWakeMillis = 1_000L,
                minimumIntervalMillis = 1_500L,
            ),
        )
        assertFalse(
            AccessibilityWakePolicy.shouldThrottle(
                nowMillis = 2_500L,
                lastWakeMillis = 1_000L,
                minimumIntervalMillis = 1_500L,
            ),
        )
    }

    @Test
    fun `content wakes defer while a delivery is sending`() {
        assertTrue(AccessibilityWakePolicy.shouldDeferDuringSend(sendInFlight = true))
        assertFalse(AccessibilityWakePolicy.shouldDeferDuringSend(sendInFlight = false))
    }
}
