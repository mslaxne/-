package com.local.wxbotprobe.bot

object LookalikeText {
    private val ordered = linkedMapOf(
        "ィテ" to "行",
        "イテ" to "行",
        "彳テ" to "行",
        "彳亍" to "行",
        "亻尔" to "你",
        "亻十" to "什",
        "亻也" to "他",
        "亻本" to "体",
        "亻乍" to "作",
        "氵去" to "法",
        "氵殳" to "没",
        "讠十" to "计",
        "讠兑" to "说",
        "讠果" to "课",
        "讠吴" to "误",
        "忄白" to "怕",
        "忄青" to "情",
        "扌丁" to "打",
        "女马" to "妈",
        "女夭" to "妖",
        "女子" to "好",
        "口巴" to "吧",
        "口尼" to "呢",
        "口那" to "哪",
        "白勺" to "的",
        "日月" to "明",
        "木目" to "相",
        "禾口" to "和",
        "王见" to "现",
        "土也" to "地",
        "宀子" to "字",
    ).entries.sortedByDescending { it.key.length }

    fun interpret(text: String): String {
        var result = text
        for ((form, meaning) in ordered) {
            result = result.replace(form, meaning)
        }
        return result
    }
}
