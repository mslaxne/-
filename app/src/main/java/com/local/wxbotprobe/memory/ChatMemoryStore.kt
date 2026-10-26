package com.local.wxbotprobe.memory

import com.local.wxbotprobe.chat.ChatIdentity
import com.local.wxbotprobe.chat.TextRules
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class ChatMemoryStore(
    private val root: File,
) {
    private val indexFile = File(root, "index.json")
    private val chatsDirectory = File(root, "chats")
    private val sessionsDirectory = File(root, "sessions")
    private val recapsDirectory = File(root, "recaps")

    fun <T> transaction(block: () -> T): T {
        return synchronized(storeLock, block)
    }

    fun resolveChat(
        identity: ChatIdentity,
        nowMillis: Long = System.currentTimeMillis(),
        defaultRetentionDays: Int = MemorySettings.DEFAULT_RETENTION_DAYS,
    ): ChatIndexEntry {
        val identityKey = identityKey(identity)
        val index = readIndex().toMutableMap()
        index[identityKey]?.let { entry ->
            if (File(root, entry.file).isFile) {
                return entry
            }
        }

        val chatId = UUID.randomUUID().toString()
        val relativeFile = "chats/$chatId.json"
        val entry = ChatIndexEntry(
            identityKey = identityKey,
            chatId = chatId,
            displayName = identity.title.trim(),
            file = relativeFile,
            aliases = listOf(identity.title.trim()).distinct(),
            updatedAt = nowMillis,
            retentionDays = MemorySettings.normalise(defaultRetentionDays),
            summaryRetentionMode = MemorySettings.DEFAULT_SUMMARY_RETENTION_MODE,
            summaryRetentionValue = MemorySettings.DEFAULT_SUMMARY_RETENTION_VALUE,
        )
        saveMemory(
            ChatMemory(
                chatId = chatId,
                identityKey = identityKey,
                displayName = entry.displayName,
                createdAt = nowMillis,
                updatedAt = nowMillis,
                sessions = emptyList(),
                events = emptyList(),
            ),
        )
        index[identityKey] = entry
        writeIndex(index)
        return entry
    }

    fun loadMemory(entry: ChatIndexEntry): ChatMemory {
        val file = File(root, entry.file)
        val raw = readJsonFile(file)
        if (raw != null) {
            runCatching { parseMemory(JSONObject(raw)) }.getOrNull()?.let { return it }
        }
        return ChatMemory(
            chatId = entry.chatId,
            identityKey = entry.identityKey,
            displayName = entry.displayName,
            createdAt = entry.updatedAt,
            updatedAt = entry.updatedAt,
            sessions = emptyList(),
            events = emptyList(),
        ).also(::saveMemory)
    }

    fun saveMemory(memory: ChatMemory) {
        val rootObject = JSONObject()
            .put("version", 1)
            .put("chatId", memory.chatId)
            .put("identityKey", memory.identityKey)
            .put("displayName", memory.displayName)
            .put("createdAt", memory.createdAt)
            .put("updatedAt", memory.updatedAt)
            .put("sessions", JSONArray().apply {
                memory.sessions.forEach { put(sessionDigestJson(it)) }
            })
            .put("events", JSONArray().apply {
                memory.events.forEach { put(eventJson(it)) }
            })
        writeTextAtomically(
            File(chatsDirectory, "${memory.chatId}.json"),
            rootObject.toString(2),
        )
        updateIndexTimestamp(memory)
    }

    fun prune(
        memory: ChatMemory,
        retentionDays: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): ChatMemory {
        if (retentionDays == MemorySettings.PERMANENT_DAYS) {
            return memory
        }
        val cutoff = nowMillis - retentionDays * DAY_MILLIS
        val keptSessions = memory.sessions.filter { it.closedAt >= cutoff }
        val removedSessions = memory.sessions.filterNot { it.closedAt >= cutoff }
        val keptEvents = memory.events.filter {
            MemoryRetentionPolicy.shouldKeepEvent(it, cutoff, nowMillis)
        }
        removedSessions.forEach { digest ->
            File(sessionsDirectory, "${digest.sessionId}.json").delete()
        }
        return memory.copy(
            sessions = keptSessions,
            events = keptEvents,
            updatedAt = nowMillis,
        )
    }

    fun createSession(entry: ChatIndexEntry, nowMillis: Long): AgentSession {
        val session = AgentSession(
            sessionId = UUID.randomUUID().toString(),
            chatId = entry.chatId,
            identityKey = entry.identityKey,
            displayName = entry.displayName,
            openedAt = nowMillis,
            closedAt = 0L,
            status = AgentSession.STATUS_OPEN,
            summaryStatus = "pending",
            summaryError = "",
            messages = emptyList(),
        )
        saveSession(session)
        return session
    }

    fun saveSession(session: AgentSession) {
        val rootObject = JSONObject()
            .put("version", 1)
            .put("sessionId", session.sessionId)
            .put("chatId", session.chatId)
            .put("identityKey", session.identityKey)
            .put("displayName", session.displayName)
            .put("openedAt", session.openedAt)
            .put("closedAt", session.closedAt)
            .put("status", session.status)
            .put("summaryStatus", session.summaryStatus)
            .put("summaryError", session.summaryError)
            .put("summaryRunId", session.summaryRunId)
            .put("messages", JSONArray().apply {
                session.messages.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("role", it.role)
                            .put("text", it.text)
                            .put("timestamp", it.timestamp)
                            .put("delivery", it.delivery),
                    )
                }
            })
        writeTextAtomically(
            File(sessionsDirectory, "${session.sessionId}.json"),
            rootObject.toString(2),
        )
    }

    fun loadSession(sessionId: String): AgentSession? {
        val raw = readJsonFile(File(sessionsDirectory, "$sessionId.json")) ?: return null
        return runCatching { parseSession(JSONObject(raw)) }.getOrNull()
    }

    fun openSessions(): List<AgentSession> {
        return sessionFiles()
            .mapNotNull { file ->
                runCatching { parseSession(JSONObject(file.readText())) }.getOrNull()
            }
            .filter { it.isOpen }
            .sortedBy { it.openedAt }
    }

    fun unfinishedSessions(): List<AgentSession> {
        return sessionFiles()
            .mapNotNull { file ->
                runCatching { parseSession(JSONObject(file.readText())) }.getOrNull()
            }
            .filter { it.isOpen || it.summaryStatus == "pending" }
            .sortedBy { it.openedAt }
    }

    fun awaitingSummary(): List<AgentSession> {
        return sessionFiles()
            .mapNotNull { file ->
                runCatching { parseSession(JSONObject(file.readText())) }.getOrNull()
            }
            .filter {
                it.status == AgentSession.STATUS_CLOSING ||
                    (
                        it.status == AgentSession.STATUS_CLOSED &&
                            it.summaryStatus == "pending"
                        )
            }
            .sortedBy { it.openedAt }
    }

    fun sessionMessages(sessionId: String): List<SessionMessage> {
        return loadSession(sessionId)?.messages.orEmpty()
    }

    fun appendMessage(session: AgentSession, message: SessionMessage): AgentSession {
        return transaction {
            val current = loadSession(session.sessionId) ?: return@transaction session
            val updated = current.copy(messages = current.messages + message)
            saveSession(updated)
            updated
        }
    }

    fun closeSession(
        session: AgentSession,
        nowMillis: Long,
        summaryStatus: String,
        summaryError: String = "",
        summaryRunId: String = "",
    ): AgentSession {
        val updated = session.copy(
            closedAt = nowMillis,
            status = AgentSession.STATUS_CLOSED,
            summaryStatus = summaryStatus,
            summaryError = summaryError,
            summaryRunId = summaryRunId,
        )
        saveSession(updated)
        return updated
    }

    fun saveSummary(
        session: AgentSession,
        summary: SessionSummary,
        nowMillis: Long,
    ): ChatMemory? = transaction {
        val current = loadSession(session.sessionId) ?: return@transaction null
        if (current.summaryStatus != "running" ||
            current.summaryRunId != session.summaryRunId
        ) {
            return@transaction null
        }
        val entry = readIndex().values.firstOrNull { it.chatId == current.chatId }
            ?: return@transaction null
        val memory = loadMemory(entry)
        val digest = SessionDigest(
            sessionId = current.sessionId,
            openedAt = current.openedAt,
            closedAt = nowMillis,
            summary = summary.summary,
            topics = summary.topics,
            messageCount = current.messages.size,
            summaryStatus = "complete",
        )
        val newEvents = summary.events.map { event ->
            MemoryEvent(
                id = UUID.randomUUID().toString(),
                type = event.type,
                text = event.text,
                date = event.date,
                status = event.status,
                importance = event.importance.coerceIn(0.0, 1.0),
                resolved = event.status in setOf("done", "cancelled", "declined"),
                lastMentionedAt = nowMillis,
                sourceSessionId = current.sessionId,
                sourceMessageIds = event.sourceMessageIds,
            )
        }
        val updated = memory.copy(
            sessions = (memory.sessions + digest)
                .distinctBy { it.sessionId }
                .sortedByDescending { it.closedAt },
            events = mergeEvents(memory.events, newEvents),
            updatedAt = nowMillis,
        )
        closeSession(
            current,
            nowMillis,
            summaryStatus = "complete",
            summaryError = "",
            summaryRunId = "",
        )
        val retained = prune(updated, entry.retentionDays, nowMillis)
        saveMemory(retained)
        writeRecap(current.copy(closedAt = nowMillis), summary)
        pruneSummaryRecaps(entry, nowMillis)
        retained
    }

    fun writeRecap(session: AgentSession, summary: SessionSummary) {
        val text = buildString {
            appendLine("# ${session.displayName} - 会话总结")
            appendLine()
            appendLine(summary.summary.trim())
            if (summary.topics.isNotEmpty()) {
                appendLine()
                appendLine("主题：${summary.topics.joinToString("、")}")
            }
            if (summary.events.isNotEmpty()) {
                appendLine()
                appendLine("事件与待办：")
                summary.events.forEach {
                    val date = if (it.date.isBlank()) "" else "（${it.date}）"
                    appendLine("- [${it.status}] ${it.text}$date")
                }
            }
        }
        writeTextAtomically(recapFile(session.chatId, session.sessionId, create = true), text)
        writeTextAtomically(
            recapMetadataFile(session.chatId, session.sessionId, create = true),
            JSONObject()
                .put("version", 1)
                .put("sessionId", session.sessionId)
                .put("chatId", session.chatId)
                .put("openedAt", session.openedAt)
                .put("closedAt", session.closedAt)
                .put("displayName", session.displayName)
                .put("text", text)
                .toString(2),
        )
        File(recapsDirectory, "${session.sessionId}.md").delete()
    }

    fun latestRecap(): String? {
        val latest = recapsDirectory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".md") }
            .maxByOrNull { it.lastModified() }
            ?: return null
        return readJsonFile(latest)
    }

    fun recapsFor(
        entry: ChatIndexEntry,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<SessionRecap> {
        migrateLegacyRecaps(entry)
        pruneSummaryRecaps(entry, nowMillis)
        val archived = allRecaps(entry)
        if (archived.isNotEmpty()) {
            return archived.sortedByDescending { it.closedAt }
        }
        val memory = loadMemory(entry)
        return memory.sessions
            .sortedByDescending { it.closedAt }
            .mapNotNull { digest ->
                val file = recapFile(entry.chatId, digest.sessionId, create = false)
                val text = readJsonFile(file) ?: return@mapNotNull null
                SessionRecap(
                    sessionId = digest.sessionId,
                    openedAt = digest.openedAt,
                    closedAt = digest.closedAt,
                    text = text,
                )
            }
    }

    fun setSummaryRetention(
        chatId: String,
        mode: String,
        value: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): ChatIndexEntry? {
        val option = MemorySettings.normaliseSummaryRetention(mode, value)
        val index = readIndex().toMutableMap()
        val match = index.entries.firstOrNull { it.value.chatId == chatId } ?: return null
        val updated = match.value.copy(
            summaryRetentionMode = option.mode,
            summaryRetentionValue = option.value,
            updatedAt = nowMillis,
        )
        index[match.key] = updated
        writeIndex(index)
        pruneSummaryRecaps(updated, nowMillis)
        return updated
    }

    fun allChats(): List<ChatIndexEntry> {
        return readIndex().values.sortedByDescending { it.updatedAt }
    }

    fun setRetentionDays(
        chatId: String,
        retentionDays: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): ChatIndexEntry? {
        require(retentionDays in MemorySettings.OPTIONS) {
            "unsupported retention: $retentionDays"
        }
        val index = readIndex().toMutableMap()
        val match = index.entries.firstOrNull { it.value.chatId == chatId } ?: return null
        val updated = match.value.copy(
            retentionDays = retentionDays,
            updatedAt = nowMillis,
        )
        index[match.key] = updated
        writeIndex(index)
        return updated
    }

    fun setRetentionAndPrune(
        chatId: String,
        retentionDays: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): ChatIndexEntry? {
        return transaction {
            val updated = setRetentionDays(chatId, retentionDays, nowMillis)
                ?: return@transaction null
            val memory = loadMemory(updated)
            val pruned = prune(memory, retentionDays, nowMillis)
            if (pruned != memory) {
                saveMemory(pruned)
            }
            updated
        }
    }

    fun deleteChat(chatId: String): Boolean {
        return transaction {
            val index = readIndex().toMutableMap()
            val match = index.entries.firstOrNull { it.value.chatId == chatId }
            val memory = match?.value?.let(::loadMemory)
            val sessionIds = memory?.sessions
                ?.map { it.sessionId }
                ?.toMutableSet()
                ?: mutableSetOf()

            sessionFiles().forEach { file ->
                val session = runCatching {
                    parseSession(JSONObject(file.readText()))
                }.getOrNull()
                if (session?.chatId == chatId) {
                    sessionIds += session.sessionId
                    file.delete()
                }
            }
            sessionIds.forEach { sessionId ->
                File(sessionsDirectory, "$sessionId.json").delete()
                deleteRecap(chatId, sessionId)
            }
            File(recapsDirectory, chatId).deleteRecursively()
            File(chatsDirectory, "$chatId.json").delete()
            match?.value?.file?.let { File(root, it).delete() }

            if (match != null) {
                index.remove(match.key)
                writeIndex(index)
            }
            match != null || sessionIds.isNotEmpty()
        }
    }

    fun clearAll() {
        transaction {
            if (root.exists()) {
                root.deleteRecursively()
            }
            chatsDirectory.mkdirs()
            sessionsDirectory.mkdirs()
            recapsDirectory.mkdirs()
        }
    }

    private fun updateIndexTimestamp(memory: ChatMemory) {
        val index = readIndex().toMutableMap()
        val existing = index[memory.identityKey] ?: return
        index[memory.identityKey] = existing.copy(
            displayName = memory.displayName,
            updatedAt = memory.updatedAt,
        )
        writeIndex(index)
    }

    private fun readIndex(): Map<String, ChatIndexEntry> {
        val raw = readJsonFile(indexFile) ?: return emptyMap()
        return runCatching {
            val rootObject = JSONObject(raw)
            val chats = rootObject.optJSONObject("chats") ?: JSONObject()
            buildMap {
                chats.keys().forEach { key ->
                    val item = chats.optJSONObject(key) ?: return@forEach
                    put(
                        key,
                        ChatIndexEntry(
                            identityKey = key,
                            chatId = item.getString("chatId"),
                            displayName = item.optString("displayName"),
                            file = item.getString("file"),
                            aliases = item.optJSONArray("aliases").stringList(),
                            updatedAt = item.optLong("updatedAt"),
                            retentionDays = MemorySettings.normalise(
                                item.optInt(
                                    "retentionDays",
                                    MemorySettings.DEFAULT_RETENTION_DAYS,
                                ),
                            ),
                            summaryRetentionMode = MemorySettings.normaliseSummaryRetention(
                                item.optString(
                                    "summaryRetentionMode",
                                    MemorySettings.DEFAULT_SUMMARY_RETENTION_MODE,
                                ),
                                item.optInt(
                                    "summaryRetentionValue",
                                    MemorySettings.DEFAULT_SUMMARY_RETENTION_VALUE,
                                ),
                            ).mode,
                            summaryRetentionValue = MemorySettings.normaliseSummaryRetention(
                                item.optString(
                                    "summaryRetentionMode",
                                    MemorySettings.DEFAULT_SUMMARY_RETENTION_MODE,
                                ),
                                item.optInt(
                                    "summaryRetentionValue",
                                    MemorySettings.DEFAULT_SUMMARY_RETENTION_VALUE,
                                ),
                            ).value,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeIndex(index: Map<String, ChatIndexEntry>) {
        val chats = JSONObject()
        index.forEach { (key, entry) ->
            chats.put(
                key,
                JSONObject()
                    .put("chatId", entry.chatId)
                    .put("displayName", entry.displayName)
                    .put("file", entry.file)
                    .put("aliases", JSONArray(entry.aliases))
                    .put("updatedAt", entry.updatedAt)
                    .put("retentionDays", entry.retentionDays)
                    .put("summaryRetentionMode", entry.summaryRetentionMode)
                    .put("summaryRetentionValue", entry.summaryRetentionValue),
            )
        }
        writeTextAtomically(
            indexFile,
            JSONObject().put("version", 2).put("chats", chats).toString(2),
        )
    }

    private fun sessionFiles(): List<File> {
        return sessionsDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
    }

    private fun parseMemory(rootObject: JSONObject): ChatMemory {
        return ChatMemory(
            chatId = rootObject.getString("chatId"),
            identityKey = rootObject.optString("identityKey"),
            displayName = rootObject.optString("displayName"),
            createdAt = rootObject.optLong("createdAt"),
            updatedAt = rootObject.optLong("updatedAt"),
            sessions = rootObject.optJSONArray("sessions").objects().mapNotNull { item ->
                runCatching {
                    SessionDigest(
                        sessionId = item.getString("sessionId"),
                        openedAt = item.optLong("openedAt"),
                        closedAt = item.optLong("closedAt"),
                        summary = item.optString("summary"),
                        topics = item.optJSONArray("topics").stringList(),
                        messageCount = item.optInt("messageCount"),
                        summaryStatus = item.optString("summaryStatus", "complete"),
                        summaryError = item.optString("summaryError"),
                    )
                }.getOrNull()
            },
            events = rootObject.optJSONArray("events").objects().mapNotNull { item ->
                runCatching {
                    MemoryEvent(
                        id = item.getString("id"),
                        type = item.optString("type"),
                        text = item.optString("text"),
                        date = item.optString("date"),
                        status = item.optString("status", "pending"),
                        importance = item.optDouble("importance", 0.5),
                        resolved = item.optBoolean("resolved"),
                        lastMentionedAt = item.optLong("lastMentionedAt"),
                        sourceSessionId = item.optString("sourceSessionId"),
                        sourceMessageIds = item.optJSONArray("sourceMessageIds").stringList(),
                    )
                }.getOrNull()
            },
        )
    }

    private fun parseSession(rootObject: JSONObject): AgentSession {
        return AgentSession(
            sessionId = rootObject.getString("sessionId"),
            chatId = rootObject.getString("chatId"),
            identityKey = rootObject.optString("identityKey"),
            displayName = rootObject.optString("displayName"),
            openedAt = rootObject.optLong("openedAt"),
            closedAt = rootObject.optLong("closedAt"),
            status = rootObject.optString("status", AgentSession.STATUS_OPEN),
            summaryStatus = rootObject.optString("summaryStatus", "pending"),
            summaryError = rootObject.optString("summaryError"),
            summaryRunId = rootObject.optString("summaryRunId"),
            messages = rootObject.optJSONArray("messages").objects().mapNotNull { item ->
                runCatching {
                    SessionMessage(
                        id = item.getString("id"),
                        role = item.getString("role"),
                        text = item.optString("text"),
                        timestamp = item.optLong("timestamp"),
                        delivery = item.optString("delivery"),
                    )
                }.getOrNull()
            },
        )
    }

    private fun sessionDigestJson(digest: SessionDigest): JSONObject {
        return JSONObject()
            .put("sessionId", digest.sessionId)
            .put("openedAt", digest.openedAt)
            .put("closedAt", digest.closedAt)
            .put("summary", digest.summary)
            .put("topics", JSONArray(digest.topics))
            .put("messageCount", digest.messageCount)
            .put("summaryStatus", digest.summaryStatus)
            .put("summaryError", digest.summaryError)
    }

    private fun eventJson(event: MemoryEvent): JSONObject {
        return JSONObject()
            .put("id", event.id)
            .put("type", event.type)
            .put("text", event.text)
            .put("date", event.date)
            .put("status", event.status)
            .put("importance", event.importance)
            .put("resolved", event.resolved)
            .put("lastMentionedAt", event.lastMentionedAt)
            .put("sourceSessionId", event.sourceSessionId)
            .put("sourceMessageIds", JSONArray(event.sourceMessageIds))
    }

    private fun mergeEvents(existing: List<MemoryEvent>, incoming: List<MemoryEvent>): List<MemoryEvent> {
        val result = existing.toMutableList()
        for (event in incoming) {
            val duplicateIndex = result.indexOfFirst {
                it.type == event.type &&
                    TextRules.normalise(it.text) == TextRules.normalise(event.text)
            }
            if (duplicateIndex >= 0) {
                val previous = result[duplicateIndex]
                result[duplicateIndex] = event.copy(
                    id = previous.id,
                    importance = maxOf(previous.importance, event.importance),
                    sourceMessageIds = (previous.sourceMessageIds + event.sourceMessageIds).distinct(),
                )
            } else {
                result += event
            }
        }
        return result.sortedByDescending { it.lastMentionedAt }
    }

    private fun recapFile(
        chatId: String,
        sessionId: String,
        create: Boolean,
    ): File {
        val directory = File(recapsDirectory, chatId)
        if (create && !directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, "$sessionId.md")
    }

    private fun recapMetadataFile(
        chatId: String,
        sessionId: String,
        create: Boolean,
    ): File {
        val directory = File(recapsDirectory, chatId)
        if (create && !directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, "$sessionId.json")
    }

    private fun deleteRecap(chatId: String, sessionId: String) {
        recapFile(chatId, sessionId, create = false).delete()
        recapMetadataFile(chatId, sessionId, create = false).delete()
        File(recapsDirectory, "$sessionId.md").delete()
    }

    private fun allRecaps(entry: ChatIndexEntry): List<SessionRecap> {
        val directory = File(recapsDirectory, entry.chatId)
        if (!directory.isDirectory) {
            return emptyList()
        }
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file ->
                runCatching {
                    val root = JSONObject(file.readText())
                    SessionRecap(
                        sessionId = root.getString("sessionId"),
                        openedAt = root.optLong("openedAt"),
                        closedAt = root.optLong("closedAt"),
                        text = root.optString("text"),
                    )
                }.getOrNull()
            }
            .filter { it.text.isNotBlank() }
            .toList()
    }

    private fun migrateLegacyRecaps(entry: ChatIndexEntry) {
        val memory = loadMemory(entry)
        memory.sessions.forEach { digest ->
            val metadata = recapMetadataFile(entry.chatId, digest.sessionId, create = false)
            if (metadata.isFile) {
                return@forEach
            }
            val legacy = File(recapsDirectory, "${digest.sessionId}.md")
            val text = readJsonFile(legacy) ?: return@forEach
            writeTextAtomically(
                recapMetadataFile(entry.chatId, digest.sessionId, create = true),
                JSONObject()
                    .put("version", 1)
                    .put("sessionId", digest.sessionId)
                    .put("chatId", entry.chatId)
                    .put("openedAt", digest.openedAt)
                    .put("closedAt", digest.closedAt)
                    .put("displayName", entry.displayName)
                    .put("text", text)
                    .toString(2),
            )
            writeTextAtomically(
                recapFile(entry.chatId, digest.sessionId, create = true),
                text,
            )
            legacy.delete()
        }
    }

    private fun pruneSummaryRecaps(
        entry: ChatIndexEntry,
        nowMillis: Long,
    ) {
        val option = MemorySettings.normaliseSummaryRetention(
            entry.summaryRetentionMode,
            entry.summaryRetentionValue,
        )
        if (option.mode == MemorySettings.SUMMARY_MODE_PERMANENT) {
            return
        }
        val ordered = allRecaps(entry).sortedByDescending { it.closedAt }
        val kept = when (option.mode) {
            MemorySettings.SUMMARY_MODE_COUNT ->
                ordered.take(option.value).map { it.sessionId }.toSet()
            MemorySettings.SUMMARY_MODE_DAYS -> {
                val cutoff = nowMillis - option.value * DAY_MILLIS
                ordered.filter { it.closedAt >= cutoff }
                    .map { it.sessionId }
                    .toSet()
            }
            else -> ordered.map { it.sessionId }.toSet()
        }
        ordered.filterNot { it.sessionId in kept }.forEach { digest ->
            deleteRecap(entry.chatId, digest.sessionId)
        }
    }

    private fun identityKey(identity: ChatIdentity): String {
        val normalised = TextRules.normalise(identity.title)
        require(normalised.isNotBlank()) { "chat title is unavailable" }
        return normalised
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        private val storeLock = Any()
    }
}
