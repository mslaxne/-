package com.local.wxbotprobe.send

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.app.KeyguardManager
import android.os.SystemClock
import android.os.PowerManager
import com.local.wxbotprobe.DeviceGlobalAction
import com.local.wxbotprobe.DeviceTransport
import com.local.wxbotprobe.DeviceTransportFactory
import com.local.wxbotprobe.ImeStrategy
import com.local.wxbotprobe.ImeStrategyFactory
import com.local.wxbotprobe.ImeBridge
import com.local.wxbotprobe.ImeSendSessionState
import com.local.wxbotprobe.TransportPolicy
import com.local.wxbotprobe.chat.BitmapPixelSource
import com.local.wxbotprobe.chat.ReplySplitter
import com.local.wxbotprobe.chat.ScreenPixelRules
import com.local.wxbotprobe.chat.ScreenPoint
import com.local.wxbotprobe.device.DeviceProfileResolver
import java.io.File
import kotlin.math.min
import kotlin.random.Random

data class SendOutcome(
    val parts: List<String>,
    val sentParts: Int,
    val dryRun: Boolean,
    val error: String = "",
    val cancelled: Boolean = false,
    val resultUnknown: Boolean = false,
) {
    val success: Boolean
        get() = !cancelled &&
            !resultUnknown &&
            error.isEmpty() &&
            (if (dryRun) sentParts == 0 else sentParts == parts.size)
}

class SendResultUnknownException(message: String) : IllegalStateException(message)

class WeChatSender(
    private val context: Context,
    private val transport: DeviceTransport = DeviceTransportFactory.create(context),
    private val imeStrategy: ImeStrategy = ImeStrategyFactory.create(context, transport.mode),
    private val readInputText: () -> String? = { ImeBridge.readText() },
) {
    private val deviceProfile = DeviceProfileResolver.current(context)
    private var inputField = deviceProfile.inputAnchor
    private var lastEnterError = ""
    private val sendPointCalibration = SendPointPolicy.resolveCurrent(context)

    private data class EnteredPart(
        val sendPoint: ScreenPoint,
        val textConfirmed: Boolean,
    )

    private data class TextProbeResult(
        val readable: Boolean,
        val matched: Boolean,
        val text: String?,
    )

    fun send(
        reply: String,
        dryRun: Boolean,
    ): SendOutcome {
        val parts = ReplySplitter(3).split(reply)
        return sendParts(parts, dryRun)
    }

    fun sendParts(
        parts: List<String>,
        dryRun: Boolean,
        startIndex: Int = 0,
        shouldContinue: () -> Boolean = { true },
        inputFieldHint: ScreenPoint? = null,
        onTiming: (stage: String, elapsedMs: Long) -> Unit = { _, _ -> },
        beforeSendPart: (index: Int) -> Unit = {},
        onPartSent: (Int) -> Unit = {},
    ): SendOutcome {
        if (parts.isEmpty()) {
            return SendOutcome(parts, 0, dryRun, "empty reply")
        }
        if (startIndex !in 0..parts.size) {
            return SendOutcome(parts, startIndex, dryRun, "invalid delivery start index")
        }
        if (startIndex == parts.size) {
            return SendOutcome(parts, startIndex, dryRun)
        }
        if (!transport.isReady()) {
            return SendOutcome(parts, startIndex, dryRun, transport.unavailableMessage())
        }

        var sent = startIndex
        val imeSession = ImeSendSessionState()
        val totalStartedAt = SystemClock.elapsedRealtime()
        fun reportTiming(stage: String, startedAt: Long) {
            runCatching {
                onTiming(stage, SystemClock.elapsedRealtime() - startedAt)
            }
        }
        try {
            if (!SendPointPolicy.canUseInputHint(
                    inputFieldHint != null,
                    sendPointCalibration,
                )
            ) {
                val startedAt = SystemClock.elapsedRealtime()
                locateInputBeforeKeyboard()
                reportTiming("locate_input", startedAt)
            } else {
                inputField = requireNotNull(inputFieldHint)
                runCatching { onTiming("input_hint_used", 0L) }
            }
            if (!shouldContinue()) {
                return SendOutcome(parts, sent, false, cancelled = true)
            }
            imeSession.enterFlow()
            val prepareStartedAt = SystemClock.elapsedRealtime()
            imeStrategy.prepare()
            reportTiming("prepare_ime", prepareStartedAt)
            val focusStartedAt = SystemClock.elapsedRealtime()
            focusAndPrepareInput {
                imeSession.markInputOpened()
            }
            reportTiming("focus_input", focusStartedAt)
            if (dryRun) {
                val dryRunStartedAt = SystemClock.elapsedRealtime()
                if (enterPart(parts.first()) == null) {
                    throw IllegalStateException(
                        "dry-run text did not reach the input box: $lastEnterError",
                    )
                }
                imeStrategy.clearText()
                reportTiming("dry_run_input", dryRunStartedAt)
                return SendOutcome(parts, 0, true)
            }

            for (index in startIndex until parts.size) {
                val partStartedAt = SystemClock.elapsedRealtime()
                if (!shouldContinue()) {
                    return SendOutcome(parts, sent, false, cancelled = true)
                }
                val part = parts[index]
                if (index > startIndex) {
                    Thread.sleep(typingDelay(part))
                }
                val entered = enterPart(part)
                if (entered == null) {
                    throw IllegalStateException(
                        "text did not reach the input box: $lastEnterError",
                    )
                }
                if (!shouldContinue()) {
                    return SendOutcome(parts, sent, false, cancelled = true)
                }
                beforeSendPart(index)
                if (entered.textConfirmed) {
                    tapSendAndConfirmText(part, entered.sendPoint)
                } else {
                    tapSendAndConfirm(entered.sendPoint)
                }
                sent++
                onPartSent(index)
                reportTiming("part_${index + 1}", partStartedAt)
            }
            return SendOutcome(parts, sent, false)
        } catch (error: Throwable) {
            return SendOutcome(
                parts = parts,
                sentParts = sent,
                dryRun = dryRun,
                error = error.message ?: error.javaClass.simpleName,
                resultUnknown = error is SendResultUnknownException,
            )
        } finally {
            val cleanupStartedAt = SystemClock.elapsedRealtime()
            val cleanupAllowed = runCatching {
                requireSafeEnvironment()
                true
            }.getOrDefault(false)
            if (imeSession.shouldDismissKeyboard(cleanupAllowed)) {
                runCatching { imeStrategy.clearText() }
                if (imeStrategy.dismissesKeyboardWithBack) {
                    runCatching { transport.performGlobalAction(DeviceGlobalAction.BACK) }
                }
            }
            if (imeSession.imeFlowEntered) {
                runCatching { imeStrategy.restore() }
            }
            reportTiming("cleanup", cleanupStartedAt)
            reportTiming("total", totalStartedAt)
        }
    }

    private fun focusAndPrepareInput(onInputOpened: () -> Unit) {
        requireSafeEnvironment()
        transport.tap(inputField.x, inputField.y)
        Thread.sleep(900)
        if (!imeStrategy.verifyTarget(WECHAT_PACKAGE)) {
            throw IllegalStateException(imeStrategy.targetMismatchMessage())
        }
        onInputOpened()
        val draft = readInputText()
        if (draft != null) {
            if (draft.isNotEmpty()) {
                if (!imeStrategy.clearText()) {
                    throw IllegalStateException(
                        "failed to clear leftover input: " +
                            ImeBridge.lastOperationError().ifBlank { "unknown error" },
                    )
                }
                val cleared = awaitInputText(1_200L) { it.isEmpty() }
                if (!cleared.matched) {
                    throw IllegalStateException("leftover input remained after clear")
                }
            }
            return
        }
        val image = capture()
        val source = pixels(image)
        ScreenPixelRules.locateField(source, inputField.y, deviceProfile)?.let {
            inputField = it
        }
        if (ScreenPixelRules.findGreenButton(source, inputField.y, deviceProfile) != null) {
            if (!imeStrategy.clearText()) {
                throw IllegalStateException(
                    "failed to clear leftover input: " +
                        ImeBridge.lastOperationError().ifBlank { "unknown error" },
                )
            }
            Thread.sleep(700)
            val afterClear = capture()
            val afterClearPixels = pixels(afterClear)
            val stillGreen = ScreenPixelRules.findGreenButton(
                afterClearPixels,
                inputField.y,
                deviceProfile,
            )
            afterClear.recycle()
            if (stillGreen != null) {
                throw IllegalStateException("leftover input remained after clear")
            }
        }
        image.recycle()
    }

    private fun enterPart(part: String): EnteredPart? {
        lastEnterError = ""
        val calibration = sendPointCalibration
        repeat(3) {
            requireSafeEnvironment()
            if (
                calibration != null &&
                !SendPointPolicy.canUseFixedSendPoint(inputField, calibration)
            ) {
                lastEnterError =
                    "input row moved outside the calibrated send point; " +
                        "send was cancelled before tapping"
                return null
            }
            transport.tap(inputField.x, inputField.y)
            Thread.sleep(700)
            if (!imeStrategy.commitText(part)) {
                lastEnterError = ImeBridge.lastOperationError()
                    .ifBlank { "commitText returned false" }
                return@repeat
            }
            val textProbe = awaitInputText(1_500L) { it == part }
            if (textProbe.matched) {
                if (calibration != null) {
                    return EnteredPart(calibration.sendPoint, true)
                }
                Thread.sleep(250)
                val button = captureGreenButton()
                if (button != null) {
                    return EnteredPart(button, false)
                }
                lastEnterError = "uncalibrated device has no detectable send button"
                return null
            }
            if (textProbe.readable) {
                lastEnterError = "input text did not match committed text"
                if (textProbe.text?.isNotEmpty() == true) {
                    imeStrategy.clearText()
                }
                return null
            }
            Thread.sleep(800)
            val image = capture()
            val source = pixels(image)
            val button = ScreenPixelRules.findGreenButton(
                source,
                inputField.y,
                deviceProfile,
            )
            ScreenPixelRules.locateField(source, inputField.y, deviceProfile)?.let {
                inputField = it
            }
            image.recycle()
            if (button != null) {
                return EnteredPart(button, false)
            }
            lastEnterError = "send button did not appear after commit"
            return null
        }
        return null
    }

    private fun locateInputBeforeKeyboard() {
        val image = capture()
        val source = pixels(image)
        ScreenPixelRules.locateField(source, inputField.y, deviceProfile)?.let {
            inputField = it
        }
        image.recycle()
    }

    private fun tapSendAndConfirm(detectedButton: ScreenPoint) {
        requireSafeEnvironment()
        val firstTap = runCatching {
            transport.tap(detectedButton.x, detectedButton.y)
        }
        firstTap.exceptionOrNull()?.let {
            throw SendResultUnknownException(
                "send tap result unknown: ${it.message ?: it.javaClass.simpleName}",
            )
        }
        try {
            Thread.sleep(900)
            confirmGreenCleared()
        } catch (error: SendResultUnknownException) {
            throw error
        } catch (error: Throwable) {
            throw SendResultUnknownException(
                "send confirmation failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun tapSendAndConfirmText(part: String, sendPoint: ScreenPoint) {
        requireSafeEnvironment()
        val firstTap = runCatching {
            transport.tap(sendPoint.x, sendPoint.y)
        }
        firstTap.exceptionOrNull()?.let {
            throw SendResultUnknownException(
                "send tap result unknown: ${it.message ?: it.javaClass.simpleName}",
            )
        }
        val probe = awaitInputText(1_800L) { it.isEmpty() }
        if (probe.matched) {
            return
        }
        if (probe.readable) {
            if (probe.text.orEmpty().isNotEmpty()) {
                val detected = captureGreenButton()
                    ?: throw IllegalStateException("input text remained after send tap")
                tapSendAndConfirm(detected)
                return
            }
            throw SendResultUnknownException(
                "send text state was readable but did not match the expected input",
            )
        }
        try {
            confirmGreenCleared()
        } catch (error: SendResultUnknownException) {
            throw error
        } catch (error: Throwable) {
            throw SendResultUnknownException(
                "send confirmation failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun confirmGreenCleared() {
        try {
            var image = capture()
            var source = pixels(image)
            var green = ScreenPixelRules.findGreenButton(
                source,
                inputField.y,
                deviceProfile,
            )
            if (green != null) {
                requireSafeEnvironment()
                transport.tap(green.x, green.y)
                Thread.sleep(900)
                image.recycle()
                image = capture()
                source = pixels(image)
                green = ScreenPixelRules.findGreenButton(
                    source,
                    inputField.y,
                    deviceProfile,
                )
            }
            image.recycle()
            if (green != null) {
                throw SendResultUnknownException("send button still green after send tap")
            }
        } catch (error: SendResultUnknownException) {
            throw error
        } catch (error: Throwable) {
            throw SendResultUnknownException(
                "send confirmation failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun captureGreenButton(): ScreenPoint? {
        val image = capture()
        return try {
            ScreenPixelRules.findGreenButton(
                pixels(image),
                inputField.y,
                deviceProfile,
            )
        } finally {
            image.recycle()
        }
    }

    private fun awaitInputText(
        timeoutMillis: Long,
        predicate: (String) -> Boolean,
    ): TextProbeResult {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var lastText: String? = null
        while (true) {
            val current = readInputText()
            if (current != null) {
                lastText = current
                if (predicate(current)) {
                    return TextProbeResult(true, true, current)
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return TextProbeResult(
                    readable = lastText != null,
                    matched = false,
                    text = lastText,
                )
            }
            Thread.sleep(60L)
        }
    }

    private fun requireSafeEnvironment() {
        val foregroundPackage = runCatching { transport.foregroundPackage() }
            .getOrDefault("")
        if (!TransportPolicy.foregroundAllowsSend(
                transport.mode,
                foregroundPackage,
                WECHAT_PACKAGE,
            )
        ) {
            throw IllegalStateException(
                "微信不在前台，发送已取消（检测到 " +
                    foregroundPackage.ifBlank { "<unknown>" } +
                    "）",
            )
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        SendSafetyGuard.check(
            interactive = power.isInteractive,
            locked = keyguard.isKeyguardLocked,
        )?.let { throw IllegalStateException(SendSafetyGuard.message(it)) }
    }

    private fun capture(): Bitmap {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "send")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("unable to create send cache")
        }
        val output = File(directory, "latest.png")
        transport.screenshot(output)
        return BitmapFactory.decodeFile(output.absolutePath)
            ?: throw IllegalStateException("unable to decode send screenshot")
    }

    private fun pixels(bitmap: Bitmap): BitmapPixelSource {
        return BitmapPixelSource(bitmap)
    }

    private fun typingDelay(part: String): Long {
        val seconds = min(6.0, 0.6 + 0.07 * part.length)
        return (Random.nextDouble(seconds * 0.8, seconds * 1.3) * 1000).toLong()
    }

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
