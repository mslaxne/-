package com.local.wxbotprobe.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendSafetyGuardTest {
    @Test
    fun `interactive unlocked screen is allowed`() {
        assertNull(SendSafetyGuard.check(interactive = true, locked = false))
    }

    @Test
    fun `locked or dark screen blocks sending`() {
        assertEquals(
            SendBlockReason.SCREEN_OFF,
            SendSafetyGuard.check(false, false),
        )
        assertEquals(
            SendBlockReason.LOCKED,
            SendSafetyGuard.check(true, true),
        )
    }
}
