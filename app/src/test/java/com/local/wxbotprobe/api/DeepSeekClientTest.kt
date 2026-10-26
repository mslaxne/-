package com.local.wxbotprobe.api

import com.local.wxbotprobe.bot.ChatMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTest {
    @Test
    fun `request disables thinking and uses flash model`() {
        val client = DeepSeekClient("test-key")
        val raw = client.buildRequestBody(
            listOf(ChatMessage("user", "在吗")),
            temperature = 1.0,
            maxTokens = 500,
        )
        val payload = JSONObject(raw)
        assertEquals("deepseek-flash", payload.getString("model"))
        assertEquals("disabled", payload.getJSONObject("thinking").getString("type"))
        assertFalse(payload.getBoolean("stream"))
        assertEquals(500, payload.getInt("max_tokens"))
    }

    @Test
    fun `generic compatible request omits deepseek thinking field`() {
        val client = DeepSeekClient(
            apiKey = "",
            baseUrl = "https://example.com/v1",
            model = "free-model",
            includeThinkingFlag = false,
            providerLabel = "test",
        )
        val raw = client.buildRequestBody(
            listOf(ChatMessage("user", "ping")),
            temperature = 0.0,
            maxTokens = 8,
        )
        val payload = JSONObject(raw)
        assertEquals("free-model", payload.getString("model"))
        assertFalse(payload.has("thinking"))
    }

    @Test
    fun `response parses content and usage`() {
        val raw = """
            {"model":"deepseek-flash","choices":[{"message":{"content":" 在 "}}],
             "usage":{"prompt_tokens":12,"completion_tokens":3}}
        """.trimIndent()
        val completion = DeepSeekClient.parseResponse(raw)
        assertEquals("在", completion.text)
        assertEquals(12, completion.promptTokens)
        assertEquals(3, completion.completionTokens)
        assertEquals("deepseek-flash", completion.model)
    }

    @Test
    fun `reasoning only response is an error`() {
        val raw = """
            {"choices":[{"message":{"content":null,"reasoning_content":"思考中"}}]}
        """.trimIndent()
        val error = runCatching { DeepSeekClient.parseResponse(raw) }.exceptionOrNull()
        assertTrue(error is DeepSeekException)
        assertTrue(error?.message.orEmpty().contains("thinking"))
    }
}
