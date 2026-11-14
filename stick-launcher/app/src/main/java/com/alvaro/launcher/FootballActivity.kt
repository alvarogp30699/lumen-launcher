package com.alvaro.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Lumen Fútbol: Hoy, Real Madrid y clasificaciones, con el bloque del Madrid siempre en el lateral.
 * OK sobre un partido abre su ficha: antes (Avísame, forma), en directo (goles, estadísticas, alineaciones)
 * o terminado (resumen en SmartTube).
 */
class FootballActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var menu: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var teamBox: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val menuRows = ArrayList<Pair<String, TextView>>()
    private var section = "today"
    private var detailOf: Football.Match? = null
    private var token = 0
    private val es = Locale("es", "ES")
    private val GREEN = Color.parseColor("#2FB36B")
    private val RED = Color.parseColor("#E10600")
    private val SOFT = Color.parseColor("#C3C7E6")

    private val autoRefresh = object : Runnable {
        override fun run() {
            val d = detailOf
            if (d != null) {
                if (d.live) showDetail(d, false)
            } else if (section == "today" || section == "team") {
                load(section, false)
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)

        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(80), ui.px(56), ui.px(36), ui.px(48))
            background = roundRect(ui, Color.parseColor("#0B0D13"), 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, ui.px(30))
        }
        head.addView(vIcon(this, R.drawable.ic_ball, Colors.ACCENT), LinearLayout.LayoutParams(ui.px(44), ui.px(44)).apply { rightMargin = ui.px(16) })
        head.addView(lbl(this, ui, "Fútbol", 48, Colors.TEXT, true))
        menu.addView(head)
        addMenu("today", "Hoy")
        addMenu("team", Football.TEAM_NAME)
        addMenu("st_esp.1", "Clasificación LaLiga")
        addMenu("st_uefa.champions", "Clasificación Champions")
        menu.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        teamBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(ui, Color.parseColor("#141734"), 24)
            setPadding(ui.px(26), ui.px(24), ui.px(26), ui.px(24))
            visibility = View.INVISIBLE
        }
        menu.addView(teamBox)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(64), ui.px(56), ui.px(96), ui.px(56))
            clipChildren = false
            clipToPadding = false
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            clipChildren = false
            addView(content)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#07080C"))
            addView(menu, LinearLayout.LayoutParams(ui.px(470), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        setContentView(root)
        loadTeamBox()

        val wanted = intent.getStringExtra("match")
        if (wanted != null) {
            styleMenu()
            msg("Cargando partido…")
            thread {
                val m = Football.find(wanted)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (m != null) showDetail(m, true) else select("today")
                }
            }
        } else {
            select("today")
            menuRows.first().second.requestFocus()
        }
        handler.postDelayed(autoRefresh, 30_000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && detailOf != null && !intent.hasExtra("match")) {
            detailOf = null
            select(section)
            menuRows.firstOrNull { it.first == section }?.second?.requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ───────────── menú y bloque del Madrid ─────────────

    private fun addMenu(id: String, text: String) {
        val tv = lbl(this, ui, text, 28, Colors.TEXT, true).apply {
            setPadding(ui.px(24), ui.px(18), ui.px(24), ui.px(18))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, has ->
                v.foreground = if (has) ring(ui, Colors.TEXT, 16) else null
                if (has && (section != id || detailOf != null)) select(id)
            }
            setOnClickListener { firstFocusable()?.requestFocus() }
        }
        menu.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(8) })
        menuRows.add(Pair(id, tv))
    }

    private fun firstFocusable(): View? {
        for (i in 0 until content.childCount) if (content.getChildAt(i).isFocusable) return content.getChildAt(i)
        return null
    }

    private fun styleMenu() {
        for ((id, v) in menuRows) {
            val sel = id == section && detailOf == null
            v.background = if (sel) roundRect(ui, Colors.ACCENT, 16) else null
            v.setTextColor(if (sel) Colors.onColor() else Color.parseColor("#DADDE5"))
        }
    }

    private fun formChips(form: List<String>, size: Int = 46): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (r in form) {
            val c = when (r) {
                "G" -> GREEN
                "P" -> Color.parseColor("#E5484D")
                else -> Color.parseColor("#8C95A8")
            }
            row.addView(TextView(this).apply {
                text = r
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#07080C"))
                setSizePx(ui, 24)
                typeface = Fonts.bold
                background = roundRect(ui, c, 12)
            }, LinearLayout.LayoutParams(ui.px(size), ui.px(size)).apply { rightMargin = ui.px(10) })
        }
        return row
    }

    private fun loadTeamBox() {
        thread {
            val form = Football.teamForm()
            val pos = Football.position(Football.LEAGUES[0], Football.TEAM_ID)
            val next = Football.teamMatches().firstOrNull { !it.done }
            val logo = Football.teamMatches().firstOrNull()?.let { if (it.home.id == Football.TEAM_ID) it.home.logo else it.away.logo } ?: ""
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                teamBox.removeAllViews()
                val h = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                h.addView(crest(logo, 64), LinearLayout.LayoutParams(ui.px(64), ui.px(64)).apply { rightMargin = ui.px(16) })
                h.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(lbl(this@FootballActivity, ui, Football.TEAM_NAME, 28, Colors.TEXT, true))
                    if (pos != null) addView(lbl(this@FootballActivity, ui, pos.rank.toString() + ".º en LaLiga · " + pos.pts + " pts", 24, SOFT))
                })
                teamBox.addView(h)
                if (form.isNotEmpty()) {
                    teamBox.addView(lbl(this, ui, "Últimos 5", 24, Colors.MUTED, true).apply { setPadding(0, ui.px(18), 0, ui.px(10)) })
                    teamBox.addView(formChips(form))
                }
                if (next != null) {
                    teamBox.addView(View(this).apply { setBackgroundColor(Color.argb(20, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        topMargin = ui.px(18)
                        bottomMargin = ui.px(14)
                    })
                    teamBox.addView(lbl(this, ui, if (next.live) "Jugando ahora" else "Próximo", 24, Colors.MUTED))
                    val rival = if (next.home.id == Football.TEAM_ID) next.away.short else next.home.short
                    teamBox.addView(lbl(this, ui, "vs " + rival + " · " + (if (next.live) next.score() + " " + next.status() else SimpleDateFormat("EEE HH:mm", es).format(Date(next.start))), 28, Colors.TEXT, true).apply {
                        maxLines = 2
                    })
                }
                teamBox.visibility = View.VISIBLE
            }
        }
    }

    // ───────────── secciones ─────────────

    private fun select(id: String) {
        section = id
        detailOf = null
        styleMenu()
        load(id, true)
    }

    private fun msg(t: String) {
        content.removeAllViews()
        content.addView(lbl(this, ui, t, 28, Colors.MUTED))
    }

    private fun pageTitle(t: String, sub: String) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, 0, 0, ui.px(10))
            addView(lbl(this@FootballActivity, ui, t, 48, Colors.TEXT, true))
            addView(lbl(this@FootballActivity, ui, sub, 28, Colors.MUTED).apply { setPadding(ui.px(20), 0, 0, ui.px(6)) })
        })
    }

    private fun sectionHead(t: String, color: Int, sub: String = "", top: Int = 18) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.px(top), 0, ui.px(14))
            addView(View(this@FootballActivity).apply { background = roundRect(ui, color, 4) }, LinearLayout.LayoutParams(ui.px(8), ui.px(30)).apply { rightMargin = ui.px(14) })
            addView(lbl(this@FootballActivity, ui, t, 28, Colors.TEXT, true))
            if (sub.isNotBlank()) addView(lbl(this@FootballActivity, ui, sub, 24, Colors.MUTED).apply { setPadding(ui.px(14), 0, 0, 0) })
        })
    }

    private fun load(id: String, showLoading: Boolean) {
        val my = ++token
        if (showLoading) skeleton()
        thread {
            when {
                id == "today" -> {
                    val list = Football.today()
                    runOnUiThread { if (my == token && !isFinishing) showToday(list) }
                }
                id == "team" -> {
                    val list = Football.teamMatches()
                    val pos = Football.position(Football.LEAGUES[0], Football.TEAM_ID)
                    val ucl = Football.position(Football.LEAGUES[1], Football.TEAM_ID)
                    runOnUiThread { if (my == token && !isFinishing) showTeam(list, pos, ucl) }
                }
                id.startsWith("st_") -> {
                    val lg = Football.LEAGUES.firstOrNull { it.code == id.removePrefix("st_") } ?: return@thread
                    val rows = Football.standings(lg)
                    runOnUiThread { if (my == token && !isFinishing) showTable(lg, rows) }
                }
            }
        }
    }

    private fun skeleton() {
        content.removeAllViews()
        val sk = Color.parseColor("#151924")
        content.addView(View(this).apply { background = roundRect(ui, sk, 12) }, LinearLayout.LayoutParams(ui.px(300), ui.px(56)).apply { bottomMargin = ui.px(30) })
        for (i in 0 until 5) content.addView(View(this).apply { background = roundRect(ui, sk, 24) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(112)).apply { bottomMargin = ui.px(18) })
    }

    private fun showToday(list: List<Football.Match>) {
        val focusedTag = currentFocus?.tag as? String
        content.removeAllViews()
        pageTitle("Hoy", SimpleDateFormat("EEEE, d 'de' MMMM", es).format(Date()))
        if (list.isEmpty()) {
            content.addView(stateCard(this, ui, R.drawable.ic_ball, Colors.SURFACE2, "Hoy no hay partidos",
                "No juega nadie de LaLiga, Champions ni Copa del Rey. Mira la semana deportiva.", "Semana deportiva", {
                    startActivity(Intent(this, SemanaActivity::class.java))
                }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(20) })
            return
        }
        val live = list.filter { it.live }
        val later = list.filter { it.state == "pre" }
        val done = list.filter { it.done }
        if (live.isNotEmpty()) {
            sectionHead("En directo", RED, live.map { it.league.name }.distinct().joinToString(" · "))
            for (m in live) content.addView(matchRow(m, false), rowLp(true))
        }
        if (later.isNotEmpty()) {
            sectionHead("Más tarde", Colors.ACCENT)
            for (m in later) content.addView(matchRow(m, false), rowLp(false))
        }
        if (done.isNotEmpty()) {
            sectionHead("Terminados", Color.parseColor("#5A6070"))
            for (m in done) content.addView(matchRow(m, false), rowLp(false))
        }
        if (focusedTag != null) findTag(focusedTag)?.requestFocus()
        findChannels(live + later)
    }

    private fun findChannels(list: List<Football.Match>) {
        if (!prefs.configured) return
        val my = token
        thread {
            for (m in list) {
                if (my != token) break
                val ch = Football.findChannel(prefs, m)
                runOnUiThread {
                    if (my != token) return@runOnUiThread
                    val tv = findTag(m.id)?.getTag(R.id.tv_label) as? TextView ?: return@runOnUiThread
                    tv.text = ch?.name ?: "—"
                    tv.setTextColor(if (ch != null) Colors.ACCENT else Colors.MUTED)
                }
            }
        }
    }

    private fun showTeam(list: List<Football.Match>, pos: Football.Row?, ucl: Football.Row?) {
        content.removeAllViews()
        // cabecera del equipo
        val head = FrameLayout(this).apply {
            background = roundRect(ui, Color.parseColor("#141734"), 28)
            clipToOutline = true
        }
        val logo = list.firstOrNull()?.let { if (it.home.id == Football.TEAM_ID) it.home.logo else it.away.logo } ?: ""
        val wm = crest(logo, 300).apply { alpha = 0.07f }
        head.addView(wm, FrameLayout.LayoutParams(ui.px(460), ui.px(460), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = -ui.px(40) })
        val hr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(48), ui.px(30), ui.px(48), ui.px(30))
        }
        hr.addView(crest(logo, 130), LinearLayout.LayoutParams(ui.px(130), ui.px(130)).apply { rightMargin = ui.px(36) })
        hr.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(lbl(this@FootballActivity, ui, Football.TEAM_NAME, 72, Colors.TEXT, true))
            addView(lbl(this@FootballActivity, ui, "LaLiga · Champions League · Copa del Rey", 28, SOFT))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun stat(t: String, v: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundRect(ui, Color.argb(20, 255, 255, 255), 18)
            setPadding(ui.px(26), ui.px(16), ui.px(26), ui.px(16))
            addView(lbl(this@FootballActivity, ui, t, 24, SOFT))
            addView(lbl(this@FootballActivity, ui, v, 48, Colors.TEXT, true))
        }
        if (pos != null) hr.addView(stat("LaLiga", pos.rank.toString() + ".º"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(20) })
        if (ucl != null) hr.addView(stat("Champions", ucl.rank.toString() + ".º"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(20) })
        if (pos != null) hr.addView(stat("Puntos", pos.pts.toString()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(20) })
        head.addView(hr)
        content.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(10) })
        if (list.isEmpty()) {
            content.addView(lbl(this, ui, "No hay partidos del " + Football.TEAM_NAME + " en estas fechas.", 28, Colors.MUTED).apply { setPadding(0, ui.px(24), 0, 0) })
            return
        }
        val next = list.filter { !it.done }
        val past = list.filter { it.done }.reversed()
        if (next.isNotEmpty()) {
            sectionHead("Próximos", Colors.ACCENT)
            for (m in next) content.addView(matchRow(m, true), rowLp(false))
        }
        if (past.isNotEmpty()) {
            sectionHead("Resultados", Color.parseColor("#5A6070"))
            for (m in past) content.addView(matchRow(m, true), rowLp(false))
        }
    }

    private fun rowLp(tall: Boolean) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(if (tall) 156 else 112)).apply { bottomMargin = ui.px(16) }

    private fun findTag(t: String): View? {
        for (i in 0 until content.childCount) if (content.getChildAt(i).tag == t) return content.getChildAt(i)
        return null
    }

    private fun crest(url: String, size: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        Logos.load(url, this, ui.px(size)) { }
    }

    private fun resultOf(m: Football.Match): String? {
        if (!m.done || !m.hasTeam(Football.TEAM_ID)) return null
        val mine = (if (m.home.id == Football.TEAM_ID) m.home.score else m.away.score).toIntOrNull() ?: 0
        val other = (if (m.home.id == Football.TEAM_ID) m.away.score else m.home.score).toIntOrNull() ?: 0
        return if (mine > other) "G" else if (mine < other) "P" else "E"
    }

    /** Fila de partido: estado, local, marcador, visitante y dónde se ve. En directo lleva la barra de goles. */
    private fun matchRow(m: Football.Match, teamView: Boolean): View {
        val normal = roundRect(ui, if (m.live) Color.parseColor("#141826") else Colors.SURFACE, 24)
        val focused = roundRect(ui, Colors.SURFACE2, 24)
        val edge = ring(ui, Colors.TEXT, 24)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = normal
            setPadding(ui.px(32), 0, ui.px(32), 0)
            isFocusable = true
            isClickable = true
            tag = m.id
            setOnFocusChangeListener { v, has ->
                v.background = if (has) focused else normal
                v.foreground = if (has) edge else null
                v.translationZ = if (has) ui.px(12).toFloat() else 0f
            }
            setOnClickListener { showDetail(m, true) }
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status: View = if (m.live) {
            pill(this, ui, m.status(), RED, size = 28)
        } else {
            lbl(this, ui, if (teamView && m.state == "pre") SimpleDateFormat("EEE d · HH:mm", es).format(Date(m.start)) else if (teamView) SimpleDateFormat("EEE d", es).format(Date(m.start)) else m.status(),
                if (teamView) 24 else 28, if (m.done) Color.parseColor("#8C95A8") else Color.parseColor("#DADDE5"), true)
        }
        val stBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status)
            if (teamView) addView(lbl(this@FootballActivity, ui, m.league.name, 24, Colors.MUTED))
        }
        line.addView(stBox, LinearLayout.LayoutParams(ui.px(if (teamView) 230 else 140), ViewGroup.LayoutParams.WRAP_CONTENT))
        line.addView(lbl(this, ui, m.home.short, 36, Colors.TEXT, true).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(crest(m.home.logo, 60), LinearLayout.LayoutParams(ui.px(60), ui.px(60)).apply { leftMargin = ui.px(20) })
        line.addView(TextView(this).apply {
            text = m.score()
            gravity = Gravity.CENTER
            setTextColor(if (m.state == "pre") Colors.MUTED else Colors.TEXT)
            setSizePx(ui, 48)
            typeface = Fonts.bold
            background = roundRect(ui, Color.parseColor("#07080C"), 16)
        }, LinearLayout.LayoutParams(ui.px(180), ui.px(76)).apply {
            leftMargin = ui.px(20)
            rightMargin = ui.px(20)
        })
        line.addView(crest(m.away.logo, 60), LinearLayout.LayoutParams(ui.px(60), ui.px(60)).apply { rightMargin = ui.px(20) })
        line.addView(lbl(this, ui, m.away.short, 36, Colors.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val res = resultOf(m)
        val label = lbl(this, ui, if (m.done) (if (teamView) "" else "Terminado") else if (prefs.configured) "Buscando canal…" else "", 24, Colors.MUTED, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        right.addView(label)
        if (res != null) right.addView(formChips(listOf(res)))
        line.addView(right, LinearLayout.LayoutParams(ui.px(300), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setTag(R.id.tv_label, label)
        row.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(112)))
        if (m.live && m.goals().isNotEmpty()) {
            row.addView(timeline(m, 20), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(40)).apply {
                leftMargin = ui.px(160)
                rightMargin = ui.px(320)
            })
        }
        return row
    }

    /** Barra 0–90' con los goles (local arriba, visitante abajo en verde claro). */
    private fun timeline(m: Football.Match, textSize: Int): View {
        val f = FrameLayout(this)
        val track = View(this).apply { background = roundRect(ui, Color.parseColor("#232838"), 3) }
        f.addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(6), Gravity.CENTER_VERTICAL))
        val fill = View(this).apply { background = roundRect(ui, Colors.TEXT, 3) }
        f.addView(fill, FrameLayout.LayoutParams(0, ui.px(6), Gravity.CENTER_VERTICAL))
        val marks = ArrayList<Pair<View, Int>>()
        for (g in m.goals()) {
            val home = g.teamId == m.home.id
            val tv = lbl(this, ui, g.minute, maxOf(textSize, 24), if (home) Colors.TEXT else Color.parseColor("#7DD3A0"), true)
            f.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (home) Gravity.TOP else Gravity.BOTTOM).apply {
                if (home) topMargin = -ui.px(14) else bottomMargin = -ui.px(14)
            })
            marks.add(Pair(tv, g.minuteInt()))
        }
        f.post {
            val w = f.width
            fill.layoutParams = FrameLayout.LayoutParams((w * (m.minuteNow().coerceIn(0, 95) / 95f)).toInt(), ui.px(6), Gravity.CENTER_VERTICAL)
            for ((tv, min) in marks) tv.translationX = (w * min.coerceIn(0, 95) / 95f) - tv.width / 2f
        }
        return f
    }

    // ───────────── clasificación ─────────────

    private fun showTable(lg: Football.League, rows: List<Football.Row>) {
        content.removeAllViews()
        pageTitle(lg.name, if (rows.isNotEmpty()) "Jornada " + rows.maxOf { it.played } else "")
        if (rows.isEmpty()) {
            content.addView(lbl(this, ui, "No se pudo cargar la clasificación.", 28, Colors.MUTED))
            return
        }
        val liga = lg.code == "esp.1"
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.px(6), 0, ui.px(18))
        }
        val zones = if (liga) listOf(Pair("#4C9AFF", "Champions"), Pair("#F5B544", "Europa League"), Pair("#2FB36B", "Conference"), Pair("#E5484D", "Descenso"))
        else listOf(Pair("#4C9AFF", "Octavos"), Pair("#F5B544", "Play-off"))
        for ((c, t) in zones) {
            legend.addView(View(this).apply { background = roundRect(ui, Color.parseColor(c), 4) }, LinearLayout.LayoutParams(ui.px(16), ui.px(16)).apply {
                rightMargin = ui.px(10)
                gravity = Gravity.CENTER_VERTICAL
            })
            legend.addView(lbl(this, ui, t, 24, Colors.MUTED).apply { setPadding(0, 0, ui.px(26), 0) })
        }
        content.addView(legend)
        content.addView(tableRow(null, "#", "Equipo", "PJ", "G", "E", "P", "DG", "Pts", Color.TRANSPARENT, false, true))
        val n = rows.size
        for ((i, r) in rows.withIndex()) {
            val zone = when {
                liga && r.rank <= 4 -> "#4C9AFF"
                liga && r.rank == 5 -> "#F5B544"
                liga && r.rank == 6 -> "#2FB36B"
                liga && r.rank > n - 3 -> "#E5484D"
                !liga && r.rank <= 8 -> "#4C9AFF"
                !liga && r.rank <= 24 -> "#F5B544"
                else -> "#00000000"
            }
            content.addView(tableRow(r, r.rank.toString(), r.team, r.played.toString(), r.w.toString(), r.d.toString(), r.l.toString(), r.gd, r.pts.toString(),
                Color.parseColor(zone), r.teamId == Football.TEAM_ID, false, i % 2 == 0))
        }
    }

    private fun tableRow(r: Football.Row?, vararg c: Any): View {
        val zone = c[8] as Int
        val mine = c[9] as Boolean
        val head = c[10] as Boolean
        val stripe = c.size > 11 && c[11] as Boolean
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(20), ui.px(10), ui.px(24), ui.px(10))
            background = when {
                mine -> roundRect(ui, Color.parseColor("#141734"), 16)
                stripe -> roundRect(ui, Colors.SURFACE, 16)
                else -> null
            }
            if (mine) foreground = ring(ui, Colors.TEXT, 16)
            isFocusable = !head
            setOnFocusChangeListener { v, has -> if (!mine) v.foreground = if (has) ring(ui, Colors.TEXT, 16) else null }
        }
        row.addView(View(this).apply { background = roundRect(ui, zone, 3) }, LinearLayout.LayoutParams(ui.px(6), ui.px(40)).apply { rightMargin = ui.px(14) })
        val widths = listOf(50, 0, 80, 70, 70, 70, 100, 100)
        for (i in 0 until 8) {
            if (i == 1 && r != null) {
                row.addView(crest(r.logo, 44),
                    LinearLayout.LayoutParams(ui.px(44), ui.px(44)).apply { rightMargin = ui.px(14) })
            } else if (i == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(ui.px(58), 1))
            }
            val tv = lbl(this, ui, c[i] as String, if (head) 24 else if (i == 7) 36 else 28, if (head) Color.parseColor("#8C95A8") else if (i == 2 || i == 6) Colors.MUTED else Colors.TEXT, head || i == 0 || i == 1 || i == 7).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = if (i == 1) Gravity.START else Gravity.CENTER
            }
            row.addView(tv, if (widths[i] == 0) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(ui.px(widths[i]), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return row
    }

    // ───────────── ficha del partido ─────────────

    private fun showDetail(m0: Football.Match, focusMain: Boolean) {
        detailOf = m0
        styleMenu()
        val my = ++token
        if (focusMain) skeleton()
        thread {
            val m = if (m0.done) m0 else Football.refresh(m0)
            val d = Football.detail(m)
            val homePos = Football.position(m.league, m.home.id)
            val awayPos = Football.position(m.league, m.away.id)
            runOnUiThread {
                if (my != token || isFinishing) return@runOnUiThread
                detailOf = m
                buildDetail(m, d, homePos, awayPos, focusMain)
            }
            if (prefs.configured && !m.done) {
                val ch = Football.findChannel(prefs, m)
                runOnUiThread {
                    if (my != token || isFinishing) return@runOnUiThread
                    val b = content.findViewWithTag<LinearLayout>("watch") ?: return@runOnUiThread
                    val t = b.getChildAt(b.childCount - 1) as? TextView ?: return@runOnUiThread
                    if (ch != null) {
                        t.text = (if (m.live) "Ver en " else "Se verá en ") + ch.name
                        b.setOnClickListener {
                            Xtream.queue = prefs.visibleChannels(Xtream.channels)
                            startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", ch.id))
                        }
                    } else {
                        t.text = "No lo encuentro en tu lista"
                    }
                }
            }
        }
    }

    private fun panel(titleText: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(ui, Colors.SURFACE, 26)
        setPadding(ui.px(34), ui.px(28), ui.px(34), ui.px(28))
        isFocusable = true
        setOnFocusChangeListener { v, has -> v.foreground = if (has) ring(ui, Colors.TEXT, 26) else null }
        addView(lbl(this@FootballActivity, ui, titleText, 24, Colors.MUTED, true).apply { setPadding(0, 0, 0, ui.px(16)) })
    }

    private fun buildDetail(m: Football.Match, d: Football.Detail, hp: Football.Row?, ap: Football.Row?, focusMain: Boolean) {
        val keepY = scroll.scrollY
        content.removeAllViews()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, ui.px(20))
        }
        top.addView(pill(this, ui, m.league.name, Football.leagueColor(m.league)))
        top.addView(lbl(this, ui, (if (m.venue.isNotBlank()) m.venue + " · " else "") + SimpleDateFormat("EEEE d MMMM", es).format(Date(m.start)), 24, Colors.MUTED).apply { setPadding(ui.px(16), 0, 0, 0) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(lbl(this, ui, "Atrás · volver", 24, Colors.MUTED))
        content.addView(top)

        // cabecera con los colores de los dos equipos
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(dim(m.home.colorInt()), Color.parseColor("#12151D"), Color.parseColor("#12151D"), dim(m.away.colorInt()))).apply { cornerRadius = ui.px(32).toFloat() }
            setPadding(ui.px(56), ui.px(30), ui.px(56), ui.px(26))
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun teamCol(s: Football.Side, pos: Football.Row?, home: Boolean): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = (if (home) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            val names = LinearLayout(this@FootballActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (home) Gravity.END else Gravity.START
                addView(lbl(this@FootballActivity, ui, s.short, 48, Colors.TEXT, true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (pos != null) addView(lbl(this@FootballActivity, ui, pos.rank.toString() + ".º · " + pos.pts + " pts", 24, SOFT))
            }
            val c = crest(s.logo, 124)
            if (home) {
                addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(c, LinearLayout.LayoutParams(ui.px(124), ui.px(124)).apply { leftMargin = ui.px(28) })
            } else {
                addView(c, LinearLayout.LayoutParams(ui.px(124), ui.px(124)).apply { rightMargin = ui.px(28) })
                addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        line.addView(teamCol(m.home, hp, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        if (m.state == "pre") {
            mid.addView(lbl(this, ui, SimpleDateFormat("EEEE d", es).format(Date(m.start)), 28, SOFT).apply { gravity = Gravity.CENTER })
            mid.addView(lbl(this, ui, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.start)), 96, Colors.TEXT, true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            mid.addView(pill(this, ui, daysText(m.start), Color.argb(36, 255, 255, 255), Colors.TEXT))
        } else {
            mid.addView(lbl(this, ui, m.score(), 120, Colors.TEXT, true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            mid.addView(if (m.live) pill(this, ui, "●  " + m.status(), RED, size = 28) else pill(this, ui, "Final", Color.argb(36, 255, 255, 255), Colors.TEXT, 28))
        }
        line.addView(mid, LinearLayout.LayoutParams(ui.px(360), ViewGroup.LayoutParams.WRAP_CONTENT))
        line.addView(teamCol(m.away, ap, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(line)
        if (m.state != "pre" && m.events.isNotEmpty()) head.addView(eventTimeline(m), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(84)).apply { topMargin = ui.px(18) })
        content.addView(head)

        // botones
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.px(24), 0, ui.px(24))
            clipChildren = false
        }
        var main: View? = null
        when (m.state) {
            "pre" -> {
                val id = "m_" + m.id
                val set = Reminders.has(this, id) || Reminders.has(this, "auto_" + m.id)
                val b = bigButton(this, ui, if (set) "Te avisaré 10 min antes" else "Avísame 10 min antes", !set, R.drawable.ic_bell) {
                    if (Reminders.has(this, id) || Reminders.has(this, "auto_" + m.id)) {
                        Reminders.remove(this, id)
                        Reminders.remove(this, "auto_" + m.id)
                        Toast.makeText(this, "Aviso quitado", Toast.LENGTH_SHORT).show()
                    } else {
                        Reminders.add(this, Reminders.Item(id, m.start, "match", m.home.short + " – " + m.away.short, m.league.name, m.id))
                        val at = SimpleDateFormat("EEEE 'a las' HH:mm", es).format(Date(m.start - Reminders.BEFORE))
                        Toast.makeText(this, "Te avisaré el $at, estés en la app que estés", Toast.LENGTH_LONG).show()
                        if (!OverlayNotice.canShow(this)) Toast.makeText(this, "Para avisos encima de otras apps, da el permiso en Ajustes → Avisos", Toast.LENGTH_LONG).show()
                    }
                    showDetail(m, false)
                }
                btns.addView(b)
                main = b
                val w = bigButton(this, ui, if (prefs.configured) "Buscando dónde se verá…" else "Configura tu lista para verlo", false, R.drawable.ic_tv) { }
                w.tag = "watch"
                btns.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(20) })
            }
            "in" -> {
                val w = bigButton(this, ui, if (prefs.configured) "Buscando el canal…" else "Configura tu lista para verlo", true, R.drawable.ic_play) { }
                w.tag = "watch"
                btns.addView(w)
                main = w
            }
            else -> {
                val q = m.home.short + " " + m.away.short + " resumen"
                val b = bigButton(this, ui, "Ver resumen en SmartTube", true, R.drawable.ic_play) { openHighlights(q) }
                btns.addView(b)
                main = b
                btns.addView(lbl(this, ui, "Busca «$q»", 24, Colors.MUTED).apply { setPadding(ui.px(24), 0, 0, 0) })
            }
        }
        btns.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (m.live) btns.addView(lbl(this, ui, "Se actualiza cada 30 s", 24, Color.parseColor("#8C95A8")))
        content.addView(btns)

        // paneles
        val panels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (m.state == "pre") {
            val f = panel("Últimos 5 partidos")
            for ((s, form) in listOf(Pair(m.home, d.homeForm), Pair(m.away, d.awayForm))) {
                f.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, ui.px(8), 0, ui.px(8))
                    addView(crest(s.logo, 48), LinearLayout.LayoutParams(ui.px(48), ui.px(48)).apply { rightMargin = ui.px(18) })
                    addView(lbl(this@FootballActivity, ui, s.short, 28, Colors.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(if (form.isEmpty()) lbl(this@FootballActivity, ui, "—", 28, Colors.MUTED) else formChips(form, 44))
                })
            }
            panels.addView(f, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val c = panel("Clasificación · " + m.league.name)
            for ((s, p) in listOf(Pair(m.home, hp), Pair(m.away, ap)).sortedBy { it.second?.rank ?: 99 }) {
                c.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, ui.px(8), 0, ui.px(8))
                    val mine = s.id == Football.TEAM_ID
                    addView(lbl(this@FootballActivity, ui, (p?.rank?.toString()?.plus(".º  ") ?: "") + s.short, 28, if (mine) Colors.ACCENT else Colors.TEXT, mine), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(lbl(this@FootballActivity, ui, (p?.pts?.toString() ?: "—") + " pts", 28, Colors.TEXT, true))
                })
            }
            panels.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.px(28) })
            val info = panel("Datos del partido")
            info.addView(lbl(this, ui, m.league.name, 28, Colors.TEXT, true))
            if (m.venue.isNotBlank()) info.addView(lbl(this, ui, m.venue, 28, Colors.MUTED).apply { setPadding(0, ui.px(8), 0, 0) })
            info.addView(lbl(this, ui, SimpleDateFormat("EEEE d 'de' MMMM · HH:mm", es).format(Date(m.start)), 28, Colors.MUTED).apply { setPadding(0, ui.px(8), 0, 0) })
            panels.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.px(28) })
        } else {
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (m.events.isNotEmpty()) {
                val ev = panel("Goles y tarjetas")
                for (e in m.events) ev.addView(eventLine(m, e))
                left.addView(ev, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(28) })
            }
            if (d.stats.isNotEmpty()) {
                val st = panel("Estadísticas")
                st.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(lbl(this@FootballActivity, ui, m.home.short, 24, lighten(m.home.colorInt()), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(lbl(this@FootballActivity, ui, m.away.short, 24, lighten(m.away.colorInt()), true))
                })
                for (s in d.stats) st.addView(statRow(s, lighten(m.home.colorInt()), lighten(m.away.colorInt())))
                left.addView(st)
            }
            panels.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (d.home != null || d.away != null) {
                val p = panel("Alineaciones")
                p.addView(pitch(m, d), LinearLayout.LayoutParams(ui.px(1000), ui.px(470)))
                panels.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(28) })
            }
        }
        content.addView(panels)
        if (focusMain) main?.requestFocus() else scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun daysText(at: Long): String {
        val days = ((at - System.currentTimeMillis()) / (24 * 3600_000L)).toInt()
        return when {
            at < System.currentTimeMillis() -> "Ya empezó"
            days == 0 -> "Hoy"
            days == 1 -> "Mañana"
            else -> "En $days días"
        }
    }

    private fun dim(c: Int): Int = Color.rgb((Color.red(c) * 0.32).toInt(), (Color.green(c) * 0.32).toInt(), (Color.blue(c) * 0.32).toInt())

    /** Color del equipo pero visible sobre fondo oscuro (el blanco y negro se cambian por tonos legibles) */
    private fun lighten(c: Int): Int {
        val l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
        return if (l < 0.25) Color.parseColor("#8C95A8") else c
    }

    private fun eventTimeline(m: Football.Match): View {
        val f = FrameLayout(this)
        f.addView(View(this).apply { background = roundRect(ui, Color.argb(30, 255, 255, 255), 3) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(6), Gravity.CENTER_VERTICAL))
        val fill = View(this).apply { background = roundRect(ui, Color.argb(180, 255, 255, 255), 3) }
        f.addView(fill, FrameLayout.LayoutParams(0, ui.px(6), Gravity.CENTER_VERTICAL))
        val marks = ArrayList<Pair<View, Int>>()
        for (e in m.events) {
            val home = e.teamId == m.home.id
            val mk = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (e.goal) addView(vIcon(this@FootballActivity, R.drawable.ic_ball, Colors.TEXT), LinearLayout.LayoutParams(ui.px(22), ui.px(22)).apply { rightMargin = ui.px(6) })
                else addView(View(this@FootballActivity).apply { background = roundRect(ui, if (e.red) Color.parseColor("#E5484D") else Color.parseColor("#F5C518"), 3) }, LinearLayout.LayoutParams(ui.px(16), ui.px(22)).apply { rightMargin = ui.px(6) })
                addView(lbl(this@FootballActivity, ui, e.minute + " " + e.player.substringAfterLast(' '), 24, if (e.goal) Colors.TEXT else Color.parseColor("#DADDE5"), e.goal))
            }
            f.addView(mk, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (home) Gravity.TOP else Gravity.BOTTOM))
            marks.add(Pair(mk, e.minuteInt()))
        }
        f.post {
            val w = f.width
            fill.layoutParams = FrameLayout.LayoutParams((w * (m.minuteNow().coerceIn(0, 95) / 95f)).toInt(), ui.px(6), Gravity.CENTER_VERTICAL)
            for ((v, min) in marks) v.translationX = ((w * min.coerceIn(0, 95) / 95f) - v.width / 2f).coerceIn(0f, maxOf(0f, (w - v.width).toFloat()))
        }
        return f
    }

    private fun eventLine(m: Football.Match, e: Football.Event): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.px(10), 0, ui.px(10))
        addView(lbl(this@FootballActivity, ui, e.minute, 28, Colors.ACCENT, true), LinearLayout.LayoutParams(ui.px(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (e.goal) addView(vIcon(this@FootballActivity, R.drawable.ic_ball, Colors.TEXT), LinearLayout.LayoutParams(ui.px(28), ui.px(28)).apply { rightMargin = ui.px(16) })
        else addView(View(this@FootballActivity).apply { background = roundRect(ui, if (e.red) Color.parseColor("#E5484D") else Color.parseColor("#F5C518"), 3) }, LinearLayout.LayoutParams(ui.px(20), ui.px(28)).apply {
            leftMargin = ui.px(4)
            rightMargin = ui.px(20)
        })
        addView(LinearLayout(this@FootballActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(lbl(this@FootballActivity, ui, e.player.ifBlank { e.kind }, 28, Colors.TEXT, true))
            addView(lbl(this@FootballActivity, ui, (if (e.teamId == m.home.id) m.home.short else m.away.short) + " · " + e.kind.lowercase(), 24, Colors.MUTED))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun statRow(s: Football.Stat, ca: Int, cb: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.px(10), 0, ui.px(10))
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(lbl(this@FootballActivity, ui, s.home, 36, Colors.TEXT, true), LinearLayout.LayoutParams(ui.px(130), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(lbl(this@FootballActivity, ui, s.label, 24, Colors.MUTED, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(lbl(this@FootballActivity, ui, s.away, 36, Colors.TEXT, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(ui.px(130), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        val a = s.home.trimEnd('%').toFloatOrNull() ?: 0f
        val b = s.away.trimEnd('%').toFloatOrNull() ?: 0f
        val mx = maxOf(a, b, 0.0001f)
        // barras en espejo: cada una crece desde el centro hacia su equipo
        val bars = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        l.addView(View(this), LinearLayout.LayoutParams(0, ui.px(12), 1f - a / mx + 0.0001f))
        l.addView(View(this).apply { background = roundRect(ui, ca, 6) }, LinearLayout.LayoutParams(0, ui.px(12), a / mx + 0.0001f))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(View(this).apply { background = roundRect(ui, cb, 6) }, LinearLayout.LayoutParams(0, ui.px(12), b / mx + 0.0001f))
        r.addView(View(this), LinearLayout.LayoutParams(0, ui.px(12), 1f - b / mx + 0.0001f))
        bars.addView(l, LinearLayout.LayoutParams(0, ui.px(12), 1f))
        bars.addView(View(this), LinearLayout.LayoutParams(ui.px(8), 1))
        bars.addView(r, LinearLayout.LayoutParams(0, ui.px(12), 1f))
        box.addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(12)).apply { topMargin = ui.px(8) })
        return box
    }

    /** Alineaciones dibujadas sobre el campo según la formación (local a la izquierda). */
    private fun pitch(m: Football.Match, d: Football.Detail): View {
        val f = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#1B4A2A"), Color.parseColor("#1F5530"), Color.parseColor("#1B4A2A"))).apply { cornerRadius = ui.px(24).toFloat() }
            clipToOutline = true
        }
        val line = Color.argb(70, 255, 255, 255)
        f.addView(View(this).apply { background = ring(ui, line, 6).apply { setStroke(ui.px(3), line) } }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(ui.px(18), ui.px(18), ui.px(18), ui.px(18)) })
        f.addView(View(this).apply { setBackgroundColor(line) }, FrameLayout.LayoutParams(ui.px(3), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL).apply { setMargins(0, ui.px(18), 0, ui.px(18)) })
        f.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(ui.px(3), line) } }, FrameLayout.LayoutParams(ui.px(120), ui.px(120), Gravity.CENTER))
        val w = 1000
        val h = 470
        fun place(l: Football.Lineup?, home: Boolean, fill: Int, ink: Int) {
            if (l == null || l.starters.isEmpty()) return
            val rows = l.formation.split("-").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(4, 4, 2) }
            val lines = ArrayList<List<Football.Player>>()
            lines.add(l.starters.take(1))
            var i = 1
            for (n in rows) {
                lines.add(l.starters.drop(i).take(n))
                i += n
            }
            val nLines = lines.size
            for ((li, players) in lines.withIndex()) {
                val xFrac = 0.05f + li * (0.40f / maxOf(1, nLines - 1))
                val x = if (home) xFrac else 1f - xFrac
                for ((pi, p) in players.withIndex()) {
                    val y = (pi + 1f) / (players.size + 1f)
                    val dot = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    dot.addView(TextView(this).apply {
                        text = p.num
                        gravity = Gravity.CENTER
                        setTextColor(ink)
                        setSizePx(ui, 24)
                        typeface = Fonts.bold
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(fill)
                        }
                    }, LinearLayout.LayoutParams(ui.px(48), ui.px(48)))
                    dot.addView(lbl(this, ui, p.name.take(11), 24, Colors.TEXT, true).apply { setShadowLayer(6f, 0f, 2f, Color.BLACK) })
                    f.addView(dot, FrameLayout.LayoutParams(ui.px(170), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = ui.px((x * w).toInt() - 85)
                        topMargin = ui.px((y * h).toInt() - 40)
                    })
                }
            }
        }
        val hc = m.home.colorInt()
        val ac = m.away.colorInt()
        place(d.home, true, hc, Colors.onColor(hc))
        place(d.away, false, if (ac == hc) Color.WHITE else ac, if (ac == hc) Color.BLACK else Colors.onColor(ac))
        return f
    }

    /** Resumen: SmartTube con la búsqueda hecha (si no, YouTube en el navegador). */
    private fun openHighlights(q: String) {
        val pkgs = listOf("com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta")
        for (p in pkgs) {
            try {
                startActivity(Intent(Intent.ACTION_SEARCH).setPackage(p).putExtra(android.app.SearchManager.QUERY, q))
                return
            } catch (e: Exception) {
                // se prueba la siguiente forma
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).setPackage(p))
                return
            } catch (e: Exception) {
                // se prueba la siguiente
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        } catch (e: Exception) {
            Toast.makeText(this, "Instala SmartTube para ver los resúmenes", Toast.LENGTH_LONG).show()
        }
    }
}
