package com.local.wxbotprobe

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object BubbleVisibilityController {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var suppressionDepth = 0
    private var visibilitySink: ((visible: Boolean) -> Unit)? = null

    fun register(sink: (visible: Boolean) -> Unit) {
        synchronized(lock) {
            visibilitySink = sink
        }
        applyVisibility(sink, suppressionDepth == 0, waitForCompletion = false)
    }

    fun unregister(sink: (visible: Boolean) -> Unit) {
        synchronized(lock) {
            if (visibilitySink === sink) {
                visibilitySink = null
            }
        }
    }

    fun hideForCapture() {
        val sink: ((Boolean) -> Unit)?
        val mustHide: Boolean
        synchronized(lock) {
            suppressionDepth++
            mustHide = suppressionDepth == 1
            sink = visibilitySink
        }
        if (!mustHide || sink == null) {
            return
        }
        try {
            applyVisibility(sink, visible = false, waitForCompletion = true)
        } catch (error: Throwable) {
            synchronized(lock) {
                suppressionDepth = (suppressionDepth - 1).coerceAtLeast(0)
            }
            throw error
        }
    }

    fun restoreAfterCapture() {
        val sink: ((Boolean) -> Unit)?
        val mustShow: Boolean
        synchronized(lock) {
            suppressionDepth = (suppressionDepth - 1).coerceAtLeast(0)
            mustShow = suppressionDepth == 0
            sink = visibilitySink
        }
        if (mustShow && sink != null) {
            applyVisibility(sink, visible = true, waitForCompletion = false)
        }
    }

    private fun applyVisibility(
        sink: (Boolean) -> Unit,
        visible: Boolean,
        waitForCompletion: Boolean,
    ) {
        val latch = if (waitForCompletion) CountDownLatch(1) else null
        val action = {
            try {
                sink(visible)
            } finally {
                latch?.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            check(mainHandler.post(action)) {
                "无法派发悬浮气泡可见性更新。"
            }
        }
        if (latch != null &&
            !latch.await(VISIBILITY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        ) {
            throw IllegalStateException("悬浮气泡未能及时隐藏，设备操作已取消。")
        }
    }

    private const val VISIBILITY_TIMEOUT_MS = 2_000L
}
