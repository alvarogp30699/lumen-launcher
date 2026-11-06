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
    private var focusIdx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
    }

    override fun onResume() {
        super.onResume()
        prefs = Prefs(this)
        build()
    }

    private fun build() {
        val rows = ArrayList<View>()

        fun add(title: String, value: String, action: () -> Unit) {
            val idx = rows.size
            rows.add(makeRow(this, ui, title, value) {
                focusIdx = idx
                action()
            })
        }

        val dns = dnsOn()
        add("Sin anuncios (DNS AdGuard)", if (dns) "Activado" else "Desactivado") { toggleDns(!dns) }
        add("Calidad de vídeo", qualityLabel(prefs.quality)) {
            prefs.quality = next(prefs.quality, listOf("auto", "4k", "1080p"))
            build()
        }
        add("Formato de canal", prefs.format) {
            prefs.format = next(prefs.format, listOf("m3u8", "ts"))
            build()
        }
        add("Temporizador de reproducción", if (prefs.sleepMinutes == 0) "Desactivado" else prefs.sleepMinutes.toString() + " min") {
            val opts = listOf("0", "30", "60", "90", "120")
            prefs.sleepMinutes = next(prefs.sleepMinutes.toString(), opts).toInt()
            build()
        }
        add("Configurar IPTV desde el móvil", "QR") {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        add("Actualizar lista de canales", "") { refreshChannels() }

        val installed = Updater.installedCode(this)
        add("Buscar actualización del launcher", if (prefs.latestCode > installed) "Disponible" else "v$installed") { checkUpdate() }
        add("Liberar memoria", "") { freeMemory() }
        add("Limpiar caché de Lumen", "") {
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            Xtream.clearCache(applicationContext)
            Xtream.channels = emptyList()
            Toast.makeText(this, "Caché limpiada", Toast.LENGTH_SHORT).show()
        }
        add("Ajustes del sistema", "") {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        val title = TextView(this).apply {
            text = "Ajustes"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 44)
            typeface = Typeface.DEFAULT_BOLD
        }
        val status = TextView(this).apply {
            text = statusText()
            setTextColor(Colors.MUTED)
            setSizePx(ui, 24)
            setPadding(0, ui.px(12), 0, ui.px(28))
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(120), ui.px(56), ui.px(120), ui.px(56))
            addView(title)
            addView(status)
        }
        for (r in rows) {
            box.addView(r, LinearLayout.LayoutParams(ui.px(1100), ui.px(84)).apply {
                bottomMargin = ui.px(14)
            })
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Colors.BG)
            isVerticalScrollBarEnabled = false
            addView(box)
        }
        setContentView(scroll)
        rows.getOrNull(focusIdx)?.requestFocus()
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
