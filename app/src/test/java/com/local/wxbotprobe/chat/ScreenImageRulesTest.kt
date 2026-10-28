package com.local.wxbotprobe.chat

import com.local.wxbotprobe.device.DeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenImageRulesTest {
    private val profile = DeviceProfiles.resolve(1080, 2400, 480)

    private fun source(
        color: (Int, Int) -> PixelColor,
    ): PixelSource {
        return object : PixelSource {
            override val width: Int = 1080
            override val height: Int = 2400

            override fun colorAt(x: Int, y: Int): PixelColor = color(x, y)
        }
    }

    private fun photo(x: Int, y: Int): PixelColor {
        return PixelColor(
            red = 50 + (x * 3 + y) % 190,
            green = 40 + (x + y * 2) % 190,
            blue = 60 + (x * 2 + y * 3) % 180,
        )
    }

    @Test
    fun `pure incoming image is detected without ocr`() {
        val pixels = source { x, y ->
            if (x in 210..780 && y in 900..1420) {
                photo(x, y)
            } else {
                PixelColor(247, 247, 247)
            }
        }

        val regions = ScreenImageRules.detect(pixels, profile, sendButtonY = null)

        assertEquals(1, regions.size)
        assertEquals(BubbleKind.IN, regions.single().kind)
        assertTrue(regions.single().left in 190..220)
        assertTrue(regions.single().top in 880..910)
        assertTrue(regions.single().right in 770..800)
        assertTrue(regions.single().bottom in 1410..1440)
    }

    @Test
    fun `pure outgoing image is classified on the right`() {
        val pixels = source { x, y ->
            if (x in 430..940 && y in 1000..1460) {
                photo(x, y)
            } else {
                PixelColor(247, 247, 247)
            }
        }

        val region = ScreenImageRules.detect(
            pixels,
            profile,
            sendButtonY = null,
        ).single()

        assertEquals(BubbleKind.OUT, region.kind)
    }

    @Test
    fun `text lines and avatars are not images`() {
        val pixels = source { x, y ->
            when {
                x in 50..135 && y in 760..845 ->
                    PixelColor(80, 130, 180)
                y in 1000..1028 && x in 210..760 ->
                    PixelColor(25, 25, 25)
                y in 1080..1108 && x in 210..640 ->
                    PixelColor(25, 25, 25)
                else ->
                    PixelColor(247, 247, 247)
            }
        }

        assertTrue(
            ScreenImageRules.detect(pixels, profile, sendButtonY = null).isEmpty(),
        )
    }

    @Test
    fun `white bubble with dark text is not an image`() {
        val pixels = source { x, y ->
            when {
                x in 170..820 && y in 900..1200 ->
                    PixelColor(255, 255, 255)
                x in 220..720 && y in 1010..1038 ->
                    PixelColor(30, 30, 30)
                else ->
                    PixelColor(237, 237, 237)
            }
        }

        assertTrue(
            ScreenImageRules.detect(pixels, profile, sendButtonY = null).isEmpty(),
        )
    }

    @Test
    fun `green outgoing bubble is rejected even with noisy text edges`() {
        val pixels = source { x, y ->
            when {
                x in 190..830 && y in 1_000..1_360 -> {
                    if ((x / 8 + y / 8) % 11 == 0) {
                        PixelColor(210, 180, 120)
                    } else {
                        PixelColor(149, 236, 105)
                    }
                }
                else -> PixelColor(247, 247, 247)
            }
        }

        assertTrue(
            ScreenImageRules.detect(
                pixels,
                profile,
                sendButtonY = null,
            ).isEmpty(),
        )
    }
}
