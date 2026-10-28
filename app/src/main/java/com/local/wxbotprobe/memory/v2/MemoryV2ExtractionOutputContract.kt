package com.local.wxbotprobe.memory.v2

import org.json.JSONArray
import org.json.JSONObject

object MemoryV2ExtractionOutputContract {
    fun json(): JSONObject {
        return JSONObject()
            .put(
                "top_level_required",
                JSONArray(
                    listOf(
                        "contract",
                        "envelope",
                        "processed_message_ids",
                        "phase",
                        "candidates",
                    ),
                ),
            )
            .put("contract_value", "ExtractionBatch")
            .put(
                "candidate_required",
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
            .put("tier_enum", JSONArray(listOf("A0", "A1", "A2")))
            .put(
                "phase_required",
                JSONArray(
                    listOf(
                        "closed",
                        "closed_through_message_id",
                        "reason",
                    ),
                ),
            )
            .put(
                "operation_enum",
                JSONArray(MemoryV2ReconciliationPolicy.operations),
            )
            .put(
                "reason_enum",
                JSONArray(
                    listOf(
                        "durable_preference",
                        "confirmed_commitment",
                        "important_experience",
                        "attributed_impression",
                        "recent_event",
                    ),
                ),
            )
            .put(
                "epistemic_enum",
                JSONArray(listOf("declared", "observed", "inferred")),
            )
            .put(
                "temporal_default",
                JSONObject()
                    .put("occurred_at", JSONObject.NULL)
                    .put("local_date", JSONObject.NULL)
                    .put("timezone", "source timezone")
                    .put("precision", "unknown")
                    .put("original", ""),
            )
            .put("additional_properties", false)
            .put(
                "field_rules",
                JSONArray(
                    listOf(
                        "Use value, never object_value.",
                        "phase.closed=false means no source has been consumed: " +
                            "processed_message_ids must be empty and candidates must be empty.",
                        "When phase.closed=true, processed_message_ids must be the " +
                            "leading contiguous prefix ending at closed_through_message_id.",
                        "Candidates may cite evidence only from processed_message_ids.",
                        "A target_memory_id may appear only once per extraction batch.",
                        "tier is required and must be A0, A1 or A2. " +
                            "A3 is the session working_summary, not a candidate tier.",
                        "A0 is only for stable, explicit core identity, relationship " +
                            "or long-lived boundary facts whose predicate allows A0.",
                        "New A0 candidates are stored for human review and do not " +
                            "become active until approved.",
                        "working_summary_context and recent_source_context are " +
                            "reference-only and cannot be used as evidence.",
                        "A1 is for durable facts, preferences, commitments and " +
                            "important long-lived experiences.",
                        "A2 is for time-bound recent events or temporary states.",
                        "The envelope object must contain only job_id and exactly " +
                            "match the frozen job_id.",
                        "ADD uses target_memory_id=null and expected_version=null.",
                        "CORROBORATE, SUPERSEDE, CONTRADICT, RETRACT and NOOP " +
                            "must target an existing memory ID and its exact version.",
                        "CORROBORATE only adds independent evidence to the same value.",
                        "Use NOOP when the source only acknowledges a remembered " +
                            "fact, repeats known content, or says there is no new " +
                            "information. Do not use CORROBORATE for those sources.",
                        "SUPERSEDE replaces a changed value only when the new source " +
                            "clearly updates the old fact.",
                        "CONTRADICT does not overwrite; it records unresolved conflict.",
                        "RELATE creates a recent event and links it to a stable A1. " +
                            "Use SUPPORTS only for direct confirmation and " +
                            "ELABORATES for contextual detail.",
                        "For RELATE, target_memory_id must be one of " +
                            "relate_target_ids. Never target A2.",
                        "When an existing A1 relation role or stable preference is " +
                            "directly involved in a new recent event, use RELATE with " +
                            "that A1 as target. Do not duplicate the stable fact as " +
                            "ADD or emit an unrelated event without relation_type.",
                        "RETRACT removes only the targeted existing value.",
                        "Do not output kind, pinned or permission fields.",
                        "For a stable preference use durable_preference.",
                        "For a dated recent_event, resolve relative dates into " +
                            "local_date YYYY-MM-DD using the source occurred_at and timezone.",
                        "Use local_date null when the dated event cannot be resolved " +
                            "without guessing.",
                        "Use null rather than \"none\" for commitment_state when no " +
                            "commitment applies.",
                        "Write natural-language text from the current persona: use " +
                            "\"对方\" for the contact and \"你\" for the current agent.",
                        "temporal must contain occurred_at, local_date, timezone, " +
                            "precision and original.",
                        "epistemic must be exactly declared, observed or inferred.",
                        "commitment_state must be null or one of proposed, active, " +
                            "overdue_unknown, completed, cancelled.",
                        "importance_hint must be a number from 0 to 1.",
                    ),
                ),
            )
    }
}
