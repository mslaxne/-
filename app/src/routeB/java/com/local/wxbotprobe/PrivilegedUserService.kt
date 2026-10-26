package com.local.wxbotprobe

import android.system.Os

class PrivilegedUserService : IPrivilegedService.Stub() {
    override fun destroy() {
        System.exit(0)
    }

    override fun screenshot(path: String) {
        runCommand("/system/bin/screencap", "-p", path)
    }

    override fun tap(x: Int, y: Int) {
        runCommand("/system/bin/input", "tap", x.toString(), y.toString())
    }

    override fun uid(): Int = Os.getuid()

    override fun imeEnable(id: String) {
        runCommand("/system/bin/ime", "enable", id)
    }

    override fun imeSet(id: String) {
        runCommand("/system/bin/ime", "set", id)
    }

    override fun keyEvent(keyCode: Int) {
        runCommand("/system/bin/input", "keyevent", keyCode.toString())
    }

    override fun foregroundPackage(): String {
        val output = runCommandCapture(
            "/system/bin/sh",
            "-c",
            "dumpsys activity activities | grep -m1 topResumedActivity",
        )
        return Regex("topResumedActivity=ActivityRecord\\{[^\\s]+\\s+[^\\s]+\\s+([^/\\s]+)/")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun runCommand(vararg command: String) {
        val output = runCommandCapture(*command)
        if (output.startsWith("__EXIT__")) {
            throw IllegalStateException(output.removePrefix("__EXIT__"))
        }
    }

    private fun runCommandCapture(vararg command: String): String {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            return "__EXIT__Command failed ($exitCode): ${command.joinToString(" ")}\n$output"
        }
        return output
    }
}
