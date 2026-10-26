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
        assertEquals(2, contract.getInt("max_events_per_batch"))
        assertEquals(6, contract.getInt("working_summary_target_items"))
        assertEquals(9, contract.getInt("working_summary_max_items"))
        assertEquals(
            3,
            contract.getInt("working_summary_section_max_items"),
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
                .toStringList()
                .filterNot { it == "operation" || it == "relation_type" },
            contract.getJSONArray("event_candidate_required").toStringList(),
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
