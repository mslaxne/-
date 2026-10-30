package com.local.wxbotprobe.memory.v2

object MemoryV2ExtractionFailureRecoveryPolicy {
    const val MAX_TERMINAL_GENERATIONS = 3
    const val RETRY_COOLDOWN_SECONDS = 60L

    private val recoverableErrors = setOf(
        MemoryV2ErrorCode.SCHEMA_INVALID.name,
        MemoryV2ErrorCode.INVALID_EVIDENCE.name,
        MemoryV2ErrorCode.COVERAGE_MISMATCH.name,
        MemoryV2ErrorCode.PROMPT_VERSION_MISMATCH.name,
    )
    private val transientErrors = setOf(
        MemoryV2ErrorCode.MODEL_REQUEST_FAILED.name,
        MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name,
    )

    fun recoverable(errorCode: String?): Boolean {
        return errorCode in recoverableErrors
    }

    fun recoverableErrorCodes(): List<String> {
        return recoverableErrors.toList()
    }

    fun shouldRetryTerminalFailure(
        errorCode: String?,
        failedGenerations: Int,
        lastFailedAt: Long?,
        nowEpochSeconds: Long,
    ): Boolean {
        if (errorCode !in transientErrors) {
            return false
        }
        if (failedGenerations >= MAX_TERMINAL_GENERATIONS) {
            return false
        }
        return lastFailedAt == null ||
            nowEpochSeconds - lastFailedAt >= RETRY_COOLDOWN_SECONDS
    }
}
