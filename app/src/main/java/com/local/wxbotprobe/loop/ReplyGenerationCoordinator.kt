package com.local.wxbotprobe.loop

import android.content.Context
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.bot.ConversationStore
import com.local.wxbotprobe.bot.ChatMessage
import com.local.wxbotprobe.bot.ChatClient
import com.local.wxbotprobe.bot.IncomingMessage
import com.local.wxbotprobe.bot.MemoryConflictClarificationMarker
import com.local.wxbotprobe.bot.PersonaData
import com.local.wxbotprobe.bot.PersonaProfileCodec
import com.local.wxbotprobe.bot.PersonaText
import com.local.wxbotprobe.bot.ReplyConfig
import com.local.wxbotprobe.bot.ReplyEngine
import com.local.wxbotprobe.bot.ReplyRetryStatus
import com.local.wxbotprobe.bot.ReplyRetryStore
import com.local.wxbotprobe.bot.ReplyRetryTask
import com.local.wxbotprobe.bot.RoleplayPolicyService
import com.local.wxbotprobe.chat.CompletedBurst
import com.local.wxbotprobe.memory.v2.ReplyContextPayload
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
    private val autoSendEnabled: () -> Boolean,
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
        readStoredReply(replyFile)?.let { stored ->
            when (stored.deliveryStatus) {
                "sent" -> {
                    onStatus("回复此前已发送，恢复任务已结束。")
                    onLog("reply recovery skipped status=sent id=$generationId")
                    retryTask?.let { retryStore.remove(it.id) }
                    return
                }
                "duplicate_suppressed" -> {
                    onStatus("重复回复此前已抑制，恢复任务已结束。")
                    onLog("reply recovery skipped status=duplicate_suppressed id=$generationId")
                    retryTask?.let { retryStore.remove(it.id) }
                    return
                }
                else -> {
                    onStatus("正在恢复已生成的回复，不再重复调用模型。")
                    onLog("reply reused id=$generationId file=${replyFile.absolutePath}")
                    deliverStoredReply(
                        stored = stored,
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
            }
        }

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
        onLog(
            "reply context mode=${replyContext.mode.storedValue} " +
                "bytes=${replyContext.memoryText.toByteArray(Charsets.UTF_8).size} " +
                "messages=${replyContext.conversationMessages.size} " +
                "provider=${replyContext.providerId} " +
                "window=${replyContext.providerWindowTokens} " +
                "budgetOmitted=${replyContext.budgetOmittedMessageCount} " +
                "snapshot=${replyContext.snapshotSha256?.take(12).orEmpty()}",
        )
        val result = engine.handle(
            IncomingMessage(
                id = "android-${System.currentTimeMillis()}-${retryTask?.attempts ?: 0}",
                text = text,
                isGroup = isGroup,
                senderName = "对方",
                nowSeconds = System.currentTimeMillis() / 1000.0,
            ),
            conversationId = conversationId,
            memoryContext = MemoryConflictClarificationMarker.wrap(
                replyContext.memoryText,
                replyContext.conflictClarification,
            ),
            conversationHistory = if (replyContext.includesConversation) {
                replyContext.conversationMessages.map {
                    ChatMessage(it.role, it.text)
                }
            } else {
                null
            },
            commitReply = false,
        )
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

        val stored = StoredReply(
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
            stored = stored,
            replyContext = replyContext,
        )
        onStatus("回复已生成，未发送：${replyFile.absolutePath}")
        onNotification("回复已生成，等待发送")
        onLog(
            "reply generated chars=${stored.text.length} model=${stored.model} " +
                "latency=${stored.latencyMs} file=${replyFile.absolutePath} " +
                "generation=$generationId",
        )
        deliverStoredReply(
            stored = stored,
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
        val autoSend = autoSendEnabled()
        if (autoSend && chatFingerprint != 0L) {
            sendQueue.enqueue(
                replyFile = replyFile,
                replyText = stored.text,
                chatFingerprint = chatFingerprint,
                conversationKey = conversationId,
                chatTitle = chatTitle,
                deliveryId = generationId,
            )
        } else if (autoSend) {
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

    companion object {
        private const val MISSING_API_KEY_ERROR = "模型配置不完整"
        private const val BUDGET_BLOCKED_PREFIX = "已达到每日 API 费用上限"
    }
}
