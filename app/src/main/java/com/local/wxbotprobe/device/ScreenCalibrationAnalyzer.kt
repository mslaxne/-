package com.local.wxbotprobe.device

import com.local.wxbotprobe.chat.PixelSource
import com.local.wxbotprobe.chat.ScreenPixelRules
import com.local.wxbotprobe.chat.ScreenPoint
import kotlin.math.abs

data class ScreenCalibrationAnalysis(
    val calibration: StoredDeviceCalibration?,
    val inputAnchor: ScreenPoint?,
    val sendPoint: ScreenPoint?,
    val message: String,
) {
    val valid: Boolean
        get() = calibration != null
}

object ScreenCalibrationAnalyzer {
    fun analyze(
        pixels: PixelSource,
        identity: DeviceIdentity,
        verifiedAt: Long,
    ): ScreenCalibrationAnalysis {
        if (pixels.width != identity.width || pixels.height != identity.height) {
            return ScreenCalibrationAnalysis(
                calibration = null,
                inputAnchor = null,
                sendPoint = null,
                message = "截图尺寸与当前设备不一致",
            )
        }
        if (identity.wechatVersionCode <= 0L) {
            return ScreenCalibrationAnalysis(
                calibration = null,
                inputAnchor = null,
                sendPoint = null,
                message = "无法读取微信版本",
            )
        }
        val base = DeviceProfiles.resolve(
            identity.width,
            identity.height,
            identity.densityDpi,
        )
        val input = ScreenPixelRules.locateField(
            pixels,
            base.inputAnchor.y,
            base,
        ) ?: return ScreenCalibrationAnalysis(
            calibration = null,
            inputAnchor = null,
            sendPoint = null,
            message = "未识别到微信输入栏",
        )
        if (input.y < identity.height * 0.75) {
            return ScreenCalibrationAnalysis(
                calibration = null,
                inputAnchor = input,
                sendPoint = null,
                message = "输入栏位置异常，请关闭表情面板后重试",
            )
        }
        val send = ScreenPixelRules.findGreenButton(
            pixels,
            input.y,
            base,
        ) ?: return ScreenCalibrationAnalysis(
            calibration = null,
            inputAnchor = input,
            sendPoint = null,
            message = "未识别到绿色发送按钮，请先在输入栏保留文字草稿",
        )
        if (
            send.x < identity.width * 0.7 ||
            send.y < identity.height * 0.75 ||
            abs(send.y - input.y) > base.greenButtonSearchHalfHeightPx * 1.25
        ) {
            return ScreenCalibrationAnalysis(
                calibration = null,
                inputAnchor = input,
                sendPoint = send,
                message = "发送按钮与输入栏位置不匹配",
            )
        }
        return ScreenCalibrationAnalysis(
            calibration = StoredDeviceCalibration(
                profileId = "calibrated-${identity.width}x${identity.height}-" +
                    "${identity.densityDpi}-wechat-${identity.wechatVersionCode}",
                identity = identity,
                inputAnchor = input,
                sendPoint = send,
                contentTopY = base.contentTopY,
                fastRegionTopY = base.fastRegionTopY,
                fieldBandMinPx = base.fieldBandMinPx,
                fieldBandMaxPx = base.fieldBandMaxPx,
                greenButtonSearchHalfHeightPx = base.greenButtonSearchHalfHeightPx,
                maxFixedRowDriftPx = base.maxFixedRowDriftPx,
                verticalScale = base.verticalScale,
                verifiedAt = verifiedAt,
            ),
            inputAnchor = input,
            sendPoint = send,
            message = "识别成功",
        )
    }
}
