package com.local.wxbotprobe.memory

data class ChatIndexEntry(
    val identityKey: String,
    val chatId: String,
    val displayName: String,
    val file: String,
    val aliases: List<String>,
    val updatedAt: Long,
    val retentionDays: Int = MemorySettings.DEFAULT_RETENTION_DAYS,
    val summaryRetentionMode: String = MemorySettings.DEFAULT_SUMMARY_RETENTION_MODE,
    val summaryRetentionValue: Int = MemorySettings.DEFAULT_SUMMARY_RETENTION_VALUE,
)

data class SessionRecap(
    val sessionId: String,
    val openedAt: Long,
    val closedAt: Long,
    val text: String,
)

data class SessionDigest(
    val sessionId: String,
    val openedAt: Long,
    val closedAt: Long,
    val summary: String,
    val topics: List<String>,
    val messageCount: Int,
    val summaryStatus: String,
    val summaryError: String = "",
)

data class MemoryEvent(
    val id: String,
    val type: String,
    val text: String,
    val date: String,
    val status: String,
    val importance: Double,
    val resolved: Boolean,
    val lastMentionedAt: Long,
    val sourceSessionId: String,
    val sourceMessageIds: List<String>,
)

data class ChatMemory(
    val chatId: String,
    val identityKey: String,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sessions: List<SessionDigest>,
    val events: List<MemoryEvent>,
)

data class SessionMessage(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: Long,
    val delivery: String,
)

data class AgentSession(
    val sessionId: String,
    val chatId: String,
    val identityKey: String,
    val displayName: String,
    val openedAt: Long,
    val closedAt: Long,
    val status: String,
    val summaryStatus: String,
    val summaryError: String,
    val messages: List<SessionMessage>,
    val summaryRunId: String = "",
) {
    val isOpen: Boolean
        get() = status == STATUS_OPEN || status == STATUS_CLOSING || status == STATUS_RECOVERED

    companion object {
        const val STATUS_OPEN = "open"
        const val STATUS_CLOSING = "closing"
        const val STATUS_CLOSED = "closed"
        const val STATUS_RECOVERED = "recovered"
    }
}

data class SummaryEvent(
    val type: String,
    val text: String,
    val date: String,
    val status: String,
    val importance: Double,
    val sourceMessageIds: List<String>,
)

data class SessionSummary(
    val summary: String,
    val topics: List<String>,
    val events: List<SummaryEvent>,
)

data class MemoryContext(
    val chatId: String,
    val displayName: String,
    val note: String,
)
