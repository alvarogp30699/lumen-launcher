package com.alvaro.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Pantalla de inicio. Arriba, una tarjeta grande que cambia sola según el momento
 * (Madrid en directo, fin de semana de F1, próximo partido, seguir viendo); ◀ ▶ pasa entre ellas.
 * Debajo: «Para ti», aplicaciones, favoritos y accesos.
 */
class MainActivity : Activity() {

    companion object {
        val iconCache = HashMap<String, android.graphics.drawable.Drawable.ConstantState>()
        val bannerCache = HashMap<String, android.graphics.drawable.Drawable.ConstantState>()

        /** Último Gran Premio leído (para saber al abrir DAZN si es fin de semana de F1). */
        @Volatile
        var lastF1: F1Data.Event? = null

        private const val VOICE = 41
        private val PURPLE = Color.parseColor("#5B4FD0")
        private val F1RED = Color.parseColor("#E10600")
    }

    private val defaultNames = mapOf(
        "com.teamsmart.videomanager.tv" to "SmartTube",
        "com.liskovsoft.smarttubetv.beta" to "SmartTube",
        "com.dazn" to "DAZN",
        "com.esaba.downloader" to "Downloader",
        "com.android.vending" to "Play Store"
    )

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private var lastSig = ""
    private var token = 0

    private lateinit var page: ScrollView
    private lateinit var frame: FrameLayout
    private lateinit var heroBox: FrameLayout
    private lateinit var paraTi: LinearLayout
    private lateinit var paraTiBox: LinearLayout
    private lateinit var warnBox: LinearLayout
    private var voiceLayer: View? = null

    /** Tarjetas grandes disponibles ahora y la que se ve */
    private class Slide(val kind: String, val match: Football.Match? = null, val f1: F1Data.Event? = null)

    private var slides: List<Slide> = emptyList()
    private var slideIdx = 0
    private val f1ChannelWords = listOf("f1", "formula 1", "fórmula 1", "formula1", "fórmula1")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
    }

    override fun onResume() {
        super.onResume()
        prefs = Prefs(this)
        Updater.checkInBackground(this)
        // al volver al inicio se quita PITWALL de encima de DAZN
        PitwallOverlay.stop(this)
        if (showWhatsNew()) return
        if (signature() != lastSig) build()
        offerUpdate()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (voiceLayer != null) closeVoice()
        // en la pantalla de inicio, Atrás no hace nada más
    }

    /** Novedades tras actualizar (no en la primera instalación). */
    private fun showWhatsNew(): Boolean {
        val code = Updater.installedCode(this)
        if (prefs.seenCode == 0) {
            prefs.seenCode = code
            return false
        }
        if (code > prefs.seenCode) {
            prefs.seenCode = code
            startActivity(Intent(this, WhatsNewActivity::class.java))
            return true
        }
        return false
    }

    /** Resumen de lo que se ve en el inicio: si no cambia, no se reconstruye (Home es instantáneo). */
    private fun signature(): String = listOf(
        prefs.profile, prefs.profileNames.joinToString(","),
        prefs.pinned.joinToString(",") { it + ":" + isInstalled(resolve(it)) },
        prefs.configured, prefs.pitwallUrl, prefs.lastChannelName, prefs.latestCode, prefs.accent, prefs.lastApp,
        prefs.favorites.joinToString(","), Updater.installedCode(this), System.currentTimeMillis() / 300_000L
    ).joinToString("|")

    // ─────────────────────────────── construcción ───────────────────────────────

    private fun build() {
        lastSig = signature()
        val my = ++token
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(96), ui.px(44), ui.px(96), ui.px(56))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(topBar())
        warnBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(warnBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(24) })

        heroBox = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            clipChildren = false
            val edge = ring(ui, Colors.TEXT, 32)
            setOnFocusChangeListener { v, has ->
                v.foreground = if (has) edge else null
                v.translationZ = if (has) ui.px(16).toFloat() else 0f
            }
            setOnKeyListener { _, keyCode, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveSlide(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveSlide(1)
                        true
                    }
                    else -> false
                }
            }
            setOnClickListener { slideAction() }
        }
        root.addView(heroBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(420)).apply { topMargin = ui.px(28) })
        heroBox.addView(skeletonHero(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        paraTiBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        paraTiBox.addView(sectionLabel("Para ti"))
        paraTi = tileRow()
        paraTiBox.addView(paraTi, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(178)))
        root.addView(paraTiBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(32) })
        fillParaTi(null, null, null)

        // ---- aplicaciones ----
        root.addView(sectionLabel("Aplicaciones").apply { setPadding(0, ui.px(36), 0, ui.px(16)) })
        val apps = tileRow()
        addTile(apps, makeTile(this, ui, "Lumen TV", null, false, onClick = {
            startActivity(Intent(this, IptvActivity::class.java))
        }, glyphColor = Colors.ACCENT, iconRes = R.drawable.ic_play))
        for (saved in prefs.pinned) {
            val pkg = resolve(saved)
            val installed = isInstalled(pkg)
            val name = if (installed) appLabel(pkg) else (defaultNames[pkg] ?: pkg)
            addTile(apps, makeTile(this, ui, name, if (installed) cachedIcon(pkg) else null, !installed, onClick = {
                if (!installed) {
                    Toast.makeText(this, "$name no está instalada. Instálala con Downloader o desde Play Store.", Toast.LENGTH_LONG).show()
                } else {
                    if (pkg == "com.dazn" && lastF1?.let { F1Data.isWeekend(it, System.currentTimeMillis()) } == true) PitwallOverlay.start(this)
                    if (launchPackage(this, pkg)) prefs.lastApp = pkg
                }
            }, glyph = if (!installed) name.take(1).uppercase() else null, glyphColor = Colors.SURFACE2))
        }
        addTile(apps, makeTile(this, ui, "Todas", null, false, onClick = {
            startActivity(Intent(this, AppsActivity::class.java))
        }, glyphColor = Color.parseColor("#232838"), iconRes = R.drawable.ic_grid))
        root.addView(scrollOf(apps))

        // ---- favoritos ----
        favoritesRow()?.let { root.addView(it) }

        // ---- accesos ----
        root.addView(sectionLabel("Accesos").apply { setPadding(0, ui.px(36), 0, ui.px(16)) })
        val acc = tileRow()
        addSmall(acc, makeTile(this, ui, "Fútbol", null, false, onClick = {
            startActivity(Intent(this, FootballActivity::class.java))
        }, glyphColor = Color.parseColor("#2FB36B"), compact = true, iconRes = R.drawable.ic_ball))
        addSmall(acc, makeTile(this, ui, "Semana", null, false, onClick = {
            startActivity(Intent(this, SemanaActivity::class.java))
        }, glyphColor = Color.parseColor("#4C9AFF"), compact = true, iconRes = R.drawable.ic_calendar))
        if (prefs.pitwallUrl.isNotBlank()) {
            addSmall(acc, makeTile(this, ui, "Calendario F1", null, false, onClick = {
                startActivity(Intent(this, F1Activity::class.java))
            }, glyphColor = F1RED, compact = true, iconRes = R.drawable.ic_flag))
        }
        addSmall(acc, makeTile(this, ui, "Ajustes", null, false, onClick = {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, glyphColor = Color.parseColor("#3A4256"), compact = true, iconRes = R.drawable.ic_sliders))
        addSmall(acc, makeTile(this, ui, "Configurar", null, false, onClick = {
            startActivity(Intent(this, SetupActivity::class.java))
        }, glyphColor = Color.parseColor("#1D9E75"), compact = true, iconRes = R.drawable.ic_phone))
        root.addView(scrollOf(acc))

        page = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            isSmoothScrollingEnabled = true
            background = homeBackground(ui, Colors.ACCENT)
            clipChildren = false
            addView(root)
        }
        frame = FrameLayout(this)
        frame.addView(page)
        voiceLayer = null
        setContentView(frame)
        heroBox.requestFocus()

        checkHealth()
        loadHero(my)
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.splash_logo) }, LinearLayout.LayoutParams(ui.px(38), ui.px(38)))
        bar.addView(lbl(this, ui, "Lumen", 36, Colors.TEXT, true).apply { setPadding(ui.px(16), 0, 0, 0) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (prefs.latestCode > Updater.installedCode(this)) {
            bar.addView(pill(this, ui, "Actualización disponible", Colors.ACCENT), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.px(28) })
        }
        bar.addView(TextClock(this).apply {
            format24Hour = "EEE d MMM"
            format12Hour = "EEE d MMM"
            setTextColor(Colors.MUTED)
            setSizePx(ui, 28)
        })
        bar.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 36)
            typeface = Fonts.bold
            setPadding(ui.px(20), 0, ui.px(24), 0)
        })
        bar.addView(vIcon(this, if (isOnline()) R.drawable.ic_wifi else R.drawable.ic_wifi_off, if (isOnline()) Colors.MUTED else Color.parseColor("#E5484D")),
            LinearLayout.LayoutParams(ui.px(32), ui.px(32)).apply { rightMargin = ui.px(24) })
        // perfil: avatar con la inicial + nombre; OK cambia de perfil
        val name = prefs.profileNames[prefs.profile]
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(ui, Colors.SURFACE, 40)
            setPadding(ui.px(6), ui.px(6), ui.px(22), ui.px(6))
            isFocusable = true
            isClickable = true
            val edge = ring(ui, Colors.TEXT, 40)
            setOnFocusChangeListener { v, has -> v.foreground = if (has) edge else null }
            setOnClickListener {
                prefs.profile = (prefs.profile + 1) % 3
                IptvActivity.unlocked.clear()
                Xtream.queue = emptyList()
                build()
            }
        }
        chip.addView(TextView(this).apply {
            text = name.take(1).uppercase()
            gravity = Gravity.CENTER
            setTextColor(Colors.onColor())
            setSizePx(ui, 24)
            typeface = Fonts.bold
            background = roundRect(ui, Colors.ACCENT, 22)
        }, LinearLayout.LayoutParams(ui.px(44), ui.px(44)).apply { rightMargin = ui.px(12) })
        chip.addView(lbl(this, ui, name, 24, Colors.TEXT, true))
        bar.addView(chip)
        return bar
    }

    private fun sectionLabel(t: String): TextView = lbl(this, ui, t, 28, Colors.MUTED, true).apply {
        setPadding(0, 0, 0, ui.px(16))
    }

    private fun tileRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    private fun scrollOf(row: LinearLayout): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        setPadding(ui.px(14), ui.px(14), ui.px(14), ui.px(14))
        addView(row)
    }

    private fun addTile(row: LinearLayout, tile: View) {
        row.addView(tile, LinearLayout.LayoutParams(ui.px(260), ui.px(150)).apply { rightMargin = ui.px(28) })
    }

    private fun addSmall(row: LinearLayout, tile: View) {
        row.addView(tile, LinearLayout.LayoutParams(ui.px(250), ui.px(118)).apply { rightMargin = ui.px(24) })
    }

    // ─────────────────────────────── avisos de estado ───────────────────────────────

    private fun checkHealth() {
        if (!prefs.configured && prefs.pitwallUrl.isBlank()) {
            warnBox.addView(stateCard(this, ui, R.drawable.ic_phone, Color.parseColor("#1D9E75"), "Configura Lumen desde el móvil",
                "Escanea un código QR y escribe tu lista IPTV en el móvil. No hace falta el mando.", "Empezar", {
                    startActivity(Intent(this, SetupActivity::class.java))
                }, compact = true))
            return
        }
        if (!isOnline()) {
            warnBox.addView(stateCard(this, ui, R.drawable.ic_wifi_off, Color.parseColor("#E5484D"), "Sin conexión a internet",
                "El stick no llega al router. Los canales, el fútbol y la F1 vuelven solos en cuanto haya red.", "Ajustes de Wi-Fi", {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    } catch (e: Exception) {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    }
                }, compact = true))
            return
        }
        val my = token
        thread {
            val msgs = Health.check(prefs)
            runOnUiThread {
                if (my != token || isFinishing) return@runOnUiThread
                for (m in msgs.take(2)) {
                    warnBox.addView(healthCard(this, ui, m, { build() }, { startActivity(Intent(this, SetupActivity::class.java)) }),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(16) })
                }
            }
        }
    }

    // ─────────────────────────────── tarjeta grande ───────────────────────────────

    private fun loadHero(my: Int) {
        thread {
            val now = System.currentTimeMillis()
            val team = try {
                Football.teamFocus()
            } catch (e: Exception) {
                null
            }
            val f1 = F1Data.next(prefs)
            if (f1 != null) lastF1 = f1
            val today = try {
                Football.today()
            } catch (e: Exception) {
                emptyList()
            }
            // próximo partido del Madrid: aviso 10 min antes y vigilante de goles
            Football.teamMatches().firstOrNull { it.state == "pre" }?.let { Reminders.autoMadrid(applicationContext, it) }

            val l = ArrayList<Slide>()
            if (team != null && team.live) l.add(Slide("live", team))
            if (f1 != null && F1Data.isWeekend(f1, now)) l.add(Slide("f1", f1 = f1))
            if (team != null && team.state == "pre" && team.start - now < 7 * 24 * 3600_000L) l.add(Slide("next", team))
            if (team != null && team.done && now - team.start < 20 * 3600_000L) l.add(Slide("final", team))
            if (prefs.configured && prefs.lastChannelName.isNotBlank()) l.add(Slide("cont"))
            if (l.isEmpty()) l.add(Slide(if (prefs.configured) "today" else "welcome"))
            runOnUiThread {
                if (my != token || isFinishing) return@runOnUiThread
                slides = l
                slideIdx = 0
                showSlide()
                fillParaTi(team, f1, today)
            }
        }
    }

    private fun moveSlide(d: Int) {
        if (slides.size < 2) return
        slideIdx = (slideIdx + d + slides.size) % slides.size
        showSlide()
    }

    private fun slideColor(s: Slide?): Int = when (s?.kind) {
        "live", "next", "final" -> PURPLE
        "f1" -> F1RED
        else -> Colors.ACCENT
    }

    private fun showSlide() {
        val s = slides.getOrNull(slideIdx) ?: return
        val v = when (s.kind) {
            "live", "next", "final" -> madridSlide(s.match!!, s.kind)
            "f1" -> f1Slide(s.f1!!)
            "cont" -> continueSlide()
            "today" -> todaySlide()
            else -> welcomeSlide()
        }
        heroBox.removeAllViews()
        v.alpha = 0f
        heroBox.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.animate().alpha(1f).setDuration(220).start()
        page.background = homeBackground(ui, slideColor(s))
    }

    private fun slideAction() {
        val s = slides.getOrNull(slideIdx) ?: return
        when (s.kind) {
            "live" -> {
                val m = s.match!!
                thread {
                    val ch = if (prefs.configured) Football.findChannel(prefs, m) else null
                    runOnUiThread {
                        if (ch != null) {
                            Xtream.queue = prefs.visibleChannels(Xtream.channels)
                            startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", ch.id))
                        } else {
                            startActivity(Intent(this, FootballActivity::class.java).putExtra("match", m.id))
                        }
                    }
                }
            }
            "next", "final" -> startActivity(Intent(this, FootballActivity::class.java).putExtra("match", s.match!!.id))
            "f1" -> openDazn()
            "cont" -> startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", prefs.lastChannelId))
            "today" -> startActivity(Intent(this, FootballActivity::class.java))
            else -> startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun openDazn() {
        val pkg = "com.dazn"
        if (isInstalled(pkg)) {
            PitwallOverlay.start(this)
            if (launchPackage(this, pkg)) prefs.lastApp = pkg
        } else {
            startActivity(Intent(this, F1Activity::class.java))
        }
    }

    private fun dots(color: Int = Colors.TEXT): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (slides.size < 2) return box
        for (i in slides.indices) {
            val on = i == slideIdx
            box.addView(View(this).apply { background = roundRect(ui, if (on) color else Colors.alpha(color, 0x5A), 4) },
                LinearLayout.LayoutParams(ui.px(if (on) 36 else 8), ui.px(8)).apply { leftMargin = ui.px(10) })
        }
        return box
    }

    private fun card(bg: Int, radius: Int = 32): FrameLayout = FrameLayout(this).apply {
        background = roundRect(ui, bg, radius)
        clipToOutline = true
    }

    private fun crest(url: String, size: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        Logos.load(url, this, ui.px(size)) { }
    }

    /** Escudo enorme y muy tenue como fondo de la tarjeta */
    private fun watermark(box: FrameLayout, url: String, left: Boolean, alpha: Float) {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            this.alpha = alpha
        }
        Logos.load(url, iv, ui.px(300)) { }
        box.addView(iv, FrameLayout.LayoutParams(ui.px(620), ui.px(620)).apply {
            gravity = (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            if (left) leftMargin = -ui.px(80) else rightMargin = -ui.px(80)
        })
    }

    private fun daysText(at: Long): String {
        val today = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val d0 = today.get(Calendar.YEAR) * 400 + today.get(Calendar.DAY_OF_YEAR)
        val d1 = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
        return when (val n = d1 - d0) {
            0 -> "Hoy"
            1 -> "Mañana"
            else -> "En $n días"
        }
    }

    private fun madridSlide(m: Football.Match, kind: String): View {
        val box = card(Color.parseColor("#141734"))
        watermark(box, m.home.logo, true, 0.08f)
        watermark(box, m.away.logo, false, 0.10f)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(56), ui.px(36), ui.px(56), ui.px(32))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (kind == "live") top.addView(pill(this, ui, "●  EN DIRECTO", F1RED), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.px(14) })
        top.addView(pill(this, ui, m.league.name, Football.leagueColor(m.league)))
        if (m.venue.isNotBlank()) top.addView(lbl(this, ui, m.venue, 24, Color.parseColor("#C3C7E6")).apply { setPadding(ui.px(18), 0, 0, 0) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(lbl(this, ui, "Tu equipo", 24, Color.parseColor("#C3C7E6"), true))
        col.addView(top)

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mid.addView(lbl(this, ui, m.home.short, 48, Colors.TEXT, true).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mid.addView(crest(m.home.logo, 132), LinearLayout.LayoutParams(ui.px(132), ui.px(132)).apply { leftMargin = ui.px(28) })
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        if (kind == "next") {
            center.addView(lbl(this, ui, SimpleDateFormat("EEEE", Locale("es", "ES")).format(Date(m.start)), 28, Color.parseColor("#C3C7E6")).apply { gravity = Gravity.CENTER })
            center.addView(lbl(this, ui, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.start)), 96, Colors.TEXT, true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
        } else {
            center.addView(lbl(this, ui, m.score(), 120, Colors.TEXT, true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            center.addView(lbl(this, ui, m.status(), 36, if (kind == "live") Color.parseColor("#FF8A8A") else Colors.MUTED, true).apply { gravity = Gravity.CENTER })
        }
        mid.addView(center, LinearLayout.LayoutParams(ui.px(340), ViewGroup.LayoutParams.WRAP_CONTENT))
        mid.addView(crest(m.away.logo, 132), LinearLayout.LayoutParams(ui.px(132), ui.px(132)).apply { rightMargin = ui.px(28) })
        mid.addView(lbl(this, ui, m.away.short, 48, Colors.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (kind != "next" && m.goals().isNotEmpty()) col.addView(goalTimeline(m), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(54)).apply {
            leftMargin = ui.px(40)
            rightMargin = ui.px(40)
            bottomMargin = ui.px(12)
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = lbl(this, ui, "", 24, Color.parseColor("#C3C7E6"))
        when (kind) {
            "next" -> {
                bottom.addView(pill(this, ui, daysText(m.start), Color.argb(40, 255, 255, 255), Colors.TEXT), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.px(18) })
                info.text = if (prefs.configured) "Buscando dónde se ve…" else ""
            }
            "live" -> info.text = m.goals().joinToString("  ·  ") { it.player.substringAfterLast(' ') + " " + it.minute }
            else -> info.text = "Final · " + m.goals().joinToString("  ·  ") { it.player.substringAfterLast(' ') + " " + it.minute }
        }
        bottom.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(dots())
        val cta = lbl(this, ui, when (kind) {
            "live" -> "OK · ver el partido"
            "final" -> "OK · resumen y estadísticas"
            else -> "OK · ficha del partido"
        }, 24, Colors.TEXT, true).apply { setPadding(ui.px(24), 0, 0, 0) }
        bottom.addView(cta)
        col.addView(bottom)
        box.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (kind != "final" && prefs.configured) {
            val my = token
            thread {
                val ch = Football.findChannel(prefs, m)
                runOnUiThread {
                    if (my != token) return@runOnUiThread
                    if (kind == "next") info.text = if (ch != null) "Se ve en " + ch.name else "No lo encuentro en tu guía todavía"
                    if (kind == "live" && ch != null) cta.text = "OK · ver en " + ch.name
                }
            }
        }
        return box
    }

    /** Barra 0–90' con los goles encima (local) o debajo (visitante). */
    private fun goalTimeline(m: Football.Match): View {
        val f = FrameLayout(this)
        val track = View(this).apply { background = roundRect(ui, Color.argb(36, 255, 255, 255), 3) }
        f.addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(6), Gravity.CENTER_VERTICAL))
        val fill = View(this).apply { background = roundRect(ui, Colors.TEXT, 3) }
        f.addView(fill, FrameLayout.LayoutParams(0, ui.px(6), Gravity.CENTER_VERTICAL))
        val marks = ArrayList<Pair<TextView, Int>>()
        for (g in m.goals()) {
            val home = g.teamId == m.home.id
            val tv = lbl(this, ui, g.minute, 24, if (home) Colors.TEXT else Color.parseColor("#FF9C9C"), true)
            f.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (home) Gravity.TOP else Gravity.BOTTOM).apply {
                if (home) topMargin = -ui.px(10) else bottomMargin = -ui.px(10)
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

    private fun f1Slide(e: F1Data.Event): View {
        val now = System.currentTimeMillis()
        val box = card(Color.parseColor("#1A0707"))
        box.addView(vIcon(this, R.drawable.ic_circuit, F1RED).apply { alpha = 0.45f }, FrameLayout.LayoutParams(ui.px(640), ui.px(420), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = ui.px(120) })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.px(56), ui.px(40), ui.px(56), ui.px(36))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(pill(this, ui, "F1", F1RED))
        head.addView(lbl(this, ui, "Fin de semana de carrera", 24, Color.parseColor("#FFB3B3"), true).apply { setPadding(ui.px(16), 0, 0, 0) })
        left.addView(head)
        left.addView(lbl(this, ui, e.name, 72, Colors.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, ui.px(16), 0, 0)
        })
        left.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val cur = F1Data.current(e, now)
        if (cur != null) {
            val cd = F1Data.countdown(cur.at, now)
            left.addView(lbl(this, ui, if (cd == "EN DIRECTO") cur.name else cur.name + " en", 24, Color.parseColor("#E3C6C6")))
            left.addView(lbl(this, ui, if (cd == "EN DIRECTO") "En directo" else cd.removePrefix("EN ").lowercase(), 72, Colors.TEXT, true).apply { includeFontPadding = false })
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f))
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(ui, Color.argb(140, 7, 8, 12), 24)
            setPadding(ui.px(30), ui.px(18), ui.px(30), ui.px(18))
        }
        val fmt = SimpleDateFormat("EEE HH:mm", Locale("es", "ES"))
        for (x in e.sessions.takeLast(5)) {
            val done = x.at + 2 * 3600_000L < now
            val isNext = x === cur
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.px(if (isNext) 16 else 0), ui.px(8), ui.px(if (isNext) 16 else 0), ui.px(8))
                if (isNext) background = roundRect(ui, Color.argb(30, 255, 255, 255), 12)
            }
            if (done) {
                r.addView(vIcon(this, R.drawable.ic_check, Color.parseColor("#7DD3A0")), LinearLayout.LayoutParams(ui.px(26), ui.px(26)).apply { rightMargin = ui.px(16) })
            } else {
                r.addView(View(this).apply { background = ring(ui, if (isNext) Colors.TEXT else Color.parseColor("#5A6070"), 13) }, LinearLayout.LayoutParams(ui.px(26), ui.px(26)).apply { rightMargin = ui.px(16) })
            }
            r.addView(lbl(this, ui, x.name, 28, if (done) Colors.MUTED else Colors.TEXT, isNext), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(lbl(this, ui, fmt.format(Date(x.at)), 28, if (isNext) Colors.TEXT else Colors.MUTED, isNext))
            list.addView(r)
        }
        list.addView(pill(this, ui, if (isInstalled("com.dazn")) "OK · abrir DAZN con PITWALL" else "OK · calendario de F1", Colors.TEXT, Color.parseColor("#0B0D12"), 24).apply {
            gravity = Gravity.CENTER
            setPadding(ui.px(20), ui.px(10), ui.px(20), ui.px(10))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(14) })
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(list)
            addView(dots(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = ui.px(14)
            })
        }
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = ui.px(56) })
        box.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return box
    }

    private fun continueSlide(): View {
        val box = card(Colors.SURFACE)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(56), ui.px(40), ui.px(56), ui.px(36))
        }
        val ch = Xtream.channels.firstOrNull { it.id == prefs.lastChannelId }
        val logoBox = FrameLayout(this).apply {
            background = roundRect(ui, Color.parseColor("#0B0D12"), 24)
            setPadding(ui.px(24), ui.px(20), ui.px(24), ui.px(20))
        }
        val initials = lbl(this, ui, prefs.lastChannelName.take(2).uppercase(), 48, Colors.MUTED, true).apply { gravity = Gravity.CENTER }
        logoBox.addView(initials, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (ch != null) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            logoBox.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            Logos.load(ch.icon, iv, ui.px(300)) { ok -> initials.visibility = if (ok) View.INVISIBLE else View.VISIBLE }
        }
        row.addView(logoBox, LinearLayout.LayoutParams(ui.px(420), ui.px(236)).apply { rightMargin = ui.px(56) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(vIcon(this@MainActivity, R.drawable.ic_play, Colors.ACCENT), LinearLayout.LayoutParams(ui.px(24), ui.px(24)).apply { rightMargin = ui.px(12) })
            addView(lbl(this@MainActivity, ui, "Seguir viendo", 28, Colors.ACCENT, true))
        })
        col.addView(lbl(this, ui, prefs.lastChannelName, 72, Colors.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val prog = lbl(this, ui, " ", 36, Colors.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(prog)
        val track = FrameLayout(this).apply {
            background = roundRect(ui, Colors.SURFACE2, 4)
            visibility = View.INVISIBLE
        }
        val fill = View(this).apply { background = roundRect(ui, Colors.ACCENT, 4) }
        track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(8)).apply { topMargin = ui.px(20) })
        val left = lbl(this, ui, "", 24, Colors.MUTED).apply { setPadding(0, ui.px(10), 0, 0) }
        col.addView(left)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.END
            addView(dots())
        }
        row.addView(side, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val my = token
        thread {
            val nowS = System.currentTimeMillis() / 1000
            val cur = Xtream.epg(prefs, prefs.lastChannelId).firstOrNull { it.start <= nowS && nowS < it.end }
            runOnUiThread {
                if (my != token || cur == null) return@runOnUiThread
                prog.text = cur.title
                track.visibility = View.VISIBLE
                val frac = ((nowS - cur.start).toFloat() / maxOf(1L, cur.end - cur.start)).coerceIn(0f, 1f)
                track.post { fill.layoutParams = FrameLayout.LayoutParams((track.width * frac).toInt(), ViewGroup.LayoutParams.MATCH_PARENT) }
                left.text = "quedan " + ((cur.end - nowS) / 60) + " min"
            }
        }
        return box
    }

    private fun todaySlide(): View {
        val box = card(Colors.SURFACE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(56), ui.px(40), ui.px(56), ui.px(36))
        }
        col.addView(lbl(this, ui, "Buenas " + greeting(), 28, Colors.ACCENT, true))
        col.addView(lbl(this, ui, "¿Qué vemos hoy?", 72, Colors.TEXT, true))
        col.addView(lbl(this, ui, "OK · partidos de hoy en Fútbol", 28, Colors.MUTED).apply { setPadding(0, ui.px(16), 0, 0) })
        box.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return box
    }

    private fun welcomeSlide(): View {
        val box = card(Colors.SURFACE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(56), ui.px(40), ui.px(56), ui.px(36))
        }
        col.addView(lbl(this, ui, "Bienvenido a Lumen", 28, Colors.ACCENT, true))
        col.addView(lbl(this, ui, "Configúralo desde el móvil", 72, Colors.TEXT, true))
        col.addView(lbl(this, ui, "OK · ver el código QR", 28, Colors.MUTED).apply { setPadding(0, ui.px(16), 0, 0) })
        box.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return box
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 6 -> "noches"
            h < 13 -> "días"
            h < 20 -> "tardes"
            else -> "noches"
        }
    }

    /** Silueta gris mientras cargan los datos (en vez de «Cargando…»). */
    private fun skeletonHero(): View {
        val sk = Color.parseColor("#151924")
        val sk2 = Color.parseColor("#1B2030")
        val box = card(sk)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(56), ui.px(40), ui.px(56), ui.px(36))
        }
        fun bar(w: Int, h: Int) = View(this).apply { background = roundRect(ui, sk2, 10) }.also {
            it.layoutParams = LinearLayout.LayoutParams(ui.px(w), ui.px(h))
        }
        col.addView(bar(320, 40))
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        mid.addView(bar(132, 138))
        mid.addView(View(this), LinearLayout.LayoutParams(ui.px(60), 1))
        mid.addView(bar(260, 96))
        mid.addView(View(this), LinearLayout.LayoutParams(ui.px(60), 1))
        mid.addView(bar(132, 138))
        col.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(bar(280, 40))
        box.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.animate().alpha(0.6f).setDuration(700).withEndAction { box.animate().alpha(1f).setDuration(700).start() }.start()
        return box
    }

    // ─────────────────────────────── «Para ti» ───────────────────────────────

    private fun fillParaTi(team: Football.Match?, f1: F1Data.Event?, today: List<Football.Match>?) {
        paraTi.removeAllViews()
        val heroKind = slides.getOrNull(0)?.kind
        val cards = ArrayList<View>()
        if (prefs.configured && prefs.lastChannelName.isNotBlank() && heroKind != "cont") cards.add(continueCard())
        if (f1 != null && heroKind != "f1") F1Data.current(f1, System.currentTimeMillis())?.let { cards.add(f1Card(f1, it)) }
        if (team != null && heroKind !in listOf("live", "next", "final")) cards.add(teamCard(team))
        if (today != null && today.isNotEmpty()) cards.add(todayCard(today))
        if (cards.isEmpty() && today == null) {
            for (i in 0 until 3) cards.add(View(this).apply { background = roundRect(ui, Color.parseColor("#151924"), 24) })
        }
        if (cards.isEmpty()) {
            paraTiBox.visibility = View.GONE
            return
        }
        paraTiBox.visibility = View.VISIBLE
        for ((i, c) in cards.take(3).withIndex()) {
            paraTi.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = ui.px(28) })
        }
    }

    private fun smallCard(onClick: () -> Unit): LinearLayout {
        val normal = roundRect(ui, Colors.alpha(Colors.SURFACE, 0xEE), 24)
        val focused = roundRect(ui, Colors.SURFACE2, 24)
        val edge = ring(ui, Colors.TEXT, 24)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = normal
            setPadding(ui.px(30), ui.px(24), ui.px(30), ui.px(22))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, has ->
                v.background = if (has) focused else normal
                v.foreground = if (has) edge else null
                v.translationZ = if (has) ui.px(14).toFloat() else 0f
                focusPop(v, has, 1.04f)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun kicker(iconRes: Int?, text: String, color: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (iconRes != null) addView(vIcon(this@MainActivity, iconRes, color), LinearLayout.LayoutParams(ui.px(24), ui.px(24)).apply { rightMargin = ui.px(12) })
        addView(lbl(this@MainActivity, ui, text, 24, color, true))
    }

    private fun cardTitle(t: String): TextView = lbl(this, ui, t, 36, Colors.TEXT, true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun continueCard(): View {
        val c = smallCard { startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", prefs.lastChannelId)) }
        c.addView(kicker(R.drawable.ic_play, "Seguir viendo", Colors.ACCENT))
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(cardTitle(prefs.lastChannelName))
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val sub = lbl(this, ui, " ", 24, Colors.MUTED).apply { maxLines = 1 }
        c.addView(sub)
        val my = token
        thread {
            val nowS = System.currentTimeMillis() / 1000
            val cur = Xtream.epg(prefs, prefs.lastChannelId).firstOrNull { it.start <= nowS && nowS < it.end }
            runOnUiThread { if (my == token && cur != null) sub.text = cur.title + " · quedan " + ((cur.end - nowS) / 60) + " min" }
        }
        return c
    }

    private fun f1Card(e: F1Data.Event, s: F1Data.Session): View {
        val c = smallCard { if (F1Data.isWeekend(e, System.currentTimeMillis())) openDazn() else startActivity(Intent(this, F1Activity::class.java)) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(pill(this, ui, "F1", F1RED))
        head.addView(lbl(this, ui, daysText(s.at) + " " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.at)), 24, Color.parseColor("#FF9C9C"), true).apply { setPadding(ui.px(14), 0, 0, 0) })
        c.addView(head)
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(cardTitle(e.name))
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(lbl(this, ui, s.name + (if (isInstalled("com.dazn")) " · abre DAZN" else ""), 24, Colors.MUTED))
        return c
    }

    private fun teamCard(m: Football.Match): View {
        val c = smallCard { startActivity(Intent(this, FootballActivity::class.java).putExtra("match", m.id)) }
        c.addView(kicker(R.drawable.ic_ball, "Tu equipo · " + (if (m.live) "en directo" else if (m.done) "último partido" else daysText(m.start).lowercase()), Color.parseColor("#C3C7E6")))
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(cardTitle(if (m.state == "pre") m.home.short + " – " + m.away.short else m.home.short + " " + m.score() + " " + m.away.short))
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(lbl(this, ui, m.league.name + " · " + m.status(), 24, Colors.MUTED))
        return c
    }

    private fun todayCard(l: List<Football.Match>): View {
        val c = smallCard { startActivity(Intent(this, FootballActivity::class.java)) }
        val live = l.firstOrNull { it.live }
        c.addView(kicker(R.drawable.ic_ball, "Fútbol hoy", if (live != null) Color.parseColor("#FF8A8A") else Colors.MUTED))
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val first = live ?: l.firstOrNull { !it.done } ?: l.first()
        c.addView(cardTitle(first.home.short + " " + first.score() + " " + first.away.short + (if (first.live) " · " + first.status() else "")))
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        c.addView(lbl(this, ui, if (l.size > 1) (l.size - 1).toString() + " partidos más hoy" else first.league.name, 24, Colors.MUTED))
        return c
    }

    // ─────────────────────────────── favoritos ───────────────────────────────

    private fun favoritesRow(): View? {
        val favIds = prefs.favorites
        if (!prefs.configured || favIds.isEmpty()) return null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }
        box.addView(sectionLabel("Favoritos").apply { setPadding(0, ui.px(36), 0, ui.px(16)) })
        val row = tileRow()
        box.addView(scrollOf(row))
        val my = token
        thread {
            if (Xtream.channels.isEmpty()) Xtream.load(applicationContext, prefs, false)
            val favs = prefs.visibleChannels(Xtream.channels).filter { it.id in favIds }.take(12)
            if (favs.isEmpty()) return@thread
            runOnUiThread {
                if (my != token || isFinishing) return@runOnUiThread
                val parts = ArrayList<Triple<Channel, TextView, View>>()
                for (ch in favs) {
                    val (tile, prog, fill) = favTile(ch) {
                        Xtream.queue = favs
                        startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", ch.id))
                    }
                    row.addView(tile, LinearLayout.LayoutParams(ui.px(268), ui.px(150)).apply { rightMargin = ui.px(26) })
                    parts.add(Triple(ch, prog, fill))
                }
                box.visibility = View.VISIBLE
                thread {
                    val now = System.currentTimeMillis() / 1000
                    for ((ch, prog, fill) in parts) {
                        if (my != token) break
                        val cur = Xtream.epg(prefs, ch.id).firstOrNull { it.start <= now && now < it.end } ?: continue
                        val frac = ((now - cur.start).toFloat() / maxOf(1L, cur.end - cur.start)).coerceIn(0f, 1f)
                        runOnUiThread {
                            prog.text = cur.title
                            val track = fill.parent as View
                            track.visibility = View.VISIBLE
                            track.post { fill.layoutParams = FrameLayout.LayoutParams((track.width * frac).toInt(), ViewGroup.LayoutParams.MATCH_PARENT) }
                        }
                    }
                }
            }
        }
        return box
    }

    private fun favTile(ch: Channel, onClick: () -> Unit): Triple<View, TextView, View> {
        val tile = smallCard(onClick)
        tile.setPadding(ui.px(20), ui.px(18), ui.px(20), ui.px(16))
        val initials = lbl(this, ui, ch.name.split(" ", "-", "|", ":").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }, 24, Colors.MUTED, true).apply { gravity = Gravity.CENTER }
        val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logoBox = FrameLayout(this).apply {
            background = roundRect(ui, Color.parseColor("#0B0D12"), 10)
            setPadding(ui.px(6), ui.px(4), ui.px(6), ui.px(4))
            addView(initials, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(logo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        Logos.load(ch.icon, logo, ui.px(120)) { ok -> initials.visibility = if (ok) View.INVISIBLE else View.VISIBLE }
        head.addView(logoBox, LinearLayout.LayoutParams(ui.px(96), ui.px(48)))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(lbl(this, ui, ch.num.toString(), 24, Colors.ACCENT, true))
        tile.addView(head)
        val prog = lbl(this, ui, ch.name, 24, Colors.TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, ui.px(10), 0, 0)
        }
        tile.addView(prog)
        val track = FrameLayout(this).apply {
            background = roundRect(ui, Colors.SURFACE2, 3)
            visibility = View.INVISIBLE
        }
        val fill = View(this).apply { background = roundRect(ui, Colors.ACCENT, 3) }
        track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        tile.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(6)).apply { topMargin = ui.px(10) })
        return Triple(tile, prog, fill)
    }

    // ─────────────────────────────── voz ───────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> {
                startVoice()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                val pkg = prefs.lastApp
                if (pkg.isNotBlank() && isInstalled(pkg) && launchPackage(this, pkg)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startVoice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Di un canal, un equipo o una app")
            @Suppress("DEPRECATION")
            startActivityForResult(i, VOICE)
        } catch (e: Exception) {
            Toast.makeText(this, "Este stick no tiene búsqueda por voz instalada", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VOICE || resultCode != RESULT_OK) return
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        showVoice(said)
    }

    private class VoiceHit(val title: String, val sub: String, val tag: String, val tagColor: Int, val action: () -> Unit)

    private fun showVoice(said: String) {
        closeVoice()
        val q = normText(said).replace("pon ", "").replace("abre ", "").replace("quiero ver ", "").replace("el ", "").replace("la ", "").trim()
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(224, 7, 8, 12))
            isClickable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val mic = FrameLayout(this).apply { background = roundRect(ui, Colors.ACCENT, 75) }
        mic.addView(vIcon(this, R.drawable.ic_mic, Colors.onColor()), FrameLayout.LayoutParams(ui.px(72), ui.px(72), Gravity.CENTER))
        col.addView(mic, LinearLayout.LayoutParams(ui.px(150), ui.px(150)))
        col.addView(lbl(this, ui, "Te he oído", 28, Colors.MUTED).apply { setPadding(0, ui.px(26), 0, 0) })
        col.addView(lbl(this, ui, "«$said»", 72, Colors.TEXT, true).apply { gravity = Gravity.CENTER })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, LinearLayout.LayoutParams(ui.px(900), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(30) })
        col.addView(lbl(this, ui, "Atrás · cerrar", 24, Color.parseColor("#8C95A8")).apply { setPadding(0, ui.px(24), 0, 0) })
        layer.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        frame.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        voiceLayer = layer
        list.addView(lbl(this, ui, "Buscando…", 28, Colors.MUTED).apply { gravity = Gravity.CENTER })
        val my = token
        thread {
            val hits = ArrayList<VoiceHit>()
            val words = q.split(" ").filter { it.length >= 2 }
            if (q.contains("madrid")) {
                Football.teamFocus()?.let { m ->
                    val ch = if (m.live && prefs.configured) Football.findChannel(prefs, m) else null
                    hits.add(VoiceHit(m.home.short + " – " + m.away.short, m.league.name + " · " + m.status(), if (m.live) "EN DIRECTO" else "PARTIDO",
                        if (m.live) F1RED else PURPLE) {
                        if (ch != null) startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", ch.id))
                        else startActivity(Intent(this, FootballActivity::class.java).putExtra("match", m.id))
                    })
                }
            }
            if (q.contains("futbol") || q.contains("partido")) hits.add(VoiceHit("Fútbol de hoy", "LaLiga, Champions y Copa", "FÚTBOL", Color.parseColor("#2FB36B")) {
                startActivity(Intent(this, FootballActivity::class.java))
            })
            if (q.contains("f1") || q.contains("formula") || q.contains("carrera")) hits.add(VoiceHit("F1 en DAZN", "Con PITWALL encima", "F1", F1RED) { openDazn() })
            if (q.contains("semana")) hits.add(VoiceHit("Semana deportiva", "Los próximos 7 días", "SEMANA", Color.parseColor("#4C9AFF")) {
                startActivity(Intent(this, SemanaActivity::class.java))
            })
            if (prefs.configured && words.isNotEmpty()) {
                if (Xtream.channels.isEmpty()) Xtream.load(applicationContext, prefs, false)
                for (c in prefs.visibleChannels(Xtream.channels).filter { ch -> words.all { ch.key.contains(it) } }.take(4)) {
                    hits.add(VoiceHit(c.name, "Canal " + c.num, "CANAL", Colors.SURFACE2) {
                        Xtream.queue = prefs.visibleChannels(Xtream.channels)
                        startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", c.id))
                    })
                }
            }
            for (pkg in prefs.pinned.map { resolve(it) }.filter { isInstalled(it) }) {
                val name = appLabel(pkg)
                if (words.any { normText(name).contains(it) }) hits.add(VoiceHit(name, "Aplicación", "APP", Colors.SURFACE2) {
                    if (launchPackage(this, pkg)) prefs.lastApp = pkg
                })
            }
            runOnUiThread {
                if (my != token || voiceLayer !== layer) return@runOnUiThread
                list.removeAllViews()
                if (hits.isEmpty()) {
                    list.addView(lbl(this, ui, "No encuentro nada con ese nombre", 28, Colors.MUTED).apply { gravity = Gravity.CENTER })
                    return@runOnUiThread
                }
                var first: View? = null
                for (h in hits.take(4)) {
                    val r = smallCard {
                        closeVoice()
                        h.action()
                    }
                    r.orientation = LinearLayout.HORIZONTAL
                    r.gravity = Gravity.CENTER_VERTICAL
                    r.addView(pill(this, ui, h.tag, h.tagColor), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.px(24) })
                    r.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(lbl(this@MainActivity, ui, h.title, 28, Colors.TEXT, true))
                        addView(lbl(this@MainActivity, ui, h.sub, 24, Colors.MUTED))
                    })
                    list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(14) })
                    if (first == null) first = r
                }
                first?.requestFocus()
            }
        }
    }

    private fun closeVoice() {
        voiceLayer?.let { frame.removeView(it) }
        voiceLayer = null
        heroBox.requestFocus()
    }

    // ─────────────────────────────── utilidades ───────────────────────────────

    /** Si hay una versión nueva, pregunta una sola vez si se instala ahora. */
    private fun offerUpdate() {
        val latest = prefs.latestCode
        if (latest <= Updater.installedCode(this) || latest <= prefs.askedCode || prefs.updateRepo.isBlank()) return
        prefs.askedCode = latest
        AlertDialog.Builder(this)
            .setTitle("Nueva versión de Lumen")
            .setMessage("Hay una actualización (v1.$latest). ¿Instalarla ahora? Tarda un minuto.")
            .setPositiveButton("Instalar") { _, _ ->
                thread {
                    val info = Updater.latest(prefs.updateRepo)
                    runOnUiThread {
                        if (info != null) {
                            Updater.downloadAndInstall(this, info) { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                        } else {
                            Toast.makeText(this, "No se pudo comprobar la actualización", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Luego", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.activeNetworkInfo?.isConnected == true
    } catch (e: Exception) {
        true
    }

    /** Si SmartTube estable no está pero sí la beta, se usa la beta. */
    private fun resolve(pkg: String): String =
        if (pkg == "com.teamsmart.videomanager.tv" && !isInstalled(pkg) && isInstalled("com.liskovsoft.smarttubetv.beta")) {
            "com.liskovsoft.smarttubetv.beta"
        } else {
            pkg
        }

    private fun appLabel(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            defaultNames[pkg] ?: pkg
        }

    /** Los iconos se leen una sola vez: reconstruir el inicio es así mucho más rápido. */
    private fun cachedIcon(pkg: String): Drawable? {
        val cs = iconCache[pkg]
        if (cs != null) return cs.newDrawable(resources)
        return try {
            val d = packageManager.getApplicationIcon(pkg)
            d.constantState?.let { iconCache[pkg] = it }
            d
        } catch (e: Exception) {
            null
        }
    }

    private fun isInstalled(pkg: String): Boolean =
        try {
            packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
}
