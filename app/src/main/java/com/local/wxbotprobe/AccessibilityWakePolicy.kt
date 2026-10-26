package com.local.wxbotprobe

object AccessibilityWakePolicy {
    fun shouldWake(
        eventPackage: String,
        expectedPackage: String,
        lastForegroundPackage: String,
        windowStateChanged: Boolean,
        contentChanged: Boolean,
    ): Boolean {
        if (eventPackage != expectedPackage) {
            return false
        }
        if (windowStateChanged) {
            return true
        }
        return contentChanged && lastForegroundPackage == expectedPackage
    }

    fun shouldThrottle(
        nowMillis: Long,
        lastWakeMillis: Long,
        minimumIntervalMillis: Long,
    ): Boolean {
        return lastWakeMillis > 0L &&
            nowMillis - lastWakeMillis < minimumIntervalMillis
    }

    fun shouldDeferDuringSend(sendInFlight: Boolean): Boolean {
        return sendInFlight
    }
}
