package com.local.wxbotprobe

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

object MainUiComponents {
    data class ActionRow(
        val view: View,
        val detail: TextView,
    )

    data class SwitchRow(
        val view: View,
        val control: Switch,
        val detail: TextView,
    )

    fun sectionLabel(context: Context, title: String): TextView {
        return AppUi.textView(context, title, 15f, AppUi.textSecondary, medium = true).apply {
            setPadding(dp(context, 2), 0, dp(context, 2), dp(context, 8))
        }
    }

    fun topPadding(view: View, context: Context, valueDp: Int) {
        view.setPadding(
            view.paddingLeft,
            dp(context, valueDp),
            view.paddingRight,
            view.paddingBottom,
        )
    }

    fun primaryButton(
        context: Context,
        label: String,
        iconResource: Int,
        onClick: () -> Unit,
    ): Button {
        return Button(context).apply {
            text = label
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setCompoundDrawablesWithIntrinsicBounds(iconResource, 0, 0, 0)
            compoundDrawablePadding = dp(context, 8)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = dp(context, 52)
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            stateListAnimator = null
            background = AppUi.filledRipple(context, AppUi.primary, 8f)
            setOnClickListener { onClick() }
        }
    }

    fun secondaryDangerButton(
        context: Context,
        label: String,
        onClick: () -> Unit,
    ): Button {
        return Button(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(AppUi.danger)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stop_line, 0, 0, 0)
            compoundDrawablePadding = dp(context, 8)
            compoundDrawableTintList = ColorStateList.valueOf(AppUi.danger)
            gravity = Gravity.CENTER
            minHeight = dp(context, 48)
            setPadding(dp(context, 14), 0, dp(context, 14), 0)
            stateListAnimator = null
            background = AppUi.filledRipple(context, AppUi.dangerSoft, 8f)
            setOnClickListener { onClick() }
        }
    }

    fun actionRow(
        context: Context,
        iconResource: Int,
        title: String,
        subtitle: String,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): ActionRow {
        val foreground = if (danger) AppUi.danger else AppUi.text
        val iconTint = if (danger) AppUi.danger else AppUi.primary
        val iconFill = if (danger) AppUi.dangerSoft else AppUi.primarySoft
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 68)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 14), dp(context, 10))
            isClickable = true
            isFocusable = true
            background = AppUi.rowRipple(context)
            setOnClickListener { onClick() }
        }
        row.addView(
            AppUi.iconTile(context, iconResource, iconTint, iconFill),
            LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)).apply {
                marginEnd = dp(context, 12)
            },
        )
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        copy.addView(AppUi.textView(context, title, 16f, foreground, medium = true))
        val detail = AppUi.textView(context, subtitle, 13f, AppUi.textSecondary).apply {
            setPadding(0, dp(context, 3), 0, 0)
        }
        copy.addView(detail)
        row.addView(
            copy,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            AppUi.icon(context, R.drawable.ic_chevron_right, AppUi.textMuted, 18),
            LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)).apply {
                marginStart = dp(context, 10)
            },
        )
        return ActionRow(row, detail)
    }

    fun switchRow(
        context: Context,
        iconResource: Int,
        title: String,
        checked: Boolean,
        onText: String = "已开启",
        offText: String = "已关闭",
        onChecked: (Boolean) -> Unit,
    ): SwitchRow {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 68)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 12), dp(context, 10))
        }
        row.addView(
            AppUi.iconTile(context, iconResource, AppUi.primary, AppUi.primarySoft),
            LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)).apply {
                marginEnd = dp(context, 12)
            },
        )
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        copy.addView(AppUi.textView(context, title, 16f, AppUi.text, medium = true))
        val detail = AppUi.textView(
            context,
            if (checked) onText else offText,
            13f,
            AppUi.textSecondary,
        ).apply {
            setPadding(0, dp(context, 3), 0, 0)
        }
        copy.addView(detail)
        row.addView(
            copy,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val control = Switch(context).apply {
            isChecked = checked
            showText = false
            contentDescription = title
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(AppUi.primary, AppUi.border),
            )
            setOnCheckedChangeListener { _, value ->
                detail.text = if (value) onText else offText
                onChecked(value)
            }
        }
        row.addView(
            control,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(context, 8)
            },
        )
        return SwitchRow(row, control, detail)
    }

    fun contactRow(
        context: Context,
        displayName: String,
        detailText: String,
        onManage: () -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 72)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 14), dp(context, 10))
            isClickable = true
            isFocusable = true
            background = AppUi.rowRipple(context)
            setOnClickListener { onManage() }
        }
        val avatar = AppUi.textView(
            context,
            displayName.take(1).ifBlank { "聊" },
            17f,
            AppUi.primary,
            medium = true,
        ).apply {
            gravity = Gravity.CENTER
            background = AppUi.rounded(context, AppUi.primarySoft, 100f)
        }
        row.addView(
            avatar,
            LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)).apply {
                marginEnd = dp(context, 12)
            },
        )
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        copy.addView(AppUi.textView(context, displayName, 16f, AppUi.text, medium = true))
        copy.addView(
            AppUi.textView(context, detailText, 13f, AppUi.textSecondary).apply {
                setPadding(0, dp(context, 3), 0, 0)
            },
        )
        row.addView(
            copy,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_settings)
                setColorFilter(AppUi.textSecondary)
                contentDescription = "管理 $displayName 的记忆"
                setPadding(dp(context, 9), dp(context, 9), dp(context, 9), dp(context, 9))
                background = AppUi.filledRipple(context, AppUi.surfaceSubtle, 10f)
                setOnClickListener { onManage() }
            },
            LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)).apply {
                marginStart = dp(context, 8)
            },
        )
        return row
    }

    fun groupViews(context: Context, rowViews: List<View>): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppUi.rounded(context, AppUi.surface, 8f, AppUi.border)
            clipToOutline = true
            rowViews.forEachIndexed { index, row ->
                addView(row, fullWidth())
                if (index < rowViews.lastIndex) {
                    addView(AppUi.divider(context, 64))
                }
            }
        }
    }

    fun statusSurface(context: Context, content: TextView): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppUi.rounded(context, AppUi.surface, 8f, AppUi.border)
            clipToOutline = true
            addView(content, fullWidth())
        }
    }

    fun fullWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    fun wrapContent(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    fun dialogBuilder(context: Context): AlertDialog.Builder {
        return AlertDialog.Builder(context, R.style.Theme_WxBotProbe_Dialog)
    }

    fun showDestructiveDialog(
        context: Context,
        title: String,
        message: String,
        positiveLabel: String,
        onConfirm: () -> Unit,
    ) {
        val dialog = dialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveLabel) { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(AppUi.danger)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(AppUi.textSecondary)
        }
        dialog.show()
        dialog.window?.setDimAmount(0.28f)
    }

    fun dp(context: Context, value: Int): Int = AppUi.dp(context, value)
}
