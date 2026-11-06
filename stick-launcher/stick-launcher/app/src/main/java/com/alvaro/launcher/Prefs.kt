package com.alvaro.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ajustes guardados en el propio stick. No hay ninguna lista ni credencial incluida en la app. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    private fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) {
        sp.edit().putString(k, v).apply()
    }

    private fun putInt(k: String, v: Int) {
        sp.edit().putInt(k, v).apply()
    }

    var server: String
        get() = str("server")
        set(v) = put("server", v.trim().trimEnd('/'))

    var user: String
        get() = str("user")
        set(v) = put("user", v.trim())

    var pass: String
        get() = str("pass")
        set(v) = put("pass", v.trim())

    /** usuario/repositorio de GitHub donde se publican las versiones del launcher */
    var updateRepo: String
        get() = str("repo")
        set(v) = put("repo", v.trim())

    /** auto | 4k | 1080p */
    var quality: String
        get() = str("quality", "auto")
        set(v) = put("quality", v)

    /** m3u8 | ts */
    var format: String
        get() = str("format", "m3u8")
        set(v) = put("format", v)

    var sleepMinutes: Int
        get() = sp.getInt("sleep", 0)
        set(v) = putInt("sleep", v)

    var lastChannelId: Int
        get() = sp.getInt("lastId", -1)
        set(v) = putInt("lastId", v)

    var lastChannelName: String
        get() = str("lastName")
        set(v) = put("lastName", v)

    var latestCode: Int
        get() = sp.getInt("latestCode", 0)
        set(v) = putInt("latestCode", v)

    var lastUpdateCheck: Long
        get() = sp.getLong("lastCheck", 0L)
        set(v) {
            sp.edit().putLong("lastCheck", v).apply()
        }

    /** Dirección de PITWALL en tu PC, p. ej. http://192.168.1.50:3000 (con ?token=... si usas contraseña) */
    var pitwallUrl: String
        get() = str("pitwall")
        set(v) = put("pitwall", v.trim())

    /** Retraso anti-spoiler (segundos) recordado por canal */
    fun pitwallDelay(channelId: Int): Int = sp.getInt("pwd_$channelId", sp.getInt("pwd_last", 10))

    fun setPitwallDelay(channelId: Int, sec: Int) {
        sp.edit().putInt("pwd_$channelId", sec).putInt("pwd_last", sec).apply()
    }

    /** Avisos de F1 (bandera amarilla, safety car…) sobre el vídeo, con el retraso aplicado */
    var pitwallAlerts: Boolean
        get() = sp.getBoolean("pwAlerts", true)
        set(v) {
            sp.edit().putBoolean("pwAlerts", v).apply()
        }

    /** Copia de seguridad de todos los ajustes (JSON). Incluye la contraseña de la lista: guárdala en un sitio privado. */
    fun exportJson(): String {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            if (k in SKIP) continue
            when (v) {
                is String -> o.put(k, JSONArray().put("s").put(v))
                is Int -> o.put(k, JSONArray().put("i").put(v))
                is Long -> o.put(k, JSONArray().put("l").put(v))
                is Boolean -> o.put(k, JSONArray().put("b").put(v))
            }
        }
        return o.toString(2)
    }

    /** Restaura una copia. Devuelve el número de ajustes recuperados (0 si el archivo no sirve). */
    fun importJson(text: String): Int {
        return try {
            val o = JSONObject(text)
            val ed = sp.edit()
            var n = 0
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k in SKIP) continue
                val a = o.optJSONArray(k) ?: continue
                when (a.optString(0)) {
                    "s" -> ed.putString(k, a.optString(1))
                    "i" -> ed.putInt(k, a.optInt(1))
                    "l" -> ed.putLong(k, a.optLong(1))
                    "b" -> ed.putBoolean(k, a.optBoolean(1))
                    else -> continue
                }
                n++
            }
            ed.apply()
            n
        } catch (e: Exception) {
            0
        }
    }

    val configured: Boolean
        get() = server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()

    var favorites: Set<Int>
        get() = str("favs").split(",").mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = put("favs", v.joinToString(","))

    var pinned: List<String>
        get() = if (sp.contains("pinned")) str("pinned").split(",").filter { it.isNotBlank() } else DEFAULT_PINS
        set(v) = put("pinned", v.joinToString(","))

    var hidden: Set<String>
        get() = str("hidden").split(",").filter { it.isNotBlank() }.toSet()
        set(v) = put("hidden", v.joinToString(","))

    companion object {
        private val SKIP = setOf("latestCode", "lastCheck")
        val DEFAULT_PINS = listOf(
            "com.teamsmart.videomanager.tv",
            "com.dazn",
            "com.esaba.downloader",
            "com.android.vending"
        )
    }
}
