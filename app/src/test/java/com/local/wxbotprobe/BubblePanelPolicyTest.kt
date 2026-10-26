package com.local.wxbotprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePanelPolicyTest {
    @Test
    fun `left panel puts close at the outer edge`() {
        assertEquals(
            listOf(
                BubblePanelAction.START_PAUSE,
                BubblePanelAction.STOP,
                BubblePanelAction.COLLAPSE,
                BubblePanelAction.CLOSE,
            ),
            BubblePanelPolicy.visualOrder("left"),
        )
    }

    @Test
    fun `right panel mirrors close to the outer edge`() {
        assertEquals(BubblePanelAction.CLOSE, BubblePanelPolicy.actionAt("right", 0))
        assertEquals(BubblePanelAction.STOP, BubblePanelPolicy.actionAt("right", 3))
        assertEquals(BubblePanelAction.CLOSE, BubblePanelPolicy.actionAt("left", 99))
    }

    @Test
    fun `closing pauses only a running agent`() {
        assertTrue(BubbleClosePolicy.shouldPause(AgentRunState.RUNNING))
        assertFalse(BubbleClosePolicy.shouldPause(AgentRunState.PAUSED))
        assertFalse(BubbleClosePolicy.shouldPause(AgentRunState.STOPPING))
        assertFalse(BubbleClosePolicy.shouldPause(AgentRunState.OFF))
    }

    @Test
    fun `small movement remains a tap and larger movement starts dragging`() {
        assertFalse(BubbleDragPolicy.moved(dx = 4, dy = -6, touchSlop = 8))
        assertTrue(BubbleDragPolicy.moved(dx = 9, dy = 0, touchSlop = 8))
        assertTrue(BubbleDragPolicy.moved(dx = 0, dy = -9, touchSlop = 8))
    }

    @Test
    fun `dragged coordinate stays inside the screen bounds`() {
        assertEquals(10, BubbleDragPolicy.coordinate(100, -500, 10, 800))
        assertEquals(800, BubbleDragPolicy.coordinate(700, 500, 10, 800))
        assertEquals(250, BubbleDragPolicy.coordinate(200, 50, 10, 800))
    }
}
