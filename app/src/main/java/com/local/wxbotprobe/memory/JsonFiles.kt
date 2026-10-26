package com.local.wxbotprobe.memory

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun writeTextAtomically(file: File, text: String) {
    val parent = file.parentFile ?: throw IllegalArgumentException("file has no parent")
    if (!parent.exists() && !parent.mkdirs()) {
        throw IllegalStateException("unable to create ${parent.absolutePath}")
    }
    val temporary = File(parent, "${file.name}.tmp")
    FileOutputStream(temporary).use {
        it.write(text.toByteArray(Charsets.UTF_8))
        it.fd.sync()
    }
    Files.move(
        temporary.toPath(),
        file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
    )
}

internal fun readJsonFile(file: File): String? {
    if (!file.isFile) {
        return null
    }
    return runCatching { file.readText() }.getOrNull()
}
