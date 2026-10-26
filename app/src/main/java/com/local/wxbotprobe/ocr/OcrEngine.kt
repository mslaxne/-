package com.local.wxbotprobe.ocr

import android.graphics.Bitmap
import com.local.wxbotprobe.chat.OcrRow

interface OcrEngine : AutoCloseable {
    fun recognize(bitmap: Bitmap): List<OcrRow>

    fun recognizeFast(bitmap: Bitmap): List<OcrRow> = recognize(bitmap)
}
