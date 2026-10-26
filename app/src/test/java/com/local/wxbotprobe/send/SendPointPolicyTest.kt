package com.local.wxbotprobe.send

import com.local.wxbotprobe.chat.ScreenPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendPointPolicyTest {
    private val fixedSendPoint = ScreenPoint(984, 2320)
    private val targetIdentity = SendScreenIdentity(
        screenWidth = 1080,
        screenHeight = 2400,
        densityDpi = 480,
        wechatVersionCode = 3200L,
    )

    @Test
    fun `default and modest custom ime shifts allow fixed point`() {
        assertTrue(
            SendPointPolicy.canUseFixedSendPoint(
                ScreenPoint(538, 2291),
                fixedSendPoint,
            ),
        )
        assertTrue(
            SendPointPolicy.canUseFixedSendPoint(
                ScreenPoint(538, 1900),
                fixedSendPoint,
            ),
        )
    }

    @Test
    fun `panel shifted input row blocks fixed point before tap`() {
        assertFalse(
            SendPointPolicy.canUseFixedSendPoint(
                ScreenPoint(538, 1316),
                fixedSendPoint,
            ),
        )
        assertFalse(
            SendPointPolicy.canUseFixedSendPoint(
                ScreenPoint(538, 1500),
                fixedSendPoint,
            ),
        )
    }

    @Test
    fun `measured target resolves its versioned calibration profile`() {
        val profile = SendPointPolicy.resolve(targetIdentity)

        assertEquals(
            "vivo-v2055a-1080x2400-480-wechat-3200",
            profile?.profileId,
        )
        assertEquals(fixedSendPoint, profile?.sendPoint)
        assertTrue(
            SendPointPolicy.canUseFixedSendPoint(
                ScreenPoint(538, 1900),
                requireNotNull(profile),
            ),
        )
    }

    @Test
    fun `unknown device or wechat version falls back to button detection`() {
        assertNull(
            SendPointPolicy.resolve(
                targetIdentity.copy(screenWidth = 1440, screenHeight = 3200),
            ),
        )
        assertNull(
            SendPointPolicy.resolve(
                targetIdentity.copy(densityDpi = 560),
            ),
        )
        assertNull(
            SendPointPolicy.resolve(
                targetIdentity.copy(wechatVersionCode = 3300L),
            ),
        )
    }

    @Test
    fun `input hint is trusted only for an exact calibration`() {
        val profile = requireNotNull(SendPointPolicy.resolve(targetIdentity))

        assertTrue(SendPointPolicy.canUseInputHint(true, profile))
        assertFalse(SendPointPolicy.canUseInputHint(false, profile))
        assertFalse(SendPointPolicy.canUseInputHint(true, null))
    }
}
