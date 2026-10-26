package com.local.wxbotprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import com.local.wxbotprobe.device.DeviceProfileResolver
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object TransportDiagnostics {
    data class ScreenshotSample(
        val index: Int,
        val success: Boolean,
        val latencyMs: Long,
        val allBlack: Boolean,
        val pssKb: Long,
        val error: String,
    )

    fun runAccessibilityCaptureBenchmark(
        context: Context,
        count: Int = 60,
        intervalMs: Long = 3_000L,
        outputDirectory: File,
        initialDelayMs: Long = 5_000L,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): File {
        require(count > 0) { "截图次数必须大于零。" }
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw IllegalStateException("无法创建基准目录。")
        }
        val shizukuTransport = ShizukuTransport()
        val accessibilityTransport = AccessibilityTransport()
        if (!shizukuTransport.isReady()) {
            throw IllegalStateException(shizukuTransport.unavailableMessage())
        }
        if (!accessibilityTransport.isReady()) {
            throw IllegalStateException(accessibilityTransport.unavailableMessage())
        }

        Thread.sleep(initialDelayMs)
        val shizukuComparison = File(outputDirectory, "route-b-comparison.png")
        val accessibilityComparison = File(outputDirectory, "route-c-comparison.png")
        shizukuTransport.screenshot(shizukuComparison)
        Thread.sleep(500L)
        accessibilityTransport.screenshot(accessibilityComparison)
        val pixelComparison = comparePixels(shizukuComparison, accessibilityComparison)

        val latest = File(outputDirectory, "route-c-latest.png")
        val samples = mutableListOf<ScreenshotSample>()
        val pssBefore = Debug.getPss()
        var pssMinimum = pssBefore
        var pssMaximum = pssBefore
        for (index in 1..count) {
            val startedAt = SystemClock.elapsedRealtime()
            var success = false
            var black = false
            var error = ""
            try {
                accessibilityTransport.screenshot(latest)
                val bitmap = BitmapFactory.decodeFile(latest.absolutePath)
                    ?: throw IllegalStateException("基准截图解码失败。")
                try {
                    black = isNearlyBlack(bitmap)
                } finally {
                    bitmap.recycle()
                }
                success = true
            } catch (failure: Throwable) {
                error = failure.message ?: failure.javaClass.simpleName
            }
            val pss = Debug.getPss()
            pssMinimum = minOf(pssMinimum, pss)
            pssMaximum = maxOf(pssMaximum, pss)
            samples += ScreenshotSample(
                index = index,
                success = success,
                latencyMs = SystemClock.elapsedRealtime() - startedAt,
                allBlack = black,
                pssKb = pss,
                error = error,
            )
            onProgress(index, count)
            val remaining = intervalMs - (SystemClock.elapsedRealtime() - startedAt)
            if (index < count && remaining > 0L) {
                Thread.sleep(remaining)
            }
        }

        val report = File(
            outputDirectory,
            "route-c-capture-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date()) +
                ".json",
        )
        writeReport(
            report = report,
            samples = samples,
            pixelComparison = pixelComparison,
            pssBefore = pssBefore,
            pssMinimum = pssMinimum,
            pssMaximum = pssMaximum,
            device = "${Build.MANUFACTURER} ${Build.MODEL} / Android " +
                "${Build.VERSION.RELEASE} / ${DeviceProfileResolver.current(context).profileId}",
        )
        return report
    }

    private data class PixelComparison(
        val width: Int,
        val height: Int,
        val equalRatio: Double,
        val meanAbsoluteError: Double,
    )

    private fun comparePixels(
        first: File,
        second: File,
    ): PixelComparison {
        val firstBitmap = BitmapFactory.decodeFile(first.absolutePath)
            ?: throw IllegalStateException("路线 B 对比截图解码失败。")
        val secondBitmap = BitmapFactory.decodeFile(second.absolutePath)
            ?: run {
                firstBitmap.recycle()
                throw IllegalStateException("路线 C 对比截图解码失败。")
            }
        try {
            if (firstBitmap.width != secondBitmap.width ||
                firstBitmap.height != secondBitmap.height
            ) {
                throw IllegalStateException(
                    "两条路线截图尺寸不同：" +
                        "${firstBitmap.width}x${firstBitmap.height} / " +
                        "${secondBitmap.width}x${secondBitmap.height}",
                )
            }
            val pixelCount = firstBitmap.width * firstBitmap.height
            val firstPixels = IntArray(pixelCount)
            val secondPixels = IntArray(pixelCount)
            firstBitmap.getPixels(
                firstPixels,
                0,
                firstBitmap.width,
                0,
                0,
                firstBitmap.width,
                firstBitmap.height,
            )
            secondBitmap.getPixels(
                secondPixels,
                0,
                secondBitmap.width,
                0,
                0,
                secondBitmap.width,
                secondBitmap.height,
            )
            var equalPixels = 0L
            var absoluteDifference = 0L
            for (index in firstPixels.indices) {
                val left = firstPixels[index]
                val right = secondPixels[index]
                if (left == right) {
                    equalPixels++
                }
                absoluteDifference += abs((left shr 16 and 0xFF) - (right shr 16 and 0xFF))
                absoluteDifference += abs((left shr 8 and 0xFF) - (right shr 8 and 0xFF))
                absoluteDifference += abs((left and 0xFF) - (right and 0xFF))
            }
            return PixelComparison(
                width = firstBitmap.width,
                height = firstBitmap.height,
                equalRatio = equalPixels.toDouble() / pixelCount,
                meanAbsoluteError =
                    absoluteDifference.toDouble() / (pixelCount * 3),
            )
        } finally {
            firstBitmap.recycle()
            secondBitmap.recycle()
        }
    }

    private fun isNearlyBlack(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(
            pixels,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height,
        )
        var sampled = 0
        var visible = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = pixels[y * bitmap.width + x]
                if (maxOf(
                        color shr 16 and 0xFF,
                        color shr 8 and 0xFF,
                        color and 0xFF,
                    ) > 24
                ) {
                    visible++
                }
                sampled++
                x += 16
            }
            y += 16
        }
        return sampled == 0 || visible * 100 < sampled
    }

    private fun writeReport(
        report: File,
        samples: List<ScreenshotSample>,
        pixelComparison: PixelComparison,
        pssBefore: Long,
        pssMinimum: Long,
        pssMaximum: Long,
        device: String,
    ) {
        val successful = samples.filter { it.success }
        val sortedLatencies = successful.map { it.latencyMs }.sorted()
        val root = JSONObject()
            .put("device", device)
            .put("transport", TransportMode.ACCESSIBILITY.name)
            .put("attempts", samples.size)
            .put("successes", successful.size)
            .put("failures", samples.size - successful.size)
            .put(
                "successRate",
                successful.size.toDouble() / samples.size,
            )
            .put("blackFrames", samples.count { it.allBlack })
            .put("blackRate", samples.count { it.allBlack }.toDouble() / samples.size)
            .put(
                "latencyMs",
                JSONObject()
                    .put("min", sortedLatencies.minOrNull() ?: -1L)
                    .put("p50", percentile(sortedLatencies, 0.50))
                    .put("p95", percentile(sortedLatencies, 0.95))
                    .put("max", sortedLatencies.maxOrNull() ?: -1L),
            )
            .put(
                "pixelComparison",
                JSONObject()
                    .put("width", pixelComparison.width)
                    .put("height", pixelComparison.height)
                    .put("equalRatio", pixelComparison.equalRatio)
                    .put("meanAbsoluteError", pixelComparison.meanAbsoluteError),
            )
            .put(
                "pssKb",
                JSONObject()
                    .put("before", pssBefore)
                    .put("min", pssMinimum)
                    .put("max", pssMaximum)
                    .put("delta", pssMaximum - pssBefore),
            )
            .put(
                "samples",
                JSONArray().apply {
                    samples.forEach { sample ->
                        put(
                            JSONObject()
                                .put("index", sample.index)
                                .put("success", sample.success)
                                .put("latencyMs", sample.latencyMs)
                                .put("allBlack", sample.allBlack)
                                .put("pssKb", sample.pssKb)
                                .put("error", sample.error),
                        )
                    }
                },
            )
        FileOutputStream(report).use {
            it.write(root.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    private fun percentile(values: List<Long>, fraction: Double): Long {
        if (values.isEmpty()) {
            return -1L
        }
        val index = ((values.size - 1) * fraction).toInt().coerceIn(values.indices)
        return values[index]
    }
}
