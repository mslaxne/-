package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.send.DeliveryStatus
import com.local.wxbotprobe.send.DeliveryStore
import java.io.File
import org.json.JSONObject

class DebugDeliveryUnknownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val phase = intent?.getStringExtra(EXTRA_PHASE).orEmpty()
        Thread(
            {
                val result = runCatching {
                    when (phase) {
                        PHASE_CREATE -> create(context.applicationContext)
                        PHASE_STATUS -> status(context.applicationContext)
                        PHASE_CLEANUP -> cleanup(context.applicationContext)
                        else -> error("unknown delivery-probe phase: $phase")
                    }
                }
                val output = File(context.filesDir, OUTPUT_PATH)
                output.parentFile?.mkdirs()
                output.writeText(
                    result.fold(
                        onSuccess = { it.toString(2) },
                        onFailure = {
                            JSONObject()
                                .put("status", "failed")
                                .put("phase", phase)
                                .put("error", it.message.orEmpty())
                                .put("stack", it.stackTraceToString())
                                .toString(2)
                        },
                    ),
                    Charsets.UTF_8,
                )
                android.util.Log.i("DebugDeliveryUnknown", output.readText())
                pending.finish()
            },
            "debug-delivery-unknown",
        ).start()
    }

    private fun create(context: Context): JSONObject {
        val store = store(context)
        store.remove(FIXTURE_ID)
        val now = System.currentTimeMillis()
        val source = File(context.filesDir, SOURCE_PATH)
        source.parentFile?.mkdirs()
        source.writeText(
            JSONObject()
                .put("text", "unknown result fixture")
                .put("deliveryId", FIXTURE_ID)
                .put("deliveryStatus", "unknown")
                .toString(2),
            Charsets.UTF_8,
        )
        val queued = store.enqueue(
            sourceReply = source.absolutePath,
            sourceReplyFile = source.absolutePath,
            chatFingerprint = 0x740074L,
            conversationKey = "debug|unknown-result-fixture",
            parts = listOf("unknown fixture part 1", "unknown fixture part 2"),
            deliveryId = FIXTURE_ID,
            nowMillis = now,
        )
        store.save(
            queued.copy(
                nextPartIndex = 1,
                status = DeliveryStatus.UNKNOWN,
                lastError = "debug fixture: send result was not confirmed",
                updatedAt = now,
            ),
        )
        return status(context)
            .put("status", "created")
            .put("phase", PHASE_CREATE)
    }

    private fun status(context: Context): JSONObject {
        val delivery = store(context).find(FIXTURE_ID)
            ?: return JSONObject()
                .put("status", "absent")
                .put("phase", PHASE_STATUS)
        return JSONObject()
            .put("status", "present")
            .put("phase", PHASE_STATUS)
            .put("delivery_id", delivery.id)
            .put("delivery_status", delivery.status.name.lowercase())
            .put("next_part_index", delivery.nextPartIndex)
            .put("parts", delivery.parts.size)
            .put("last_error", delivery.lastError)
            .put(
                "next_ready",
                store(context).nextReady(delivery.chatFingerprint, Long.MAX_VALUE) != null,
            )
    }

    private fun cleanup(context: Context): JSONObject {
        val removed = store(context).remove(FIXTURE_ID)
        File(context.filesDir, SOURCE_PATH).delete()
        return JSONObject()
            .put("status", "cleaned")
            .put("phase", PHASE_CLEANUP)
            .put("removed", removed)
    }

    private fun store(context: Context): DeliveryStore {
        return DeliveryStore(
            File(context.getExternalFilesDir(null) ?: context.filesDir, "outbox"),
        )
    }

    companion object {
        const val ACTION = "com.local.wxbotprobe.DEBUG_DELIVERY_UNKNOWN"
        const val EXTRA_PHASE = "phase"
        const val PHASE_CREATE = "create"
        const val PHASE_STATUS = "status"
        const val PHASE_CLEANUP = "cleanup"
        private const val FIXTURE_ID = "debug-unknown-result-fixture"
        private const val SOURCE_PATH = "debug/unknown-result-fixture.json"
        private const val OUTPUT_PATH = "debug/delivery-unknown.json"
    }
}
