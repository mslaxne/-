package com.local.wxbotprobe.memory.v2

object MemoryV2BatchSplitPolicy {
    fun splitExtraction(sourceIds: List<String>): List<List<String>> {
        val distinct = sourceIds.distinct()
        if (distinct.size < 2) {
            return emptyList()
        }
        val splitAt = (distinct.size + 1) / 2
        return listOf(
            distinct.take(splitAt),
            distinct.drop(splitAt),
        )
    }
}
