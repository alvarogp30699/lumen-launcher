package com.alvaro.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Guía de programación: parrilla de las próximas 3 horas de los canales de la categoría (o favoritos) que se estaba mirando.
 * OK sobre un programa abre ese canal. Los programas de F1 salen resaltados.
 */
class GuideActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val pool = Executors.newFixedThreadPool(4)

    private val spanMin = 180
    private val pxPerMin = 8
    private var windowStart = 0L
    private var channels: List<Channel> = emptyList()
    private val tracks = HashMap<Int, FrameLayout>()
    private val f1Words = listOf("formula 1", "fórmula 1", "formula1", "fórmula1", "grand prix", "gran premio")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)

        val queue = Xtream.queue.ifEmpty { prefs.visibleChannels(Xtream.channels) }
        channels = queue.take(25)
        val now = System.currentTimeMillis() / 1000
        windowStart = now - (now % 1800)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.BG)
            setPadding(ui.px(80), ui.px(48), ui.px(80), ui.px(40))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "Guía de programación"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 40)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "OK: ver el canal   ·   Atrás: volver"
            setTextColor(Colors.MUTED)
            setSizePx(ui, 22)
        })
        root.addView(head)

        // cabecera de horas
        val hours = FrameLayout(this)
        var m = 0
        while (m < spanMin) {
            hours.addView(TextView(this).apply {
                text = fmt.format(Date((windowStart + m * 60L) * 1000))
                setTextColor(Colors.MUTED)
                setSizePx(ui, 22)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = ui.px(m * pxPerMin)
            })
            m += 30
        }
        val hoursRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.px(24), 0, ui.px(8))
            addView(View(this@GuideActivity), LinearLayout.LayoutParams(ui.px(300), 1))
            addView(hours, LinearLayout.LayoutParams(ui.px(spanMin * pxPerMin), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(hoursRow)

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (ch in channels) {
            val name = TextView(this).apply {
                text = ch.name
                setTextColor(Colors.TEXT)
                setSizePx(ui, 26)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }
            val track = FrameLayout(this)
            tracks[ch.id] = track
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(name, LinearLayout.LayoutParams(ui.px(300), ui.px(76)))
                addView(track, LinearLayout.LayoutParams(ui.px(spanMin * pxPerMin), ui.px(76)))
            }
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(88)))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(rows)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        for (ch in channels) {
            pool.execute {
                val list = Xtream.epg(prefs, ch.id)
                runOnUiThread { if (!isFinishing) fill(ch, list) }
            }
        }
    }

    private fun fill(ch: Channel, list: List<Epg>) {
        val track = tracks[ch.id] ?: return
        val end = windowStart + spanMin * 60L
        var any = false
        for (e in list) {
            if (e.end <= windowStart || e.start >= end) continue
            val s = maxOf(e.start, windowStart)
            val f = minOf(e.end, end)
            val left = ((s - windowStart) / 60).toInt() * pxPerMin
            val width = (((f - s) / 60).toInt() * pxPerMin - 6).coerceAtLeast(40)
            val isF1 = f1Words.any { e.title.lowercase().contains(it) }
            track.addView(block(ch, e, isF1), FrameLayout.LayoutParams(ui.px(width), ui.px(76)).apply {
                leftMargin = ui.px(left)
            })
            any = true
        }
        if (!any) {
            track.addView(TextView(this).apply {
                text = "Sin información de programación"
                setTextColor(Colors.MUTED)
                setSizePx(ui, 22)
                gravity = Gravity.CENTER_VERTICAL
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(76)))
        }
    }

    private fun block(ch: Channel, e: Epg, isF1: Boolean): View {
        val base = if (isF1) Color.parseColor("#3A2A0A") else Colors.SURFACE
        val normal = GradientDrawable().apply {
            cornerRadius = ui.px(14).toFloat()
            setColor(base)
            setStroke(ui.px(3), Color.TRANSPARENT)
        }
        val focused = GradientDrawable().apply {
            cornerRadius = ui.px(14).toFloat()
            setColor(Colors.SURFACE2)
            setStroke(ui.px(3), Colors.ACCENT)
        }
        return TextView(this).apply {
            text = (if (isF1) "🏁 " else "") + e.title
            setTextColor(Colors.TEXT)
            setSizePx(ui, 24)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(18), 0, ui.px(12), 0)
            background = normal
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, has -> background = if (has) focused else normal }
            setOnClickListener {
                startActivity(Intent(this@GuideActivity, PlayerActivity::class.java).putExtra("id", ch.id))
            }
        }
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }
}
