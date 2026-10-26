package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class DebugImeProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            return
        }
        val pendingResult = goAsync()
        val writeProbe = intent.getBooleanExtra("write", false)
        Thread {
            try {
                runProbe(context, writeProbe)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun runProbe(context: Context, writeProbe: Boolean) {
        val before = ImeBridge.readText()
        var committed: Boolean? = null
        var afterWrite: String? = null
        var cleared: Boolean? = null
        var afterClear: String? = null
        var error = ""
        if (writeProbe) {
            if (before?.isNotEmpty() == true) {
                error = "input draft was not empty"
            } else {
                committed = ImeBridge.commitText(PROBE_TEXT)
                Thread.sleep(800L)
                afterWrite = ImeBridge.readText()
                cleared = ImeBridge.clearText()
                Thread.sleep(500L)
                afterClear = ImeBridge.readText()
            }
        }
        val report = JSONObject()
            .put("targetPackage", ImeBridge.currentTargetPackage().orEmpty())
            .put("beforeReadable", before != null)
            .put("beforeLength", before?.length ?: -1)
            .put("writeProbe", writeProbe)
            .put("committed", committed)
            .put("afterWriteReadable", afterWrite != null)
            .put("afterWriteLength", afterWrite?.length ?: -1)
            .put("afterWriteMatches", afterWrite == PROBE_TEXT)
            .put("cleared", cleared)
            .put("afterClearReadable", afterClear != null)
            .put("afterClearLength", afterClear?.length ?: -1)
            .put("error", error)
        val directory = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "diagnostics",
        )
        directory.mkdirs()
        val output = File(directory, "ime-text-probe.json")
        FileOutputStream(output).use {
            it.write(report.toString(2).toByteArray(Charsets.UTF_8))
        }
        ProbeStatus.write(
            context,
            "IME 文本探针完成：可读=${before != null}，" +
                "写入匹配=${afterWrite == PROBE_TEXT}，" +
                "清空长度=${afterClear?.length ?: -1}。",
        )
    }

    companion object {
        const val ACTION = "com.local.wxbotprobe.DEBUG_IME_TEXT_PROBE"
        private const val PROBE_TEXT = "Handora输入测试"
    }
}
