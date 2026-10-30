package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryV2SummaryOutputContractTest {
    private val contract = MemoryV2SummaryOutputContract.json()

    @Test
    fun `summary contract requires complete working state`() {
        assertEquals("SummaryBatch", contract.getString("contract_value"))
        assertEquals(
            listOf(
                "contract",
                "envelope",
                "processed_message_ids",
                "events",
                "working_summary",
            ),
            contract.getJSONArray("top_level_required").toStringList(),
        )
        assertEquals(
            listOf("goals", "constraints", "unresolved", "references"),
            contract.getJSONArray("working_summary_required").toStringList(),
        )
        assertEquals(
            listOf("text", "evidence"),
            contract.getJSONArray("working_item_required").toStringList(),
        )
    }

    @Test
    fun `summary events use recent event contract`() {
        assertEquals(
            listOf("recent_event"),
            contract.getJSONArray("event_reason_enum").toStringList(),
        )
        assertEquals(
            MemoryV2ExtractionOutputContract.json()
                .getJSONArray("candidate_required")
                .toStringList(),
            contract.getJSONArray("event_candidate_required").toStringList(),
        )
        val rules = contract.getJSONArray("field_rules").toStringList()
        assertEquals(
            1,
            rules.count {
                it.contains("operation ADD") &&
                    it.contains("relation_type null")
            },
        )
    }

    @Test
    fun `summary contract rejects date precision without a local date`() {
        val rules = contract.getJSONArray("field_rules").toStringList()

        assertEquals(
            1,
            rules.count { it.contains("precision=unknown when local_date is null") },
        )
        assertEquals(
            1,
            rules.count { it.contains("precision=date or day only") },
        )
    }

    private fun org.json.JSONArray.toStringList(): List<String> {
        return (0 until length()).map { getString(it) }
    }
}
