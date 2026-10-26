package com.local.wxbotprobe.ocr

import com.local.wxbotprobe.device.DeviceProfile
import kotlin.math.max

object SnapshotRegions {
    const val FAST_REGION_HEIGHT = 1_600

    fun messageTop(imageHeight: Int): Int {
        val safeHeight = imageHeight.coerceAtLeast(1)
        val bottomRegionTop = (safeHeight - FAST_REGION_HEIGHT).coerceAtLeast(0)
        return max(bottomRegionTop, 0).coerceAtMost(safeHeight - 1)
    }

    fun messageTop(
        imageHeight: Int,
        profile: DeviceProfile,
    ): Int {
        val safeHeight = imageHeight.coerceAtLeast(1)
        if (safeHeight != profile.height) {
            return messageTop(safeHeight)
        }
        return profile.fastRegionTopY.coerceIn(0, safeHeight - 1)
    }
}
