package com.local.wxbotprobe.memory.v2

import org.json.JSONArray
import org.json.JSONObject

object MemoryV2SummaryOutputContract {
    fun json(): JSONObject {
        return JSONObject()
            .put(
                "top_level_required",
                JSONArray(
                    listOf(
                        "contract",
                        "envelope",
                        "processed_message_ids",
                        "events",
                        "working_summary",
                    ),
                ),
            )
            .put("contract_value", "SummaryBatch")
            .put(
                "event_candidate_required",
                JSONArray(
                    listOf(
                        "candidate_id",
                        "tier",
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
                ),
            )
            .put("event_reason_enum", JSONArray(listOf("recent_event")))
            .put(
                "working_summary_required",
                JSONArray(
                    listOf("goals", "constraints", "unresolved", "references"),
                ),
            )
            .put(
                "working_item_required",
                JSONArray(listOf("text", "evidence")),
            )
            .put("additional_properties", false)
            .put(
                "field_rules",
                JSONArray(
                    listOf(
                        "Use value, never object_value.",
                        "The envelope object must contain only job_id and exactly " +
                            "match the frozen job_id.",
                        "Do not output kind, pinned or permission fields.",
                        "A3 is the rolling session working_summary. " +
                            "Each recent event candidate must use tier A2.",
                        "Every summary event must use operation ADD and " +
                            "relation_type null.",
                        "Every working_summary section must be an array.",
                        "temporal must contain occurred_at, local_date, timezone, " +
                            "precision and original.",
                        "Use precision=unknown when local_date is null. " +
                            "Use precision=date or day only with a valid " +
                            "YYYY-MM-DD local_date.",
                        "Use precision=instant only with a numeric occurred_at.",
                        "epistemic must be exactly declared, observed or inferred.",
                        "commitment_state must be null or one of proposed, active, " +
                            "overdue_unknown, completed, cancelled.",
                        "importance_hint must be a number from 0 to 1.",
                        "tier is required on events and must be A2.",
                    ),
                ),
            )
    }
}
