package com.local.wxbotprobe.memory.v2

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryV2CandidateNormalizerTest {
    @Test
    fun `epistemic synonyms normalize to contract values`() {
        assertEquals("declared", MemoryV2CandidateNormalizer.epistemic("asserted"))
        assertEquals("declared", MemoryV2CandidateNormalizer.epistemic("certain"))
        assertEquals("observed", MemoryV2CandidateNormalizer.epistemic("seen"))
        assertEquals("inferred", MemoryV2CandidateNormalizer.epistemic("uncertain"))
        assertNull(MemoryV2CandidateNormalizer.epistemic("guessed"))
    }

    @Test
    fun `current prompt epistemic values are strict`() {
        assertEquals(
            "declared",
            MemoryV2CandidateNormalizer.epistemic(
                "declared",
                allowSynonyms = false,
            ),
        )
        assertNull(
            MemoryV2CandidateNormalizer.epistemic(
                "asserted",
                allowSynonyms = false,
            ),
        )
    }

    @Test
    fun `importance labels and numbers normalize to unit interval`() {
        assertEquals(0.8, MemoryV2CandidateNormalizer.importance("high"), 0.0)
        assertEquals(0.5, MemoryV2CandidateNormalizer.importance("medium"), 0.0)
        assertEquals(0.7, MemoryV2CandidateNormalizer.importance("0.7"), 0.0)
        assertEquals(1.0, MemoryV2CandidateNormalizer.importance(4), 0.0)
        assertEquals(0.5, MemoryV2CandidateNormalizer.importance(null), 0.0)
        assertEquals(
            0.5,
            MemoryV2CandidateNormalizer.importance(
                "medium",
                allowLabelSynonyms = false,
            ),
            0.0,
        )
    }

    @Test
    fun `partial temporal is completed using safe defaults`() {
        val normalized = MemoryV2CandidateNormalizer.temporal(
            JSONObject()
                .put("local_date", "2026-10-24")
                .put("original", "今天"),
            fallbackTimezone = "Asia/Shanghai",
        )!!

        assertEquals(JSONObject.NULL, normalized.get("occurred_at"))
        assertEquals("2026-10-24", normalized.getString("local_date"))
        assertEquals("Asia/Shanghai", normalized.getString("timezone"))
        assertEquals("date", normalized.getString("precision"))
        assertEquals("今天", normalized.getString("original"))
    }

    @Test
    fun `precision synonyms normalize without inventing an exact date`() {
        val second = MemoryV2CandidateNormalizer.temporal(
            JSONObject()
                .put("occurred_at", 1_792_820_374)
                .put("local_date", "2026-10-24")
                .put("timezone", "Asia/Shanghai")
                .put("precision", "second")
                .put("original", "刚刚"),
            fallbackTimezone = "Asia/Shanghai",
        )!!
        val day = MemoryV2CandidateNormalizer.temporal(
            JSONObject()
                .put("local_date", "2026-10-24")
                .put("timezone", "Asia/Shanghai")
                .put("precision", "exact")
                .put("original", "今天"),
            fallbackTimezone = "Asia/Shanghai",
        )
        val minute = MemoryV2CandidateNormalizer.temporal(
            JSONObject()
                .put("occurred_at", 1_792_816_097)
                .put("local_date", "2026-10-24")
                .put("timezone", "Asia/Shanghai")
                .put("precision", "minute")
                .put("original", "刚刚"),
            fallbackTimezone = "Asia/Shanghai",
        )!!

        assertEquals("instant", second.getString("precision"))
        assertEquals("instant", minute.getString("precision"))
        assertNull(day)
    }

    @Test
    fun `invalid temporal values remain rejected`() {
        assertNull(
            MemoryV2CandidateNormalizer.temporal(
                JSONObject()
                    .put("local_date", "24/10/2026")
                    .put("timezone", "Asia/Shanghai")
                    .put("precision", "date")
                    .put("original", "日期错误"),
                fallbackTimezone = "Asia/Shanghai",
            ),
        )
        assertNull(
            MemoryV2CandidateNormalizer.temporal(
                JSONObject()
                    .put("occurred_at", -1)
                    .put("timezone", "Asia/Shanghai")
                    .put("precision", "instant")
                    .put("original", "错误时间"),
                fallbackTimezone = "Asia/Shanghai",
            ),
        )
    }
}
