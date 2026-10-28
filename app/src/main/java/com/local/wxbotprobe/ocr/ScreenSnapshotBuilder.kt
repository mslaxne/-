package com.local.wxbotprobe.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import com.local.wxbotprobe.chat.BitmapPixelSource
import com.local.wxbotprobe.chat.ChatIdentity
import com.local.wxbotprobe.chat.ChatHeaderIdentity
import com.local.wxbotprobe.chat.IMAGE_PLACEHOLDER
import com.local.wxbotprobe.chat.OcrRow
import com.local.wxbotprobe.chat.ScreenImageRegion
import com.local.wxbotprobe.chat.ScreenImageRules
import com.local.wxbotprobe.chat.ScreenPixelRules
import com.local.wxbotprobe.chat.ScreenReadingRules
import com.local.wxbotprobe.chat.ScreenSnapshot
import com.local.wxbotprobe.device.DeviceProfile
import com.local.wxbotprobe.device.DeviceProfiles
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// RapidOCR retains large native arenas at full phone resolution. Recognition
// runs on a smaller copy while pixel rules continue to use the original frame.
internal const val OCR_INPUT_SCALE = 0.75f

internal fun scaleOcrRows(
    rows: List<OcrRow>,
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): List<OcrRow> {
    if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
        return rows
    }
    val scaleX = sourceWidth.toFloat() / targetWidth
    val scaleY = sourceHeight.toFloat() / targetHeight
    return rows.map {
        it.copy(
            y = (it.y * scaleY).roundToInt(),
            xLeft = floor(it.xLeft * scaleX).toInt(),
            xRight = ceil(it.xRight * scaleX).toInt(),
        )
    }
}

class ScreenSnapshotBuilder(
    private val engine: OcrEngine,
    private val baseProfile: DeviceProfile,
) {
    fun read(bitmap: Bitmap, engineName: String): ScreenSnapshot {
        val profile = profileFor(bitmap)
        val rules = ScreenReadingRules(
            width = bitmap.width,
            height = bitmap.height,
            inputY = profile.inputAnchor.y,
            contentTopY = profile.contentTopY,
            minChars = 2,
            minScore = 0.5,
            watch = "",
            verticalScale = profile.verticalScale,
        )
        val engineStartedAt = SystemClock.elapsedRealtime()
        val rows = recognizeFastScaled(bitmap)
        val engineFinishedAt = SystemClock.elapsedRealtime()
        val pixels = BitmapPixelSource(bitmap)
        val chat = ChatHeaderIdentity.from(pixels, rows)
        val sendY = ScreenPixelRules.sendButtonY(pixels, profile)
        val filtered = rules.filterRows(rows, sendY) {
            ScreenPixelRules.inkIsMessage(pixels, it, profile)
        }
        val blocks = rules.bubbleBlocks(filtered.messages)
        val detectedImages = ScreenImageRules.detect(pixels, profile, sendY)
        val imageLikeBlocks = blocks
            .filter { ScreenPixelRules.looksLikeImage(pixels, it, profile) }
            .toSet()
        val imageRegions = detectedImages + imageLikeBlocks
            .mapNotNull { block ->
                val box = ScreenPixelRules.bubbleBox(
                    block,
                    bitmap.width,
                    bitmap.height,
                    profile,
                )
                if (detectedImages.any { overlaps(box, it) }) {
                    null
                } else {
                    ScreenImageRegion(
                        left = box[0],
                        top = box[1],
                        right = box[2] + 1,
                        bottom = box[3] + 1,
                        kind = ScreenPixelRules.bubbleKind(pixels, block),
                    )
                }
            }
        val items = buildList {
            blocks
                .filterNot { it in imageLikeBlocks }
                .forEach { block ->
                    add(
                        com.local.wxbotprobe.chat.ScreenItem(
                            text = block.text,
                            kind = ScreenPixelRules.bubbleKind(pixels, block),
                            y = block.rows.first().y,
                            xLeft = block.xLeft,
                        ),
                    )
                }
            imageRegions.forEach { region ->
                val encoded = ScreenImageEncoder.encode(bitmap, region) ?: return@forEach
                add(
                    com.local.wxbotprobe.chat.ScreenItem(
                        text = IMAGE_PLACEHOLDER,
                        kind = region.kind,
                        y = region.top,
                        xLeft = region.left,
                        image = encoded.bytes,
                        imageWidth = encoded.width,
                        imageHeight = encoded.height,
                        imageSha256 = encoded.sha256,
                        imageMimeType = encoded.mimeType,
                    ),
                )
            }
        }.sortedBy { it.y }
        return ScreenSnapshot(
            width = bitmap.width,
            height = bitmap.height,
            items = items,
            greyRows = filtered.grey,
            isGroup = false,
            inputField = ScreenPixelRules.locateField(
                pixels,
                profile.inputAnchor.y,
                profile,
            ),
            sendButtonY = sendY,
            ocrRowCount = rows.size,
            engine = engineName,
            chat = chat,
            ocrEngineMs = engineFinishedAt - engineStartedAt,
            pixelRulesMs = SystemClock.elapsedRealtime() - engineFinishedAt,
        )
    }

    fun readRegion(
        bitmap: Bitmap,
        top: Int,
        height: Int,
        chat: ChatIdentity,
        engineName: String,
    ): ScreenSnapshot {
        val profile = profileFor(bitmap)
        val safeTop = top.coerceIn(0, bitmap.height - 1)
        val safeHeight = height.coerceIn(1, bitmap.height - safeTop)
        val crop = Bitmap.createBitmap(bitmap, 0, safeTop, bitmap.width, safeHeight)
        return try {
            val localInputY = (profile.inputAnchor.y - safeTop)
                .coerceIn(0, crop.height - 1)
            val rules = ScreenReadingRules(
                width = crop.width,
                height = crop.height,
                inputY = localInputY,
                contentTopY = (crop.height * ScreenReadingRules.CHAT_TOP).toInt(),
                minChars = 2,
                minScore = 0.5,
                watch = "",
                verticalScale = profile.verticalScale,
            )
            val engineStartedAt = SystemClock.elapsedRealtime()
            val rows = recognizeFastScaled(crop)
            val engineFinishedAt = SystemClock.elapsedRealtime()
            val pixels = BitmapPixelSource(crop)
            val sendY = ScreenPixelRules.sendButtonY(pixels, profile)
            val filtered = rules.filterRows(rows, sendY) {
                ScreenPixelRules.inkIsMessage(pixels, it, profile)
            }
            val localContentTop = (crop.height * ScreenReadingRules.CHAT_TOP).toInt()
            val localContentBottom = (
                sendY?.minus((90 * profile.verticalScale).roundToInt())
                    ?: (localInputY - (60 * profile.verticalScale).roundToInt())
                ).coerceIn(localContentTop + 1, crop.height)
            val blocks = rules.bubbleBlocks(filtered.messages)
            val detectedImages = ScreenImageRules.detect(
                pixels = pixels,
                profile = profile,
                sendButtonY = sendY,
                contentTopOverride = localContentTop,
                contentBottomOverride = localContentBottom,
            )
            val imageLikeBlocks = blocks
                .filter { ScreenPixelRules.looksLikeImage(pixels, it, profile) }
                .toSet()
            val imageRegions = detectedImages + imageLikeBlocks
                .mapNotNull { block ->
                    val box = ScreenPixelRules.bubbleBox(
                        block,
                        crop.width,
                        crop.height,
                        profile,
                    )
                    if (detectedImages.any { overlaps(box, it) }) {
                        null
                    } else {
                        ScreenImageRegion(
                            left = box[0],
                            top = box[1],
                            right = box[2] + 1,
                            bottom = box[3] + 1,
                            kind = ScreenPixelRules.bubbleKind(pixels, block),
                        )
                    }
                }
            val items = buildList {
                blocks
                    .filterNot { it in imageLikeBlocks }
                    .forEach { block ->
                        add(
                            com.local.wxbotprobe.chat.ScreenItem(
                                text = block.text,
                                kind = ScreenPixelRules.bubbleKind(pixels, block),
                                y = block.rows.first().y + safeTop,
                                xLeft = block.xLeft,
                            ),
                        )
                    }
                imageRegions.forEach { region ->
                    val encoded = ScreenImageEncoder.encode(crop, region) ?: return@forEach
                    add(
                        com.local.wxbotprobe.chat.ScreenItem(
                            text = IMAGE_PLACEHOLDER,
                            kind = region.kind,
                            y = region.top + safeTop,
                            xLeft = region.left,
                            image = encoded.bytes,
                            imageWidth = encoded.width,
                            imageHeight = encoded.height,
                            imageSha256 = encoded.sha256,
                            imageMimeType = encoded.mimeType,
                        ),
                    )
                }
            }.sortedBy { it.y }
            val inputField = ScreenPixelRules.locateField(
                pixels,
                localInputY,
                profile,
            )
                ?.let { com.local.wxbotprobe.chat.ScreenPoint(it.x, it.y + safeTop) }
            ScreenSnapshot(
                width = bitmap.width,
                height = bitmap.height,
                items = items,
                greyRows = filtered.grey.map { it.copy(y = it.y + safeTop) },
                isGroup = false,
                inputField = inputField,
                sendButtonY = sendY?.let { it + safeTop },
                ocrRowCount = rows.size,
                engine = engineName,
                chat = chat,
                ocrEngineMs = engineFinishedAt - engineStartedAt,
                pixelRulesMs = SystemClock.elapsedRealtime() - engineFinishedAt,
            )
        } finally {
            crop.recycle()
        }
    }

    private fun profileFor(bitmap: Bitmap): DeviceProfile {
        return DeviceProfiles.forImage(baseProfile, bitmap.width, bitmap.height)
    }

    private fun recognizeFastScaled(bitmap: Bitmap): List<OcrRow> {
        val scaledWidth = (bitmap.width * OCR_INPUT_SCALE).roundToInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * OCR_INPUT_SCALE).roundToInt().coerceAtLeast(1)
        val scaled = if (scaledWidth == bitmap.width && scaledHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        }
        return try {
            scaleOcrRows(
                rows = engine.recognizeFast(scaled),
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                targetWidth = scaled.width,
                targetHeight = scaled.height,
            )
        } finally {
            if (scaled !== bitmap) {
                scaled.recycle()
            }
        }
    }

    private fun overlaps(
        box: IntArray,
        region: ScreenImageRegion,
    ): Boolean {
        val left = maxOf(box[0], region.left)
        val top = maxOf(box[1], region.top)
        val right = minOf(box[2], region.right)
        val bottom = minOf(box[3], region.bottom)
        if (right <= left || bottom <= top) {
            return false
        }
        val overlap = (right - left).toLong() * (bottom - top)
        val blockArea = (
            (box[2] - box[0]).coerceAtLeast(1).toLong() *
                (box[3] - box[1]).coerceAtLeast(1)
            )
        return overlap.toDouble() / blockArea >= 0.35
    }

}
