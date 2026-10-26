package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import com.local.wxbotprobe.device.DeviceProfileResolver
import com.local.wxbotprobe.ocr.LocalOcrAuditor
import com.local.wxbotprobe.ocr.RapidOcrAndroidEngine
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

// Debug-only benchmark for the full production snapshot builder.
class DebugOcrBenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            return
        }
        val auditCount = intent.getIntExtra("auditCount", 0).coerceIn(0, 20)
        val pendingResult = goAsync()
        Thread {
            try {
                runBenchmark(context, auditCount)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun runBenchmark(
        context: Context,
        auditCount: Int,
    ) {
        context.startService(
            Intent(context, BotLoopService::class.java)
                .setAction(BotLoopService.ACTION_PAUSE),
        )
        Thread.sleep(1_000L)

        val screenshot = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "monitor/latest.png",
        )
        if (!screenshot.isFile) {
            ProbeStatus.write(context, "Debug OCR 基准失败：没有最新监控截图。")
            return
        }
        val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
            ?: run {
                ProbeStatus.write(context, "Debug OCR 基准失败：截图解码失败。")
                return
            }
        val inputWidth = bitmap.width
        val inputHeight = bitmap.height
        val startedAt = SystemClock.elapsedRealtime()
        val snapshot = try {
            val engine = RapidOcrAndroidEngine.get(context)
            val built = com.local.wxbotprobe.ocr.ScreenSnapshotBuilder(
                engine,
                DeviceProfileResolver.current(context),
            ).read(bitmap, "rapidocr-android-1.3.0")
            if (auditCount > 0) {
                runAuditBenchmark(context, bitmap, built.inputField?.y, auditCount)
            }
            built
        } finally {
            bitmap.recycle()
        }
        val totalMs = SystemClock.elapsedRealtime() - startedAt
        val directory = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "benchmarks",
        )
        directory.mkdirs()
        val output = File(directory, "debug-ocr-builder-report.json")
        val report = JSONObject()
            .put("totalMs", totalMs)
            .put("engineMs", snapshot.ocrEngineMs)
            .put("rulesMs", snapshot.pixelRulesMs)
            .put("rowCount", snapshot.ocrRowCount)
            .put("itemCount", snapshot.items.size)
            .put("inputWidth", inputWidth)
            .put("inputHeight", inputHeight)
            .put("pssKb", Debug.getPss())
        FileOutputStream(output).use {
            it.write(report.toString(2).toByteArray(Charsets.UTF_8))
        }
        ProbeStatus.write(
            context,
            "Debug OCR builder：${totalMs}ms，" +
                "engine=${snapshot.ocrEngineMs}ms，" +
                "rules=${snapshot.pixelRulesMs}ms。\n${output.absolutePath}",
        )
    }

    private fun runAuditBenchmark(
        context: Context,
        bitmap: android.graphics.Bitmap,
        inputY: Int?,
        count: Int,
    ) {
        val profile = DeviceProfileResolver.current(context)
        val auditor = LocalOcrAuditor(RapidOcrAndroidEngine.get(context))
        val safeInputY = inputY ?: (bitmap.height * 0.95).toInt()
        val pssBefore = Debug.getPss()
        val startedAt = SystemClock.elapsedRealtime()
        var filteredRows = 0
        repeat(count) {
            filteredRows += auditor.audit(
                bitmap = bitmap,
                inputY = safeInputY,
                baseTexts = emptyList(),
                profile = profile,
            ).filteredRows
        }
        val report = JSONObject()
            .put("count", count)
            .put("totalMs", SystemClock.elapsedRealtime() - startedAt)
            .put("filteredRows", filteredRows)
            .put("pssBeforeKb", pssBefore)
            .put("pssAfterKb", Debug.getPss())
        val directory = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "benchmarks",
        )
        directory.mkdirs()
        val output = File(directory, "debug-ocr-audit-report.json")
        FileOutputStream(output).use {
            it.write(report.toString(2).toByteArray(Charsets.UTF_8))
        }
        ProbeStatus.write(
            context,
            "Debug OCR audit：${count} 次，${report.getLong("totalMs")}ms。\n" +
                output.absolutePath,
        )
    }

    companion object {
        private const val ACTION = "com.local.wxbotprobe.DEBUG_OCR_BENCHMARK"
    }
}
