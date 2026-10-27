package com.local.wxbotprobe.loop

import org.junit.Assert.assertEquals
import org.junit.Test

class StoredReplyRecoveryPolicyTest {
    @Test
    fun `missing reply generates a new response`() {
        assertEquals(
            StoredReplyDisposition.ABSENT,
            StoredReplyRecoveryPolicy.decide(null),
        )
    }

    @Test
    fun `generated and queued replies are reused`() {
        listOf("not_queued", "pending", "partial", " PARTIAL ").forEach {
            assertEquals(StoredReplyDisposition.REUSE, StoredReplyRecoveryPolicy.decide(it))
        }
    }

    @Test
    fun `terminal delivered or suppressed replies are skipped`() {
        listOf("sent", "duplicate_suppressed").forEach {
            assertEquals(StoredReplyDisposition.SKIP, StoredReplyRecoveryPolicy.decide(it))
        }
    }

    @Test
    fun `unknown failed and unrecognised replies require review`() {
        listOf("unknown", "failed", "future-state").forEach {
            assertEquals(
                StoredReplyDisposition.MANUAL_REVIEW,
                StoredReplyRecoveryPolicy.decide(it),
            )
        }
    }
}
