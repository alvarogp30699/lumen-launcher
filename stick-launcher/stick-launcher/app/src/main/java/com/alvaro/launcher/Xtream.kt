package com.alvaro.launcher

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Category(val id: String, val name: String)
data class Channel(val id: Int, val num: Int, val name: String, val cat: String, val key: String = normText(name))
data class Epg(val title: String, val start: Long, val end: Long)

/** Cliente mínimo de la API Xtream Codes (canales en directo y guía corta). */
object Xtream {
    @Volatile
    var categories: List<Category> = emptyList()

    @Volatile
    var channels: List<Channel> = emptyList()

    /** Lista sobre la que se hace zapping en el reproductor */
    @Volatile
    var queue: List<Channel> = emptyList()

    fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "LumenLauncher/0.2")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun api(p: Prefs, action: String, extra: String = ""): String =
        p.server + "/player_api.php?username=" + enc(p.user) + "&password=" + enc(p.pass) + "&action=" + action + extra

    fun streamUrl(p: Prefs, id: Int): String =
        p.server + "/live/" + enc(p.user) + "/" + enc(p.pass) + "/" + id + "." + p.format

    /** Carga categorías y canales (caché de 6 h en disco). Llamar siempre fuera del hilo principal. */
    fun load(ctx: Context, p: Prefs, force: Boolean): Boolean {
        val catFile = File(ctx.filesDir, "cats.json")
        val chFile = File(ctx.filesDir, "channels.json")
        val fresh = catFile.exists() && chFile.exists() &&
            System.currentTimeMillis() - chFile.lastModified() < 6 * 3600 * 1000L
        if (!force && fresh) {
            if (parseInto(catFile.readText(), chFile.readText())) return true
        }
        try {
            val cats = http(api(p, "get_live_categories"))
            val chs = http(api(p, "get_live_streams"))
            if (parseInto(cats, chs)) {
                catFile.writeText(cats)
                chFile.writeText(chs)
                return true
            }
        } catch (e: Exception) {
            // sin red o credenciales mal: se intenta con la caché
        }
        if (catFile.exists() && chFile.exists()) return parseInto(catFile.readText(), chFile.readText())
        return false
    }

    fun clearCache(ctx: Context) {
        File(ctx.filesDir, "cats.json").delete()
        File(ctx.filesDir, "channels.json").delete()
    }

    private fun parseInto(catsJson: String, chJson: String): Boolean {
        return try {
            val ca = JSONArray(catsJson)
            val cats = ArrayList<Category>()
            for (i in 0 until ca.length()) {
                val o = ca.getJSONObject(i)
                cats.add(Category(o.optString("category_id"), o.optString("category_name")))
            }
            val ha = JSONArray(chJson)
            val list = ArrayList<Channel>()
            for (i in 0 until ha.length()) {
                val o = ha.getJSONObject(i)
                list.add(
                    Channel(
                        o.optInt("stream_id"),
                        o.optInt("num"),
                        o.optString("name"),
                        o.optString("category_id")
                    )
                )
            }
            list.sortBy { it.num }
            categories = cats
            channels = list
            list.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /** Programa actual y siguiente de un canal. */
    fun epg(p: Prefs, id: Int): List<Epg> {
        return try {
            val j = JSONObject(http(api(p, "get_short_epg", "&stream_id=$id&limit=3")))
            val a = j.optJSONArray("epg_listings") ?: return emptyList()
            val out = ArrayList<Epg>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Epg(
                        decode(o.optString("title")),
                        o.optString("start_timestamp").toLongOrNull() ?: 0L,
                        o.optString("stop_timestamp").toLongOrNull() ?: 0L
                    )
                )
            }
            out.sortBy { it.start }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decode(s: String): String {
        return try {
            val d = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            if (d.isBlank() || d.any { it == '�' || (it < ' ' && it != '\n') }) s else d
        } catch (e: Exception) {
            s
        }
    }
}
