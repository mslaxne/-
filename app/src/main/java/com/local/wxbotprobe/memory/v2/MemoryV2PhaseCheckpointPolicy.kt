package com.local.wxbotprobe.memory.v2

import org.json.JSONObject

data class MemoryV2PhaseCheckpoint(
    val closed: Boolean,
    val processedMessageIds: List<String>,
    val reason: String,
)

object MemoryV2PhaseCheckpointPolicy {
    fun validate(
        allSourceIds: List<String>,
        processedMessageIds: List<String>,
        phase: JSONObject?,
        phaseAware: Boolean,
    ): MemoryV2PhaseCheckpoint {
        if (!phaseAware) {
            if (processedMessageIds != allSourceIds) {
                throw MemoryV2Exception(MemoryV2ErrorCode.COVERAGE_MISMATCH)
            }
            return MemoryV2PhaseCheckpoint(
                closed = true,
                processedMessageIds = processedMessageIds,
                reason = "legacy extraction batch",
            )
        }
        val checkpoint = phase
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        if (
            !checkpoint.has("closed") ||
            !checkpoint.has("closed_through_message_id") ||
            !checkpoint.has("reason")
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val closed = checkpoint.opt("closed") as? Boolean
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        val reason = checkpoint.optString("reason")
            .takeIf(String::isNotBlank)
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        val rawBoundary = checkpoint.opt("closed_through_message_id")
        val boundary = when {
            rawBoundary == null || rawBoundary == JSONObject.NULL -> null
            rawBoundary is String && rawBoundary.isNotBlank() -> rawBoundary
            else -> throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
            }
        if (closed) {
            if (
                processedMessageIds.isEmpty() ||
                processedMessageIds != allSourceIds.take(processedMessageIds.size) ||
                boundary != processedMessageIds.last()
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.COVERAGE_MISMATCH)
            }
        } else if (processedMessageIds.isNotEmpty() || boundary != null) {
            throw MemoryV2Exception(MemoryV2ErrorCode.COVERAGE_MISMATCH)
        }
        return MemoryV2PhaseCheckpoint(
            closed = closed,
            processedMessageIds = processedMessageIds,
            reason = reason,
        )
    }
}
