package com.local.wxbotprobe

import android.content.Context
import android.content.Intent

object RouteBPlatform {
    const val available: Boolean = true

    val isBinderReady: Boolean
        get() = ShizukuShell.isBinderReady

    val isPermissionGranted: Boolean
        get() = ShizukuShell.isPermissionGranted

    val isServiceReady: Boolean
        get() = ShizukuShell.isServiceReady

    fun initialize(context: Context) {
        ShizukuShell.initialize(context)
    }

    fun connect(context: Context) {
        ShizukuShell.connect(context)
    }

    fun forceReconnect(context: Context) {
        ShizukuShell.forceReconnect(context)
    }

    fun openManager(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(
            "moe.shizuku.privileged.api",
        ) ?: return false
        context.startActivity(intent)
        return true
    }

    fun screenshot(path: String) {
        ShizukuShell.screenshot(path)
    }

    fun tap(x: Int, y: Int) {
        ShizukuShell.tap(x, y)
    }

    fun foregroundPackage(): String {
        return ShizukuShell.foregroundPackage()
    }

    fun keyEvent(keyCode: Int) {
        ShizukuShell.keyEvent(keyCode)
    }

    fun imeEnable(id: String) {
        ShizukuShell.imeEnable(id)
    }

    fun imeSet(id: String) {
        ShizukuShell.imeSet(id)
    }
}
