package com.local.wxbotprobe.ocr

import com.local.wxbotprobe.device.DeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotRegionsTest {
    @Test
    fun `target display uses the bottom message region`() {
        assertEquals(800, SnapshotRegions.messageTop(2400))
    }

    @Test
    fun `small display keeps the message region in bounds`() {
        assertEquals(0, SnapshotRegions.messageTop(100))
    }

    @Test
    fun `scaled display derives the fast region from its profile`() {
        val profile = DeviceProfiles.resolve(1440, 3200, 560)

        assertEquals(profile.fastRegionTopY, SnapshotRegions.messageTop(3200, profile))
        assertEquals(1067, profile.fastRegionTopY)
    }
}
