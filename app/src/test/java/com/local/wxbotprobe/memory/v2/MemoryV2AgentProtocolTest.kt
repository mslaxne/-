package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2AgentProtocolTest {
    @Test
    fun `read tool call is parsed without a raw scope argument`() {
        val turn = MemoryV2AgentProtocol.parse(
            """
            {
              "tool":"memory.search",
              "arguments":{"query":"喜欢吃什么","tiers":["A1"],"limit":5}
            }
            """.trimIndent(),
        )

        assertTrue(turn is MemoryV2AgentTurn.ToolCall)
        val call = turn as MemoryV2AgentTurn.ToolCall
        assertEquals("memory.search", call.name)
        assertFalse(call.arguments.has("scope_id"))
        assertEquals(5, call.arguments.getInt("limit"))
    }

    @Test
    fun `commit tool unwraps extraction batch`() {
        val turn = MemoryV2AgentProtocol.parse(
            """
            {
              "tool":"memory.commit_batch",
              "arguments":{"contract":"ExtractionBatch","candidates":[]}
            }
            """.trimIndent(),
        )

        assertTrue(turn is MemoryV2AgentTurn.FinalBatch)
        assertTrue((turn as MemoryV2AgentTurn.FinalBatch).json.contains("ExtractionBatch"))
    }

    @Test
    fun `direct extraction batch remains supported`() {
        val turn = MemoryV2AgentProtocol.parse(
            """{"contract":"ExtractionBatch","candidates":[]}""",
        )

        assertTrue(turn is MemoryV2AgentTurn.FinalBatch)
    }

    @Test
    fun `catalog contains capabilities but no executable SQL`() {
        val catalog = MemoryV2AgentProtocol.databaseCatalog.toString()

        assertTrue(catalog.contains("messages"))
        assertTrue(catalog.contains("memories"))
        assertFalse(catalog.contains("SELECT ", ignoreCase = true))
        assertFalse(catalog.contains("INSERT ", ignoreCase = true))
    }

    @Test
    fun `natural language without protocol is invalid`() {
        assertTrue(
            MemoryV2AgentProtocol.parse("我检查完了，没有需要记录的内容")
                is MemoryV2AgentTurn.Invalid,
        )
    }
}
