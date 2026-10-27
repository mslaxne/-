package com.local.wxbotprobe.loop

import android.content.Context
import com.local.wxbotprobe.SendSettings
import com.local.wxbotprobe.WhitelistContacts
import com.local.wxbotprobe.api.ApiUsageStore
import com.local.wxbotprobe.chat.ReplySplitter
import com.local.wxbotprobe.chat.ScreenPoint
import com.local.wxbotprobe.chat.TextRules
import com.local.wxbotprobe.chat.OutgoingConfirmationTracker
import com.local.wxbotprobe.send.AutoSendBlockReason
import com.local.wxbotprobe.send.AutoSendPolicy
import com.local.wxbotprobe.send.AutoSendPolicyInput
import com.local.wxbotprobe.send.AutoSendUsageStore
import com.local.wxbotprobe.send.Delivery
import com.local.wxbotprobe.send.DeliveryStatus
import com.local.wxbotprobe.send.DeliveryStore
import com.local.wxbotprobe.send.ReplyContentGuard
import com.local.wxbotprobe.send.WeChatSender
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Calendar
import org.json.JSONObject

class SendQueueCoordinator(
    private val context: Context,
    private val memory: MemoryCoordinator,
    private val deviceActions: DeviceActionGate,
    private val outgoingConfirmation: OutgoingConfirmationTracker,
    private val canAutoSend: () -> Boolean,
    private val onDeliveredReply: (conversationId: String, text: String, nowMillis: Long) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onNotification: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onSendStateChanged: (Boolean) -> Unit,
    private val senderFactory: () -> WeChatSender = { WeChatSender(context) },
) {
    private val store = DeliveryStore(
        File(context.getExternalFilesDir(null) ?: context.filesDir, "outbox"),
    )
    private val sendUsage = AutoSendUsageStore(context)
    private val apiUsage = ApiUsageStore(context)
    private val replyContentGuard = ReplyContentGuard(
        File(context.filesDir, "reply-content-guard.json"),
    )
    private var lastBlockReason: AutoSendBlockReason? = null
    private var lastInputFieldHint: ScreenPoint? = null

    init {
        store.recoverInterruptedSends()
    }

    fun findDelivery(id: String): Delivery? = store.find(id)

    fun retargetDelivery(
        id: String,
        chatFingerprint: Long,
        conversationKey: String,
    ): Delivery? {
        return store.retarget(
            id = id,
            chatFingerprint = chatFingerprint,
            conversationKey = conversationKey,
        )
    }

    fun enqueue(
        replyFile: File,
        replyText: String,
        chatFingerprint: Long,
        conversationKey: String,
        chatTitle: String,
        deliveryId: String? = null,
    ): Delivery? {
        val parts = ReplySplitter(3).split(replyText)
        if (parts.isEmpty()) {
            return null
        }
        deliveryId?.let { id ->
            store.find(id)?.let { existing ->
                updateReplyDelivery(
                    replyFile,
                    existing.status,
                    existing.nextPartIndex,
                    existing.parts.size,
                    existing.lastError,
                    existing.id,
                )
                onLog("delivery reused id=${existing.id} status=${existing.status.name.lowercase()}")
                if (existing.waiting) {
                    drain(chatFingerprint, chatTitle)
                }
                return existing
            }
        }
        val generationId = deliveryId ?: "${
            System.currentTimeMillis()
        }-${System.nanoTime()}"
        if (!replyContentGuard.tryAccept(
                conversationKey = conversationKey,
                text = replyText,
                generationId = generationId,
            )
        ) {
            markReplyContentSuppressed(replyFile)
            onStatus("检测到短时间重复回复，已抑制本条发送。")
            onNotification("重复回复已抑制")
            onLog(
                "delivery suppressed reason=duplicate_content " +
                    "chat=$conversationKey generation=$generationId",
            )
            return null
        }
        val queued = store.enqueue(
            sourceReply = replyText,
            sourceReplyFile = replyFile.absolutePath,
            chatFingerprint = chatFingerprint,
            conversationKey = conversationKey,
            parts = parts,
            deliveryId = deliveryId,
        )
        updateReplyDelivery(
            replyFile,
            DeliveryStatus.PENDING,
            sentParts = 0,
            totalParts = parts.size,
            error = "",
            deliveryId = queued.id,
        )
        onLog("delivery queued parts=${parts.size} chat=$conversationKey")
        drain(chatFingerprint, chatTitle)
        return queued
    }

    fun drain(
        chatFingerprint: Long,
        chatTitle: String,
        inputFieldHint: ScreenPoint? = null,
    ) {
        deviceActions.run {
            drainLocked(chatFingerprint, chatTitle, inputFieldHint)
        }
    }

    private fun drainLocked(
        chatFingerprint: Long,
        chatTitle: String,
        inputFieldHint: ScreenPoint?,
    ) {
        if (inputFieldHint != null) {
            lastInputFieldHint = inputFieldHint
        }
        if (!SendSettings.autoSend(context)) {
            lastBlockReason = null
            return
        }
        if (!canAutoSend() || chatFingerprint == 0L) {
            return
        }
        repeat(MAX_DELIVERIES_PER_POLL) {
            if (!canAutoSend()) {
                return
            }
            val pending = store.nextReady(
                chatFingerprint,
                System.currentTimeMillis(),
            ) ?: return
            val remainingParts = pending.parts.size - pending.nextPartIndex
            val blocked = policyBlock(chatTitle, remainingParts)
            if (blocked != null) {
                if (blocked != lastBlockReason) {
                    lastBlockReason = blocked
                    val message = AutoSendPolicy.message(blocked)
                    onStatus(message)
                    onNotification("自动发送已暂停")
                    onLog(
                        "delivery policy block reason=${blocked.name} " +
                            "chatFp=${java.lang.Long.toHexString(chatFingerprint)} " +
                            "titleLength=${chatTitle.length}",
                    )
                }
                return
            }
            lastBlockReason = null
            onNotification(
                "正在恢复发送 ${pending.nextPartIndex + 1}/${pending.parts.size}",
            )
            onLog(
                "send start id=${pending.id} " +
                    "part=${pending.nextPartIndex + 1}/${pending.parts.size} " +
                    "inputHint=${lastInputFieldHint != null}",
            )
            onSendStateChanged(true)
            val outcome = try {
                senderFactory().sendParts(
                    parts = pending.parts,
                    dryRun = false,
                    startIndex = pending.nextPartIndex,
                    shouldContinue = canAutoSend,
                    inputFieldHint = lastInputFieldHint,
                    onTiming = { stage, elapsedMs ->
                        onLog(
                            "send timing id=${pending.id} stage=$stage " +
                                "elapsedMs=$elapsedMs",
                        )
                    },
                    beforeSendPart = { index ->
                        store.beginSend(pending.id)
                            ?: throw IllegalStateException(
                                "delivery was removed or is no longer sendable",
                            )
                        outgoingConfirmation.expect(
                            token = "${pending.id}:$index",
                            deliveryId = pending.id,
                            partIndex = index,
                            chatFingerprint = pending.chatFingerprint,
                            text = pending.parts[index],
                            tappedAtMillis = System.currentTimeMillis(),
                        )
                    },
                ) { index ->
                    val current = store.find(pending.id) ?: pending
                    val nowMillis = System.currentTimeMillis()
                    val updated = current.recordPartSent(index, nowMillis)
                    store.save(updated)
                    sendUsage.increment(nowMillis)
                    memory.recordDeliveredParts(
                        listOf(updated.parts[index]),
                        nowMillis,
                    )
                    onDeliveredReply(
                        memory.chatId ?: updated.conversationKey,
                        updated.parts[index],
                        nowMillis,
                    )
                    updateReplyDelivery(
                        File(updated.sourceReplyFile),
                        updated.status,
                        updated.nextPartIndex,
                        updated.parts.size,
                        error = "",
                        deliveryId = updated.id,
                    )
                    onLog(
                        "delivery part sent id=${updated.id} " +
                            "part=${index + 1}/${updated.parts.size} " +
                            "sinceQueueMs=${nowMillis - updated.createdAt}",
                    )
                }
            } finally {
                onSendStateChanged(false)
            }

            if (outcome.cancelled) {
                onStatus("代理已暂停，发送队列已保留。")
                onNotification("代理已暂停，发送队列已保留")
                onLog("delivery cancelled by agent state id=${pending.id}")
                return
            }
            val now = System.currentTimeMillis()
            val latest = store.find(pending.id) ?: pending
            if (outcome.resultUnknown) {
                val error = outcome.error.ifBlank { "send result was not confirmed" }
                val unknown = latest.markSendResultUnknown(error, now)
                store.save(unknown)
                updateReplyDelivery(
                    File(unknown.sourceReplyFile),
                    unknown.status,
                    unknown.nextPartIndex,
                    unknown.parts.size,
                    error = unknown.lastError,
                    deliveryId = unknown.id,
                )
                val message = "发送结果未知：$error，请检查聊天后手动处理"
                onStatus(message)
                onNotification("发送结果未知")
                onLog(
                    "delivery unknown id=${unknown.id} " +
                        "sent=${unknown.nextPartIndex} error=$error",
                )
                return
            } else if (outcome.success) {
                val complete = if (latest.status == DeliveryStatus.SENT) {
                    latest
                } else {
                    latest.copy(
                        nextPartIndex = latest.parts.size,
                        nextAttemptAt = 0L,
                        status = DeliveryStatus.SENT,
                        lastError = "",
                        updatedAt = now,
                    )
                }
                store.save(complete)
                updateReplyDelivery(
                    File(complete.sourceReplyFile),
                    complete.status,
                    complete.nextPartIndex,
                    complete.parts.size,
                    error = "",
                    deliveryId = complete.id,
                )
                val message =
                    "回复发送成功：${complete.nextPartIndex}/${complete.parts.size} 条"
                onStatus(message)
                onNotification(message)
                onLog("delivery sent id=${complete.id} parts=${complete.parts.size}")
            } else {
                val error = outcome.error.ifBlank { "unknown send failure" }
                val failed = latest.recordFailure(outcome.sentParts, error, now)
                store.save(failed)
                updateReplyDelivery(
                    File(failed.sourceReplyFile),
                    failed.status,
                    failed.nextPartIndex,
                    failed.parts.size,
                    error = failed.lastError,
                    deliveryId = failed.id,
                )
                val message =
                    "回复发送失败：$error，已发送 ${failed.nextPartIndex}/${failed.parts.size} 条"
                onStatus(message)
                onNotification(message)
                onLog(
                    "delivery failed id=${failed.id} sent=${failed.nextPartIndex} " +
                        "attempts=${failed.attempts} error=$error",
                )
                return
            }
        }
    }

    private fun policyBlock(
        chatTitle: String,
        remainingParts: Int,
    ): AutoSendBlockReason? {
        val calendar = Calendar.getInstance()
        val currentMinute = calendar.get(Calendar.HOUR_OF_DAY) * 60 +
            calendar.get(Calendar.MINUTE)
        return AutoSendPolicy.check(
            AutoSendPolicyInput(
                chatTitle = TextRules.normalise(chatTitle),
                whitelist = WhitelistContacts.current(context),
                sentToday = sendUsage.sentToday(),
                remainingParts = remainingParts,
                spentUsdToday = apiUsage.spentToday(),
                dailyMessageLimit = SendSettings.dailyMessageLimit(context),
                dailyCostLimitUsd = SendSettings.dailyCostLimitUsd(context),
                quietHoursEnabled = SendSettings.quietHoursEnabled(context),
                quietStartMinute = SendSettings.quietStartMinute(context),
                quietEndMinute = SendSettings.quietEndMinute(context),
                currentMinute = currentMinute,
            ),
        )
    }

    private fun updateReplyDelivery(
        file: File,
        status: DeliveryStatus,
        sentParts: Int,
        totalParts: Int,
        error: String,
        deliveryId: String,
    ) {
        if (!file.isFile) {
            return
        }
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        root.put("sent", status == DeliveryStatus.SENT)
        root.put("deliveryId", deliveryId)
        root.put("deliveryStatus", status.name.lowercase())
        root.put("sentParts", sentParts)
        root.put("totalParts", totalParts)
        root.put("lastError", error)
        root.put("deliveryUpdatedAt", System.currentTimeMillis())
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use {
            it.write(root.toString(2).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun markReplyContentSuppressed(file: File) {
        if (!file.isFile) {
            return
        }
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        root.put("sent", false)
        root.put("deliveryStatus", "duplicate_suppressed")
        root.put("lastError", "same reply was accepted within the duplicate window")
        root.put("deliveryUpdatedAt", System.currentTimeMillis())
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use {
            it.write(root.toString(2).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        private const val MAX_DELIVERIES_PER_POLL = 5
    }
}
