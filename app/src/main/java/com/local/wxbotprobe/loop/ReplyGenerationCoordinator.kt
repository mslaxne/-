package com.local.wxbotprobe.loop

import android.content.Context
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.bot.ConversationStore
import com.local.wxbotprobe.bot.ChatMessage
import com.local.wxbotprobe.bot.ChatClient
import com.local.wxbotprobe.bot.IncomingMessage
import com.local.wxbotprobe.bot.PersonaData
import com.local.wxbotprobe.bot.PersonaProfileCodec
import com.local.wxbotprobe.bot.PersonaText
import com.local.wxbotprobe.bot.ReplyConfig
import com.local.wxbotprobe.bot.ReplyEngine
import com.local.wxbotprobe.bot.ReplyRetryStatus
import com.local.wxbotprobe.bot.ReplyRetryStore
import com.local.wxbotprobe.bot.ReplyRetryTask
import com.local.wxbotprobe.bot.ReplyConversationMergePolicy
import com.local.wxbotprobe.bot.RoleplayPolicyService
import com.local.wxbotprobe.chat.CompletedBurst
import com.local.wxbotprobe.chat.StartupConversationContext
import com.local.wxbotprobe.memory.v2.ReplyContextPayload
import com.local.wxbotprobe.send.DeliveryStatus
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class ReplyGenerationCoordinator(
    private val context: Context,
    private val sendQueue: SendQueueCoordinator,
    private val canAutoSend: () -> Boolean,
    private val onStatus: (String) -> Unit,
    private val onNotification: (String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val retryStore = ReplyRetryStore(File(context.filesDir, "reply-retries"))
    private var engine: ReplyEngine? = null
    private var enginePersona: String? = null
    private var apiClient: ChatClient? = null
    private val roleplayPolicies = RoleplayPolicyService(context)
    private val workLock = Any()
    private val acceptingWork = AtomicBoolean(true)

    fun beginRun() {
        synchronized(workLock) {
            acceptingWork.set(true)
        }
    }

    fun cancelPendingGeneration() {
        val removed = synchronized(workLock) {
            acceptingWork.set(false)
            retryStore.removeAll()
        }
        if (removed > 0) {
            onLog("reply pending cancelled at stop count=$removed")
        }
    }

    fun prepareBurst(
        burst: CompletedBurst,
        chatTitle: String,
        chatFingerprint: Long,
        conversationKey: String,
    ) {
        if (!acceptingWork.get() || !canAutoSend()) {
            onLog("burst ignored agent inactive")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.CHINA)
            .format(Date())
        val directory = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "inbox",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建收件目录")
        }
        val source = File(directory, "burst-$stamp.json")
        val text = burst.items.joinToString("\n") { it.text }
        val root = JSONObject()
            .put("receivedAt", System.currentTimeMillis())
            .put("isGroup", burst.isGroup)
            .put("chatTitle", chatTitle)
            .put("conversationKey", conversationKey)
            .put("chatFingerprint", java.lang.Long.toHexString(chatFingerprint))
            .put("text", text)
            .put(
                "items",
                JSONArray().apply {
                    burst.items.forEach {
                        put(
                            JSONObject()
                                .put("text", it.text)
                                .put("kind", it.kind.name.lowercase())
                                .put("y", it.y)
                                .put("xLeft", it.xLeft),
                        )
                    }
                },
            )
        FileOutputStream(source).use {
            it.write(root.toString(2).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        val task = synchronized(workLock) {
            if (!acceptingWork.get() || !canAutoSend()) {
                null
            } else {
                retryStore.enqueue(
                    sourceFile = source.absolutePath,
                    chatTitle = chatTitle,
                    chatFingerprint = chatFingerprint,
                    conversationKey = conversationKey,
                    text = text,
                    isGroup = burst.isGroup,
                )
            }
        }
        if (task == null) {
            source.delete()
            onLog("burst cancelled before reply task enqueue")
            return
        }
        updateSourceRetry(source, task)
        onStatus("连发合并完成：${burst.items.size} 条\n${source.absolutePath}")
        onNotification("收到 ${burst.items.size} 条消息，已合并")
        onLog(
            "burst items=${burst.items.size} group=${burst.isGroup} " +
                "file=${source.absolutePath} replyTask=${task.id}",
        )
    }

    fun prepareStartupConversation(
        startup: StartupConversationContext,
        chatTitle: String,
        chatFingerprint: Long,
        conversationKey: String,
        onNewConversation: () -> Unit,
    ): Boolean {
        if (!startup.shouldRespond) {
            onLog("startup context ignored: last message is not readable incoming")
            return false
        }
        if (!acceptingWork.get() || !canAutoSend()) {
            onLog("startup context ignored agent inactive")
            return false
        }
        val replyFile = replyFileFor(startup.taskId)
        val stored = readStoredReply(replyFile)
        val existingTask = retryStore.find(startup.taskId)
        val existingDelivery = sendQueue.findDelivery(startup.taskId)
        if (existingTask?.status == ReplyRetryStatus.FAILED) {
            onStatus("首屏回复生成已失败，需要人工检查后再处理。")
            onNotification("首屏回复生成失败，需人工处理")
            onLog("startup context generation failed id=${startup.snapshotId}")
            return false
        }
        when (existingDelivery?.status) {
            DeliveryStatus.SENT -> {
                onStatus("首屏回复此前已经发送，本次不再处理。")
                onLog("startup context delivery already sent id=${startup.snapshotId}")
                return false
            }
            DeliveryStatus.UNKNOWN,
            DeliveryStatus.FAILED,
            -> {
                onStatus("首屏回复需要人工检查，本次不会自动重发。")
                onNotification("首屏回复需人工处理")
                onLog(
                    "startup context delivery manual review id=${startup.snapshotId} " +
                        "status=${existingDelivery.status.name.lowercase()}",
                )
                return false
            }
            else -> Unit
        }
        if (existingDelivery != null && existingDelivery.waiting) {
            sendQueue.retargetDelivery(
                id = startup.taskId,
                chatFingerprint = chatFingerprint,
                conversationKey = conversationKey,
            )
        }
        if (existingDelivery != null && stored == null) {
            onStatus("首屏回复队列存在但正文文件缺失，需要人工检查。")
            onNotification("首屏回复需人工处理")
            onLog(
                "startup context missing reply file id=${startup.snapshotId} " +
                    "delivery=${existingDelivery.id} " +
                    "status=${existingDelivery.status.name.lowercase()}",
            )
            return false
        }
        when (StoredReplyRecoveryPolicy.decide(stored?.deliveryStatus)) {
            StoredReplyDisposition.SKIP -> {
                onStatus("首屏上下文此前已经处理，本次不再回复。")
                onLog(
                    "startup context skipped id=${startup.snapshotId} " +
                        "status=${stored?.deliveryStatus.orEmpty()}",
                )
                return false
            }
            StoredReplyDisposition.MANUAL_REVIEW -> {
                onStatus("首屏回复需要人工检查，本次不会自动重发。")
                onNotification("首屏回复需人工处理")
                onLog(
                    "startup context manual review id=${startup.snapshotId} " +
                        "status=${stored?.deliveryStatus.orEmpty()}",
                )
                return false
            }
            StoredReplyDisposition.REUSE,
            StoredReplyDisposition.ABSENT,
            -> Unit
        }

        val isNewConversation = stored == null &&
            existingDelivery == null &&
            existingTask == null
        if (isNewConversation) {
            onNewConversation()
        }
        val source = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "inbox/startup-${startup.snapshotId}.json",
        )
        writeStartupSource(
            output = source,
            startup = startup,
            chatTitle = chatTitle,
            chatFingerprint = chatFingerprint,
            conversationKey = conversationKey,
        )
        val task = synchronized(workLock) {
            if (!acceptingWork.get() || !canAutoSend()) {
                null
            } else {
                retryStore.enqueue(
                    sourceFile = source.absolutePath,
                    chatTitle = chatTitle,
                    chatFingerprint = chatFingerprint,
                    conversationKey = conversationKey,
                    text = startup.lastMessageText,
                    isGroup = startup.isGroup,
                    taskId = startup.taskId,
                )
            }
        }
        if (task == null) {
            onLog("startup context cancelled before reply task enqueue")
            return false
        }
        updateSourceRetry(source, task)
        val reused = !isNewConversation
        onStatus(
            if (reused) {
                "首屏上下文已恢复，正在复用未发送回复。"
            } else {
                "首屏上下文已读取，正在生成回复。"
            },
        )
        onNotification(if (reused) "正在恢复首屏回复" else "正在生成首屏回复")
        onLog(
            "startup context prepared id=${startup.snapshotId} " +
                "visible=${startup.messages.size} task=${task.id} reused=$reused " +
                "file=${source.absolutePath}",
        )
        return true
    }

    private fun writeStartupSource(
        output: File,
        startup: StartupConversationContext,
        chatTitle: String,
        chatFingerprint: Long,
        conversationKey: String,
    ) {
        val directory = output.parentFile
            ?: throw IllegalStateException("startup source directory is unavailable")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建首屏上下文目录")
        }
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "startup")
            .put("snapshotId", startup.snapshotId)
            .put("receivedAt", System.currentTimeMillis())
            .put("isGroup", startup.isGroup)
            .put("chatTitle", chatTitle)
            .put("conversationKey", conversationKey)
            .put("chatFingerprint", java.lang.Long.toHexString(chatFingerprint))
            .put("text", startup.lastMessageText)
            .put(
                "items",
                JSONArray().apply {
                    startup.messages.forEach {
                        put(
                            JSONObject()
                                .put("role", it.role)
                                .put("text", it.text),
                        )
                    }
                },
            )
        val temporary = File(directory, "${output.name}.tmp")
        FileOutputStream(temporary).use {
            it.write(root.toString(2).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            output.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun readStartupPrompt(source: File): StartupPrompt? {
        if (!source.isFile) {
            return null
        }
        val root = runCatching { JSONObject(source.readText()) }.getOrNull()
            ?: throw IllegalStateException("首屏上下文文件无法解析：${source.absolutePath}")
        if (root.optString("kind") != "startup") {
            return null
        }
        if (root.optInt("schemaVersion", 0) != 1) {
            throw IllegalStateException("首屏上下文版本不支持：${source.absolutePath}")
        }
        val snapshotId = root.optString("snapshotId")
        if (snapshotId.isBlank()) {
            throw IllegalStateException("首屏上下文缺少 snapshotId：${source.absolutePath}")
        }
        val items = root.optJSONArray("items")
            ?: throw IllegalStateException("首屏上下文缺少消息：${source.absolutePath}")
        val messages = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val role = item.optString("role")
                val text = item.optString("text")
                if (role !in setOf("user", "assistant") || text.isBlank()) {
                    continue
                }
                add(ChatMessage(role, text))
            }
        }
        if (messages.isEmpty() || messages.last().role != "user") {
            throw IllegalStateException("首屏上下文消息顺序无效：${source.absolutePath}")
        }
        return StartupPrompt(snapshotId = snapshotId, messages = messages)
    }

    fun drainRetries(
        chatTitle: String,
        chatFingerprint: Long,
        conversationKey: String,
        conversationId: String,
        replyContext: ReplyContextPayload,
        contactId: String,
        personaId: String,
        persona: PersonaData,
    ) {
        val task = retryStore.nextReady(
            chatFingerprint,
            System.currentTimeMillis(),
        ) ?: return
        onNotification(
            if (task.attempts == 0) {
                "正在生成回复"
            } else {
                "正在重试回复 ${task.attempts + 1}/${ReplyRetryTask.MAX_ATTEMPTS}"
            },
        )
        processGeneration(
            text = task.text,
            isGroup = task.isGroup,
            source = File(task.sourceFile),
            chatTitle = chatTitle,
            chatFingerprint = chatFingerprint,
            conversationKey = conversationKey,
            conversationId = conversationId,
            replyContext = replyContext,
            contactId = contactId,
            personaId = personaId,
            persona = persona,
            retryTask = task,
        )
    }

    fun recordDeliveredReply(
        conversationId: String,
        text: String,
        nowMillis: Long,
    ) {
        engine?.recordDeliveredReply(
            conversationId = conversationId,
            text = text,
            nowSeconds = nowMillis / 1000.0,
        )
    }

    private fun processGeneration(
        text: String,
        isGroup: Boolean,
        source: File,
        chatTitle: String,
        chatFingerprint: Long,
        conversationKey: String,
        conversationId: String,
        replyContext: ReplyContextPayload,
        contactId: String,
        personaId: String,
        persona: PersonaData,
        retryTask: ReplyRetryTask?,
    ) {
        val generationId = retryTask?.id
            ?: "direct-${System.currentTimeMillis()}-${System.nanoTime()}"
        if (!acceptingWork.get() || !canAutoSend()) {
            retryTask?.let { retryStore.remove(it.id) }
            onLog("reply generation skipped agent inactive id=$generationId")
            return
        }
        val replyFile = replyFileFor(generationId)
        val existingReply = readStoredReply(replyFile)
        if (existingReply != null) {
            when (StoredReplyRecoveryPolicy.decide(existingReply.deliveryStatus)) {
                StoredReplyDisposition.SKIP -> {
                    if (existingReply.deliveryStatus.equals("sent", ignoreCase = true)) {
                        onStatus("回复此前已发送，恢复任务已结束。")
                        onLog("reply recovery skipped status=sent id=$generationId")
                    } else {
                        onStatus("重复回复此前已抑制，恢复任务已结束。")
                        onLog(
                            "reply recovery skipped " +
                                "status=${existingReply.deliveryStatus} id=$generationId",
                        )
                    }
                    retryTask?.let { retryStore.remove(it.id) }
                    return
                }
                StoredReplyDisposition.MANUAL_REVIEW -> {
                    onStatus(
                        "回复状态为 ${existingReply.deliveryStatus}，需要人工检查后再处理。",
                    )
                    onNotification("回复需要人工检查")
                    onLog(
                        "reply manual review status=${existingReply.deliveryStatus} " +
                            "id=$generationId file=${replyFile.absolutePath}",
                    )
                    retryTask?.let { retryStore.remove(it.id) }
                    return
                }
                StoredReplyDisposition.REUSE -> {
                    onStatus("正在恢复已生成的回复，不再重复调用模型。")
                    onLog("reply reused id=$generationId file=${replyFile.absolutePath}")
                    deliverStoredReply(
                        stored = existingReply,
                        source = source,
                        replyFile = replyFile,
                        generationId = generationId,
                        chatTitle = chatTitle,
                        chatFingerprint = chatFingerprint,
                        conversationId = conversationId,
                    )
                    retryTask?.let { retryStore.remove(it.id) }
                    return
                }
                StoredReplyDisposition.ABSENT -> Unit
            }
        }
        val startupPrompt = readStartupPrompt(source)

        val engine = replyEngine(persona, contactId, personaId)
        if (engine == null) {
            if (retryTask == null) {
                enqueueRetry(
                    text = text,
                    isGroup = isGroup,
                    source = source,
                    chatTitle = chatTitle,
                    chatFingerprint = chatFingerprint,
                    conversationKey = conversationKey,
                    error = MISSING_API_KEY_ERROR,
                    countAttempt = false,
                )
            } else if (retryTask.lastError != MISSING_API_KEY_ERROR) {
                val waiting = retryTask.copy(
                    lastError = MISSING_API_KEY_ERROR,
                    updatedAt = System.currentTimeMillis(),
                )
                retryStore.save(waiting)
                updateSourceRetry(source, waiting)
                onStatus("回复生成等待：$MISSING_API_KEY_ERROR。")
                onNotification("回复已保存，等待完成模型配置")
                onLog(
                    "reply waiting id=${waiting.id} reason=$MISSING_API_KEY_ERROR",
                )
            }
            return
        }
        if (retryTask != null && retryTask.attempts > 0) {
            onStatus(
                "正在重试回复 ${retryTask.attempts + 1}/${ReplyRetryTask.MAX_ATTEMPTS}。",
            )
        } else {
            onNotification("正在生成回复")
        }
        val incoming = IncomingMessage(
            id = startupPrompt?.let {
                "startup-${it.snapshotId}-${retryTask?.attempts ?: 0}"
            } ?: "android-${System.currentTimeMillis()}-${retryTask?.attempts ?: 0}",
            text = text,
            isGroup = isGroup,
            senderName = "对方",
            nowSeconds = System.currentTimeMillis() / 1000.0,
        )
        val memoryContext = replyContext.memoryText
        val persistedConversation = if (replyContext.includesConversation) {
            replyContext.conversationMessages.map {
                ChatMessage(it.role, it.text)
            }
        } else {
            emptyList()
        }
        val conversationHistory = startupPrompt?.let {
            ReplyConversationMergePolicy.merge(
                persisted = persistedConversation,
                visible = it.messages,
            )
        } ?: persistedConversation.takeIf { it.isNotEmpty() }
        onLog(
            "reply context mode=${replyContext.mode.storedValue} " +
                "bytes=${replyContext.memoryText.toByteArray(Charsets.UTF_8).size} " +
                "persistedMessages=${replyContext.conversationMessages.size} " +
                "historyMessages=${conversationHistory?.size ?: 0} " +
                "startupVisibleMessages=${startupPrompt?.messages?.size ?: 0} " +
                "provider=${replyContext.providerId} " +
                "window=${replyContext.providerWindowTokens} " +
                "budgetOmitted=${replyContext.budgetOmittedMessageCount} " +
                "snapshot=${replyContext.snapshotSha256?.take(12).orEmpty()} " +
                "startup=${startupPrompt != null}",
        )
        val result = if (startupPrompt != null) {
            engine.handleColdStart(
                incoming,
                conversationId,
                memoryContext,
                conversationHistory,
                commitReply = false,
            )
        } else {
            engine.handle(
                incoming,
                conversationId,
                memoryContext,
                conversationHistory,
                commitReply = false,
            )
        }
        if (!acceptingWork.get() || !canAutoSend()) {
            retryTask?.let { retryStore.remove(it.id) }
            onLog("reply generation discarded after stop id=$generationId")
            return
        }
        if (result.budgetBlocked) {
            val message = result.error.ifBlank { "daily API budget exceeded" }
            if (retryTask == null) {
                enqueueRetry(
                    text = text,
                    isGroup = isGroup,
                    source = source,
                    chatTitle = chatTitle,
                    chatFingerprint = chatFingerprint,
                    conversationKey = conversationKey,
                    error = message,
                    countAttempt = false,
                )
            } else if (!retryTask.lastError.startsWith(BUDGET_BLOCKED_PREFIX)) {
                val waiting = retryTask.copy(
                    lastError = message,
                    updatedAt = System.currentTimeMillis(),
                )
                retryStore.save(waiting)
                updateSourceRetry(source, waiting)
                onStatus("$message 生成任务已保留。")
                onNotification("API 费用达到上限，回复任务等待")
                onLog("reply budget blocked id=${waiting.id} error=$message")
            }
            return
        }
        if (!result.sent) {
            if (result.error.isNotBlank()) {
                if (retryTask == null) {
                    enqueueRetry(
                        text = text,
                        isGroup = isGroup,
                        source = source,
                        chatTitle = chatTitle,
                        chatFingerprint = chatFingerprint,
                        conversationKey = conversationKey,
                        error = result.error,
                        countAttempt = true,
                    )
                } else {
                    recordRetryFailure(retryTask, source, result.error)
                }
            } else {
                retryTask?.let { retryStore.remove(it.id) }
                val reason = result.skippedReason.ifBlank { "未知原因" }
                onStatus("回复已跳过：$reason")
                onNotification("回复已跳过")
                onLog("reply skipped reason=$reason")
            }
            return
        }

        val generatedReply = StoredReply(
            text = result.text,
            model = result.model,
            latencyMs = result.latencyMs,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            deliveryStatus = "not_queued",
        )
        persistReplyIfAbsent(
            generationId = generationId,
            source = source,
            chatTitle = chatTitle,
            chatFingerprint = chatFingerprint,
            conversationKey = conversationKey,
            stored = generatedReply,
            replyContext = replyContext,
            startupPrompt = startupPrompt,
        )
        onStatus("回复已生成，未发送：${replyFile.absolutePath}")
        onNotification("回复已生成，等待发送")
        onLog(
            "reply generated chars=${generatedReply.text.length} " +
                "model=${generatedReply.model} latency=${generatedReply.latencyMs} " +
                "file=${replyFile.absolutePath} " +
                "generation=$generationId",
        )
        deliverStoredReply(
            stored = generatedReply,
            source = source,
            replyFile = replyFile,
            generationId = generationId,
            chatTitle = chatTitle,
            chatFingerprint = chatFingerprint,
            conversationId = conversationId,
        )
        retryTask?.let { retryStore.remove(it.id) }
    }

    private fun enqueueRetry(
        text: String,
        isGroup: Boolean,
        source: File,
        chatTitle: String,
        chatFingerprint: Long,
        conversationKey: String,
        error: String,
        countAttempt: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val queued = retryStore.enqueue(
            sourceFile = source.absolutePath,
            chatTitle = chatTitle,
            chatFingerprint = chatFingerprint,
            conversationKey = conversationKey,
            text = text,
            isGroup = isGroup,
            nowMillis = now,
        )
        val task = if (countAttempt) {
            queued.recordFailure(now, error)
        } else {
            queued.copy(lastError = error, updatedAt = now)
        }
        retryStore.save(task)
        updateSourceRetry(source, task)
        reportRetryFailure(task, error, initial = true)
    }

    private fun recordRetryFailure(
        task: ReplyRetryTask,
        source: File,
        error: String,
    ) {
        val failed = task.recordFailure(System.currentTimeMillis(), error)
        retryStore.save(failed)
        updateSourceRetry(source, failed)
        reportRetryFailure(failed, error, initial = false)
    }

    private fun reportRetryFailure(
        task: ReplyRetryTask,
        error: String,
        initial: Boolean,
    ) {
        val prefix = if (initial) "回复生成失败" else "回复重试失败"
        if (task.status == ReplyRetryStatus.FAILED) {
            val message =
                "$prefix：$error。已重试 ${task.attempts} 次，已标记失败。"
            onStatus(message)
            onNotification("回复生成失败，已停止重试")
            onLog(
                "reply retry failed id=${task.id} attempts=${task.attempts} error=$error",
            )
            return
        }
        val waitSeconds = (
            (task.nextAttemptAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L
            )
        val retryDetail = if (task.attempts == 0) {
            "等待模型配置或网络恢复"
        } else {
            "${task.attempts}/${ReplyRetryTask.MAX_ATTEMPTS} 次，${waitSeconds} 秒后重试"
        }
        val message = "$prefix：$error。$retryDetail。"
        onStatus(message)
        onNotification("回复生成失败，已加入重试")
        onLog(
            "reply retry queued id=${task.id} attempts=${task.attempts} " +
                "next=${task.nextAttemptAt} error=$error",
        )
    }

    private fun replyFileFor(generationId: String): File {
        val directory = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "replies",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建回复目录")
        }
        val safeId = generationId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(directory, "reply-$safeId.json")
    }

    private fun readStoredReply(file: File): StoredReply? {
        if (!file.isFile) {
            return null
        }
        val root = runCatching { JSONObject(file.readText()) }.getOrNull()
            ?: throw IllegalStateException("回复文件无法解析：${file.absolutePath}")
        val text = root.optString("text")
        if (text.isBlank()) {
            throw IllegalStateException("回复文件缺少正文：${file.absolutePath}")
        }
        return StoredReply(
            text = text,
            model = root.optString("model"),
            latencyMs = root.optLong("latencyMs"),
            promptTokens = root.optInt("promptTokens"),
            completionTokens = root.optInt("completionTokens"),
            deliveryStatus = root.optString("deliveryStatus", "not_queued"),
        )
    }

    private fun persistReplyIfAbsent(
        generationId: String,
        source: File,
        chatTitle: String,
        chatFingerprint: Long,
        conversationKey: String,
        stored: StoredReply,
        replyContext: ReplyContextPayload,
        startupPrompt: StartupPrompt?,
    ) {
        val output = replyFileFor(generationId)
        if (output.isFile) {
            return
        }
        val root = JSONObject()
            .put("text", stored.text)
            .put("model", stored.model)
            .put("latencyMs", stored.latencyMs)
            .put("promptTokens", stored.promptTokens)
            .put("completionTokens", stored.completionTokens)
            .put("sourceBurst", source.absolutePath)
            .put("chatTitle", chatTitle)
            .put("conversationKey", conversationKey)
            .put("chatFingerprint", java.lang.Long.toHexString(chatFingerprint))
            .put("generationId", generationId)
            .put("sent", false)
            .put("deliveryStatus", stored.deliveryStatus)
            .put("replyContextMode", replyContext.mode.storedValue)
            .put("replyContextBytes", replyContext.memoryText.toByteArray(Charsets.UTF_8).size)
            .put(
                "replyContextMessages",
                replyContext.conversationMessages.size,
            )
            .put("replyContextProvider", replyContext.providerId)
            .put("replyContextWindowTokens", replyContext.providerWindowTokens)
            .put(
                "replyContextBudgetOmittedMessages",
                replyContext.budgetOmittedMessageCount,
            )
            .put("replyContextSnapshot", replyContext.snapshotSha256.orEmpty())
            .put("startupSnapshotId", startupPrompt?.snapshotId.orEmpty())
            .put("startupVisibleMessages", startupPrompt?.messages?.size ?: 0)
        val temporary = File(output.parentFile, "${output.name}.tmp")
        FileOutputStream(temporary).use {
            it.write(root.toString(2).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            output.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun deliverStoredReply(
        stored: StoredReply,
        source: File,
        replyFile: File,
        generationId: String,
        chatTitle: String,
        chatFingerprint: Long,
        conversationId: String,
    ) {
        if (!canAutoSend()) {
            onStatus("代理已暂停或停止，回复仅保存，不会自动发送。")
            onNotification("回复已保存，代理当前不发送")
            return
        }
        if (chatFingerprint != 0L) {
            sendQueue.enqueue(
                replyFile = replyFile,
                replyText = stored.text,
                chatFingerprint = chatFingerprint,
                conversationKey = conversationId,
                chatTitle = chatTitle,
                deliveryId = generationId,
            )
        } else {
            onStatus("聊天标题不可识别，已拒绝自动发送。")
            onNotification("聊天标题不可识别，已拒绝自动发送")
            onLog(
                "delivery refused: chat fingerprint unavailable " +
                    "generation=$generationId source=${source.absolutePath}",
            )
        }
    }

    private fun updateSourceRetry(file: File, task: ReplyRetryTask) {
        if (!file.isFile) {
            return
        }
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        root.put("replyRetryId", task.id)
        root.put("replyRetryStatus", task.status.name.lowercase())
        root.put("replyRetryAttempts", task.attempts)
        root.put("replyRetryNextAt", task.nextAttemptAt)
        root.put("replyRetryLastError", task.lastError)
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

    private fun replyEngine(
        persona: PersonaData,
        contactId: String,
        personaId: String,
    ): ReplyEngine? {
        val key = "$contactId|$personaId|${PersonaProfileCodec.encode(persona)}"
        if (engine != null && enginePersona == key) {
            return engine
        }
        val client = modelClient() ?: return null
        val config = ReplyConfig(
            systemPrompt = PersonaText.systemPrompt(persona),
            personaMessages = PersonaText.turns(persona.examples),
            temperature = 1.0,
            maxTokens = 500,
            historyTurns = 0,
            minReplyInterval = 2.0,
            maxReplyChars = 200,
            identityQuestionPolicy = persona.identityQuestionPolicy,
            roleplayPolicyProvider = {
                roleplayPolicies.effectivePolicy(
                    contactId = contactId,
                    personaId = personaId,
                    persona = persona,
                )
            },
        )
        return ReplyEngine(
            config = config,
            client = client,
            store = ConversationStore(config.historyTurns),
        ).also {
            engine = it
            enginePersona = key
        }
    }

    private fun modelClient(): ChatClient? {
        apiClient?.let { return it }
        return ModelClientFactory.create(context)?.also { apiClient = it }
    }

    private data class StoredReply(
        val text: String,
        val model: String,
        val latencyMs: Long,
        val promptTokens: Int,
        val completionTokens: Int,
        val deliveryStatus: String,
    )

    private data class StartupPrompt(
        val snapshotId: String,
        val messages: List<ChatMessage>,
    )

    companion object {
        private const val MISSING_API_KEY_ERROR = "模型配置不完整"
        private const val BUDGET_BLOCKED_PREFIX = "已达到每日 API 费用上限"
    }
}
