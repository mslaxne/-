package com.local.wxbotprobe

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

object ShizukuShell {
    private const val REQUEST_CODE = 2001

    private var appContext: Context? = null
    private var initialized = false
    private var bindingRequested = false

    @Volatile
    private var service: IPrivilegedService? = null

    val isBinderReady: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val isServiceReady: Boolean
        get() = service != null

    val isPermissionGranted: Boolean
        get() = hasPermission()

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                appContext!!.packageName,
                PrivilegedUserService::class.java.name,
            ),
        )
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(true)
            // Shizuku recreates the UserService when this value changes.
            .version(BuildConfig.VERSION_CODE + 100)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IPrivilegedService.Stub.asInterface(binder)
            bindingRequested = false
            val uid = runCatching { service?.uid() }.getOrNull()
            report("Shizuku 用户服务已连接，UID=$uid")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bindingRequested = false
            report("Shizuku 用户服务已断开。")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        appContext ?: return@OnBinderReceivedListener
        if (hasPermission()) {
            bindIfPossible()
        } else {
            report("Shizuku 已运行，等待授权。")
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        bindingRequested = false
        report("Shizuku 服务已停止，请重新激活。")
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener {
            requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                report("Shizuku 授权成功。")
                bindIfPossible()
            } else {
                report("Shizuku 授权被拒绝。")
            }
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        userServiceArgs
        if (initialized) {
            if (hasPermission()) {
                bindIfPossible()
            }
            return
        }

        initialized = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    fun connect(context: Context) {
        initialize(context)
        if (!isBinderReady) {
            report("Shizuku 未运行。请先打开 Shizuku 并启动服务。")
            return
        }
        if (!hasPermission()) {
            try {
                Shizuku.requestPermission(REQUEST_CODE)
                report("已请求 Shizuku 授权，请在系统弹窗中允许。")
            } catch (error: Throwable) {
                report("请求 Shizuku 授权失败：${error.message}")
            }
            return
        }
        bindIfPossible()
    }

    fun forceReconnect(context: Context) {
        initialize(context)
        service = null
        bindingRequested = false
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
            report("已清理旧的 Shizuku 用户服务。")
        } catch (error: Throwable) {
            report("清理旧用户服务失败：${error.message}")
        }
        bindIfPossible()
    }

    fun screenshot(path: String) {
        val activeService = requireService()
        activeService.screenshot(path)
    }

    fun tap(x: Int, y: Int) {
        val activeService = requireService()
        activeService.tap(x, y)
    }

    fun imeEnable(id: String) {
        requireService().imeEnable(id)
    }

    fun imeSet(id: String) {
        requireService().imeSet(id)
    }

    fun keyEvent(keyCode: Int) {
        requireService().keyEvent(keyCode)
    }

    fun foregroundPackage(): String {
        return requireService().foregroundPackage().orEmpty()
    }

    private fun bindIfPossible() {
        if (service != null || bindingRequested || !isBinderReady || !hasPermission()) {
            return
        }
        bindingRequested = true
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
            report("正在连接 Shizuku 用户服务。")
        } catch (error: Throwable) {
            bindingRequested = false
            report("绑定 Shizuku 用户服务失败：${error.message}")
        }
    }

    private fun hasPermission(): Boolean {
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun requireService(): IPrivilegedService {
        return service
            ?: throw IllegalStateException("Shizuku 用户服务尚未连接。")
    }

    private fun report(message: String) {
        appContext?.let { ProbeStatus.write(it, message) }
    }
}
