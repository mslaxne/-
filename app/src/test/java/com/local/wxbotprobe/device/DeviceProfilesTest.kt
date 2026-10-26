package com.local.wxbotprobe.device

import com.local.wxbotprobe.chat.ScreenPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfilesTest {
    @Test
    fun `verified target keeps measured geometry`() {
        val profile = DeviceProfiles.resolve(1080, 2400, 480)

        assertEquals(DeviceProfileMode.VERIFIED, profile.mode)
        assertEquals(ScreenPoint(538, 2320), profile.inputAnchor)
        assertEquals(500, profile.contentTopY)
        assertEquals(800, profile.fastRegionTopY)
        assertEquals(1_600, profile.fastRegionHeight)
        assertEquals(30, profile.fieldBandMinPx)
        assertEquals(150, profile.fieldBandMaxPx)
        assertEquals(110, profile.greenButtonSearchHalfHeightPx)
        assertEquals(500, profile.maxFixedRowDriftPx)
    }

    @Test
    fun `unknown display uses bounded proportional geometry`() {
        val profile = DeviceProfiles.resolve(1440, 3200, 560)

        assertEquals(DeviceProfileMode.SCALED_FALLBACK, profile.mode)
        assertEquals(717, profile.inputAnchor.x)
        assertEquals(3093, profile.inputAnchor.y)
        assertEquals(667, profile.contentTopY)
        assertTrue(profile.fastRegionTopY > profile.contentTopY)
        assertTrue(profile.fieldBandMaxPx > profile.fieldBandMinPx)
        assertTrue(profile.greenButtonSearchHalfHeightPx > 110)
        assertTrue(profile.verticalScale > 1f)
    }

    @Test
    fun `image orientation mismatch selects a new safe profile`() {
        val portrait = DeviceProfiles.resolve(1080, 2400, 480)
        val landscape = DeviceProfiles.forImage(portrait, 2400, 1080)

        assertEquals(DeviceProfileMode.SCALED_FALLBACK, landscape.mode)
        assertEquals(2400, landscape.width)
        assertEquals(1080, landscape.height)
        assertNotEquals(portrait.profileId, landscape.profileId)
    }
}
