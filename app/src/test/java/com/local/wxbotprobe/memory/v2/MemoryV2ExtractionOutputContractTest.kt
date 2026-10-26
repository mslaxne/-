package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2ExtractionOutputContractTest {
    private val contract = MemoryV2ExtractionOutputContract.json()

    @Test
    fun `top level contract matches extraction batch`() {
        assertEquals("ExtractionBatch", contract.getString("contract_value"))
        assertEquals(
            listOf(
                "contract",
                "envelope",
                "processed_message_ids",
                "candidates",
            ),
            contract.getJSONArray("top_level_required").toStringList(),
        )
    }

    @Test
    fun `candidate requires the exact persisted fields`() {
        assertEquals(
            listOf(
                "candidate_id",
                "operation",
                "relation_type",
                "subject_id",
                "viewpoint_id",
                "predicate",
                "text",
                "value",
                "epistemic",
                "reason",
                "target_memory_id",
                "expected_version",
                "temporal",
                "commitment_state",
                "importance_hint",
                "evidence",
            ),
            contract.getJSONArray("candidate_required").toStringList(),
        )
        assertFalse(
            contract.getJSONArray("candidate_required").toStringList()
                .contains("object_value"),
        )
        assertEquals(
            listOf(
                "ADD",
                "CORROBORATE",
                "SUPERSEDE",
                "CONTRADICT",
                "RELATE",
                "RETRACT",
                "NOOP",
            ),
            contract.getJSONArray("operation_enum").toStringList(),
        )
        assertFalse(
            contract.getJSONArray("candidate_required").toStringList()
                .contains("kind"),
        )
    }

    @Test
    fun `reason and epistemic values match the validator`() {
        assertEquals(
            listOf(
                "durable_preference",
                "confirmed_commitment",
                "important_experience",
                "attributed_impression",
                "recent_event",
            ),
            contract.getJSONArray("reason_enum").toStringList(),
        )
        assertEquals(
            listOf("declared", "observed", "inferred"),
            contract.getJSONArray("epistemic_enum").toStringList(),
        )
        assertTrue(
            contract.getJSONArray("field_rules").toStringList()
                .contains("Use value, never object_value."),
        )
        assertTrue(
            contract.getJSONArray("field_rules").toStringList()
                .any { it.contains("resolve relative dates") },
        )
        assertTrue(
            contract.getJSONArray("field_rules").toStringList()
                .any { it.contains("rather than \"none\"") },
        )
        assertTrue(
            contract.getJSONArray("field_rules").toStringList()
                .any { it.contains("current agent") },
        )
        assertTrue(
            contract.getJSONArray("field_rules").toStringList()
                .any { it.contains("target_memory_id=null") },
        )
        assertTrue(
            contract.getJSONArray("field_rules").toStringList()
                .any { it.contains("RELATE creates") },
        )
    }

    private fun org.json.JSONArray.toStringList(): List<String> {
        return (0 until length()).map { getString(it) }
    }
}
