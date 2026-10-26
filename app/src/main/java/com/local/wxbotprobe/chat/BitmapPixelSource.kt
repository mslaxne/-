package com.local.wxbotprobe.chat

import android.graphics.Bitmap

class BitmapPixelSource(
    private val bitmap: Bitmap,
) : PixelSource {
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height

    private val pixels = IntArray(width * height).also {
        bitmap.getPixels(it, 0, width, 0, 0, width, height)
    }

    override fun colorAt(x: Int, y: Int): PixelColor {
        require(x in 0 until width && y in 0 until height) {
            "pixel coordinate ($x, $y) is outside ${width}x$height"
        }
        val argb = pixels[y * width + x]
        return PixelColor(
            red = argb shr 16 and 0xFF,
            green = argb shr 8 and 0xFF,
            blue = argb and 0xFF,
        )
    }
}
