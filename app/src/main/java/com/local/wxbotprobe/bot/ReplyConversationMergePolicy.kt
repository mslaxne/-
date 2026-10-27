package com.local.wxbotprobe.bot

import com.local.wxbotprobe.chat.TextRules

object ReplyConversationMergePolicy {
    fun merge(
        persisted: List<ChatMessage>,
        visible: List<ChatMessage>,
    ): List<ChatMessage> {
        if (persisted.isEmpty()) {
            return visible
        }
        if (visible.isEmpty()) {
            return persisted
        }
        val persistedKeys = persisted.map(::key).toSet()
        val overlap = maximalSuffixOverlap(persisted, visible)
        if (overlap == 0) {
            return persisted + visible.filterNot { key(it) in persistedKeys }
        }
        val visiblePrefix = visible.dropLast(overlap)
            .filterNot { key(it) in persistedKeys }
        return visiblePrefix + persisted
    }

    private fun maximalSuffixOverlap(
        persisted: List<ChatMessage>,
        visible: List<ChatMessage>,
    ): Int {
        val max = minOf(persisted.size, visible.size)
        for (size in max downTo 1) {
            val persistedTail = persisted.subList(persisted.size - size, persisted.size)
            val visibleTail = visible.subList(visible.size - size, visible.size)
            if (
                persistedTail.indices.all {
                    key(persistedTail[it]) == key(visibleTail[it])
                }
            ) {
                return size
            }
        }
        return 0
    }

    private fun key(message: ChatMessage): String {
        return message.role + '\u0000' + TextRules.normalise(message.content)
    }
}
