package com.local.wxbotprobe.chat

import com.local.wxbotprobe.device.DeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPixelRulesTest {
    private fun source(
        width: Int,
        height: Int,
        color: (Int, Int) -> PixelColor,
    ): PixelSource {
        return object : PixelSource {
            override val width: Int = width
            override val height: Int = height

            override fun colorAt(x: Int, y: Int): PixelColor = color(x, y)
        }
    }

    @Test
    fun `black ink is a message and grey ink is not`() {
        val pixels = source(400, 600) { _, _ -> PixelColor(255, 255, 255) }
        val black = OcrRow(100, 60, 260, "消息", 1.0)
        val grey = OcrRow(200, 60, 260, "昵称", 1.0)
        val mixed = object : PixelSource by pixels {
            override fun colorAt(x: Int, y: Int): PixelColor {
                return when {
                    y in 94..134 -> PixelColor(25, 25, 25)
                    y in 194..234 -> PixelColor(140, 140, 140)
                    else -> PixelColor(255, 255, 255)
                }
            }
        }
        assertTrue(ScreenPixelRules.inkIsMessage(mixed, black))
        assertFalse(ScreenPixelRules.inkIsMessage(mixed, grey))
    }

    @Test
    fun `green send button is found near the input row`() {
        val pixels = source(1080, 2400) { x, y ->
            if (x in 950..1010 && y in 2240..2290) {
                PixelColor(7, 193, 96)
            } else {
                PixelColor(237, 237, 237)
            }
        }
        assertEquals(2265, ScreenPixelRules.sendButtonY(pixels))
    }

    @Test
    fun `green send button center is detected near anchor`() {
        val pixels = source(1080, 2400) { x, y ->
            if (x in 950..1010 && y in 2240..2290) {
                PixelColor(7, 193, 96)
            } else {
                PixelColor(237, 237, 237)
            }
        }
        val point = ScreenPixelRules.findGreenButton(pixels, 2270)
        assertTrue(point != null)
        assertTrue(point!!.x in 950..1010)
        assertTrue(point.y in 2240..2290)
    }

    @Test
    fun `green send button follows custom ime raised input row`() {
        val pixels = source(1080, 2400) { x, y ->
            if (x in 950..1010 && y in 1935..1985) {
                PixelColor(7, 193, 96)
            } else {
                PixelColor(237, 237, 237)
            }
        }
        val point = ScreenPixelRules.findGreenButton(pixels, 1966)
        assertTrue(point != null)
        assertTrue(point!!.y in 1935..1985)
        assertNull(ScreenPixelRules.findGreenButton(pixels, 2270))
    }

    @Test
    fun `white bubble is incoming and green bubble is outgoing`() {
        val whitePixels = source(1080, 2400) { _, _ -> PixelColor(255, 255, 255) }
        val greenPixels = source(1080, 2400) { _, _ -> PixelColor(149, 236, 105) }
        val block = BubbleBlock(listOf(OcrRow(400, 200, 700, "消息", 1.0)))
        assertEquals(BubbleKind.IN, ScreenPixelRules.bubbleKind(whitePixels, block))
        assertEquals(BubbleKind.OUT, ScreenPixelRules.bubbleKind(greenPixels, block))
    }

    @Test
    fun `input field is located inside the bright widest band`() {
        val pixels = source(1080, 2400) { _, y ->
            if (y in 2200..2320) {
                PixelColor(255, 255, 255)
            } else {
                PixelColor(237, 237, 237)
            }
        }
        val field = ScreenPixelRules.locateField(pixels, 2320)
        assertEquals(540, field?.x)
        assertTrue(field != null && field.y in 2200..2320)
    }

    @Test
    fun `blank screen has no input field`() {
        val pixels = source(1080, 2400) { _, _ -> PixelColor(237, 237, 237) }
        assertNull(ScreenPixelRules.locateField(pixels, 2320))
    }

    @Test
    fun `custom ime can move input field more than 250 pixels`() {
        val pixels = source(1080, 2400) { _, y ->
            if (y in 1900..2020) {
                PixelColor(255, 255, 255)
            } else {
                PixelColor(237, 237, 237)
            }
        }
        val field = ScreenPixelRules.locateField(pixels, 2292)
        assertTrue(field != null)
        assertTrue(field!!.y in 1900..2020)
    }

    @Test
    fun `input above emoji panel is found outside the old anchor window`() {
        val pixels = source(1080, 2400) { _, y ->
            if (y in 1200..1305 || y in 1350..1550) {
                PixelColor(255, 255, 255)
            } else {
                PixelColor(237, 237, 237)
            }
        }

        val field = ScreenPixelRules.locateField(pixels, 2320)

        assertTrue(field != null)
        assertTrue(field!!.y in 1200..1305)
    }

    @Test
    fun `wide white bubble does not win over the input row`() {
        val pixels = source(1080, 2400) { x, y ->
            if (y in 1500..1590 && x in 150..850) {
                PixelColor(255, 255, 255)
            } else if (y in 2200..2320) {
                PixelColor(255, 255, 255)
            } else {
                PixelColor(237, 237, 237)
            }
        }

        val field = ScreenPixelRules.locateField(pixels, 2320)

        assertTrue(field != null)
        assertTrue(field!!.y in 2200..2320)
    }

    @Test
    fun `large chat bubble does not win over bottom input row`() {
        val pixels = source(1080, 2400) { x, y ->
            if (y in 1900..2080 && x in 150..850) {
                PixelColor(255, 255, 255)
            } else if (y in 2200..2320 && x in 120..960) {
                PixelColor(255, 255, 255)
            } else {
                PixelColor(237, 237, 237)
            }
        }

        val field = ScreenPixelRules.locateField(pixels, 2320)

        assertTrue(field != null)
        assertTrue(field!!.y in 2200..2320)
    }

    @Test
    fun `near white chat background does not merge with input row`() {
        val pixels = source(1080, 2376) { x, y ->
            when {
                y in 2197..2304 && x in 120..770 ->
                    PixelColor(255, 255, 255)
                y in 400..480 && x in 150..900 ->
                    PixelColor(255, 255, 255)
                else ->
                    PixelColor(247, 247, 247)
            }
        }

        val profile = com.local.wxbotprobe.device.DeviceProfiles.resolve(
            1080,
            2376,
            480,
        )
        val field = ScreenPixelRules.locateField(pixels, 2297, profile)

        assertTrue(field != null)
        assertTrue(field!!.y in 2197..2304)
    }

    @Test
    fun `noisy bubble is treated as an image`() {
        val pixels = source(400, 400) { x, y ->
            if (x in 90..310 && y in 90..150) {
                PixelColor(90 + x % 100, 90 + y % 100, 140)
            } else {
                PixelColor(255, 255, 255)
            }
        }
        val block = BubbleBlock(listOf(OcrRow(100, 100, 300, "图", 1.0)))
        assertTrue(ScreenPixelRules.looksLikeImage(pixels, block))
    }

    @Test
    fun `scaled profile detects input row and green button on larger display`() {
        val profile = DeviceProfiles.resolve(1440, 3200, 560)
        val pixels = source(1440, 3200) { x, y ->
            when {
                y in 3000..3060 && x in 180..1160 -> PixelColor(255, 255, 255)
                y in 3000..3060 && x in 1260..1360 ->
                    PixelColor(7, 193, 96)
                else -> PixelColor(237, 237, 237)
            }
        }

        val field = ScreenPixelRules.locateField(pixels, profile.inputAnchor.y, profile)
        val button = ScreenPixelRules.findGreenButton(
            pixels,
            field?.y ?: profile.inputAnchor.y,
            profile,
        )

        assertTrue(field != null)
        assertTrue(button != null)
        assertTrue(button!!.y in 3000..3060)
    }
}
