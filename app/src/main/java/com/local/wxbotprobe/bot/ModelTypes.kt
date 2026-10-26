package com.local.wxbotprobe.bot

data class ChatMessage(
    val role: String,
    val content: String,
)

data class Completion(
    val text: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val model: String = "",
)

interface ChatClient {
    fun chat(
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
    ): Completion
}

data class IncomingMessage(
    val id: String,
    val text: String,
    val isGroup: Boolean = false,
    val senderName: String = "",
    val nowSeconds: Double = System.currentTimeMillis() / 1000.0,
)

data class ReplyResult(
    val text: String = "",
    val skippedReason: String = "",
    val error: String = "",
    val budgetBlocked: Boolean = false,
    val latencyMs: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val model: String = "",
) {
    val sent: Boolean
        get() = text.isNotBlank() && error.isBlank() && skippedReason.isBlank()
}
