package com.local.wxbotprobe.maintenance

import android.content.Context
import com.local.wxbotprobe.bot.ReplyRetryStore
import com.local.wxbotprobe.send.DeliveryStatus
import com.local.wxbotprobe.send.DeliveryStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class RuntimeDataPolicy(
    val retentionDays: Int,
    val maxBytes: Long,
) {
    val enabled: Boolean
        get() = retentionDays > 0 || maxBytes > 0
}

data class RuntimeCleanupResult(
    val deletedFiles: Int = 0,
    val deletedBytes: Long = 0,
)

object RuntimeDataSettings {
    private const val PREFS = "runtime_data_settings"
    private const val KEY_RETENTION_DAYS = "retention_days"
    private const val KEY_MAX_BYTES = "max_bytes"

    val RETENTION_OPTIONS = listOf(0, 7, 30, 90)
    val SIZE_OPTIONS_MB = listOf(0, 100, 250, 500)

    fun policy(context: Context): RuntimeDataPolicy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RuntimeDataPolicy(
            retentionDays = prefs.getInt(KEY_RETENTION_DAYS, 0)
                .takeIf { it in RETENTION_OPTIONS }
                ?: 0,
            maxBytes = (
                prefs.getInt(KEY_MAX_BYTES, 0)
                    .takeIf { it in SIZE_OPTIONS_MB }
                    ?: 0
                ).toLong() * 1024L * 1024L,
        )
    }

    fun setPolicy(context: Context, policy: RuntimeDataPolicy) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RETENTION_DAYS, policy.retentionDays)
            .putInt(
                KEY_MAX_BYTES,
                (policy.maxBytes / (1024L * 1024L)).toInt(),
            )
            .apply()
    }

    fun retentionLabel(days: Int): String {
        return if (days <= 0) "永久保留" else "保留 $days 天"
    }

    fun sizeLabel(maxBytes: Long): String {
        val mb = (maxBytes / (1024L * 1024L)).toInt()
        return if (mb <= 0) "不限容量" else "最多 $mb MB"
    }
}

class RuntimeDataCleaner(
    private val externalRoot: File,
    internalCaptures: File,
    private val protectedFiles: Set<String>,
    private val internalCalibration: File? = null,
) {
    private val targetDirectories = buildList {
        listOf(
            "benchmarks",
            "diagnostics",
            "inbox",
            "logs",
            "monitor",
            "ocr",
            "outbox",
            "replies",
            "screens",
            "send",
            "shizuku-captures",
            "transport-benchmark",
            "transport-captures",
        ).forEach { add(File(externalRoot, it)) }
        add(internalCaptures)
        internalCalibration?.let(::add)
    }

    fun totalBytes(): Long {
        return targetFiles().sumOf { it.length() }
    }

    fun apply(
        policy: RuntimeDataPolicy,
        nowMillis: Long = System.currentTimeMillis(),
    ): RuntimeCleanupResult {
        if (!policy.enabled) {
            return RuntimeCleanupResult()
        }
        var deleted = RuntimeCleanupResult()
        if (policy.retentionDays > 0) {
            val cutoff = nowMillis - policy.retentionDays * DAY_MILLIS
            deleted = deleted.plus(
                deleteWhere { it.lastModified() < cutoff },
            )
        }
        if (policy.maxBytes > 0) {
            val remaining = eligibleFiles().sortedBy { it.lastModified() }
            var total = targetFiles().sumOf { it.length() }
            for (file in remaining) {
                if (total <= policy.maxBytes) {
                    break
                }
                val length = file.length()
                if (file.delete()) {
                    deleted = deleted.plus(deletedFiles = 1, deletedBytes = length)
                    total -= length
                }
            }
        }
        removeEmptyDirectories()
        return deleted
    }

    fun clearAll(): RuntimeCleanupResult {
        val result = deleteWhere { true }
        removeEmptyDirectories()
        return result
    }

    private fun deleteWhere(predicate: (File) -> Boolean): RuntimeCleanupResult {
        var files = 0
        var bytes = 0L
        eligibleFiles().filter(predicate).forEach { file ->
            val length = file.length()
            if (file.delete()) {
                files++
                bytes += length
            }
        }
        return RuntimeCleanupResult(files, bytes)
    }

    private fun eligibleFiles(): List<File> {
        return targetFiles().filterNot { it.isProtected() }
    }

    private fun targetFiles(): List<File> {
        return targetDirectories
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile }
                    .toList()
            }
            .distinctBy { it.absolutePath }
    }

    private fun File.isProtected(): Boolean {
        return normalizedPath() in protectedFiles
    }

    private fun removeEmptyDirectories() {
        targetDirectories.forEach { root ->
            root.walkBottomUp()
                .filter { it.isDirectory && it != root }
                .forEach { it.delete() }
        }
    }

    private fun RuntimeCleanupResult.plus(
        other: RuntimeCleanupResult = RuntimeCleanupResult(),
        deletedFiles: Int = other.deletedFiles,
        deletedBytes: Long = other.deletedBytes,
    ): RuntimeCleanupResult {
        return RuntimeCleanupResult(
            deletedFiles = this.deletedFiles + deletedFiles,
            deletedBytes = this.deletedBytes + deletedBytes,
        )
    }

    companion object {
        private const val DAY_MILLIS = 86_400_000L

        fun forContext(context: Context): RuntimeDataCleaner {
            val externalRoot = context.getExternalFilesDir(null) ?: context.filesDir
            val protected = mutableSetOf<String>()
            DeliveryStore(File(externalRoot, "outbox")).all().forEach { delivery ->
                if (delivery.status != DeliveryStatus.SENT) {
                    protected += File(
                        externalRoot,
                        "outbox/${delivery.id}.json",
                    ).normalizedPath()
                    if (delivery.sourceReplyFile.isNotBlank()) {
                        protected += File(delivery.sourceReplyFile).normalizedPath()
                    }
                }
            }
            ReplyRetryStore(File(context.filesDir, "reply-retries")).all().forEach { task ->
                protected += File(
                    context.filesDir,
                    "reply-retries/${task.id}.json",
                ).normalizedPath()
                if (task.sourceFile.isNotBlank()) {
                    protected += File(task.sourceFile).normalizedPath()
                }
            }
            return RuntimeDataCleaner(
                externalRoot = externalRoot,
                internalCaptures = File(context.filesDir, "captures"),
                protectedFiles = protected,
                internalCalibration = File(context.filesDir, "calibration"),
            )
        }

        private fun File.normalizedPath(): String {
            return absoluteFile.normalize().path
        }
    }
}

object RuntimeDataMaintenance {
    private val started = AtomicBoolean(false)

    fun runPolicyOnce(context: Context) {
        val policy = RuntimeDataSettings.policy(context)
        if (!policy.enabled || !started.compareAndSet(false, true)) {
            return
        }
        val appContext = context.applicationContext
        Thread(
            {
                runCatching {
                    RuntimeDataCleaner.forContext(appContext).apply(policy)
                }
            },
            "handora-runtime-cleanup",
        ).apply { isDaemon = true }.start()
    }
}
