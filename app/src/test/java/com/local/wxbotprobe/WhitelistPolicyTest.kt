package com.local.wxbotprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class WhitelistPolicyTest {
    @Test
    fun `stale identities are removed while existing contacts remain`() {
        assertEquals(
            setOf("张三"),
            WhitelistPolicy.existingOnly(
                savedIdentityKeys = setOf("张三", "布鲁斯"),
                existingIdentityKeys = setOf("张三", "李四"),
            ),
        )
    }

    @Test
    fun `deleting a contact removes its whitelist identity`() {
        assertEquals(
            setOf("张三"),
            WhitelistPolicy.without(
                savedIdentityKeys = setOf("张三", "布鲁斯"),
                identityKey = "布鲁斯",
            ),
        )
    }
}
