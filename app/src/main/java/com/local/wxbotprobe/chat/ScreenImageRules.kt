package com.local.wxbotprobe.chat

import com.local.wxbotprobe.device.DeviceProfile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ScreenImageRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val kind: BubbleKind,
)

object ScreenImageRules {
    private const val GRID_STEP = 8
    private const val CLOSE_RADIUS = 3
    private const val MIN_REGION_WIDTH_SHARE = 0.18
    private const val MIN_REGION_HEIGHT_SHARE = 0.055
    private const val EDGE_PADDING = 8
    private const val WECHAT_GREEN_REJECTION_RATIO = 0.18

    fun detect(
        pixels: PixelSource,
        profile: DeviceProfile,
        sendButtonY: Int?,
        contentTopOverride: Int? = null,
        contentBottomOverride: Int? = null,
    ): List<ScreenImageRegion> {
        if (pixels.width < 4 || pixels.height < 4) {
            return emptyList()
        }
        val scale = profile.verticalScale.coerceAtLeast(0.1f)
        val contentTop = (contentTopOverride ?: max(
            (pixels.height * ScreenReadingRules.CHAT_TOP).toInt(),
            profile.contentTopY,
        )).coerceIn(0, pixels.height - 1)
        val contentBottom = (contentBottomOverride ?: (
            sendButtonY?.minus((90 * scale).roundToInt())
                ?: (profile.inputAnchor.y - (60 * scale).roundToInt())
            )).coerceIn(contentTop + 1, pixels.height)
        val leftLimit = (pixels.width * 0.08).toInt().coerceAtLeast(0)
        val rightLimit = (pixels.width * 0.95).toInt()
            .coerceIn(leftLimit + 1, pixels.width)
        val columns = (rightLimit - leftLimit) / GRID_STEP
        val rows = (contentBottom - contentTop) / GRID_STEP
        if (columns < 8 || rows < 8) {
            return emptyList()
        }

        val candidate = BooleanArray(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = leftLimit + column * GRID_STEP + GRID_STEP / 2
                val y = contentTop + row * GRID_STEP + GRID_STEP / 2
                if (isVisualPixel(pixels.colorAt(x, y))) {
                    candidate[row * columns + column] = true
                }
            }
        }

        val expanded = expand(candidate, columns, rows, CLOSE_RADIUS)
        val visited = BooleanArray(expanded.size)
        val minimumWidth = max(
            (pixels.width * MIN_REGION_WIDTH_SHARE).roundToInt(),
            (180 * scale).roundToInt(),
        )
        val minimumHeight = max(
            (pixels.height * MIN_REGION_HEIGHT_SHARE).roundToInt(),
            (110 * scale).roundToInt(),
        )
        val regions = mutableListOf<ScreenImageRegion>()
        val queue = ArrayDeque<Int>()
        for (start in expanded.indices) {
            if (!expanded[start] || visited[start]) {
                continue
            }
            queue.addLast(start)
            visited[start] = true
            var minColumn = columns
            var maxColumn = -1
            var minRow = rows
            var maxRow = -1
            var originalPixels = 0
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val row = index / columns
                val column = index % columns
                if (candidate[index]) {
                    minColumn = min(minColumn, column)
                    maxColumn = max(maxColumn, column)
                    minRow = min(minRow, row)
                    maxRow = max(maxRow, row)
                    originalPixels++
                }
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) {
                            continue
                        }
                        val nextRow = row + dy
                        val nextColumn = column + dx
                        if (
                            nextRow !in 0 until rows ||
                            nextColumn !in 0 until columns
                        ) {
                            continue
                        }
                        val next = nextRow * columns + nextColumn
                        if (expanded[next] && !visited[next]) {
                            visited[next] = true
                            queue.addLast(next)
                        }
                    }
                }
            }
            if (originalPixels < 40 || maxColumn < minColumn || maxRow < minRow) {
                continue
            }
            val padding = (EDGE_PADDING * scale).roundToInt().coerceAtLeast(4)
            val left = (
                leftLimit + minColumn * GRID_STEP - padding
                ).coerceIn(0, pixels.width - 1)
            val top = (
                contentTop + minRow * GRID_STEP - padding
                ).coerceIn(contentTop, contentBottom - 1)
            val right = (
                leftLimit + (maxColumn + 1) * GRID_STEP + padding
                ).coerceIn(left + 1, pixels.width)
            val bottom = (
                contentTop + (maxRow + 1) * GRID_STEP + padding
                ).coerceIn(top + 1, contentBottom)
            if (
                right - left < minimumWidth ||
                bottom - top < minimumHeight
            ) {
                continue
            }
            if (
                wechatGreenRatio(
                    pixels,
                    left,
                    top,
                    right,
                    bottom,
                ) >= WECHAT_GREEN_REJECTION_RATIO
            ) {
                continue
            }
            val kind = when {
                left < pixels.width * 0.35 &&
                    right < pixels.width * 0.86 -> BubbleKind.IN
                right > pixels.width * 0.65 &&
                    left > pixels.width * 0.28 -> BubbleKind.OUT
                else -> BubbleKind.UNKNOWN
            }
            regions += ScreenImageRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                kind = kind,
            )
        }
        return regions
            .sortedBy { it.top }
            .filterIndexed { index, region ->
                regions.take(index).none { previous ->
                    overlapRatio(previous, region) >= 0.65
                }
            }
    }

    private fun isVisualPixel(color: PixelColor): Boolean {
        val range = color.brightest - color.darkest
        val neutralLight = color.brightest >= 224 && range <= 18
        val greenBubble = color.green >= 190 &&
            color.green - color.red >= 24 &&
            color.green - color.blue >= 36 &&
            color.blue <= 190
        val neutralInk = color.brightest <= 170 && range <= 38
        return !neutralLight && !greenBubble && !neutralInk
    }

    private fun wechatGreenRatio(
        pixels: PixelSource,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Double {
        var samples = 0
        var green = 0
        for (y in top until bottom step GRID_STEP) {
            for (x in left until right step GRID_STEP) {
                samples++
                val color = pixels.colorAt(
                    x.coerceIn(0, pixels.width - 1),
                    y.coerceIn(0, pixels.height - 1),
                )
                if (
                    color.green >= 190 &&
                    color.green - color.red >= 24 &&
                    color.green - color.blue >= 36 &&
                    color.blue <= 190
                ) {
                    green++
                }
            }
        }
        return if (samples == 0) 0.0 else green.toDouble() / samples
    }

    private fun expand(
        candidate: BooleanArray,
        columns: Int,
        rows: Int,
        radius: Int,
    ): BooleanArray {
        val expanded = BooleanArray(candidate.size)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (!candidate[row * columns + column]) {
                    continue
                }
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val targetRow = row + dy
                        val targetColumn = column + dx
                        if (targetRow in 0 until rows && targetColumn in 0 until columns) {
                            expanded[targetRow * columns + targetColumn] = true
                        }
                    }
                }
            }
        }
        return expanded
    }

    private fun overlapRatio(
        first: ScreenImageRegion,
        second: ScreenImageRegion,
    ): Double {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        if (right <= left || bottom <= top) {
            return 0.0
        }
        val overlap = (right - left).toLong() * (bottom - top)
        val smaller = min(
            (first.right - first.left).toLong() * (first.bottom - first.top),
            (second.right - second.left).toLong() * (second.bottom - second.top),
        ).coerceAtLeast(1L)
        return overlap.toDouble() / smaller
    }
}
