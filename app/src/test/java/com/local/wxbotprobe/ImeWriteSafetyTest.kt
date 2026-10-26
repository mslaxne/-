package com.local.wxbotprobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeWriteSafetyTest {
    @Test
    fun weChatTargetIsAllowed() {
        val decision = ImeWritePolicy.check(RouteCImePolicy.WECHAT_PACKAGE)
        assertTrue(decision.allowed)
        assertTrue(decision.message.isEmpty())
    }

    @Test
    fun otherApplicationIsRejected() {
        val decision = ImeWritePolicy.check("com.example.other")
        assertFalse(decision.allowed)
        assertTrue(decision.message.contains("com.example.other"))
    }

    @Test
    fun missingTargetIsRejected() {
        assertFalse(ImeWritePolicy.check(null).allowed)
        assertFalse(ImeWritePolicy.check("").allowed)
    }
}
