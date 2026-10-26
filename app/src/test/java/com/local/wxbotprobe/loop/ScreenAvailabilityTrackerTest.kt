package com.local.wxbotprobe.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenAvailabilityTrackerTest {
    @Test
    fun unchangedAvailabilityDoesNotEmitDuplicateEvents() {
        val tracker = ScreenAvailabilityTracker()

        assertNull(tracker.update(true))
        assertEquals(ScreenAvailabilityChange.PAUSED, tracker.update(false))
        assertNull(tracker.update(false))
        assertEquals(ScreenAvailabilityChange.RESUMED, tracker.update(true))
        assertNull(tracker.update(true))
    }

    @Test
    fun initializedUnavailableDeviceEmitsPauseWhenObserved() {
        val tracker = ScreenAvailabilityTracker(initiallyAvailable = false)

        assertNull(tracker.update(false))
        assertEquals(ScreenAvailabilityChange.RESUMED, tracker.update(true))
    }
}
