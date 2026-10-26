package com.local.wxbotprobe

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.api.ModelSettings
import com.local.wxbotprobe.bot.ConversationStore
import com.local.wxbotprobe.bot.EffectiveRoleplayPolicy
import com.local.wxbotprobe.bot.IncomingMessage
import com.local.wxbotprobe.bot.PersonaLoader
import com.local.wxbotprobe.bot.PersonaIdentityMode
import com.local.wxbotprobe.bot.PersonaText
import com.local.wxbotprobe.bot.ReplyConfig
import com.local.wxbotprobe.bot.ReplyEngine
import com.local.wxbotprobe.device.DeviceProfileResolver
import com.local.wxbotprobe.ocr.OcrEngine
import com.local.wxbotprobe.ocr.RapidOcrAndroidEngine
import com.local.wxbotprobe.ocr.ScreenSnapshotBuilder
import com.local.wxbotprobe.send.WeChatSender
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class DiagnosticsPageView(
    private val activity: Activity,
    private val onScreenshotRequested: () -> Unit,
    private val onStartAgent: () -> Unit,
    private val onStopAgent: () -> Unit,
    private val onConfigureApiKey: () -> Unit,
    private val onOpenWeChatSoon: () -> Unit,
) {
    val view: LinearLayout

    private lateinit var statusText: TextView
    private lateinit var queueDetail: TextView
    private lateinit var previewText: TextView
    private lateinit var previewImage: ImageView
    private var lastPreviewPath: String? = null

    init {
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)

            addView(MainUiComponents.sectionLabel(activity, "最近结果"))
            statusText = AppUi.textView(
                activity,
                "",
                14f,
                AppUi.textSecondary,
            ).apply {
                setPadding(dp(16), dp(15), dp(16), dp(15))
                setLineSpacing(dp(3).toFloat(), 1f)
            }
            addView(
                MainUiComponents.statusSurface(activity, statusText),
                MainUiComponents.fullWidth(),
            )

            addView(
                MainUiComponents.sectionLabel(activity, "任务队列").apply {
                    MainUiComponents.topPadding(this, activity, 22)
                },
            )
            val queueRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_refresh,
                "发送与生成队列",
                QueueAdminDialog.summary(activity),
                onClick = {
                    QueueAdminDialog(activity) { refresh() }.show()
                },
            )
            queueDetail = queueRow.detail
            addView(
                MainUiComponents.groupViews(activity, listOf(queueRow.view)),
                MainUiComponents.fullWidth(),
            )

            addView(
                MainUiComponents.sectionLabel(activity, "读屏与识别").apply {
                    MainUiComponents.topPadding(this, activity, 22)
                },
            )
            addView(
                MainUiComponents.groupViews(
                    activity,
                    listOf(
                        actionRow(
                            R.drawable.ic_play,
                            "启动读屏监控",
                            "建立基线并开始轮询",
                            onClick = onStartAgent,
                        ),
                        actionRow(
                            R.drawable.ic_stop_line,
                            "停止读屏监控",
                            "停止并整理当前会话",
                            danger = true,
                            onClick = onStopAgent,
                        ),
                        actionRow(
                            R.drawable.ic_camera,
                            "截图",
                            "首次使用需要录屏授权",
                            onClick = onScreenshotRequested,
                        ),
                        actionRow(
                            R.drawable.ic_scan,
                            "当前传输截图",
                            "5 秒后按所选路线执行",
                            onClick = ::captureCurrentTransport,
                        ),
                        actionRow(
                            R.drawable.ic_activity,
                            "路线 C 截图基准",
                            "连续 60 次，间隔 3 秒",
                            onClick = ::runAccessibilityBenchmark,
                        ),
                        actionRow(
                            R.drawable.ic_scan,
                            "OCR 最新截图",
                            "RapidOCR 识别文字",
                            onClick = ::recognizeLatestScreenshot,
                        ),
                        actionRow(
                            R.drawable.ic_activity,
                            "解析最新截图并读屏",
                            "生成气泡与聊天身份结果",
                            onClick = ::parseLatestScreenshot,
                        ),
                    ),
                ),
                MainUiComponents.fullWidth(),
            )

            addView(
                MainUiComponents.sectionLabel(activity, "输入与发送").apply {
                    MainUiComponents.topPadding(this, activity, 22)
                },
            )
            addView(
                MainUiComponents.groupViews(
                    activity,
                    listOf(
                        actionRow(
                            R.drawable.ic_keyboard,
                            "当前传输点击输入框",
                            "5 秒后点击当前设备的输入框锚点",
                            onClick = ::tapCurrentTransport,
                        ),
                        actionRow(
                            R.drawable.ic_robot,
                            "生成测试回复",
                            "调用模型但不发送",
                            onClick = ::generateTestReply,
                        ),
                        actionRow(
                            R.drawable.ic_keyboard,
                            "测试输入链路",
                            "写入微信但停留在草稿状态",
                        ) { performSendTest(dryRun = true) },
                        actionRow(
                            R.drawable.ic_send,
                            "发送一条测试消息",
                            "向当前聊天发送“链路测试”",
                            danger = true,
                            onClick = ::confirmLiveSend,
                        ),
                        actionRow(
                            R.drawable.ic_camera,
                            "停止录屏服务",
                            "结束 MediaProjection 会话",
                            onClick = ::stopCaptureService,
                        ),
                    ),
                ),
                MainUiComponents.fullWidth(),
            )

            addView(
                MainUiComponents.sectionLabel(activity, "截图预览").apply {
                    MainUiComponents.topPadding(this, activity, 22)
                },
            )
            previewText = AppUi.textView(
                activity,
                "尚无截图",
                14f,
                AppUi.textSecondary,
            )
            val previewSurface = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = AppUi.rounded(activity, AppUi.surface, 8f, AppUi.border)
                clipToOutline = true
            }
            previewSurface.addView(previewText, MainUiComponents.fullWidth())
            previewImage = ImageView(activity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(AppUi.surfaceSubtle)
            }
            previewSurface.addView(
                previewImage,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(420),
                ).apply {
                    topMargin = dp(10)
                },
            )
            addView(previewSurface, MainUiComponents.fullWidth())
        }
    }

    fun renderStatus(value: String) {
        statusText.text = value
    }

    fun refresh() {
        if (::queueDetail.isInitialized) {
            queueDetail.text = QueueAdminDialog.summary(activity)
        }
    }

    fun refreshPreview(path: String?) {
        if (path == lastPreviewPath || path == null) {
            return
        }
        lastPreviewPath = path
        val file = File(path)
        if (!file.exists()) {
            previewText.text = activity.getString(R.string.screenshot_missing, path)
            return
        }
        val bitmap = BitmapFactory.decodeFile(path)
        if (bitmap == null) {
            previewText.text = activity.getString(R.string.screenshot_decode_failed)
            return
        }
        previewImage.setImageBitmap(bitmap)
        previewText.text = activity.getString(
            R.string.screenshot_summary,
            bitmap.width,
            bitmap.height,
            file.length() / 1024,
        )
    }

    private fun captureCurrentTransport() {
        val transport = DeviceTransportFactory.create(activity)
        if (!transport.isReady()) {
            ProbeStatus.write(activity, transport.unavailableMessage())
            if (transport.mode == TransportMode.SHIZUKU) {
                RouteBPlatform.connect(activity)
            } else {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }

        ProbeStatus.write(
            activity,
            "5 秒后使用${transport.mode.label}截图。请保持微信聊天界面。",
        )
        onOpenWeChatSoon()
        activity.window.decorView.postDelayed({
            Thread {
                try {
                    val directory = File(
                        activity.getExternalFilesDir(null) ?: activity.filesDir,
                        "transport-captures",
                    )
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw IllegalStateException("无法创建传输截图目录")
                    }
                    val stamp = SimpleDateFormat(
                        "yyyyMMdd-HHmmss-SSS",
                        Locale.CHINA,
                    ).format(Date())
                    val output = File(
                        directory,
                        "${transport.mode.storageValue}-$stamp.png",
                    )
                    transport.screenshot(output)
                    ProbeStatus.write(
                        activity,
                        "${transport.mode.label}截图成功：${output.absolutePath}",
                        output.absolutePath,
                    )
                } catch (error: Throwable) {
                    ProbeStatus.write(
                        activity,
                        "${transport.mode.label}截图失败：${error.message}",
                    )
                }
            }.start()
        }, SHIZUKU_DELAY_MS)
    }

    private fun tapCurrentTransport() {
        val transport = DeviceTransportFactory.create(activity)
        if (!transport.isReady()) {
            ProbeStatus.write(activity, transport.unavailableMessage())
            if (transport.mode == TransportMode.SHIZUKU) {
                RouteBPlatform.connect(activity)
            } else {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }

        val inputPoint = DeviceProfileResolver.current(activity).inputAnchor
        ProbeStatus.write(
            activity,
            "5 秒后使用${transport.mode.label}点击 " +
                "(${inputPoint.x}, ${inputPoint.y})。",
        )
        onOpenWeChatSoon()
        activity.window.decorView.postDelayed({
            Thread {
                try {
                    transport.tap(inputPoint.x, inputPoint.y)
                    ProbeStatus.write(
                        activity,
                        "${transport.mode.label}已点击 " +
                            "(${inputPoint.x}, ${inputPoint.y})，未发送消息。",
                    )
                } catch (error: Throwable) {
                    ProbeStatus.write(
                        activity,
                        "${transport.mode.label}点击失败：${error.message}",
                    )
                }
            }.start()
        }, SHIZUKU_DELAY_MS)
    }

    private fun runAccessibilityBenchmark() {
        val shizuku = ShizukuTransport()
        if (!shizuku.isReady()) {
            ProbeStatus.write(activity, shizuku.unavailableMessage())
            RouteBPlatform.connect(activity)
            return
        }
        val accessibility = AccessibilityTransport()
        if (!accessibility.isReady()) {
            ProbeStatus.write(activity, accessibility.unavailableMessage())
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        ProbeStatus.write(
            activity,
            "路线 C 基准将在 5 秒后开始：60 次截图，间隔 3 秒。",
        )
        onOpenWeChatSoon()
        Thread {
            val directory = File(
                activity.getExternalFilesDir(null) ?: activity.filesDir,
                "transport-benchmark",
            )
            try {
                val report = TransportDiagnostics.runAccessibilityCaptureBenchmark(
                    context = activity,
                    count = 60,
                    intervalMs = 3_000L,
                    outputDirectory = directory,
                    initialDelayMs = SHIZUKU_DELAY_MS,
                ) { completed, total ->
                    if (completed == total || completed % 10 == 0) {
                        ProbeStatus.write(
                            activity,
                            "路线 C 截图基准 $completed/$total。",
                        )
                    }
                }
                ProbeStatus.write(
                    activity,
                    "路线 C 截图基准完成：${report.absolutePath}",
                    File(directory, "route-c-latest.png").absolutePath,
                )
            } catch (error: Throwable) {
                ProbeStatus.write(
                    activity,
                    "路线 C 截图基准失败：${error.message}",
                )
            }
        }.start()
    }

    private fun recognizeLatestScreenshot() {
        runOcrLatest(
            engineKey = "rapidocr",
            engineName = "rapidocr-android-1.3.0",
        ) {
            RapidOcrAndroidEngine.get(activity)
        }
    }

    private fun runOcrLatest(
        engineKey: String,
        engineName: String,
        engineFactory: () -> OcrEngine,
    ) {
        val screenshotPath = ProbeStatus.screenshotPath(activity)
        val screenshot = screenshotPath?.let(::File)
        if (screenshot == null || !screenshot.exists()) {
            ProbeStatus.write(activity, "没有可 OCR 的 Shizuku 截图。")
            return
        }

        ProbeStatus.write(activity, "正在识别最新 Shizuku 截图（$engineName）。")
        Thread {
            val startedAt = System.currentTimeMillis()
            try {
                val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
                    ?: throw IllegalStateException("截图解码失败")
                val rows = engineFactory().use { it.recognize(bitmap) }
                bitmap.recycle()

                val outputDirectory = File(
                    activity.getExternalFilesDir(null) ?: activity.filesDir,
                    "ocr",
                )
                if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                    throw IllegalStateException("无法创建 OCR 输出目录")
                }
                val stamp = SimpleDateFormat(
                    "yyyyMMdd-HHmmss-SSS",
                    Locale.CHINA,
                ).format(Date())
                val output = File(outputDirectory, "$engineKey-$stamp.json")
                val elapsedMs = System.currentTimeMillis() - startedAt
                val root = JSONObject()
                    .put("engine", engineName)
                    .put("image", screenshot.absolutePath)
                    .put("elapsedMs", elapsedMs)
                    .put("rowCount", rows.size)
                    .put(
                        "rows",
                        JSONArray().apply {
                            rows.forEach { row ->
                                put(
                                    JSONObject()
                                        .put("y", row.y)
                                        .put("xLeft", row.xLeft)
                                        .put("xRight", row.xRight)
                                        .put("text", row.text)
                                        .put("score", row.score),
                                )
                            }
                        },
                    )
                FileOutputStream(output).use {
                    it.write(root.toString(2).toByteArray(Charsets.UTF_8))
                }
                ProbeStatus.write(
                    activity,
                    "$engineName OCR 完成：${rows.size} 行，${elapsedMs}ms\n" +
                        output.absolutePath,
                )
            } catch (error: Throwable) {
                ProbeStatus.write(activity, "$engineName OCR 失败：${error.message}")
            }
        }.start()
    }

    private fun parseLatestScreenshot() {
        val screenshotPath = ProbeStatus.screenshotPath(activity)
        val screenshot = screenshotPath?.let(::File)
        if (screenshot == null || !screenshot.exists()) {
            ProbeStatus.write(activity, "没有可解析的 Shizuku 截图。")
            return
        }

        ProbeStatus.write(activity, "正在解析最新截图的聊天气泡。")
        Thread {
            val startedAt = System.currentTimeMillis()
            try {
                val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
                    ?: throw IllegalStateException("截图解码失败")
                val snapshot = ScreenSnapshotBuilder(
                    RapidOcrAndroidEngine.get(activity),
                    DeviceProfileResolver.current(activity),
                ).read(bitmap, "rapidocr-android-1.3.0")
                bitmap.recycle()

                val outputDirectory = File(
                    activity.getExternalFilesDir(null) ?: activity.filesDir,
                    "screens",
                )
                if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                    throw IllegalStateException("无法创建读屏输出目录")
                }
                val stamp = SimpleDateFormat(
                    "yyyyMMdd-HHmmss-SSS",
                    Locale.CHINA,
                ).format(Date())
                val output = File(outputDirectory, "screen-$stamp.json")
                val elapsedMs = System.currentTimeMillis() - startedAt
                val root = JSONObject()
                    .put("engine", snapshot.engine)
                    .put("image", screenshot.absolutePath)
                    .put("elapsedMs", elapsedMs)
                    .put("width", snapshot.width)
                    .put("height", snapshot.height)
                    .put("ocrRowCount", snapshot.ocrRowCount)
                    .put("isGroup", snapshot.isGroup)
                    .put("chatTitle", snapshot.chat.title)
                    .put("conversationKey", snapshot.chat.conversationKey)
                    .put(
                        "chatFingerprint",
                        java.lang.Long.toHexString(snapshot.chat.fingerprint),
                    )
                    .put("sendButtonY", snapshot.sendButtonY)
                    .put(
                        "inputField",
                        snapshot.inputField?.let {
                            JSONObject().put("x", it.x).put("y", it.y)
                        },
                    )
                    .put(
                        "items",
                        JSONArray().apply {
                            snapshot.items.forEach { item ->
                                put(
                                    JSONObject()
                                        .put("text", item.text)
                                        .put("kind", item.kind.name.lowercase())
                                        .put("y", item.y)
                                        .put("xLeft", item.xLeft),
                                )
                            }
                        },
                    )
                    .put(
                        "greyRows",
                        JSONArray().apply {
                            snapshot.greyRows.forEach { row ->
                                put(
                                    JSONObject()
                                        .put("text", row.text)
                                        .put("y", row.y)
                                        .put("xLeft", row.xLeft)
                                        .put("xRight", row.xRight),
                                )
                            }
                        },
                    )
                FileOutputStream(output).use {
                    it.write(root.toString(2).toByteArray(Charsets.UTF_8))
                }
                val incoming = snapshot.items.count {
                    it.kind == com.local.wxbotprobe.chat.BubbleKind.IN
                }
                ProbeStatus.write(
                    activity,
                    "读屏完成：${snapshot.items.size} 个气泡，$incoming 个对方，" +
                        "${elapsedMs}ms\n${output.absolutePath}",
                )
            } catch (error: Throwable) {
                ProbeStatus.write(activity, "读屏解析失败：${error.message}")
            }
        }.start()
    }

    private fun generateTestReply() {
        val client = ModelClientFactory.create(activity)
        if (client == null) {
            ProbeStatus.write(activity, "请先完成模型配置。")
            onConfigureApiKey()
            return
        }
        ProbeStatus.write(activity, "正在生成测试回复（不会发送）。")
        Thread {
            val startedAt = System.currentTimeMillis()
            try {
                val persona = PersonaLoader(activity).load()
                val config = ReplyConfig(
                    systemPrompt = PersonaText.systemPrompt(persona),
                    personaMessages = PersonaText.turns(persona.examples),
                    temperature = 1.0,
                    maxTokens = 500,
                    historyTurns = 0,
                    minReplyInterval = 0.0,
                    maxReplyChars = 200,
                    identityQuestionPolicy = persona.identityQuestionPolicy,
                    roleplayPolicyProvider = {
                        if (
                            persona.identityMode ==
                            PersonaIdentityMode.IMMERSIVE_COMPANION
                        ) {
                            EffectiveRoleplayPolicy.TRANSPARENT
                        } else {
                            EffectiveRoleplayPolicy.NOT_APPLICABLE
                        }
                    },
                )
                val engine = ReplyEngine(
                    config,
                    client,
                    ConversationStore(config.historyTurns),
                )
                val result = engine.handle(
                    IncomingMessage(
                        id = "debug-${System.currentTimeMillis()}",
                        text = "在吗",
                    ),
                    commitReply = false,
                )
                if (!result.sent) {
                    val reason = result.error.ifBlank {
                        result.skippedReason.ifBlank { "未知原因" }
                    }
                    ProbeStatus.write(activity, "测试回复失败：$reason")
                    return@Thread
                }

                val directory = File(
                    activity.getExternalFilesDir(null) ?: activity.filesDir,
                    "replies",
                )
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IllegalStateException("无法创建回复目录")
                }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.CHINA)
                    .format(Date())
                val output = File(directory, "debug-reply-$stamp.json")
                val root = JSONObject()
                    .put("text", result.text)
                    .put("model", result.model)
                    .put("provider", ModelSettings.load(activity).provider.label)
                    .put("elapsedMs", System.currentTimeMillis() - startedAt)
                    .put("latencyMs", result.latencyMs)
                    .put("promptTokens", result.promptTokens)
                    .put("completionTokens", result.completionTokens)
                    .put("sent", false)
                FileOutputStream(output).use {
                    it.write(root.toString(2).toByteArray(Charsets.UTF_8))
                }
                ProbeStatus.write(
                    activity,
                    "测试回复已生成，未发送：${output.absolutePath}",
                )
            } catch (error: Throwable) {
                ProbeStatus.write(activity, "测试回复失败：${error.message}")
            }
        }.start()
    }

    private fun confirmLiveSend() {
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = "发送测试消息",
            message = "将向当前微信聊天发送「链路测试」。",
            positiveLabel = "发送",
            onConfirm = { performSendTest(dryRun = false) },
        )
    }

    private fun performSendTest(dryRun: Boolean) {
        val transport = DeviceTransportFactory.create(activity)
        if (!transport.isReady()) {
            ProbeStatus.write(activity, transport.unavailableMessage())
            if (transport.mode == TransportMode.SHIZUKU) {
                RouteBPlatform.connect(activity)
            }
            return
        }
        if (transport.mode == TransportMode.ACCESSIBILITY) {
            ImeStrategyFactory.routeCStartupMessage(activity)?.let { message ->
                ProbeStatus.write(activity, message)
                return
            }
        }
        val text = if (dryRun) "在\n刚看到" else "链路测试"
        ProbeStatus.write(
            activity,
            if (dryRun) "5 秒后测试输入，不会发送。" else "5 秒后发送测试消息。",
        )
        onOpenWeChatSoon()
        activity.window.decorView.postDelayed({
            Thread {
                val outcome = WeChatSender(activity).send(text, dryRun)
                val message = when {
                    dryRun && outcome.success ->
                        "输入链路测试成功：拆成 ${outcome.parts.size} 条，未发送。"
                    dryRun ->
                        "输入链路测试失败：${outcome.error}"
                    outcome.success ->
                        "测试消息发送成功：${outcome.sentParts}/${outcome.parts.size} 条"
                    else ->
                        "测试消息发送失败：${outcome.error}，" +
                            "已发送 ${outcome.sentParts}/${outcome.parts.size} 条"
                }
                ProbeStatus.write(activity, message)
            }.start()
        }, SHIZUKU_DELAY_MS)
    }

    private fun stopCaptureService() {
        val intent = Intent(activity, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        activity.startService(intent)
    }

    private fun actionRow(
        iconResource: Int,
        title: String,
        subtitle: String,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): View {
        return MainUiComponents.actionRow(
            activity,
            iconResource,
            title,
            subtitle,
            danger,
            onClick,
        ).view
    }

    private fun dp(value: Int): Int = AppUi.dp(activity, value)

    companion object {
        private const val SHIZUKU_DELAY_MS = 5_000L
    }
}
