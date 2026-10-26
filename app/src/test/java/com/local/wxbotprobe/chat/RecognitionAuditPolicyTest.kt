package com.local.wxbotprobe.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionAuditPolicyTest {
    @Test
    fun `changed frame without more bubbles requests recheck`() {
        assertTrue(
            RecognitionAuditPolicy.shouldRecheck(
                input(
                    frameChanged = true,
                    previousItems = 8,
                    currentItems = 8,
                ),
            ),
        )
    }

    @Test
    fun `recognized additional bubble does not request recheck`() {
        assertFalse(
            RecognitionAuditPolicy.shouldRecheck(
                input(
                    frameChanged = true,
                    previousItems = 8,
                    currentItems = 9,
                ),
            ),
        )
    }

    @Test
    fun `lost bubble or OCR row requests recheck`() {
        assertTrue(
            RecognitionAuditPolicy.shouldRecheck(
                input(
                    frameChanged = false,
                    previousItems = 9,
                    currentItems = 8,
                ),
            ),
        )
        assertTrue(
            RecognitionAuditPolicy.shouldRecheck(
                input(
                    frameChanged = false,
                    previousItems = 8,
                    currentItems = 8,
                    previousRows = 25,
                    currentRows = 20,
                ),
            ),
        )
    }

    @Test
    fun `chat change burst completion and cooldown suppress recheck`() {
        assertFalse(
            RecognitionAuditPolicy.shouldRecheck(
                input(sameChat = false),
            ),
        )
        assertFalse(
            RecognitionAuditPolicy.shouldRecheck(
                input(burstCompleted = true),
            ),
        )
        assertFalse(
            RecognitionAuditPolicy.shouldRecheck(
                input(cooldownReady = false),
            ),
        )
    }

    @Test
    fun `static frame below thresholds does not request recheck`() {
        assertFalse(RecognitionAuditPolicy.frameChanged(0.1, 0.0002))
    }

    @Test
    fun `pending audit candidates request follow-up after cooldown`() {
        assertTrue(
            RecognitionAuditPolicy.shouldRecheck(
                input(
                    frameChanged = false,
                    pendingAuditCandidates = true,
                ),
            ),
        )
        assertFalse(
            RecognitionAuditPolicy.shouldRecheck(
                input(
                    frameChanged = false,
                    pendingAuditCandidates = true,
                    cooldownReady = false,
                ),
            ),
        )
    }

    @Test
    fun `chat change and burst completion suppress pending follow-up`() {
        assertFalse(
            RecognitionAuditPolicy.shouldRecheck(
                input(
                    sameChat = false,
                    pendingAuditCandidates = true,
                ),
            ),
        )
        assertFalse(
            RecognitionAuditPolicy.shouldRecheck(
                input(
                    burstCompleted = true,
                    pendingAuditCandidates = true,
                ),
            ),
        )
    }

    @Test
    fun `fresh incoming item suppresses redundant local audit`() {
        assertFalse(
            RecognitionAuditPolicy.shouldRecheck(
                input(
                    frameChanged = true,
                    pendingAuditCandidates = true,
                    freshIncomingDetected = true,
                ),
            ),
        )
    }

    @Test
    fun `pending burst suppresses local audit contention`() {
        assertFalse(
            RecognitionAuditPolicy.shouldRecheck(
                input(
                    frameChanged = true,
                    pendingBurst = true,
                ),
            ),
        )
    }

    private fun input(
        sameChat: Boolean = true,
        frameChanged: Boolean = true,
        previousItems: Int = 8,
        currentItems: Int = 8,
        previousRows: Int = 20,
        currentRows: Int = 20,
        burstCompleted: Boolean = false,
        cooldownReady: Boolean = true,
        pendingAuditCandidates: Boolean = false,
        freshIncomingDetected: Boolean = false,
        pendingBurst: Boolean = false,
    ): RecognitionAuditInput {
        return RecognitionAuditInput(
            primed = true,
            sameChat = sameChat,
            frameChanged = frameChanged,
            previousItemCount = previousItems,
            currentItemCount = currentItems,
            previousOcrRowCount = previousRows,
            currentOcrRowCount = currentRows,
            burstCompleted = burstCompleted,
            cooldownReady = cooldownReady,
            pendingAuditCandidates = pendingAuditCandidates,
            freshIncomingDetected = freshIncomingDetected,
            pendingBurst = pendingBurst,
        )
    }
}
