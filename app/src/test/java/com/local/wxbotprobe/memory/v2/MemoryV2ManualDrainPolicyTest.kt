package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2ManualDrainPolicyTest {
    @Test
    fun `manual drain checks extraction before summary`() {
        assertEquals(
            MemoryV2PumpStage.EXTRACTION,
            MemoryV2ManualDrainPolicy.stage(
                hasPendingExtraction = true,
                hasPendingSummary = true,
            ),
        )
        assertEquals(
            MemoryV2PumpStage.SUMMARY,
            MemoryV2ManualDrainPolicy.stage(
                hasPendingExtraction = false,
                hasPendingSummary = true,
            ),
        )
        assertEquals(
            MemoryV2PumpStage.COMPLETE,
            MemoryV2ManualDrainPolicy.stage(
                hasPendingExtraction = false,
                hasPendingSummary = false,
            ),
        )
    }

    @Test
    fun `manual summary uses bounded eight message checkpoints`() {
        assertEquals(8, MemoryV2ManualDrainPolicy.summaryCheckpointSize())
    }

    @Test
    fun `structural evidence and storage failures stop manual drain`() {
        assertTrue(
            MemoryV2ManualDrainPolicy.requiresStop(
                MemoryV2ErrorCode.SCHEMA_INVALID,
            ),
        )
        assertTrue(
            MemoryV2ManualDrainPolicy.requiresStop(
                MemoryV2ErrorCode.INVALID_EVIDENCE,
            ),
        )
        assertTrue(
            MemoryV2ManualDrainPolicy.requiresStop(
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
            ),
        )
        assertFalse(
            MemoryV2ManualDrainPolicy.requiresStop(
                MemoryV2ErrorCode.MODEL_REQUEST_FAILED,
            ),
        )
    }
}
