package com.local.wxbotprobe.bot

data class ChatImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String = "image/jpeg",
    val sha256: String = "",
) {
    init {
        require(bytes.isNotEmpty()) { "image bytes are required" }
        require(width > 0 && height > 0) { "image dimensions must be positive" }
        require(mimeType.startsWith("image/")) { "unsupported image mime type" }
    }

    fun dataUrl(): String {
        return "data:$mimeType;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ChatImage) {
            return false
        }
        return width == other.width &&
            height == other.height &&
            mimeType == other.mimeType &&
            sha256 == other.sha256 &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + sha256.hashCode()
        return result
    }
}

data class ChatMessage(
    val role: String,
    val content: String,
    val images: List<ChatImage> = emptyList(),
)

enum class ReplyDecisionAction {
    REPLY,
    WAIT,
    SILENT,
}

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
    val images: List<ChatImage> = emptyList(),
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
    val decisionAction: ReplyDecisionAction? = null,
) {
    val sent: Boolean
        get() = text.isNotBlank() && error.isBlank() && skippedReason.isBlank()
}
