package com.local.wxbotprobe.chat

data class PixelColor(
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    val darkest: Int = minOf(red, green, blue)
    val brightest: Int = maxOf(red, green, blue)
}

interface PixelSource {
    val width: Int
    val height: Int

    fun colorAt(x: Int, y: Int): PixelColor
}

class ArrayPixelSource(
    private val pixels: IntArray,
    override val width: Int,
    override val height: Int,
) : PixelSource {
    init {
        require(pixels.size >= width * height)
    }

    override fun colorAt(x: Int, y: Int): PixelColor {
        val argb = pixels[y * width + x]
        return PixelColor(
            red = argb shr 16 and 0xFF,
            green = argb shr 8 and 0xFF,
            blue = argb and 0xFF,
        )
    }
}
