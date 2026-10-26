package com.local.wxbotprobe.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTitleWarningPolicyTest {
    @Test
    fun `chinese member count suffix is a soft warning`() {
        assertTrue(GroupTitleWarningPolicy.isLikelyGroupTitle("测试群（5）"))
        assertTrue(GroupTitleWarningPolicy.isLikelyGroupTitle("测试群 ( 12 )"))
    }

    @Test
    fun `ordinary chat titles are not warned`() {
        assertFalse(GroupTitleWarningPolicy.isLikelyGroupTitle("A"))
        assertFalse(GroupTitleWarningPolicy.isLikelyGroupTitle("项目 5"))
        assertFalse(GroupTitleWarningPolicy.isLikelyGroupTitle("括号（不是数字）"))
        assertFalse(GroupTitleWarningPolicy.isLikelyGroupTitle(""))
    }
}
