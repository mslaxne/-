package com.local.wxbotprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPolicyTest {
    @Test
    fun `route c is the default transport`() {
        assertEquals(TransportMode.ACCESSIBILITY, TransportPolicy.defaultMode())
    }

    @Test
    fun `route c does not depend on shizuku readiness`() {
        assertTrue(
            TransportPolicy.transportReady(
                TransportMode.ACCESSIBILITY,
                shizukuReady = false,
                accessibilityReady = true,
            ),
        )
        assertFalse(
            TransportPolicy.transportReady(
                TransportMode.ACCESSIBILITY,
                shizukuReady = true,
                accessibilityReady = false,
            ),
        )
    }

    @Test
    fun `route c requires an explicit wechat foreground package`() {
        assertTrue(
            TransportPolicy.foregroundAllowsSend(
                TransportMode.ACCESSIBILITY,
                "com.tencent.mm",
            ),
        )
        assertFalse(
            TransportPolicy.foregroundAllowsSend(
                TransportMode.ACCESSIBILITY,
                "",
            ),
        )
        assertFalse(
            TransportPolicy.foregroundAllowsSend(
                TransportMode.ACCESSIBILITY,
                "com.android.launcher",
            ),
        )
    }

    @Test
    fun `route b preserves its transient empty package fallback`() {
        assertTrue(
            TransportPolicy.foregroundAllowsSend(
                TransportMode.SHIZUKU,
                "",
            ),
        )
        assertFalse(
            TransportPolicy.foregroundAllowsSend(
                TransportMode.SHIZUKU,
                "com.android.launcher",
            ),
        )
    }

    @Test
    fun `ime windows do not replace the foreground application`() {
        assertTrue(
            AccessibilityWindowPolicy.shouldIgnore(
                eventPackage = "com.baidu.input_vivo",
                eventClass = "android.inputmethodservice.SoftInputWindow",
                ownPackage = "com.local.wxbotprobe",
                inputMethodPackages = setOf(
                    "com.baidu.input_vivo",
                    "com.local.wxbotprobe",
                ),
            ),
        )
        assertTrue(
            AccessibilityWindowPolicy.shouldIgnore(
                eventPackage = "com.baidu.input_vivo",
                eventClass = "com.baidu.input_vivo.ImeWindow",
                ownPackage = "com.local.wxbotprobe",
                inputMethodPackages = setOf("com.baidu.input_vivo"),
            ),
        )
        assertFalse(
            AccessibilityWindowPolicy.shouldIgnore(
                eventPackage = "com.tencent.mm",
                eventClass = "com.tencent.mm.ui.LauncherUI",
                ownPackage = "com.local.wxbotprobe",
                inputMethodPackages = setOf("com.baidu.input_vivo"),
            ),
        )
    }

    @Test
    fun `transient system windows do not replace the foreground application`() {
        assertTrue(
            AccessibilityWindowPolicy.shouldIgnore(
                eventPackage = "com.android.systemui",
                eventClass = "com.android.systemui.statusbar.phone.StatusBar",
                ownPackage = "com.local.wxbotprobe",
                inputMethodPackages = emptySet(),
            ),
        )
        assertTrue(
            AccessibilityWindowPolicy.shouldIgnore(
                eventPackage = "com.vivo.upslide",
                eventClass = "com.vivo.upslide.OverlayWindow",
                ownPackage = "com.local.wxbotprobe",
                inputMethodPackages = emptySet(),
            ),
        )
        assertFalse(
            AccessibilityWindowPolicy.shouldIgnore(
                eventPackage = "com.bbk.launcher2",
                eventClass = "com.bbk.launcher2.Launcher",
                ownPackage = "com.local.wxbotprobe",
                inputMethodPackages = emptySet(),
            ),
        )
    }

    @Test
    fun `own main activity remains the foreground application`() {
        assertFalse(
            AccessibilityWindowPolicy.shouldIgnore(
                eventPackage = "com.local.wxbotprobe",
                eventClass = "com.local.wxbotprobe.MainActivity",
                ownPackage = "com.local.wxbotprobe",
                inputMethodPackages = setOf("com.local.wxbotprobe"),
            ),
        )
        assertTrue(
            AccessibilityWindowPolicy.shouldIgnore(
                eventPackage = "com.local.wxbotprobe",
                eventClass = "com.local.wxbotprobe.BotImeService",
                ownPackage = "com.local.wxbotprobe",
                inputMethodPackages = setOf("com.local.wxbotprobe"),
            ),
        )
    }
}
