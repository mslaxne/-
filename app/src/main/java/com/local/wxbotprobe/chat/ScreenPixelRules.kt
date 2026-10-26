package com.local.wxbotprobe.chat

import com.local.wxbotprobe.device.DeviceProfile
import com.local.wxbotprobe.device.DeviceProfiles
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ScreenPoint(
    val x: Int,
    val y: Int,
)

object ScreenPixelRules {
    private const val WECHAT_GREEN_RED = 7
    private const val WECHAT_GREEN_GREEN = 193
    private const val WECHAT_GREEN_BLUE = 96
    private const val INK_DARKEST_MAX = 100
    private const val FIELD_MIN_BRIGHT = 250
    private const val FIELD_RUN_SHARE = 0.30
    private const val FIELD_SCAN_TOP = 0.09
    private const val FIELD_BOTTOM_PREFERENCE = 0.72
    private const val FIELD_WIDTH_TOLERANCE = 0.80

    fun sendButtonY(pixels: PixelSource): Int? {
        return sendButtonY(pixels, defaultProfile(pixels))
    }

    fun sendButtonY(
        pixels: PixelSource,
        profile: DeviceProfile,
    ): Int? {
        var minY = pixels.height
        var maxY = -1
        var count = 0
        for (y in (pixels.height * 0.35).toInt() until pixels.height step 2) {
            for (x in (pixels.width * 0.6).toInt() until pixels.width step 2) {
                val color = pixels.colorAt(x, y)
                if (
                    abs(color.red - WECHAT_GREEN_RED) < 45 &&
                    abs(color.green - WECHAT_GREEN_GREEN) < 45 &&
                    abs(color.blue - WECHAT_GREEN_BLUE) < 45
                ) {
                    count++
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                }
            }
        }
        val minimumCount = maxOf(
            8,
            (30 * profile.verticalScale * profile.verticalScale).roundToInt(),
        )
        return if (count < minimumCount) null else (minY + maxY) / 2
    }

    fun findGreenButton(
        pixels: PixelSource,
        anchorY: Int,
    ): ScreenPoint? {
        return findGreenButton(pixels, anchorY, defaultProfile(pixels))
    }

    fun findGreenButton(
        pixels: PixelSource,
        anchorY: Int,
        profile: DeviceProfile,
    ): ScreenPoint? {
        val minY = max(0, anchorY - profile.greenButtonSearchHalfHeightPx)
        val maxY = min(pixels.height, anchorY + profile.greenButtonSearchHalfHeightPx)
        val minX = (pixels.width * 0.7).toInt()
        var left = pixels.width
        var top = pixels.height
        var right = -1
        var bottom = -1
        var count = 0
        for (y in minY until maxY) {
            for (x in minX until pixels.width) {
                val color = pixels.colorAt(x, y)
                if (
                    abs(color.red - WECHAT_GREEN_RED) < 45 &&
                    abs(color.green - WECHAT_GREEN_GREEN) < 45 &&
                    abs(color.blue - WECHAT_GREEN_BLUE) < 45
                ) {
                    count++
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
            }
        }
        if (count < 40 || right < 0 || bottom < 0) {
            return null
        }
        return ScreenPoint((left + right) / 2, (top + bottom) / 2)
    }

    fun inkIsMessage(
        pixels: PixelSource,
        row: OcrRow,
    ): Boolean {
        return inkIsMessage(pixels, row, defaultProfile(pixels))
    }

    fun inkIsMessage(
        pixels: PixelSource,
        row: OcrRow,
        profile: DeviceProfile,
    ): Boolean {
        val startOffset = (6 * profile.verticalScale).roundToInt()
        val endOffset = (34 * profile.verticalScale).roundToInt()
        for (y in max(0, row.y - startOffset) until min(
            pixels.height,
            row.y + endOffset,
        ) step 2) {
            for (x in max(0, row.xLeft) until min(pixels.width, row.xRight) step 2) {
                if (pixels.colorAt(x, y).darkest <= INK_DARKEST_MAX) {
                    return true
                }
            }
        }
        return false
    }

    fun bubbleKind(
        pixels: PixelSource,
        block: BubbleBlock,
    ): BubbleKind {
        var green = 0
        var white = 0
        for (row in block.rows) {
            if (row.y !in 0 until pixels.height) {
                continue
            }
            for (x in intArrayOf(
                row.xLeft - 16,
                row.xLeft - 8,
                row.xRight + 8,
                row.xRight + 16,
            )) {
                val color = pixels.colorAt(x.coerceIn(0, pixels.width - 1), row.y)
                if (color.green > 200 && color.red < 200 && color.blue < 160) {
                    green++
                }
                if (color.red > 250 && color.green > 250 && color.blue > 250) {
                    white++
                }
            }
        }
        return when {
            green > 0 -> BubbleKind.OUT
            white > 0 -> BubbleKind.IN
            else -> BubbleKind.UNKNOWN
        }
    }

    fun bubbleBox(
        block: BubbleBlock,
        width: Int,
        height: Int,
    ): IntArray {
        return bubbleBox(
            block,
            width,
            height,
            DeviceProfiles.resolve(width, height, 480),
        )
    }

    fun bubbleBox(
        block: BubbleBlock,
        width: Int,
        height: Int,
        profile: DeviceProfile,
    ): IntArray {
        val horizontalPadding = (20 * profile.verticalScale).roundToInt()
        val topPadding = (20 * profile.verticalScale).roundToInt()
        val bottomPadding = (40 * profile.verticalScale).roundToInt()
        return intArrayOf(
            max(0, block.xLeft - horizontalPadding),
            max(0, block.rows.first().y - topPadding),
            min(width - 1, block.xRight + horizontalPadding),
            min(height, block.rows.last().y + bottomPadding),
        )
    }

    fun looksLikeImage(
        pixels: PixelSource,
        block: BubbleBlock,
    ): Boolean {
        return looksLikeImage(pixels, block, defaultProfile(pixels))
    }

    fun looksLikeImage(
        pixels: PixelSource,
        block: BubbleBlock,
        profile: DeviceProfile,
    ): Boolean {
        val box = bubbleBox(block, pixels.width, pixels.height, profile)
        var samples = 0
        var flat = 0
        for (y in box[1] until box[3] step 4) {
            for (x in box[0] until box[2] step 8) {
                val color = pixels.colorAt(x, y)
                samples++
                val isFlat = color.darkest > 200 ||
                    color.brightest < 120 ||
                    (color.green > 180 && color.red < 200 && color.blue < 170)
                if (isFlat) {
                    flat++
                }
            }
        }
        return samples >= 40 && flat.toDouble() / samples < 0.6
    }

    fun locateField(
        pixels: PixelSource,
        anchorY: Int,
    ): ScreenPoint? {
        return locateField(pixels, anchorY, defaultProfile(pixels))
    }

    fun locateField(
        pixels: PixelSource,
        anchorY: Int,
        profile: DeviceProfile,
    ): ScreenPoint? {
        // The input row moves above the old anchor when WeChat opens a panel.
        val top = (pixels.height * FIELD_SCAN_TOP).toInt()
        val bottom = pixels.height
        val bands = mutableListOf<MutableList<IntArray>>()
        var current = mutableListOf<IntArray>()

        for (y in top until bottom) {
            val run = longestBrightRun(
                pixels,
                y,
                maxGap = (6 * profile.verticalScale).roundToInt().coerceAtLeast(1),
            )
            if (run[0] >= pixels.width * FIELD_RUN_SHARE) {
                current += intArrayOf(y, run[0], run[1])
            } else if (current.isNotEmpty()) {
                bands += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) {
            bands += current
        }

        val usable = bands.filter {
            it.size in profile.fieldBandMinPx..profile.fieldBandMaxPx
        }
        if (usable.isEmpty()) {
            return null
        }
        val bottomBands = usable.filter { rows ->
            rows[rows.size / 2][0] >= pixels.height * FIELD_BOTTOM_PREFERENCE
        }
        val candidatePool = if (bottomBands.isNotEmpty()) bottomBands else usable
        val bestWidth = candidatePool.maxOf { rows -> rows.maxOf { it[1] } }
        val band = candidatePool
            .filter { rows -> rows.maxOf { it[1] } >= bestWidth * FIELD_WIDTH_TOLERANCE }
            .minBy { rows -> abs(rows[rows.size / 2][0] - anchorY) }
        var y = band[band.size / 2][0]
        val widest = band.maxBy { it[1] }
        val x = widest[2] + widest[1] / 2
        val color = pixels.colorAt(x.coerceIn(0, pixels.width - 1), y)
        if (color.darkest < FIELD_MIN_BRIGHT) {
            y = widest[0]
        }
        return ScreenPoint(x, y)
    }

    private fun longestBrightRun(
        pixels: PixelSource,
        y: Int,
        maxGap: Int = 6,
    ): IntArray {
        var bestLength = 0
        var bestStart = 0
        var runStart = -1
        var lastBrightX = -1

        for (x in 0 until pixels.width) {
            val bright = pixels.colorAt(x, y).darkest >= FIELD_MIN_BRIGHT
            if (!bright) {
                continue
            }
            if (runStart < 0 || x - lastBrightX > maxGap) {
                runStart = x
            }
            lastBrightX = x
            val length = lastBrightX - runStart + 1
            if (length > bestLength) {
                bestLength = length
                bestStart = runStart
            }
        }
        return intArrayOf(bestLength, bestStart)
    }

    private fun defaultProfile(pixels: PixelSource): DeviceProfile {
        return DeviceProfiles.resolve(pixels.width, pixels.height, 480)
    }
}
