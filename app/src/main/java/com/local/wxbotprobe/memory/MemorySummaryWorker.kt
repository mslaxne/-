package com.local.wxbotprobe.memory

import android.content.Context
import com.local.wxbotprobe.ProbeStatus
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.memory.v2.MemoryV2ReadPath
import com.local.wxbotprobe.memory.v2.MemoryV2Settings
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object MemorySummaryGate {
    val lock = Any()
}

object MemorySummaryWorker {
    private const val SUCCESS_SPACING_MS = 250L
    private const val FAILURE_BACKOFF_MS = 30_000L
    private const val MISSING_KEY_BACKOFF_MS = 60_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "handora-memory-summary").apply { isDaemon = true }
    }
    private val scheduled = AtomicBoolean(false)

    @Volatile
    private var nextAllowedAt = 0L

    fun pendingCount(context: Context): Int {
        if (usesV2ReadPath(context)) {
            return 0
        }
        return store(context).awaitingSummary().size
    }

    fun kick(context: Context) {
        if (usesV2ReadPath(context)) {
            return
        }
        if (!scheduled.compareAndSet(false, true)) {
            return
        }
        val appContext = context.applicationContext
        val delay = (nextAllowedAt - System.currentTimeMillis()).coerceAtLeast(0L)
        executor.schedule(
            {
                try {
                    runOne(appContext)
                } catch (error: Throwable) {
                    nextAllowedAt = System.currentTimeMillis() + FAILURE_BACKOFF_MS
                    ProbeStatus.write(
                        appContext,
                        "聊天记忆后台整理异常：${error.message}，30 秒后重试。",
                    )
                } finally {
                    scheduled.set(false)
                    if (
                        !usesV2ReadPath(appContext) &&
                        store(appContext).awaitingSummary().isNotEmpty()
                    ) {
                        kick(appContext)
                    }
                }
            },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun runOne(context: Context) {
        if (usesV2ReadPath(context)) {
            return
        }
        val store = store(context)
        val pending = store.awaitingSummary()
        if (pending.isEmpty()) {
            return
        }
        val client = ModelClientFactory.create(
            context,
            timeoutMs = 30_000,
            maxRetries = 1,
        )
        if (client == null) {
            nextAllowedAt = System.currentTimeMillis() + MISSING_KEY_BACKOFF_MS
            ProbeStatus.write(
                context,
                "聊天记忆待整理：${pending.size} 个会话，等待模型配置。",
            )
            return
        }

        val session = pending.first()
        ProbeStatus.write(
            context,
            "正在后台整理聊天记忆：${session.displayName}（待处理 ${pending.size} 个）",
        )
        val outcome = synchronized(MemorySummaryGate.lock) {
            AgentMemoryManager(store).summarizeSession(
                sessionId = session.sessionId,
                nowMillis = System.currentTimeMillis(),
                client = client,
            )
        }
        val remaining = store.awaitingSummary().size
        if (outcome.completed) {
            nextAllowedAt = System.currentTimeMillis() + SUCCESS_SPACING_MS
            val suffix = if (remaining > 0) "，剩余 $remaining 个" else ""
            ProbeStatus.write(
                context,
                "聊天记忆整理完成：${session.displayName}$suffix。",
            )
        } else {
            nextAllowedAt = System.currentTimeMillis() + FAILURE_BACKOFF_MS
            ProbeStatus.write(
                context,
                "聊天记忆整理失败：${session.displayName}，待处理 $remaining 个，" +
                    "30 秒后重试。${outcome.error}",
            )
        }
    }

    private fun store(context: Context): ChatMemoryStore {
        return ChatMemoryStore(File(context.filesDir, "memory"))
    }

    private fun usesV2ReadPath(context: Context): Boolean {
        val snapshot = MemoryV2Settings.readPathSnapshot(context)
        return snapshot.enabled && snapshot.path == MemoryV2ReadPath.V2
    }
}
