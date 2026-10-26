package com.local.wxbotprobe.send

enum class SendBlockReason {
    SCREEN_OFF,
    LOCKED,
}

object SendSafetyGuard {
    fun check(
        interactive: Boolean,
        locked: Boolean,
    ): SendBlockReason? {
        if (!interactive) {
            return SendBlockReason.SCREEN_OFF
        }
        if (locked) {
            return SendBlockReason.LOCKED
        }
        return null
    }

    fun message(reason: SendBlockReason): String {
        return when (reason) {
            SendBlockReason.SCREEN_OFF -> "屏幕已熄灭，发送已取消"
            SendBlockReason.LOCKED -> "屏幕已锁定，发送已取消"
        }
    }
}
