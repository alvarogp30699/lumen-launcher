package com.alvaro.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Colors {
    val SURFACE = Color.parseColor("#151922")
    val SURFACE2 = Color.parseColor("#1D2330")
    val TEXT = Color.parseColor("#F3F4F7")
    val MUTED = Color.parseColor("#9AA3B5")

    /** Color de acento elegido (Ajustes o página del móvil) */
    @Volatile
    var ACCENT = Color.parseColor("#F5B544")

    val ACCENTS = listOf(
        "#F5B544" to "Ámbar",
        "#E10600" to "Rojo F1",
        "#4C9AFF" to "Azul",
        "#3DDC84" to "Verde",
        "#B18CFF" to "Violeta"
    )

    fun apply(hex: String) {
        ACCENT = try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            Color.parseColor("#F5B544")
        }
    }

    fun accentName(hex: String): String = ACCENTS.firstOrNull { it.first.equals(hex, true) }?.second ?: "Ámbar"

    /** Texto legible encima de un color: oscuro sobre colores claros, blanco sobre oscuros. */
    fun onColor(c: Int = ACCENT): Int {
        val l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
        return if (l > 0.6) Color.parseColor("#1A1203") else Color.WHITE
    }

    fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)
}

/** Escala todo respecto a un diseño de 1920 px de ancho, sea cual sea la densidad de la tele. */
class Ui(ctx: Context) {
    private val k = ctx.resources.displayMetrics.widthPixels / 1920f
    fun px(v: Int): Int = (v * k).toInt()
    fun textPx(v: Int): Float = v * k
}

fun TextView.setSizePx(ui: Ui, v: Int) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.textPx(v))
    // todos los textos usan la fuente de Lumen (Inter); la negrita se respeta
    val t = typeface
    if (t !== Fonts.bold && t !== Fonts.light) typeface = if (t?.isBold == true) Fonts.bold else Fonts.regular
}

/** Fuente propia (Inter, dentro de la app). Si no se pudiera leer, se usa la del sistema. */
object Fonts {
    var regular: Typeface = Typeface.DEFAULT
        private set
    var bold: Typeface = Typeface.DEFAULT_BOLD
        private set
    var light: Typeface = Typeface.DEFAULT
        private set

    fun init(ctx: Context) {
        try {
            regular = Typeface.createFromAsset(ctx.assets, "fonts/inter-regular.otf")
            bold = Typeface.createFromAsset(ctx.assets, "fonts/inter-semibold.otf")
            light = Typeface.createFromAsset(ctx.assets, "fonts/inter-light.otf")
        } catch (e: Exception) {
            regular = Typeface.DEFAULT
            bold = Typeface.DEFAULT_BOLD
            light = Typeface.DEFAULT
        }
    }
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
        typeface = Fonts.bold
    }
    val right = TextView(ctx).apply {
        text = value
        setTextColor(Colors.ACCENT)
        setSizePx(ui, 26)
        typeface = Fonts.bold
    }
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // los dos fondos se crean una vez: moverse por la lista no genera basura de memoria
        val off = focusBg(ui, false, 18)
        val on = focusBg(ui, true, 18)
        background = off
        setPadding(ui.px(28), 0, ui.px(28), 0)
        isFocusable = true
        isClickable = true
        addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(right)
        setOnFocusChangeListener { _, hasFocus -> background = if (hasFocus) on else off }
        setOnClickListener { onClick() }
    }
}

fun makeButton(ctx: Context, ui: Ui, label: String, accent: Boolean, onClick: () -> Unit): View {
    return TextView(ctx).apply {
        text = label
        setTextColor(if (accent) Colors.onColor() else Colors.TEXT)
        setSizePx(ui, 26)
        typeface = Fonts.bold
        gravity = Gravity.CENTER
        setPadding(ui.px(40), ui.px(18), ui.px(40), ui.px(18))
        val off = buttonBg(ui, accent, false)
        val on = buttonBg(ui, accent, true)
        background = off
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { _, hasFocus -> background = if (hasFocus) on else off }
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

fun roundRect(ui: Ui, color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
    cornerRadius = ui.px(radius).toFloat()
    setColor(color)
}

fun ring(ui: Ui, color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
    cornerRadius = ui.px(radius).toFloat()
    setColor(Color.TRANSPARENT)
    setStroke(ui.px(4), color)
}

/** Efecto de las teles: lo enfocado crece un poco. Lo hace la GPU, apenas gasta. */
fun focusPop(v: View, has: Boolean, scale: Float = 1.08f) {
    v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(140).start()
}

/** Fondo de las pantallas principales: degradado oscuro con un brillo suave del color de acento arriba a la derecha. */
fun homeBackground(ui: Ui, glowColor: Int = Colors.ACCENT): Drawable {
    val base = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor("#141925"), Color.parseColor("#08090D"))
    )
    val glow = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = ui.px(1200).toFloat()
        setGradientCenter(0.92f, 0.0f)
        colors = intArrayOf(Colors.alpha(glowColor, 0x40), Colors.alpha(glowColor, 0x00))
    }
    return LayerDrawable(arrayOf<Drawable>(base, glow))
}

/** Icono vectorial blanco (o oscuro, según el fondo) dentro de un cuadrado redondeado de color. */
fun iconBadge(ctx: Context, ui: Ui, res: Int, color: Int, size: Int): View = FrameLayout(ctx).apply {
    background = roundRect(ui, color, size / 4)
    val pad = ui.px(size * 22 / 100)
    addView(ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(Colors.onColor(color))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
        setMargins(pad, pad, pad, pad)
    })
}

/** Icono hecho con un símbolo dentro de un cuadrado redondeado de color. */
fun glyphBadge(ctx: Context, ui: Ui, glyph: String, color: Int, size: Int): TextView = TextView(ctx).apply {
    text = glyph
    gravity = Gravity.CENTER
    setTextColor(Colors.onColor(color))
    setSizePx(ui, size * 46 / 100)
    typeface = Fonts.bold
    includeFontPadding = false
    background = roundRect(ui, color, size / 4)
}

/**
 * Mosaico de app. Con banner (las apps de Android TV lo traen) ocupa todo el mosaico;
 * si no, icono o símbolo arriba y nombre abajo. Al enfocarlo crece y se marca con un borde.
 */
fun makeTile(
    ctx: Context,
    ui: Ui,
    label: String,
    icon: Drawable?,
    dimmed: Boolean,
    onClick: () -> Unit,
    onLong: (() -> Unit)? = null,
    banner: Drawable? = null,
    glyph: String? = null,
    glyphColor: Int = Colors.ACCENT,
    compact: Boolean = false,
    iconRes: Int? = null
): View {
    val radius = if (compact) 20 else 24
    val normal = roundRect(ui, Colors.SURFACE, radius)
    val focused = roundRect(ui, Colors.SURFACE2, radius)
    val edge = ring(ui, Colors.TEXT, radius)

    val tile = FrameLayout(ctx).apply {
        background = normal
        clipToOutline = true
        isFocusable = true
        isClickable = true
        alpha = if (dimmed) 0.45f else 1f
        contentDescription = label
    }

    var nameStrip: View? = null
    if (banner != null) {
        tile.addView(ImageView(ctx).apply {
            setImageDrawable(banner)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // nombre de la app sobre una franja oscura: solo aparece al enfocar
        val strip = TextView(ctx).apply {
            text = label
            setTextColor(Colors.TEXT)
            setSizePx(ui, 24)
            typeface = Fonts.bold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.BOTTOM
            setPadding(ui.px(18), ui.px(30), ui.px(18), ui.px(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(230, 8, 9, 13), Color.argb(0, 8, 9, 13))
            )
            visibility = View.GONE
        }
        tile.addView(strip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        nameStrip = strip
    } else {
        val iconSize = if (compact) 46 else 66
        val pad = if (compact) 16 else 22
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(pad), ui.px(pad), ui.px(pad), ui.px(pad))
        }
        val iconView: View = if (iconRes != null) {
            iconBadge(ctx, ui, iconRes, glyphColor, iconSize)
        } else if (glyph != null) {
            glyphBadge(ctx, ui, glyph, glyphColor, iconSize)
        } else {
            ImageView(ctx).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_START
            }
        }
        inner.addView(iconView, LinearLayout.LayoutParams(ui.px(iconSize), ui.px(iconSize)))
        inner.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        inner.addView(TextView(ctx).apply {
            text = label
            setTextColor(Colors.TEXT)
            setSizePx(ui, if (compact) 22 else 27)
            includeFontPadding = false
            typeface = Fonts.bold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        tile.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    tile.setOnFocusChangeListener { v, has ->
        v.background = if (has) focused else normal
        v.foreground = if (has) edge else null
        v.translationZ = if (has) ui.px(14).toFloat() else 0f
        nameStrip?.visibility = if (has) View.VISIBLE else View.GONE
        focusPop(v, has)
    }
    tile.setOnClickListener { onClick() }
    if (onLong != null) {
        tile.setOnLongClickListener {
            onLong()
            true
        }
    }
    return tile
}


/** Icono vectorial teñido de un color. */
fun vIcon(ctx: Context, res: Int, color: Int): ImageView = ImageView(ctx).apply {
    setImageResource(res)
    setColorFilter(color)
    scaleType = ImageView.ScaleType.FIT_CENTER
}

/** Texto con la fuente y el tamaño de Lumen en una sola línea. */
fun lbl(ctx: Context, ui: Ui, text: String, size: Int, color: Int = Colors.TEXT, bold: Boolean = false): TextView = TextView(ctx).apply {
    this.text = text
    setTextColor(color)
    setSizePx(ui, size)
    if (bold) typeface = Fonts.bold
}

/** Etiqueta de color (competición, EN DIRECTO, FHD…). */
fun pill(ctx: Context, ui: Ui, text: String, bg: Int, fg: Int = Colors.onColor(bg), size: Int = 24): TextView = TextView(ctx).apply {
    this.text = text
    setTextColor(fg)
    setSizePx(ui, size)
    typeface = Fonts.bold
    setPadding(ui.px(14), ui.px(4), ui.px(14), ui.px(4))
    background = roundRect(ui, bg, 8)
}

/** Botón grande de las pantallas nuevas: ámbar si es el principal. Crece al enfocarlo. */
fun bigButton(ctx: Context, ui: Ui, text: String, primary: Boolean, iconRes: Int? = null, onClick: () -> Unit): LinearLayout {
    val bg = if (primary) Colors.ACCENT else Colors.SURFACE2
    val fg = if (primary) Colors.onColor() else Colors.TEXT
    val edge = ring(ui, Colors.TEXT, 16)
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundRect(ui, bg, 16)
        setPadding(ui.px(30), ui.px(16), ui.px(30), ui.px(16))
        isFocusable = true
        isClickable = true
        if (iconRes != null) {
            addView(vIcon(ctx, iconRes, fg), LinearLayout.LayoutParams(ui.px(28), ui.px(28)).apply { rightMargin = ui.px(14) })
        }
        addView(lbl(ctx, ui, text, 28, fg, true))
        setOnFocusChangeListener { v, has ->
            v.foreground = if (has) edge else null
            focusPop(v, has, 1.04f)
        }
        setOnClickListener { onClick() }
    }
}

/**
 * Tarjeta de estado (sin internet, PITWALL apagado, IPTV caducada, canal sin señal…):
 * icono de color, título, explicación y uno o dos botones.
 */
fun stateCard(
    ctx: Context,
    ui: Ui,
    iconRes: Int,
    iconBg: Int,
    title: String,
    text: String,
    primary: String?,
    onPrimary: (() -> Unit)?,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
    compact: Boolean = false
): LinearLayout {
    val box = LinearLayout(ctx).apply {
        orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = if (compact) Gravity.CENTER_VERTICAL else Gravity.START
        background = roundRect(ui, Colors.SURFACE, 28)
        val p = if (compact) 22 else 40
        setPadding(ui.px(p + 4), ui.px(p), ui.px(p + 4), ui.px(p))
    }
    val badgeSize = if (compact) 64 else 88
    val badge = FrameLayout(ctx).apply {
        background = roundRect(ui, iconBg, badgeSize / 4)
        addView(vIcon(ctx, iconRes, Colors.onColor(iconBg)), FrameLayout.LayoutParams(ui.px(badgeSize * 55 / 100), ui.px(badgeSize * 55 / 100), Gravity.CENTER))
    }
    val texts = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(lbl(ctx, ui, title, if (compact) 28 else 36, Colors.TEXT, true))
        addView(lbl(ctx, ui, text, if (compact) 24 else 28, Color.parseColor("#C9CED9")).apply {
            setPadding(0, ui.px(6), 0, 0)
            setLineSpacing(ui.px(4).toFloat(), 1f)
        })
    }
    val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    if (primary != null && onPrimary != null) buttons.addView(bigButton(ctx, ui, primary, true) { onPrimary() })
    if (secondary != null && onSecondary != null) {
        buttons.addView(bigButton(ctx, ui, secondary, false) { onSecondary() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(16) })
    }
    if (compact) {
        box.addView(badge, LinearLayout.LayoutParams(ui.px(badgeSize), ui.px(badgeSize)).apply { rightMargin = ui.px(24) })
        box.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(24) })
    } else {
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badge, LinearLayout.LayoutParams(ui.px(badgeSize), ui.px(badgeSize)).apply { rightMargin = ui.px(26) })
            addView(texts.getChildAt(0).also { texts.removeViewAt(0) })
        }
        box.addView(head)
        box.addView(texts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(18) })
        box.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(26) })
    }
    return box
}

/** Convierte un aviso de Health en tarjeta: icono y color según el motivo. */
fun healthCard(ctx: Context, ui: Ui, msg: String, onRetry: () -> Unit, onFix: () -> Unit): LinearLayout {
    val clean = msg.removePrefix("⚠ ").trim()
    return when {
        clean.startsWith("PITWALL") -> stateCard(ctx, ui, R.drawable.ic_pc, Color.parseColor("#F5B544"), "PITWALL no responde",
            "Enciende el PC y abre PITWALL, o revisa la dirección desde el móvil.", "Reintentar", onRetry, "Cambiar dirección", onFix, compact = true)
        clean.contains("caducado") || clean.contains("cuenta") -> stateCard(ctx, ui, R.drawable.ic_card, Color.parseColor("#B18CFF"), "Tu suscripción IPTV no está activa",
            clean.removePrefix("IPTV: ").replaceFirstChar { it.uppercase() } + ". El resto del launcher sigue funcionando.", "Cambiar lista", onFix, compact = true)
        clean.contains("contraseña") -> stateCard(ctx, ui, R.drawable.ic_card, Color.parseColor("#B18CFF"), "La lista IPTV no acepta tus datos",
            "Usuario o contraseña incorrectos.", "Cambiar lista", onFix, compact = true)
        else -> stateCard(ctx, ui, R.drawable.ic_wifi_off, Color.parseColor("#E5484D"), "Sin conexión con la lista IPTV",
            "El servidor no responde. Vuelve solo en cuanto haya red.", "Reintentar", onRetry, compact = true)
    }
}
