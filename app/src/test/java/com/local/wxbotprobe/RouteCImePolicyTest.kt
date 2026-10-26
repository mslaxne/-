package com.local.wxbotprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCImePolicyTest {
    @Test
    fun `enabled but not default blocks route c startup`() {
        assertEquals(
            RouteCImeIssue.NOT_DEFAULT,
            RouteCImePolicy.startupIssue(
                enabled = true,
                isDefault = false,
            ),
        )
        assertFalse(
            RouteCImePolicy.readiness(
                enabled = true,
                isDefault = false,
            ).ready,
        )
    }

    @Test
    fun `default bot ime may start before an input is focused`() {
        assertNull(
            RouteCImePolicy.startupIssue(
                enabled = true,
                isDefault = true,
            ),
        )
        assertEquals(
            RouteCImeIssue.TARGET_NOT_CONNECTED,
            RouteCImePolicy.writeIssue(
                enabled = true,
                isDefault = true,
                targetPackage = null,
            ),
        )
    }

    @Test
    fun `default bot ime connected to wechat may write`() {
        assertNull(
            RouteCImePolicy.writeIssue(
                enabled = true,
                isDefault = true,
                targetPackage = RouteCImePolicy.WECHAT_PACKAGE,
            ),
        )
    }

    @Test
    fun `non wechat input target is rejected`() {
        assertEquals(
            RouteCImeIssue.TARGET_NOT_WECHAT,
            RouteCImePolicy.writeIssue(
                enabled = true,
                isDefault = true,
                targetPackage = "com.android.settings",
            ),
        )
    }

    @Test
    fun `default input check is not inferred from a live connection`() {
        assertTrue(
            RouteCImePolicy.isBotImeComponent(
                "com.local.wxbotprobe/.BotImeService",
            ),
        )
        assertTrue(
            RouteCImePolicy.isBotImeComponent(
                "com.local.wxbotprobe/com.local.wxbotprobe.BotImeService",
            ),
        )
        assertFalse(
            RouteCImePolicy.isBotImeComponent(
                "com.baidu.input_vivo/.ImeVivoService",
            ),
        )
    }

    @Test
    fun `prepare failure never dismisses with back`() {
        val state = ImeSendSessionState()
        state.enterFlow()

        // prepare() failed before the input tap.
        assertFalse(state.shouldDismissKeyboard(cleanupAllowed = true))

        state.markInputOpened()
        assertTrue(state.shouldDismissKeyboard(cleanupAllowed = true))
        assertFalse(state.shouldDismissKeyboard(cleanupAllowed = false))
    }

    @Test
    fun `headless route c never sends back to dismiss a keyboard`() {
        assertFalse(
            ImeExitPolicy.dismissesKeyboardWithBack(TransportMode.ACCESSIBILITY),
        )
        assertTrue(
            ImeExitPolicy.dismissesKeyboardWithBack(TransportMode.SHIZUKU),
        )
    }
}
