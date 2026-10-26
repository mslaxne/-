package com.local.wxbotprobe.ocr

import com.local.wxbotprobe.chat.OcrRow
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRowScalingTest {
    @Test
    fun scalesRowsFromDownsampledImageBackToSource() {
        val source = OcrRow(
            y = 100,
            xLeft = 90,
            xRight = 181,
            text = "消息",
            score = 0.97,
        )

        val scaled = scaleOcrRows(
            rows = listOf(source),
            sourceWidth = 1080,
            sourceHeight = 2400,
            targetWidth = 810,
            targetHeight = 1800,
        ).single()

        assertEquals(133, scaled.y)
        assertEquals(120, scaled.xLeft)
        assertEquals(242, scaled.xRight)
        assertEquals("消息", scaled.text)
        assertEquals(0.97, scaled.score, 0.0)
    }

    @Test
    fun returnsOriginalRowsWhenDimensionsMatch() {
        val row = OcrRow(10, 20, 30, "same", 0.9)

        val scaled = scaleOcrRows(
            rows = listOf(row),
            sourceWidth = 810,
            sourceHeight = 1800,
            targetWidth = 810,
            targetHeight = 1800,
        )

        assertEquals(listOf(row), scaled)
    }
}
