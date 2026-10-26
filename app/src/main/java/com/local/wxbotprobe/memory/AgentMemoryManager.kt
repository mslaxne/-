package com.local.wxbotprobe.memory

import com.local.wxbotprobe.bot.ChatClient
import com.local.wxbotprobe.chat.ChatIdentity
import com.local.wxbotprobe.chat.TextRules
import java.util.UUID

private object MemorySummaryRun {
    val id: String = UUID.randomUUID().toString()
}

data class MemorySummaryOutcome(
    val sessionId: String,
    val completed: Boolean,
    val skipped: Boolean = false,
    val error: String = "",
)

class AgentMemoryManager(
    private val store: ChatMemoryStore,
) {
    private var activeSession: AgentSession? = null
    private var activeContext: MemoryContext? = null

    val chatId: String?
        get() = activeSession?.chatId

    val contextNote: String
        get() = activeContext?.note.orEmpty()

    val displayName: String?
        get() = activeSession?.displayName

    val activeSessionId: String?
        get() = activeSession?.sessionId

    fun open(
        identity: ChatIdentity,
        nowMillis: Long = System.currentTimeMillis(),
        client: ChatClient? = null,
    ): MemoryContext {
        val identityKey = TextRules.normalise(identity.title)
        require(identityKey.isNotBlank()) { "chat title is unavailable" }
        val current = activeSession
        if (current != null && current.identityKey == identityKey) {
            return activeContext ?: MemoryContext(current.chatId, current.displayName, "")
        }
        if (current != null) {
            detachActiveForSummary(nowMillis)
        }

        val (entry, memory) = store.transaction {
            val resolved = store.resolveChat(identity, nowMillis)
            val loaded = store.loadMemory(resolved)
            val pruned = store.prune(loaded, resolved.retentionDays, nowMillis)
            if (pruned != loaded) {
                store.saveMemory(pruned)
            }
            resolved to pruned
        }
        val note = MemoryContextBuilder.build(memory, nowMillis)
        val session = store.openSessions()
            .filter { it.identityKey == identityKey }
            .filter {
                it.status == AgentSession.STATUS_OPEN ||
                    it.status == AgentSession.STATUS_RECOVERED
            }
            .maxByOrNull { it.openedAt }
            ?: store.createSession(entry, nowMillis)
        activeSession = session
        activeContext = MemoryContext(entry.chatId, entry.displayName, note)
        return activeContext!!
    }

    fun recordIncoming(
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        record("user", text, "received", nowMillis)
    }

    fun recordDeliveredParts(
        parts: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        parts.filter { it.isNotBlank() }.forEach {
            record("assistant", it, "sent", nowMillis)
        }
    }

    fun close(
        nowMillis: Long = System.currentTimeMillis(),
        client: ChatClient? = null,
    ) {
        val closing = detachActiveForSummary(nowMillis) ?: return
        summarizeSession(closing.sessionId, nowMillis, client)
    }

    fun detachActiveForSummary(nowMillis: Long): AgentSession? {
        val session = activeSession ?: return null
        activeSession = null
        activeContext = null
        if (session.messages.isEmpty()) {
            store.closeSession(session, nowMillis, summaryStatus = "skipped")
            return null
        }
        val closing = session.copy(
            closedAt = nowMillis,
            status = AgentSession.STATUS_CLOSING,
            summaryStatus = "pending",
            summaryError = "",
        )
        store.saveSession(closing)
        return closing
    }

    fun summarizeSession(
        sessionId: String,
        nowMillis: Long = System.currentTimeMillis(),
        client: ChatClient? = null,
    ): MemorySummaryOutcome {
        var missing = false
        var alreadyRunning = false
        var alreadyComplete = false
        var empty = false
        val closing = store.transaction {
            val session = store.loadSession(sessionId)
            if (session == null) {
                missing = true
                return@transaction null
            }
            if (session.summaryStatus == "complete" &&
                session.status == AgentSession.STATUS_CLOSED
            ) {
                alreadyComplete = true
                return@transaction null
            }
            if (session.summaryStatus == "running" &&
                session.summaryRunId == MemorySummaryRun.id
            ) {
                alreadyRunning = true
                return@transaction null
            }
            if (session.messages.isEmpty()) {
                store.closeSession(session, nowMillis, summaryStatus = "skipped")
                empty = true
                return@transaction null
            }
            val closedAt = session.closedAt.takeIf { it > 0L } ?: nowMillis
            session.copy(
                closedAt = closedAt,
                status = AgentSession.STATUS_CLOSING,
                summaryStatus = "running",
                summaryError = "",
                summaryRunId = MemorySummaryRun.id,
            ).also(store::saveSession)
        }
        if (missing) {
            return MemorySummaryOutcome(
                sessionId = sessionId,
                completed = false,
                error = "session is missing",
            )
        }
        if (alreadyComplete) {
            return MemorySummaryOutcome(sessionId = sessionId, completed = true)
        }
        if (alreadyRunning) {
            return MemorySummaryOutcome(
                sessionId = sessionId,
                completed = false,
                skipped = true,
                error = "summary is already running",
            )
        }
        if (empty) {
            return MemorySummaryOutcome(
                sessionId = sessionId,
                completed = true,
                skipped = true,
            )
        }
        val claimed = closing ?: return MemorySummaryOutcome(
            sessionId = sessionId,
            completed = false,
            error = "unable to claim summary",
        )
        val closedAt = claimed.closedAt.takeIf { it > 0L } ?: nowMillis
        if (client == null) {
            val error = "model client is unavailable"
            markSummaryFailure(sessionId, closedAt, claimed.summaryRunId, error)
            return MemorySummaryOutcome(
                sessionId = sessionId,
                completed = false,
                error = error,
            )
        }
        return try {
            val summary = SessionSummarizer(client).summarize(claimed)
            val saved = store.saveSummary(claimed, summary, closedAt)
            if (saved == null) {
                return MemorySummaryOutcome(
                    sessionId = sessionId,
                    completed = false,
                    error = "session was removed while summarizing",
                )
            }
            MemorySummaryOutcome(sessionId = sessionId, completed = true)
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            markSummaryFailure(sessionId, closedAt, claimed.summaryRunId, message)
            MemorySummaryOutcome(
                sessionId = sessionId,
                completed = false,
                error = message,
            )
        }
    }

    private fun markSummaryFailure(
        sessionId: String,
        closedAt: Long,
        summaryRunId: String,
        error: String,
    ) {
        store.transaction {
            val current = store.loadSession(sessionId) ?: return@transaction
            if (current.summaryStatus == "complete" ||
                current.summaryRunId != summaryRunId
            ) {
                return@transaction
            }
            store.closeSession(
                current,
                closedAt,
                summaryStatus = "pending",
                summaryError = error,
                summaryRunId = "",
            )
        }
    }

    fun recoverOpenSessions(
        nowMillis: Long = System.currentTimeMillis(),
        client: ChatClient? = null,
    ) {
        val sessions = store.openSessions()
            .filter { it.sessionId != activeSession?.sessionId }
        for (session in sessions) {
            finishSession(session, nowMillis, client)
        }
    }

    private fun finishSession(
        session: AgentSession,
        nowMillis: Long,
        client: ChatClient?,
    ) {
        summarizeSession(session.sessionId, nowMillis, client)
    }

    private fun record(
        role: String,
        text: String,
        delivery: String,
        nowMillis: Long,
    ) {
        if (text.isBlank()) {
            return
        }
        val session = activeSession ?: return
        val message = SessionMessage(
            id = "m-${UUID.randomUUID()}",
            role = role,
            text = text.trim(),
            timestamp = nowMillis,
            delivery = delivery,
        )
        activeSession = store.appendMessage(session, message)
    }
}
