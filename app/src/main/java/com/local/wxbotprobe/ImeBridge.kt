package com.local.wxbotprobe

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ImeBridge {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var serviceRef: WeakReference<BotImeService>? = null

    @Volatile
    private var targetPackage: String = ""

    @Volatile
    private var lastOperationError: String = ""

    fun attach(service: BotImeService) {
        serviceRef = WeakReference(service)
    }

    fun isAttached(): Boolean = serviceRef?.get() != null

    fun noteInputTarget(packageName: String?) {
        targetPackage = packageName.orEmpty()
    }

    fun noteInputFinished() {
        targetPackage = ""
    }

    fun currentTargetPackage(): String? {
        if (serviceRef?.get() == null) {
            return null
        }
        return targetPackage.takeIf { it.isNotBlank() }
    }

    fun isTargetPackage(packageName: String): Boolean {
        return currentTargetPackage() == packageName
    }

    fun detach(service: BotImeService) {
        if (serviceRef?.get() === service) {
            serviceRef = null
            targetPackage = ""
            lastOperationError = ""
        }
    }

    fun lastOperationError(): String {
        return lastOperationError
    }

    fun noteOperationError(message: String) {
        lastOperationError = message
    }

    fun noteOperationSuccess() {
        lastOperationError = ""
    }

    fun commitText(text: String): Boolean {
        lastOperationError = ""
        return runOnMain { service ->
            service.commitExternal(text)
        }
    }

    fun clearText(): Boolean {
        lastOperationError = ""
        return runOnMain { service ->
            service.clearExternal()
        }
    }

    fun readText(): String? {
        val service = serviceRef?.get() ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return service.readExternalText()
        }
        val latch = CountDownLatch(1)
        var result: String? = null
        handler.post {
            result = service.readExternalText()
            latch.countDown()
        }
        if (!latch.await(3, TimeUnit.SECONDS)) {
            return null
        }
        return result
    }

    private fun runOnMain(action: (BotImeService) -> Boolean): Boolean {
        val service = serviceRef?.get() ?: return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return action(service)
        }
        val latch = CountDownLatch(1)
        var result = false
        handler.post {
            result = action(service)
            latch.countDown()
        }
        if (!latch.await(3, TimeUnit.SECONDS)) {
            return false
        }
        return result
    }
}
