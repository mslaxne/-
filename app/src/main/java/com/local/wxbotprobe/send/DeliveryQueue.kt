package com.local.wxbotprobe.send

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class DeliveryStatus {
    PENDING,
    PARTIAL,
    UNKNOWN,
    SENT,
    FAILED;

    companion object {
        fun from(value: String): DeliveryStatus {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING
        }
    }
}

data class Delivery(
    val id: String,
    val sourceReply: String,
    val sourceReplyFile: String,
    val chatFingerprint: Long,
    val conversationKey: String,
    val parts: List<String>,
    val nextPartIndex: Int,
    val attempts: Int,
    val nextAttemptAt: Long,
    val status: DeliveryStatus,
    val lastError: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sendInFlight: Boolean = false,
    val sendRunId: String = "",
) {
    val waiting: Boolean
        get() = status == DeliveryStatus.PENDING || status == DeliveryStatus.PARTIAL

    fun recordPartSent(index: Int, nowMillis: Long): Delivery {
        require(index == nextPartIndex) {
            "part index $index does not match next index $nextPartIndex"
        }
        val next = index + 1
        return copy(
            nextPartIndex = next,
            nextAttemptAt = 0L,
            status = if (next >= parts.size) DeliveryStatus.SENT else DeliveryStatus.PARTIAL,
            lastError = "",
            updatedAt = nowMillis,
            sendInFlight = false,
            sendRunId = "",
        )
    }

    fun recordFailure(nextIndex: Int, error: String, nowMillis: Long): Delivery {
        val newAttempts = attempts + 1
        val delay = minOf(60_000L, 3_000L * newAttempts * newAttempts)
        return copy(
            nextPartIndex = nextIndex.coerceIn(0, parts.size),
            attempts = newAttempts,
            nextAttemptAt = nowMillis + delay,
            status = when {
                newAttempts >= MAX_ATTEMPTS -> DeliveryStatus.FAILED
                nextIndex > 0 -> DeliveryStatus.PARTIAL
                else -> DeliveryStatus.PENDING
            },
            lastError = error,
            updatedAt = nowMillis,
            sendInFlight = false,
            sendRunId = "",
        )
    }

    fun markSendResultUnknown(error: String, nowMillis: Long): Delivery {
        return copy(
            nextAttemptAt = 0L,
            status = DeliveryStatus.UNKNOWN,
            lastError = error,
            updatedAt = nowMillis,
            sendInFlight = false,
            sendRunId = "",
        )
    }

    fun confirmUnknownPartSent(nowMillis: Long): Delivery {
        require(status == DeliveryStatus.UNKNOWN) {
            "only an unknown delivery can be confirmed as sent"
        }
        require(nextPartIndex in parts.indices) {
            "unknown delivery has no unsent part to confirm"
        }
        return recordPartSent(nextPartIndex, nowMillis)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}

class DeliveryStore(
    private val directory: File,
) {
    fun enqueue(
        sourceReply: String,
        sourceReplyFile: String,
        chatFingerprint: Long,
        conversationKey: String,
        parts: List<String>,
        deliveryId: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Delivery {
        require(parts.isNotEmpty()) { "delivery must contain at least one part" }
        require(chatFingerprint != 0L) { "chat fingerprint is unavailable" }
        deliveryId?.let { existing ->
            find(existing)?.let { return it }
        }
        val delivery = Delivery(
            id = deliveryId ?: "$nowMillis-${UUID.randomUUID()}",
            sourceReply = sourceReply,
            sourceReplyFile = sourceReplyFile,
            chatFingerprint = chatFingerprint,
            conversationKey = conversationKey,
            parts = parts,
            nextPartIndex = 0,
            attempts = 0,
            nextAttemptAt = 0L,
            status = DeliveryStatus.PENDING,
            lastError = "",
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        save(delivery)
        return delivery
    }

    fun find(id: String): Delivery? {
        return read(File(directory, "$id.json"))
    }

    fun all(): List<Delivery> {
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

    fun nextReady(chatFingerprint: Long, nowMillis: Long): Delivery? {
        return all().firstOrNull {
            it.waiting &&
                it.chatFingerprint == chatFingerprint &&
                it.nextAttemptAt <= nowMillis
        }
    }

    fun manualRetry(
        id: String,
        nowMillis: Long = System.currentTimeMillis(),
        acknowledgeUnknownResult: Boolean = false,
    ): Delivery? {
        val current = find(id) ?: return null
        if (current.status == DeliveryStatus.SENT) {
            return null
        }
        if (current.status == DeliveryStatus.UNKNOWN && !acknowledgeUnknownResult) {
            return null
        }
        val reset = current.copy(
            attempts = 0,
            nextAttemptAt = 0L,
            status = if (current.nextPartIndex > 0) {
                DeliveryStatus.PARTIAL
            } else {
                DeliveryStatus.PENDING
            },
            lastError = "",
            updatedAt = nowMillis,
            sendInFlight = false,
            sendRunId = "",
        )
        save(reset)
        return reset
    }

    fun remove(id: String): Boolean {
        return File(directory, "$id.json").delete()
    }

    fun beginSend(id: String, nowMillis: Long = System.currentTimeMillis()): Delivery? {
        val current = find(id) ?: return null
        if (!current.waiting) {
            return null
        }
        val updated = current.copy(
            sendInFlight = true,
            sendRunId = DeliveryProcessRun.id,
            updatedAt = nowMillis,
        )
        save(updated)
        return updated
    }

    fun recoverInterruptedSends(
        currentRunId: String = DeliveryProcessRun.id,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        var recovered = 0
        all().filter {
            it.waiting &&
                it.sendInFlight &&
                it.sendRunId != currentRunId
        }.forEach { delivery ->
            save(
                delivery.markSendResultUnknown(
                    "send result was not confirmed before process exit",
                    nowMillis,
                ),
            )
            recovered++
        }
        return recovered
    }

    fun save(delivery: Delivery) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("unable to create delivery directory")
        }
        val output = File(directory, "${delivery.id}.json")
        val temporary = File(directory, "${delivery.id}.json.tmp")
        val root = JSONObject()
            .put("id", delivery.id)
            .put("sourceReply", delivery.sourceReply)
            .put("sourceReplyFile", delivery.sourceReplyFile)
            .put("chatFingerprint", java.lang.Long.toHexString(delivery.chatFingerprint))
            .put("conversationKey", delivery.conversationKey)
            .put("parts", JSONArray(delivery.parts))
            .put("nextPartIndex", delivery.nextPartIndex)
            .put("attempts", delivery.attempts)
            .put("nextAttemptAt", delivery.nextAttemptAt)
            .put("status", delivery.status.name.lowercase())
            .put("lastError", delivery.lastError)
            .put("createdAt", delivery.createdAt)
            .put("updatedAt", delivery.updatedAt)
            .put("sendInFlight", delivery.sendInFlight)
            .put("sendRunId", delivery.sendRunId)

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

    private fun read(file: File): Delivery? {
        if (!file.isFile) {
            return null
        }
        return runCatching {
            val root = JSONObject(file.readText())
            val partsJson = root.getJSONArray("parts")
            val parts = buildList {
                for (index in 0 until partsJson.length()) {
                    add(partsJson.getString(index))
                }
            }
            Delivery(
                id = root.getString("id"),
                sourceReply = root.optString("sourceReply"),
                sourceReplyFile = root.optString("sourceReplyFile"),
                chatFingerprint = root.optString("chatFingerprint").toLong(16),
                conversationKey = root.optString("conversationKey"),
                parts = parts,
                nextPartIndex = root.optInt("nextPartIndex").coerceIn(0, parts.size),
                attempts = root.optInt("attempts"),
                nextAttemptAt = root.optLong("nextAttemptAt"),
                status = DeliveryStatus.from(root.optString("status")),
                lastError = root.optString("lastError"),
                createdAt = root.optLong("createdAt"),
                updatedAt = root.optLong("updatedAt"),
                sendInFlight = root.optBoolean("sendInFlight"),
                sendRunId = root.optString("sendRunId"),
            )
        }.getOrNull()
    }
}

object DeliveryProcessRun {
    val id: String = UUID.randomUUID().toString()
}
