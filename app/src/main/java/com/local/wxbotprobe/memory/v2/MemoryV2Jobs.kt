package com.local.wxbotprobe.memory.v2

import org.json.JSONArray
import org.json.JSONObject

data class MemoryJobEnqueueResult(
    val status: String,
    val jobId: String,
    val envelope: TrustedMemoryEnvelope? = null,
)

data class LeasedMemoryJob(
    val jobId: String,
    val scopeId: String,
    val sessionId: String,
    val kind: String,
    val attempts: Int,
    val maxAttempts: Int,
    val envelope: TrustedMemoryEnvelope,
)

data class MemoryOverflowRepairResult(
    val changed: Boolean,
    val splitJobId: String? = null,
    val errorCode: String? = null,
)

data class ValidatedQuote(
    val messageId: String,
    val sourceRevision: Int,
    val quote: String,
    val location: LocatedQuote,
)

data class ValidatedMemoryCandidate(
    val candidateId: String,
    val operation: String,
    val relationType: String?,
    val subjectId: String,
    val viewpointId: String?,
    val predicate: String,
    val text: String,
    val valueJson: String,
    val epistemic: String,
    val reason: String,
    val targetMemoryId: String?,
    val expectedVersion: Int?,
    val temporalJson: String,
    val commitmentState: String?,
    val importanceHint: Double,
    val evidence: List<ValidatedQuote>,
)

data class ValidatedExtractionBatch(
    val envelope: TrustedMemoryEnvelope,
    val processedMessageIds: List<String>,
    val candidates: List<ValidatedMemoryCandidate>,
)

data class ValidatedWorkingItem(
    val section: String,
    val text: String,
    val evidence: List<ValidatedQuote>,
)

data class ValidatedSummaryBatch(
    val envelope: TrustedMemoryEnvelope,
    val processedMessageIds: List<String>,
    val events: List<ValidatedMemoryCandidate>,
    val workingItems: List<ValidatedWorkingItem>,
)

fun TrustedMemoryEnvelope.toJsonObject(): JSONObject {
    return JSONObject()
        .put("schema_version", schemaVersion)
        .put("scope_id", scopeId)
        .put("session_id", sessionId)
        .put("job_id", jobId)
        .put("kind", kind)
        .put("base_scope_revision", baseScopeRevision)
        .put("base_deletion_epoch", baseDeletionEpoch)
        .put("base_working_summary_version", baseWorkingSummaryVersion)
        .put("persona_revision", personaRevision)
        .put("import_generation", importGeneration)
        .put("policy_version", policyVersion)
        .put("config_version", configVersion)
        .put("prompt_version", promptVersion)
        .put(
            "sources",
            JSONArray().apply {
                sources.forEach {
                    put(
                        JSONObject()
                            .put("message_id", it.messageId)
                            .put("revision", it.revision)
                            .put("sha256", it.sha256),
                    )
                }
            },
        )
        .put("new_source_ids", JSONArray(newSourceIds))
        .put(
            "target_versions",
            JSONObject().apply {
                targetVersions.forEach { (key, value) -> put(key, value) }
            },
        )
        .put("created_at", createdAt)
}
