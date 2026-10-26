package com.local.wxbotprobe.send

import android.content.Context
import com.local.wxbotprobe.chat.ScreenPoint
import com.local.wxbotprobe.device.DeviceCalibrationStore
import com.local.wxbotprobe.device.DeviceIdentityResolver
import kotlin.math.abs

data class SendScreenIdentity(
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int,
    val wechatVersionCode: Long,
)

data class SendPointCalibration(
    val profileId: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int,
    val wechatVersionCode: Long,
    val sendPoint: ScreenPoint,
    val maxFixedRowDrift: Int,
)

object SendPointPolicy {
    private const val MAX_FIXED_SEND_ROW_DRIFT = 500
    private val calibratedProfiles = listOf(
        SendPointCalibration(
            profileId = "vivo-v2055a-1080x2400-480-wechat-3200",
            screenWidth = 1080,
            screenHeight = 2400,
            densityDpi = 480,
            wechatVersionCode = 3200L,
            sendPoint = ScreenPoint(984, 2320),
            maxFixedRowDrift = MAX_FIXED_SEND_ROW_DRIFT,
        ),
    )

    fun resolve(identity: SendScreenIdentity): SendPointCalibration? {
        return calibratedProfiles.firstOrNull { profile ->
            profile.screenWidth == identity.screenWidth &&
                profile.screenHeight == identity.screenHeight &&
                profile.densityDpi == identity.densityDpi &&
                profile.wechatVersionCode == identity.wechatVersionCode
        }
    }

    fun resolveCurrent(context: Context): SendPointCalibration? {
        val identity = DeviceIdentityResolver.current(context)
        DeviceCalibrationStore(context).loadMatching(identity)?.let { calibration ->
            return SendPointCalibration(
                profileId = calibration.profileId,
                screenWidth = identity.width,
                screenHeight = identity.height,
                densityDpi = identity.densityDpi,
                wechatVersionCode = identity.wechatVersionCode,
                sendPoint = calibration.sendPoint,
                maxFixedRowDrift = calibration.maxFixedRowDriftPx,
            )
        }
        if (identity.wechatVersionCode <= 0L) {
            return null
        }
        return resolve(
            SendScreenIdentity(
                screenWidth = identity.width,
                screenHeight = identity.height,
                densityDpi = identity.densityDpi,
                wechatVersionCode = identity.wechatVersionCode,
            ),
        )
    }

    fun canUseFixedSendPoint(
        inputField: ScreenPoint,
        sendPoint: ScreenPoint,
    ): Boolean {
        return abs(inputField.y - sendPoint.y) <= MAX_FIXED_SEND_ROW_DRIFT
    }

    fun canUseFixedSendPoint(
        inputField: ScreenPoint,
        calibration: SendPointCalibration,
    ): Boolean {
        return abs(inputField.y - calibration.sendPoint.y) <=
            calibration.maxFixedRowDrift
    }

    fun canUseInputHint(
        hasHint: Boolean,
        calibration: SendPointCalibration?,
    ): Boolean {
        return hasHint && calibration != null
    }
}
