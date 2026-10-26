package com.local.wxbotprobe.chat

data class ChatIdentity(
    val title: String,
    val fingerprint: Long,
) {
    val conversationKey: String
        get() {
            val normalisedTitle = TextRules.normalise(title).take(80)
            return "$normalisedTitle#${java.lang.Long.toHexString(fingerprint)}"
        }

    companion object {
        val EMPTY = ChatIdentity("", 0L)
    }
}

object ChatIdentityPolicy {
    fun isUsableTitle(title: String): Boolean {
        val normalized = TextRules.normalise(title)
        return normalized.isNotBlank() &&
            normalized.lowercase() !in setOf("微信", "wechat") &&
            !Regex("^微信\\d+$").matches(normalized) &&
            !Regex("^\\d{1,2}:\\d{2}$").matches(normalized) &&
            !Regex("^\\d+$").matches(normalized)
    }
}

object ChatHeaderIdentity {
    fun from(pixels: PixelSource, rows: List<OcrRow>): ChatIdentity {
        return ChatIdentity(
            title = titleFrom(rows, pixels.width, pixels.height),
            fingerprint = fingerprintFrom(pixels),
        )
    }

    fun titleFrom(rows: List<OcrRow>, width: Int, height: Int): String {
        val minY = (height * 0.035).toInt()
        val maxY = (height * 0.10).toInt()
        val minX = (width * 0.15).toInt()
        val maxX = (width * 0.85).toInt()
        val candidates = rows
            .asSequence()
            .filter { it.y in minY..maxY }
            .filter { it.xLeft >= minX && it.xRight <= maxX }
            .filter { it.score >= 0.45 }
            .map { it.text.trim() }
            .filter { it.any(Char::isLetterOrDigit) }
            .filter(ChatIdentityPolicy::isUsableTitle)
            .toList()
        if (candidates.isEmpty()) {
            return ""
        }
        val center = width / 2.0
        return candidates.minWithOrNull(
            compareBy<String> { candidate ->
                rows.first { it.text.trim() == candidate }.y
            }.thenBy { candidate ->
                val row = rows.first { it.text.trim() == candidate }
                kotlin.math.abs((row.xLeft + row.xRight) / 2.0 - center)
            }.thenByDescending { candidate ->
                rows.first { it.text.trim() == candidate }.score
            },
        ).orEmpty()
    }

    fun fingerprintFrom(pixels: PixelSource): Long {
        if (pixels.width < 100 || pixels.height < 200) {
            return 0L
        }
        val left = (pixels.width * 0.18).toInt()
        val right = (pixels.width * 0.82).toInt()
        val top = (pixels.height * 0.04).toInt()
        // Keep the crop above content that shifts when the keyboard opens.
        val bottom = (pixels.height * 0.087).toInt()
        if (right - left < 9 || bottom - top < 8) {
            return 0L
        }

        var hash = 0L
        for (row in 0 until 8) {
            val y = top + (bottom - top - 1) * row / 7
            var previous = luminance(pixels, left, y)
            for (column in 1 until 9) {
                val x = left + (right - left - 1) * column / 8
                val current = luminance(pixels, x, y)
                hash = hash shl 1
                if (previous > current) {
                    hash = hash or 1L
                }
                previous = current
            }
        }
        return hash
    }

    private fun luminance(pixels: PixelSource, x: Int, y: Int): Int {
        val color = pixels.colorAt(x, y)
        return (color.red * 299 + color.green * 587 + color.blue * 114) / 1000
    }
}
