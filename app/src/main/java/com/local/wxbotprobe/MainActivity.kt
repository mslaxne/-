package com.local.wxbotprobe

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.local.wxbotprobe.shutdown.AppShutdownCoordinator
import com.local.wxbotprobe.maintenance.RuntimeDataMaintenance
import com.local.wxbotprobe.memory.v2.MemoryV2MessageCleanupService
import com.local.wxbotprobe.memory.v2.MemoryV2BackgroundWorker
import com.local.wxbotprobe.send.DeliveryStore
import java.io.File

class MainActivity : Activity() {
    private lateinit var headerTitle: TextView
    private lateinit var headerBack: ImageButton
    private lateinit var headerExit: TextView
    private lateinit var headerSettings: ImageButton
    private lateinit var homePage: HomePageView
    private lateinit var memoryPage: MemoryPageView
    private lateinit var personaPage: PersonaPageView
    private lateinit var settingsPage: SettingsPageView
    private lateinit var diagnosticsPage: DiagnosticsPageView
    private lateinit var homeScreen: LinearLayout
    private lateinit var memoryScreen: LinearLayout
    private lateinit var personaScreen: LinearLayout
    private lateinit var settingsScreen: LinearLayout
    private lateinit var diagnosticsScreen: LinearLayout
    private lateinit var rootScroll: ScrollView

    private val screenHistory = ArrayDeque<String>()
    private var currentScreen = "home"
    private var pendingScreenshotAfterPermission = false
    private var shutdownInProgress = false
    private var skipPersonaPresetMigration = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skipPersonaPresetMigration = BuildConfig.DEBUG &&
            intent?.getBooleanExtra(
                "skip_persona_preset_migration",
                false,
            ) == true
        if (TransportSettings.mode(this) == TransportMode.SHIZUKU) {
            RouteBPlatform.initialize(this)
        }
        BotLoopService.restoreAfterProcessRestart(this)
        DeliveryStore(File(getExternalFilesDir(null) ?: filesDir, "outbox"))
            .recoverInterruptedSends()
        setContentView(buildContentView())
        RuntimeDataMaintenance.runPolicyOnce(this)
        val skipMemoryAutomation = BuildConfig.DEBUG &&
            intent?.getBooleanExtra("skip_memory_automation", false) == true
        if (!skipMemoryAutomation) {
            MemoryV2BackgroundWorker.kick(this)
            MemoryV2MessageCleanupService.kick(this)
        }

        registerReceiver(
            statusReceiver,
            IntentFilter(ProbeStatus.BROADCAST_ACTION),
            Context.RECEIVER_NOT_EXPORTED,
        )
        if (!skipPersonaPresetMigration) {
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        restoreBubbleIfNeeded()
        if (!skipPersonaPresetMigration) {
            refreshStatus()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (shutdownInProgress) {
            return
        }
        if (currentScreen != "home") {
            goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun buildContentView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setBackgroundColor(AppUi.background)
            setPadding(16.dp(), 16.dp(), 16.dp(), 28.dp())
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 56.dp()
        }
        val leftSlot = FrameLayout(this)
        headerBack = headerIconButton(R.drawable.ic_back, "返回") { goBack() }
        leftSlot.addView(
            headerBack,
            FrameLayout.LayoutParams(44.dp(), 44.dp(), Gravity.CENTER),
        )
        headerExit = AppUi.textView(
            this,
            "退出",
            15f,
            AppUi.danger,
            medium = true,
        ).apply {
            gravity = Gravity.CENTER
            contentDescription = "退出代理"
            background = AppUi.filledRipple(this@MainActivity, AppUi.dangerSoft, 12f)
            isClickable = true
            isFocusable = true
            setOnClickListener { confirmExit() }
        }
        leftSlot.addView(
            headerExit,
            FrameLayout.LayoutParams(60.dp(), 44.dp(), Gravity.CENTER),
        )
        topBar.addView(leftSlot, LinearLayout.LayoutParams(72.dp(), 56.dp()))
        headerTitle = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 23f
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.NORMAL,
            )
            setTextColor(AppUi.text)
            includeFontPadding = false
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        topBar.addView(
            headerTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        headerSettings = headerIconButton(
            R.drawable.ic_settings,
            "设置",
        ) { openScreen("settings") }
        val rightSlot = FrameLayout(this)
        rightSlot.addView(
            headerSettings,
            FrameLayout.LayoutParams(44.dp(), 44.dp(), Gravity.CENTER),
        )
        topBar.addView(rightSlot, LinearLayout.LayoutParams(72.dp(), 56.dp()))
        content.addView(
            topBar,
            fullWidth().apply {
                bottomMargin = 4.dp()
            },
        )

        homePage = HomePageView(
            context = this,
            onToggleAgent = ::toggleAgentFromHome,
            onStopAgent = ::stopBotLoop,
            onToggleBubble = { enabled ->
                if (enabled) showAgentBubble() else hideAgentBubble()
            },
            onOpenMemory = { openScreen("memory") },
            onOpenSettings = { openScreen("settings") },
        )
        memoryPage = MemoryPageView(this, ::refreshStatus)
        personaPage = PersonaPageView(
            this,
            ::refreshStatus,
            presetMigrationEnabled = !skipPersonaPresetMigration,
        )
        settingsPage = SettingsPageView(
            activity = this,
            onOpenPersona = { openScreen("persona") },
            onOpenDiagnostics = { openScreen("diagnostics") },
            onRefreshRequested = ::refreshStatus,
            onOpenWeChatSoon = ::switchToWeChatSoon,
            onRequestNotification = ::openNotificationPermissionFlow,
        )
        diagnosticsPage = DiagnosticsPageView(
            activity = this,
            onScreenshotRequested = ::onScreenshotClicked,
            onStartAgent = ::startBotLoop,
            onStopAgent = ::stopBotLoop,
            onConfigureApiKey = settingsPage::showModelConfigDialog,
            onOpenWeChatSoon = ::switchToWeChatSoon,
        )

        homeScreen = homePage.view
        memoryScreen = memoryPage.view
        personaScreen = personaPage.view
        settingsScreen = settingsPage.view
        diagnosticsScreen = diagnosticsPage.view
        val screens = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(homeScreen, fullWidth())
            addView(memoryScreen, fullWidth())
            addView(personaScreen, fullWidth())
            addView(settingsScreen, fullWidth())
            addView(diagnosticsScreen, fullWidth())
        }
        content.addView(screens, fullWidth())

        rootScroll = ScrollView(this).apply {
            setBackgroundColor(AppUi.background)
            isFillViewport = true
            addView(content)
        }
        showScreen("home")
        return rootScroll
    }

    private fun headerIconButton(
        resource: Int,
        description: String,
        onClick: () -> Unit,
    ): ImageButton {
        return ImageButton(this).apply {
            setImageResource(resource)
            contentDescription = description
            setColorFilter(AppUi.textSecondary)
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            background = AppUi.filledRipple(this@MainActivity, AppUi.surface, 12f)
            setOnClickListener { onClick() }
        }
    }

    private fun openScreen(screen: String) {
        if (screen == currentScreen) {
            return
        }
        screenHistory.addLast(currentScreen)
        showScreen(screen)
    }

    private fun goBack() {
        if (currentScreen == "persona") {
            personaPage.confirmLeave(::goBackNow)
            return
        }
        goBackNow()
    }

    private fun goBackNow() {
        showScreen(screenHistory.removeLastOrNull() ?: "home")
    }

    private fun showScreen(screen: String) {
        if (!::homeScreen.isInitialized) {
            return
        }
        currentScreen = screen
        homeScreen.visibility = if (screen == "home") View.VISIBLE else View.GONE
        memoryScreen.visibility = if (screen == "memory") View.VISIBLE else View.GONE
        personaScreen.visibility = if (screen == "persona") View.VISIBLE else View.GONE
        settingsScreen.visibility = if (screen == "settings") View.VISIBLE else View.GONE
        diagnosticsScreen.visibility = if (screen == "diagnostics") View.VISIBLE else View.GONE
        headerTitle.text = when (screen) {
            "memory" -> "聊天记忆"
            "persona" -> "人格设置"
            "settings" -> "设置"
            "diagnostics" -> "诊断工具"
            else -> getString(R.string.app_name)
        }
        headerBack.visibility = if (screen == "home") View.GONE else View.VISIBLE
        headerExit.visibility = if (screen == "home") View.VISIBLE else View.GONE
        headerExit.isEnabled = !shutdownInProgress
        headerSettings.visibility = if (screen == "home") View.VISIBLE else View.INVISIBLE
        if (screen == "memory") {
            memoryPage.refresh()
        }
        if (screen == "persona") {
            personaPage.refresh()
        }
        if (screen == "diagnostics") {
            diagnosticsPage.refresh()
        }
        rootScroll.scrollTo(0, 0)
    }

    private fun refreshStatus() {
        if (skipPersonaPresetMigration) {
            return
        }
        val message = ProbeStatus.message(this)
        val time = ProbeStatus.time(this)
        val timeText = if (time > 0L) {
            java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.CHINA,
            ).format(java.util.Date(time))
        } else {
            "尚未记录"
        }
        val statusWithTime = getString(R.string.status_with_time, message, timeText)
        homePage.renderStatus(statusWithTime)
        homePage.renderRunState(BotLoopService.runState)
        homePage.refreshDeployment()
        settingsPage.refresh()
        memoryPage.refresh()
        personaPage.refresh()
        diagnosticsPage.renderStatus(statusWithTime)
        diagnosticsPage.refreshPreview(ProbeStatus.screenshotPath(this))
    }

    private fun confirmExit() {
        if (shutdownInProgress) {
            return
        }
        val message = when (BotLoopService.runState) {
            AgentRunState.RUNNING,
            AgentRunState.PAUSED,
            AgentRunState.STOPPING,
            -> "代理会立即停止，随后最多用 8 秒整理当前记忆。即使整理失败或超时，也会退出。"
            AgentRunState.OFF ->
                "将关闭悬浮气泡、读屏服务、通知和代理。"
        }
        MainUiComponents.showDestructiveDialog(
            context = this,
            title = "退出代理",
            message = message,
            positiveLabel = "退出",
            onConfirm = ::beginShutdown,
        )
    }

    private fun beginShutdown() {
        if (shutdownInProgress) {
            return
        }
        shutdownInProgress = true
        headerExit.isEnabled = false
        headerExit.text = "退出中"
        headerSettings.isEnabled = false
        AppShutdownCoordinator.shutdown(this) { result ->
            val message = when {
                result.timedOut -> "代理已退出；记忆已保留为待恢复"
                result.error.isNotBlank() -> "代理已退出；记忆总结失败"
                result.summarized -> "代理已停止并完成记忆整理"
                else -> "代理已退出"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({
                finishAndRemoveTask()
                mainHandler.postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 250L)
            }, 350L)
        }
    }

    private fun startBotLoop() {
        presentAgentResult(AgentControl.start(this))
    }

    private fun stopBotLoop() {
        presentAgentResult(AgentControl.stop(this))
    }

    private fun toggleAgentFromHome() {
        presentAgentResult(AgentControl.toggle(this))
    }

    private fun presentAgentResult(result: AgentActionResult) {
        ProbeStatus.write(this, result.message)
        when (result.step) {
            AgentSetupStep.NONE -> Unit
            AgentSetupStep.INPUT_METHOD ->
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            AgentSetupStep.INPUT_METHOD_SELECT ->
                getSystemService(InputMethodManager::class.java)
                    .showInputMethodPicker()
            AgentSetupStep.ACCESSIBILITY ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            AgentSetupStep.NOTIFICATION -> requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
            AgentSetupStep.BATTERY -> openBatteryOptimizationSettings()
            AgentSetupStep.DEVICE_PROFILE -> Unit
            AgentSetupStep.API_KEY -> settingsPage.showModelConfigDialog()
            AgentSetupStep.OPEN_WECHAT -> switchToWeChatSoon()
        }
        mainHandler.postDelayed({ refreshStatus() }, 500L)
    }

    private fun openBatteryOptimizationSettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun showAgentBubble() {
        BubbleSettings.setEnabled(this, true)
        if (!Settings.canDrawOverlays(this)) {
            ProbeStatus.write(this, "请允许“显示在其他应用上层”，返回后气泡会自动出现。")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        startForegroundService(
            Intent(this, AgentBubbleService::class.java).setAction(
                AgentBubbleService.ACTION_SHOW,
            ),
        )
        ProbeStatus.write(this, "代理悬浮气泡已开启。")
        mainHandler.postDelayed(::refreshStatus, 400L)
    }

    private fun hideAgentBubble() {
        BubbleSettings.setEnabled(this, false)
        startService(
            Intent(this, AgentBubbleService::class.java).setAction(
                AgentBubbleService.ACTION_HIDE,
            ),
        )
        ProbeStatus.write(this, "代理悬浮气泡已关闭。")
        mainHandler.postDelayed(::refreshStatus, 400L)
    }

    private fun restoreBubbleIfNeeded() {
        if (BubbleSettings.enabled(this) && Settings.canDrawOverlays(this)) {
            startForegroundService(
                Intent(this, AgentBubbleService::class.java).setAction(
                    AgentBubbleService.ACTION_SHOW,
                ),
            )
        }
    }

    private fun onScreenshotClicked() {
        if (
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingScreenshotAfterPermission = true
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
            return
        }
        beginCaptureFlow()
    }

    private fun openNotificationPermissionFlow() {
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            openNotificationSettings()
            return
        }
        val preferences = getSharedPreferences(
            NOTIFICATION_PERMISSION_PREFS,
            MODE_PRIVATE,
        )
        val askedBefore = preferences.getBoolean(
            NOTIFICATION_PERMISSION_REQUESTED,
            false,
        )
        val shouldAsk = !askedBefore ||
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        if (shouldAsk) {
            preferences.edit()
                .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true)
                .apply()
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun beginCaptureFlow() {
        if (ScreenCaptureService.isSessionActive) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_CAPTURE_AFTER_DELAY
                putExtra(ScreenCaptureService.EXTRA_DELAY_MS, CAPTURE_DELAY_MS)
            }
            startService(intent)
            ProbeStatus.write(this, "5 秒后截图。请保持微信聊天界面。")
            switchToWeChatSoon()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(
            manager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION,
        )
    }

    private fun switchToWeChatSoon() {
        Toast.makeText(this, "5 秒内切回微信", Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({
            moveTaskToBack(true)
        }, 700L)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) {
            return
        }

        if (resultCode != RESULT_OK || data == null) {
            ProbeStatus.write(this, "录屏授权被取消。")
            return
        }

        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_AND_CAPTURE
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_PROJECTION_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_DELAY_MS, CAPTURE_DELAY_MS)
        }
        startForegroundService(intent)
        ProbeStatus.write(this, "录屏授权成功。5 秒后截图，请切回微信。")
        switchToWeChatSoon()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && pendingScreenshotAfterPermission) {
            pendingScreenshotAfterPermission = false
            beginCaptureFlow()
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            mainHandler.postDelayed(::refreshStatus, 250L)
        }
    }

    private fun fullWidth(): LinearLayout.LayoutParams {
        return MainUiComponents.fullWidth()
    }

    private fun Int.dp(): Int {
        return AppUi.dp(this@MainActivity, this)
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val NOTIFICATION_PERMISSION_PREFS =
            "notification_permission_flow"
        private const val NOTIFICATION_PERMISSION_REQUESTED = "requested"
        private const val CAPTURE_DELAY_MS = 5_000L
    }
}
