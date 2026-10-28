package com.local.wxbotprobe.memory.v2

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

object MemoryV2TemporalPolicy {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val openCommitmentStates = setOf(
        "proposed",
        "active",
        "overdue_unknown",
    )

    fun expiryFor(
        reason: String,
        temporalJson: String,
        commitmentState: String?,
        sourceAnchorEpochSeconds: Long,
        ttlSeconds: Long,
        fallbackTimezone: String,
    ): Long {
        val ttlExpiry = sourceAnchorEpochSeconds + ttlSeconds
        if (reason != "recent_event") {
            return ttlExpiry
        }
        val temporal = runCatching { JSONObject(temporalJson) }.getOrNull()
            ?: return ttlExpiry
        val zone = timezone(temporal.optString("timezone"), fallbackTimezone)
            ?: return ttlExpiry
        val localDate = temporal.optString("local_date")
            .takeIf { it.isNotBlank() && !temporal.isNull("local_date") }
            ?.let { runCatching { LocalDate.parse(it, dateFormatter) }.getOrNull() }
            ?: return ttlExpiry
        val sourceDate = Instant.ofEpochSecond(sourceAnchorEpochSeconds)
            .atZone(zone)
            .toLocalDate()
        val isOpenSameDayEvent = localDate == sourceDate &&
            commitmentState in openCommitmentStates
        if (localDate.isBefore(sourceDate) || (
                localDate == sourceDate && !isOpenSameDayEvent
                )
        ) {
            return ttlExpiry
        }
        val endOfLocalDate = localDate.plusDays(1)
            .atStartOfDay(zone)
            .toEpochSecond() - 1L
        return minOf(ttlExpiry, endOfLocalDate)
    }

    fun renderTiming(
        temporalJson: String,
        expiresAtEpochSeconds: Long?,
        fallbackTimezone: String,
    ): String? {
        val temporal = runCatching { JSONObject(temporalJson) }.getOrNull()
            ?: return expiresAtEpochSeconds?.let {
                "有效至 ${formatDateTime(it, fallbackTimezone)}"
            }
        val zone = timezone(temporal.optString("timezone"), fallbackTimezone)
            ?: return null
        val original = temporal.optString("original")
            .trim()
            .take(80)
            .takeIf { it.isNotBlank() }
        val localDate = temporal.optString("local_date")
            .takeIf { it.isNotBlank() && !temporal.isNull("local_date") }
            ?.let { runCatching { LocalDate.parse(it, dateFormatter) }.getOrNull() }
        val occurredAt = temporal.optLong("occurred_at", 0L)
            .takeIf { it > 0L }
        val timing = when {
            localDate != null -> buildString {
                append(localDate.format(dateFormatter))
                if (original != null) {
                    append("（")
                    append("来源原话：")
                    append(original)
                    append("）")
                }
            }
            original != null -> original
            occurredAt != null -> formatDateTime(occurredAt, zone)
            else -> null
        }
        val expiry = expiresAtEpochSeconds?.let {
            "有效至 ${formatDateTime(it, zone)}"
        }
        return listOfNotNull(timing, expiry)
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString("；")
    }

    private fun timezone(value: String, fallback: String): ZoneId? {
        return runCatching { ZoneId.of(value) }.getOrNull()
            ?: runCatching { ZoneId.of(fallback) }.getOrNull()
    }

    private fun formatDateTime(epochSeconds: Long, timezone: String): String {
        val zone = timezone(timezone, "UTC") ?: ZoneId.of("UTC")
        return Instant.ofEpochSecond(epochSeconds)
            .atZone(zone)
            .format(dateTimeFormatter)
    }

    private fun formatDateTime(epochSeconds: Long, zone: ZoneId): String {
        return Instant.ofEpochSecond(epochSeconds)
            .atZone(zone)
            .format(dateTimeFormatter)
    }
}
