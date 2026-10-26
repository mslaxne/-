package com.local.wxbotprobe.shutdown

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.local.wxbotprobe.AgentBubbleService
import com.local.wxbotprobe.AgentRunState
import com.local.wxbotprobe.BotLoopService
import com.local.wxbotprobe.BubbleSettings
import com.local.wxbotprobe.ProbeStatus
import com.local.wxbotprobe.ScreenCaptureService
import com.local.wxbotprobe.loop.MemoryCoordinator
import java.util.concurrent.atomic.AtomicBoolean

data class ShutdownResult(
    val summarized: Boolean,
    val timedOut: Boolean,
    val error: String = "",
)

object ShutdownPolicy {
    fun shouldSummarize(state: AgentRunState): Boolean {
        return state != AgentRunState.OFF
    }
}

object AppShutdownCoordinator {
    private const val SUMMARY_TIMEOUT_MS = 8_000L
    private val inProgress = AtomicBoolean(false)

    fun shutdown(context: Context, onFinished: (ShutdownResult) -> Unit) {
        if (!inProgress.compareAndSet(false, true)) {
            onFinished(ShutdownResult(summarized = false, timedOut = false))
            return
        }
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val summarize = ShutdownPolicy.shouldSummarize(BotLoopService.runState)
        val nowMillis = System.currentTimeMillis()

        stopImmediately(appContext)
        if (!summarize) {
            finish(ShutdownResult(summarized = false, timedOut = false), onFinished, finished)
            return
        }
        ProbeStatus.write(appContext, "正在退出：代理已停止，限时整理记忆。")

        val timeout = Runnable {
            finish(
                ShutdownResult(
                    summarized = false,
                    timedOut = true,
                    error = "记忆整理超时，已保留待恢复记录",
                ),
                onFinished,
                finished,
            )
        }
        mainHandler.postDelayed(timeout, SUMMARY_TIMEOUT_MS)

        Thread({
            val error = runCatching {
                MemoryCoordinator(
                    context = appContext,
                    onLog = { Log.e("HandoraShutdown", it) },
                    onStatus = { ProbeStatus.write(appContext, it) },
                ).closeAndRecover(nowMillis)
            }.exceptionOrNull()?.message.orEmpty()

            mainHandler.post {
                mainHandler.removeCallbacks(timeout)
                finish(
                    ShutdownResult(
                        summarized = error.isEmpty(),
                        timedOut = false,
                        error = error,
                    ),
                    onFinished,
                    finished,
                )
            }
        }, "handora-shutdown").start()
    }

    private fun stopImmediately(context: Context) {
        BubbleSettings.setEnabled(context, false)
        BotLoopService.requestEmergencyShutdown(context)
        runCatching {
            context.stopService(Intent(context, AgentBubbleService::class.java))
        }
        runCatching {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
        context.getSystemService(NotificationManager::class.java).cancelAll()
        ProbeStatus.write(context, "正在退出代理，读屏服务已停止。")
    }

    private fun finish(
        result: ShutdownResult,
        onFinished: (ShutdownResult) -> Unit,
        finished: AtomicBoolean,
    ) {
        if (!finished.compareAndSet(false, true)) {
            return
        }
        inProgress.set(false)
        onFinished(result)
    }
}
