package com.local.wxbotprobe.device

import android.content.Context
import com.local.wxbotprobe.chat.ScreenPoint
import kotlin.math.roundToInt

enum class DeviceProfileMode {
    VERIFIED,
    SCALED_FALLBACK,
}

data class DeviceProfile(
    val profileId: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val inputAnchor: ScreenPoint,
    val contentTopY: Int,
    val fastRegionTopY: Int,
    val fieldBandMinPx: Int,
    val fieldBandMaxPx: Int,
    val greenButtonSearchHalfHeightPx: Int,
    val maxFixedRowDriftPx: Int,
    val verticalScale: Float,
    val mode: DeviceProfileMode,
) {
    val fastRegionHeight: Int
        get() = (height - fastRegionTopY).coerceAtLeast(1)
}

object DeviceProfiles {
    private const val REFERENCE_WIDTH = 1080
    private const val REFERENCE_HEIGHT = 2400
    private const val REFERENCE_DENSITY = 480
    private const val REFERENCE_INPUT_X = 538
    private const val REFERENCE_INPUT_Y = 2320
    private const val REFERENCE_CONTENT_TOP = 500
    private const val REFERENCE_FAST_REGION_HEIGHT = 1600
    private const val REFERENCE_FIELD_BAND_MIN = 30
    private const val REFERENCE_FIELD_BAND_MAX = 150
    private const val REFERENCE_GREEN_SEARCH_HALF_HEIGHT = 110
    private const val REFERENCE_ROW_DRIFT = 500

    private val verifiedVivo = DeviceProfile(
        profileId = "vivo-v2055a-1080x2400-480",
        width = REFERENCE_WIDTH,
        height = REFERENCE_HEIGHT,
        densityDpi = REFERENCE_DENSITY,
        inputAnchor = ScreenPoint(REFERENCE_INPUT_X, REFERENCE_INPUT_Y),
        contentTopY = REFERENCE_CONTENT_TOP,
        fastRegionTopY = REFERENCE_HEIGHT - REFERENCE_FAST_REGION_HEIGHT,
        fieldBandMinPx = REFERENCE_FIELD_BAND_MIN,
        fieldBandMaxPx = REFERENCE_FIELD_BAND_MAX,
        greenButtonSearchHalfHeightPx = REFERENCE_GREEN_SEARCH_HALF_HEIGHT,
        maxFixedRowDriftPx = REFERENCE_ROW_DRIFT,
        verticalScale = 1f,
        mode = DeviceProfileMode.VERIFIED,
    )

    fun resolve(
        width: Int,
        height: Int,
        densityDpi: Int,
    ): DeviceProfile {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val safeDensity = densityDpi.coerceAtLeast(1)
        if (
            safeWidth == REFERENCE_WIDTH &&
            safeHeight == REFERENCE_HEIGHT &&
            safeDensity == REFERENCE_DENSITY
        ) {
            return verifiedVivo
        }
        val heightScale = safeHeight.toDouble() / REFERENCE_HEIGHT
        val densityScale = safeDensity.toDouble() / REFERENCE_DENSITY
        val verticalScale = (heightScale * 0.75 + densityScale * 0.25)
            .coerceIn(0.55, 1.75)
            .toFloat()
        val minimumBand = maxOf(16, (REFERENCE_FIELD_BAND_MIN * heightScale).roundToInt())
        val maximumBand = maxOf(
            minimumBand + 12,
            (REFERENCE_FIELD_BAND_MAX * heightScale).roundToInt(),
        )
        val fastHeight = (REFERENCE_FAST_REGION_HEIGHT * heightScale)
            .roundToInt()
            .coerceIn(1, safeHeight)
        return DeviceProfile(
            profileId = "scaled-${safeWidth}x${safeHeight}-${safeDensity}",
            width = safeWidth,
            height = safeHeight,
            densityDpi = safeDensity,
            inputAnchor = ScreenPoint(
                x = (safeWidth * REFERENCE_INPUT_X.toDouble() / REFERENCE_WIDTH)
                    .roundToInt()
                    .coerceIn(0, safeWidth - 1),
                y = (safeHeight * REFERENCE_INPUT_Y.toDouble() / REFERENCE_HEIGHT)
                    .roundToInt()
                    .coerceIn(0, safeHeight - 1),
            ),
            contentTopY = (REFERENCE_CONTENT_TOP * heightScale)
                .roundToInt()
                .coerceIn(0, safeHeight - 1),
            fastRegionTopY = (safeHeight - fastHeight)
                .coerceIn(0, safeHeight - 1),
            fieldBandMinPx = minimumBand,
            fieldBandMaxPx = maximumBand,
            greenButtonSearchHalfHeightPx = maxOf(
                36,
                (REFERENCE_GREEN_SEARCH_HALF_HEIGHT * heightScale).roundToInt(),
            ),
            maxFixedRowDriftPx = maxOf(
                180,
                (REFERENCE_ROW_DRIFT * heightScale).roundToInt(),
            ),
            verticalScale = verticalScale,
            mode = DeviceProfileMode.SCALED_FALLBACK,
        )
    }

    fun forImage(
        base: DeviceProfile,
        imageWidth: Int,
        imageHeight: Int,
    ): DeviceProfile {
        if (imageWidth == base.width && imageHeight == base.height) {
            return base
        }
        return resolve(imageWidth, imageHeight, base.densityDpi)
    }
}

object DeviceProfileResolver {
    fun current(context: Context): DeviceProfile {
        val identity = DeviceIdentityResolver.current(context)
        DeviceCalibrationStore(context).loadMatching(identity)?.let {
            return it.toDeviceProfile()
        }
        return DeviceProfiles.resolve(
            width = identity.width,
            height = identity.height,
            densityDpi = identity.densityDpi,
        )
    }
}
