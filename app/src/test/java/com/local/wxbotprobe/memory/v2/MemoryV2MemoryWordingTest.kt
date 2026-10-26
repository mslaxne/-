package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryV2MemoryWordingTest {
    @Test
    fun `contact and current agent use conversational roles`() {
        assertEquals(
            "对方表达了对你的思念。",
            MemoryV2MemoryWording.forCurrentPersona(
                "用户表达了对agent的思念。",
            ),
        )
    }

    @Test
    fun `agent matching is case insensitive but not embedded in names`() {
        assertEquals(
            "对方说你会来。",
            MemoryV2MemoryWording.forCurrentPersona(
                "用户说 Agent 会来。",
            ),
        )
        assertEquals(
            "对方提到 agent-team。",
            MemoryV2MemoryWording.forCurrentPersona(
                "用户提到 agent-team。",
            ),
        )
    }

    @Test
    fun `third party robot wording remains untouched`() {
        assertEquals(
            "对方的朋友提到机器人。",
            MemoryV2MemoryWording.forCurrentPersona(
                "用户的朋友提到机器人。",
            ),
        )
    }
}
