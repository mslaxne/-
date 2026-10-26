package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryV2CommandDigestPayloadTest {
    @Test
    fun `retract command payload includes target and expected version`() {
        assertEquals(
            "memory-1\u00003",
            commandDigestPayload(
                RetractMemoryCommand(
                    operationId = "operation-1",
                    commandSeq = 4,
                    memoryId = "memory-1",
                    expectedVersion = 3,
                ),
            ),
        )
    }

    @Test
    fun `retract tier command payload includes tier`() {
        assertEquals(
            "A2",
            commandDigestPayload(
                RetractTierCommand(
                    operationId = "operation-1",
                    commandSeq = 4,
                    tier = "A2",
                ),
            ),
        )
    }
}
