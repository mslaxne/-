package com.local.wxbotprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunPolicyTest {
    @Test
    fun `pause keeps the session and stop enters stopping`() {
        val paused = AgentRunPolicy.next(AgentRunState.RUNNING, AgentRunAction.PAUSE)
        assertEquals(AgentRunState.PAUSED, paused)

        val stopping = AgentRunPolicy.next(AgentRunState.PAUSED, AgentRunAction.STOP)
        assertEquals(AgentRunState.STOPPING, stopping)
    }

    @Test
    fun `process restart never presents a stale running state`() {
        assertEquals(
            AgentRunState.PAUSED,
            AgentRunPolicy.afterProcessRestart(AgentRunState.RUNNING),
        )
        assertEquals(
            AgentRunState.PAUSED,
            AgentRunPolicy.afterProcessRestart(AgentRunState.STOPPING),
        )
        assertEquals(
            AgentRunState.PAUSED,
            AgentRunPolicy.afterProcessRestart(AgentRunState.PAUSED),
        )
        assertEquals(
            AgentRunState.OFF,
            AgentRunPolicy.afterProcessRestart(AgentRunState.OFF),
        )
    }

    @Test
    fun `only running state can poll or auto send`() {
        assertTrue(AgentRunPolicy.canAutoSend(AgentRunState.RUNNING, running = true))
        assertFalse(AgentRunPolicy.canAutoSend(AgentRunState.PAUSED, running = false))
        assertFalse(AgentRunPolicy.canAutoSend(AgentRunState.STOPPING, running = false))
        assertFalse(AgentRunPolicy.canAutoSend(AgentRunState.RUNNING, running = false))
    }
}
