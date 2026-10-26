package com.local.wxbotprobe.chat

class ReplySplitter(
    private val maxParts: Int = 3,
) {
    fun split(text: String): List<String> {
        var parts = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (parts.size == 1 && parts.first().length > 12) {
            val chunks = SENTENCE_BOUNDARY.split(parts.first())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (chunks.size > 1) {
                parts = chunks
            }
        }
        return mergeOverflow(parts)
    }

    private fun mergeOverflow(parts: List<String>): List<String> {
        if (parts.size <= maxParts) {
            return parts
        }
        var head = parts[maxParts - 1]
        for (extra in parts.drop(maxParts)) {
            head = append(head, extra)
        }
        return parts.take(maxParts - 1) + head
    }

    private fun append(head: String, tail: String): String {
        if (tail.isEmpty()) {
            return head
        }
        if (head.isEmpty()) {
            return tail
        }
        return if (head.endsWithAny(SENTENCE_ENDINGS)) head + tail else "$head，$tail"
    }

    private fun String.endsWithAny(suffixes: List<String>): Boolean {
        return suffixes.any { endsWith(it) }
    }

    companion object {
        private val SENTENCE_BOUNDARY = Regex("(?<=[。！？!?~])")
        private val SENTENCE_ENDINGS = listOf(
            "。", "！", "？", "!", "?", "~", "…", "，", ",", "、", "；", ";", "：", ":",
        )
    }
}
