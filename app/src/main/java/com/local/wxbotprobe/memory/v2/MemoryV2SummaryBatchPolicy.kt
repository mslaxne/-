package com.local.wxbotprobe.memory.v2

data class MemorySummaryJobSnapshot(
    val state: String,
    val errorCode: String?,
    val sourceCount: Int,
)

object MemoryV2SummaryBatchPolicy {
    const val MIGRATION_ERROR = "SUMMARY_SCHEDULER_MIGRATION"

    fun effectiveSourceLimit(
        configuredMaximum: Int,
        checkpointSize: Int,
    ): Int {
        require(configuredMaximum >= 0) {
            "configuredMaximum must not be negative"
        }
        val checkpoint = checkpointSize.coerceAtLeast(1)
        return if (configuredMaximum > 0) {
            minOf(configuredMaximum, checkpoint)
        } else {
            checkpoint
        }
    }

    fun nextBatchLimit(
        last: MemorySummaryJobSnapshot?,
        maxSources: Int,
    ): Int? {
        require(maxSources >= 0) { "maxSources must not be negative" }
        if (last == null) {
            return maxSources
        }
        val limit = when {
            last.state == "saved" -> last.sourceCount.coerceAtLeast(1)
            last.state in setOf("failed", "cancelled") &&
                last.errorCode == MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED.name ->
                (last.sourceCount / 2).coerceAtLeast(1)
            last.state == "cancelled" && last.errorCode == MIGRATION_ERROR ->
                maxSources
            last.state == "cancelled" &&
                last.errorCode in setOf(
                    MemoryV2ErrorCode.STALE_SUMMARY.name,
                    MemoryV2ErrorCode.STALE_EPOCH.name,
                ) -> maxSources
            else -> null
        } ?: return null
        return if (maxSources > 0) limit.coerceAtMost(maxSources) else limit
    }
}
