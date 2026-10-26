package com.local.wxbotprobe.send

import com.local.wxbotprobe.chat.TextRules
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

data class GuardedReply(
    val conversationKey: String,
    val normalisedText: String,
    val generationId: String,
    val acceptedAt: Long,
)

class ReplyContentGuard(
    private val file: File,
    private val duplicateWindowMillis: Long = DEFAULT_DUPLICATE_WINDOW_MS,
    private val maxEntries: Int = 100,
) {
    private val lock = Any()

    fun tryAccept(
        conversationKey: String,
        text: String,
        generationId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        return synchronized(lock) {
        val normalised = TextRules.normalise(text)
        if (normalised.isEmpty()) {
            true
        } else {
            val entries = read().toMutableList()
            val previous = entries.firstOrNull {
                it.conversationKey == conversationKey
            }
            when {
                previous?.generationId == generationId &&
                    previous.normalisedText == normalised -> true
                previous?.normalisedText == normalised &&
                    nowMillis >= previous.acceptedAt &&
                    nowMillis - previous.acceptedAt <= duplicateWindowMillis -> false
                else -> {
                    entries.removeAll { it.conversationKey == conversationKey }
                    entries += GuardedReply(
                        conversationKey = conversationKey,
                        normalisedText = normalised,
                        generationId = generationId,
                        acceptedAt = nowMillis,
                    )
                    while (entries.size > maxEntries) {
                        entries.removeAt(0)
                    }
                    write(entries)
                    true
                }
            }
        }
        }
    }

    private fun read(): List<GuardedReply> {
        if (!file.isFile) {
            return emptyList()
        }
        return runCatching {
            val root = JSONArray(file.readText())
            buildList {
                for (index in 0 until root.length()) {
                    val item = root.getJSONObject(index)
                    add(
                        GuardedReply(
                            conversationKey = item.getString("conversationKey"),
                            normalisedText = item.getString("normalisedText"),
                            generationId = item.getString("generationId"),
                            acceptedAt = item.getLong("acceptedAt"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(entries: List<GuardedReply>) {
        file.parentFile?.mkdirs()
        val root = JSONArray()
        entries.forEach { entry ->
            root.put(
                JSONObject()
                    .put("conversationKey", entry.conversationKey)
                    .put("normalisedText", entry.normalisedText)
                    .put("generationId", entry.generationId)
                    .put("acceptedAt", entry.acceptedAt),
            )
        }
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
        const val DEFAULT_DUPLICATE_WINDOW_MS = 30_000L
    }
}
