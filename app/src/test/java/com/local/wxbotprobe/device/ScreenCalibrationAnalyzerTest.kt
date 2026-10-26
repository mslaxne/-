package com.local.wxbotprobe.device

import com.local.wxbotprobe.chat.PixelColor
import com.local.wxbotprobe.chat.PixelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCalibrationAnalyzerTest {
    private val identity = DeviceIdentity(
        width = 1080,
        height = 2376,
        densityDpi = 480,
        orientation = 0,
        wechatVersionCode = 3080L,
        fontScale = 1f,
    )

    private fun screen(withButton: Boolean): PixelSource {
        return object : PixelSource {
            override val width: Int = 1080
            override val height: Int = 2376

            override fun colorAt(x: Int, y: Int): PixelColor {
                return when {
                    y in 2197..2304 && x in 120..770 ->
                        PixelColor(255, 255, 255)
                    withButton && y in 2208..2294 && x in 917..1051 ->
                        PixelColor(7, 193, 96)
                    y in 300..500 && x in 150..900 ->
                        PixelColor(255, 255, 255)
                    else ->
                        PixelColor(247, 247, 247)
                }
            }
        }
    }

    @Test
    fun `calibration extracts input and send positions`() {
        val result = ScreenCalibrationAnalyzer.analyze(
            screen(withButton = true),
            identity,
            verifiedAt = 123L,
        )

        assertTrue(result.valid)
        assertNotNull(result.calibration)
        val calibration = requireNotNull(result.calibration)
        assertTrue(calibration.inputAnchor.y in 2197..2304)
        assertTrue(calibration.sendPoint.x in 917..1051)
        assertTrue(calibration.sendPoint.y in 2208..2294)
        assertEquals(DeviceProfileMode.VERIFIED, calibration.toDeviceProfile().mode)
    }

    @Test
    fun `calibration rejects missing send button`() {
        val result = ScreenCalibrationAnalyzer.analyze(
            screen(withButton = false),
            identity,
            verifiedAt = 123L,
        )

        assertFalse(result.valid)
        assertTrue(result.message.contains("绿色发送按钮"))
    }

    @Test
    fun `calibration rejects screenshot from another display`() {
        val result = ScreenCalibrationAnalyzer.analyze(
            screen(withButton = true),
            identity.copy(width = 1440),
            verifiedAt = 123L,
        )

        assertFalse(result.valid)
        assertTrue(result.message.contains("尺寸"))
    }

    @Test
    fun `calibration json round trip preserves identity and points`() {
        val result = ScreenCalibrationAnalyzer.analyze(
            screen(withButton = true),
            identity,
            verifiedAt = 123L,
        )
        val restored = StoredDeviceCalibration.fromJson(
            requireNotNull(result.calibration).toJson(),
        )

        assertEquals(result.calibration, restored)
        assertTrue(requireNotNull(restored).identity.matches(identity))
    }
}
