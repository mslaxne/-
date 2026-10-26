package com.local.wxbotprobe.memory

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object MemoryContextBuilder {
    fun build(
        memory: ChatMemory,
        nowMillis: Long,
        maxChars: Int = 1_500,
    ): String {
        val recentSessions = memory.sessions
            .filter { it.summary.isNotBlank() }
            .sortedByDescending { it.closedAt }
            .take(3)
        val selectedEvents = memory.events
            .asSequence()
            .sortedByDescending { score(it, nowMillis) }
            .take(8)
            .sortedByDescending { it.lastMentionedAt }
            .toList()

        if (recentSessions.isEmpty() && selectedEvents.isEmpty()) {
            return ""
        }

        val builder = StringBuilder()
        builder.appendLine("长期聊天记忆（只作为背景事实，不要逐字复述，也不要把推测当成事实）：")
        builder.appendLine("聊天对象：${memory.displayName.ifBlank { "未知" }}")
        if (recentSessions.isNotEmpty()) {
            builder.appendLine("过去几次聊天：")
            for (session in recentSessions) {
                builder.append("- ")
                builder.append(formatDate(session.closedAt))
                builder.append("：")
                builder.appendLine(session.summary.take(280))
            }
        }
        if (selectedEvents.isNotEmpty()) {
            builder.appendLine("需要记住的事件：")
            for (event in selectedEvents) {
                builder.append("- [")
                builder.append(event.status.ifBlank { "pending" })
                builder.append("] ")
                builder.append(event.text.take(180))
                if (event.date.isNotBlank()) {
                    builder.append("（")
                    builder.append(event.date)
                    builder.append("）")
                }
                builder.appendLine()
            }
        }
        return builder.toString().trim().take(maxChars)
    }

    internal fun score(event: MemoryEvent, nowMillis: Long): Double {
        val ageDays = max(0.0, (nowMillis - event.lastMentionedAt) / 86_400_000.0)
        val recency = 1.0 / (1.0 + ageDays / 7.0)
        val unresolved = if (event.resolved) 0.0 else 1.0
        return event.importance * 0.5 + min(1.0, recency) * 0.3 + unresolved * 0.2
    }

    private fun formatDate(timestamp: Long): String {
        if (timestamp <= 0L) {
            return "未知时间"
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp))
    }
}
