package com.local.wxbotprobe.shutdown

import com.local.wxbotprobe.AgentRunState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutdownPolicyTest {
    @Test
    fun runningAgentRequiresSummary() {
        assertTrue(ShutdownPolicy.shouldSummarize(AgentRunState.RUNNING))
    }

    @Test
    fun pausedAgentRequiresSummary() {
        assertTrue(ShutdownPolicy.shouldSummarize(AgentRunState.PAUSED))
    }

    @Test
    fun stoppingAgentRequiresIndependentSummary() {
        assertTrue(ShutdownPolicy.shouldSummarize(AgentRunState.STOPPING))
    }

    @Test
    fun stoppedAgentCanExitImmediately() {
        assertFalse(ShutdownPolicy.shouldSummarize(AgentRunState.OFF))
    }
}
