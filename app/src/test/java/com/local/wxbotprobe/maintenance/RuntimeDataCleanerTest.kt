package com.local.wxbotprobe.maintenance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeDataCleanerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `retention removes old unprotected artifacts only`() {
        val external = File(temporaryFolder.root, "external").apply { mkdirs() }
        val internal = File(temporaryFolder.root, "internal-captures").apply { mkdirs() }
        val calibration = File(temporaryFolder.root, "calibration").apply { mkdirs() }
        val logs = File(external, "logs").apply { mkdirs() }
        val diagnostics = File(external, "diagnostics").apply { mkdirs() }
        val old = File(logs, "old.log").apply {
            writeText("old")
            setLastModified(1_000L)
        }
        val recent = File(logs, "recent.log").apply {
            writeText("recent")
            setLastModified(20 * DAY)
        }
        val protected = File(logs, "protected.log").apply {
            writeText("keep")
            setLastModified(1_000L)
        }
        val oldCalibration = File(calibration, "latest.png").apply {
            writeText("old")
            setLastModified(1_000L)
        }
        val oldDiagnostic = File(diagnostics, "report.json").apply {
            writeText("old")
            setLastModified(1_000L)
        }
        File(external, "memory").mkdirs()
        val memory = File(external, "memory/chat.json").apply { writeText("{}") }
        val cleaner = RuntimeDataCleaner(
            externalRoot = external,
            internalCaptures = internal,
            protectedFiles = setOf(protected.absolutePath),
            internalCalibration = calibration,
        )

        val result = cleaner.apply(
            RuntimeDataPolicy(retentionDays = 7, maxBytes = 0),
            nowMillis = 21 * DAY,
        )

        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(protected.exists())
        assertFalse(oldCalibration.exists())
        assertFalse(oldDiagnostic.exists())
        assertTrue(memory.exists())
        assertEquals(3, result.deletedFiles)
    }

    @Test
    fun `capacity removes the oldest eligible artifacts first`() {
        val external = File(temporaryFolder.root, "external").apply { mkdirs() }
        val captures = File(temporaryFolder.root, "captures").apply { mkdirs() }
        val send = File(external, "send").apply { mkdirs() }
        val oldest = File(send, "old.png").apply {
            writeBytes(ByteArray(80))
            setLastModified(1_000L)
        }
        val newer = File(send, "new.png").apply {
            writeBytes(ByteArray(80))
            setLastModified(2_000L)
        }
        val protected = File(send, "active.png").apply {
            writeBytes(ByteArray(80))
            setLastModified(500L)
        }
        val cleaner = RuntimeDataCleaner(
            externalRoot = external,
            internalCaptures = captures,
            protectedFiles = setOf(protected.absolutePath),
        )

        cleaner.apply(
            RuntimeDataPolicy(retentionDays = 0, maxBytes = 170),
            nowMillis = 10_000L,
        )

        assertFalse(oldest.exists())
        assertTrue(newer.exists())
        assertTrue(protected.exists())
    }

    @Test
    fun `clear all preserves protected task sources`() {
        val external = File(temporaryFolder.root, "external").apply { mkdirs() }
        val captures = File(temporaryFolder.root, "captures").apply { mkdirs() }
        val inbox = File(external, "inbox").apply { mkdirs() }
        val pending = File(inbox, "pending.json").apply { writeText("{}") }
        val old = File(inbox, "old.json").apply { writeText("{}") }
        val cleaner = RuntimeDataCleaner(
            externalRoot = external,
            internalCaptures = captures,
            protectedFiles = setOf(pending.absolutePath),
        )

        cleaner.clearAll()

        assertTrue(pending.exists())
        assertFalse(old.exists())
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
