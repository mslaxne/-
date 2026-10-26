package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.local.wxbotprobe.chat.ReplySplitter
import com.local.wxbotprobe.chat.ScreenPoint
import com.local.wxbotprobe.send.SendPointPolicy
import com.local.wxbotprobe.send.WeChatSender
import java.io.File
import org.json.JSONObject

class DebugSendProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) {
            return
        }
        val pending = goAsync()
        val dryRun = intent.getBooleanExtra(EXTRA_DRY_RUN, false)
        val multiPart = intent.getBooleanExtra(EXTRA_MULTI_PART, false)
        val staleInputHint = intent.getBooleanExtra(EXTRA_STALE_INPUT_HINT, false)
        val unreadableEditor = intent.getBooleanExtra(EXTRA_UNREADABLE_EDITOR, false)
        val forceDynamic = intent.getBooleanExtra(EXTRA_FORCE_DYNAMIC, false)
        val text = intent.getStringExtra(EXTRA_TEXT_B64)
            ?.let { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_TEXT)
                ?.takeIf { it.isNotBlank() }
            ?: PROBE_TEXT
        Thread(
            {
                val output = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "diagnostics/send-probe-${if (dryRun) "dry-run" else "live"}.json",
                )
                output.parentFile?.mkdirs()
                val result = runCatching {
                    val sender = WeChatSender(
                        context = context.applicationContext,
                        readInputText = if (unreadableEditor) {
                            { null }
                        } else {
                            { com.local.wxbotprobe.ImeBridge.readText() }
                        },
                    )
                    if (forceDynamic) {
                        val field = WeChatSender::class.java.getDeclaredField(
                            "sendPointCalibration",
                        )
                        field.isAccessible = true
                        field.set(sender, null)
                    }
                    val profileId = if (forceDynamic) {
                        "dynamic-forced"
                    } else {
                        SendPointPolicy.resolveCurrent(context)?.profileId ?: "dynamic"
                    }
                    val parts = if (multiPart) {
                        listOf("1.0.69 两段一", "1.0.69 两段二")
                    } else {
                        ReplySplitter(3).split(text)
                    }
                    val outcome = sender.sendParts(
                        parts = parts,
                        dryRun = dryRun,
                        inputFieldHint = if (staleInputHint) {
                            ScreenPoint(100, 100)
                        } else {
                            null
                        },
                    )
                    JSONObject()
                        .put("status", if (outcome.success) "passed" else "failed")
                        .put("profile_id", profileId)
                        .put("parts", outcome.parts.size)
                        .put("input_chars", text.length)
                        .put("input_lines", text.lines().size)
                        .put(
                            "part_chars",
                            org.json.JSONArray(outcome.parts.map { it.length }),
                        )
                        .put("sent_parts", outcome.sentParts)
                        .put("dry_run", outcome.dryRun)
                        .put("cancelled", outcome.cancelled)
                        .put("result_unknown", outcome.resultUnknown)
                        .put("unreadable_editor", unreadableEditor)
                        .put("force_dynamic", forceDynamic)
                        .put("error", outcome.error)
                }
                output.writeText(
                    result.fold(
                        onSuccess = { it.toString(2) },
                        onFailure = {
                            JSONObject()
                                .put("status", "failed")
                                .put("error", it.message.orEmpty())
                                .put("stack", it.stackTraceToString())
                                .toString(2)
                        },
                    ),
                    Charsets.UTF_8,
                )
                android.util.Log.i("DebugSendProbe", output.readText())
                pending.finish()
            },
            "debug-send-probe",
        ).start()
    }

    companion object {
        const val ACTION = "com.local.wxbotprobe.DEBUG_SEND_PROBE"
        const val EXTRA_DRY_RUN = "dry_run"
        const val EXTRA_MULTI_PART = "multi_part"
        const val EXTRA_STALE_INPUT_HINT = "stale_input_hint"
        const val EXTRA_UNREADABLE_EDITOR = "unreadable_editor"
        const val EXTRA_FORCE_DYNAMIC = "force_dynamic"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TEXT_B64 = "text_b64"
        const val PROBE_TEXT = "1.0.80 校准"
    }
}
