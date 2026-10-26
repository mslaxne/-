package com.local.wxbotprobe.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeyMaskingTest {
    @Test
    fun `provider key exposes only a short prefix and suffix`() {
        assertEquals(
            "Key ••••lder",
            ApiKeyMasking.mask("test-token-placeholder"),
        )
    }

    @Test
    fun `short keys are fully masked`() {
        assertEquals("••••", ApiKeyMasking.mask("short"))
    }

    @Test
    fun `blank key has no display value`() {
        assertEquals("", ApiKeyMasking.mask("  "))
    }
}
