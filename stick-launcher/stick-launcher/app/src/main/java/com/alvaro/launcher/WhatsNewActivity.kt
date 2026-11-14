package com.alvaro.launcher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Novedades: sale una vez después de cada actualización. */
class WhatsNewActivity : Activity() {

    /** Lo nuevo de esta versión: icono, color, título y una línea. */
    private val items = listOf(
        listOf(R.drawable.ic_sliders, "#2FB36B", "Modo ligero", "Para sticks con poca memoria: quita PITWALL y DAZN y gasta menos búfer. Ajustes → Modo ligero."),
        listOf(R.drawable.ic_grid, "#4C9AFF", "Lumen TV más claro", "Grupos de canales legibles y, al volver, te quedas en el grupo donde estabas."),
        listOf(R.drawable.ic_mic, "#E10600", "Búsqueda por texto", "Busca canales escribiendo, además de por voz."),
        listOf(R.drawable.ic_tv, "#F5A524", "Iconos y logos mejorados", "Mosaicos sin texto cortado, SmartTube reconocido y logos de canales que reintentan solos."),
        listOf(R.drawable.ic_sync, "#9B6BFF", "Actualización automática", "Lumen avisa cuando hay versión nueva y la instala con un clic.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.px(160), ui.px(80), ui.px(160), ui.px(80))
            background = homeBackground(ui)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ImageView(this).apply { setImageResource(R.drawable.splash_logo) }, LinearLayout.LayoutParams(ui.px(72), ui.px(72)))
        left.addView(lbl(this, ui, "Lumen se ha actualizado", 28, Colors.ACCENT, true).apply { setPadding(0, ui.px(22), 0, 0) })
        left.addView(lbl(this, ui, "Novedades de la versión " + Updater.installedCode(this), 72, Colors.TEXT, true).apply { setPadding(0, ui.px(12), 0, 0) })
        left.addView(lbl(this, ui, SimpleDateFormat("d 'de' MMMM", Locale("es", "ES")).format(Date()), 28, Colors.MUTED).apply { setPadding(0, ui.px(12), 0, 0) })
        left.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val ok = bigButton(this, ui, "OK · entendido", true) { finish() }
        left.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(left, LinearLayout.LayoutParams(ui.px(560), ViewGroup.LayoutParams.MATCH_PARENT))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (it in items) {
            val bg = Color.parseColor(it[1] as String)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = roundRect(ui, Colors.SURFACE, 24)
                setPadding(ui.px(32), ui.px(28), ui.px(32), ui.px(28))
            }
            val badge = FrameLayout(this).apply {
                background = roundRect(ui, bg, 18)
                addView(vIcon(this@WhatsNewActivity, it[0] as Int, Colors.onColor(bg)), FrameLayout.LayoutParams(ui.px(34), ui.px(34), Gravity.CENTER))
            }
            row.addView(badge, LinearLayout.LayoutParams(ui.px(64), ui.px(64)).apply { rightMargin = ui.px(26) })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(lbl(this@WhatsNewActivity, ui, it[2] as String, 36, Colors.TEXT, true))
                addView(lbl(this@WhatsNewActivity, ui, it[3] as String, 28, Color.parseColor("#C9CED9")).apply { setPadding(0, ui.px(6), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            right.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(20) })
        }
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = ui.px(100) })
        setContentView(root)
        ok.requestFocus()
    }
}
