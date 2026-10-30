package com.local.wxbotprobe.memory.v2

enum class MemoryV2PumpMode {
    BACKGROUND,
    MANUAL,
}

enum class MemoryV2PumpStage {
    NONE,
    EXTRACTION,
    SUMMARY,
    COMPLETE,
    STOPPED,
}

object MemoryV2ManualDrainPolicy {
    const val SUMMARY_CHECKPOINT_SIZE = 8

    fun summaryCheckpointSize(): Int = SUMMARY_CHECKPOINT_SIZE

    fun stage(
        hasPendingExtraction: Boolean,
        hasPendingSummary: Boolean,
    ): MemoryV2PumpStage {
        return when {
            hasPendingExtraction -> MemoryV2PumpStage.EXTRACTION
            hasPendingSummary -> MemoryV2PumpStage.SUMMARY
            else -> MemoryV2PumpStage.COMPLETE
        }
    }

    fun requiresStop(errorCode: MemoryV2ErrorCode): Boolean {
        return errorCode in setOf(
            MemoryV2ErrorCode.SCHEMA_INVALID,
            MemoryV2ErrorCode.ENVELOPE_MISMATCH,
            MemoryV2ErrorCode.COVERAGE_MISMATCH,
            MemoryV2ErrorCode.SOURCE_SET_INVALID,
            MemoryV2ErrorCode.PROMPT_VERSION_MISMATCH,
            MemoryV2ErrorCode.QUOTE_NOT_FOUND,
            MemoryV2ErrorCode.QUOTE_AMBIGUOUS,
            MemoryV2ErrorCode.QUOTE_TOO_LARGE,
            MemoryV2ErrorCode.INVALID_EVIDENCE,
            MemoryV2ErrorCode.SOURCE_UNAVAILABLE,
            MemoryV2ErrorCode.SOURCE_CHANGED,
            MemoryV2ErrorCode.SOURCE_FORGOTTEN,
            MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED,
            MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
        )
    }
}
