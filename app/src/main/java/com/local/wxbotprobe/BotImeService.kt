package com.local.wxbotprobe

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

class BotImeService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        ImeBridge.attach(this)
    }

    override fun onCreateInputView(): View {
        return View(this).apply {
            isClickable = false
            isFocusable = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1,
            )
        }
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        ImeBridge.noteInputTarget(info?.packageName)
    }

    override fun onFinishInput() {
        ImeBridge.noteInputFinished()
        super.onFinishInput()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return false
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        super.onShowInputRequested(flags, configChange)
        return false
    }

    fun commitExternal(text: String): Boolean {
        val connection = requireWeChatConnection() ?: return false
        val committed = connection.commitText(text, 1)
        if (committed) {
            ImeBridge.noteOperationSuccess()
        } else {
            ImeBridge.noteOperationError("commitText 返回 false。")
        }
        return committed
    }

    fun clearExternal(): Boolean {
        val connection = requireWeChatConnection() ?: return false
        connection.sendKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END),
        )
        connection.sendKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END),
        )
        connection.deleteSurroundingText(10_000, 10_000)
        ImeBridge.noteOperationSuccess()
        return true
    }

    fun readExternalText(): String? {
        val connection = requireWeChatConnection() ?: return null
        val extracted = runCatching {
            connection.getExtractedText(
                ExtractedTextRequest().apply {
                    flags = 0
                    hintMaxChars = 10_000
                    hintMaxLines = 10
                },
                0,
            )?.text?.toString()
        }.getOrNull()
        if (extracted != null) {
            ImeBridge.noteOperationSuccess()
            return extracted
        }
        val surrounding = runCatching {
            connection.getSurroundingText(0, 0, 0)?.text?.toString()
        }.getOrNull()
        if (surrounding != null) {
            ImeBridge.noteOperationSuccess()
            return surrounding
        }
        ImeBridge.noteOperationError("无法读取当前输入框文本。")
        return null
    }

    private fun requireWeChatConnection(): InputConnection? {
        val decision = ImeWritePolicy.check(currentInputEditorInfo?.packageName)
        if (!decision.allowed) {
            ImeBridge.noteOperationError(decision.message)
            ProbeStatus.write(this, decision.message)
            return null
        }
        val connection = currentInputConnection
        if (connection == null) {
            ImeBridge.noteOperationError("当前没有可写入的输入框。")
        }
        return connection
    }

    override fun onDestroy() {
        ImeBridge.detach(this)
        super.onDestroy()
    }

}
