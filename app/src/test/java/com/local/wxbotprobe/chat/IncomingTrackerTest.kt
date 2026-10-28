package com.local.wxbotprobe.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingTrackerTest {
    private fun item(
        text: String,
        kind: BubbleKind = BubbleKind.IN,
        y: Int = 0,
    ) = ScreenItem(text, kind, y = y, xLeft = 200)

    private fun image(
        sha256: String,
        kind: BubbleKind = BubbleKind.IN,
        y: Int = 1_000,
    ) = ScreenItem(
        text = IMAGE_PLACEHOLDER,
        kind = kind,
        y = y,
        xLeft = 200,
        image = sha256.toByteArray(),
        imageWidth = 640,
        imageHeight = 480,
        imageSha256 = sha256,
    )

    @Test
    fun `only the new message is collected`() {
        val tracker = IncomingTracker()
        tracker.collectFresh(listOf(item("有点"), item("不知道吃什么")))
        val fresh = tracker.collectFresh(
            listOf(item("有点"), item("不知道吃什么"), item("你吃了吗")),
        )
        assertEquals(listOf("你吃了吗"), fresh.map { it.text })
    }

    @Test
    fun `repeated sentence counts as two messages`() {
        val tracker = IncomingTracker()
        assertEquals(listOf("在吗"), tracker.collectFresh(listOf(item("在吗"))).map { it.text })
        assertEquals(
            listOf("在吗"),
            tracker.collectFresh(listOf(item("在吗"), item("在吗"))).map { it.text },
        )
    }

    @Test
    fun `short message is collected again after leaving the screen`() {
        val tracker = IncomingTracker()
        assertEquals(
            listOf("好"),
            tracker.collectFresh(listOf(item("好", y = 2140))).map { it.text },
        )
        assertTrue(tracker.collectFresh(emptyList()).isEmpty())
        assertEquals(
            listOf("好"),
            tracker.collectFresh(listOf(item("好", y = 2140))).map { it.text },
        )
    }

    @Test
    fun `short old message reappearing much higher is not fresh`() {
        val tracker = IncomingTracker()
        tracker.collectFresh(listOf(item("为什么呀", y = 2142)))
        tracker.collectFresh(emptyList())
        assertTrue(
            tracker.collectFresh(listOf(item("为什么呀", y = 1087))).isEmpty(),
        )
    }

    @Test
    fun `short message stays duplicate while it remains visible`() {
        val tracker = IncomingTracker()
        tracker.collectFresh(listOf(item("好")))
        assertTrue(tracker.collectFresh(listOf(item("好"))).isEmpty())
        assertTrue(tracker.collectFresh(listOf(item("好"))).isEmpty())
    }

    @Test
    fun `outgoing and unknown items are ignored`() {
        val tracker = IncomingTracker()
        val fresh = tracker.collectFresh(
            listOf(
                item("我发的", BubbleKind.OUT),
                item("发送", BubbleKind.UNKNOWN),
                item("你好"),
            ),
        )
        assertEquals(listOf("你好"), fresh.map { it.text })
    }

    @Test
    fun `own previous reply tail is ignored`() {
        val tracker = IncomingTracker()
        tracker.noteSent("看看冰箱里有什么，随便做点")
        assertTrue(tracker.collectFresh(listOf(item("看看冰箱里有什么"))).isEmpty())
    }

    @Test
    fun `long OCR drift is the same message`() {
        val tracker = IncomingTracker()
        val original = "是全天下最美丽的女人无人与你媲美蒙娜丽莎的微笑中83%的喜悦都源自你我的甜心你太美了浮宫里都应该有你的相片"
        tracker.collectFresh(listOf(item(original)))
        val reread = item(original.replace("浮宫", "卢浮宫"))
        assertTrue(tracker.collectFresh(listOf(reread)).isEmpty())
    }

    @Test
    fun `long OCR drift after leaving the screen is still the same message`() {
        val tracker = IncomingTracker()
        val original = "是全天下最美丽的女人无人与你媲美蒙娜丽莎的微笑中83%的喜悦都源自你我的甜心你太美了浮宫里都应该有你的相片"
        tracker.collectFresh(listOf(item(original, y = 2142)))
        tracker.collectFresh(emptyList())
        val reread = item(original.replace("浮宫", "卢浮宫"), y = 640)
        assertTrue(tracker.collectFresh(listOf(reread)).isEmpty())
    }

    @Test
    fun `bare long tail is the same message`() {
        val tracker = IncomingTracker()
        val original = "是全天下最美丽的女人无人与你媲美蒙娜丽莎的微笑中83%的喜悦都源自你我的甜心你太美了浮宫里都应该有你的相片"
        tracker.collectFresh(listOf(item(original)))
        assertTrue(tracker.collectFresh(listOf(item(original.takeLast(30)))).isEmpty())
    }

    @Test
    fun `short near identical messages remain separate`() {
        val tracker = IncomingTracker()
        tracker.collectFresh(listOf(item("今天下午三点开会")))
        val fresh = tracker.collectFresh(
            listOf(item("今天下午三点开会"), item("明天下午三点开会")),
        )
        assertEquals(listOf("明天下午三点开会"), fresh.map { it.text })
    }

    @Test
    fun `different pure images are collected and hash duplicates are ignored`() {
        val tracker = IncomingTracker()
        tracker.prime(listOf(image("old-image")))

        assertTrue(
            tracker.collectFresh(listOf(image("old-image"))).isEmpty(),
        )
        val fresh = tracker.collectFresh(
            listOf(image("old-image"), image("new-image", y = 1_200)),
        )

        assertEquals(
            listOf("new-image"),
            fresh.map { it.imageSha256 },
        )
    }

    @Test
    fun `outgoing image does not trigger a reply`() {
        val tracker = IncomingTracker()

        assertTrue(
            tracker.collectFresh(
                listOf(image("outgoing-image", kind = BubbleKind.OUT)),
            ).isEmpty(),
        )
    }

    @Test
    fun `same image sent again at the bottom is fresh`() {
        val tracker = IncomingTracker()
        tracker.collectFresh(listOf(image("repeat-image", y = 1_600)))
        tracker.collectFresh(emptyList())

        val fresh = tracker.collectFresh(
            listOf(image("repeat-image", y = 1_620)),
        )

        assertEquals(1, fresh.size)
    }

    @Test
    fun `same image scrolling far upward is not fresh`() {
        val tracker = IncomingTracker()
        tracker.collectFresh(listOf(image("old-image", y = 1_800)))
        tracker.collectFresh(emptyList())

        assertTrue(
            tracker.collectFresh(
                listOf(image("old-image", y = 900)),
            ).isEmpty(),
        )
    }
}
