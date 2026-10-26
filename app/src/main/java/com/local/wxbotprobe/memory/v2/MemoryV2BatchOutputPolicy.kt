package com.local.wxbotprobe.memory.v2

object MemoryV2BatchOutputPolicy {
    const val EXTRACTION_OUTPUT_TOKENS = 2_048
    const val SUMMARY_OUTPUT_TOKENS = 2_000

    fun extractionMaxTokens(profileMaximum: Int?): Int {
        return minOf(
            EXTRACTION_OUTPUT_TOKENS,
            profileMaximum?.takeIf { it > 0 } ?: EXTRACTION_OUTPUT_TOKENS,
        )
    }

    fun summaryMaxTokens(profileMaximum: Int?): Int {
        return minOf(
            SUMMARY_OUTPUT_TOKENS,
            profileMaximum?.takeIf { it > 0 } ?: SUMMARY_OUTPUT_TOKENS,
        )
    }
}
