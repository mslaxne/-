package com.local.wxbotprobe

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs

class AgentBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var bubble: BubbleFrameLayout
    private lateinit var circleIcon: ImageView
    private lateinit var panel: LinearLayout
    private lateinit var startPauseButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var collapseButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubbleBackground: GradientDrawable
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private var expanded = false
    private var side = "right"
    private var widthAnimator: ValueAnimator? = null
    private var renderedState: AgentRunState? = null
    private var pressedButton: ImageButton? = null
    private val visibilitySink: (Boolean) -> Unit = { visible ->
        if (::bubble.isInitialized && attached) {
            bubble.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private val stateRefresh = object : Runnable {
        override fun run() {
            updateVisualState()
            handler.postDelayed(this, 400L)
        }
    }

    private val autoCollapse = Runnable {
        if (expanded) {
            collapseBubble()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (TransportSettings.mode(this) == TransportMode.SHIZUKU) {
            RouteBPlatform.initialize(this)
        }
        BotLoopService.restoreAfterProcessRestart(this)
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hideBubbleAndStop()
                return START_NOT_STICKY
            }
            else -> {
                if (intent == null && !BubbleSettings.enabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                BubbleSettings.setEnabled(this, true)
                showBubble()
                handler.removeCallbacks(stateRefresh)
                handler.post(stateRefresh)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!attached) {
            return
        }
        clampVerticalPosition()
        animateWindowX(params.width, side, 180L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(stateRefresh)
        handler.removeCallbacks(autoCollapse)
        widthAnimator?.cancel()
        removeBubble()
        super.onDestroy()
    }

    private fun showBubble() {
        if (attached) {
            updateVisualState()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要开启显示在其他应用上层的权限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        side = BubbleSettings.side(this)
        bubbleBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = BUBBLE_SIZE_DP.dp() / 2f
            setColor(Color.argb(244, 23, 26, 29))
            setStroke(2.dp(), BUBBLE_OFF)
        }
        bubble = BubbleFrameLayout(this).apply {
            contentDescription = "代理控制气泡"
            isClickable = true
            background = bubbleBackground
            elevation = 10.dp().toFloat()
            outlineSpotShadowColor = Color.argb(120, 0, 0, 0)
            outlineAmbientShadowColor = Color.argb(80, 0, 0, 0)
            setOnClickListener {
                if (!expanded) {
                    expandBubble()
                }
            }
        }
        circleIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_agent_dots)
            setColorFilter(Color.rgb(248, 250, 250))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        bubble.addView(
            circleIcon,
            FrameLayout.LayoutParams(26.dp(), 26.dp(), Gravity.CENTER),
        )

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            alpha = 0f
            visibility = View.GONE
            setPadding(PANEL_INSET_DP.dp(), 0, PANEL_INSET_DP.dp(), 0)
        }
        startPauseButton = panelButton("开始或暂停代理")
        stopButton = panelButton("停止并整理记忆").apply {
            setImageResource(R.drawable.ic_bubble_stop)
        }
        collapseButton = panelButton("收起气泡")
        closeButton = panelButton("关闭气泡并暂停代理").apply {
            setImageResource(R.drawable.ic_bubble_close)
        }
        bubble.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        rebuildPanelOrder()

        val metrics = windowManager.currentWindowMetrics.bounds
        val savedY = BubbleSettings.y(this)
        val startY = if (savedY >= 0) savedY else metrics.height() / 2
        params = WindowManager.LayoutParams(
            BUBBLE_SIZE_DP.dp(),
            BUBBLE_SIZE_DP.dp(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX(BUBBLE_SIZE_DP.dp(), side, metrics.width())
            y = startY.coerceIn(MARGIN_DP.dp(), maxY(BUBBLE_SIZE_DP.dp()))
        }
        installTouchHandler()
        windowManager.addView(bubble, params)
        attached = true
        BubbleVisibilityController.register(visibilitySink)
        updateVisualState()
    }

    private fun panelButton(description: String): ImageButton {
        return ImageButton(this).apply {
            contentDescription = description
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            isClickable = false
            isFocusable = false
        }
    }

    private fun rebuildPanelOrder() {
        if (!::panel.isInitialized) {
            return
        }
        panel.removeAllViews()
        BubblePanelPolicy.visualOrder(side).forEachIndexed { index, action ->
            if (index > 0) {
                panel.addView(panelDivider(), panelDividerParams())
            }
            panel.addView(buttonFor(action), panelButtonParams())
        }
        collapseButton.setImageResource(
            if (side == "left") {
                R.drawable.ic_bubble_collapse_left
            } else {
                R.drawable.ic_bubble_collapse_right
            },
        )
    }

    private fun buttonFor(action: BubblePanelAction): ImageButton {
        return when (action) {
            BubblePanelAction.START_PAUSE -> startPauseButton
            BubblePanelAction.STOP -> stopButton
            BubblePanelAction.COLLAPSE -> collapseButton
            BubblePanelAction.CLOSE -> closeButton
        }
    }

    private fun panelButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(PANEL_BUTTON_DP.dp(), PANEL_BUTTON_DP.dp())
    }

    private fun panelDividerParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(1.dp(), 28.dp())
    }

    private fun panelDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.argb(44, 255, 255, 255))
        }
    }

    private fun installTouchHandler() {
        val touchSlop = 8.dp()
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downAt = 0L

        bubble.setOnTouchListener { view, event ->
            if (expanded) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        downAt = event.eventTime
                        handler.removeCallbacks(autoCollapse)
                        showPressedState(actionAt(event.rawX))
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (!moved && BubbleDragPolicy.moved(dx, dy, touchSlop)) {
                            moved = true
                            clearPressedState()
                        }
                        if (moved) {
                            val metrics = windowManager.currentWindowMetrics.bounds
                            params.x = BubbleDragPolicy.coordinate(
                                start = startX,
                                delta = dx,
                                minimum = 0,
                                maximum = metrics.width() - params.width,
                            )
                            params.y = BubbleDragPolicy.coordinate(
                                start = startY,
                                delta = dy,
                                minimum = MARGIN_DP.dp(),
                                maximum = maxY(params.height),
                            )
                            windowManager.updateViewLayout(bubble, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        clearPressedState()
                        if (moved) {
                            snapToNearestEdge()
                            scheduleAutoCollapse()
                        } else {
                            handleExpandedTap(event.rawX, event.eventTime - downAt)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        clearPressedState()
                        scheduleAutoCollapse()
                        true
                    }
                    else -> true
                }
            } else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70L).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            moved = true
                        }
                        val metrics = windowManager.currentWindowMetrics.bounds
                        params.x = (startX + dx).coerceIn(0, metrics.width() - params.width)
                        params.y = (startY + dy).coerceIn(MARGIN_DP.dp(), maxY(params.height))
                        windowManager.updateViewLayout(bubble, params)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(70L).start()
                        if (moved) {
                            snapToNearestEdge()
                        } else {
                            view.performClick()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun handleExpandedTap(rawX: Float, duration: Long) {
        when (actionAt(rawX)) {
            BubblePanelAction.START_PAUSE -> handleStartPause()
            BubblePanelAction.STOP -> {
                if (duration >= STOP_LONG_PRESS_MS) {
                    performStop()
                } else {
                    Toast.makeText(
                        this,
                        "长按停止按钮以整理本次聊天记忆",
                        Toast.LENGTH_SHORT,
                    ).show()
                    scheduleAutoCollapse()
                }
            }
            BubblePanelAction.COLLAPSE -> collapseBubble()
            BubblePanelAction.CLOSE -> closeBubbleAndPause()
        }
    }

    private fun actionAt(rawX: Float): BubblePanelAction {
        val contentLeft = params.x + PANEL_INSET_DP.dp()
        val contentWidth = params.width - (PANEL_INSET_DP * 2).dp()
        val zone = (
            ((rawX - contentLeft) / (contentWidth / 4f)).toInt()
            )
        return BubblePanelPolicy.actionAt(side, zone)
    }

    private fun showPressedState(action: BubblePanelAction) {
        clearPressedState()
        pressedButton = buttonFor(action).also {
            it.animate().scaleX(0.88f).scaleY(0.88f).alpha(0.72f).setDuration(80L).start()
        }
    }

    private fun clearPressedState() {
        pressedButton?.animate()?.cancel()
        pressedButton?.scaleX = 1f
        pressedButton?.scaleY = 1f
        pressedButton?.alpha = 1f
        pressedButton = null
        updateVisualState()
    }

    private fun expandBubble() {
        if (expanded || !attached) {
            return
        }
        expanded = true
        handler.removeCallbacks(autoCollapse)
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        circleIcon.alpha = 1f
        rebuildPanelOrder()
        animateWindowWidth(
            from = BUBBLE_SIZE_DP.dp(),
            to = PANEL_WIDTH_DP.dp(),
            expanding = true,
            duration = 220L,
        )
        scheduleAutoCollapse()
    }

    private fun collapseBubble() {
        if (!expanded || !attached) {
            return
        }
        expanded = false
        handler.removeCallbacks(autoCollapse)
        animateWindowWidth(
            from = params.width,
            to = BUBBLE_SIZE_DP.dp(),
            expanding = false,
            duration = 180L,
        )
    }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(autoCollapse)
        handler.postDelayed(autoCollapse, AUTO_COLLAPSE_MS)
    }

    private fun animateWindowWidth(
        from: Int,
        to: Int,
        expanding: Boolean,
        duration: Long,
    ) {
        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            addUpdateListener { animator ->
                val width = animator.animatedValue as Int
                params.width = width
                params.x = initialX(width, side, windowManager.currentWindowMetrics.bounds.width())
                val progress = if (to == from) 1f else {
                    (width - from).toFloat() / (to - from).toFloat()
                }
                if (expanding) {
                    panel.alpha = progress.coerceIn(0f, 1f)
                    circleIcon.alpha = (1f - progress).coerceIn(0f, 1f)
                } else {
                    panel.alpha = (1f - progress).coerceIn(0f, 1f)
                    circleIcon.alpha = progress.coerceIn(0f, 1f)
                }
                windowManager.updateViewLayout(bubble, params)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!expanded) {
                        panel.visibility = View.GONE
                        panel.alpha = 0f
                        circleIcon.alpha = 1f
                    } else {
                        circleIcon.alpha = 0f
                    }
                }
            })
            start()
        }
    }

    private fun snapToNearestEdge() {
        val metrics = windowManager.currentWindowMetrics.bounds
        side = if (params.x + params.width / 2 < metrics.width() / 2) "left" else "right"
        BubbleSettings.setSide(this, side)
        BubbleSettings.setY(this, params.y)
        if (expanded) {
            rebuildPanelOrder()
        }
        animateWindowX(params.width, side, 180L)
    }

    private fun animateWindowX(width: Int, targetSide: String, duration: Long) {
        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofInt(
            params.x,
            initialX(width, targetSide, windowManager.currentWindowMetrics.bounds.width()),
        ).apply {
            this.duration = duration
            addUpdateListener {
                params.x = it.animatedValue as Int
                windowManager.updateViewLayout(bubble, params)
            }
            start()
        }
    }

    private fun clampVerticalPosition() {
        params.y = params.y.coerceIn(MARGIN_DP.dp(), maxY(params.height))
        BubbleSettings.setY(this, params.y)
    }

    private fun handleStartPause() {
        val result = AgentControl.toggle(this)
        val message = result.message
        android.util.Log.i("AgentBubble", "bubble action state=${BotLoopService.runState}: $message")
        ProbeStatus.write(this, message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        scheduleAutoCollapse()
        handler.postDelayed(::updateVisualState, 250L)
    }

    private fun performStop() {
        if (BotLoopService.runState == AgentRunState.OFF ||
            BotLoopService.runState == AgentRunState.STOPPING
        ) {
            return
        }
        bubble.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val message = AgentControl.stop(this).message
        android.util.Log.i("AgentBubble", "bubble stop: $message")
        ProbeStatus.write(this, message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        handler.postDelayed(::updateVisualState, 250L)
    }

    private fun closeBubbleAndPause() {
        bubble.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        val shouldPause = BubbleClosePolicy.shouldPause(BotLoopService.runState)
        if (shouldPause) {
            AgentControl.pause(this)
        }
        val message = if (shouldPause) {
            "代理已暂停，会话保留；气泡已关闭。"
        } else {
            "气泡已关闭。"
        }
        ProbeStatus.write(this, message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        hideBubbleAndStop()
    }

    private fun hideBubbleAndStop() {
        handler.removeCallbacks(stateRefresh)
        handler.removeCallbacks(autoCollapse)
        BubbleSettings.setEnabled(this, false)
        removeBubble()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateVisualState() {
        if (!attached) {
            return
        }
        val state = BotLoopService.runState
        if (state == renderedState) {
            return
        }
        val stateLabel: String
        when (state) {
            AgentRunState.RUNNING -> {
                stateLabel = "运行中"
                startPauseButton.setImageResource(R.drawable.ic_bubble_pause)
                startPauseButton.setColorFilter(Color.WHITE)
                startPauseButton.contentDescription = "暂停代理"
                startPauseButton.isEnabled = true
                stopButton.isEnabled = true
                setBubbleStroke(BUBBLE_RUNNING)
            }
            AgentRunState.PAUSED -> {
                stateLabel = "已暂停"
                startPauseButton.setImageResource(R.drawable.ic_bubble_play)
                startPauseButton.setColorFilter(BUBBLE_PAUSED)
                startPauseButton.contentDescription = "继续代理"
                startPauseButton.isEnabled = true
                stopButton.isEnabled = true
                setBubbleStroke(BUBBLE_PAUSED)
            }
            AgentRunState.STOPPING -> {
                stateLabel = "整理中"
                startPauseButton.setImageResource(R.drawable.ic_bubble_pause)
                startPauseButton.setColorFilter(BUBBLE_STOPPING)
                startPauseButton.contentDescription = "正在停止"
                startPauseButton.isEnabled = false
                stopButton.isEnabled = false
                setBubbleStroke(BUBBLE_STOPPING)
            }
            AgentRunState.OFF -> {
                stateLabel = "已停止"
                startPauseButton.setImageResource(R.drawable.ic_bubble_play)
                startPauseButton.setColorFilter(BUBBLE_START)
                startPauseButton.contentDescription = "开启代理"
                startPauseButton.isEnabled = true
                stopButton.isEnabled = false
                setBubbleStroke(BUBBLE_OFF)
            }
        }
        bubble.contentDescription = "代理气泡，$stateLabel"
        stopButton.setColorFilter(
            if (stopButton.isEnabled) BUBBLE_DANGER else BUBBLE_DISABLED,
        )
        collapseButton.setColorFilter(BUBBLE_SECONDARY)
        closeButton.setColorFilter(BUBBLE_DANGER)
        stopButton.alpha = if (stopButton.isEnabled) 1f else 0.35f
        startPauseButton.alpha = if (startPauseButton.isEnabled) 1f else 0.5f
        closeButton.alpha = 1f
        renderedState = state
    }

    private fun setBubbleStroke(color: Int) {
        bubbleBackground.setStroke(2.dp(), color)
        if (::bubble.isInitialized) {
            bubble.outlineSpotShadowColor = Color.argb(
                100,
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
            bubble.outlineAmbientShadowColor = Color.argb(
                50,
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
            bubble.invalidate()
        }
    }

    private fun initialX(width: Int, targetSide: String, screenWidth: Int): Int {
        return if (targetSide == "left") {
            MARGIN_DP.dp()
        } else {
            screenWidth - width - MARGIN_DP.dp()
        }
    }

    private fun maxY(height: Int): Int {
        return (
            windowManager.currentWindowMetrics.bounds.height() - height - MARGIN_DP.dp()
            ).coerceAtLeast(MARGIN_DP.dp())
    }

    private fun removeBubble() {
        if (attached) {
            BubbleVisibilityController.unregister(visibilitySink)
            runCatching { windowManager.removeView(bubble) }
            attached = false
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "代理悬浮气泡",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification {
        val hideIntent = PendingIntent.getService(
            this,
            31,
            Intent(this, AgentBubbleService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("代理悬浮气泡")
            .setContentText("点按展开代理控制，可拖动并吸附到屏幕两侧")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭气泡", hideIntent)
            .build()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private class BubbleFrameLayout(context: android.content.Context) : FrameLayout(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    companion object {
        const val ACTION_SHOW = "com.local.wxbotprobe.SHOW_AGENT_BUBBLE"
        const val ACTION_HIDE = "com.local.wxbotprobe.HIDE_AGENT_BUBBLE"
        private const val CHANNEL_ID = "agent_bubble"
        private const val NOTIFICATION_ID = 300
        private const val BUBBLE_SIZE_DP = 58
        private const val PANEL_WIDTH_DP = 243
        private const val PANEL_INSET_DP = 8
        private const val PANEL_BUTTON_DP = 56
        private const val MARGIN_DP = 10
        private const val AUTO_COLLAPSE_MS = 5_000L
        private const val STOP_LONG_PRESS_MS = 700L

        private val BUBBLE_RUNNING = Color.rgb(72, 224, 150)
        private val BUBBLE_PAUSED = Color.rgb(246, 194, 69)
        private val BUBBLE_STOPPING = Color.rgb(113, 166, 244)
        private val BUBBLE_OFF = Color.rgb(126, 136, 143)
        private val BUBBLE_START = Color.rgb(116, 225, 177)
        private val BUBBLE_DANGER = Color.rgb(255, 143, 143)
        private val BUBBLE_DISABLED = Color.rgb(104, 111, 116)
        private val BUBBLE_SECONDARY = Color.rgb(184, 194, 200)
    }
}
