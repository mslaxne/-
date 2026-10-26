package com.local.wxbotprobe.chat

import kotlin.math.abs

data class FrameDifference(
    val meanChannelDiff: Double,
    val changedRatio: Double,
)

class FrameFingerprint(
    private val pixels: IntArray,
) {
    val size: Int
        get() = pixels.size

    fun differenceFrom(previous: FrameFingerprint): FrameDifference {
        require(previous.size == size) { "frame fingerprints must have the same size" }
        var totalDiff = 0L
        var changed = 0
        for (index in pixels.indices) {
            val current = pixels[index]
            val old = previous.pixels[index]
            val red = abs((current shr 16 and 0xFF) - (old shr 16 and 0xFF))
            val green = abs((current shr 8 and 0xFF) - (old shr 8 and 0xFF))
            val blue = abs((current and 0xFF) - (old and 0xFF))
            totalDiff += red + green + blue
            if (maxOf(red, green, blue) > CHANGED_CHANNEL_THRESHOLD) {
                changed++
            }
        }
        return FrameDifference(
            meanChannelDiff = totalDiff.toDouble() / (size * 3.0),
            changedRatio = changed.toDouble() / size,
        )
    }

    fun isSameScene(previous: FrameFingerprint): Boolean {
        val difference = differenceFrom(previous)
        return difference.meanChannelDiff <= MEAN_DIFF_MAX &&
            difference.changedRatio <= CHANGED_RATIO_MAX
    }

    companion object {
        const val THUMBNAIL_WIDTH = 54
        const val THUMBNAIL_HEIGHT = 120
        private const val CHANGED_CHANNEL_THRESHOLD = 24
        private const val MEAN_DIFF_MAX = 1.5
        private const val CHANGED_RATIO_MAX = 0.015
    }
}
