package com.local.wxbotprobe.loop

import com.local.wxbotprobe.memory.v2.AppendMessageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryV2SessionBindingTest {
    @Test
    fun `first append creates session and later appends reuse it`() {
        val binding = MemoryV2SessionBinding()
        assertNull(binding.sessionId)

        assertEquals(
            "session-1",
            binding.accept(appendResult("session-1", seq = 1)),
        )
        assertEquals(
            "session-1",
            binding.accept(appendResult("session-1", seq = 2)),
        )
        assertEquals("session-1", binding.sessionId)
    }

    @Test
    fun `existing session remains stable`() {
        val binding = MemoryV2SessionBinding("session-existing")

        assertEquals(
            "session-existing",
            binding.accept(appendResult("session-other", seq = 2)),
        )
        assertEquals("session-existing", binding.sessionId)
    }

    private fun appendResult(
        sessionId: String,
        seq: Int,
    ): AppendMessageResult {
        return AppendMessageResult(
            status = "saved",
            scopeId = "scope-1",
            sessionId = sessionId,
            messageId = "message-$seq",
            seq = seq,
        )
    }
}
