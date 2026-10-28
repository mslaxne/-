package com.local.wxbotprobe.memory.v2

import android.content.Context
import com.local.wxbotprobe.api.ApiBudgetExceededException
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.bot.ChatClient
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface MemoryV2BatchGenerator {
    fun generate(job: LeasedMemoryJob): String

    fun maxModelCalls(job: LeasedMemoryJob): Int = 1
}

enum class MemoryV2WorkerOutcome {
    PROGRESS,
    IDLE,
    PAUSED_HOURLY,
    PAUSED_DAILY,
}

data class MemoryV2WorkerPass(
    val outcome: MemoryV2WorkerOutcome,
    val retryDelayMillis: Long? = null,
)

enum class MemoryV2PumpState {
    IDLE,
    RUNNING,
    PAUSED_HOURLY,
    PAUSED_DAILY,
}

object MemoryV2PumpStatus {
    @Volatile
    private var current = MemoryV2PumpState.IDLE

    fun state(): MemoryV2PumpState = current

    internal fun set(value: MemoryV2PumpState) {
        current = value
    }
}

class MemoryV2BackgroundWorker(
    private val context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val generator: MemoryV2BatchGenerator? = null,
    private val budget: MemoryV2CallBudgetStore = MemoryV2CallBudgetStore(context),
    private val outboxProcessor: MemoryV2OutboxProcessor = SqlRetrievalOutboxProcessor,
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val config = MemoryV2ConfigLoader.load(context)
    private val jobs = MemoryV2JobService(context, database, now)
    private val commits = MemoryV2BatchCommitService(context, database, now)
    private val outbox = MemoryV2OutboxWorker(
        context,
        database,
        outboxProcessor,
        now,
    )
    private val expiry = MemoryV2ExpiryService(context, database)
    private val cleanup = MemoryV2MessageCleanupService(context, database, now)

    fun runOnce(owner: String = "memory-v2-worker"): Boolean {
        return runOnceDetailed(owner).outcome == MemoryV2WorkerOutcome.PROGRESS
    }

    fun runOnceDetailed(
        owner: String = "memory-v2-worker",
    ): MemoryV2WorkerPass {
        val expiryReconciled = runCatching {
            expiry.reconcileRecentEvents() > 0
        }.getOrDefault(false)
        val outboxProcessed = outbox.runOnce("$owner-outbox")
        val overflowRepaired = runCatching {
            jobs.splitFailedOverflowBatch().changed
        }.getOrDefault(false)
        val jobOutcome = runJobOnce(owner)
        if (
            jobOutcome == MemoryV2WorkerOutcome.PAUSED_HOURLY ||
            jobOutcome == MemoryV2WorkerOutcome.PAUSED_DAILY
        ) {
            return MemoryV2WorkerPass(jobOutcome)
        }
        val summaryQueued = runCatching {
            jobs.enqueueNextSummary() != null
        }.getOrDefault(false)
        val extractionQueued = runCatching {
            jobs.enqueueMissingExtraction() != null
        }.getOrDefault(false)
        val cleanupProcessed = runCatching {
            cleanup.runOnce().changed
        }.getOrDefault(false)
        val changed = expiryReconciled || outboxProcessed || overflowRepaired ||
            jobOutcome == MemoryV2WorkerOutcome.PROGRESS ||
            extractionQueued || summaryQueued || cleanupProcessed
        return MemoryV2WorkerPass(
            if (changed) {
                MemoryV2WorkerOutcome.PROGRESS
            } else {
                MemoryV2WorkerOutcome.IDLE
            },
            retryDelayMillis = if (changed) {
                null
            } else {
                jobs.nextPendingDelayMillis()
            },
        )
    }

    private fun runJobOnce(owner: String): MemoryV2WorkerOutcome {
        val generator = generator ?: createGenerator()
            ?: return MemoryV2WorkerOutcome.IDLE
        val leased = jobs.lease(owner, limit = 1).firstOrNull()
            ?: return MemoryV2WorkerOutcome.IDLE
        val reservedCalls = generator.maxModelCalls(leased).coerceIn(1, 4)
        if (!budget.tryAcquire(leased.scopeId, now(), reservedCalls)) {
            jobs.pauseUntil(
                leased,
                nextHourMillis() / 1_000L,
                MemoryV2ErrorCode.CALL_BUDGET_EXHAUSTED.name,
            )
            return MemoryV2WorkerOutcome.PAUSED_HOURLY
        }
        try {
            val raw = generator.generate(leased)
            when (leased.kind) {
                "extract" -> {
                    val batch = jobs.validateExtraction(leased, raw)
                    if (batch.candidates.isEmpty()) {
                        jobs.completeEmpty(leased, batch)
                    } else {
                        commits.commitExtraction(leased, batch)
                    }
                }
                "summary" -> {
                    val batch = jobs.validateSummary(leased, raw)
                    if (batch.events.isEmpty() && batch.workingItems.isEmpty()) {
                        jobs.completeEmpty(leased, batch)
                    } else {
                        commits.commitSummary(leased, batch)
                    }
                }
            }
            return MemoryV2WorkerOutcome.PROGRESS
        } catch (error: ApiBudgetExceededException) {
            budget.release(leased.scopeId, now(), reservedCalls)
            jobs.pauseUntil(
                leased,
                nextLocalDayMillis() / 1_000L,
                "DAILY_BUDGET_EXHAUSTED",
            )
            return MemoryV2WorkerOutcome.PAUSED_DAILY
        } catch (error: MemoryV2Exception) {
            if (error.code == MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED) {
                budget.release(leased.scopeId, now(), reservedCalls)
            }
            if (
                error.code in setOf(
                    MemoryV2ErrorCode.STALE_EPOCH,
                    MemoryV2ErrorCode.STALE_SUMMARY,
                    MemoryV2ErrorCode.STALE_VERSION,
                )
            ) {
                jobs.cancelForReschedule(leased, error.code)
                return MemoryV2WorkerOutcome.PROGRESS
            }
            if (error.code == MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED) {
                if (leased.kind == "summary") {
                    jobs.cancelForSummaryResize(leased, error.code)
                    return MemoryV2WorkerOutcome.PROGRESS
                }
                val childIds = runCatching {
                    jobs.splitContextOverflow(leased)
                }.getOrDefault(emptyList())
                if (childIds.isNotEmpty()) {
                    return MemoryV2WorkerOutcome.PROGRESS
                }
            }
            jobs.fail(leased, error.code.name)
            return MemoryV2WorkerOutcome.PROGRESS
        } catch (error: Throwable) {
            jobs.fail(
                leased,
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name,
            )
            return MemoryV2WorkerOutcome.PROGRESS
        }
    }

    private fun createGenerator(): MemoryV2BatchGenerator? {
        val client: ChatClient = ModelClientFactory.create(context) ?: return null
        return MemoryV2DeepSeekBatchGenerator(
            context = context,
            database = database,
            client = client,
            now = now,
            enableTools = true,
        )
    }

    companion object {
        private val executor = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "memory-v2-worker").apply { isDaemon = true }
        }
        private val scheduled = AtomicBoolean(false)
        private val generation = AtomicInteger(0)

        @Volatile
        private var scheduledFuture: ScheduledFuture<*>? = null

        fun kick(context: Context) {
            if (!scheduled.compareAndSet(false, true)) {
                return
            }
            val appContext = context.applicationContext
            schedule(appContext, 0L)
        }

        fun restart(context: Context) {
            generation.incrementAndGet()
            scheduledFuture?.cancel(false)
            scheduled.set(false)
            MemoryV2PumpStatus.set(MemoryV2PumpState.IDLE)
            kick(context)
        }

        fun isPumping(): Boolean {
            return MemoryV2PumpStatus.state() == MemoryV2PumpState.RUNNING
        }

        fun pumpState(): MemoryV2PumpState {
            return MemoryV2PumpStatus.state()
        }

        private fun schedule(context: Context, delayMillis: Long) {
            val token = generation.get()
            scheduledFuture = executor.schedule(
                {
                    if (token != generation.get()) {
                        return@schedule
                    }
                    try {
                        MemoryV2PumpStatus.set(MemoryV2PumpState.RUNNING)
                        var nextDelay: Long? = null
                        var processed = 0
                        val worker = MemoryV2BackgroundWorker(context)
                        while (
                            processed < MAX_PASSES_PER_WAKE &&
                            token == generation.get()
                        ) {
                            val pass = worker.runOnceDetailed()
                            when (pass.outcome) {
                                MemoryV2WorkerOutcome.PROGRESS -> processed++
                                MemoryV2WorkerOutcome.IDLE -> {
                                    nextDelay = pass.retryDelayMillis
                                    break
                                }
                                MemoryV2WorkerOutcome.PAUSED_HOURLY -> {
                                    nextDelay = millisUntilNextHour()
                                    MemoryV2PumpStatus.set(
                                        MemoryV2PumpState.PAUSED_HOURLY,
                                    )
                                    break
                                }
                                MemoryV2WorkerOutcome.PAUSED_DAILY -> {
                                    nextDelay = millisUntilNextLocalDay()
                                    MemoryV2PumpStatus.set(
                                        MemoryV2PumpState.PAUSED_DAILY,
                                    )
                                    break
                                }
                            }
                        }
                        if (token != generation.get()) {
                            return@schedule
                        }
                        if (nextDelay == null) {
                            MemoryV2PumpStatus.set(MemoryV2PumpState.IDLE)
                            scheduled.set(false)
                            scheduledFuture = null
                        } else {
                            schedule(context, nextDelay)
                        }
                    } catch (_: Throwable) {
                        if (token == generation.get()) {
                            MemoryV2PumpStatus.set(MemoryV2PumpState.IDLE)
                            scheduled.set(false)
                            scheduledFuture = null
                        }
                    }
                },
                delayMillis,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun nextHourMillis(): Long {
            val now = System.currentTimeMillis()
            return now - (now % 3_600_000L) + 3_600_000L
        }

        private fun millisUntilNextHour(): Long {
            return (
                nextHourMillis() - System.currentTimeMillis() + 2_000L
                ).coerceAtLeast(1_000L)
        }

        private fun nextLocalDayMillis(): Long {
            val zone = ZoneId.systemDefault()
            return ZonedDateTime.now(zone)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        }

        private fun millisUntilNextLocalDay(): Long {
            return (
                nextLocalDayMillis() - System.currentTimeMillis() + 2_000L
                ).coerceAtLeast(1_000L)
        }

        private const val MAX_PASSES_PER_WAKE = 1_000
    }
}

class MemoryV2CallBudgetStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val tier = MemoryBudgetSettings.hourlyTier(context)

    @Synchronized
    fun tryAcquire(
        scopeId: String,
        nowEpochSeconds: Long,
        units: Int = 1,
    ): Boolean {
        val cost = units.coerceAtLeast(1)
        val hour = SimpleDateFormat("yyyyMMddHH", Locale.US)
            .format(Date(nowEpochSeconds * 1_000L))
        val deviceKey = "device:$hour"
        val scopeKey = "scope:$scopeId:$hour"
        val deviceCount = preferences.getInt(deviceKey, 0)
        val scopeCount = preferences.getInt(scopeKey, 0)
        val scopeLimit = tier.scopeLimit
        val deviceLimit = tier.deviceLimit
        if (
            (deviceLimit != null && deviceCount + cost > deviceLimit) ||
            (scopeLimit != null && scopeCount + cost > scopeLimit)
        ) {
            return false
        }
        preferences.edit()
            .putInt(deviceKey, deviceCount + cost)
            .putInt(scopeKey, scopeCount + cost)
            .apply()
        return true
    }

    @Synchronized
    fun release(
        scopeId: String,
        nowEpochSeconds: Long,
        units: Int = 1,
    ) {
        val cost = units.coerceAtLeast(1)
        val hour = SimpleDateFormat("yyyyMMddHH", Locale.US)
            .format(Date(nowEpochSeconds * 1_000L))
        val deviceKey = "device:$hour"
        val scopeKey = "scope:$scopeId:$hour"
        val deviceCount = preferences.getInt(deviceKey, 0)
        val scopeCount = preferences.getInt(scopeKey, 0)
        preferences.edit()
            .putInt(deviceKey, (deviceCount - cost).coerceAtLeast(0))
            .putInt(scopeKey, (scopeCount - cost).coerceAtLeast(0))
            .apply()
    }

    companion object {
        private const val PREFS = "memory_v2_call_budget"
    }
}
