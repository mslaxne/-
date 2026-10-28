package com.local.wxbotprobe.memory.v2

object MemoryV2ExtractionFailureRecoveryPolicy {
    private val recoverableErrors = setOf(
        MemoryV2ErrorCode.SCHEMA_INVALID.name,
        MemoryV2ErrorCode.INVALID_EVIDENCE.name,
        MemoryV2ErrorCode.COVERAGE_MISMATCH.name,
        MemoryV2ErrorCode.PROMPT_VERSION_MISMATCH.name,
    )

    fun recoverable(errorCode: String?): Boolean {
        return errorCode in recoverableErrors
    }

    fun recoverableErrorCodes(): List<String> {
        return recoverableErrors.toList()
    }
}
