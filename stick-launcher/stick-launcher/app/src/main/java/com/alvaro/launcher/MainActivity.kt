package com.alvaro.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/** Pantalla de inicio. Se registra como HOME, así Android la abre sola al encender el stick. */
class MainActivity : Activity() {

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
            val icon = if (installed) {
                try {
                    packageManager.getApplicationIcon(pkg)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
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
        bar.addView(title)
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
            setSizePx(ui, 200)
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

        box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        return box
    }

    private fun isInstalled(pkg: String): Boolean =
        try {
            packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
}
