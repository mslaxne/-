package com.local.wxbotprobe.bot

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class PersistedReplyImage(
    val file: File,
    val reference: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

class ReplyImageStore(
    private val directory: File,
) {
    fun persist(image: ChatImage): PersistedReplyImage {
        val sha256 = image.sha256.ifBlank {
            MessageDigest.getInstance("SHA-256")
                .digest(image.bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        val extension = when (image.mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val reference = "$sha256.$extension"
        val output = File(directory, reference)
        if (!output.isFile || output.length() != image.bytes.size.toLong()) {
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("无法创建图片附件目录")
            }
            val temporary = File(directory, "$reference.tmp")
            FileOutputStream(temporary).use {
                it.write(image.bytes)
                it.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
        return PersistedReplyImage(
            file = output,
            reference = reference,
            sha256 = sha256,
            width = image.width,
            height = image.height,
            mimeType = image.mimeType,
        )
    }

    fun load(
        reference: String,
        width: Int,
        height: Int,
        mimeType: String,
        sha256: String,
    ): ChatImage? {
        val file = resolve(reference) ?: return null
        if (!file.isFile || width <= 0 || height <= 0) {
            return null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) {
            return null
        }
        return runCatching {
            ChatImage(
                bytes = bytes,
                width = width,
                height = height,
                mimeType = mimeType.ifBlank { "image/jpeg" },
                sha256 = sha256.ifBlank {
                    MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                },
            )
        }.getOrNull()
    }

    fun resolve(reference: String): File? {
        if (reference.isBlank()) {
            return null
        }
        val candidate = File(directory, File(reference).name)
        val root = directory.absoluteFile.normalize()
        val resolved = candidate.absoluteFile.normalize()
        return resolved.takeIf {
            it.parentFile == root &&
                it.name == File(reference).name &&
                !reference.contains('/') &&
                !reference.contains('\\')
        }
    }

    fun cleanupUnreferenced(
        referencedPaths: Set<String>,
        minimumAgeMillis: Long = 0L,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        if (!directory.isDirectory) {
            return 0
        }
        val normalized = referencedPaths.mapTo(mutableSetOf()) {
            File(it).absoluteFile.normalize().path
        }
        return directory.listFiles()
            .orEmpty()
            .count { file ->
                file.isFile &&
                    file.absoluteFile.normalize().path !in normalized &&
                    nowMillis - file.lastModified() >= minimumAgeMillis &&
                    file.delete()
            }
    }
}
