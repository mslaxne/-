package com.local.wxbotprobe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import com.local.wxbotprobe.device.DeviceProfileMode
import com.local.wxbotprobe.device.DeviceProfileResolver

data class AgentActionResult(
    val message: String,
    val step: AgentSetupStep = AgentSetupStep.NONE,
)

object AgentControl {
    fun toggle(context: Context): AgentActionResult {
        return when (BotLoopService.runState) {
            AgentRunState.RUNNING -> pause(context)
            AgentRunState.PAUSED -> resume(context)
            AgentRunState.OFF -> start(context)
            AgentRunState.STOPPING -> AgentActionResult("正在停止并整理记忆")
        }
    }

    fun start(context: Context): AgentActionResult {
        if (BotLoopService.runState == AgentRunState.PAUSED) {
            return resume(context)
        }
        val decision = startupDecision(context)
        if (!decision.ready) return decision.toResult()
        context.startForegroundService(Intent(context, BotLoopService::class.java))
        return decision.toResult()
    }

    fun pause(context: Context): AgentActionResult {
        context.startService(
            Intent(context, BotLoopService::class.java).apply {
                action = BotLoopService.ACTION_PAUSE
            },
        )
        return AgentActionResult("代理已暂停，会话保留")
    }

    fun resume(context: Context): AgentActionResult {
        val decision = startupDecision(context)
        if (!decision.ready) return decision.toResult()
        context.startForegroundService(
            Intent(context, BotLoopService::class.java).apply {
                action = BotLoopService.ACTION_RESUME
            },
        )
        return decision.toResult()
    }

    fun stop(context: Context): AgentActionResult {
        context.startForegroundService(
            Intent(context, BotLoopService::class.java).apply {
                action = BotLoopService.ACTION_STOP
            },
        )
        return AgentActionResult("代理已停止，记忆将在后台整理")
    }

    private fun startupDecision(context: Context): AgentStartDecision {
        val transport = DeviceTransportFactory.create(context)
        if (!transport.isReady() && transport.mode == TransportMode.SHIZUKU) {
            RouteBPlatform.connect(context)
        }
        val routeCRequiresIme = transport.mode == TransportMode.ACCESSIBILITY
        val power = context.getSystemService(PowerManager::class.java)
        return AgentStartPolicy.evaluate(
            transportReady = transport.isReady(),
            transportMessage = transport.unavailableMessage(),
            routeCRequiresIme = routeCRequiresIme,
            routeCImeIssue = if (routeCRequiresIme) {
                ImeStrategyFactory.routeCStartupIssue(context)
            } else {
                null
            },
            accessibilityConnected = ProbeAccessibilityService.instance != null,
            batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
            apiKeyConfigured = com.local.wxbotprobe.api.ModelSettings.isConfigured(context),
            notificationGranted = context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
            deviceProfileVerified = DeviceProfileResolver.current(context).mode ==
                DeviceProfileMode.VERIFIED,
        )
    }

    private fun AgentStartDecision.toResult(): AgentActionResult {
        return AgentActionResult(message, step)
    }
}
