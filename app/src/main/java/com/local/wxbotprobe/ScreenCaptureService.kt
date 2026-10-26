package com.local.wxbotprobe

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var captureHandler: Handler
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureTimeout: Runnable? = null
    private val captureInFlight = AtomicBoolean(false)
    private val captureFinalized = AtomicBoolean(true)
    private val captureArmed = AtomicBoolean(false)
    private var captureStartedAt = 0L
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        workerThread = HandlerThread("screen-capture-worker").apply { start() }
        captureHandler = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AND_CAPTURE -> startSessionAndCapture(intent)
            ACTION_CAPTURE_AFTER_DELAY -> {
                if (projection == null) {
                    ProbeStatus.write(this, "录屏会话不存在，请重新点击截图并授权。")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    scheduleCapture(intent.getLongExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS))
                }
            }
            ACTION_STOP -> stopSession("录屏服务已停止。")
            else -> {
                ProbeStatus.write(this, "收到未知录屏服务指令。")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isSessionActive = false
        releaseCaptureResources()
        projection?.let { activeProjection ->
            projectionCallback?.let { callback ->
                runCatching { activeProjection.unregisterCallback(callback) }
            }
            runCatching { activeProjection.stop() }
        }
        projection = null
        projectionCallback = null
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun startSessionAndCapture(intent: Intent) {
        startForeground(
            NOTIFICATION_ID,
            buildNotification("录屏会话启动中"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData = intent.getParcelableExtra(
            EXTRA_PROJECTION_DATA,
            Intent::class.java,
        )
        if (projectionData == null) {
            failSession("录屏授权数据为空。")
            return
        }

        if (projection == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val activeProjection = try {
                manager.getMediaProjection(resultCode, projectionData)
            } catch (error: Exception) {
                failSession("创建 MediaProjection 失败：${error.message}")
                return
            }

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    isSessionActive = false
                    projection = null
                    releaseCaptureResources()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            activeProjection.registerCallback(callback, mainHandler)
            projection = activeProjection
            projectionCallback = callback
            isSessionActive = true
        }

        updateNotification("录屏服务运行中")
        scheduleCapture(intent.getLongExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS))
    }

    private fun scheduleCapture(delayMs: Long) {
        if (!captureInFlight.compareAndSet(false, true)) {
            ProbeStatus.write(this, "上一张截图尚未完成，忽略本次请求。")
            return
        }
        captureFinalized.set(false)
        captureArmed.set(false)
        captureStartedAt = SystemClock.elapsedRealtime()
        updateNotification("等待 ${delayMs / 1000} 秒后截图")
        mainHandler.postDelayed({
            captureOnce()
        }, delayMs)
    }

    private fun captureOnce() {
        val activeProjection = projection
        if (activeProjection == null || stopping) {
            finishCapture(false, "录屏会话已结束，未截图。", null)
            return
        }

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val powerManager = getSystemService(PowerManager::class.java)
        if (keyguardManager.isKeyguardLocked || !powerManager.isInteractive) {
            finishCapture(false, "屏幕已锁定或熄灭，未截图。", null)
            return
        }

        val windowManager = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        try {
            val reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2,
            )
            imageReader = reader

            reader.setOnImageAvailableListener({ availableReader ->
                val image = try {
                    availableReader.acquireLatestImage()
                } catch (error: Exception) {
                    finishCapture(false, "读取截屏帧失败：${error.message}", null)
                    return@setOnImageAvailableListener
                }

                if (image == null) {
                    return@setOnImageAvailableListener
                }

                if (!captureArmed.get()) {
                    image.close()
                    return@setOnImageAvailableListener
                }

                if (isNearlyAllBlack(image)) {
                    val elapsed = SystemClock.elapsedRealtime() - captureStartedAt
                    image.close()
                    if (elapsed >= BLACK_FRAME_LIMIT_MS) {
                        finishCapture(
                            false,
                            "MediaProjection 持续返回纯黑画面，未保存截图。",
                            null,
                        )
                    }
                    return@setOnImageAvailableListener
                }

                captureTimeout?.let(mainHandler::removeCallbacks)
                captureTimeout = null
                val path = try {
                    saveImage(image, width, height)
                } catch (error: Exception) {
                    finishCapture(false, "保存截屏失败：${error.message}", null)
                    null
                } finally {
                    image.close()
                }

                if (path != null) {
                    finishCapture(true, "截图成功：$path", path)
                }
            }, captureHandler)

            virtualDisplay = activeProjection.createVirtualDisplay(
                "WxBotProbeCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            )
            mainHandler.postDelayed({
                captureArmed.set(true)
            }, FRAME_SETTLE_MS)
        } catch (error: Exception) {
            finishCapture(false, "启动屏幕捕获失败：${error.message}", null)
            return
        }

        updateNotification("正在截屏")
        val timeout = Runnable {
            finishCapture(false, "6 秒内未收到可读屏幕帧，请检查录屏授权。", null)
        }
        captureTimeout = timeout
        mainHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
    }

    private fun isNearlyAllBlack(image: Image): Boolean {
        val plane = image.planes.first()
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var sampled = 0
        var visible = 0

        var y = 0
        while (y < image.height) {
            val rowOffset = y * rowStride
            var x = 0
            while (x < image.width) {
                val index = rowOffset + x * pixelStride
                if (index + 2 < buffer.limit()) {
                    val red = buffer.get(index).toInt() and 0xFF
                    val green = buffer.get(index + 1).toInt() and 0xFF
                    val blue = buffer.get(index + 2).toInt() and 0xFF
                    if (maxOf(red, green, blue) > 24) {
                        visible++
                    }
                    sampled++
                }
                x += 16
            }
            y += 16
        }

        return sampled == 0 || visible * 100 < sampled
    }

    private fun saveImage(image: Image, width: Int, height: Int): String {
        val plane = image.planes.first()
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            height,
            Bitmap.Config.ARGB_8888,
        )
        plane.buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(plane.buffer)
        val croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        if (croppedBitmap !== paddedBitmap) {
            paddedBitmap.recycle()
        }

        val directory = File(filesDir, "captures")
        if (!directory.exists() && !directory.mkdirs()) {
            croppedBitmap.recycle()
            throw IllegalStateException("无法创建截图目录 ${directory.absolutePath}")
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.CHINA)
            .format(Date())
        val outputFile = File(directory, "capture-$stamp.png")
        FileOutputStream(outputFile).use { output ->
            if (!croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                croppedBitmap.recycle()
                throw IllegalStateException("PNG 编码失败")
            }
        }
        croppedBitmap.recycle()
        return outputFile.absolutePath
    }

    private fun finishCapture(success: Boolean, message: String, path: String?) {
        if (!captureFinalized.compareAndSet(false, true)) {
            return
        }
        captureInFlight.set(false)
        captureArmed.set(false)
        captureTimeout?.let(mainHandler::removeCallbacks)
        captureTimeout = null
        releaseCaptureResources()
        ProbeStatus.write(this, message, path)
        updateNotification(if (success) "截图完成" else message)
    }

    private fun releaseCaptureResources() {
        imageReader?.setOnImageAvailableListener(null, null)
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
    }

    private fun failSession(message: String) {
        ProbeStatus.write(this, message)
        updateNotification(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSession(message: String) {
        stopping = true
        isSessionActive = false
        releaseCaptureResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ProbeStatus.write(this, message)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "代理录屏",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_CAPTURE_AFTER_DELAY
            putExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS)
        }
        val capturePendingIntent = PendingIntent.getService(
            this,
            2,
            captureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("代理")
            .setContentText(message)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_camera, "5秒后截图", capturePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    companion object {
        const val ACTION_START_AND_CAPTURE = "com.local.wxbotprobe.START_AND_CAPTURE"
        const val ACTION_CAPTURE_AFTER_DELAY = "com.local.wxbotprobe.CAPTURE_AFTER_DELAY"
        const val ACTION_STOP = "com.local.wxbotprobe.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_DELAY_MS = "delay_ms"

        @Volatile
        var isSessionActive = false
            private set

        private const val CHANNEL_ID = "wxbot_probe_capture"
        private const val NOTIFICATION_ID = 100
        private const val DEFAULT_DELAY_MS = 5_000L
        private const val FRAME_SETTLE_MS = 1_200L
        private const val CAPTURE_TIMEOUT_MS = 6_000L
        private const val BLACK_FRAME_LIMIT_MS = 5_500L
    }
}
