package com.alvaro.launcher

import android.content.Context
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Category(val id: String, val name: String)
data class Channel(val id: Int, val num: Int, val name: String, val cat: String, val icon: String = "", val key: String = normText(name))
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

    /** Carga categorías y canales (caché de 6 h en disco). Llamar siempre fuera del hilo principal.
     *  La lista se descarga directamente a disco y se lee "en streaming": aunque tenga miles de canales
     *  nunca se carga entera en memoria como texto (clave con 2 GB de RAM). */
    fun load(ctx: Context, p: Prefs, force: Boolean): Boolean {
        val catFile = File(ctx.filesDir, "cats.json")
        val chFile = File(ctx.filesDir, "channels.json")
        val fresh = catFile.exists() && chFile.exists() &&
            System.currentTimeMillis() - chFile.lastModified() < 6 * 3600 * 1000L
        if (!force && fresh && parseFiles(catFile, chFile)) return true
        try {
            val catTmp = File(ctx.filesDir, "cats.tmp")
            val chTmp = File(ctx.filesDir, "channels.tmp")
            download(api(p, "get_live_categories"), catTmp)
            download(api(p, "get_live_streams"), chTmp)
            if (parseFiles(catTmp, chTmp)) {
                catTmp.renameTo(catFile)
                chTmp.renameTo(chFile)
                return true
            }
            catTmp.delete()
            chTmp.delete()
        } catch (e: Exception) {
            // sin red o credenciales mal: se intenta con la caché
            ErrorLog.add(ctx, "Lista IPTV: " + (e.message ?: e.javaClass.simpleName))
        }
        return catFile.exists() && chFile.exists() && parseFiles(catFile, chFile)
    }

    fun clearCache(ctx: Context) {
        File(ctx.filesDir, "cats.json").delete()
        File(ctx.filesDir, "channels.json").delete()
        synchronized(epgCache) { epgCache.clear() }
    }

    private fun download(url: String, dest: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "LumenLauncher/0.2")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP " + c.responseCode)
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it, 32 * 1024) } }
        } finally {
            c.disconnect()
        }
    }

    /** Lee un valor simple (texto o número) o null, sin importar cómo lo envíe el proveedor. */
    private fun JsonReader.str(): String? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); null }
    }

    private fun parseFiles(catFile: File, chFile: File): Boolean {
        return try {
            val cats = ArrayList<Category>()
            JsonReader(catFile.bufferedReader()).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var id = ""
                    var name = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "category_id" -> id = r.str() ?: ""
                            "category_name" -> name = r.str() ?: ""
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    cats.add(Category(id, name))
                }
                r.endArray()
            }
            // los ids de categoría se repiten miles de veces: se comparte una sola copia de cada uno
            val pool = HashMap<String, String>()
            val list = ArrayList<Channel>(4096)
            JsonReader(chFile.bufferedReader()).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var id = 0
                    var num = 0
                    var name = ""
                    var cat = ""
                    var icon = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id" -> id = r.str()?.toIntOrNull() ?: 0
                            "num" -> num = r.str()?.toIntOrNull() ?: 0
                            "name" -> name = r.str() ?: ""
                            "category_id" -> { val c = r.str() ?: ""; cat = pool.getOrPut(c) { c } }
                            "stream_icon" -> icon = (r.str() ?: "").trim()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id != 0) list.add(Channel(id, num, name, cat, icon))
                }
                r.endArray()
            }
            list.sortBy { it.num }
            list.trimToSize()
            categories = cats
            channels = list
            list.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    // Guía corta en memoria durante 5 min (máx. 300 canales): al volver a un canal no se pide otra vez
    private class CachedEpg(val at: Long, val list: List<Epg>)
    private val epgCache = object : LinkedHashMap<Int, CachedEpg>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedEpg>?): Boolean = size > 300
    }

    fun trimMemory() {
        synchronized(epgCache) { epgCache.clear() }
    }

    /** Programa actual y siguiente de un canal. */
    fun epg(p: Prefs, id: Int, limit: Int = 3): List<Epg> {
        val nowMs = System.currentTimeMillis()
        val key = id * 16 + limit.coerceIn(1, 15)
        synchronized(epgCache) {
            val c = epgCache[key]
            if (c != null && nowMs - c.at < 5 * 60_000L && c.list.last().end * 1000 > nowMs) return c.list
        }
        val fetched = fetchEpg(p, id, limit.coerceIn(1, 15))
        // solo se guarda si llegó algo: un fallo de red no deja el canal 5 min sin guía
        if (fetched.isNotEmpty()) synchronized(epgCache) { epgCache[key] = CachedEpg(nowMs, fetched) }
        return fetched
    }

    private fun fetchEpg(p: Prefs, id: Int, limit: Int): List<Epg> {
        return try {
            val j = JSONObject(http(api(p, "get_short_epg", "&stream_id=$id&limit=$limit")))
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
