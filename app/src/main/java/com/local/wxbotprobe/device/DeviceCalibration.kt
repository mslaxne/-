package com.local.wxbotprobe.device

import android.content.Context
import android.view.WindowManager
import com.local.wxbotprobe.chat.ScreenPoint
import org.json.JSONObject

data class DeviceIdentity(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val orientation: Int,
    val wechatVersionCode: Long,
    val fontScale: Float,
) {
    fun matches(other: DeviceIdentity): Boolean {
        return width == other.width &&
            height == other.height &&
            densityDpi == other.densityDpi &&
            orientation == other.orientation &&
            wechatVersionCode == other.wechatVersionCode &&
            kotlin.math.abs(fontScale - other.fontScale) < 0.01f
    }
}

data class StoredDeviceCalibration(
    val profileId: String,
    val identity: DeviceIdentity,
    val inputAnchor: ScreenPoint,
    val sendPoint: ScreenPoint,
    val contentTopY: Int,
    val fastRegionTopY: Int,
    val fieldBandMinPx: Int,
    val fieldBandMaxPx: Int,
    val greenButtonSearchHalfHeightPx: Int,
    val maxFixedRowDriftPx: Int,
    val verticalScale: Float,
    val verifiedAt: Long,
) {
    fun toDeviceProfile(): DeviceProfile {
        return DeviceProfile(
            profileId = profileId,
            width = identity.width,
            height = identity.height,
            densityDpi = identity.densityDpi,
            inputAnchor = inputAnchor,
            contentTopY = contentTopY,
            fastRegionTopY = fastRegionTopY,
            fieldBandMinPx = fieldBandMinPx,
            fieldBandMaxPx = fieldBandMaxPx,
            greenButtonSearchHalfHeightPx = greenButtonSearchHalfHeightPx,
            maxFixedRowDriftPx = maxFixedRowDriftPx,
            verticalScale = verticalScale,
            mode = DeviceProfileMode.VERIFIED,
        )
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("profile_id", profileId)
            .put("width", identity.width)
            .put("height", identity.height)
            .put("density_dpi", identity.densityDpi)
            .put("orientation", identity.orientation)
            .put("wechat_version_code", identity.wechatVersionCode)
            .put("font_scale", identity.fontScale.toDouble())
            .put("input_x", inputAnchor.x)
            .put("input_y", inputAnchor.y)
            .put("send_x", sendPoint.x)
            .put("send_y", sendPoint.y)
            .put("content_top_y", contentTopY)
            .put("fast_region_top_y", fastRegionTopY)
            .put("field_band_min_px", fieldBandMinPx)
            .put("field_band_max_px", fieldBandMaxPx)
            .put("green_search_half_height_px", greenButtonSearchHalfHeightPx)
            .put("max_fixed_row_drift_px", maxFixedRowDriftPx)
            .put("vertical_scale", verticalScale.toDouble())
            .put("verified_at", verifiedAt)
    }

    companion object {
        fun fromJson(root: JSONObject): StoredDeviceCalibration? {
            return runCatching {
                val identity = DeviceIdentity(
                    width = root.getInt("width"),
                    height = root.getInt("height"),
                    densityDpi = root.getInt("density_dpi"),
                    orientation = root.getInt("orientation"),
                    wechatVersionCode = root.getLong("wechat_version_code"),
                    fontScale = root.getDouble("font_scale").toFloat(),
                )
                val calibration = StoredDeviceCalibration(
                    profileId = root.getString("profile_id"),
                    identity = identity,
                    inputAnchor = ScreenPoint(
                        root.getInt("input_x"),
                        root.getInt("input_y"),
                    ),
                    sendPoint = ScreenPoint(
                        root.getInt("send_x"),
                        root.getInt("send_y"),
                    ),
                    contentTopY = root.getInt("content_top_y"),
                    fastRegionTopY = root.getInt("fast_region_top_y"),
                    fieldBandMinPx = root.getInt("field_band_min_px"),
                    fieldBandMaxPx = root.getInt("field_band_max_px"),
                    greenButtonSearchHalfHeightPx = root.getInt(
                        "green_search_half_height_px",
                    ),
                    maxFixedRowDriftPx = root.getInt("max_fixed_row_drift_px"),
                    verticalScale = root.getDouble("vertical_scale").toFloat(),
                    verifiedAt = root.getLong("verified_at"),
                )
                if (calibration.isValid()) calibration else null
            }.getOrNull()
        }
    }

    fun isValid(): Boolean {
        return profileId.isNotBlank() &&
            identity.width > 0 &&
            identity.height > 0 &&
            identity.densityDpi > 0 &&
            identity.wechatVersionCode > 0L &&
            inputAnchor.x in 0 until identity.width &&
            inputAnchor.y in 0 until identity.height &&
            sendPoint.x in 0 until identity.width &&
            sendPoint.y in 0 until identity.height &&
            contentTopY in 0 until identity.height &&
            fastRegionTopY in contentTopY until identity.height &&
            fieldBandMinPx > 0 &&
            fieldBandMaxPx > fieldBandMinPx &&
            greenButtonSearchHalfHeightPx > 0 &&
            maxFixedRowDriftPx > 0 &&
            verticalScale in 0.5f..2f
    }
}

class DeviceCalibrationStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )

    fun load(): StoredDeviceCalibration? {
        val raw = preferences.getString(KEY_PROFILE, null) ?: return null
        return runCatching {
            StoredDeviceCalibration.fromJson(JSONObject(raw))
        }.getOrNull()
    }

    fun loadMatching(identity: DeviceIdentity): StoredDeviceCalibration? {
        return load()?.takeIf { it.identity.matches(identity) }
    }

    fun save(calibration: StoredDeviceCalibration) {
        require(calibration.isValid()) { "invalid device calibration" }
        preferences.edit()
            .putString(KEY_PROFILE, calibration.toJson().toString())
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_PROFILE).apply()
    }

    companion object {
        private const val PREFS = "device_calibration"
        private const val KEY_PROFILE = "profile"
    }
}

object DeviceIdentityResolver {
    fun current(context: Context): DeviceIdentity {
        val bounds = context.getSystemService(WindowManager::class.java)
            .maximumWindowMetrics
            .bounds
        val versionCode = runCatching {
            context.packageManager.getPackageInfo("com.tencent.mm", 0).longVersionCode
        }.getOrDefault(0L)
        return DeviceIdentity(
            width = bounds.width(),
            height = bounds.height(),
            densityDpi = context.resources.displayMetrics.densityDpi,
            orientation = if (bounds.width() <= bounds.height()) 0 else 1,
            wechatVersionCode = versionCode,
            fontScale = context.resources.configuration.fontScale,
        )
    }
}
