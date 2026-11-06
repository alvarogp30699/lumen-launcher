package com.alvaro.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Colors {
    val BG = Color.parseColor("#0B0D12")
    val SURFACE = Color.parseColor("#151922")
    val SURFACE2 = Color.parseColor("#1D2330")
    val TEXT = Color.parseColor("#F3F4F7")
    val MUTED = Color.parseColor("#9AA3B5")
    val ACCENT = Color.parseColor("#F5B544")
}

/** Escala todo respecto a un diseño de 1920 px de ancho, sea cual sea la densidad de la tele. */
class Ui(ctx: Context) {
    private val k = ctx.resources.displayMetrics.widthPixels / 1920f
    fun px(v: Int): Int = (v * k).toInt()
    fun textPx(v: Int): Float = v * k
}

fun TextView.setSizePx(ui: Ui, v: Int) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.textPx(v))
}

fun circleIcon(color: Int): Drawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun focusBg(ui: Ui, focused: Boolean, radius: Int = 24): GradientDrawable = GradientDrawable().apply {
    cornerRadius = ui.px(radius).toFloat()
    setColor(if (focused) Colors.SURFACE2 else Colors.SURFACE)
    setStroke(ui.px(4), if (focused) Colors.TEXT else Color.TRANSPARENT)
}

private fun buttonBg(ui: Ui, accent: Boolean, focused: Boolean): GradientDrawable = GradientDrawable().apply {
    cornerRadius = ui.px(16).toFloat()
    setColor(if (accent) Colors.ACCENT else Colors.SURFACE2)
    setStroke(ui.px(4), if (focused) Colors.TEXT else Color.TRANSPARENT)
}

/** Fila de ajustes: título a la izquierda, valor a la derecha. */
fun makeRow(ctx: Context, ui: Ui, title: String, value: String, onClick: () -> Unit): View {
    val left = TextView(ctx).apply {
        text = title
        setTextColor(Colors.TEXT)
        setSizePx(ui, 28)
        typeface = Typeface.DEFAULT_BOLD
    }
    val right = TextView(ctx).apply {
        text = value
        setTextColor(Colors.ACCENT)
        setSizePx(ui, 26)
        typeface = Typeface.DEFAULT_BOLD
    }
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = focusBg(ui, false, 18)
        setPadding(ui.px(28), 0, ui.px(28), 0)
        isFocusable = true
        isClickable = true
        addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(right)
        setOnFocusChangeListener { _, hasFocus -> background = focusBg(ui, hasFocus, 18) }
        setOnClickListener { onClick() }
    }
}

fun makeButton(ctx: Context, ui: Ui, label: String, accent: Boolean, onClick: () -> Unit): View {
    return TextView(ctx).apply {
        text = label
        setTextColor(if (accent) Color.parseColor("#1A1203") else Colors.TEXT)
        setSizePx(ui, 26)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(ui.px(40), ui.px(18), ui.px(40), ui.px(18))
        background = buttonBg(ui, accent, false)
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { _, hasFocus -> background = buttonBg(ui, accent, hasFocus) }
        setOnClickListener { onClick() }
    }
}

fun launchPackage(ctx: Context, pkg: String): Boolean {
    val pm = ctx.packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    if (intent == null) return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
    return true
}

fun makeTile(
    ctx: Context,
    ui: Ui,
    label: String,
    icon: Drawable?,
    dimmed: Boolean,
    onClick: () -> Unit,
    onLong: (() -> Unit)? = null
): View {
    val normal = GradientDrawable().apply {
        cornerRadius = ui.px(24).toFloat()
        setColor(Colors.SURFACE)
        setStroke(ui.px(4), Color.TRANSPARENT)
    }
    val focused = GradientDrawable().apply {
        cornerRadius = ui.px(24).toFloat()
        setColor(Colors.SURFACE2)
        setStroke(ui.px(4), Colors.TEXT)
    }

    val iconView = ImageView(ctx).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.FIT_START
        layoutParams = LinearLayout.LayoutParams(ui.px(64), ui.px(64))
    }
    val spacer = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
    }
    val title = TextView(ctx).apply {
        text = label
        setTextColor(Colors.TEXT)
        setSizePx(ui, 30)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = normal
        setPadding(ui.px(26), ui.px(26), ui.px(26), ui.px(26))
        isFocusable = true
        isClickable = true
        alpha = if (dimmed) 0.5f else 1f
        addView(iconView)
        addView(spacer)
        addView(title)
        setOnFocusChangeListener { _, hasFocus -> background = if (hasFocus) focused else normal }
        setOnClickListener { onClick() }
        if (onLong != null) {
            setOnLongClickListener {
                onLong()
                true
            }
        }
    }
}
