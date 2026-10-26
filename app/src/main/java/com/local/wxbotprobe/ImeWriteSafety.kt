package com.local.wxbotprobe

data class ImeWriteDecision(
    val allowed: Boolean,
    val targetPackage: String,
    val message: String,
)

object ImeWritePolicy {
    fun check(targetPackage: String?): ImeWriteDecision {
        val target = targetPackage.orEmpty()
        return when {
            target.isBlank() -> ImeWriteDecision(
                allowed = false,
                targetPackage = target,
                message = "当前输入目标未知，已拒绝写入。",
            )
            target != RouteCImePolicy.WECHAT_PACKAGE -> ImeWriteDecision(
                allowed = false,
                targetPackage = target,
                message = "当前输入目标是 $target，不是微信，已拒绝写入。",
            )
            else -> ImeWriteDecision(
                allowed = true,
                targetPackage = target,
                message = "",
            )
        }
    }
}
