package com.local.wxbotprobe.ocr

import android.graphics.Bitmap
import com.local.wxbotprobe.chat.BitmapPixelSource
import com.local.wxbotprobe.chat.BubbleKind
import com.local.wxbotprobe.chat.ScreenPixelRules
import com.local.wxbotprobe.chat.ScreenReadingRules
import com.local.wxbotprobe.chat.TextRules
import com.local.wxbotprobe.device.DeviceProfile
import com.local.wxbotprobe.device.DeviceProfiles
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LocalOcrAuditResult(
    val cropHeight: Int,
    val filteredRows: Int,
    val recoveredRows: Int,
    val candidates: List<LocalAuditCandidate> = emptyList(),
)

object LocalOcrAuditPolicy {
    private const val FUZZY_MIN_CHARS = 20
    private const val FUZZY_RATIO = 0.85

    fun countRecovered(
        baseTexts: List<String>,
        auditTexts: List<String>,
    ): Int {
        val base = baseTexts.map(TextRules::normalise)
            .filter { it.isNotEmpty() }
        return auditTexts.asSequence()
            .map(TextRules::normalise)
            .filter { it.length >= 2 }
            .distinct()
            .count { candidate ->
                base.none { existing -> matches(existing, candidate) }
            }
    }

    fun isRecovered(
        baseTexts: List<String>,
        auditText: String,
    ): Boolean {
        val candidate = TextRules.normalise(auditText)
        if (candidate.length < 2) {
            return false
        }
        return baseTexts.asSequence()
            .map(TextRules::normalise)
            .filter { it.isNotEmpty() }
            .none { matches(it, candidate) }
    }

    fun matches(existing: String, candidate: String): Boolean {
        if (existing == candidate) {
            return true
        }
        if (existing.length < FUZZY_MIN_CHARS || candidate.length < FUZZY_MIN_CHARS) {
            return false
        }
        if (existing in candidate || candidate in existing) {
            return true
        }
        return TextRules.sequenceRatio(existing, candidate) >= FUZZY_RATIO
    }
}

class LocalOcrAuditor(
    private val engine: OcrEngine,
    private val scale: Float = OCR_INPUT_SCALE,
) {
    fun audit(
        bitmap: Bitmap,
        inputY: Int,
        baseTexts: List<String>,
        profile: DeviceProfile = DeviceProfiles.resolve(
            bitmap.width,
            bitmap.height,
            480,
        ),
    ): LocalOcrAuditResult {
        val safeInputY = inputY.coerceIn(bitmap.height / 3, bitmap.height)
        val bottomGap = (40 * profile.verticalScale).roundToInt()
        val bottom = min(bitmap.height, safeInputY - bottomGap)
        val maxCropHeight = (MAX_CROP_HEIGHT * profile.verticalScale).roundToInt()
        val minimumCropHeight = (MIN_CROP_HEIGHT * profile.verticalScale)
            .roundToInt()
        val top = max((bitmap.height * 0.09).toInt(), bottom - maxCropHeight)
        if (bottom - top < minimumCropHeight) {
            return LocalOcrAuditResult(0, 0, 0)
        }
        val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
        val scaled = try {
            Bitmap.createScaledBitmap(
                crop,
                (crop.width * scale).toInt(),
                (crop.height * scale).toInt(),
                true,
            )
        } finally {
            crop.recycle()
        }
        return try {
            val scaledProfile = DeviceProfiles.resolve(
                scaled.width,
                scaled.height,
                profile.densityDpi,
            )
            val rules = ScreenReadingRules(
                width = scaled.width,
                height = scaled.height,
                inputY = ((safeInputY - top) * scale).toInt(),
                contentTopY = (scaled.height * ScreenReadingRules.CHAT_TOP).toInt(),
                minChars = 2,
                minScore = 0.5,
                watch = "",
                verticalScale = scaledProfile.verticalScale,
            )
            val rows = engine.recognize(scaled)
            val pixels = BitmapPixelSource(scaled)
            val filtered = rules.filterRows(rows, null) {
                ScreenPixelRules.inkIsMessage(pixels, it, scaledProfile)
            }
            val blocks = rules.bubbleBlocks(filtered.messages)
                .filterNot {
                    ScreenPixelRules.looksLikeImage(pixels, it, scaledProfile)
                }
            val candidates = blocks.mapNotNull { block ->
                if (!LocalOcrAuditPolicy.isRecovered(baseTexts, block.text)) {
                    return@mapNotNull null
                }
                val kind = ScreenPixelRules.bubbleKind(pixels, block)
                if (kind == BubbleKind.UNKNOWN) {
                    return@mapNotNull null
                }
                LocalAuditCandidate(
                    text = block.text,
                    kind = kind,
                    y = (block.rows.first().y / scale).toInt() + top,
                    xLeft = (block.xLeft / scale).toInt(),
                    score = block.rows.map { it.score }.average(),
                )
            }
            LocalOcrAuditResult(
                cropHeight = bottom - top,
                filteredRows = filtered.messages.size,
                recoveredRows = candidates.size,
                candidates = candidates,
            )
        } finally {
            scaled.recycle()
        }
    }

    companion object {
        private const val MAX_CROP_HEIGHT = 1_200
        private const val MIN_CROP_HEIGHT = 160
    }
}
