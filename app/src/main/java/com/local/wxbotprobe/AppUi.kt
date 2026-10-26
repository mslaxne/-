package com.local.wxbotprobe

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object AppUi {
    val background: Int
        get() = Color.rgb(244, 246, 247)
    val surface: Int
        get() = Color.rgb(255, 255, 255)
    val surfaceSubtle: Int
        get() = Color.rgb(237, 241, 242)
    val text: Int
        get() = Color.rgb(23, 26, 29)
    val textSecondary: Int
        get() = Color.rgb(98, 107, 115)
    val textMuted: Int
        get() = Color.rgb(138, 147, 155)
    val border: Int
        get() = Color.rgb(221, 227, 231)
    val primary: Int
        get() = Color.rgb(12, 113, 91)
    val primaryPressed: Int
        get() = Color.rgb(9, 88, 69)
    val primarySoft: Int
        get() = Color.rgb(231, 244, 239)
    val running: Int
        get() = Color.rgb(30, 158, 104)
    val runningSoft: Int
        get() = Color.rgb(232, 247, 240)
    val paused: Int
        get() = Color.rgb(182, 123, 0)
    val pausedSoft: Int
        get() = Color.rgb(255, 244, 217)
    val info: Int
        get() = Color.rgb(60, 111, 196)
    val infoSoft: Int
        get() = Color.rgb(234, 241, 252)
    val danger: Int
        get() = Color.rgb(196, 71, 71)
    val dangerSoft: Int
        get() = Color.rgb(251, 236, 236)

    fun textView(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int,
        medium: Boolean = false,
    ): TextView {
        return TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(
                if (medium) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL,
            )
            includeFontPadding = false
        }
    }

    fun rounded(
        context: Context,
        fill: Int,
        radiusDp: Float = 8f,
        strokeColor: Int? = null,
        strokeDp: Float = 1f,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            setColor(fill)
            if (strokeColor != null) {
                setStroke((strokeDp * context.resources.displayMetrics.density).toInt(), strokeColor)
            }
        }
    }

    fun filledRipple(
        context: Context,
        fill: Int,
        radiusDp: Float = 8f,
        strokeColor: Int? = null,
    ): RippleDrawable {
        val content = rounded(context, fill, radiusDp, strokeColor)
        val mask = rounded(context, Color.WHITE, radiusDp)
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(34, 12, 113, 91)),
            content,
            mask,
        )
    }

    fun rowRipple(context: Context): RippleDrawable {
        val mask = rounded(context, Color.WHITE, 8f)
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(28, 12, 113, 91)),
            ColorDrawable(Color.TRANSPARENT),
            mask,
        )
    }

    fun icon(
        context: Context,
        resource: Int,
        tint: Int,
        sizeDp: Int = 20,
    ): ImageView {
        return ImageView(context).apply {
            setImageResource(resource)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }
    }

    fun iconTile(
        context: Context,
        resource: Int,
        tint: Int,
        fill: Int,
        sizeDp: Int = 36,
    ): LinearLayout {
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = rounded(context, fill, 8f)
            addView(icon(context, resource, tint, 20))
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }
    }

    fun divider(context: Context, startMarginDp: Int): View {
        return View(context).apply {
            setBackgroundColor(border)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 1),
            ).apply {
                marginStart = dp(context, startMarginDp)
            }
        }
    }

    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
