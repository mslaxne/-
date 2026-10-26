package com.local.wxbotprobe.bot

class ConversationStore(
    val maxTurns: Int = 0,
) {
    private val histories = mutableMapOf<String, ArrayDeque<ChatMessage>>()
    private val lastReplyAt = mutableMapOf<String, Double>()
    private val lastMessageAt = mutableMapOf<String, Double>()

    init {
        require(maxTurns >= 0) { "maxTurns must not be negative" }
    }

    fun history(conversationId: String): List<ChatMessage> {
        return histories[conversationId]?.toList().orEmpty()
    }

    fun append(conversationId: String, role: String, content: String) {
        if (content.isBlank()) {
            return
        }
        val turns = histories.getOrPut(conversationId) { ArrayDeque() }
        if (maxTurns > 0) {
            while (turns.size >= maxTurns) {
                turns.removeFirst()
            }
        }
        turns.addLast(ChatMessage(role, content))
    }

    fun clear(conversationId: String) {
        histories.remove(conversationId)
        lastMessageAt.remove(conversationId)
    }

    fun popLast(conversationId: String): ChatMessage? {
        return histories[conversationId]?.removeLastOrNull()
    }

    fun lastReplyAt(conversationId: String): Double = lastReplyAt[conversationId] ?: 0.0

    fun markReply(conversationId: String, timestamp: Double) {
        lastReplyAt[conversationId] = timestamp
    }

    fun quietFor(conversationId: String, now: Double): Double {
        val last = lastMessageAt[conversationId] ?: return 0.0
        return (now - last).coerceAtLeast(0.0)
    }

    fun markMessage(conversationId: String, timestamp: Double) {
        lastMessageAt[conversationId] = timestamp
    }
}
