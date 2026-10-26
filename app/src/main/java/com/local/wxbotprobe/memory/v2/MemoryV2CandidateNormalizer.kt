package com.local.wxbotprobe.memory.v2

import java.time.LocalDate
import org.json.JSONObject

object MemoryV2CandidateNormalizer {
    fun epistemic(value: String): String? {
        return when (value.lowercase()) {
            "declared", "asserted", "certain", "confirmed", "stated" -> "declared"
            "observed", "seen" -> "observed"
            "inferred", "uncertain", "possible", "speculative" -> "inferred"
            else -> null
        }
    }

    fun importance(value: Any?): Double {
        val parsed = when (value) {
            is Number -> value.toDouble()
            is String -> {
                when (value.lowercase()) {
                    "high" -> 0.8
                    "medium" -> 0.5
                    "low" -> 0.2
                    else -> value.toDoubleOrNull()
                }
            }
            else -> null
        } ?: return 0.5
        if (!parsed.isFinite()) {
            return 0.5
        }
        return parsed.coerceIn(0.0, 1.0)
    }

    fun temporal(
        value: JSONObject,
        fallbackTimezone: String,
    ): JSONObject? {
        val localDateValue = value.opt("local_date")
        if (
            localDateValue != null &&
            localDateValue != JSONObject.NULL &&
            (
                localDateValue !is String ||
                    runCatching { LocalDate.parse(localDateValue) }.isFailure
                )
        ) {
            return null
        }
        val occurredAtValue = value.opt("occurred_at")
        if (
            occurredAtValue != null &&
            occurredAtValue != JSONObject.NULL &&
            (
                occurredAtValue !is Number ||
                    occurredAtValue.toLong() < 0L
                )
        ) {
            return null
        }
        val originalValue = value.opt("original")
        if (originalValue != null && originalValue != JSONObject.NULL && originalValue !is String) {
            return null
        }
        val timezone = value.opt("timezone")
            ?.takeIf { it is String && it.isNotBlank() }
            ?: fallbackTimezone
        val providedPrecision = value.optString("precision")
            .takeIf { it.isNotBlank() }
            ?.let {
                when (it.lowercase()) {
                    "instant", "exact", "second", "minute", "hour" -> "instant"
                    "date", "day" -> "date"
                    "unknown" -> "unknown"
                    else -> null
                }
            }
        if (!value.isNull("precision") && providedPrecision == null) {
            return null
        }
        val precision = providedPrecision ?: when {
            occurredAtValue is Number -> "instant"
            localDateValue is String && localDateValue.isNotBlank() -> "date"
            else -> "unknown"
        }
        if (
            precision == "instant" && occurredAtValue !is Number ||
            precision == "date" && (
                localDateValue !is String || localDateValue.isBlank()
                )
        ) {
            return null
        }
        return JSONObject()
            .put("occurred_at", occurredAtValue ?: JSONObject.NULL)
            .put("local_date", localDateValue ?: JSONObject.NULL)
            .put("timezone", timezone)
            .put("precision", precision)
            .put("original", originalValue?.toString().orEmpty())
    }
}
