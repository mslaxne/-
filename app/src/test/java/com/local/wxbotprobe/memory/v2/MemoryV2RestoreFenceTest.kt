package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryV2RestoreFenceTest {
    @Test
    fun `matching restore guard is accepted`() {
        MemoryV2RestoreFence.requireMatch("guard-a", "guard-a")
    }

    @Test
    fun `foreign restore guard is rejected`() {
        val error = assertThrows(MemoryV2Exception::class.java) {
            MemoryV2RestoreFence.requireMatch("guard-a", "guard-b")
        }
        assertEquals(MemoryV2ErrorCode.RESTORE_FENCE_MISMATCH, error.code)
    }
}
