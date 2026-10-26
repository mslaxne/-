package com.local.wxbotprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStartPolicyTest {
    private fun evaluate(
        transportReady: Boolean = true,
        routeCRequiresIme: Boolean = true,
        routeCImeIssue: RouteCImeIssue? = null,
        accessibilityConnected: Boolean = true,
        batteryExempt: Boolean = true,
        apiKeyConfigured: Boolean = true,
        notificationGranted: Boolean = true,
        deviceProfileVerified: Boolean = true,
    ): AgentStartDecision {
        return AgentStartPolicy.evaluate(
            transportReady = transportReady,
            transportMessage = "transport unavailable",
            routeCRequiresIme = routeCRequiresIme,
            routeCImeIssue = routeCImeIssue,
            accessibilityConnected = accessibilityConnected,
            batteryExempt = batteryExempt,
            apiKeyConfigured = apiKeyConfigured,
            notificationGranted = notificationGranted,
            deviceProfileVerified = deviceProfileVerified,
        )
    }

    @Test
    fun `ready environment opens wechat and starts`() {
        val decision = evaluate()
        assertTrue(decision.ready)
        assertEquals(AgentSetupStep.OPEN_WECHAT, decision.step)
    }

    @Test
    fun `transport is checked before setup steps`() {
        val decision = evaluate(
            transportReady = false,
            routeCRequiresIme = false,
            accessibilityConnected = false,
            batteryExempt = false,
            apiKeyConfigured = false,
        )
        assertFalse(decision.ready)
        assertEquals("transport unavailable", decision.message)
    }

    @Test
    fun `enabled but non default ime opens the picker`() {
        val decision = evaluate(routeCImeIssue = RouteCImeIssue.NOT_DEFAULT)
        assertFalse(decision.ready)
        assertEquals(AgentSetupStep.INPUT_METHOD_SELECT, decision.step)
        assertEquals(
            RouteCImePolicy.startupMessage(RouteCImeIssue.NOT_DEFAULT),
            decision.message,
        )
    }

    @Test
    fun `disabled ime opens input method management`() {
        val decision = evaluate(routeCImeIssue = RouteCImeIssue.NOT_ENABLED)

        assertFalse(decision.ready)
        assertEquals(AgentSetupStep.INPUT_METHOD, decision.step)
    }

    @Test
    fun `accessibility keepalive is required for route b too`() {
        val decision = evaluate(
            routeCRequiresIme = false,
            accessibilityConnected = false,
        )
        assertFalse(decision.ready)
        assertEquals(AgentSetupStep.ACCESSIBILITY, decision.step)
    }

    @Test
    fun `api key remains a hard requirement`() {
        assertEquals(
            AgentSetupStep.API_KEY,
            evaluate(apiKeyConfigured = false).step,
        )
    }

    @Test
    fun `battery exemption is a non blocking reliability warning`() {
        val decision = evaluate(batteryExempt = false)

        assertTrue(decision.ready)
        assertEquals(AgentSetupStep.OPEN_WECHAT, decision.step)
        assertEquals(AgentSetupStep.BATTERY, decision.warnings.single().step)
        assertTrue(decision.message.contains("可能被系统暂停"))
    }

    @Test
    fun `notification permission remains a hard requirement`() {
        val decision = evaluate(notificationGranted = false)

        assertFalse(decision.ready)
        assertEquals(AgentSetupStep.NOTIFICATION, decision.step)
    }

    @Test
    fun `compatibility profile warns without blocking startup`() {
        val decision = evaluate(deviceProfileVerified = false)

        assertTrue(decision.ready)
        assertEquals(
            AgentSetupStep.DEVICE_PROFILE,
            decision.warnings.single().step,
        )
    }
}
