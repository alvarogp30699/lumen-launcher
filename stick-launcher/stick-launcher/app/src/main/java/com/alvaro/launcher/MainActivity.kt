package com.alvaro.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import org.json.JSONObject

/** Pantalla de inicio. Se registra como HOME, así Android la abre sola al encender el stick. */
class MainActivity : Activity() {

    private companion object {
        val iconCache = HashMap<String, android.graphics.drawable.Drawable.ConstantState>()
    }

    private val defaultNames = mapOf(
        "com.teamsmart.videomanager.tv" to "SmartTube",
        "com.liskovsoft.smarttubetv.beta" to "SmartTube",
        "com.dazn" to "DAZN",
        "com.esaba.downloader" to "Downloader",
        "com.android.vending" to "Play Store"
    )

    override fun onResume() {
        super.onResume()
        Updater.checkInBackground(this)
        build()
        offerUpdate()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // En la pantalla de inicio, Atrás no hace nada.
    }

    private fun build() {
        val ui = Ui(this)
        val prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.BG)
            setPadding(ui.px(96), ui.px(56), ui.px(96), ui.px(72))
            clipChildren = false
            clipToPadding = false
        }

        root.addView(topBar(ui, prefs))
        root.addView(warningBar(ui, prefs))
        root.addView(hero(ui, prefs))

        val label = TextView(this).apply {
            text = "Aplicaciones"
            setTextColor(Colors.MUTED)
            setSizePx(ui, 22)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, ui.px(18))
        }
        root.addView(label)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val iptvTile = makeTile(this, ui, "Lumen IPTV", circleIcon(Colors.ACCENT), false, onClick = {
            startActivity(Intent(this, IptvActivity::class.java))
        })
        addTile(row, iptvTile, ui)

        for (saved in prefs.pinned) {
            val pkg = resolve(saved)
            val installed = isInstalled(pkg)
            val name = if (installed) appLabel(pkg) else (defaultNames[pkg] ?: pkg)
            val icon = if (installed) cachedIcon(pkg) else null
            val tile = makeTile(this, ui, name, icon, !installed, onClick = {
                if (!installed || !launchPackage(this, pkg)) {
                    Toast.makeText(
                        this,
                        "$name no está instalada. Instálala con Downloader o desde Play Store.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
            addTile(row, tile, ui)
        }

        if (prefs.pitwallUrl.isNotBlank()) {
            addTile(
                row,
                makeTile(this, ui, "Calendario F1", circleIcon(Color.parseColor("#E10600")), false, onClick = {
                    startActivity(Intent(this, F1Activity::class.java))
                }),
                ui
            )
        }
        addTile(
            row,
            makeTile(this, ui, "Ajustes", circleIcon(Colors.MUTED), false, onClick = {
                startActivity(Intent(this, SettingsActivity::class.java))
            }),
            ui
        )
        addTile(
            row,
            makeTile(this, ui, "Todas las apps", circleIcon(Colors.TEXT), false, onClick = {
                startActivity(Intent(this, AppsActivity::class.java))
            }),
            ui
        )

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        }
        root.addView(scroll)

        setContentView(root)
        iptvTile.requestFocus()
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
            pkg
        }

    private fun addTile(row: LinearLayout, tile: View, ui: Ui) {
        row.addView(tile, LinearLayout.LayoutParams(ui.px(300), ui.px(168)).apply {
            rightMargin = ui.px(28)
        })
    }

    private var lastHealth = 0L
    private var lastWarnings: String = ""

    /** Franja de avisos: lista IPTV o PITWALL que no responden, con el motivo. Se comprueba como mucho cada 3 min. */
    private fun warningBar(ui: Ui, prefs: Prefs): View {
        val tv = TextView(this).apply {
            setTextColor(Color.parseColor("#1A1203"))
            setSizePx(ui, 24)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(ui.px(24), ui.px(12), ui.px(24), ui.px(12))
            background = GradientDrawable().apply {
                cornerRadius = ui.px(14).toFloat()
                setColor(Color.parseColor("#FFD400"))
            }
            text = lastWarnings
            visibility = if (lastWarnings.isBlank()) View.GONE else View.VISIBLE
        }
        val now = System.currentTimeMillis()
        if (now - lastHealth > 180_000L && (prefs.configured || prefs.pitwallUrl.isNotBlank())) {
            lastHealth = now
            thread {
                val msgs = Health.check(prefs)
                lastWarnings = msgs.joinToString("   ·   ")
                runOnUiThread {
                    if (!isFinishing) {
                        tv.text = lastWarnings
                        tv.visibility = if (lastWarnings.isBlank()) View.GONE else View.VISIBLE
                    }
                }
            }
        }
        val box = LinearLayout(this)
        box.addView(tv)
        return box
    }

    /** Si hay una versión nueva, pregunta una sola vez si se instala ahora. */
    private fun offerUpdate() {
        val prefs = Prefs(this)
        val latest = prefs.latestCode
        if (latest <= Updater.installedCode(this) || latest <= prefs.askedCode || prefs.updateRepo.isBlank()) return
        prefs.askedCode = latest
        AlertDialog.Builder(this)
            .setTitle("Nueva versión de Lumen Launcher")
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

    private fun topBar(ui: Ui, prefs: Prefs): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Inicio"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 26)
            typeface = Typeface.DEFAULT_BOLD
        }
        val update = TextView(this).apply {
            text = if (prefs.latestCode > Updater.installedCode(this@MainActivity)) {
                "Actualización disponible · Ajustes"
            } else {
                ""
            }
            setTextColor(Colors.ACCENT)
            setSizePx(ui, 24)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, ui.px(40), 0)
        }
        val date = TextClock(this).apply {
            format24Hour = "EEE d MMM"
            format12Hour = "EEE d MMM"
            setTextColor(Colors.MUTED)
            setSizePx(ui, 26)
        }
        val time = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 34)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(ui.px(28), 0, 0, 0)
        }
        val profile = makeButton(this, ui, "👤  " + prefs.profileNames[prefs.profile], false) {
            prefs.profile = (prefs.profile + 1) % 3
            IptvActivity.unlocked.clear()
            Xtream.queue = emptyList()
            build()
        }
        bar.addView(title)
        bar.addView(profile, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(36) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(update)
        bar.addView(date)
        bar.addView(time)
        return bar
    }

    private fun hero(ui: Ui, prefs: Prefs): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 170)
            typeface = Typeface.DEFAULT_BOLD
        }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 6 -> "Buenas noches"
            hour < 13 -> "Buenos días"
            hour < 20 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        val hello = TextView(this).apply {
            text = greeting
            setTextColor(Colors.ACCENT)
            setSizePx(ui, 40)
            typeface = Typeface.DEFAULT_BOLD
        }
        box.addView(clock)
        box.addView(hello)

        if (prefs.configured && prefs.lastChannelName.isNotBlank()) {
            val resume = makeButton(this, ui, "▶  Seguir viendo · " + prefs.lastChannelName, true) {
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", prefs.lastChannelId))
            }
            box.addView(resume, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ui.px(28) })
        }

        nextF1Row(ui, prefs)?.let {
            box.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ui.px(24) })
        }

        box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        return box
    }

    private val f1ChannelWords = listOf("f1", "formula 1", "fórmula 1", "formula1", "fórmula1")

    /** Línea "Próximo directo F1" con cuenta atrás (datos de tu PITWALL) y botón para abrir el canal de F1. */
    private fun nextF1Row(ui: Ui, prefs: Prefs): View? {
        if (prefs.pitwallUrl.isBlank()) return null
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val label = TextView(this).apply {
            setTextColor(Colors.TEXT)
            setSizePx(ui, 28)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, ui.px(28), 0)
        }
        row.addView(label)
        thread {
            try {
                val u = java.net.URI(prefs.pitwallUrl.trim())
                val tok = (u.query ?: "").split("&").firstOrNull { it.startsWith("token=") }
                val url = u.scheme + "://" + u.authority + "/api/standings?series=f1" + (if (tok != null) "&$tok" else "")
                val next = JSONObject(Xtream.http(url)).optJSONObject("next") ?: return@thread
                val sessions = next.optJSONArray("sessions") ?: return@thread
                val parse = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val now = System.currentTimeMillis()
                var text: String? = null
                for (i in 0 until sessions.length()) {
                    val so = sessions.getJSONObject(i)
                    val at = parse.parse(so.optString("at"))?.time ?: continue
                    if (at + 2 * 3600_000L < now) continue
                    val mins = (at - now) / 60_000
                    val cuenta = when {
                        mins <= 0 -> "¡en directo ahora!"
                        mins < 60 -> "en $mins min"
                        mins < 48 * 60 -> "en " + (mins / 60) + " h " + (mins % 60) + " min"
                        else -> "en " + (mins / 1440) + " días"
                    }
                    val day = SimpleDateFormat("EEE HH:mm", Locale("es", "ES")).format(Date(at))
                    text = "🏁 F1 · " + next.optString("name") + " · " + so.optString("name") + " · " + day + " · " + cuenta
                    break
                }
                if (text == null) return@thread
                if (prefs.configured && Xtream.channels.isEmpty()) Xtream.load(applicationContext, prefs, false)
                val f1 = prefs.visibleChannels(Xtream.channels).firstOrNull { c -> f1ChannelWords.any { c.name.lowercase().contains(it) } }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    label.text = text
                    if (f1 != null) {
                        row.addView(makeButton(this, ui, "▶  Ver " + f1.name, false) {
                            Xtream.queue = Xtream.channels
                            startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", f1.id))
                        })
                    }
                    row.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                // PITWALL apagado o sin red: la línea simplemente no aparece
            }
        }
        return row
    }

    /** Los iconos se leen una sola vez: reconstruir el inicio es así mucho más rápido. */
    private fun cachedIcon(pkg: String): android.graphics.drawable.Drawable? {
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
