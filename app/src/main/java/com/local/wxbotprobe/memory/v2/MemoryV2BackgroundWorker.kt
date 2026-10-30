package com.local.wxbotprobe.memory.v2

import android.content.Context
import com.local.wxbotprobe.api.ApiBudgetExceededException
import com.local.wxbotprobe.api.DeepSeekException
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.bot.ChatClient
import java.io.File
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
    STOPPED,
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

data class MemoryV2PumpSnapshot(
    val state: MemoryV2PumpState,
    val mode: MemoryV2PumpMode,
    val stage: MemoryV2PumpStage,
    val errorCode: String?,
)

object MemoryV2PumpStatus {
    @Volatile
    private var current = MemoryV2PumpState.IDLE

    @Volatile
    private var currentMode = MemoryV2PumpMode.BACKGROUND

    @Volatile
    private var currentStage = MemoryV2PumpStage.NONE

    @Volatile
    private var lastErrorCode: String? = null

    fun state(): MemoryV2PumpState = current

    fun snapshot(): MemoryV2PumpSnapshot {
        return MemoryV2PumpSnapshot(
            state = current,
            mode = currentMode,
            stage = currentStage,
            errorCode = lastErrorCode,
        )
    }

    internal fun set(value: MemoryV2PumpState) {
        current = value
    }

    internal fun begin(mode: MemoryV2PumpMode) {
        currentMode = mode
        currentStage = MemoryV2PumpStage.NONE
        lastErrorCode = null
        current = MemoryV2PumpState.RUNNING
    }

    internal fun stage(value: MemoryV2PumpStage) {
        currentStage = value
    }

    internal fun stop(errorCode: String) {
        currentStage = MemoryV2PumpStage.STOPPED
        lastErrorCode = errorCode
        current = MemoryV2PumpState.IDLE
    }

    internal fun finish() {
        if (currentMode == MemoryV2PumpMode.MANUAL) {
            if (currentStage != MemoryV2PumpStage.STOPPED) {
                currentStage = MemoryV2PumpStage.COMPLETE
            }
        } else {
            currentStage = MemoryV2PumpStage.NONE
        }
        current = MemoryV2PumpState.IDLE
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
    private val manualSourceKeys = mutableSetOf<String>()

    fun runOnce(owner: String = "memory-v2-worker"): Boolean {
        return runOnceDetailed(owner).outcome == MemoryV2WorkerOutcome.PROGRESS
    }

    fun runOnceDetailed(
        owner: String = "memory-v2-worker",
        forceExtraction: Boolean = false,
    ): MemoryV2WorkerPass {
        val maintenanceChanged = runMaintenance(owner)
        if (forceExtraction) {
            return runManualDrainPass(owner, maintenanceChanged)
        }
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
        val changed = maintenanceChanged ||
            jobOutcome == MemoryV2WorkerOutcome.PROGRESS ||
            extractionQueued || summaryQueued
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

    private fun runMaintenance(owner: String): Boolean {
        val expiryReconciled = runCatching {
            expiry.reconcileRecentEvents() > 0
        }.getOrDefault(false)
        val outboxProcessed = outbox.runOnce("$owner-outbox")
        val overflowRepaired = runCatching {
            jobs.splitFailedOverflowBatch().changed
        }.getOrDefault(false)
        val cleanupProcessed = runCatching {
            cleanup.runOnce().changed
        }.getOrDefault(false)
        return expiryReconciled || outboxProcessed ||
            overflowRepaired || cleanupProcessed
    }

    private fun runManualDrainPass(
        owner: String,
        maintenanceChanged: Boolean,
    ): MemoryV2WorkerPass {
        val extractionQueue = runCatching {
            jobs.enqueueMissingExtraction(
                force = true,
                excludedSourceKeys = manualSourceKeys,
            ).also { queued ->
                queued?.envelope?.sources?.map { it.messageId }?.let {
                    manualSourceKeys += manualSourceKey(it)
                }
            }
        }.getOrNull()
        val queuedExtraction = extractionQueue?.status == "pending"
        val extraction = jobs.lease(
            owner = owner,
            limit = 1,
            kind = "extract",
            ignoreNextAttempt = true,
        ).firstOrNull()
        if (extraction != null) {
            manualSourceKeys += manualSourceKey(extraction.envelope.newSourceIds)
            MemoryV2PumpStatus.stage(MemoryV2PumpStage.EXTRACTION)
            return manualPass(runLeasedJob(extraction, manualDrain = true))
        }

        val summaryQueue = runCatching {
            jobs.enqueueNextSummary(
                forceOpen = true,
                checkpointSize = MemoryV2ManualDrainPolicy.summaryCheckpointSize(),
            )
        }.getOrNull()
        val queuedSummary = summaryQueue?.status == "pending"
        val summary = jobs.lease(
            owner = owner,
            limit = 1,
            kind = "summary",
            ignoreNextAttempt = true,
        ).firstOrNull()
        if (summary != null) {
            MemoryV2PumpStatus.stage(MemoryV2PumpStage.SUMMARY)
            return manualPass(runLeasedJob(summary, manualDrain = true))
        }

        val hasPendingExtraction = hasPendingMessages("extraction_state")
        val hasPendingSummary = hasPendingMessages("summary_state")
        val stage = MemoryV2ManualDrainPolicy.stage(
            hasPendingExtraction = hasPendingExtraction,
            hasPendingSummary = hasPendingSummary,
        )
        MemoryV2PumpStatus.stage(stage)
        if (hasPendingExtraction) {
            MemoryV2PumpStatus.stop("EXTRACTION_NOT_SCHEDULABLE")
            return MemoryV2WorkerPass(MemoryV2WorkerOutcome.STOPPED)
        }
        if (hasPendingSummary) {
            MemoryV2PumpStatus.stop("SUMMARY_NOT_SCHEDULABLE")
            return MemoryV2WorkerPass(MemoryV2WorkerOutcome.STOPPED)
        }
        val changed = maintenanceChanged || queuedExtraction || queuedSummary
        return MemoryV2WorkerPass(
            if (changed) {
                MemoryV2WorkerOutcome.PROGRESS
            } else {
                MemoryV2WorkerOutcome.IDLE
            },
        )
    }

    private fun manualPass(outcome: MemoryV2WorkerOutcome): MemoryV2WorkerPass {
        return MemoryV2WorkerPass(outcome)
    }

    private fun hasPendingMessages(column: String): Boolean {
        require(column in setOf("extraction_state", "summary_state"))
        return database.readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE $column='pending' " +
                "AND state='live' LIMIT 1",
            null,
        ).use { it.moveToFirst() }
    }

    private fun runJobOnce(
        owner: String,
    ): MemoryV2WorkerOutcome {
        val generator = generator ?: createGenerator(false)
            ?: return MemoryV2WorkerOutcome.IDLE
        val leased = jobs.lease(
            owner = owner,
            limit = 1,
            kind = null,
            ignoreNextAttempt = false,
        ).firstOrNull()
            ?: return MemoryV2WorkerOutcome.IDLE
        return runLeasedJob(leased, manualDrain = false, batchGenerator = generator)
    }

    private fun runLeasedJob(
        leased: LeasedMemoryJob,
        manualDrain: Boolean,
        batchGenerator: MemoryV2BatchGenerator? = null,
    ): MemoryV2WorkerOutcome {
        val generator = batchGenerator ?: generator ?: createGenerator(manualDrain)
        if (generator == null) {
            jobs.pauseUntil(
                leased,
                now(),
                "MODEL_NOT_CONFIGURED",
            )
            if (manualDrain) {
                MemoryV2PumpStatus.stop("MODEL_NOT_CONFIGURED")
                return MemoryV2WorkerOutcome.STOPPED
            }
            return MemoryV2WorkerOutcome.IDLE
        }
        val reservedCalls = generator.maxModelCalls(leased).coerceIn(1, 4)
        val budgetReserved = !manualDrain &&
            budget.tryAcquire(leased.scopeId, now(), reservedCalls)
        if (!manualDrain && !budgetReserved) {
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
            if (budgetReserved) {
                budget.release(leased.scopeId, now(), reservedCalls)
            }
            jobs.pauseUntil(
                leased,
                nextLocalDayMillis() / 1_000L,
                "DAILY_BUDGET_EXHAUSTED",
            )
            if (manualDrain) {
                MemoryV2PumpStatus.stop("DAILY_BUDGET_EXHAUSTED")
                return MemoryV2WorkerOutcome.STOPPED
            }
            return MemoryV2WorkerOutcome.PAUSED_DAILY
        } catch (error: MemoryV2Exception) {
            appendLog(
                context,
                "job failed id=${leased.jobId} kind=${leased.kind} " +
                    "attempt=${leased.attempts}/${leased.maxAttempts} " +
                    "code=${error.code.name} " +
                    "manualCritical=" +
                    "${MemoryV2ManualDrainPolicy.requiresStop(error.code)} " +
                    "detail=${error.message.orEmpty().take(160)}",
            )
            if (error.code == MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED) {
                if (budgetReserved) {
                    budget.release(leased.scopeId, now(), reservedCalls)
                }
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
            if (
                !manualDrain &&
                leased.kind == "extract" &&
                error.code == MemoryV2ErrorCode.SCHEMA_INVALID &&
                leased.envelope.newSourceIds.size > 1
            ) {
                val childIds = runCatching {
                    jobs.splitContextOverflow(
                        leased,
                        cancellationReason = MemoryV2ErrorCode.SCHEMA_INVALID,
                    )
                }.getOrDefault(emptyList())
                if (childIds.isNotEmpty()) {
                    appendLog(
                        context,
                        "job split after schema failure id=${leased.jobId} " +
                            "children=${childIds.size}",
                    )
                    return MemoryV2WorkerOutcome.PROGRESS
                }
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
            if (manualDrain) {
                runCatching { jobs.fail(leased, error.code.name) }
                MemoryV2PumpStatus.stop(error.code.name)
                return MemoryV2WorkerOutcome.STOPPED
            }
            jobs.fail(leased, error.code.name)
            return MemoryV2WorkerOutcome.PROGRESS
        } catch (error: Throwable) {
            val errorCode = if (error is DeepSeekException) {
                MemoryV2ErrorCode.MODEL_REQUEST_FAILED
            } else {
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE
            }
            val detail = if (error is DeepSeekException) {
                " status=${error.status ?: "none"} retryable=${error.retryable}"
            } else {
                ""
            }
            appendLog(
                context,
                "job crashed id=${leased.jobId} kind=${leased.kind} " +
                    "attempt=${leased.attempts}/${leased.maxAttempts} " +
                    "code=${errorCode.name} class=${error.javaClass.simpleName}$detail",
            )
            if (manualDrain) {
                runCatching {
                    jobs.fail(
                        leased,
                        errorCode.name,
                    )
                }
                MemoryV2PumpStatus.stop(errorCode.name)
                return MemoryV2WorkerOutcome.STOPPED
            }
            jobs.fail(
                leased,
                errorCode.name,
            )
            return MemoryV2WorkerOutcome.PROGRESS
        }
    }

    private fun createGenerator(
        bypassLimits: Boolean,
    ): MemoryV2BatchGenerator? {
        val client: ChatClient = ModelClientFactory.create(
            context = context,
            enforceDailyBudget = !bypassLimits,
        ) ?: return null
        return MemoryV2DeepSeekBatchGenerator(
            context = context,
            database = database,
            client = client,
            now = now,
            enableTools = true,
        )
    }

    private fun manualSourceKey(sourceIds: List<String>): String {
        return sourceIds.joinToString("\u0000")
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
            schedule(appContext, 0L, mode = MemoryV2PumpMode.BACKGROUND)
        }

        fun restart(
            context: Context,
            forceExtraction: Boolean = false,
        ) {
            generation.incrementAndGet()
            scheduledFuture?.cancel(false)
            scheduled.set(false)
            MemoryV2PumpStatus.set(MemoryV2PumpState.IDLE)
            if (!scheduled.compareAndSet(false, true)) {
                return
            }
            schedule(
                context.applicationContext,
                0L,
                mode = if (forceExtraction) {
                    MemoryV2PumpMode.MANUAL
                } else {
                    MemoryV2PumpMode.BACKGROUND
                },
            )
        }

        fun isPumping(): Boolean {
            return MemoryV2PumpStatus.state() == MemoryV2PumpState.RUNNING
        }

        fun pumpState(): MemoryV2PumpState {
            return MemoryV2PumpStatus.state()
        }

        private fun schedule(
            context: Context,
            delayMillis: Long,
            mode: MemoryV2PumpMode,
        ) {
            val token = generation.get()
            scheduledFuture = executor.schedule(
                {
                    if (token != generation.get()) {
                        return@schedule
                    }
                    try {
                        MemoryV2PumpStatus.begin(mode)
                        var nextDelay: Long? = null
                        var processed = 0
                        val worker = MemoryV2BackgroundWorker(context)
                        while (
                            processed < MAX_PASSES_PER_WAKE &&
                            token == generation.get()
                        ) {
                            val pass = worker.runOnceDetailed(
                                forceExtraction = mode == MemoryV2PumpMode.MANUAL,
                            )
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
                                MemoryV2WorkerOutcome.STOPPED -> {
                                    nextDelay = null
                                    break
                                }
                            }
                        }
                        if (token != generation.get()) {
                            return@schedule
                        }
                        if (nextDelay == null) {
                            MemoryV2PumpStatus.finish()
                            scheduled.set(false)
                            scheduledFuture = null
                        } else {
                            schedule(
                                context,
                                nextDelay,
                                mode = MemoryV2PumpMode.BACKGROUND,
                            )
                        }
                    } catch (error: Throwable) {
                        appendLog(
                            context,
                            "worker wake crashed class=${error.javaClass.simpleName}",
                        )
                        if (token == generation.get()) {
                            MemoryV2PumpStatus.stop("WORKER_CRASH")
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
        private const val LOG_MAX_BYTES = 1_000_000L

        private fun appendLog(context: Context, message: String) {
            runCatching {
                val directory = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "logs",
                )
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                val file = File(directory, "memory-v2.log")
                if (file.length() > LOG_MAX_BYTES) {
                    val old = File(directory, "memory-v2.log.1")
                    if (old.exists()) {
                        old.delete()
                    }
                    file.renameTo(old)
                }
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.CHINA,
                ).format(Date())
                file.appendText("$timestamp $message\n")
            }
        }
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
