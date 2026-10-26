package com.local.wxbotprobe.memory.v2

data class MemoryScopeKey(
    val ownerId: String,
    val contactId: String,
    val agentId: String,
    val personaId: String,
    val realmId: String,
)

data class TrustedScopeContext(
    val scopeId: String,
    val key: MemoryScopeKey,
    val personaRevision: Int,
    val revision: Int,
    val deletionEpoch: Int,
    val importGeneration: Int,
    val retentionPolicy: String,
    val state: String,
    val restoreGuardId: String,
)

data class MemorySourceRef(
    val messageId: String,
    val revision: Int,
    val sha256: String,
)

data class TrustedMemoryEnvelope(
    val schemaVersion: String,
    val scopeId: String,
    val sessionId: String,
    val jobId: String,
    val kind: String,
    val baseScopeRevision: Int,
    val baseDeletionEpoch: Int,
    val baseWorkingSummaryVersion: Int,
    val personaRevision: Int,
    val importGeneration: Int,
    val policyVersion: String,
    val configVersion: String,
    val promptVersion: String,
    val sources: List<MemorySourceRef>,
    val newSourceIds: List<String>,
    val targetVersions: Map<String, Int>,
    val createdAt: Long,
)

data class AppendMessageRequest(
    val clientMessageId: String,
    val sessionId: String?,
    val role: String,
    val text: String,
    val occurredAt: Long,
    val receivedAt: Long,
    val timezone: String,
    val hostExpiresAt: Long? = null,
)

data class AppendMessageResult(
    val status: String,
    val scopeId: String,
    val sessionId: String,
    val messageId: String,
    val seq: Int,
    val errorCode: String? = null,
)

data class MemoryV2OpenSession(
    val sessionId: String,
    val nextSeq: Int,
    val updatedAt: Long,
)

data class MemoryV2SessionRecovery(
    val session: MemoryV2OpenSession,
    val closedDuplicates: Int,
)

data class MemoryV2Health(
    val scopeId: String,
    val activeMemories: Int,
    val activeSources: Int,
    val pendingJobs: Int,
    val pendingOutbox: Int,
    val usedBytes: Long,
)

enum class MemoryV2ErrorCode {
    UNAUTHORIZED_SCOPE,
    INVALID_ARGUMENT,
    INVALID_UNICODE,
    QUOTE_NOT_FOUND,
    QUOTE_AMBIGUOUS,
    QUOTE_TOO_LARGE,
    MODEL_CONTEXT_UNKNOWN,
    MODEL_PROFILE_MISMATCH,
    MODEL_PROFILE_STALE,
    UNSUPPORTED_TOKEN_MODE,
    OUTPUT_BUDGET_INVALID,
    CONTEXT_BUDGET_EXCEEDED,
    RESOURCE_CAPACITY_EXHAUSTED,
    UNKNOWN_PREDICATE,
    FIELD_NOT_EDITABLE,
    VIEWPOINT_REQUIRED,
    VALUE_TYPE_INVALID,
    ITEM_TOO_LARGE,
    INVALID_EVIDENCE,
    SOURCE_UNAVAILABLE,
    SOURCE_CHANGED,
    SOURCE_FORGOTTEN,
    CONFLICT_NEEDS_REVIEW,
    STALE_VERSION,
    IDEMPOTENCY_CONFLICT,
    OPERATION_EXPIRED,
    MEMORY_NOT_FOUND,
    FIELD_BLOCKED,
    RELEARN_REQUIRED,
    MEMORY_EXPIRED,
    JOB_NOT_FOUND,
    JOB_TERMINAL,
    SCHEMA_INVALID,
    ENVELOPE_MISMATCH,
    COVERAGE_MISMATCH,
    SOURCE_SET_INVALID,
    PROMPT_VERSION_MISMATCH,
    STALE_EPOCH,
    STALE_GENERATION,
    STALE_PERSONA,
    STALE_POLICY,
    STALE_SUMMARY,
    CALL_BUDGET_EXHAUSTED,
    LEGACY_MAPPING_NOT_APPROVED,
    LEGACY_MAPPING_STALE,
    LEGACY_DELETION_BARRIER,
    RESTORE_FENCE_MISMATCH,
    STORAGE_UNAVAILABLE,
}

class MemoryV2Exception(
    val code: MemoryV2ErrorCode,
    detail: String = "",
) : IllegalStateException(
    if (detail.isBlank()) code.name else "${code.name}: $detail",
)
