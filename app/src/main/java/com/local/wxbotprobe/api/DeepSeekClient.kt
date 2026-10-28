package com.local.wxbotprobe.api

import com.local.wxbotprobe.bot.ChatClient
import com.local.wxbotprobe.bot.ChatImagePolicy
import com.local.wxbotprobe.bot.ChatMessage
import com.local.wxbotprobe.bot.Completion
import com.local.wxbotprobe.chat.IMAGE_PLACEHOLDER
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import org.json.JSONObject

class DeepSeekException(
    message: String,
    val status: Int? = null,
    val retryable: Boolean = false,
) : IOException(message)

class DeepSeekClient(
    private val apiKey: String = "",
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-flash",
    private val timeoutMs: Int = 60_000,
    private val maxRetries: Int = 3,
    private val includeThinkingFlag: Boolean = true,
    private val providerLabel: String = "DeepSeek",
    private val imageInputEnabled: Boolean = false,
) : ChatClient {
    override fun chat(
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
    ): Completion {
        val body = buildRequestBody(messages, temperature, maxTokens)
        var lastError: DeepSeekException? = null
        for (attempt in 1..maxRetries.coerceAtLeast(1)) {
            try {
                val connection = URL("${baseUrl.trimEnd('/')}/chat/completions")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = timeoutMs
                    connection.readTimeout = timeoutMs
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    if (apiKey.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    connection.setRequestProperty("Accept", "application/json")
                    connection.outputStream.use {
                        it.write(body.toByteArray(Charsets.UTF_8))
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        .orEmpty()
                    if (status !in 200..299) {
                        val retryable = status in listOf(408, 409, 429) || status >= 500
                        throw DeepSeekException(
                            "$providerLabel returned HTTP $status: ${raw.take(400)}",
                            status,
                            retryable,
                        )
                    }
                    return parseResponse(raw)
                } finally {
                    connection.disconnect()
                }
            } catch (error: DeepSeekException) {
                lastError = error
                if (!error.retryable) {
                    throw error
                }
            } catch (error: IOException) {
                lastError = DeepSeekException(
                    "$providerLabel network error: ${error.message}",
                    retryable = true,
                )
            }

            if (attempt < maxRetries) {
                Thread.sleep(min(1 shl (attempt - 1), 8) * 1_000L)
            }
        }
        throw lastError ?: DeepSeekException("$providerLabel call failed")
    }

    internal fun buildRequestBody(
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
    ): String {
        val requestMessages = ChatImagePolicy.capForRequest(messages)
        val payload = JSONObject()
            .put("model", model)
            .put(
                "messages",
                org.json.JSONArray().apply {
                    requestMessages.forEach {
                        val images = if (imageInputEnabled && it.role == "user") {
                            it.images
                        } else {
                            emptyList()
                        }
                        val content = if (images.isEmpty()) {
                            it.content.ifBlank {
                                if (it.images.isNotEmpty()) IMAGE_PLACEHOLDER else ""
                            }
                        } else {
                            org.json.JSONArray().apply {
                                put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put(
                                            "text",
                                            it.content.ifBlank { IMAGE_PLACEHOLDER },
                                        ),
                                )
                                images.forEach { image ->
                                    put(
                                        JSONObject()
                                            .put("type", "image_url")
                                            .put(
                                                "image_url",
                                                JSONObject().put("url", image.dataUrl()),
                                            ),
                                    )
                                }
                            }
                        }
                        put(
                            JSONObject()
                                .put("role", it.role)
                                .put("content", content),
                        )
                    }
                },
            )
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", false)
        if (includeThinkingFlag) {
            payload.put("thinking", JSONObject().put("type", "disabled"))
        }
        return payload.toString()
    }

    companion object {
        internal fun parseResponse(raw: String): Completion {
            val data = try {
                JSONObject(raw)
            } catch (error: Exception) {
                throw DeepSeekException("response was not JSON: ${raw.take(200)}")
            }
            val choices = data.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                throw DeepSeekException("response contained no choices")
            }
            val choice = choices.getJSONObject(0)
            val message = choice.optJSONObject("message") ?: JSONObject()
            val text = message.optString("content").trim()
            val reasoning = message.optString("reasoning_content").trim()
            if (text.isEmpty() && reasoning.isNotEmpty()) {
                throw DeepSeekException(
                    "模型只输出了思考过程没有正文；thinking 必须设为 disabled",
                )
            }
            val usage = data.optJSONObject("usage") ?: JSONObject()
            return Completion(
                text = text,
                promptTokens = usage.optInt("prompt_tokens", 0),
                completionTokens = usage.optInt("completion_tokens", 0),
                model = data.optString("model"),
            )
        }
    }
}
