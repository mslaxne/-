package com.local.wxbotprobe.memory.v2

import com.local.wxbotprobe.bot.PersonaData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2PersonaRevisionTest {
    @Test
    fun `same persona text has stable positive revision`() {
        val persona = PersonaData("说话自然", listOf("你好" to "嗨"))
        val first = MemoryV2PersonaRevision.of(persona)
        val second = MemoryV2PersonaRevision.of(persona)

        assertTrue(first > 0)
        assertEquals(first, second)
    }

    @Test
    fun `persona edits invalidate old revision`() {
        assertNotEquals(
            MemoryV2PersonaRevision.of(PersonaData("简短", emptyList())),
            MemoryV2PersonaRevision.of(PersonaData("温和", emptyList())),
        )
    }

    @Test
    fun `identity policy edits invalidate old revision`() {
        val baseline = PersonaData("简短", emptyList())
        val changed = baseline.copy(
            identityMode = com.local.wxbotprobe.bot.PersonaIdentityMode
                .TRANSPARENT_COMPANION,
            contactAddress = "同事",
        )

        assertNotEquals(
            MemoryV2PersonaRevision.of(baseline),
            MemoryV2PersonaRevision.of(changed),
        )
    }
}
