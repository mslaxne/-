package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2ReconciliationPolicyTest {
    @Test
    fun `normalization accepts contract names and safe synonyms`() {
        assertEquals("ADD", MemoryV2ReconciliationPolicy.normalize(null))
        assertEquals("ADD", MemoryV2ReconciliationPolicy.normalize("add"))
        assertEquals(
            "CORROBORATE",
            MemoryV2ReconciliationPolicy.normalize("support"),
        )
        assertEquals(
            "SUPERSEDE",
            MemoryV2ReconciliationPolicy.normalize("update"),
        )
        assertEquals("RELATE", MemoryV2ReconciliationPolicy.normalize("link"))
        assertEquals("NOOP", MemoryV2ReconciliationPolicy.normalize("no_op"))
        assertNull(MemoryV2ReconciliationPolicy.normalize("delete_everything"))
    }

    @Test
    fun `current prompts accept only canonical operation values`() {
        assertEquals("SUPERSEDE", MemoryV2ReconciliationPolicy.canonical("supersede"))
        assertNull(MemoryV2ReconciliationPolicy.canonical("update"))
        assertNull(MemoryV2ReconciliationPolicy.canonical(""))
    }

    @Test
    fun `add must not carry a target while mutations require id and version`() {
        assertEquals(
            MemoryV2ErrorCode.SCHEMA_INVALID,
            MemoryV2ReconciliationPolicy.validateTarget("ADD", "memory-1", 1),
        )
        assertEquals(
            MemoryV2ErrorCode.SCHEMA_INVALID,
            MemoryV2ReconciliationPolicy.validateTarget(
                "CORROBORATE",
                null,
                null,
            ),
        )
        assertEquals(
            MemoryV2ErrorCode.SCHEMA_INVALID,
            MemoryV2ReconciliationPolicy.validateTarget(
                "SUPERSEDE",
                "memory-1",
                null,
            ),
        )
        assertNull(
            MemoryV2ReconciliationPolicy.validateTarget(
                "SUPERSEDE",
                "memory-1",
                3,
            ),
        )
        assertNull(
            MemoryV2ReconciliationPolicy.validateTarget("ADD", null, null),
        )
    }

    @Test
    fun `only evidence-changing operations mutate a memory`() {
        assertTrue(MemoryV2ReconciliationPolicy.mutatesMemory("CORROBORATE"))
        assertTrue(MemoryV2ReconciliationPolicy.mutatesMemory("SUPERSEDE"))
        assertTrue(MemoryV2ReconciliationPolicy.mutatesMemory("RETRACT"))
        assertFalse(MemoryV2ReconciliationPolicy.mutatesMemory("ADD"))
        assertFalse(MemoryV2ReconciliationPolicy.mutatesMemory("CONTRADICT"))
        assertFalse(MemoryV2ReconciliationPolicy.mutatesMemory("RELATE"))
        assertFalse(MemoryV2ReconciliationPolicy.mutatesMemory("NOOP"))
    }

    @Test
    fun `relation types distinguish support from elaboration`() {
        assertEquals(
            "SUPPORTS",
            MemoryV2ReconciliationPolicy.normalizeRelationType("corroborates"),
        )
        assertEquals(
            "ELABORATES",
            MemoryV2ReconciliationPolicy.normalizeRelationType("context"),
        )
        assertNull(MemoryV2ReconciliationPolicy.normalizeRelationType("replaces"))
    }

    @Test
    fun `current prompts accept only canonical relation values`() {
        assertEquals(
            "SUPPORTS",
            MemoryV2ReconciliationPolicy.canonicalRelationType("supports"),
        )
        assertNull(
            MemoryV2ReconciliationPolicy.canonicalRelationType("corroborates"),
        )
    }
}
