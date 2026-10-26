package com.local.wxbotprobe

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

interface ImeStrategy {
    val dismissesKeyboardWithBack: Boolean

    fun prepare()

    fun verifyTarget(packageName: String): Boolean

    fun targetMismatchMessage(): String

    fun commitText(text: String): Boolean

    fun clearText(): Boolean

    fun restore()
}

object ImeExitPolicy {
    fun dismissesKeyboardWithBack(mode: TransportMode): Boolean {
        return mode == TransportMode.SHIZUKU
    }
}

object ImeStrategyFactory {
    const val BOT_IME = RouteCImePolicy.BOT_IME
    private const val DEFAULT_ORIGINAL_IME = "com.baidu.input_vivo/.ImeVivoService"

    fun create(
        context: Context,
        mode: TransportMode,
    ): ImeStrategy {
        return when (mode) {
            TransportMode.SHIZUKU -> ShizukuImeStrategy(context)
            TransportMode.ACCESSIBILITY -> ResidentBotImeStrategy(context)
        }
    }

    fun isBotImeEnabled(context: Context): Boolean {
        return context.getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList
            .any { info ->
                info.packageName == context.packageName &&
                    (
                        info.serviceName == BotImeService::class.java.name ||
                            info.serviceName == ".BotImeService"
                        )
            }
    }

    fun isBotImeDefault(context: Context): Boolean {
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        return RouteCImePolicy.isBotImeComponent(current)
    }

    fun routeCStartupIssue(context: Context): RouteCImeIssue? {
        return RouteCImePolicy.startupIssue(
            enabled = isBotImeEnabled(context),
            isDefault = isBotImeDefault(context),
        )
    }

    fun routeCStartupMessage(context: Context): String? {
        return routeCStartupIssue(context)?.let(RouteCImePolicy::startupMessage)
    }

    private class ShizukuImeStrategy(
        private val context: Context,
    ) : ImeStrategy {
        override val dismissesKeyboardWithBack: Boolean =
            ImeExitPolicy.dismissesKeyboardWithBack(TransportMode.SHIZUKU)

        private val originalIme: String by lazy(::resolveOriginalIme)

        override fun prepare() {
            RouteBPlatform.imeEnable(BOT_IME)
            RouteBPlatform.imeSet(BOT_IME)
            waitForIme(BOT_IME)
        }

        override fun verifyTarget(packageName: String): Boolean = ImeBridge.isAttached()

        override fun targetMismatchMessage(): String =
            "BotIme 已选中但输入连接未建立，路线 B 已拒绝写入。"

        override fun commitText(text: String): Boolean = ImeBridge.commitText(text)

        override fun clearText(): Boolean = ImeBridge.clearText()

        override fun restore() {
            runCatching { RouteBPlatform.imeSet(originalIme) }
        }

        private fun waitForIme(expected: String) {
            repeat(20) {
                val current = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                )
                if (current == expected) {
                    Thread.sleep(500)
                    return
                }
                Thread.sleep(150)
            }
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            )
            throw IllegalStateException("输入法未切换到 BotIme：$current")
        }

        private fun resolveOriginalIme(): String {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            ).orEmpty()
            if (current.isNotBlank() && current != BOT_IME) {
                SendSettings.setOriginalIme(context, current)
                return current
            }
            return SendSettings.originalIme(context)
                ?.takeIf { it.isNotBlank() && it != BOT_IME }
                ?: DEFAULT_ORIGINAL_IME
        }
    }

    private class ResidentBotImeStrategy(
        private val context: Context,
    ) : ImeStrategy {
        override val dismissesKeyboardWithBack: Boolean =
            ImeExitPolicy.dismissesKeyboardWithBack(TransportMode.ACCESSIBILITY)

        override fun prepare() {
            routeCStartupIssue(context)?.let { issue ->
                throw IllegalStateException(RouteCImePolicy.startupMessage(issue))
            }
        }

        override fun verifyTarget(packageName: String): Boolean {
            val issue = RouteCImePolicy.writeIssue(
                enabled = isBotImeEnabled(context),
                isDefault = isBotImeDefault(context),
                targetPackage = ImeBridge.currentTargetPackage(),
            )
            return issue == null && packageName == RouteCImePolicy.WECHAT_PACKAGE
        }

        override fun targetMismatchMessage(): String {
            val target = ImeBridge.currentTargetPackage()
            val issue = RouteCImePolicy.writeIssue(
                enabled = isBotImeEnabled(context),
                isDefault = isBotImeDefault(context),
                targetPackage = target,
            ) ?: RouteCImeIssue.TARGET_NOT_WECHAT
            return RouteCImePolicy.writeMessage(issue, target)
        }

        override fun commitText(text: String): Boolean = ImeBridge.commitText(text)

        override fun clearText(): Boolean = ImeBridge.clearText()

        // Route C never switches the system IME or restores another keyboard.
        override fun restore() = Unit
    }
}
