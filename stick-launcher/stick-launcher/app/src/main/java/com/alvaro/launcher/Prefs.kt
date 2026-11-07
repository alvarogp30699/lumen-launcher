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

    // ---- Perfiles: el perfil 0 usa las claves de siempre (así no se pierde nada); los demás llevan prefijo ----
    var profile: Int
        get() = sp.getInt("profile", 0).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("profile", v.coerceIn(0, 2)).apply()
        }

    /** Nombres de los 3 perfiles */
    var profileNames: List<String>
        get() {
            val l = str("profNames", "Yo,Familia,Invitado").split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
            val defaults = listOf("Yo", "Familia", "Invitado")
            while (l.size < 3) l.add(defaults[l.size])
            return l.take(3)
        }
        set(v) = put("profNames", v.joinToString(","))

    private fun pk(k: String): String = if (profile == 0) k else "p" + profile + "_" + k
    private fun strP(k: String, d: String = ""): String = sp.getString(pk(k), d) ?: d
    private fun putP(k: String, v: String) {
        sp.edit().putString(pk(k), v).apply()
    }

    /** PIN de 4 cifras para las categorías bloqueadas (vacío = sin PIN) */
    var pin: String
        get() = str("pin")
        set(v) = put("pin", v.filter { it.isDigit() }.take(6))

    /** Canales ocultos de la lista (por perfil) */
    var hiddenChannels: Set<Int>
        get() = strP("hiddenCh").split(",").mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = putP("hiddenCh", v.joinToString(","))

    /** Categorías ocultas (por perfil) */
    var hiddenCats: Set<String>
        get() = strP("hiddenCat").split(",").filter { it.isNotBlank() }.toSet()
        set(v) = putP("hiddenCat", v.joinToString(","))

    /** Orden de las categorías: las de esta lista van primero, en este orden (por perfil) */
    var catOrder: List<String>
        get() = strP("catOrder").split(",").filter { it.isNotBlank() }
        set(v) = putP("catOrder", v.joinToString(","))

    /** Categorías bloqueadas con PIN (por perfil) */
    var lockedCats: Set<String>
        get() = strP("lockedCat").split(",").filter { it.isNotBlank() }.toSet()
        set(v) = putP("lockedCat", v.joinToString(","))

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
        get() = str("repo", "alvarogp30699/lumen-launcher")
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
        get() = sp.getInt(pk("lastId"), -1)
        set(v) {
            sp.edit().putInt(pk("lastId"), v).apply()
        }

    var lastChannelName: String
        get() = strP("lastName")
        set(v) = putP("lastName", v)

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

    /** Canales visibles para este perfil: sin ocultos ni de categorías ocultas/bloqueadas (salvo desbloqueadas). */
    fun visibleChannels(all: List<Channel>, unlocked: Set<String> = emptySet()): List<Channel> {
        val hc = hiddenChannels
        val hcat = hiddenCats
        val locked = lockedCats.filter { it !in unlocked }.toSet()
        // sin nada oculto ni bloqueado se devuelve la misma lista (no se copian miles de canales)
        if (hc.isEmpty() && hcat.isEmpty() && locked.isEmpty()) return all
        return all.filter { it.id !in hc && it.cat !in hcat && it.cat !in locked }
    }

    /** Retraso del marcador de fútbol por canal (segundos): así no te enteras del gol antes de verlo */
    fun footDelay(channelId: Int): Int = sp.getInt("fd_$channelId", sp.getInt("fd_last", 45))

    fun setFootDelay(channelId: Int, sec: Int) {
        sp.edit().putInt("fd_$channelId", sec).putInt("fd_last", sec).apply()
    }

    /** Avisos de F1 (bandera amarilla, safety car…) sobre el vídeo, con el retraso aplicado */
    var pitwallAlerts: Boolean
        get() = sp.getBoolean("pwAlerts", true)
        set(v) {
            sp.edit().putBoolean("pwAlerts", v).apply()
        }

    /** Siglas de tus pilotos favoritos, p. ej. VER,ALO: solo avisos de ellos (más los de bandera y lluvia) */
    var pitwallFavs: String
        get() = strP("pwFavs", str("pwFavs"))
        set(v) = putP("pwFavs", v.uppercase().replace(" ", ""))

    /** Abrir el panel solo al empezar una sesión de F1 y cerrarlo al acabar */
    var pitwallAuto: Boolean
        get() = sp.getBoolean("pwAuto", true)
        set(v) {
            sp.edit().putBoolean("pwAuto", v).apply()
        }

    /** Código secreto del mando del móvil (se genera una vez) */
    val remoteToken: String
        get() {
            var t = str("remoteTok")
            if (t.isBlank()) {
                t = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                put("remoteTok", t)
            }
            return t
        }

    /** Última versión nueva por la que ya se preguntó, para no insistir */
    var askedCode: Int
        get() = sp.getInt("askedCode", 0)
        set(v) = putInt("askedCode", v)

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

    private fun bool(k: String, d: Boolean): Boolean = sp.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) {
        sp.edit().putBoolean(k, v).apply()
    }

    // ---- Sincronización ----
    /** Fútbol: leer el reloj del partido en la imagen y calcular el retraso solo */
    var syncAuto: Boolean
        get() = bool("syncAuto", true)
        set(v) = setBool("syncAuto", v)

    /** Fútbol: los goles no salen en el panel hasta que cambie el marcador de la tele */
    var goalsGate: Boolean
        get() = bool("goalsGate", true)
        set(v) = setBool("goalsGate", v)

    /** Margen de seguridad que se suma al retraso calculado (s) */
    var syncMargin: Int
        get() = sp.getInt("syncMargin", 10)
        set(v) = putInt("syncMargin", v.coerceIn(0, 60))

    /** Últimas mediciones del retraso de DAZN (s), para arrancar con la mediana */
    var daznDelays: List<Double>
        get() = str("daznDelays").split(",").mapNotNull { it.toDoubleOrNull() }
        set(v) = put("daznDelays", v.takeLast(12).joinToString(",") { String.format(java.util.Locale.US, "%.1f", it) })

    fun daznDelay(): Int {
        val l = daznDelays.sorted()
        return if (l.isEmpty()) 30 else l[l.size / 2].toInt()
    }

    /** Tecla del mando que hace de «¡Vuelta!» dentro de DAZN (mantenerla pulsada) */
    var vueltaKey: String
        get() = str("vueltaKey", "MENU")
        set(v) = put("vueltaKey", v)

    /** Modo de PITWALL sobre DAZN: compact, full o alerts */
    var overlayMode: String
        get() = str("overlayMode", "compact")
        set(v) = put("overlayMode", v)

    // ---- Avisos ----
    /** Avisar 10 min antes de cada partido del Real Madrid */
    var notifyMadrid: Boolean
        get() = bool("notifyMadrid", true)
        set(v) = setBool("notifyMadrid", v)

    /** Aviso de gol del Madrid cuando no estás viendo el partido */
    var notifyGoals: Boolean
        get() = bool("notifyGoals", true)
        set(v) = setBool("notifyGoals", v)

    /** Recordatorios puestos con «Avísame» (JSON) */
    var remindersJson: String
        get() = str("reminders", "[]")
        set(v) = put("reminders", v)

    /** Última versión cuyas novedades ya se enseñaron */
    var seenCode: Int
        get() = sp.getInt("seenCode", 0)
        set(v) = putInt("seenCode", v)

    /** Pista de audio elegida por canal ("es", "en"…; vacío = la del canal) */
    fun audioFor(channelId: Int): String = str("aud_$channelId")
    fun setAudioFor(channelId: Int, lang: String) = put("aud_$channelId", lang)

    /** Subtítulos por canal ("" = desactivados) */
    fun subsFor(channelId: Int): String = str("sub_$channelId")
    fun setSubsFor(channelId: Int, lang: String) = put("sub_$channelId", lang)

    /** Color de acento del launcher */
    var accent: String
        get() = str("accent", "#F5B544")
        set(v) = put("accent", v)

    /** Cerrar apps de fondo al abrir un canal (más memoria para el vídeo) */
    var autoFree: Boolean
        get() = sp.getBoolean("autoFree", true)
        set(v) {
            sp.edit().putBoolean("autoFree", v).apply()
        }

    /** Última app abierta desde el launcher (tecla MENU en el inicio para volver) */
    var lastApp: String
        get() = strP("lastApp")
        set(v) = putP("lastApp", v)

    val configured: Boolean
        get() = server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()

    var favorites: Set<Int>
        get() = strP("favs").split(",").mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = putP("favs", v.joinToString(","))

    var pinned: List<String>
        get() = if (sp.contains(pk("pinned"))) strP("pinned").split(",").filter { it.isNotBlank() } else DEFAULT_PINS
        set(v) = putP("pinned", v.joinToString(","))

    var hidden: Set<String>
        get() = strP("hidden").split(",").filter { it.isNotBlank() }.toSet()
        set(v) = putP("hidden", v.joinToString(","))

    companion object {
        private val SKIP = setOf("latestCode", "lastCheck", "askedCode", "profile", "seenCode")
        val DEFAULT_PINS = listOf(
            "com.teamsmart.videomanager.tv",
            "com.dazn",
            "com.esaba.downloader",
            "com.android.vending"
        )
    }
}
