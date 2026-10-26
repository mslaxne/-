package com.local.wxbotprobe.memory

import com.local.wxbotprobe.bot.ChatClient
import com.local.wxbotprobe.bot.ChatMessage
import com.local.wxbotprobe.bot.Completion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSummarizerTest {
    private class JsonClient(vararg responses: String) : ChatClient {
        private val responses = responses.toMutableList()
        val calls = mutableListOf<List<ChatMessage>>()
        val maxTokens = mutableListOf<Int>()

        override fun chat(
            messages: List<ChatMessage>,
            temperature: Double,
            maxTokens: Int,
        ): Completion {
            calls += messages
            this.maxTokens += maxTokens
            return Completion(responses.removeAt(0))
        }
    }

    private fun session() = AgentSession(
        sessionId = "s1",
        chatId = "c1",
        identityKey = "张三",
        displayName = "张三",
        openedAt = 1_000L,
        closedAt = 2_000L,
        status = AgentSession.STATUS_CLOSING,
        summaryStatus = "running",
        summaryError = "",
        messages = listOf(
            SessionMessage("m1", "user", "周六一起吃饭吗", 1_100L, "received"),
            SessionMessage("m2", "assistant", "可以，到时候看", 1_200L, "sent"),
        ),
    )

    @Test
    fun `structured summary is parsed`() {
        val client = JsonClient(
            """
            ```json
            {
              "summary": "聊了周末吃饭。",
              "topics": ["周末", "吃饭"],
              "events": [{
                "type": "invitation",
                "text": "周六一起吃饭",
                "date": "2026-10-24",
                "status": "proposed",
                "importance": 0.9,
                "sourceIds": ["m1"]
              }]
            }
            ```
            """.trimIndent(),
        )
        val summary = SessionSummarizer(client).summarize(session())
        assertEquals("聊了周末吃饭。", summary.summary)
        assertEquals("周六一起吃饭", summary.events.single().text)
        assertEquals(listOf("m1"), summary.events.single().sourceMessageIds)
        assertTrue(client.calls.single().last().content.contains("[m1] 对方"))
    }

    @Test
    fun `truncated summary is retried with a compact schema`() {
        val truncated = """
            {
              "summary": "聊了周末吃饭。",
              "topics": ["周末", "吃饭"],
              "events": [{
                "type": "invitation",
                "text": "周六一起吃饭",
                "date": "2026-10-24",
                "status": "proposed"
        """.trimIndent()
        val repaired = """
            {
              "summary": "聊了周末吃饭。",
              "topics": ["周末"],
              "events": [{
                "type": "invitation",
                "text": "周六一起吃饭",
                "date": "2026-10-24",
                "status": "proposed",
                "importance": 0.9,
                "sourceIds": ["m1"]
              }]
            }
        """.trimIndent()
        val client = JsonClient(truncated, repaired)

        val summary = SessionSummarizer(client).summarize(session())

        assertEquals("聊了周末吃饭。", summary.summary)
        assertEquals("周六一起吃饭", summary.events.single().text)
        assertEquals(2, client.calls.size)
        assertEquals(listOf(1_200, 900), client.maxTokens)
        assertTrue(client.calls[1].first().content.contains("最多3条"))
    }

    @Test
    fun `invalid summary errors do not expose the model response`() {
        val invalid = """{"summary":"私密内容","events":["""
        val client = JsonClient(invalid, invalid)

        val error = try {
            SessionSummarizer(client).summarize(session())
            null
        } catch (failure: IllegalStateException) {
            failure
        }

        assertEquals(
            "summary response was invalid after compact retry",
            error?.message,
        )
        assertTrue(error?.message?.contains("私密内容") == false)
    }
}
