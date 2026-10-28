package com.local.wxbotprobe.ocr

import android.graphics.Bitmap
import com.local.wxbotprobe.chat.ScreenImageRegion
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

data class EncodedScreenImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val sha256: String,
    val mimeType: String = "image/jpeg",
)

object ScreenImageEncoder {
    private const val MAX_EDGE_PX = 1024
    private const val MAX_BYTES = 800_000

    fun encode(
        source: Bitmap,
        region: ScreenImageRegion,
    ): EncodedScreenImage? {
        val left = region.left.coerceIn(0, source.width - 1)
        val top = region.top.coerceIn(0, source.height - 1)
        val right = region.right.coerceIn(left + 1, source.width)
        val bottom = region.bottom.coerceIn(top + 1, source.height)
        if (right - left < 24 || bottom - top < 24) {
            return null
        }
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val allocated = mutableListOf(crop)
        var current = crop
        try {
            scaleDown(current, MAX_EDGE_PX).let {
                if (it !== current) {
                    allocated += it
                    current = it
                }
            }
            var bytes = compress(current, 82)
            if (bytes.size > MAX_BYTES) {
                bytes = compress(current, 68)
            }
            if (bytes.size > MAX_BYTES) {
                scaleDown(current, 768).let {
                    if (it !== current) {
                        allocated += it
                        current = it
                    }
                }
                bytes = compress(current, 72)
            }
            if (bytes.size > MAX_BYTES) {
                scaleDown(current, 512).let {
                    if (it !== current) {
                        allocated += it
                        current = it
                    }
                }
                bytes = compress(current, 70)
            }
            if (bytes.size > MAX_BYTES) {
                return null
            }
            return EncodedScreenImage(
                bytes = bytes,
                width = current.width,
                height = current.height,
                sha256 = sha256(bytes),
            )
        } finally {
            allocated.asReversed().distinct().forEach {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maximumEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maximumEdge) {
            return bitmap
        }
        val scale = maximumEdge.toFloat() / longest
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            "screen image compression failed"
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
