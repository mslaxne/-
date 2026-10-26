package com.local.wxbotprobe.loop

import com.local.wxbotprobe.BubbleVisibilityController

interface DeviceVisibilityGate {
    fun hide()
    fun restore()
}

object BubbleDeviceVisibilityGate : DeviceVisibilityGate {
    override fun hide() {
        BubbleVisibilityController.hideForCapture()
    }

    override fun restore() {
        BubbleVisibilityController.restoreAfterCapture()
    }
}

class DeviceActionGate(
    private val visibility: DeviceVisibilityGate = BubbleDeviceVisibilityGate,
) {
    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock) {
        var visibilitySuspended = false
        try {
            visibility.hide()
            visibilitySuspended = true
            block()
        } finally {
            if (visibilitySuspended) {
                visibility.restore()
            }
        }
    }
}
