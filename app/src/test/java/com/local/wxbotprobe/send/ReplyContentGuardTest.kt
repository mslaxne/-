package com.local.wxbotprobe.send

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplyContentGuardTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun guard(): ReplyContentGuard {
        return ReplyContentGuard(
            file = File(temporaryFolder.root, "reply-content-guard.json"),
            duplicateWindowMillis = 10_000L,
        )
    }

    @Test
    fun `same reply is suppressed for the same chat inside the window`() {
        val guard = guard()
        assertTrue(guard.tryAccept("chat", "收到", "generation-1", 1_000L))
        assertFalse(guard.tryAccept("chat", "收到。", "generation-2", 2_000L))
    }

    @Test
    fun `same generation remains idempotent`() {
        val guard = guard()
        assertTrue(guard.tryAccept("chat", "测什么", "generation-1", 1_000L))
        assertTrue(guard.tryAccept("chat", "测什么", "generation-1", 2_000L))
    }

    @Test
    fun `different chats and expired windows are accepted`() {
        val guard = guard()
        assertTrue(guard.tryAccept("chat-a", "收到", "generation-1", 1_000L))
        assertTrue(guard.tryAccept("chat-b", "收到", "generation-2", 2_000L))
        assertTrue(guard.tryAccept("chat-a", "收到", "generation-3", 11_001L))
    }

    @Test
    fun `accepted reply survives recreation`() {
        assertTrue(guard().tryAccept("chat", "测什么", "generation-1", 1_000L))
        assertFalse(guard().tryAccept("chat", "测什么", "generation-2", 2_000L))
    }
}
