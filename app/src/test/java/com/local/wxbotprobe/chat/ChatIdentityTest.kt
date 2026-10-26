package com.local.wxbotprobe.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatIdentityTest {
    private fun source(pattern: Int, belowHeaderInk: Boolean = false): PixelSource {
        return object : PixelSource {
            override val width: Int = 1080
            override val height: Int = 2400

            override fun colorAt(x: Int, y: Int): PixelColor {
                val inHeader = y in 96..216
                val dark = (inHeader && when (pattern) {
                    1 -> x in 380..450 || x in 560..650
                    2 -> x in 250..330 || x in 700..780
                    else -> false
                }) || (belowHeaderInk && y in 210..240 && x in 200..500)
                return if (dark) PixelColor(20, 20, 20) else PixelColor(255, 255, 255)
            }
        }
    }

    @Test
    fun `same title area keeps the same fingerprint`() {
        assertEquals(
            ChatHeaderIdentity.fingerprintFrom(source(1)),
            ChatHeaderIdentity.fingerprintFrom(source(1)),
        )
    }

    @Test
    fun `different title area changes the fingerprint`() {
        assertNotEquals(
            ChatHeaderIdentity.fingerprintFrom(source(1)),
            ChatHeaderIdentity.fingerprintFrom(source(2)),
        )
    }

    @Test
    fun `content below the measured title bar does not affect the fingerprint`() {
        assertEquals(
            ChatHeaderIdentity.fingerprintFrom(source(1)),
            ChatHeaderIdentity.fingerprintFrom(source(1, belowHeaderInk = true)),
        )
    }

    @Test
    fun `blank header is rejected`() {
        assertEquals(0L, ChatHeaderIdentity.fingerprintFrom(source(0)))
    }

    @Test
    fun `title ignores status and navigation noise`() {
        val rows = listOf(
            OcrRow(31, 894, 946, "4G HD", 0.9),
            OcrRow(128, 79, 144, "11", 0.8),
            OcrRow(129, 399, 668, "文件传输助手", 0.94),
        )
        assertEquals(
            "文件传输助手",
            ChatHeaderIdentity.titleFrom(rows, width = 1080, height = 2400),
        )
    }

    @Test
    fun `title prefers centered text over off center high confidence noise`() {
        val rows = listOf(
            OcrRow(170, 200, 420, "微信(8)", 0.99),
            OcrRow(175, 500, 580, "A", 0.55),
            OcrRow(195, 470, 610, "11:59", 0.99),
        )
        assertEquals(
            "A",
            ChatHeaderIdentity.titleFrom(rows, width = 1080, height = 2400),
        )
    }

    @Test
    fun `generic wechat titles are not usable chat identities`() {
        assertFalse(ChatIdentityPolicy.isUsableTitle("微信"))
        assertFalse(ChatIdentityPolicy.isUsableTitle("微信(8)"))
        assertFalse(ChatIdentityPolicy.isUsableTitle("11:59"))
        assertFalse(ChatIdentityPolicy.isUsableTitle("8"))
        assertFalse(ChatIdentityPolicy.isUsableTitle("WeChat"))
        assertTrue(ChatIdentityPolicy.isUsableTitle("A"))
    }

    @Test
    fun `conversation key combines title and fingerprint`() {
        val first = ChatIdentity("文件传输助手", 0x1234L)
        val second = ChatIdentity("文件传输助手", 0x5678L)
        assertTrue(first.conversationKey.contains("文件传输助手"))
        assertNotEquals(first.conversationKey, second.conversationKey)
    }
}
