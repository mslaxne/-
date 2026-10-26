package com.local.wxbotprobe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProbeAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val captureInFlight = AtomicBoolean(false)
    private val gestureInFlight = AtomicBoolean(false)

    @Volatile
    private var lastForegroundPackage = ""

    override fun onServiceConnected() {
        instance = this
        if (ProbeStatus.message(this) == "尚未测试") {
            ProbeStatus.write(this, "无障碍服务已连接。")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val eventType = safeEvent.eventType
        val isWindowStateChanged = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isContentChanged = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isWindowStateChanged && !isContentChanged) {
            return
        }
        val packageName = safeEvent.packageName?.toString().orEmpty()
        if (packageName.isBlank()) {
            return
        }
        if (isContentChanged) {
            val previousForegroundPackage = lastForegroundPackage
            if (packageName == WECHAT_PACKAGE) {
                lastForegroundPackage = packageName
            }
            if (AccessibilityWakePolicy.shouldWake(
                    eventPackage = packageName,
                    expectedPackage = WECHAT_PACKAGE,
                    lastForegroundPackage = previousForegroundPackage,
                    windowStateChanged = false,
                    contentChanged = true,
                )
            ) {
                contentChangeListener?.invoke(eventType)
            }
            return
        }
        val className = safeEvent.className?.toString().orEmpty()
        Log.d("WxBotTransport", "window package=$packageName class=$className")
        val inputMethodPackages = getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList
            .mapTo(mutableSetOf()) { it.packageName }
        if (AccessibilityWindowPolicy.shouldIgnore(
                eventPackage = packageName,
                eventClass = className,
                ownPackage = this.packageName,
                inputMethodPackages = inputMethodPackages,
            )
        ) {
            // IME, overlay and dialog windows do not replace the app underneath.
            return
        }
        lastForegroundPackage = packageName
        if (AccessibilityWakePolicy.shouldWake(
                eventPackage = packageName,
                expectedPackage = WECHAT_PACKAGE,
                lastForegroundPackage = lastForegroundPackage,
                windowStateChanged = true,
                contentChanged = false,
            )
        ) {
            contentChangeListener?.invoke(eventType)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    fun currentForegroundPackage(): String {
        val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (activePackage.isNotBlank()) {
            val inputMethodPackages = getSystemService(InputMethodManager::class.java)
                .enabledInputMethodList
                .mapTo(mutableSetOf()) { it.packageName }
            if (!AccessibilityWindowPolicy.shouldIgnore(
                    eventPackage = activePackage,
                    eventClass = "",
                    ownPackage = packageName,
                    inputMethodPackages = inputMethodPackages,
                )
            ) {
                lastForegroundPackage = activePackage
            }
        }
        return lastForegroundPackage
    }

    fun captureScreenshot(output: File) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "无障碍截图不能在主线程同步等待。"
        }
        check(captureInFlight.compareAndSet(false, true)) {
            "上一张无障碍截图尚未完成。"
        }
        val finished = CountDownLatch(1)
        var failure: Throwable? = null
        var dispatched = false
        try {
            val posted = mainHandler.post {
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        screenshotExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val buffer = screenshot.hardwareBuffer
                                var hardwareBitmap: Bitmap? = null
                                try {
                                    hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                        buffer,
                                        screenshot.colorSpace,
                                    ) ?: throw IllegalStateException("无法读取无障碍截图缓冲区。")
                                    val softwareBitmap = hardwareBitmap.copy(
                                        Bitmap.Config.ARGB_8888,
                                        false,
                                    ) ?: throw IllegalStateException("无法转换无障碍截图。")
                                    try {
                                        saveBitmap(output, softwareBitmap)
                                    } finally {
                                        softwareBitmap.recycle()
                                    }
                                } catch (error: Throwable) {
                                    failure = error
                                } finally {
                                    hardwareBitmap?.recycle()
                                    buffer.close()
                                    finished.countDown()
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                failure = IllegalStateException(screenshotError(errorCode))
                                finished.countDown()
                            }
                        },
                    )
                } catch (error: Throwable) {
                    failure = error
                    finished.countDown()
                }
            }
            dispatched = posted
            if (!posted) {
                throw IllegalStateException("无法派发无障碍截图请求。")
            }
            if (!finished.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("无障碍截图超时。")
            }
            failure?.let { throw it }
        } finally {
            if (!dispatched) {
                finished.countDown()
            }
            captureInFlight.set(false)
        }
    }

    fun tapAndWait(x: Float, y: Float): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "无障碍点击不能在主线程同步等待。"
        }
        if (!gestureInFlight.compareAndSet(false, true)) {
            throw IllegalStateException("上一个无障碍手势尚未完成。")
        }
        val finished = CountDownLatch(1)
        var failure: Throwable? = null
        try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
                .build()
            val posted = mainHandler.post {
                try {
                    val accepted = dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription) {
                                finished.countDown()
                            }

                            override fun onCancelled(gestureDescription: GestureDescription) {
                                failure = IllegalStateException("无障碍点击手势被系统取消。")
                                finished.countDown()
                            }
                        },
                        mainHandler,
                    )
                    if (!accepted) {
                        failure = IllegalStateException("系统拒绝无障碍点击手势。")
                        finished.countDown()
                    }
                } catch (error: Throwable) {
                    failure = error
                    finished.countDown()
                }
            }
            if (!posted) {
                throw IllegalStateException("无法派发无障碍点击手势。")
            }
            if (!finished.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("无障碍点击等待超时。")
            }
            failure?.let { throw it }
            return true
        } finally {
            gestureInFlight.set(false)
        }
    }

    private fun saveBitmap(output: File, bitmap: Bitmap) {
        val parent = output.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("无法创建截图目录：${parent.absolutePath}")
        }
        FileOutputStream(output).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IllegalStateException("无障碍截图 PNG 编码失败。")
            }
        }
    }

    private fun screenshotError(errorCode: Int): String {
        return when (errorCode) {
            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "无障碍截图内部错误。"
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍服务没有截图权限。"
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图请求过于频繁，系统已限频。"
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "无障碍截图显示无效。"
            else -> "无障碍截图失败，错误码 $errorCode。"
        }
    }

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"

        @Volatile
        var instance: ProbeAccessibilityService? = null
            private set

        @Volatile
        private var contentChangeListener: ((Int) -> Unit)? = null

        fun setContentChangeListener(listener: ((Int) -> Unit)?) {
            contentChangeListener = listener
        }

        private const val SCREENSHOT_TIMEOUT_MS = 8_000L
        private const val GESTURE_TIMEOUT_MS = 3_000L
    }
}
