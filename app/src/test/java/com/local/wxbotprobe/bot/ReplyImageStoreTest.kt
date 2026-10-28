package com.local.wxbotprobe.bot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplyImageStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): ReplyImageStore {
        return ReplyImageStore(File(temporaryFolder.root, "reply-images"))
    }

    @Test
    fun `image is stored by hash and loaded with metadata`() {
        val store = store()
        val source = ChatImage(
            bytes = byteArrayOf(1, 2, 3, 4),
            width = 640,
            height = 480,
            sha256 = "image-a",
        )

        val persisted = store.persist(source)
        val loaded = store.load(
            reference = persisted.reference,
            width = source.width,
            height = source.height,
            mimeType = source.mimeType,
            sha256 = source.sha256,
        )

        assertEquals("image-a.jpg", persisted.reference)
        assertNotNull(loaded)
        assertTrue(source.bytes.contentEquals(loaded!!.bytes))
        assertEquals(source.sha256, loaded.sha256)
    }

    @Test
    fun `full screen png keeps its mime type and extension`() {
        val store = store()
        val source = ChatImage(
            bytes = byteArrayOf(1, 2, 3),
            width = 1080,
            height = 2400,
            mimeType = "image/png",
            sha256 = "screen-a",
        )

        val persisted = store.persist(source)
        val loaded = store.load(
            reference = persisted.reference,
            width = source.width,
            height = source.height,
            mimeType = source.mimeType,
            sha256 = source.sha256,
        )

        assertEquals("screen-a.png", persisted.reference)
        assertEquals("image/png", persisted.mimeType)
        assertEquals(source, loaded)
    }

    @Test
    fun `path traversal reference is rejected`() {
        assertNull(store().resolve("../outside.jpg"))
        assertNull(store().resolve("/tmp/outside.jpg"))
    }

    @Test
    fun `cleanup removes only unreferenced images`() {
        val store = store()
        val keep = store.persist(
            ChatImage(byteArrayOf(1), 10, 10, sha256 = "keep"),
        )
        val remove = store.persist(
            ChatImage(byteArrayOf(2), 10, 10, sha256 = "remove"),
        )

        val deleted = store.cleanupUnreferenced(setOf(keep.file.absolutePath))

        assertEquals(1, deleted)
        assertTrue(keep.file.isFile)
        assertTrue(!remove.file.exists())
    }
}
