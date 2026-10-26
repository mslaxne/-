package com.local.wxbotprobe.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionGateTest {
    private class FakeVisibility : DeviceVisibilityGate {
        var hidden = false
        var restores = 0

        override fun hide() {
            hidden = true
        }

        override fun restore() {
            hidden = false
            restores++
        }
    }

    @Test
    fun `device action hides and restores visibility`() {
        val visibility = FakeVisibility()
        val gate = DeviceActionGate(visibility)

        val result = gate.run {
            assertTrue(visibility.hidden)
            7
        }

        assertEquals(7, result)
        assertFalse(visibility.hidden)
        assertEquals(1, visibility.restores)
    }

    @Test
    fun `visibility is restored when the device action fails`() {
        val visibility = FakeVisibility()
        val gate = DeviceActionGate(visibility)

        runCatching {
            gate.run<Unit> {
                throw IllegalStateException("capture failed")
            }
        }

        assertFalse(visibility.hidden)
        assertEquals(1, visibility.restores)
    }
}
