package com.local.wxbotprobe.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SendAuthorizationPolicyTest {
    @Test
    fun `first recognized private chat becomes the active authorization`() {
        val result = SendAuthorizationPolicy.bind(
            chatFingerprint = 42L,
            chatTitle = "联系人",
            isGroup = false,
        )

        assertNotNull(result.binding)
        assertEquals(42L, result.binding?.chatFingerprint)
        assertEquals("联系人", result.binding?.normalizedTitle)
    }

    @Test
    fun `group and unknown chats cannot become active authorization`() {
        assertEquals(
            SendAuthorizationIssue.GROUP_CHAT,
            SendAuthorizationPolicy.bind(42L, "聊天群（8）", isGroup = false).issue,
        )
        assertEquals(
            SendAuthorizationIssue.UNRECOGNIZED_CHAT,
            SendAuthorizationPolicy.bind(0L, "联系人", isGroup = false).issue,
        )
    }

    @Test
    fun `chat switch invalidates the current authorization`() {
        val binding = SendAuthorizationPolicy.bind(42L, "联系人", isGroup = false).binding!!

        assertNull(SendAuthorizationPolicy.check(binding, 42L, "联系人"))
        assertEquals(
            SendAuthorizationIssue.CHAT_CHANGED,
            SendAuthorizationPolicy.check(binding, 99L, "联系人"),
        )
        assertEquals(
            SendAuthorizationIssue.CHAT_CHANGED,
            SendAuthorizationPolicy.check(binding, 42L, "另一个人"),
        )
    }
}
