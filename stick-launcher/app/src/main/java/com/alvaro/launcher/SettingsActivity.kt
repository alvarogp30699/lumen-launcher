package com.alvaro.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Ajustes de Lumen: DNS sin anuncios, vídeo, actualización y estado del stick. */
class SettingsActivity : Activity() {

    companion object {
        const val DNS_HOST = "dns.adguard-dns.com"
    }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private var focusIdx = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
    }

    override fun onResume() {
        super.onResume()
        prefs = Prefs(this)
        build()
    }

    private var cat = 0
    private val cats = listOf("General", "Lumen TV", "Fútbol y F1", "Avisos", "Sistema")
    private lateinit var rowsBox: LinearLayout
    private val catViews = ArrayList<TextView>()

    private fun build() {
        catViews.clear()
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(80), ui.px(56), ui.px(36), ui.px(48))
            setBackgroundColor(android.graphics.Color.parseColor("#0B0D13"))
        }
        menu.addView(lbl(this, ui, "Ajustes", 48, Colors.TEXT, true).apply { setPadding(0, 0, 0, ui.px(30)) })
        for ((i, c) in cats.withIndex()) {
            val tv = lbl(this, ui, c, 28, Colors.TEXT, true).apply {
                setPadding(ui.px(24), ui.px(18), ui.px(24), ui.px(18))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, has ->
                    v.foreground = if (has) ring(ui, Colors.TEXT, 16) else null
                    if (has && cat != i) {
                        cat = i
                        focusIdx = -1
                        styleCats()
                        fillRows()
                    }
                }
                setOnClickListener { rowsBox.getChildAt(if (cat == 0) 1 else 0)?.requestFocus() }
            }
            menu.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(8) })
            catViews.add(tv)
        }
        menu.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        menu.addView(lbl(this, ui, "Lumen v" + Updater.installedCode(this) + (if (prefs.latestCode > Updater.installedCode(this)) " · hay versión nueva" else " · al día"), 24, android.graphics.Color.parseColor("#8C95A8")))

        rowsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(64), ui.px(56), ui.px(96), ui.px(56))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(rowsBox)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#07080C"))
            addView(menu, LinearLayout.LayoutParams(ui.px(470), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        setContentView(root)
        styleCats()
        fillRows()
        if (focusIdx < 0) catViews[cat].requestFocus()
    }

    private fun styleCats() {
        for ((i, v) in catViews.withIndex()) {
            val sel = i == cat
            v.background = if (sel) roundRect(ui, Colors.ACCENT, 16) else null
            v.setTextColor(if (sel) Colors.onColor() else android.graphics.Color.parseColor("#DADDE5"))
        }
    }

    private fun toggle(on: Boolean): View {
        val box = FrameLayout(this).apply { background = roundRect(ui, if (on) Colors.ACCENT else android.graphics.Color.parseColor("#2A2F3C"), 21) }
        box.addView(View(this).apply { background = roundRect(ui, if (on) Colors.onColor() else android.graphics.Color.parseColor("#8C95A8"), 16) },
            FrameLayout.LayoutParams(ui.px(32), ui.px(32), (if (on) android.view.Gravity.END else android.view.Gravity.START) or android.view.Gravity.CENTER_VERTICAL).apply { setMargins(ui.px(5), 0, ui.px(5), 0) })
        box.layoutParams = LinearLayout.LayoutParams(ui.px(76), ui.px(42))
        return box
    }

    private fun value(t: String): View = lbl(this, ui, t, 28, Colors.ACCENT, true)

    private fun srow(title: String, desc: String, right: View?, action: () -> Unit) {
        val idx = rowsBox.childCount
        val normal = roundRect(ui, Colors.SURFACE, 20)
        val focused = roundRect(ui, Colors.SURFACE2, 20)
        val edge = ring(ui, Colors.TEXT, 20)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = normal
            setPadding(ui.px(30), ui.px(22), ui.px(30), ui.px(22))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, has ->
                v.background = if (has) focused else normal
                v.foreground = if (has) edge else null
            }
            setOnClickListener {
                focusIdx = idx
                action()
            }
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(lbl(this@SettingsActivity, ui, title, 28, Colors.TEXT, true))
            if (desc.isNotBlank()) addView(lbl(this@SettingsActivity, ui, desc, 24, Colors.MUTED).apply { setPadding(0, ui.px(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) row.addView(right, right.layoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rowsBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(16) })
    }

    private fun head(t: String) {
        rowsBox.addView(lbl(this, ui, t, 28, Colors.MUTED, true).apply { setPadding(0, ui.px(10), 0, ui.px(14)) })
    }

    private fun refresh() {
        styleCats()
        fillRows()
    }

    private fun meters(): View {
        val mi = memInfo()
        val mb = 1024L * 1024L
        val st = StatFs(Environment.getDataDirectory().path)
        val freeGb = st.availableBytes / (1024.0 * 1024.0 * 1024.0)
        val totGb = st.totalBytes / (1024.0 * 1024.0 * 1024.0)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun meter(t: String, v: String, pct: Float, col: Int): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(ui, Colors.SURFACE, 20)
            setPadding(ui.px(26), ui.px(22), ui.px(26), ui.px(22))
            addView(lbl(this@SettingsActivity, ui, t, 24, Colors.MUTED))
            addView(lbl(this@SettingsActivity, ui, v, 36, Colors.TEXT, true).apply { setPadding(0, ui.px(6), 0, ui.px(12)) })
            val track = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                background = roundRect(ui, android.graphics.Color.parseColor("#232838"), 4)
            }
            track.addView(View(this@SettingsActivity).apply { background = roundRect(ui, col, 4) }, LinearLayout.LayoutParams(0, ui.px(8), pct.coerceIn(0.02f, 1f)))
            track.addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, ui.px(8), (1f - pct).coerceIn(0.0001f, 1f)))
            addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(8)))
        }
        val f = java.util.Locale("es", "ES")
        row.addView(meter("RAM libre", (mi.availMem / mb).toString() + " MB de " + (mi.totalMem / mb) + " MB", mi.availMem.toFloat() / mi.totalMem, android.graphics.Color.parseColor("#2FB36B")),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(meter("Almacenamiento libre", String.format(f, "%.1f", freeGb) + " GB de " + String.format(f, "%.0f", totGb) + " GB", (freeGb / totGb).toFloat(), android.graphics.Color.parseColor("#4C9AFF")),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.px(20) })
        row.addView(meter("Wi-Fi", wifiText(), 0.8f, Colors.ACCENT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.px(20) })
        return row
    }

    private fun keysEnabled(): Boolean = try {
        (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(packageName + "/")
    } catch (e: Exception) {
        false
    }

    private fun fillRows() {
        rowsBox.removeAllViews()
        when (cat) {
            0 -> {
                rowsBox.addView(meters(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(26) })
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for ((hex, _) in Colors.ACCENTS) {
                    val sel = hex.equals(prefs.accent, true)
                    chips.addView(View(this).apply {
                        background = roundRect(ui, android.graphics.Color.parseColor(hex), 22)
                        if (sel) foreground = ring(ui, Colors.TEXT, 22)
                    }, LinearLayout.LayoutParams(ui.px(44), ui.px(44)).apply { leftMargin = ui.px(14) })
                }
                srow("Color de acento", Colors.accentName(prefs.accent) + " · se aplica a todo el launcher", chips) {
                    val i = Colors.ACCENTS.indexOfFirst { it.first.equals(prefs.accent, true) }
                    val n = Colors.ACCENTS[(i + 1) % Colors.ACCENTS.size].first
                    prefs.accent = n
                    Colors.apply(n)
                    build()
                }
                srow("Perfil", "Cada perfil tiene sus favoritos y canales ocultos · los nombres se cambian desde el móvil", value(prefs.profileNames[prefs.profile])) {
                    prefs.profile = (prefs.profile + 1) % 3
                    IptvActivity.unlocked.clear()
                    Xtream.queue = emptyList()
                    refresh()
                }
                val dns = dnsOn()
                srow("Sin anuncios (DNS)", "Bloquea anuncios en todas las apps", toggle(dns)) { toggleDns(!dns) }
                srow("Liberar memoria al ver canales", "Cierra las apps de fondo al abrir un canal", toggle(prefs.autoFree)) {
                    prefs.autoFree = !prefs.autoFree
                    refresh()
                }
                srow("Liberar memoria ahora", "Cierra las apps que no estás usando", null) { freeMemory() }
                srow("Salvapantallas", "Reloj, tu equipo y la F1 · elige «Lumen» en el sistema", value("Lumen")) {
                    try {
                        startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Este stick no tiene ajuste de salvapantallas. Búscalo en Ajustes → Pantalla.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            1 -> {
                srow("Calidad de vídeo", "La más alta que aguante tu conexión", value(qualityLabel(prefs.quality))) {
                    prefs.quality = next(prefs.quality, listOf("auto", "4k", "1080p"))
                    refresh()
                }
                srow("Formato de canal", "Prueba «ts» si un canal no carga", value(prefs.format)) {
                    prefs.format = next(prefs.format, listOf("m3u8", "ts"))
                    refresh()
                }
                srow("Temporizador de reproducción", "Apaga el vídeo pasado un tiempo", value(if (prefs.sleepMinutes == 0) "Desactivado" else prefs.sleepMinutes.toString() + " min")) {
                    val opts = listOf("0", "30", "60", "90", "120")
                    prefs.sleepMinutes = next(prefs.sleepMinutes.toString(), opts).toInt()
                    refresh()
                }
                srow("Actualizar lista de canales", "Vuelve a descargarla del proveedor", null) { refreshChannels() }
                val nHidden = prefs.hiddenChannels.size + prefs.hiddenCats.size
                srow("Mostrar canales y categorías ocultos", "Vuelven a aparecer todos", value(if (nHidden == 0) "Ninguno" else "$nHidden ocultos")) {
                    prefs.hiddenChannels = emptySet()
                    prefs.hiddenCats = emptySet()
                    Toast.makeText(this, "Ya se ven todos otra vez", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                srow("Comprobar canales favoritos", "Mira cuáles emiten ahora", null) { checkFavorites() }
                srow("Configurar la lista desde el móvil", "Código QR", null) { startActivity(Intent(this, SetupActivity::class.java)) }
                srow("Limpiar caché de Lumen", "Logos, guía y lista guardada", null) {
                    cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    Xtream.clearCache(applicationContext)
                    Xtream.channels = emptyList()
                    Logos.clear()
                    Football.clear()
                    Toast.makeText(this, "Caché limpiada", Toast.LENGTH_SHORT).show()
                }
            }
            2 -> {
                head("Fútbol en Lumen TV")
                srow("Sincronizar solo", "Lee el reloj del partido en la imagen y calcula el retraso", toggle(prefs.syncAuto)) {
                    prefs.syncAuto = !prefs.syncAuto
                    refresh()
                }
                srow("Goles solo cuando los veas", "Espera a que cambie el marcador de la retransmisión", toggle(prefs.goalsGate)) {
                    prefs.goalsGate = !prefs.goalsGate
                    refresh()
                }
                srow("Margen de seguridad", "Se suma al retraso calculado", value("+" + prefs.syncMargin + " s")) {
                    prefs.syncMargin = next(prefs.syncMargin.toString(), listOf("0", "5", "10", "15", "20", "30")).toInt()
                    refresh()
                }
                head("F1 en DAZN")
                val n = prefs.daznDelays.size
                srow("Retraso habitual de DAZN", if (n == 0) "Se aprende con «¡Vuelta!» · OK para empezar de cero" else "Aprendido de $n mediciones · OK para empezar de cero", value(prefs.daznDelay().toString() + " s")) {
                    prefs.daznDelays = emptyList()
                    refresh()
                }
                srow("Tecla del mando para «¡Vuelta!»", "Mantenerla pulsada dentro de DAZN", value(when (prefs.vueltaKey) {
                    "INFO" -> "Mantener INFO"
                    "AZUL" -> "Mantener azul"
                    else -> "Mantener MENÚ"
                })) {
                    prefs.vueltaKey = next(prefs.vueltaKey, listOf("MENU", "INFO", "AZUL"))
                    refresh()
                }
                srow("Activar la tecla en Accesibilidad", if (keysEnabled()) "Activada" else "Busca «Lumen Launcher» en la lista y actívalo", value(if (keysEnabled()) "Sí" else "No")) {
                    try {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Este stick no deja abrir Accesibilidad desde aquí", Toast.LENGTH_LONG).show()
                    }
                }
                srow("Modo de PITWALL sobre DAZN", "También se cambia desde el móvil", value(when (prefs.overlayMode) {
                    "full" -> "Completo"
                    "alerts" -> "Solo avisos"
                    else -> "Compacto"
                })) {
                    prefs.overlayMode = next(prefs.overlayMode, listOf("compact", "full", "alerts"))
                    refresh()
                }
            }
            3 -> {
                srow("Avisarme de los partidos del " + Football.TEAM_NAME, "10 min antes, estés en la app que estés", toggle(prefs.notifyMadrid)) {
                    prefs.notifyMadrid = !prefs.notifyMadrid
                    refresh()
                }
                srow("Aviso de gol del " + Football.TEAM_NAME, "Cuando marca y no estás viendo el partido", toggle(prefs.notifyGoals)) {
                    prefs.notifyGoals = !prefs.notifyGoals
                    refresh()
                }
                val can = OverlayNotice.canShow(this)
                srow("Mostrar sobre otras apps", if (can) "Concedido · los avisos y PITWALL salen encima de DAZN o SmartTube"
                else "Necesario para avisos y PITWALL sobre DAZN. Si no deja activarlo, por adb: appops set " + packageName + " SYSTEM_ALERT_WINDOW allow",
                    value(if (can) "Sí" else "No")) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + packageName)))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Actívalo por adb: appops set " + packageName + " SYSTEM_ALERT_WINDOW allow", Toast.LENGTH_LONG).show()
                    }
                }
                val list = Reminders.list(this).filter { it.kind != "watch" }
                srow("Recordatorios puestos", if (list.isEmpty()) "Ninguno · ponlos con «Avísame» en un partido" else list.take(3).joinToString(" · ") { it.title } + " · OK para quitarlos todos",
                    value(list.size.toString())) {
                    for (r in list) Reminders.remove(this, r.id)
                    refresh()
                }
            }
            else -> {
                val installed = Updater.installedCode(this)
                srow("Buscar actualización", "Lumen se actualiza desde GitHub", value(if (prefs.latestCode > installed) "Disponible" else "v$installed")) { checkUpdate() }
                srow("Novedades de esta versión", "", null) { startActivity(Intent(this, WhatsNewActivity::class.java)) }
                val logSize = ErrorLog.read(this).length
                srow("Registro de errores", "También se descarga desde el móvil", value(if (logSize == 0) "Vacío" else (logSize / 1024 + 1).toString() + " KB")) { showLog() }
                srow("Estado del stick", statusText(), null) { refresh() }
                srow("Ajustes del sistema", "Android TV", null) {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        if (focusIdx >= 0) rowsBox.getChildAt(focusIdx)?.requestFocus()
    }

    private fun next(cur: String, opts: List<String>): String = opts[(opts.indexOf(cur) + 1) % opts.size]

    private fun qualityLabel(q: String): String = when (q) {
        "4k" -> "4K"
        "1080p" -> "Máx. 1080p"
        else -> "Auto"
    }

    // ---- DNS sin anuncios ----

    private fun dnsOn(): Boolean {
        return try {
            Settings.Global.getString(contentResolver, "private_dns_mode") == "hostname" &&
                Settings.Global.getString(contentResolver, "private_dns_specifier") == DNS_HOST
        } catch (e: Exception) {
            false
        }
    }

    private fun toggleDns(on: Boolean) {
        try {
            if (on) {
                Settings.Global.putString(contentResolver, "private_dns_specifier", DNS_HOST)
                Settings.Global.putString(contentResolver, "private_dns_mode", "hostname")
            } else {
                Settings.Global.putString(contentResolver, "private_dns_mode", "opportunistic")
            }
            Toast.makeText(this, if (on) "DNS sin anuncios activado" else "DNS sin anuncios desactivado", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            AlertDialog.Builder(this)
                .setTitle("Falta un permiso (una sola vez)")
                .setMessage(
                    "Conecta el stick por ADB y ejecuta:\n\n" +
                        "adb shell pm grant " + packageName + " android.permission.WRITE_SECURE_SETTINGS\n\n" +
                        "Con tools/stick.bat es la opción 7."
                )
                .setPositiveButton("Entendido", null)
                .show()
        }
        build()
    }

    // ---- Lista de canales ----

    private fun refreshChannels() {
        if (!prefs.configured) {
            Toast.makeText(this, "Primero configura la lista IPTV", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Actualizando canales…", Toast.LENGTH_SHORT).show()
        thread {
            val ok = Xtream.load(applicationContext, prefs, true)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Lista actualizada: " + Xtream.channels.size + " canales" else "No se pudo actualizar la lista",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---- Actualización del launcher ----

    private fun checkUpdate() {
        if (prefs.updateRepo.isBlank()) {
            Toast.makeText(this, "Indica el repositorio en Configurar IPTV desde el móvil", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Buscando actualización…", Toast.LENGTH_SHORT).show()
        thread {
            val info = Updater.latest(prefs.updateRepo)
            runOnUiThread {
                if (info == null) {
                    Toast.makeText(this, "No se pudo comprobar. Revisa el repositorio y la conexión.", Toast.LENGTH_LONG).show()
                } else if (info.code <= Updater.installedCode(this)) {
                    prefs.latestCode = info.code
                    Toast.makeText(this, "Ya tienes la última versión", Toast.LENGTH_LONG).show()
                    build()
                } else {
                    prefs.latestCode = info.code
                    Toast.makeText(this, "Descargando " + info.tag + "…", Toast.LENGTH_LONG).show()
                    Updater.downloadAndInstall(this, info) { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ---- Comprobar favoritos ----

    /** Prueba uno a uno los favoritos del perfil y lista los que no responden. Conviene hacerlo sin estar viendo un canal. */
    private fun checkFavorites() {
        if (!prefs.configured) {
            Toast.makeText(this, "Primero configura la lista IPTV", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Comprobando favoritos…", Toast.LENGTH_SHORT).show()
        thread {
            if (Xtream.channels.isEmpty()) Xtream.load(applicationContext, prefs, false)
            val favIds = prefs.favorites
            val favs = Xtream.channels.filter { it.id in favIds }
            val dead = ArrayList<Channel>()
            for (ch in favs) {
                var ok = false
                try {
                    val c = java.net.URL(Xtream.streamUrl(prefs, ch.id)).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 6000
                    c.readTimeout = 6000
                    c.setRequestProperty("User-Agent", "LumenLauncher/0.2")
                    try {
                        ok = c.responseCode in 200..299 && c.inputStream.read() >= 0
                    } finally {
                        c.disconnect()
                    }
                } catch (e: Exception) {
                    ok = false
                }
                if (!ok) dead.add(ch)
                Thread.sleep(300)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (favs.isEmpty()) {
                    Toast.makeText(this, "No tienes favoritos en este perfil", Toast.LENGTH_LONG).show()
                } else if (dead.isEmpty()) {
                    Toast.makeText(this, "Los " + favs.size + " favoritos responden", Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(dead.size.toString() + " de " + favs.size + " no responden")
                        .setMessage(dead.joinToString("\n") { it.name })
                        .setPositiveButton("Quitar de favoritos") { _, _ ->
                            prefs.favorites = prefs.favorites - dead.map { it.id }.toSet()
                            build()
                        }
                        .setNegativeButton("Dejarlos", null)
                        .show()
                }
            }
        }
    }

    // ---- Registro de errores ----

    private fun showLog() {
        val txt = ErrorLog.read(this)
        AlertDialog.Builder(this)
            .setTitle("Registro de errores")
            .setMessage(if (txt.isBlank()) "No hay errores registrados." else txt.takeLast(2500))
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Borrar") { _, _ ->
                ErrorLog.clear(this)
                build()
            }
            .show()
    }

    // ---- Estado del stick ----

    private fun memInfo(): ActivityManager.MemoryInfo {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi
    }

    @Suppress("DEPRECATION")
    private fun wifiText(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo ?: return "sin Wi-Fi"
        val f = info.frequency
        if (f <= 0) return "sin Wi-Fi"
        val band = if (f > 4900) "5 GHz" else "2,4 GHz"
        return band + " · " + info.linkSpeed + " Mbps"
    }

    private fun statusText(): String {
        val mi = memInfo()
        val mb = 1024L * 1024L
        val ramFree = mi.availMem / mb
        val ramTotal = mi.totalMem / mb
        val st = StatFs(Environment.getDataDirectory().path)
        val freeGb = st.availableBytes / (1024.0 * 1024.0 * 1024.0)
        val gb = String.format(java.util.Locale.getDefault(), "%.1f", freeGb)
        return "RAM libre: $ramFree de $ramTotal MB   ·   Almacenamiento libre: $gb GB   ·   Wi-Fi: " + wifiText()
    }

    @Suppress("DEPRECATION")
    private fun freeMemory() {
        val before = memInfo().availMem
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (app in packageManager.getInstalledApplications(0)) {
            if (app.packageName != packageName) am.killBackgroundProcesses(app.packageName)
        }
        // Android tarda un instante en recuperar la memoria
        window.decorView.postDelayed({
            val freed = (memInfo().availMem - before) / (1024L * 1024L)
            Toast.makeText(this, "Memoria liberada: " + maxOf(freed, 0L) + " MB", Toast.LENGTH_LONG).show()
            build()
        }, 1200)
    }
}
