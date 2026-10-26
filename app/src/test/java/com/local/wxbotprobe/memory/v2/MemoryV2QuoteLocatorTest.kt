package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2QuoteLocatorTest {
    @Test
    fun `locates quote using unicode scalar offsets`() {
        val located = MemoryV2QuoteLocator.locate(
            body = "😀我住在杭州。",
            quote = "我住在杭州",
        )

        assertEquals(1, located.startCodepoint)
        assertEquals(6, located.endCodepoint)
        assertEquals(
            MemoryV2QuoteLocator.sha256("😀我住在杭州。"),
            located.sourceSha256,
        )
    }

    @Test
    fun `rejects missing and duplicate quotes`() {
        assertError(MemoryV2ErrorCode.QUOTE_NOT_FOUND) {
            MemoryV2QuoteLocator.locate("今晚见面", "明天见面")
        }
        assertError(MemoryV2ErrorCode.QUOTE_AMBIGUOUS) {
            MemoryV2QuoteLocator.locate("好，好", "好")
        }
    }

    @Test
    fun `rejects oversized and malformed unicode quotes`() {
        assertError(MemoryV2ErrorCode.QUOTE_TOO_LARGE) {
            MemoryV2QuoteLocator.locate(
                body = "abcd",
                quote = "abcd",
                maxQuoteUtf8Bytes = 3,
            )
        }
        assertError(MemoryV2ErrorCode.INVALID_UNICODE) {
            MemoryV2QuoteLocator.locate(
                body = "bad\uD800text",
                quote = "bad",
            )
        }
    }

    @Test
    fun `source hash is deterministic utf8 sha256`() {
        val hash = MemoryV2QuoteLocator.sha256("杭州")
        assertTrue(hash.matches(Regex("^[a-f0-9]{64}$")))
        assertEquals(hash, MemoryV2QuoteLocator.sha256("杭州"))
    }

    private fun assertError(
        expected: MemoryV2ErrorCode,
        block: () -> Unit,
    ) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is MemoryV2Exception)
        assertEquals(expected, (error as MemoryV2Exception).code)
    }
}
