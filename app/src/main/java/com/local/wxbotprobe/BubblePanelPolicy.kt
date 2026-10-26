package com.local.wxbotprobe

enum class BubblePanelAction {
    START_PAUSE,
    STOP,
    COLLAPSE,
    CLOSE,
}

object BubblePanelPolicy {
    fun visualOrder(side: String): List<BubblePanelAction> {
        return if (side == "left") {
            listOf(
                BubblePanelAction.START_PAUSE,
                BubblePanelAction.STOP,
                BubblePanelAction.COLLAPSE,
                BubblePanelAction.CLOSE,
            )
        } else {
            listOf(
                BubblePanelAction.CLOSE,
                BubblePanelAction.COLLAPSE,
                BubblePanelAction.START_PAUSE,
                BubblePanelAction.STOP,
            )
        }
    }

    fun actionAt(side: String, zone: Int): BubblePanelAction {
        val order = visualOrder(side)
        return order[zone.coerceIn(0, order.lastIndex)]
    }
}

object BubbleClosePolicy {
    fun shouldPause(state: AgentRunState): Boolean {
        return state == AgentRunState.RUNNING
    }
}
