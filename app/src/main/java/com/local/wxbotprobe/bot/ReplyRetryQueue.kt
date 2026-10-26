package com.local.wxbotprobe.bot

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

enum class ReplyRetryStatus {
    PENDING,
    FAILED;

    companion object {
        fun from(value: String): ReplyRetryStatus {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING
        }
    }
}

data class ReplyRetryTask(
    val id: String,
    val sourceFile: String,
    val chatTitle: String,
    val chatFingerprint: Long,
    val conversationKey: String,
    val text: String,
    val isGroup: Boolean,
    val attempts: Int,
    val nextAttemptAt: Long,
    val status: ReplyRetryStatus,
    val lastError: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val waiting: Boolean
        get() = status == ReplyRetryStatus.PENDING

    fun recordFailure(nowMillis: Long, error: String): ReplyRetryTask {
        val nextAttempts = attempts + 1
        val delay = minOf(60_000L, 5_000L * (1L shl (nextAttempts - 1).coerceIn(0, 3)))
        return copy(
            attempts = nextAttempts,
            nextAttemptAt = if (nextAttempts >= MAX_ATTEMPTS) 0L else nowMillis + delay,
            status = if (nextAttempts >= MAX_ATTEMPTS) {
                ReplyRetryStatus.FAILED
            } else {
                ReplyRetryStatus.PENDING
            },
            lastError = error,
            updatedAt = nowMillis,
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}

class ReplyRetryStore(
    private val directory: File,
) {
    fun enqueue(
        sourceFile: String,
        chatTitle: String,
        chatFingerprint: Long,
        conversationKey: String,
        text: String,
        isGroup: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): ReplyRetryTask {
        require(text.isNotBlank()) { "retry text is required" }
        val task = ReplyRetryTask(
            id = "$nowMillis-${UUID.randomUUID()}",
            sourceFile = sourceFile,
            chatTitle = chatTitle,
            chatFingerprint = chatFingerprint,
            conversationKey = conversationKey,
            text = text,
            isGroup = isGroup,
            attempts = 0,
            nextAttemptAt = 0L,
            status = ReplyRetryStatus.PENDING,
            lastError = "",
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        save(task)
        return task
    }

    fun find(id: String): ReplyRetryTask? {
        return read(File(directory, "$id.json"))
    }

    fun all(): List<ReplyRetryTask> {
        if (!directory.exists()) {
            return emptyList()
        }
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull(::read)
            .sortedBy { it.createdAt }
            .toList()
    }

    fun nextReady(chatFingerprint: Long, nowMillis: Long): ReplyRetryTask? {
        return all().firstOrNull {
            it.waiting &&
                it.chatFingerprint == chatFingerprint &&
                it.nextAttemptAt <= nowMillis
        }
    }

    fun manualRetry(id: String, nowMillis: Long = System.currentTimeMillis()): ReplyRetryTask? {
        val current = find(id) ?: return null
        val reset = current.copy(
            attempts = 0,
            nextAttemptAt = 0L,
            status = ReplyRetryStatus.PENDING,
            lastError = "",
            updatedAt = nowMillis,
        )
        save(reset)
        return reset
    }

    fun save(task: ReplyRetryTask) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("unable to create reply retry directory")
        }
        val output = File(directory, "${task.id}.json")
        val temporary = File(directory, "${task.id}.json.tmp")
        val root = JSONObject()
            .put("id", task.id)
            .put("sourceFile", task.sourceFile)
            .put("chatTitle", task.chatTitle)
            .put("chatFingerprint", java.lang.Long.toHexString(task.chatFingerprint))
            .put("conversationKey", task.conversationKey)
            .put("text", task.text)
            .put("isGroup", task.isGroup)
            .put("attempts", task.attempts)
            .put("nextAttemptAt", task.nextAttemptAt)
            .put("status", task.status.name.lowercase())
            .put("lastError", task.lastError)
            .put("createdAt", task.createdAt)
            .put("updatedAt", task.updatedAt)

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

    fun remove(id: String) {
        val file = File(directory, "$id.json")
        if (file.exists()) {
            file.delete()
        }
    }

    fun removeAll(): Int {
        if (!directory.exists()) {
            return 0
        }
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".json") }
            .count { it.delete() }
    }

    private fun read(file: File): ReplyRetryTask? {
        if (!file.isFile) {
            return null
        }
        return runCatching {
            val root = JSONObject(file.readText())
            ReplyRetryTask(
                id = root.getString("id"),
                sourceFile = root.optString("sourceFile"),
                chatTitle = root.optString("chatTitle"),
                chatFingerprint = root.optString("chatFingerprint").toLong(16),
                conversationKey = root.optString("conversationKey"),
                text = root.optString("text"),
                isGroup = root.optBoolean("isGroup"),
                attempts = root.optInt("attempts"),
                nextAttemptAt = root.optLong("nextAttemptAt"),
                status = ReplyRetryStatus.from(root.optString("status")),
                lastError = root.optString("lastError"),
                createdAt = root.optLong("createdAt"),
                updatedAt = root.optLong("updatedAt"),
            )
        }.getOrNull()
    }
}
