package com.local.wxbotprobe

enum class AgentRunState {
    OFF,
    RUNNING,
    PAUSED,
    STOPPING,
}

enum class AgentRunAction {
    START,
    PAUSE,
    RESUME,
    STOP,
    STOPPED,
    SERVICE_DESTROYED,
}

object AgentRunPolicy {
    fun next(current: AgentRunState, action: AgentRunAction): AgentRunState {
        return when (action) {
            AgentRunAction.START ->
                if (current == AgentRunState.PAUSED) AgentRunState.RUNNING
                else if (current == AgentRunState.OFF) AgentRunState.RUNNING
                else current
            AgentRunAction.PAUSE ->
                if (current == AgentRunState.RUNNING) AgentRunState.PAUSED else current
            AgentRunAction.RESUME ->
                if (current == AgentRunState.PAUSED) AgentRunState.RUNNING else current
            AgentRunAction.STOP ->
                if (current == AgentRunState.OFF) AgentRunState.OFF else AgentRunState.STOPPING
            AgentRunAction.STOPPED -> AgentRunState.OFF
            AgentRunAction.SERVICE_DESTROYED -> when (current) {
                AgentRunState.RUNNING -> AgentRunState.PAUSED
                AgentRunState.PAUSED,
                AgentRunState.STOPPING,
                AgentRunState.OFF,
                -> current
            }
        }
    }

    fun afterProcessRestart(persisted: AgentRunState): AgentRunState {
        return when (persisted) {
            AgentRunState.RUNNING,
            AgentRunState.STOPPING,
            -> AgentRunState.PAUSED
            AgentRunState.OFF,
            AgentRunState.PAUSED,
            -> persisted
        }
    }

    fun canPoll(state: AgentRunState, running: Boolean): Boolean {
        return running && state == AgentRunState.RUNNING
    }

    fun canAutoSend(state: AgentRunState, running: Boolean): Boolean {
        return canPoll(state, running)
    }
}
