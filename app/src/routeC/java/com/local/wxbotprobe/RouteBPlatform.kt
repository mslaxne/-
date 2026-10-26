package com.local.wxbotprobe

import android.content.Context

object RouteBPlatform {
    const val available: Boolean = false

    val isBinderReady: Boolean = false
    val isPermissionGranted: Boolean = false
    val isServiceReady: Boolean = false

    fun initialize(context: Context) = Unit

    fun connect(context: Context) {
        ProbeStatus.write(context, "当前是路线 C 精简构建，不包含 Shizuku 回退。")
    }

    fun forceReconnect(context: Context) {
        ProbeStatus.write(context, "当前是路线 C 精简构建，不包含 Shizuku 回退。")
    }

    fun openManager(context: Context): Boolean = false

    fun screenshot(path: String) {
        throw UnsupportedOperationException("当前构建不包含 Shizuku 截图。")
    }

    fun tap(x: Int, y: Int) {
        throw UnsupportedOperationException("当前构建不包含 Shizuku 点击。")
    }

    fun foregroundPackage(): String = ""

    fun keyEvent(keyCode: Int) {
        throw UnsupportedOperationException("当前构建不包含 Shizuku 按键。")
    }

    fun imeEnable(id: String) {
        throw UnsupportedOperationException("当前构建不包含 Shizuku IME 切换。")
    }

    fun imeSet(id: String) {
        throw UnsupportedOperationException("当前构建不包含 Shizuku IME 切换。")
    }
}
