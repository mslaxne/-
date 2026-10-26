package com.local.wxbotprobe.memory.v2

object MemoryV2WorkingSummaryPolicy {
    fun mergeCoveredSources(
        previousSourceIds: List<String>,
        newSourceIds: List<String>,
    ): List<String> {
        return LinkedHashSet(previousSourceIds).apply {
            addAll(newSourceIds)
        }.toList()
    }

    fun shouldCheckpoint(
        pendingSummaryMessages: Int,
        minimumNewMessages: Int,
    ): Boolean {
        return pendingSummaryMessages >= minimumNewMessages.coerceAtLeast(1)
    }

    fun withinBound(
        rawSummary: String,
        maximumUtf8Bytes: Int,
    ): Boolean {
        return rawSummary.toByteArray(Charsets.UTF_8).size <=
            maximumUtf8Bytes.coerceAtLeast(1)
    }
}
