package com.alvaro.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Todas las apps instaladas. Mantener OK (o Menú) abre un menú para fijar en el inicio,
 * mover, ocultar o ver la ficha de la app.
 */
class AppsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private var showHidden = false
    private var focusPkg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        build()
    }

    private fun build() {
        val pm = packageManager
        val hidden = prefs.hidden

        val seen = HashSet<String>()
        val apps = ArrayList<ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg != packageName && seen.add(pkg)) apps.add(info)
            }
        }
        apps.sortBy { it.loadLabel(pm).toString().lowercase() }

        val grid = GridLayout(this).apply {
            columnCount = 5
            clipChildren = false
            clipToPadding = false
        }
        val tiles = HashMap<String, View>()
        var first: View? = null
        for (info in apps) {
            val pkg = info.activityInfo.packageName
            val cls = info.activityInfo.name
            val isHidden = hidden.contains(pkg)
            if (isHidden && !showHidden) continue
            val label = info.loadLabel(pm).toString()
            val tile = makeTile(
                this, ui, label, MainActivity.iconCache[pkg]?.newDrawable(resources) ?: info.loadIcon(pm).also { d -> d.constantState?.let { MainActivity.iconCache[pkg] = it } }, isHidden,
                banner = info.activityInfo.loadBanner(pm),
                onClick = {
                    Prefs(this).lastApp = pkg
                    startActivity(
                        Intent(Intent.ACTION_MAIN)
                            .setComponent(ComponentName(pkg, cls))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                onLong = { showMenu(pkg, label) }
            )
            tile.tag = pkg
            tiles[pkg] = tile
            val lp = GridLayout.LayoutParams().apply {
                width = ui.px(300)
                height = ui.px(168)
                setMargins(0, 0, ui.px(28), ui.px(28))
            }
            grid.addView(tile, lp)
            if (first == null) first = tile
        }

        val title = TextView(this).apply {
            text = "Todas las aplicaciones"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 34)
            typeface = Fonts.bold
        }
        val toggle = makeButton(
            this, ui,
            if (showHidden) "Ocultar las ocultas" else "Ver ocultas (" + hidden.size + ")",
            false
        ) {
            showHidden = !showHidden
            build()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, ui.px(32))
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
        }
        val hint = TextView(this).apply {
            text = "Mantén OK pulsado sobre una app (o pulsa Menú) para fijarla, moverla u ocultarla"
            setTextColor(Colors.MUTED)
            setSizePx(ui, 22)
            setPadding(0, ui.px(8), 0, 0)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(96), ui.px(56), ui.px(96), ui.px(56))
            clipChildren = false
            clipToPadding = false
            addView(header)
            addView(grid)
            addView(hint)
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            background = homeBackground(ui)
            addView(content)
        }
        setContentView(scroll)

        val target = focusPkg?.let { tiles[it] } ?: first
        target?.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val pkg = currentFocus?.tag as? String
            if (pkg != null) {
                showMenu(pkg, pkg)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showMenu(pkg: String, label: String) {
        focusPkg = pkg
        val pinned = prefs.pinned.toMutableList()
        val isPinned = pinned.contains(pkg)
        val isHidden = prefs.hidden.contains(pkg)

        val names = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun option(name: String, action: () -> Unit) {
            names.add(name)
            actions.add(action)
        }

        if (isPinned) {
            option("Quitar del inicio") {
                pinned.remove(pkg)
                prefs.pinned = pinned
            }
            val idx = pinned.indexOf(pkg)
            if (idx > 0) {
                option("Mover a la izquierda en el inicio") {
                    pinned[idx] = pinned[idx - 1]
                    pinned[idx - 1] = pkg
                    prefs.pinned = pinned
                }
            }
            if (idx < pinned.size - 1) {
                option("Mover a la derecha en el inicio") {
                    pinned[idx] = pinned[idx + 1]
                    pinned[idx + 1] = pkg
                    prefs.pinned = pinned
                }
            }
        } else {
            option("Fijar en el inicio") {
                pinned.add(pkg)
                prefs.pinned = pinned
            }
        }
        if (isHidden) {
            option("Mostrar en la lista") {
                val set = prefs.hidden.toMutableSet()
                set.remove(pkg)
                prefs.hidden = set
            }
        } else {
            option("Ocultar de la lista") {
                val set = prefs.hidden.toMutableSet()
                set.add(pkg)
                prefs.hidden = set
            }
        }
        option("Ficha de la app (desactivar o desinstalar)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }

        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(names.toTypedArray()) { _, which ->
                actions[which]()
                build()
            }
            .show()
    }
}
