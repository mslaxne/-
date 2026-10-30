package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2ExtractionFailureRecoveryPolicyTest {
    @Test
    fun `prompt and evidence contract failures can be retried after prompt change`() {
        assertTrue(
            MemoryV2ExtractionFailureRecoveryPolicy.recoverable(
                MemoryV2ErrorCode.SCHEMA_INVALID.name,
            ),
        )
        assertTrue(
            MemoryV2ExtractionFailureRecoveryPolicy.recoverable(
                MemoryV2ErrorCode.INVALID_EVIDENCE.name,
            ),
        )
        assertTrue(
            MemoryV2ExtractionFailureRecoveryPolicy.recoverable(
                MemoryV2ErrorCode.COVERAGE_MISMATCH.name,
            ),
        )
    }

    @Test
    fun `source and storage failures are not blindly requeued`() {
        assertFalse(
            MemoryV2ExtractionFailureRecoveryPolicy.recoverable(
                MemoryV2ErrorCode.SOURCE_UNAVAILABLE.name,
            ),
        )
        assertFalse(
            MemoryV2ExtractionFailureRecoveryPolicy.recoverable(
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name,
            ),
        )
    }

    @Test
    fun `storage failure gets a bounded delayed retry`() {
        assertTrue(
            MemoryV2ExtractionFailureRecoveryPolicy.shouldRetryTerminalFailure(
                errorCode = MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name,
                failedGenerations = 1,
                lastFailedAt = 1_000,
                nowEpochSeconds = 1_061,
            ),
        )
        assertTrue(
            MemoryV2ExtractionFailureRecoveryPolicy.shouldRetryTerminalFailure(
                errorCode = MemoryV2ErrorCode.MODEL_REQUEST_FAILED.name,
                failedGenerations = 1,
                lastFailedAt = 1_000,
                nowEpochSeconds = 1_061,
            ),
        )
        assertFalse(
            MemoryV2ExtractionFailureRecoveryPolicy.shouldRetryTerminalFailure(
                errorCode = MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name,
                failedGenerations = 1,
                lastFailedAt = 1_000,
                nowEpochSeconds = 1_059,
            ),
        )
        assertFalse(
            MemoryV2ExtractionFailureRecoveryPolicy.shouldRetryTerminalFailure(
                errorCode = MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name,
                failedGenerations = 3,
                lastFailedAt = 1_000,
                nowEpochSeconds = 2_000,
            ),
        )
    }
}
