package com.local.wxbotprobe.memory.v2

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryV2PhaseCheckpointPolicyTest {
    private val sources = listOf("m1", "m2", "m3", "m4")

    @Test
    fun `open phase consumes nothing`() {
        val result = validate(
            processed = emptyList(),
            phase = JSONObject()
                .put("closed", false)
                .put("closed_through_message_id", JSONObject.NULL)
                .put("reason", "the plan still has an unresolved dependency"),
        )

        assertFalse(result.closed)
        assertTrue(result.processedMessageIds.isEmpty())
    }

    @Test
    fun `closed phase consumes only its leading prefix`() {
        val result = validate(
            processed = listOf("m1", "m2"),
            phase = JSONObject()
                .put("closed", true)
                .put("closed_through_message_id", "m2")
                .put("reason", "action loop closed before a topic shift"),
        )

        assertTrue(result.closed)
        assertEquals(listOf("m1", "m2"), result.processedMessageIds)
    }

    @Test
    fun `non-prefix boundary is rejected`() {
        try {
            validate(
                processed = listOf("m2", "m3"),
                phase = JSONObject()
                    .put("closed", true)
                    .put("closed_through_message_id", "m3")
                    .put("reason", "invalid range"),
            )
            fail("expected coverage mismatch")
        } catch (error: MemoryV2Exception) {
            assertEquals(MemoryV2ErrorCode.COVERAGE_MISMATCH, error.code)
        }
    }

    @Test
    fun `legacy prompt keeps whole-batch processing`() {
        val result = MemoryV2PhaseCheckpointPolicy.validate(
            allSourceIds = sources,
            processedMessageIds = sources,
            phase = null,
            phaseAware = false,
        )

        assertTrue(result.closed)
        assertEquals(sources, result.processedMessageIds)
    }

    private fun validate(
        processed: List<String>,
        phase: JSONObject,
    ): MemoryV2PhaseCheckpoint {
        return MemoryV2PhaseCheckpointPolicy.validate(
            allSourceIds = sources,
            processedMessageIds = processed,
            phase = phase,
            phaseAware = true,
        )
    }
}
