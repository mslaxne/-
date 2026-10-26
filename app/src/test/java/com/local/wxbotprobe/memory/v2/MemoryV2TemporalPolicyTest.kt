package com.local.wxbotprobe.memory.v2

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2TemporalPolicyTest {
    private val anchor = Instant.parse("2026-10-24T04:00:00Z").epochSecond
    private val thirtyDays = 30L * 24L * 60L * 60L

    @Test
    fun `tomorrow event expires at the end of its local date`() {
        val expiry = MemoryV2TemporalPolicy.expiryFor(
            reason = "recent_event",
            temporalJson = temporal(
                localDate = "2026-10-25",
                original = "明天",
            ),
            commitmentState = "active",
            sourceAnchorEpochSeconds = anchor,
            ttlSeconds = thirtyDays,
            fallbackTimezone = "Asia/Shanghai",
        )

        assertEquals(
            Instant.parse("2026-10-25T15:59:59Z").epochSecond,
            expiry,
        )
    }

    @Test
    fun `unresolved date keeps the A2 ttl`() {
        val expiry = MemoryV2TemporalPolicy.expiryFor(
            reason = "recent_event",
            temporalJson = temporal(localDate = null, original = "改天"),
            commitmentState = null,
            sourceAnchorEpochSeconds = anchor,
            ttlSeconds = thirtyDays,
            fallbackTimezone = "Asia/Shanghai",
        )

        assertEquals(anchor + thirtyDays, expiry)
    }

    @Test
    fun `past event keeps the A2 ttl instead of expiring immediately`() {
        val expiry = MemoryV2TemporalPolicy.expiryFor(
            reason = "recent_event",
            temporalJson = temporal(
                localDate = "2026-10-23",
                original = "昨天",
            ),
            commitmentState = "completed",
            sourceAnchorEpochSeconds = anchor,
            ttlSeconds = thirtyDays,
            fallbackTimezone = "Asia/Shanghai",
        )

        assertEquals(anchor + thirtyDays, expiry)
    }

    @Test
    fun `same day plan can use its date while a completed event keeps ttl`() {
        val active = MemoryV2TemporalPolicy.expiryFor(
            reason = "recent_event",
            temporalJson = temporal(
                localDate = "2026-10-24",
                original = "今晚",
            ),
            commitmentState = "active",
            sourceAnchorEpochSeconds = anchor,
            ttlSeconds = thirtyDays,
            fallbackTimezone = "Asia/Shanghai",
        )
        val completed = MemoryV2TemporalPolicy.expiryFor(
            reason = "recent_event",
            temporalJson = temporal(
                localDate = "2026-10-24",
                original = "今天",
            ),
            commitmentState = "completed",
            sourceAnchorEpochSeconds = anchor,
            ttlSeconds = thirtyDays,
            fallbackTimezone = "Asia/Shanghai",
        )

        assertEquals(
            Instant.parse("2026-10-24T15:59:59Z").epochSecond,
            active,
        )
        assertEquals(anchor + thirtyDays, completed)
    }

    @Test
    fun `rendered timing tells the reply model both event date and expiry`() {
        val text = MemoryV2TemporalPolicy.renderTiming(
            temporalJson = temporal(
                localDate = "2026-10-25",
                original = "明天",
            ),
            expiresAtEpochSeconds =
                Instant.parse("2026-10-25T15:59:59Z").epochSecond,
            fallbackTimezone = "Asia/Shanghai",
        ).orEmpty()

        assertTrue(text.contains("2026-10-25"))
        assertTrue(text.contains("明天"))
        assertTrue(text.contains("有效至 2026-10-25 23:59"))
    }

    private fun temporal(localDate: String?, original: String): String {
        return JSONObject()
            .put("occurred_at", JSONObject.NULL)
            .put("local_date", localDate ?: JSONObject.NULL)
            .put("timezone", "Asia/Shanghai")
            .put("precision", if (localDate == null) "unknown" else "date")
            .put("original", original)
            .toString()
    }
}
