package com.alvaro.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.concurrent.thread

/**
 * Lista de opciones sobre el vídeo (audio y subtítulos, otras versiones del canal…).
 * ▲▼ moverse, OK elegir, ◀ o Atrás cerrar.
 */
class ChoicePanel(private val act: Activity, private val ui: Ui) {
    class Item(val title: String, val sub: String, val tag: String?, val selected: Boolean, val pick: () -> Unit)

    val view: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(ui, Color.argb(248, 11, 13, 19), 28)
        setPadding(ui.px(28), ui.px(28), ui.px(28), ui.px(24))
        visibility = View.GONE
    }
    private var items: List<Item> = emptyList()
    private var rows: List<View> = emptyList()
    private var sel = 0
    val isOpen: Boolean get() = view.visibility == View.VISIBLE

    fun show(title: String, sub: String?, list: List<Item>, foot: String) {
        view.removeAllViews()
        view.addView(lbl(act, ui, title, 36, Colors.TEXT, true).apply { setPadding(ui.px(12), 0, 0, ui.px(6)) })
        if (!sub.isNullOrBlank()) view.addView(lbl(act, ui, sub, 24, Color.parseColor("#C9CED9")).apply {
            setPadding(ui.px(12), 0, ui.px(12), ui.px(14))
            setLineSpacing(ui.px(4).toFloat(), 1f)
        })
        items = list
        val rs = ArrayList<View>()
        var lastTag: String? = null
        for (it in list) {
            if (it.tag != null && it.tag != lastTag && list.count { x -> x.tag == it.tag } > 1) {
                view.addView(lbl(act, ui, it.tag, 24, Colors.MUTED, true).apply { setPadding(ui.px(12), ui.px(10), 0, ui.px(6)) })
            }
            lastTag = it.tag
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.px(20), ui.px(16), ui.px(20), ui.px(16))
            }
            val dot = FrameLayout(act).apply { background = ring(ui, if (it.selected) Colors.ACCENT else Color.parseColor("#5A6070"), 14) }
            if (it.selected) dot.addView(View(act).apply { background = roundRect(ui, Colors.ACCENT, 6) }, FrameLayout.LayoutParams(ui.px(12), ui.px(12), Gravity.CENTER))
            r.addView(dot, LinearLayout.LayoutParams(ui.px(28), ui.px(28)).apply { rightMargin = ui.px(20) })
            r.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(lbl(act, ui, it.title, 28, Colors.TEXT, true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (it.sub.isNotBlank()) addView(lbl(act, ui, it.sub, 24, Colors.MUTED))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            view.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(6) })
            rs.add(r)
        }
        rows = rs
        view.addView(lbl(act, ui, foot, 24, Color.parseColor("#8C95A8")).apply { setPadding(ui.px(12), ui.px(12), 0, 0) })
        sel = list.indexOfFirst { it.selected }.coerceAtLeast(0)
        paint()
        view.visibility = View.VISIBLE
        view.bringToFront()
    }

    private fun paint() {
        for ((i, r) in rows.withIndex()) {
            r.background = if (i == sel) roundRect(ui, Colors.SURFACE2, 16) else null
            r.foreground = if (i == sel) ring(ui, Colors.TEXT, 16) else null
        }
    }

    fun close() {
        view.visibility = View.GONE
    }

    fun key(code: Int): Boolean {
        if (!isOpen) return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (rows.isNotEmpty()) sel = (sel - 1 + rows.size) % rows.size
            KeyEvent.KEYCODE_DPAD_DOWN -> if (rows.isNotEmpty()) sel = (sel + 1) % rows.size
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val it = items.getOrNull(sel)
                close()
                it?.pick?.invoke()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> {
                close()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
            else -> return true
        }
        paint()
        return true
    }
}

/**
 * Fútbol sincronizado en el reproductor: sigue el partido que se está viendo (ESPN),
 * lee el reloj y el marcador de la retransmisión (ML Kit, sin internet) para calcular el retraso
 * y no enseña un gol hasta que el marcador de tu tele cambia. Panel lateral con ▶.
 */
class FootSync(
    private val act: Activity,
    private val ui: Ui,
    private val prefs: Prefs,
    private val videoView: () -> View?,
    var match: Football.Match,
    private val channelId: Int
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var running = true

    // datos recibidos (hora de llegada → estado del partido)
    private val snaps = ArrayList<Pair<Long, Football.Match>>()
    private var lastMinute = -1
    private val dataMinute = HashMap<Int, Long>()
    private var dataGoalAt = HashMap<Int, Long>()

    // lo que se ve en la tele
    private var tvGoals: Int? = null
    private var tvClockAt = 0L
    private val samples = ArrayList<Pair<Double, String>>()
    private var manual = 0
    private val startedAt = System.currentTimeMillis()
    private var detail: Football.Detail? = null
    private var detailAt = 0L
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var ocrBusy = false
    private var ocrUsed = false

    // panel
    val panel: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.argb(242, 7, 8, 12))
        setPadding(ui.px(44), ui.px(44), ui.px(44), ui.px(32))
        visibility = View.GONE
    }
    private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(act).apply {
        isVerticalScrollBarEnabled = false
        addView(body)
    }
    val isOpen: Boolean get() = panel.visibility == View.VISIBLE

    init {
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(lbl(act, ui, "◀ ▶ ajustar a mano   ·   ▲▼ ver más   ·   Atrás cerrar", 24, Color.parseColor("#8C95A8")).apply { setPadding(0, ui.px(14), 0, 0) })
        manual = 0
        snaps.add(Pair(System.currentTimeMillis(), match))
        lastMinute = match.minuteNow()
        poll()
        // se lanzan después de construir el objeto (ocrRun y paintRun se declaran más abajo)
        main.postDelayed({ ocrRun.run() }, 3000)
        main.postDelayed({ paintRun.run() }, 1000)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (ocrUsed) {
            try {
                recognizer.close()
            } catch (e: Exception) {
                // ya cerrado
            }
        }
    }

    /** El vídeo se cortó y volvió: el retraso habrá cambiado. */
    fun onReconnect() {
        samples.clear()
        tvGoals = null
    }

    // ───────────── datos ─────────────

    private fun poll() {
        if (!running) return
        thread {
            val m = Football.refresh(match)
            val now = System.currentTimeMillis()
            main.post {
                if (!running) return@post
                val prevGoals = snaps.lastOrNull()?.second?.goals()?.size ?: 0
                match = m
                snaps.add(Pair(now, m))
                while (snaps.size > 2 && now - snaps[1].first > 15 * 60_000L) snaps.removeAt(0)
                val minute = m.minuteNow()
                // cambio de minuto observado (no en los añadidos, que son ambiguos)
                if (m.live && lastMinute >= 0 && minute == lastMinute + 1 && minute != 46 && minute != 91 && !dataMinute.containsKey(minute)) {
                    dataMinute[minute] = now - 7_500L
                }
                lastMinute = minute
                val goals = m.goals().size
                if (goals > prevGoals) for (g in prevGoals + 1..goals) dataGoalAt[g] = now - 7_500L
            }
            if (now - detailAt > 60_000L) {
                detail = Football.detail(m)
                detailAt = now
            }
        }
        if (match.live || match.state == "pre") main.postDelayed({ poll() }, 15_000L)
    }

    // ───────────── lectura de la imagen ─────────────

    private val clockRe = Regex("""(\d{1,3})\s?[:.]\s?(\d{2})""")
    private val scoreRe = Regex("""(?<![\d:.])(\d{1,2})\s?[-–]\s?(\d{1,2})(?![\d:.])""")

    private val ocrRun = object : Runnable {
        override fun run() {
            if (!running) return
            if (prefs.syncAuto && match.live && !ocrBusy) capture()
            main.postDelayed(this, 6_000L)
        }
    }

    private fun capture() {
        val v = videoView() ?: return
        if (v.width == 0 || v.height == 0) return
        val bmp = Bitmap.createBitmap(1280, 200, Bitmap.Config.ARGB_8888)
        val at = System.currentTimeMillis()
        ocrBusy = true
        try {
            when (v) {
                is SurfaceView -> PixelCopy.request(v, Rect(0, 0, v.width, (v.height * 0.24f).toInt()), bmp, { res ->
                    if (res == PixelCopy.SUCCESS) read(bmp, at) else {
                        bmp.recycle()
                        ocrBusy = false
                    }
                }, main)
                is TextureView -> {
                    val full = v.bitmap
                    if (full == null) {
                        ocrBusy = false
                        return
                    }
                    val crop = Bitmap.createScaledBitmap(Bitmap.createBitmap(full, 0, 0, full.width, (full.height * 0.24f).toInt()), 1280, 200, true)
                    full.recycle()
                    read(crop, at)
                }
                else -> ocrBusy = false
            }
        } catch (e: Exception) {
            ocrBusy = false
        }
    }

    private fun read(bmp: Bitmap, at: Long) {
        ocrUsed = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t -> onText(t.text, at) }
            .addOnCompleteListener {
                bmp.recycle()
                ocrBusy = false
            }
    }

    private fun onText(text: String, at: Long) {
        // reloj del partido
        val clocks = clockRe.findAll(text).mapNotNull {
            val mm = it.groupValues[1].toInt()
            val ss = it.groupValues[2].toInt()
            if (mm in 0..130 && ss in 0..59) Pair(mm, ss) else null
        }.toList()
        val clock = clocks.firstOrNull()
        if (clock != null) {
            tvClockAt = at
            // la tele marca mm:ss → en los datos eso es el minuto mm+1 más ss segundos
            val start = dataMinute[clock.first + 1]
            if (start != null) {
                val d = (at - (start + clock.second * 1000L)) / 1000.0
                if (d in 0.0..240.0) addSample(d, "reloj")
            }
        }
        // marcador de la tele (los números con guion que no son el reloj)
        val clean = clockRe.replace(text, " ")
        val score = scoreRe.find(clean)
        if (score != null) {
            val a = score.groupValues[1].toInt()
            val b = score.groupValues[2].toInt()
            val total = a + b
            val dataTotal = match.goals().size
            if (total <= dataTotal + 1 && a <= 15 && b <= 15) {
                val prev = tvGoals
                if (prev != null && total == prev + 1) {
                    dataGoalAt[total]?.let { g ->
                        val d = (at - g) / 1000.0
                        if (d in 0.0..240.0) addSample(d, "gol")
                    }
                }
                if (prev == null || total >= prev) tvGoals = total
            }
        }
    }

    private fun addSample(d: Double, src: String) {
        samples.add(Pair(d, src))
        while (samples.size > 7) samples.removeAt(0)
    }

    /** Retraso que se aplica: lo medido (mediana) + margen, o el guardado del canal. Más el ajuste a mano. */
    fun delaySec(): Int {
        val base = if (samples.isEmpty()) prefs.footDelay(channelId).toDouble() else samples.map { it.first }.sorted()[samples.size / 2] + prefs.syncMargin
        return (base + manual).toInt().coerceIn(0, 300)
    }

    private fun shown(): Football.Match {
        val cut = System.currentTimeMillis() - delaySec() * 1000L
        return snaps.lastOrNull { it.first <= cut }?.second ?: snaps.first().second
    }

    fun status(): String = when {
        samples.isNotEmpty() -> "Sincronizado solo · " + delaySec() + " s"
        !prefs.syncAuto -> "Retraso fijo · " + delaySec() + " s"
        System.currentTimeMillis() - startedAt < 90_000L -> "Sincronizando…"
        else -> "No veo el reloj · uso " + delaySec() + " s"
    }

    // ───────────── panel ─────────────

    fun toggle() {
        if (isOpen) panel.visibility = View.GONE else {
            panel.visibility = View.VISIBLE
            panel.bringToFront()
            paint()
        }
    }

    fun key(code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> adjust(-5)
            KeyEvent.KEYCODE_DPAD_RIGHT -> adjust(5)
            KeyEvent.KEYCODE_DPAD_UP -> scroll.smoothScrollBy(0, -ui.px(160))
            KeyEvent.KEYCODE_DPAD_DOWN -> scroll.smoothScrollBy(0, ui.px(160))
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
            else -> panel.visibility = View.GONE
        }
        return true
    }

    fun adjust(d: Int) {
        manual += d
        if (samples.isEmpty()) {
            prefs.setFootDelay(channelId, (prefs.footDelay(channelId) + manual).coerceIn(0, 300))
            manual = 0
        }
        paint()
    }

    private val paintRun = object : Runnable {
        override fun run() {
            if (!running) return
            if (isOpen) paint()
            main.postDelayed(this, 2_000L)
        }
    }

    private fun paint() {
        val m = shown()
        body.removeAllViews()
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(pill(act, ui, m.league.name, Football.leagueColor(m.league)))
        head.addView(lbl(act, ui, m.home.short + " – " + m.away.short, 28, Colors.TEXT, true).apply {
            setPadding(ui.px(14), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        body.addView(head)

        val synced = samples.isNotEmpty()
        val st = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(ui, if (synced) Color.argb(36, 47, 179, 107) else Colors.SURFACE, 16)
            setPadding(ui.px(20), ui.px(16), ui.px(20), ui.px(16))
        }
        val ic = FrameLayout(act).apply { background = roundRect(ui, if (synced) Color.parseColor("#2FB36B") else Color.parseColor("#232838"), 24) }
        ic.addView(vIcon(act, if (synced) R.drawable.ic_check else R.drawable.ic_sync, if (synced) Color.parseColor("#07080C") else Colors.TEXT), FrameLayout.LayoutParams(ui.px(26), ui.px(26), Gravity.CENTER))
        st.addView(ic, LinearLayout.LayoutParams(ui.px(48), ui.px(48)).apply { rightMargin = ui.px(16) })
        val src = samples.lastOrNull()?.second
        st.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(lbl(act, ui, status(), 28, if (synced) Color.parseColor("#7DD3A0") else Colors.TEXT, true))
            addView(lbl(act, ui, when {
                src == "gol" -> "Medido con el último gol"
                src == "reloj" -> "Por el reloj de la tele" + (if (tvClockAt > 0) " · hace " + ((System.currentTimeMillis() - tvClockAt) / 1000) + " s" else "")
                manual != 0 -> "Ajustado a mano " + (if (manual > 0) "+" else "") + manual + " s"
                else -> "Los goles salen cuando los ves"
            }, 24, Colors.MUTED))
        })
        body.addView(st, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(18) })

        // goles y tarjetas (con la puerta del marcador de la tele)
        body.addView(lbl(act, ui, "Goles y tarjetas", 24, Colors.MUTED, true).apply { setPadding(0, ui.px(24), 0, ui.px(8)) })
        val allowedGoals = if (prefs.goalsGate && tvGoals != null) minOf(m.goals().size, tvGoals!!) else m.goals().size
        var goalN = 0
        var hidden = 0
        for (e in m.events) {
            if (e.goal) {
                goalN++
                if (goalN > allowedGoals) {
                    hidden++
                    continue
                }
            }
            body.addView(eventRow(m, e))
        }
        val pending = maxOf(hidden, if (prefs.goalsGate) match.goals().size - maxOf(allowedGoals, 0) - hidden else 0)
        if (pending > 0) {
            body.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ring(ui, Color.parseColor("#3A4050"), 14)
                setPadding(ui.px(16), ui.px(14), ui.px(16), ui.px(14))
                addView(vIcon(act, R.drawable.ic_ball, Color.parseColor("#5A6070")), LinearLayout.LayoutParams(ui.px(26), ui.px(26)).apply { rightMargin = ui.px(14) })
                addView(lbl(act, ui, if (pending > 1) "$pending goles en espera" else "Gol en espera", 26, Colors.MUTED, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(lbl(act, ui, "sale cuando lo veas", 24, Colors.MUTED))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(6) })
        }
        if (m.events.isEmpty() && pending == 0) body.addView(lbl(act, ui, "Todavía nada", 26, Colors.MUTED))

        // estadísticas
        val d = detail
        if (d != null && d.stats.isNotEmpty()) {
            body.addView(lbl(act, ui, "Estadísticas", 24, Colors.MUTED, true).apply { setPadding(0, ui.px(24), 0, ui.px(8)) })
            for (s in d.stats.take(5)) body.addView(statRow(s))
        }
    }

    private fun eventRow(m: Football.Match, e: Football.Event): View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.px(8), 0, ui.px(8))
        addView(lbl(act, ui, e.minute, 26, Colors.ACCENT, true), LinearLayout.LayoutParams(ui.px(80), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (e.goal) addView(vIcon(act, R.drawable.ic_ball, Colors.TEXT), LinearLayout.LayoutParams(ui.px(26), ui.px(26)).apply { rightMargin = ui.px(14) })
        else addView(View(act).apply { background = roundRect(ui, if (e.red) Color.parseColor("#E5484D") else Color.parseColor("#F5C518"), 3) }, LinearLayout.LayoutParams(ui.px(18), ui.px(24)).apply {
            leftMargin = ui.px(4)
            rightMargin = ui.px(18)
        })
        addView(lbl(act, ui, e.player.ifBlank { e.kind }, 26, Colors.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(lbl(act, ui, if (e.teamId == m.home.id) m.home.short else m.away.short, 24, Colors.MUTED))
    }

    private fun statRow(s: Football.Stat): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.px(8), 0, ui.px(8))
        }
        box.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(lbl(act, ui, s.home, 28, Colors.TEXT, true), LinearLayout.LayoutParams(ui.px(100), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(lbl(act, ui, s.label, 24, Colors.MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(lbl(act, ui, s.away, 28, Colors.TEXT, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(ui.px(100), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        val a = s.home.trimEnd('%').toFloatOrNull() ?: 0f
        val b = s.away.trimEnd('%').toFloatOrNull() ?: 0f
        val mx = maxOf(a, b, 0.0001f)
        val bars = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val l = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        l.addView(View(act), LinearLayout.LayoutParams(0, ui.px(10), 1f - a / mx + 0.0001f))
        l.addView(View(act).apply { background = roundRect(ui, Color.parseColor("#E5484D"), 5) }, LinearLayout.LayoutParams(0, ui.px(10), a / mx + 0.0001f))
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(View(act).apply { background = roundRect(ui, Color.parseColor("#2FB36B"), 5) }, LinearLayout.LayoutParams(0, ui.px(10), b / mx + 0.0001f))
        r.addView(View(act), LinearLayout.LayoutParams(0, ui.px(10), 1f - b / mx + 0.0001f))
        bars.addView(l, LinearLayout.LayoutParams(0, ui.px(10), 1f))
        bars.addView(View(act), LinearLayout.LayoutParams(ui.px(6), 1))
        bars.addView(r, LinearLayout.LayoutParams(0, ui.px(10), 1f))
        box.addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(10)).apply { topMargin = ui.px(6) })
        return box
    }
}
