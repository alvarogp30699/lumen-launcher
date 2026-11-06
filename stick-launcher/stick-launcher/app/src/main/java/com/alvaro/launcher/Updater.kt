package com.alvaro.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Busca versiones nuevas en los Releases de GitHub (tags v1.N, donde N = versionCode) e instala el APK. */
object Updater {
    class Info(val tag: String, val code: Int, val url: String)

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Int =
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        } catch (e: Exception) {
            0
        }

    fun latest(repo: String): Info? {
        return try {
            val j = JSONObject(Xtream.http("https://api.github.com/repos/$repo/releases/latest"))
            val tag = j.optString("tag_name")
            val assets = j.optJSONArray("assets")
            var url = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
            }
            val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
            if (url.isEmpty()) null else Info(tag, code, url)
        } catch (e: Exception) {
            null
        }
    }

    /** Comprueba como mucho cada 12 h y deja anotado si hay versión nueva. */
    fun checkInBackground(ctx: Context) {
        val prefs = Prefs(ctx)
        if (prefs.updateRepo.isBlank()) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < 12 * 3600 * 1000L) return
        thread {
            val info = latest(prefs.updateRepo)
            if (info != null) {
                prefs.latestCode = info.code
                prefs.lastUpdateCheck = System.currentTimeMillis()
            }
        }
    }

    /** Descarga el APK y abre el instalador del sistema. onError recibe el motivo si algo falla. */
    fun downloadAndInstall(act: Activity, info: Info, onError: (String) -> Unit) {
        thread {
            try {
                val f = File(act.cacheDir, "lumen-update.apk")
                val c = URL(info.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "LumenLauncher/0.2")
                c.inputStream.use { ins -> f.outputStream().use { out -> ins.copyTo(out) } }
                act.runOnUiThread {
                    try {
                        val uri = FileProvider.getUriForFile(act, act.packageName + ".files", f)
                        val i = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        act.startActivity(i)
                    } catch (e: Exception) {
                        onError("No se pudo abrir el instalador de este stick.")
                    }
                }
            } catch (e: Exception) {
                act.runOnUiThread { onError("No se pudo descargar la actualización.") }
            }
        }
    }
}
