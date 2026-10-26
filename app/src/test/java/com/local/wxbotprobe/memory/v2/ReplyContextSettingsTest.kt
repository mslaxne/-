package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyContextSettingsTest {
    @Test
    fun `stored mode falls back to layered`() {
        assertEquals(
            ReplyContextMode.LAYERED,
            ReplyContextMode.fromStored(null),
        )
        assertEquals(
            ReplyContextMode.LAYERED,
            ReplyContextMode.fromStored("unknown"),
        )
        assertEquals(
            ReplyContextMode.LEGACY,
            ReplyContextMode.fromStored("legacy"),
        )
    }

    @Test
    fun `layered context wins when available`() {
        val result = ReplyContextFallbackPolicy.build(
            primary = { "layered" },
            fallback = { "legacy" },
        )

        assertEquals("layered", result.text)
        assertEquals(ReplyContextMode.LAYERED, result.source)
    }

    @Test
    fun `blank or failed layered context falls back`() {
        val blank = ReplyContextFallbackPolicy.build(
            primary = { "  " },
            fallback = { "legacy" },
        )
        val failed = ReplyContextFallbackPolicy.build(
            primary = { error("snapshot failed") },
            fallback = { "legacy" },
        )

        assertEquals(ReplyContextMode.LEGACY, blank.source)
        assertEquals("legacy", blank.text)
        assertEquals(ReplyContextMode.LEGACY, failed.source)
        assertEquals("legacy", failed.text)
        assertNotNull(failed.error)
    }

    @Test
    fun `fallback failure returns empty text without throwing`() {
        val result = ReplyContextFallbackPolicy.build(
            primary = { error("snapshot failed") },
            fallback = { error("legacy failed") },
        )

        assertTrue(result.text.isEmpty())
        assertEquals(ReplyContextMode.LEGACY, result.source)
        assertTrue(result.error.orEmpty().contains("legacy failed"))
    }
}
