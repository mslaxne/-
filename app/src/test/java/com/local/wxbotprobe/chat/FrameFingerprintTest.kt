package com.local.wxbotprobe.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFingerprintTest {
    @Test
    fun `tiny camera noise counts as the same scene`() {
        val first = FrameFingerprint(IntArray(200) { 0xFF202020.toInt() })
        val second = FrameFingerprint(IntArray(200) { index ->
            if (index % 100 == 0) 0xFF262626.toInt() else 0xFF202020.toInt()
        })
        assertTrue(first.isSameScene(second))
    }

    @Test
    fun `small localized bubble change remains below scene threshold`() {
        val first = FrameFingerprint(IntArray(200) { 0xFF202020.toInt() })
        val second = FrameFingerprint(IntArray(200) { index ->
            if (index == 100) 0xFFFFFFFF.toInt() else 0xFF202020.toInt()
        })
        assertTrue(first.isSameScene(second))
    }

    @Test
    fun `large content change crosses scene threshold`() {
        val first = FrameFingerprint(IntArray(200) { 0xFF202020.toInt() })
        val second = FrameFingerprint(IntArray(200) { index ->
            if (index % 10 == 0) 0xFFFFFFFF.toInt() else 0xFF202020.toInt()
        })
        assertFalse(first.isSameScene(second))
    }
}
