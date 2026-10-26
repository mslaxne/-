package com.local.wxbotprobe.memory

import com.local.wxbotprobe.bot.ChatClient
import com.local.wxbotprobe.bot.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class SessionSummarizer(
    private val client: ChatClient,
) {
    fun summarize(session: AgentSession): SessionSummary {
        val messages = session.messages.filter { it.text.isNotBlank() }
        require(messages.isNotEmpty()) { "session has no messages" }
        val transcript = renderTranscript(messages)
        val prepared = if (transcript.length <= MAX_RAW_CHARS) {
            transcript
        } else {
            compactLongTranscript(session, messages)
        }
        val completion = client.chat(
            summaryMessages(session, prepared, SYSTEM_PROMPT),
            temperature = 0.2,
            maxTokens = PRIMARY_MAX_TOKENS,
        )
        return try {
            parseSummary(completion.text)
        } catch (firstError: Exception) {
            val compactCompletion = client.chat(
                summaryMessages(session, prepared, COMPACT_SYSTEM_PROMPT),
                temperature = 0.1,
                maxTokens = COMPACT_MAX_TOKENS,
            )
            try {
                parseSummary(compactCompletion.text)
            } catch (secondError: Exception) {
                throw IllegalStateException(
                    "summary response was invalid after compact retry",
                    secondError,
                )
            }
        }
    }

    private fun summaryMessages(
        session: AgentSession,
        prepared: String,
        systemPrompt: String,
    ): List<ChatMessage> {
        return listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage(
                "user",
                "聊天对象：${session.displayName}\n" +
                    "日期：${formatDate(session.closedAt)}\n\n$prepared",
            ),
        )
    }

    private fun compactLongTranscript(
        session: AgentSession,
        messages: List<SessionMessage>,
    ): String {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (message in messages) {
            val line = renderLine(message) + "\n"
            if (current.length + line.length > CHUNK_CHARS && current.isNotEmpty()) {
                chunks += current.toString()
                current.clear()
            }
            current.append(line)
        }
        if (current.isNotEmpty()) {
            chunks += current.toString()
        }
        val compacted = chunks.mapIndexed { index, chunk ->
            val completion = client.chat(
                listOf(
                    ChatMessage(
                        "system",
                        "把这段聊天压缩成简洁事实、邀约、待办、承诺和未解决问题。" +
                            "保留绝对日期与消息编号，不评价，不补全没有说过的信息。",
                    ),
                    ChatMessage(
                        "user",
                        "聊天对象：${session.displayName}\n第 ${index + 1}/${chunks.size} 段\n\n$chunk",
                    ),
                ),
                temperature = 0.1,
                maxTokens = 280,
            )
            completion.text
        }
        return compacted.joinToString("\n\n")
    }

    private fun renderTranscript(messages: List<SessionMessage>): String {
        return messages.joinToString("\n") { renderLine(it) }
    }

    private fun renderLine(message: SessionMessage): String {
        val speaker = if (message.role == "assistant") "我" else "对方"
        return "[${message.id}] $speaker：${message.text.replace("\n", " ")}"
    }

    private fun parseSummary(raw: String): SessionSummary {
        val jsonText = extractJson(raw)
        val root = try {
            JSONObject(jsonText)
        } catch (error: Exception) {
            throw IllegalStateException("summary response was not valid JSON", error)
        }
        val summary = root.optString("summary").trim()
        if (summary.isBlank()) {
            throw IllegalStateException("summary was empty")
        }
        val topics = root.optJSONArray("topics").stringList()
        val events = root.optJSONArray("events").objects().mapNotNull { item ->
            val text = item.optString("text").trim()
            if (text.isBlank()) {
                return@mapNotNull null
            }
            SummaryEvent(
                type = item.optString("type", "event"),
                text = text,
                date = item.optString("date"),
                status = item.optString("status", "pending"),
                importance = item.optDouble("importance", 0.5).coerceIn(0.0, 1.0),
                sourceMessageIds = item.optJSONArray("sourceIds").stringList(),
            )
        }
        return SessionSummary(
            summary = summary.take(500),
            topics = topics.take(8),
            events = events.take(12),
        )
    }

    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "summary response did not contain JSON" }
        return raw.substring(start, end + 1)
    }

    private fun formatDate(timestamp: Long): String {
        val value = if (timestamp > 0L) timestamp else System.currentTimeMillis()
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(value))
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
        private const val MAX_RAW_CHARS = 9_000
        private const val CHUNK_CHARS = 6_000
        private const val PRIMARY_MAX_TOKENS = 1_200
        private const val COMPACT_MAX_TOKENS = 900
        private val SYSTEM_PROMPT = """
            你是聊天记录整理器。只输出一个 JSON 对象，不要 markdown。
            JSON 结构：
            {
              "summary": "不超过120字的总体概述",
              "topics": ["最多6个主题"],
              "events": [
                {
                  "type": "invitation|todo_for_me|todo_for_other|promise|fact|question",
                  "text": "不超过40字的简短明确事件",
                  "date": "YYYY-MM-DD 或空字符串",
                  "status": "proposed|accepted|declined|pending|done|cancelled|unknown",
                  "importance": 0.0,
                  "sourceIds": ["最多3个消息编号"]
                }
              ]
            }
            规则：
            1. 只记录聊天中明确出现的信息，不把推测写成事实。
            2. 相对日期要根据提供的聊天日期换算；无法确定就留空。
            3. 邀约、待办、承诺、未解决问题优先，普通寒暄可以省略。
            4. importance 在 0 到 1 之间。
            5. events 最多8条；没有事件时为空数组。
            6. 必须输出完整闭合的 JSON，不要省略号、注释或额外说明。
        """.trimIndent()
        private val COMPACT_SYSTEM_PROMPT = """
            你是聊天记录整理器。只输出一个完整闭合的 JSON 对象，不要 markdown。
            字段固定为 summary、topics、events。
            summary 不超过100字；topics 最多4个。
            events 最多3条，每条只保留 type、text、date、status、importance、sourceIds；
            text 不超过30字，sourceIds 最多2个。
            只记录明确事实，优先邀约、待办、承诺和未解决问题。
            不要输出省略号，不要截断数组或对象。
        """.trimIndent()
    }
}
