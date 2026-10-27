package com.local.wxbotprobe.loop

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.os.SystemClock
import com.local.wxbotprobe.DeviceTransport
import com.local.wxbotprobe.TransportPolicy
import com.local.wxbotprobe.chat.CompletedBurst
import com.local.wxbotprobe.chat.FrameFingerprint
import com.local.wxbotprobe.chat.LiveChatState
import com.local.wxbotprobe.chat.RecognitionAuditInput
import com.local.wxbotprobe.chat.RecognitionAuditPolicy
import com.local.wxbotprobe.chat.ScreenSnapshot
import com.local.wxbotprobe.device.DeviceProfileResolver
import com.local.wxbotprobe.ocr.RapidOcrAndroidEngine
import com.local.wxbotprobe.ocr.LocalOcrAuditor
import com.local.wxbotprobe.ocr.AuditCandidateTracker
import com.local.wxbotprobe.ocr.AuditCandidateMerger
import com.local.wxbotprobe.ocr.LocalOcrAuditResult
import com.local.wxbotprobe.ocr.ScreenSnapshotBuilder
import com.local.wxbotprobe.ocr.SnapshotRegions
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class PollReading(
    val snapshot: ScreenSnapshot,
    val wasPrimed: Boolean,
    val isPrimed: Boolean,
    val completed: CompletedBurst?,
    val recheckSoon: Boolean,
)

class PollingCoordinator(
    private val context: Context,
    private val transport: DeviceTransport,
    private val deviceActions: DeviceActionGate,
    private val canPoll: () -> Boolean,
    private val onStatus: (String) -> Unit,
    private val onNotification: (String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val state = LiveChatState(BURST_WAIT_MS, BURST_MAX_AGE_MS)
    private var lastForegroundPackage = ""
    private var lastFingerprint: FrameFingerprint? = null
    private var lastSnapshot: ScreenSnapshot? = null
    private var previousChatFingerprint: Long? = null
    private var previousItemCount = 0
    private var previousOcrRowCount = 0
    private var lastRecheckRequestedAt = 0L
    private val auditCandidates = AuditCandidateTracker()
    private val auditMerger = AuditCandidateMerger()
    private val screenAvailability = ScreenAvailabilityTracker()
    private val auditExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wxbot-audit").apply { isDaemon = true }
    }
    private val auditScheduled = AtomicBoolean(false)
    private val auditClosed = AtomicBoolean(false)

    fun capture(forceOcr: Boolean = false): ScreenSnapshot? {
        onLog("poll stage=start")
        if (!canPoll()) {
            onLog("poll stage=blocked agent-state")
            return null
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val screenAvailable = !keyguard.isKeyguardLocked && power.isInteractive
        when (screenAvailability.update(screenAvailable)) {
            ScreenAvailabilityChange.PAUSED -> {
                onLog("poll paused screen unavailable")
                onNotification("等待屏幕点亮")
                onStatus("已暂停读屏：屏幕已锁定或熄灭，点亮后自动恢复。")
            }
            ScreenAvailabilityChange.RESUMED -> {
                onLog("poll resumed screen available")
                onNotification("正在监听微信")
                onStatus("读屏已恢复，正在监听微信。")
            }
            null -> Unit
        }
        if (!screenAvailable) return null
        val foregroundPackage = runCatching { transport.foregroundPackage() }
            .getOrDefault("")
        if (!TransportPolicy.foregroundAllowsSend(
                transport.mode,
                foregroundPackage,
                WECHAT_PACKAGE,
            )
        ) {
            onLog(
                "poll stage=blocked foreground=" +
                    foregroundPackage.ifBlank { "<unknown>" },
            )
            if (foregroundPackage != lastForegroundPackage) {
                lastForegroundPackage = foregroundPackage
                onLog(
                    "poll paused outside WeChat: " +
                        foregroundPackage.ifBlank { "<unknown>" },
                )
                onNotification("等待微信回到前台")
            }
            resetForNewBaseline()
            return null
        }
        onLog("poll stage=foreground package=$foregroundPackage")

        val directory = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "monitor",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建监控目录")
        }
        val screenshot = File(directory, "latest.png")
        val screenshotStartedAt = SystemClock.elapsedRealtime()
        onLog("poll stage=screenshot-start")
        deviceActions.run {
            transport.screenshot(screenshot)
        }
        val screenshotFinishedAt = SystemClock.elapsedRealtime()
        onLog(
            "poll stage=screenshot-done " +
                "screenshotMs=${screenshotFinishedAt - screenshotStartedAt}",
        )
        val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
            ?: throw IllegalStateException("监控截图解码失败")
        val decodedAt = SystemClock.elapsedRealtime()
        onLog("poll stage=decode-done decodeMs=${decodedAt - screenshotFinishedAt}")
        val fingerprint = frameFingerprint(bitmap)
        val fingerprintedAt = SystemClock.elapsedRealtime()
        val previousFingerprint = lastFingerprint
        val frameDifference = previousFingerprint?.differenceFrom(fingerprint)
        val screenshotMs = screenshotFinishedAt - screenshotStartedAt
        val decodeMs = decodedAt - screenshotFinishedAt
        val fingerprintMs = fingerprintedAt - decodedAt
        if (
            !forceOcr &&
            state.isPrimed &&
            previousFingerprint != null &&
            fingerprint.isSameScene(previousFingerprint) &&
            auditCandidates.pendingCount() == 0 &&
            auditMerger.pendingCount() == 0
        ) {
            lastFingerprint = fingerprint
            bitmap.recycle()
            val cached = lastSnapshot
            if (state.hasPendingBurst && cached != null) {
                onLog(
                    "poll OCR reused unchanged burst check " +
                        "meanDiff=${formatMetric(frameDifference?.meanChannelDiff)} " +
                        "changedRatio=${formatMetric(frameDifference?.changedRatio)}",
                )
                return cached.copy(
                    frameMeanDiff = frameDifference?.meanChannelDiff,
                    frameChangedRatio = frameDifference?.changedRatio,
                )
            }
            onLog(
                "poll OCR skipped unchanged " +
                    "meanDiff=${formatMetric(frameDifference?.meanChannelDiff)} " +
                    "changedRatio=${formatMetric(frameDifference?.changedRatio)} " +
                    "screenshotMs=$screenshotMs decodeMs=$decodeMs " +
                    "fingerprintMs=$fingerprintMs",
            )
            return null
        }
        val ocrStartedAt = SystemClock.elapsedRealtime()
        val cachedSnapshot = lastSnapshot
        val useFastRegion = !forceOcr &&
            state.isPrimed &&
            cachedSnapshot != null &&
            cachedSnapshot.chat.fingerprint != 0L
        val baseSnapshot = try {
            val profile = DeviceProfileResolver.current(context)
            val builder = ScreenSnapshotBuilder(
                RapidOcrAndroidEngine.get(context),
                profile,
            )
            if (useFastRegion) {
                val top = SnapshotRegions.messageTop(bitmap.height, profile)
                builder.readRegion(
                    bitmap = bitmap,
                    top = top,
                    height = bitmap.height - top,
                    chat = cachedSnapshot!!.chat,
                    engineName = "rapidocr-android-1.3.0-fast-region-" +
                        profile.fastRegionHeight,
                )
            } else {
                builder.read(
                    bitmap,
                    "rapidocr-android-1.3.0-full-fast",
                )
            }
        } finally {
            bitmap.recycle()
        }
        val ocrMs = SystemClock.elapsedRealtime() - ocrStartedAt
        val snapshot = baseSnapshot.copy(
            frameMeanDiff = frameDifference?.meanChannelDiff,
            frameChangedRatio = frameDifference?.changedRatio,
        )
        lastFingerprint = fingerprint
        onLog(
            "poll OCR executed " +
                "meanDiff=${formatMetric(frameDifference?.meanChannelDiff)} " +
                "changedRatio=${formatMetric(frameDifference?.changedRatio)} " +
                "screenshotMs=$screenshotMs decodeMs=$decodeMs " +
                "fingerprintMs=$fingerprintMs ocrMs=$ocrMs " +
                "engineMs=${snapshot.ocrEngineMs} rulesMs=${snapshot.pixelRulesMs} " +
                "mode=${if (useFastRegion) "fast-region" else "full-fast"} " +
                "items=${snapshot.items.size} rows=${snapshot.ocrRowCount}",
        )
        if (!canPoll()) {
            return null
        }
        val merged = auditMerger.merge(
            snapshot,
            SystemClock.elapsedRealtime(),
        )
        if (merged.addedCount > 0) {
            onLog("poll OCR audit merged count=${merged.addedCount}")
        }
        lastSnapshot = merged.snapshot
        return merged.snapshot
    }

    private fun formatMetric(value: Double?): String {
        return value?.let { String.format(Locale.US, "%.6f", it) } ?: "n/a"
    }

    private fun resetForNewBaseline() {
        val hadState = state.isPrimed ||
            state.hasPendingBurst ||
            lastSnapshot != null ||
            lastFingerprint != null
        state.resetForNewBaseline()
        lastFingerprint = null
        lastSnapshot = null
        previousChatFingerprint = null
        previousItemCount = 0
        previousOcrRowCount = 0
        auditCandidates.clear()
        auditMerger.clear()
        if (hadState) {
            onLog("poll baseline reset outside WeChat")
        }
    }

    private fun frameFingerprint(bitmap: Bitmap): FrameFingerprint {
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            FrameFingerprint.THUMBNAIL_WIDTH,
            FrameFingerprint.THUMBNAIL_HEIGHT,
            true,
        )
        return try {
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(
                pixels,
                0,
                scaled.width,
                0,
                0,
                scaled.width,
                scaled.height,
            )
            FrameFingerprint(pixels)
        } finally {
            if (scaled !== bitmap) {
                scaled.recycle()
            }
        }
    }

    fun observe(snapshot: ScreenSnapshot): PollReading {
        val wasPrimed = state.isPrimed
        val now = SystemClock.elapsedRealtime()
        val completed = state.observe(
            snapshot.items,
            snapshot.isGroup,
            now,
            snapshot.chat.fingerprint,
            snapshot.chat.title,
        )
        if (state.lastFreshCount > 0 || completed != null) {
            onLog(
                "poll result items=${snapshot.items.size} " +
                    "fresh=${state.lastFreshCount} " +
                    "burst=${completed?.items?.size ?: 0}",
            )
        }
        logAuditConfirmations(
            "poll OCR audit candidate",
            auditCandidates.observeFullFrame(
                snapshot.items,
                snapshot.chat.fingerprint,
                now,
            ),
        )
        val sameChat = previousChatFingerprint != null &&
            previousChatFingerprint == snapshot.chat.fingerprint
        val cooldownReady = lastRecheckRequestedAt == 0L ||
            now - lastRecheckRequestedAt >= RECHECK_COOLDOWN_MS
        val recheckSoon = RecognitionAuditPolicy.shouldRecheck(
            RecognitionAuditInput(
                primed = wasPrimed,
                sameChat = sameChat,
                frameChanged = RecognitionAuditPolicy.frameChanged(
                    snapshot.frameMeanDiff,
                    snapshot.frameChangedRatio,
                ),
                previousItemCount = previousItemCount,
                currentItemCount = snapshot.items.size,
                previousOcrRowCount = previousOcrRowCount,
                currentOcrRowCount = snapshot.ocrRowCount,
                burstCompleted = completed != null,
                cooldownReady = cooldownReady,
                pendingAuditCandidates = auditCandidates.pendingCount() > 0,
                freshIncomingDetected = state.lastFreshCount > 0,
                pendingBurst = state.hasPendingBurst,
            ),
        )
        if (recheckSoon) {
            lastRecheckRequestedAt = now
            onLog(
                "poll OCR recheck scheduled " +
                    "meanDiff=${snapshot.frameMeanDiff ?: -1.0} " +
                    "changedRatio=${snapshot.frameChangedRatio ?: -1.0} " +
                    "items=${snapshot.items.size} rows=${snapshot.ocrRowCount} " +
                    "pending=${auditCandidates.pendingCount()}",
            )
        }
        previousChatFingerprint = snapshot.chat.fingerprint
        previousItemCount = snapshot.items.size
        previousOcrRowCount = snapshot.ocrRowCount
        return PollReading(
            snapshot = snapshot,
            wasPrimed = wasPrimed,
            isPrimed = state.isPrimed,
            completed = completed,
            recheckSoon = recheckSoon,
        )
    }

    fun auditSuspiciousFrame(snapshot: ScreenSnapshot) {
        if (auditClosed.get() || !auditScheduled.compareAndSet(false, true)) {
            onLog("poll OCR audit skipped busy")
            return
        }
        val source = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "monitor/latest.png",
        )
        if (!source.isFile) {
            auditScheduled.set(false)
            return
        }
        val screenshot = File(source.parentFile, "audit-${SystemClock.elapsedRealtime()}.png")
        val copied = runCatching {
            source.copyTo(screenshot, overwrite = true)
        }.isSuccess
        if (!copied) {
            auditScheduled.set(false)
            return
        }
        auditExecutor.execute {
            try {
                runSuspiciousAudit(snapshot, screenshot)
            } finally {
                screenshot.delete()
                auditScheduled.set(false)
            }
        }
    }

    fun isAuditInFlight(): Boolean = auditScheduled.get()

    private fun runSuspiciousAudit(
        snapshot: ScreenSnapshot,
        screenshot: File,
    ) {
        val bitmap = runCatching {
            android.graphics.BitmapFactory.decodeFile(screenshot.absolutePath)
        }.getOrNull() ?: return
        try {
            val result = LocalOcrAuditor(RapidOcrAndroidEngine.get(context)).audit(
                bitmap = bitmap,
                inputY = snapshot.inputField?.y ?: (snapshot.height * 0.95).toInt(),
                baseTexts = snapshot.items.map { it.text },
                profile = DeviceProfileResolver.current(context),
            )
            val confirmations = auditCandidates.observeAudit(
                result.candidates,
                snapshot.chat.fingerprint,
                SystemClock.elapsedRealtime(),
            )
            onLog(
                "poll OCR audit cropHeight=${result.cropHeight} " +
                    "rows=${result.filteredRows} recovered=${result.recoveredRows} " +
                    "pending=${auditCandidates.pendingCount()}",
            )
            logAuditConfirmations(
                "poll OCR audit candidate",
                confirmations,
            )
            val localConfirmations = confirmations.count {
                it.source == AuditCandidateTracker.ConfirmationSource.LOCAL_AUDIT
            }
            if (localConfirmations > 0) {
                val queued = auditMerger.enqueue(
                    confirmations,
                    SystemClock.elapsedRealtime(),
                )
                onLog(
                    "poll OCR audit merge queued count=$queued " +
                        "confirmed=$localConfirmations",
                )
            }
        } catch (error: Throwable) {
            onLog("poll OCR audit failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            bitmap.recycle()
        }
    }

    fun close() {
        if (auditClosed.compareAndSet(false, true)) {
            auditExecutor.shutdownNow()
        }
    }

    fun resetForStartupEvaluation() {
        resetForNewBaseline()
    }

    fun cachedSnapshot(): ScreenSnapshot? = lastSnapshot

    fun nextBurstDueAt(): Long? = state.burstDueAtMillis

    fun drainDueBurst(nowMillis: Long): CompletedBurst? {
        return state.drainBurstIfReady(nowMillis)
    }

    companion object {
        private const val BURST_WAIT_MS = 4_000L
        private const val BURST_MAX_AGE_MS = 12_000L
        private const val RECHECK_COOLDOWN_MS = 5_000L
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    private fun logAuditConfirmations(
        prefix: String,
        confirmations: List<AuditCandidateTracker.Confirmation>,
    ) {
        confirmations.forEach { confirmation ->
            onLog(
                "$prefix confirmed key=${confirmation.key} " +
                    "chat=${java.lang.Long.toHexString(confirmation.chatFingerprint)} " +
                    "kind=${confirmation.kind.name.lowercase()} " +
                    "y=${confirmation.y} " +
                    "textLength=${confirmation.textLength} " +
                    "source=${confirmation.source.name.lowercase()} " +
                    "observations=${confirmation.observations}",
            )
        }
    }
}
