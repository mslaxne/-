package com.local.wxbotprobe

import android.content.Context
import android.os.SystemClock
import java.io.File

enum class TransportMode(
    val storageValue: String,
    val label: String,
) {
    SHIZUKU("shizuku", "路线 B · Shizuku"),
    ACCESSIBILITY("accessibility", "路线 C · 无障碍"),
}

enum class DeviceGlobalAction {
    BACK,
    HOME,
}

interface DeviceTransport {
    val mode: TransportMode

    fun isReady(): Boolean

    fun unavailableMessage(): String

    fun screenshot(output: File)

    fun tap(x: Int, y: Int)

    fun foregroundPackage(): String

    fun performGlobalAction(action: DeviceGlobalAction): Boolean
}

object TransportSettings {
    private const val PREFS = "transport_settings"
    private const val KEY_MODE = "transport_mode"

    fun mode(context: Context): TransportMode {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, TransportMode.ACCESSIBILITY.storageValue)
        val selected = TransportMode.entries.firstOrNull {
            it.storageValue == stored
        } ?: TransportMode.ACCESSIBILITY
        return if (selected == TransportMode.SHIZUKU && !RouteBPlatform.available) {
            TransportMode.ACCESSIBILITY
        } else {
            selected
        }
    }

    fun setMode(context: Context, mode: TransportMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.storageValue)
            .apply()
    }
}

object TransportPolicy {
    fun defaultMode(): TransportMode = TransportMode.ACCESSIBILITY

    fun foregroundAllowsSend(
        mode: TransportMode,
        foregroundPackage: String,
        expectedPackage: String = "com.tencent.mm",
    ): Boolean {
        return when (mode) {
            // Route B preserves its previous transient-empty fallback.
            TransportMode.SHIZUKU ->
                foregroundPackage.isBlank() || foregroundPackage == expectedPackage
            TransportMode.ACCESSIBILITY -> foregroundPackage == expectedPackage
        }
    }

    fun transportReady(
        mode: TransportMode,
        shizukuReady: Boolean,
        accessibilityReady: Boolean,
    ): Boolean {
        return when (mode) {
            TransportMode.SHIZUKU -> shizukuReady
            TransportMode.ACCESSIBILITY -> accessibilityReady
        }
    }
}

object AccessibilityWindowPolicy {
    private val transientSystemPackages = setOf(
        "com.android.systemui",
        "com.vivo.upslide",
    )

    fun shouldIgnore(
        eventPackage: String,
        eventClass: String,
        ownPackage: String,
        inputMethodPackages: Set<String>,
    ): Boolean {
        if (eventPackage in transientSystemPackages) {
            return true
        }
        val normalizedClass = eventClass.lowercase()
        if (normalizedClass.contains("inputmethod") ||
            normalizedClass.contains("softinput") ||
            normalizedClass.contains("popupwindow")
        ) {
            return true
        }
        if (eventPackage == ownPackage) {
            return !eventClass.contains("MainActivity", ignoreCase = true)
        }
        return eventPackage in inputMethodPackages
    }
}

object DeviceTransportFactory {
    fun create(context: Context): DeviceTransport {
        return when (TransportSettings.mode(context)) {
            TransportMode.SHIZUKU -> ShizukuTransport()
            TransportMode.ACCESSIBILITY -> AccessibilityTransport()
        }
    }
}

class ShizukuTransport : DeviceTransport {
    override val mode: TransportMode = TransportMode.SHIZUKU

    override fun isReady(): Boolean = RouteBPlatform.isServiceReady

    override fun unavailableMessage(): String =
        if (RouteBPlatform.available) {
            "Shizuku 用户服务尚未连接，无法使用路线 B。"
        } else {
            "当前 APK 不包含路线 B Shizuku 回退。"
        }

    override fun screenshot(output: File) {
        requireReady()
        RouteBPlatform.screenshot(output.absolutePath)
    }

    override fun tap(x: Int, y: Int) {
        requireReady()
        RouteBPlatform.tap(x, y)
    }

    override fun foregroundPackage(): String {
        requireReady()
        return RouteBPlatform.foregroundPackage()
    }

    override fun performGlobalAction(action: DeviceGlobalAction): Boolean {
        requireReady()
        RouteBPlatform.keyEvent(
            when (action) {
                DeviceGlobalAction.BACK -> 4
                DeviceGlobalAction.HOME -> 3
            },
        )
        return true
    }

    private fun requireReady() {
        check(isReady()) { unavailableMessage() }
    }
}

class AccessibilityTransport(
    private val serviceProvider: () -> ProbeAccessibilityService? = {
        ProbeAccessibilityService.instance
    },
) : DeviceTransport {
    override val mode: TransportMode = TransportMode.ACCESSIBILITY

    override fun isReady(): Boolean = serviceProvider() != null

    override fun unavailableMessage(): String =
        "无障碍传输服务尚未连接。请启用本 App 的无障碍服务。"

    override fun screenshot(output: File) {
        repeat(SCREENSHOT_ATTEMPTS) { attempt ->
            AccessibilityScreenshotPacing.awaitSlot()
            try {
                requireService().captureScreenshot(output)
                return
            } catch (error: Throwable) {
                val rateLimited = error.message?.contains("频繁") == true
                if (!rateLimited || attempt == SCREENSHOT_ATTEMPTS - 1) {
                    throw error
                }
                AccessibilityScreenshotPacing.noteRateLimit()
                Thread.sleep(RATE_LIMIT_BACKOFF_MS)
            }
        }
    }

    override fun tap(x: Int, y: Int) {
        if (!requireService().tapAndWait(x.toFloat(), y.toFloat())) {
            throw IllegalStateException("无障碍点击手势未完成。")
        }
    }

    override fun foregroundPackage(): String {
        return requireService().currentForegroundPackage()
    }

    override fun performGlobalAction(action: DeviceGlobalAction): Boolean {
        val service = requireService()
        val accessibilityAction = when (action) {
            DeviceGlobalAction.BACK -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            DeviceGlobalAction.HOME -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        }
        return service.performGlobalAction(accessibilityAction)
    }

    private fun requireService(): ProbeAccessibilityService {
        return serviceProvider() ?: throw IllegalStateException(unavailableMessage())
    }

    companion object {
        private const val SCREENSHOT_ATTEMPTS = 3
        private const val RATE_LIMIT_BACKOFF_MS = 3_500L
    }
}

private object AccessibilityScreenshotPacing {
    private val lock = Any()
    private var lastStartedAt = 0L
    private var minimumIntervalMs = AccessibilityScreenshotPacingPolicy.BASE_INTERVAL_MS

    fun awaitSlot() {
        synchronized(lock) {
            val waitMs = lastStartedAt + minimumIntervalMs - SystemClock.elapsedRealtime()
            if (waitMs > 0L) {
                Thread.sleep(waitMs)
            }
            lastStartedAt = SystemClock.elapsedRealtime()
        }
    }

    fun noteRateLimit() {
        synchronized(lock) {
            minimumIntervalMs = AccessibilityScreenshotPacingPolicy.afterRateLimit(
                minimumIntervalMs,
            )
        }
    }
}
