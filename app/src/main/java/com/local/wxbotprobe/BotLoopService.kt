package com.local.wxbotprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.local.wxbotprobe.chat.GroupTitleWarningPolicy
import com.local.wxbotprobe.chat.ChatIdentityPolicy
import com.local.wxbotprobe.chat.BubbleKind
import com.local.wxbotprobe.chat.ScreenSnapshot
import com.local.wxbotprobe.chat.OutgoingConfirmationTracker
import com.local.wxbotprobe.chat.StartupConversationContextBuilder
import com.local.wxbotprobe.loop.DeviceActionGate
import com.local.wxbotprobe.loop.MemoryCoordinator
import com.local.wxbotprobe.loop.PollLivenessPolicy
import com.local.wxbotprobe.loop.PollingCoordinator
import com.local.wxbotprobe.loop.ReplyGenerationCoordinator
import com.local.wxbotprobe.loop.SendQueueCoordinator
import com.local.wxbotprobe.maintenance.RuntimeDataMaintenance
import com.local.wxbotprobe.send.AuthorizedSendChat
import com.local.wxbotprobe.send.SendAuthorizationIssue
import com.local.wxbotprobe.send.SendAuthorizationPolicy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class BotLoopService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var actionThread: HandlerThread
    private lateinit var actionHandler: Handler
    private lateinit var stateStore: AgentStateStore
    private lateinit var memoryCoordinator: MemoryCoordinator
    private lateinit var pollingCoordinator: PollingCoordinator
    private lateinit var replyCoordinator: ReplyGenerationCoordinator
    private lateinit var sendQueueCoordinator: SendQueueCoordinator
    private val deviceActions = DeviceActionGate()
    private val outgoingConfirmation = OutgoingConfirmationTracker()
    private val coordinationScheduled = AtomicBoolean(false)
    private val pollInFlight = AtomicBoolean(false)
    private val wakeScheduled = AtomicBoolean(false)
    private val wakeAfterPoll = AtomicBoolean(false)
    private val deliveryInFlight = AtomicBoolean(false)
    private val wakeAfterSend = AtomicBoolean(false)
    private val schedulerLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var startupEvaluationPending = false

    @Volatile
    private var boundSendChat: AuthorizedSendChat? = null

    @Volatile
    private var running = false
    private var groupTitleWarningIssued = false
    @Volatile
    private var lastPollActivityAt = 0L
    @Volatile
    private var lastWakeRequestedAt = 0L
    private val transport: DeviceTransport by lazy {
        DeviceTransportFactory.create(this)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) {
                return
            }
            if (deliveryInFlight.get()) {
                wakeAfterSend.set(true)
                log("poll deferred for send")
                return
            }
            pollInFlight.set(true)
            lastPollActivityAt = SystemClock.elapsedRealtime()
            log("poll tick")
            var nextDelay = POLL_INTERVAL_MS
            try {
                drainDueBurstBeforePoll()
                if (pollOnce()) {
                    nextDelay = OCR_RECHECK_DELAY_MS
                }
            } catch (error: Throwable) {
                log("poll failed: ${error.stackTraceToString()}")
                updateNotification("轮询失败，继续重试")
                ProbeStatus.write(this@BotLoopService, "读屏轮询失败：${error.message}")
            } finally {
                pollInFlight.set(false)
                lastPollActivityAt = SystemClock.elapsedRealtime()
                if (running) {
                    if (wakeAfterPoll.getAndSet(false)) {
                        log("poll wake drain")
                        val wakeDelay = if (pollingCoordinator.isAuditInFlight()) {
                            AUDIT_WAKE_RETRY_MS
                        } else {
                            0L
                        }
                        scheduleWakePoll(wakeDelay)
                    } else {
                        if (pollingCoordinator.isAuditInFlight()) {
                            nextDelay = maxOf(nextDelay, POLL_INTERVAL_MS)
                        }
                        val dueDelay = pollingCoordinator.nextBurstDueAt()
                            ?.minus(SystemClock.elapsedRealtime())
                        if (dueDelay != null && dueDelay < nextDelay) {
                            nextDelay = dueDelay.coerceAtLeast(0L)
                        }
                        schedulePoll(this, nextDelay)
                    }
                }
            }
        }
    }

    private val pollWatchdog = object : Runnable {
        override fun run() {
            if (!running) {
                return
            }
            val sinceLastPollMs = SystemClock.elapsedRealtime() - lastPollActivityAt
            if (PollLivenessPolicy.shouldRestartWorker(
                    running = running,
                    pollInFlight = pollInFlight.get(),
                    sinceLastPollMs = sinceLastPollMs,
                )
            ) {
                restartStalledPollWorker(sinceLastPollMs)
            }
            mainHandler.postDelayed(this, POLL_WATCHDOG_INTERVAL_MS)
        }
    }

    private val wakePollRunnable: Runnable = Runnable {
        wakeScheduled.set(false)
        if (!running) {
            return@Runnable
        }
        if (deliveryInFlight.get()) {
            wakeAfterSend.set(true)
            log("poll wake deferred for send")
            return@Runnable
        }
        wakeAfterSend.set(false)
        if (pollingCoordinator.isAuditInFlight()) {
            if (wakeScheduled.compareAndSet(false, true)) {
                worker.postDelayed(wakePollRunnable, AUDIT_WAKE_RETRY_MS)
            }
            return@Runnable
        }
        if (pollInFlight.get()) {
            wakeAfterPoll.set(true)
            log("poll wake deferred")
            return@Runnable
        }
        worker.removeCallbacks(pollRunnable)
        log("poll wake event")
        schedulePoll(pollRunnable, 0L)
    }

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
        stateStore = AgentStateStore(this)
        val persisted = stateStore.load()
        val restored = AgentRunPolicy.afterProcessRestart(persisted)
        runState = restored
        if (restored != persisted) {
            stateStore.save(restored)
        }
        workerThread = HandlerThread("wxbot-loop").apply { start() }
        worker = Handler(workerThread.looper)
        actionThread = HandlerThread("wxbot-actions").apply { start() }
        actionHandler = Handler(actionThread.looper)
        createNotificationChannel()
        initializeCoordinators()
        if (TransportSettings.mode(this) == TransportMode.ACCESSIBILITY) {
            ProbeAccessibilityService.setContentChangeListener(::onAccessibilityWake)
        }
        RuntimeDataMaintenance.runPolicyOnce(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseLoop()
            ACTION_RESUME -> resumeLoop()
            ACTION_STOP -> stopLoop("读屏监控已停止。")
            ACTION_EMERGENCY_STOP -> emergencyStop()
            else -> startLoop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacks(pollWatchdog)
        ProbeAccessibilityService.setContentChangeListener(null)
        updateRunState(
            AgentRunPolicy.next(runState, AgentRunAction.SERVICE_DESTROYED),
        )
        serviceAlive = false
        worker.removeCallbacks(pollRunnable)
        worker.removeCallbacks(wakePollRunnable)
        wakeScheduled.set(false)
        wakeAfterPoll.set(false)
        deliveryInFlight.set(false)
        wakeAfterSend.set(false)
        if (::pollingCoordinator.isInitialized) {
            pollingCoordinator.close()
        }
        workerThread.quitSafely()
        actionHandler.removeCallbacksAndMessages(null)
        actionThread.quitSafely()
        super.onDestroy()
    }

    private fun initializeCoordinators() {
        val status: (String) -> Unit = { ProbeStatus.write(this, it) }
        memoryCoordinator = MemoryCoordinator(
            context = this,
            onLog = ::log,
            onStatus = status,
        )
        sendQueueCoordinator = SendQueueCoordinator(
            context = this,
            memory = memoryCoordinator,
            deviceActions = deviceActions,
            outgoingConfirmation = outgoingConfirmation,
            canAutoSend = { AgentRunPolicy.canAutoSend(runState, running) },
            isSendAuthorized = ::isSendAuthorized,
            onDeliveredReply = { conversationId, text, nowMillis ->
                replyCoordinator.recordDeliveredReply(conversationId, text, nowMillis)
            },
            onStatus = status,
            onNotification = ::updateNotification,
            onLog = ::log,
            onSendStateChanged = { sending ->
                deliveryInFlight.set(sending)
                if (!sending) {
                    flushDeferredSendWake()
                }
            },
        )
        replyCoordinator = ReplyGenerationCoordinator(
            context = this,
            sendQueue = sendQueueCoordinator,
            canAutoSend = { AgentRunPolicy.canAutoSend(runState, running) },
            onStatus = status,
            onNotification = ::updateNotification,
            onLog = ::log,
        )
        pollingCoordinator = PollingCoordinator(
            context = this,
            transport = transport,
            deviceActions = deviceActions,
            canPoll = { AgentRunPolicy.canPoll(runState, running) },
            onStatus = status,
            onNotification = ::updateNotification,
            onLog = ::log,
        )
    }

    private fun startLoop() {
        if (runState == AgentRunState.RUNNING) {
            return
        }
        if (runState == AgentRunState.PAUSED) {
            resumeLoop()
            return
        }
        ensureForeground("正在建立读屏基线")
        if (!transport.isReady()) {
            ProbeStatus.write(this, transport.unavailableMessage())
            updateRunState(AgentRunState.OFF)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (transport.mode == TransportMode.ACCESSIBILITY) {
            ImeStrategyFactory.routeCStartupMessage(this)?.let { message ->
                ProbeStatus.write(this, message)
                updateRunState(AgentRunState.OFF)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        }
        groupTitleWarningIssued = false
        boundSendChat = null
        running = true
        startupEvaluationPending = true
        updateRunState(AgentRunPolicy.next(runState, AgentRunAction.START))
        replyCoordinator.beginRun()
        ProbeStatus.write(this, "读屏监控启动，正在建立基线。")
        log("loop start")
        lastPollActivityAt = SystemClock.elapsedRealtime()
        lastWakeRequestedAt = 0L
        deliveryInFlight.set(false)
        wakeAfterSend.set(false)
        schedulePoll(pollRunnable, FIRST_POLL_DELAY_MS)
        mainHandler.removeCallbacks(pollWatchdog)
        mainHandler.postDelayed(pollWatchdog, POLL_WATCHDOG_INTERVAL_MS)
    }

    private fun pauseLoop() {
        if (runState != AgentRunState.RUNNING) {
            return
        }
        running = false
        updateRunState(AgentRunPolicy.next(runState, AgentRunAction.PAUSE))
        worker.removeCallbacks(pollRunnable)
        worker.removeCallbacks(wakePollRunnable)
        wakeScheduled.set(false)
        wakeAfterPoll.set(false)
        wakeAfterSend.set(false)
        updateNotification("代理已暂停，当前会话不会总结")
        ProbeStatus.write(this, "代理已暂停。当前会话保留，停止代理时才会生成总结。")
        mainHandler.removeCallbacks(pollWatchdog)
        log("loop pause")
    }

    private fun resumeLoop() {
        if (runState != AgentRunState.PAUSED) {
            return
        }
        if (!transport.isReady()) {
            ProbeStatus.write(this, transport.unavailableMessage())
            return
        }
        if (transport.mode == TransportMode.ACCESSIBILITY) {
            ImeStrategyFactory.routeCStartupMessage(this)?.let { message ->
                ProbeStatus.write(this, message)
                return
            }
        }
        ensureForeground("正在恢复读屏")
        pollingCoordinator.resetForStartupEvaluation()
        groupTitleWarningIssued = false
        boundSendChat = null
        running = true
        startupEvaluationPending = true
        updateRunState(AgentRunPolicy.next(runState, AgentRunAction.RESUME))
        replyCoordinator.beginRun()
        updateNotification("代理已继续")
        ProbeStatus.write(this, "代理已继续，正在恢复读屏。")
        log("loop resume")
        lastPollActivityAt = SystemClock.elapsedRealtime()
        lastWakeRequestedAt = 0L
        wakeAfterSend.set(false)
        schedulePoll(pollRunnable, FIRST_POLL_DELAY_MS)
        mainHandler.removeCallbacks(pollWatchdog)
        mainHandler.postDelayed(pollWatchdog, POLL_WATCHDOG_INTERVAL_MS)
    }

    private fun schedulePoll(runnable: Runnable, delayMs: Long) {
        synchronized(schedulerLock) {
            if (!worker.postDelayed(runnable, delayMs)) {
                log("poll schedule rejected delayMs=$delayMs")
            }
        }
    }

    private fun onAccessibilityWake(eventType: Int) {
        if (!running) {
            return
        }
        if (AccessibilityWakePolicy.shouldDeferDuringSend(deliveryInFlight.get())) {
            wakeAfterSend.set(true)
            return
        }
        if (pollInFlight.get()) {
            wakeAfterPoll.set(true)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (AccessibilityWakePolicy.shouldThrottle(
                nowMillis = now,
                lastWakeMillis = lastWakeRequestedAt,
                minimumIntervalMillis = CONTENT_WAKE_MIN_INTERVAL_MS,
            )
        ) {
            return
        }
        if (!wakeScheduled.compareAndSet(false, true)) {
            return
        }
        lastWakeRequestedAt = now
        if (!worker.postDelayed(wakePollRunnable, CONTENT_WAKE_DEBOUNCE_MS)) {
            wakeScheduled.set(false)
            log("poll wake schedule rejected eventType=$eventType")
        }
    }

    private fun scheduleWakePoll(delayMs: Long) {
        if (!running || !wakeScheduled.compareAndSet(false, true)) {
            return
        }
        if (!worker.postDelayed(wakePollRunnable, delayMs)) {
            wakeScheduled.set(false)
            wakeAfterPoll.set(true)
            log("poll wake schedule rejected delayMs=$delayMs")
        }
    }

    private fun flushDeferredSendWake() {
        if (!running || !wakeAfterSend.getAndSet(false)) {
            return
        }
        if (!wakeScheduled.compareAndSet(false, true)) {
            wakeAfterSend.set(true)
            return
        }
        if (!worker.postDelayed(wakePollRunnable, SEND_WAKE_SETTLE_MS)) {
            wakeScheduled.set(false)
            wakeAfterSend.set(true)
            log("poll wake after send rejected")
        }
    }

    private fun restartStalledPollWorker(sinceLastPollMs: Long) {
        log("poll watchdog restart sinceLastPollMs=$sinceLastPollMs")
        synchronized(schedulerLock) {
            worker.removeCallbacks(pollRunnable)
            worker.removeCallbacks(wakePollRunnable)
            wakeScheduled.set(false)
            wakeAfterPoll.set(false)
            workerThread.quitSafely()
            workerThread = HandlerThread("wxbot-loop").apply { start() }
            worker = Handler(workerThread.looper)
            lastPollActivityAt = SystemClock.elapsedRealtime()
            if (!worker.postDelayed(pollRunnable, 0L)) {
                log("poll watchdog schedule rejected")
            }
        }
    }

    private fun drainDueBurstBeforePoll() {
        if (!AgentRunPolicy.canPoll(runState, running)) {
            return
        }
        val snapshot = pollingCoordinator.cachedSnapshot() ?: return
        val completed = pollingCoordinator.drainDueBurst(
            SystemClock.elapsedRealtime(),
        ) ?: return
        log("burst completed before OCR items=${completed.items.size}")
        dispatchCompletedBurst(snapshot, completed)
    }

    private fun pollOnce(): Boolean {
        if (!AgentRunPolicy.canPoll(runState, running)) {
            return false
        }
        val snapshot = pollingCoordinator.capture(
            forceOcr = startupEvaluationPending,
        ) ?: return false
        outgoingConfirmation.observe(
            chatFingerprint = snapshot.chat.fingerprint,
            items = snapshot.items,
            nowMillis = System.currentTimeMillis(),
        ).forEach { event ->
            log(
                "outgoing shadow token=${event.token} " +
                    "result=${event.outcome.name.lowercase()} " +
                    "latencyMs=${event.latencyMillis}",
            )
        }
        if (!ChatIdentityPolicy.isUsableTitle(snapshot.chat.title)) {
            pauseForSendAuthorization(
                SendAuthorizationIssue.UNRECOGNIZED_CHAT,
                snapshot.chat.fingerprint,
            )
            return false
        }
        val reading = pollingCoordinator.observe(snapshot)
        if (!reading.isPrimed) {
            return false
        }
        if (!ensureSendAuthorization(snapshot)) {
            return false
        }
        memoryCoordinator.open(snapshot.chat, System.currentTimeMillis())
        if (!reading.wasPrimed) {
            val incoming = snapshot.items.count {
                it.kind == com.local.wxbotprobe.chat.BubbleKind.IN
            }
            ProbeStatus.write(
                this,
                "读屏基线完成：${snapshot.chat.title.ifBlank { "未识别聊天" }}，" +
                    "${snapshot.items.size} 个气泡，$incoming 个对方。",
            )
            updateNotification("基线完成，等待新消息")
            log(
                "baseline chatFp=" +
                    "${java.lang.Long.toHexString(snapshot.chat.fingerprint)} " +
                    "items=${snapshot.items.size} incoming=$incoming",
            )
            if (!groupTitleWarningIssued) {
                groupTitleWarningIssued = true
                if (GroupTitleWarningPolicy.isLikelyGroupTitle(snapshot.chat.title)) {
                    ProbeStatus.write(
                        this,
                        "当前聊天标题以人数结尾，疑似群聊。建议切换到单聊；代理不会强制停止。",
                    )
                    updateNotification("疑似群聊，建议切换聊天")
                    log(
                        "group title warning issued titleLength=${snapshot.chat.title.length}",
                    )
                }
            }
        }
        maybeEvaluateStartup(snapshot)
        scheduleCoordination(snapshot)
        if (reading.recheckSoon) {
            pollingCoordinator.auditSuspiciousFrame(snapshot)
        }
        if (!AgentRunPolicy.canPoll(runState, running)) {
            return false
        }
        reading.completed?.let { completed ->
            dispatchCompletedBurst(snapshot, completed)
        }
        return reading.recheckSoon
    }

    private fun ensureSendAuthorization(snapshot: ScreenSnapshot): Boolean {
        val current = boundSendChat
        if (current == null) {
            val result = SendAuthorizationPolicy.bind(
                chatFingerprint = snapshot.chat.fingerprint,
                chatTitle = snapshot.chat.title,
                isGroup = snapshot.isGroup,
            )
            val binding = result.binding
                ?: run {
                    pauseForSendAuthorization(
                        result.issue ?: SendAuthorizationIssue.UNRECOGNIZED_CHAT,
                        snapshot.chat.fingerprint,
                    )
                    return false
                }
            boundSendChat = binding
            ProbeStatus.write(this, "本次代理已绑定当前私聊，启动即授权自动发送。")
            log(
                "send authorization bound chatFp=" +
                    java.lang.Long.toHexString(binding.chatFingerprint) +
                    " titleLength=${binding.normalizedTitle.length}",
            )
            return true
        }
        val issue = SendAuthorizationPolicy.check(
            binding = current,
            chatFingerprint = snapshot.chat.fingerprint,
            chatTitle = snapshot.chat.title,
        ) ?: return true
        pauseForSendAuthorization(issue, snapshot.chat.fingerprint)
        return false
    }

    private fun pauseForSendAuthorization(
        issue: SendAuthorizationIssue,
        chatFingerprint: Long,
    ) {
        val message = SendAuthorizationPolicy.message(issue)
        log(
            "send authorization paused issue=${issue.name.lowercase()} " +
                "chatFp=${java.lang.Long.toHexString(chatFingerprint)}",
        )
        if (runState == AgentRunState.RUNNING) {
            pauseLoop()
            ProbeStatus.write(this, "$message 当前会话与发送队列已保留。")
            updateNotification("当前聊天未获发送授权")
        }
    }

    private fun isSendAuthorized(
        chatFingerprint: Long,
        chatTitle: String,
    ): Boolean {
        val binding = boundSendChat ?: return false
        return SendAuthorizationPolicy.check(
            binding = binding,
            chatFingerprint = chatFingerprint,
            chatTitle = chatTitle,
        ) == null
    }

    private fun maybeEvaluateStartup(snapshot: ScreenSnapshot) {
        if (!startupEvaluationPending) {
            return
        }
        startupEvaluationPending = false
        if (!memoryCoordinator.isReady) {
            log("startup context skipped memory not ready")
            ProbeStatus.write(this, "首屏上下文未读取：聊天记忆尚未建立。")
            return
        }
        val contactId = memoryCoordinator.chatId
        if (contactId.isNullOrBlank()) {
            log("startup context skipped contact missing")
            return
        }
        val startup = StartupConversationContextBuilder.build(snapshot, contactId)
        if (startup == null) {
            log("startup context skipped no readable messages")
            return
        }
        if (!startup.shouldRespond) {
            log(
                "startup context skipped last kind=${startup.lastMessageKind.name.lowercase()} " +
                    "textLength=${startup.lastMessageText.length}",
            )
            ProbeStatus.write(
                this,
                if (startup.lastMessageKind == BubbleKind.OUT) {
                    "首屏最下方是自己发送的消息，不主动回复。"
                } else {
                    "首屏最下方消息无法确认来源，不主动回复。"
                },
            )
            return
        }
        runCatching {
            replyCoordinator.prepareStartupConversation(
                startup = startup,
                chatTitle = snapshot.chat.title,
                chatFingerprint = snapshot.chat.fingerprint,
                conversationKey = snapshot.chat.conversationKey,
                onNewConversation = {
                    memoryCoordinator.recordIncoming(
                        text = startup.lastMessageText,
                        nowMillis = System.currentTimeMillis(),
                        clientMessageId = "startup-user-${startup.snapshotId}",
                    )
                },
            )
        }.onFailure {
            startupEvaluationPending = true
            log("startup context preparation failed: ${it.stackTraceToString()}")
            ProbeStatus.write(this, "首屏上下文处理失败：${it.message}")
        }
    }

    private fun dispatchCompletedBurst(
        snapshot: ScreenSnapshot,
        completed: com.local.wxbotprobe.chat.CompletedBurst,
    ) {
        if (
            BuildConfig.DEBUG &&
            getSharedPreferences(
                DEBUG_BASELINE_PREFS,
                MODE_PRIVATE,
            ).getBoolean(DEBUG_BASELINE_ONLY, false)
        ) {
            log("debug baseline-only suppressed burst items=${completed.items.size}")
            ProbeStatus.write(this, "调试基线模式已抑制 burst。")
            return
        }
        completed.items.forEach {
            memoryCoordinator.recordIncoming(it.text, System.currentTimeMillis())
        }
        replyCoordinator.prepareBurst(
            burst = completed,
            chatTitle = snapshot.chat.title,
            chatFingerprint = snapshot.chat.fingerprint,
            conversationKey = snapshot.chat.conversationKey,
        )
        val conversationId =
            memoryCoordinator.chatId ?: snapshot.chat.conversationKey
        val replyContext = memoryCoordinator.replyContext
        val persona = memoryCoordinator.currentPersona
        val personaId = memoryCoordinator.currentPersonaId.orEmpty()
        actionHandler.post {
            replyCoordinator.drainRetries(
                chatTitle = snapshot.chat.title,
                chatFingerprint = snapshot.chat.fingerprint,
                conversationKey = snapshot.chat.conversationKey,
                conversationId = conversationId,
                replyContext = replyContext,
                contactId = conversationId,
                personaId = personaId,
                persona = persona,
            )
        }
    }

    private fun scheduleCoordination(snapshot: ScreenSnapshot) {
        if (!coordinationScheduled.compareAndSet(false, true)) {
            return
        }
        val chatTitle = snapshot.chat.title
        val chatFingerprint = snapshot.chat.fingerprint
        val conversationKey = snapshot.chat.conversationKey
        val conversationId = memoryCoordinator.chatId ?: conversationKey
        val replyContext = memoryCoordinator.replyContext
        val persona = memoryCoordinator.currentPersona
        val personaId = memoryCoordinator.currentPersonaId.orEmpty()
        val inputFieldHint = snapshot.inputField
        actionHandler.post {
            try {
                if (!AgentRunPolicy.canPoll(runState, running)) {
                    return@post
                }
                sendQueueCoordinator.drain(
                    chatFingerprint = chatFingerprint,
                    chatTitle = chatTitle,
                    inputFieldHint = inputFieldHint,
                )
                if (!AgentRunPolicy.canPoll(runState, running)) {
                    return@post
                }
                replyCoordinator.drainRetries(
                    chatTitle = chatTitle,
                    chatFingerprint = chatFingerprint,
                    conversationKey = conversationKey,
                    conversationId = conversationId,
                    replyContext = replyContext,
                    contactId = conversationId,
                    personaId = personaId,
                    persona = persona,
                )
            } finally {
                coordinationScheduled.set(false)
            }
        }
    }

    private fun stopLoop(message: String) {
        if (runState == AgentRunState.STOPPING) {
            return
        }
        running = false
        mainHandler.removeCallbacks(pollWatchdog)
        worker.removeCallbacks(pollRunnable)
        worker.removeCallbacks(wakePollRunnable)
        wakeScheduled.set(false)
        wakeAfterPoll.set(false)
        deliveryInFlight.set(false)
        wakeAfterSend.set(false)
        updateRunState(AgentRunState.OFF)
        runCatching {
            memoryCoordinator.detachForAsyncSummary(System.currentTimeMillis())
        }.onFailure {
            log("memory detach failed: ${it.stackTraceToString()}")
        }
        runCatching {
            replyCoordinator.cancelPendingGeneration()
        }.onFailure {
            log("reply cancel failed: ${it.stackTraceToString()}")
        }
        ProbeStatus.write(this, "$message 记忆将在后台整理。")
        mainHandler.removeCallbacks(pollWatchdog)
        log("loop stop")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun emergencyStop() {
        running = false
        worker.removeCallbacks(pollRunnable)
        worker.removeCallbacks(wakePollRunnable)
        wakeScheduled.set(false)
        wakeAfterPoll.set(false)
        deliveryInFlight.set(false)
        wakeAfterSend.set(false)
        runCatching {
            replyCoordinator.cancelPendingGeneration()
        }.onFailure {
            log("reply cancel failed: ${it.stackTraceToString()}")
        }
        ProbeAccessibilityService.setContentChangeListener(null)
        updateRunState(AgentRunState.OFF)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        log("loop emergency stop")
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "微信机器人读屏",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, BotLoopService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseAction = if (runState == AgentRunState.PAUSED) {
            PendingIntent.getService(
                this,
                12,
                Intent(this, BotLoopService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getService(
                this,
                13,
                Intent(this, BotLoopService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("微信机器人读屏")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                if (runState == AgentRunState.PAUSED) {
                    android.R.drawable.ic_media_play
                } else {
                    android.R.drawable.ic_media_pause
                },
                if (runState == AgentRunState.PAUSED) "继续" else "暂停",
                pauseAction,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止并总结",
                stopIntent,
            )
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun ensureForeground(text: String) {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateRunState(next: AgentRunState) {
        runState = next
        stateStore.save(next)
    }

    @Synchronized
    private fun log(message: String) {
        try {
            val directory = File(getExternalFilesDir(null) ?: filesDir, "logs")
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val file = File(directory, "bot-loop.log")
            if (file.length() > MAX_LOG_BYTES) {
                val old = File(directory, "bot-loop.log.1")
                if (old.exists()) {
                    old.delete()
                }
                file.renameTo(old)
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
                .format(Date())
            file.appendText("$timestamp $message\n")
        } catch (_: Throwable) {
            // Logging must never stop the bot loop.
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.local.wxbotprobe.PAUSE_BOT_LOOP"
        const val ACTION_RESUME = "com.local.wxbotprobe.RESUME_BOT_LOOP"
        const val ACTION_STOP = "com.local.wxbotprobe.STOP_BOT_LOOP"
        const val ACTION_EMERGENCY_STOP = "com.local.wxbotprobe.EMERGENCY_STOP_BOT_LOOP"

        @Volatile
        var runState = AgentRunState.OFF
            private set

        @Volatile
        private var serviceAlive = false

        val isRunning: Boolean
            get() = runState == AgentRunState.RUNNING

        val isPaused: Boolean
            get() = runState == AgentRunState.PAUSED

        fun restoreAfterProcessRestart(context: android.content.Context) {
            if (serviceAlive) {
                return
            }
            val store = AgentStateStore(context)
            val persisted = store.load()
            val restored = AgentRunPolicy.afterProcessRestart(persisted)
            runState = restored
            if (restored != persisted) {
                store.save(restored)
            }
        }

        fun requestEmergencyShutdown(context: android.content.Context) {
            runState = AgentRunState.OFF
            AgentStateStore(context).save(AgentRunState.OFF)
            if (serviceAlive) {
                runCatching {
                    context.startService(
                        Intent(context, BotLoopService::class.java).setAction(
                            ACTION_EMERGENCY_STOP,
                        ),
                    )
                }
            }
        }

        internal const val DEBUG_BASELINE_PREFS = "debug_baseline_only"
        internal const val DEBUG_BASELINE_ONLY = "enabled"
        private const val CHANNEL_ID = "wxbot_loop"
        private const val NOTIFICATION_ID = 200
        private const val FIRST_POLL_DELAY_MS = 500L
        private const val POLL_INTERVAL_MS = 3_000L
        private const val OCR_RECHECK_DELAY_MS = 900L
        private const val POLL_WATCHDOG_INTERVAL_MS = 5_000L
        private const val CONTENT_WAKE_DEBOUNCE_MS = 200L
        private const val CONTENT_WAKE_MIN_INTERVAL_MS = 1_500L
        private const val AUDIT_WAKE_RETRY_MS = 200L
        private const val SEND_WAKE_SETTLE_MS = 250L
        private const val MAX_LOG_BYTES = 1_000_000L
    }
}
