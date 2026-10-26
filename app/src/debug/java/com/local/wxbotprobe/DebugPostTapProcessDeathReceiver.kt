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

class DebugPostTapProcessDeathReceiver : BroadcastReceiver() {
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
                        PHASE_PREPARE -> prepare(context.applicationContext)
                        PHASE_RECOVER -> recover(context.applicationContext)
                        PHASE_STATUS -> writeStatus(context.applicationContext)
                        PHASE_CLEANUP -> cleanup(context.applicationContext)
                        else -> error("unknown post-tap phase: $phase")
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
                android.util.Log.i("DebugPostTapDeath", output.readText())
                pending.finish()
            },
            "debug-post-tap-death",
        ).start()
    }

    private fun prepare(context: Context): JSONObject {
        val store = store(context)
        check(store.find(FIXTURE_ID) == null) {
            "fixture already exists; recover, inspect or clean it first"
        }
        check(
            store.all().none {
                it.waiting || it.sendInFlight || it.status == DeliveryStatus.UNKNOWN
            },
        ) {
            "outbox has an active or unreviewed delivery"
        }

        val transport = DeviceTransportFactory.create(context)
        check(transport.isReady()) { transport.unavailableMessage() }
        val deviceActions = DeviceActionGate()
        val snapshot = captureFileTransferAssistant(context, transport, deviceActions)
        val policyTitle = WhitelistContacts.current(context).firstOrNull()
            ?: error("the current send whitelist is empty")
        val now = System.currentTimeMillis()
        val source = File(context.filesDir, SOURCE_PATH)
        source.parentFile?.mkdirs()
        source.writeText(
            JSONObject()
                .put("text", PROBE_TEXT)
                .put("deliveryId", FIXTURE_ID)
                .put("deliveryStatus", "pending")
                .put("probe", "post-tap-process-death")
                .toString(2),
            Charsets.UTF_8,
        )
        store.enqueue(
            sourceReply = source.absolutePath,
            sourceReplyFile = source.absolutePath,
            chatFingerprint = snapshot.chat.fingerprint,
            conversationKey = "debug|post-tap-process-death",
            parts = listOf(PROBE_TEXT),
            deliveryId = FIXTURE_ID,
            nowMillis = now,
        )

        val marker = marker(context)
        marker.parentFile?.mkdirs()
        marker.delete()
        val metrics = context.resources.displayMetrics
        val blockingTransport = BlockAfterSendTapTransport(
            delegate = transport,
            store = store,
            deliveryId = FIXTURE_ID,
            marker = marker,
            sendXThreshold = (metrics.widthPixels * 0.72f).toInt(),
            sendYThreshold = (metrics.heightPixels * 0.70f).toInt(),
        )
        val memory = MemoryCoordinator(
            context = context,
            onLog = { android.util.Log.i("DebugPostTapDeath", it) },
            onStatus = {},
            scheduleBackgroundWork = false,
        )
        try {
            SendQueueCoordinator(
                context = context,
                memory = memory,
                deviceActions = deviceActions,
                outgoingConfirmation = OutgoingConfirmationTracker(),
                canAutoSend = { true },
                onDeliveredReply = { _, _, _ -> },
                onStatus = {},
                onNotification = {},
                onLog = { android.util.Log.i("DebugPostTapDeath", it) },
                onSendStateChanged = {},
                senderFactory = {
                    WeChatSender(
                        context = context,
                        transport = blockingTransport,
                    )
                },
            ).drain(
                chatFingerprint = snapshot.chat.fingerprint,
                chatTitle = policyTitle,
                inputFieldHint = snapshot.inputField,
            )
        } finally {
            memory.close()
        }
        return writeStatus(context)
            .put("status", if (blockingTransport.markerWritten) {
                "blocked_after_send_tap"
            } else {
                "drain_returned_without_block"
            })
            .put("captured_title", snapshot.chat.title)
            .put("policy_test_title", policyTitle)
    }

    private fun recover(context: Context): JSONObject {
        val store = store(context)
        val before = store.find(FIXTURE_ID)
            ?: error("post-tap fixture is absent")
        check(before.sendInFlight) {
            "fixture is not marked in flight; status=${before.status}"
        }
        val recovered = store.recoverInterruptedSends()
        return writeStatus(context)
            .put("status", "recovered")
            .put("recovered_count", recovered)
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
            repeat(CAPTURE_ATTEMPTS) {
                val foreground = runCatching { transport.foregroundPackage() }
                    .getOrDefault("<unavailable>")
                if (foreground == WECHAT_PACKAGE) {
                    val snapshot = polling.capture()
                    val title = TextRules.normalise(snapshot?.chat?.title.orEmpty())
                    observations +=
                        "capture=${snapshot != null} titleLength=${title.length} " +
                        "fingerprint=${snapshot?.chat?.fingerprint ?: 0}"
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
                Thread.sleep(CAPTURE_RETRY_MS)
            }
        } finally {
            polling.close()
        }
        error(
            "file transfer assistant was not detected; " +
                observations.takeLast(6).joinToString(" | "),
        )
    }

    private fun writeStatus(context: Context): JSONObject {
        val store = store(context)
        val delivery = store.find(FIXTURE_ID)
            ?: return JSONObject()
                .put("status", "absent")
                .put("outbox_size", store.all().size)
        return JSONObject()
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
            .put("marker_present", marker(context).isFile)
            .put("outbox_size", store.all().size)
    }

    private fun cleanup(context: Context): JSONObject {
        val store = store(context)
        val delivery = store.find(FIXTURE_ID)
        val removed = delivery?.let {
            check(it.status == DeliveryStatus.UNKNOWN) {
                "refusing to remove delivery status ${it.status}"
            }
            store.remove(it.id)
        } ?: false
        val sourceRemoved = File(context.filesDir, SOURCE_PATH).delete()
        val markerRemoved = marker(context).delete()
        return JSONObject()
            .put("status", "cleaned")
            .put("removed", removed)
            .put("source_removed", sourceRemoved)
            .put("marker_removed", markerRemoved)
            .put("outbox_size", store.all().size)
    }

    private fun marker(context: Context): File {
        return File(context.filesDir, MARKER_PATH)
    }

    private fun store(context: Context): DeliveryStore {
        return DeliveryStore(
            File(context.getExternalFilesDir(null) ?: context.filesDir, "outbox"),
        )
    }

    private class BlockAfterSendTapTransport(
        private val delegate: DeviceTransport,
        private val store: DeliveryStore,
        private val deliveryId: String,
        private val marker: File,
        private val sendXThreshold: Int,
        private val sendYThreshold: Int,
    ) : DeviceTransport by delegate {
        var markerWritten = false
            private set

        override fun tap(x: Int, y: Int) {
            val sendArea = x >= sendXThreshold && y >= sendYThreshold
            val delivery = store.find(deliveryId)
            delegate.tap(x, y)
            if (
                sendArea &&
                !markerWritten &&
                delivery?.sendInFlight == true &&
                delivery.nextPartIndex == 0 &&
                delivery.parts.size == 1
            ) {
                markerWritten = true
                marker.parentFile?.mkdirs()
                marker.writeText(
                    JSONObject()
                        .put("status", "blocked_after_send_tap")
                        .put("delivery_status", delivery.status.name.lowercase())
                        .put("next_part_index", delivery.nextPartIndex)
                        .put("send_in_flight", delivery.sendInFlight)
                        .toString(2),
                    Charsets.UTF_8,
                )
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1_000L)
                }
            }
        }
    }

    companion object {
        const val ACTION = "com.local.wxbotprobe.DEBUG_POST_TAP_PROCESS_DEATH"
        const val EXTRA_PHASE = "phase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_RECOVER = "recover"
        const val PHASE_STATUS = "status"
        const val PHASE_CLEANUP = "cleanup"
        private const val FIXTURE_ID = "debug-post-tap-death-fixture"
        private const val SOURCE_PATH = "debug/post-tap-death-reply.json"
        private const val OUTPUT_PATH = "debug/post-tap-death.json"
        private const val MARKER_PATH = "debug/post-tap-death-blocked.json"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val FILE_HELPER_TITLE = "文件传输助手"
        private const val PROBE_TEXT = "点击后进程死亡测试 20261027"
        private const val CAPTURE_ATTEMPTS = 20
        private const val CAPTURE_RETRY_MS = 500L
    }
}
