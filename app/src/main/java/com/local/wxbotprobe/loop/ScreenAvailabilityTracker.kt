package com.local.wxbotprobe.loop

enum class ScreenAvailabilityChange {
    PAUSED,
    RESUMED,
}

class ScreenAvailabilityTracker(
    initiallyAvailable: Boolean = true,
) {
    private var available = initiallyAvailable

    fun update(isAvailable: Boolean): ScreenAvailabilityChange? {
        if (isAvailable == available) return null
        available = isAvailable
        return if (isAvailable) {
            ScreenAvailabilityChange.RESUMED
        } else {
            ScreenAvailabilityChange.PAUSED
        }
    }
}
