package com.local.wxbotprobe.ocr

import android.content.Context
import android.graphics.Bitmap
import com.benjaminwan.ocrlibrary.OcrEngine as RapidOcrLibraryEngine
import com.local.wxbotprobe.chat.OcrRow

class RapidOcrAndroidEngine private constructor(context: Context) : OcrEngine {
    private val engine = RapidOcrLibraryEngine(context.applicationContext)

    override fun recognize(bitmap: Bitmap): List<OcrRow> =
        recognize(bitmap, doAngle = true, mostAngle = true)

    override fun recognizeFast(bitmap: Bitmap): List<OcrRow> =
        recognize(bitmap, doAngle = false, mostAngle = false)

    private fun recognize(
        bitmap: Bitmap,
        doAngle: Boolean,
        mostAngle: Boolean,
    ): List<OcrRow> = synchronized(engine) {
        val previousDoAngle = engine.doAngle
        val previousMostAngle = engine.mostAngle
        engine.doAngle = doAngle
        engine.mostAngle = mostAngle
        try {
            val output = Bitmap.createBitmap(
                bitmap.width,
                bitmap.height,
                Bitmap.Config.ARGB_8888,
            )
            try {
                val result = engine.detect(bitmap, output, OCR_THREADS)
                try {
                    result.textBlocks
                        .map { block ->
                            val xs = block.boxPoint.map { it.x }
                            val ys = block.boxPoint.map { it.y }
                            OcrRow(
                                y = ys.min(),
                                xLeft = xs.min(),
                                xRight = xs.max(),
                                text = block.text,
                                score = block.charScores
                                    .takeIf { it.isNotEmpty() }
                                    ?.average()
                                    ?: block.boxScore.toDouble(),
                            )
                        }
                        .filter { it.text.isNotBlank() }
                        .sortedBy { it.y }
                } finally {
                    result.boxImg
                        ?.takeIf { it !== output && !it.isRecycled }
                        ?.recycle()
                }
            } finally {
                if (!output.isRecycled) {
                    output.recycle()
                }
            }
        } finally {
            engine.doAngle = previousDoAngle
            engine.mostAngle = previousMostAngle
        }
    }

    override fun close() = Unit

    companion object {
        private const val OCR_THREADS = 0

        @Volatile
        private var instance: RapidOcrAndroidEngine? = null

        fun get(context: Context): RapidOcrAndroidEngine {
            return instance ?: synchronized(this) {
                instance ?: RapidOcrAndroidEngine(context).also { instance = it }
            }
        }
    }
}
