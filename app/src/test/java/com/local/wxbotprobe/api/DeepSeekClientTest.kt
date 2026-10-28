package com.local.wxbotprobe.api

import com.local.wxbotprobe.bot.ChatMessage
import com.local.wxbotprobe.bot.ChatImage
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
    fun `vision request uses openai image content parts`() {
        val client = DeepSeekClient(
            apiKey = "test-key",
            imageInputEnabled = true,
        )
        val image = ChatImage(
            bytes = byteArrayOf(1, 2, 3),
            width = 640,
            height = 480,
            sha256 = "image-a",
        )
        val raw = client.buildRequestBody(
            listOf(
                ChatMessage(
                    role = "user",
                    content = "[[图片]]",
                    images = listOf(image),
                ),
            ),
            temperature = 1.0,
            maxTokens = 500,
        )

        val content = JSONObject(raw)
            .getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("[[图片]]", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(
            content
                .getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url")
                .startsWith("data:image/jpeg;base64,"),
        )
    }

    @Test
    fun `vision disabled keeps image messages text only`() {
        val client = DeepSeekClient("test-key")
        val raw = client.buildRequestBody(
            listOf(
                ChatMessage(
                    role = "user",
                    content = "",
                    images = listOf(
                        ChatImage(
                            bytes = byteArrayOf(1),
                            width = 10,
                            height = 10,
                        ),
                    ),
                ),
            ),
            temperature = 1.0,
            maxTokens = 50,
        )

        val content = JSONObject(raw)
            .getJSONArray("messages")
            .getJSONObject(0)
            .getString("content")
        assertEquals("[[图片]]", content)
    }

    @Test
    fun `assistant messages never carry image parts`() {
        val client = DeepSeekClient(
            apiKey = "test-key",
            imageInputEnabled = true,
        )
        val raw = client.buildRequestBody(
            listOf(
                ChatMessage(
                    role = "assistant",
                    content = "图片",
                    images = listOf(
                        ChatImage(
                            bytes = byteArrayOf(1),
                            width = 10,
                            height = 10,
                        ),
                    ),
                ),
            ),
            temperature = 1.0,
            maxTokens = 50,
        )

        assertTrue(
            JSONObject(raw)
                .getJSONArray("messages")
                .getJSONObject(0)
                .get("content") is String,
        )
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
