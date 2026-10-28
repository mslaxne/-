package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.chat.OutgoingConfirmationTracker
import com.local.wxbotprobe.chat.ScreenSnapshot
import com.local.wxbotprobe.chat.TextRules
import com.local.wxbotprobe.loop.DeviceActionGate
import com.local.wxbotprobe.loop.MemoryCoordinator
import com.local.wxbotprobe.loop.PollingCoordinator
import com.local.wxbotprobe.loop.SendQueueCoordinator
import com.local.wxbotprobe.send.DeliveryStatus
import com.local.wxbotprobe.send.DeliveryStore
import com.local.wxbotprobe.send.WeChatSender
import java.io.File
import org.json.JSONObject

class DebugNaturalUnknownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) {
            return
        }
        val pending = goAsync()
        val phase = intent.getStringExtra(EXTRA_PHASE).orEmpty()
        Thread(
            {
                val result = runCatching {
                    when (phase) {
                        PHASE_RUN -> runOnce(context.applicationContext)
                        PHASE_STATUS -> status(context.applicationContext)
                        PHASE_CLEANUP -> cleanup(context.applicationContext)
                        else -> error("unknown natural-unknown phase: $phase")
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
                android.util.Log.i("DebugNaturalUnknown", output.readText())
                pending.finish()
            },
            "debug-natural-unknown",
        ).start()
    }

    private fun runOnce(context: Context): JSONObject {
        val store = store(context)
        check(store.find(FIXTURE_ID) == null) {
            "fixture already exists; inspect status or clean it before rerunning"
        }
        check(
            store.all().none {
                it.waiting || it.sendInFlight || it.status == DeliveryStatus.UNKNOWN
            },
        ) {
            "outbox has an active or unreviewed delivery; refusing to mix the live probe"
        }

        val transport = DeviceTransportFactory.create(context)
        check(transport.isReady()) { transport.unavailableMessage() }
        val deviceActions = DeviceActionGate()
        val snapshot = captureFileTransferAssistant(context, transport, deviceActions)
        val fingerprint = snapshot.chat.fingerprint
        val policyTitle = snapshot.chat.title

        val now = System.currentTimeMillis()
        val source = File(context.filesDir, SOURCE_PATH)
        source.parentFile?.mkdirs()
        source.writeText(
            JSONObject()
                .put("text", PROBE_TEXT)
                .put("deliveryId", FIXTURE_ID)
                .put("deliveryStatus", "pending")
                .put("probe", "natural-unknown")
                .toString(2),
            Charsets.UTF_8,
        )
        store.enqueue(
            sourceReply = source.absolutePath,
            sourceReplyFile = source.absolutePath,
            chatFingerprint = fingerprint,
            conversationKey = "debug|natural-unknown",
            parts = listOf(PROBE_TEXT),
            deliveryId = FIXTURE_ID,
            nowMillis = now,
        )

        val memory = MemoryCoordinator(
            context = context,
            onLog = { android.util.Log.i("DebugNaturalUnknown", it) },
            onStatus = {},
            scheduleBackgroundWork = false,
        )
        try {
            val metrics = context.resources.displayMetrics
            val faultingTransport = FailAfterSendTapTransport(
                delegate = transport,
                sendXThreshold = (metrics.widthPixels * 0.72f).toInt(),
                sendYThreshold = (metrics.heightPixels * 0.70f).toInt(),
            )
            val coordinator = SendQueueCoordinator(
                context = context,
                memory = memory,
                deviceActions = deviceActions,
                outgoingConfirmation = OutgoingConfirmationTracker(),
                canAutoSend = { true },
                isSendAuthorized = { _, _ -> true },
                onDeliveredReply = { _, _, _ -> },
                onStatus = {},
                onNotification = {},
                onLog = { android.util.Log.i("DebugNaturalUnknown", it) },
                onSendStateChanged = {},
                senderFactory = {
                    WeChatSender(
                        context = context,
                        transport = faultingTransport,
                    )
                },
            )
            coordinator.drain(
                chatFingerprint = fingerprint,
                chatTitle = policyTitle,
                inputFieldHint = snapshot.inputField,
            )
            return status(context)
                .put("status", "run_complete")
                .put("phase", PHASE_RUN)
                .put("captured_title", snapshot.chat.title)
                .put("policy_test_title", policyTitle)
                .put("tap_count", faultingTransport.tapCount)
                .put("send_tap_faulted", faultingTransport.sendTapFaulted)
        } finally {
            memory.close()
        }
    }

    private fun captureFileTransferAssistant(
        context: Context,
        transport: DeviceTransport,
        deviceActions: DeviceActionGate,
    ): ScreenSnapshot {
        val polling = PollingCoordinator(
            context = context,
            transport = transport,
            deviceActions = deviceActions,
            canPoll = { true },
            onStatus = {},
            onNotification = {},
            onLog = {},
        )
        val expectedTitle = TextRules.normalise(FILE_HELPER_TITLE)
        val observations = mutableListOf<String>()
        try {
            repeat(FILE_HELPER_CAPTURE_ATTEMPTS) {
                val foreground = runCatching { transport.foregroundPackage() }
                    .getOrDefault("<unavailable>")
                if (foreground == WECHAT_PACKAGE) {
                    val snapshot = polling.capture()
                    val title = TextRules.normalise(snapshot?.chat?.title.orEmpty())
                    observations +=
                        "capture=${snapshot != null} titleLength=${title.length} " +
                        "fingerprint=${snapshot?.chat?.fingerprint ?: 0} items=" +
                        (snapshot?.items?.size ?: 0)
                    if (
                        snapshot != null &&
                        title == expectedTitle &&
                        snapshot.chat.fingerprint != 0L
                    ) {
                        return snapshot
                    }
                } else {
                    observations += "foreground=$foreground"
                }
                Thread.sleep(FILE_HELPER_CAPTURE_RETRY_MS)
            }
        } finally {
            polling.close()
        }
        error(
            "file transfer assistant was not detected; " +
                observations.takeLast(6).joinToString(" | "),
        )
    }

    private fun status(context: Context): JSONObject {
        val store = store(context)
        val delivery = store.find(FIXTURE_ID)
            ?: return JSONObject()
                .put("status", "absent")
                .put("phase", PHASE_STATUS)
                .put("outbox_size", store.all().size)
        return JSONObject()
            .put("status", "present")
            .put("phase", PHASE_STATUS)
            .put("delivery_id", delivery.id)
            .put("delivery_status", delivery.status.name.lowercase())
            .put("next_part_index", delivery.nextPartIndex)
            .put("parts", delivery.parts.size)
            .put("attempts", delivery.attempts)
            .put("send_in_flight", delivery.sendInFlight)
            .put("last_error", delivery.lastError)
            .put(
                "next_ready",
                store.nextReady(delivery.chatFingerprint, Long.MAX_VALUE) != null,
            )
            .put("outbox_size", store.all().size)
    }

    private fun cleanup(context: Context): JSONObject {
        val store = store(context)
        val delivery = store.find(FIXTURE_ID)
        val removed = delivery?.let {
            check(it.status == DeliveryStatus.UNKNOWN || it.status == DeliveryStatus.PENDING) {
                "refusing to remove an unexpected delivery status ${it.status}"
            }
            store.remove(it.id)
        } ?: false
        val sourceRemoved = File(context.filesDir, SOURCE_PATH).delete()
        return JSONObject()
            .put("status", "cleaned")
            .put("phase", PHASE_CLEANUP)
            .put("removed", removed)
            .put("source_removed", sourceRemoved)
            .put("outbox_size", store.all().size)
    }

    private fun store(context: Context): DeliveryStore {
        return DeliveryStore(
            File(context.getExternalFilesDir(null) ?: context.filesDir, "outbox"),
        )
    }

    private class FailAfterSendTapTransport(
        private val delegate: DeviceTransport,
        private val sendXThreshold: Int,
        private val sendYThreshold: Int,
    ) : DeviceTransport by delegate {
        var tapCount = 0
            private set
        var sendTapFaulted = false
            private set

        override fun tap(x: Int, y: Int) {
            tapCount++
            delegate.tap(x, y)
            if (!sendTapFaulted && x >= sendXThreshold && y >= sendYThreshold) {
                sendTapFaulted = true
                error("debug: transport lost confirmation after the send tap")
            }
        }
    }

    companion object {
        const val ACTION = "com.local.wxbotprobe.DEBUG_NATURAL_UNKNOWN"
        const val EXTRA_PHASE = "phase"
        const val PHASE_RUN = "run"
        const val PHASE_STATUS = "status"
        const val PHASE_CLEANUP = "cleanup"
        private const val FIXTURE_ID = "debug-natural-unknown-fixture"
        private const val SOURCE_PATH = "debug/natural-unknown-reply.json"
        private const val OUTPUT_PATH = "debug/natural-unknown.json"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val FILE_HELPER_TITLE = "文件传输助手"
        private const val PROBE_TEXT = "自然未知投递测试 2026-10-26"
        private const val FILE_HELPER_CAPTURE_ATTEMPTS = 20
        private const val FILE_HELPER_CAPTURE_RETRY_MS = 500L
    }
}
