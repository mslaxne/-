package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

class DebugScreenshotBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            return
        }
        val pendingResult = goAsync()
        val count = intent.getIntExtra("count", 30).coerceIn(1, 100)
        val intervalMs = intent.getLongExtra("intervalMs", 1_500L)
            .coerceAtLeast(0L)
        Thread {
            try {
                runBenchmark(context, count, intervalMs)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun runBenchmark(context: Context, count: Int, intervalMs: Long) {
        context.startService(
            Intent(context, BotLoopService::class.java)
                .setAction(BotLoopService.ACTION_PAUSE),
        )
        Thread.sleep(1_000L)

        val transport = AccessibilityTransport()
        if (!transport.isReady()) {
            ProbeStatus.write(context, "Debug 截图基准失败：无障碍服务未连接。")
            return
        }
        val directory = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "benchmarks",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            ProbeStatus.write(context, "Debug 截图基准失败：无法创建目录。")
            return
        }
        val latest = File(directory, "debug-latest.png")
        val errors = JSONArray()
        val latencies = mutableListOf<Long>()
        var successCount = 0
        var blackCount = 0
        val pssBefore = Debug.getPss()
        var pssMin = pssBefore
        var pssMax = pssBefore

        for (index in 1..count) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                transport.screenshot(latest)
                val bitmap = BitmapFactory.decodeFile(latest.absolutePath)
                    ?: throw IllegalStateException("截图解码失败")
                try {
                    if (isNearlyBlack(bitmap)) {
                        blackCount++
                    }
                } finally {
                    bitmap.recycle()
                }
                successCount++
            } catch (error: Throwable) {
                errors.put(
                    JSONObject()
                        .put("index", index)
                        .put("error", error.message ?: error.javaClass.simpleName),
                )
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            latencies += elapsed
            val pss = Debug.getPss()
            pssMin = minOf(pssMin, pss)
            pssMax = maxOf(pssMax, pss)
            ProbeStatus.write(
                context,
                "Debug 截图基准 $index/$count：${elapsed}ms。",
            )
            val remaining = intervalMs - elapsed
            if (index < count && remaining > 0L) {
                Thread.sleep(remaining)
            }
        }

        val sorted = latencies.sorted()
        val report = JSONObject()
            .put("count", count)
            .put("intervalMs", intervalMs)
            .put("successCount", successCount)
            .put("blackCount", blackCount)
            .put("errorCount", errors.length())
            .put("errors", errors)
            .put("latencyMinMs", sorted.first())
            .put("latencyP50Ms", percentile(sorted, 0.50))
            .put("latencyP95Ms", percentile(sorted, 0.95))
            .put("latencyMaxMs", sorted.last())
            .put("pssBeforeKb", pssBefore)
            .put("pssMinKb", pssMin)
            .put("pssMaxKb", pssMax)
        val output = File(directory, "debug-capture-report.json")
        FileOutputStream(output).use {
            it.write(report.toString(2).toByteArray(Charsets.UTF_8))
        }
        ProbeStatus.write(
            context,
            "Debug 截图基准完成：$successCount/$count，错误 ${errors.length()}。\n" +
                output.absolutePath,
        )
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        val index = ((sorted.size - 1) * percentile).toInt()
        return sorted[index]
    }

    private fun isNearlyBlack(bitmap: android.graphics.Bitmap): Boolean {
        var sampled = 0
        var visible = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (maxOf(
                        color shr 16 and 0xFF,
                        color shr 8 and 0xFF,
                        color and 0xFF,
                    ) > 24
                ) {
                    visible++
                }
                sampled++
                x += 24
            }
            y += 24
        }
        return sampled == 0 || visible * 100 < sampled
    }

    companion object {
        private const val ACTION = "com.local.wxbotprobe.DEBUG_SCREENSHOT_BENCHMARK"
    }
}
