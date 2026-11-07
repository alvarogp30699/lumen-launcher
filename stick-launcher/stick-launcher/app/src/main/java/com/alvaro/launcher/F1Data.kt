package com.alvaro.launcher

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Próximo Gran Premio leído de tu PITWALL (se reutiliza 5 min para no preguntar en cada pantalla). */
object F1Data {
    class Session(val name: String, val at: Long)
    class Event(val name: String, val sessions: List<Session>)

    @Volatile
    private var raw: String? = null

    @Volatile
    private var rawAt = 0L

    fun next(prefs: Prefs): Event? {
        if (prefs.pitwallUrl.isBlank()) return null
        return try {
            val u = java.net.URI(prefs.pitwallUrl.trim())
            val tok = (u.query ?: "").split("&").firstOrNull { it.startsWith("token=") }
            val url = u.scheme + "://" + u.authority + "/api/standings?series=f1" + (if (tok != null) "&$tok" else "")
            val now = System.currentTimeMillis()
            val txt = raw?.takeIf { now - rawAt < 300_000L } ?: Xtream.http(url).also {
                raw = it
                rawAt = now
            }
            val next = JSONObject(txt).optJSONObject("next") ?: return null
            val arr = next.optJSONArray("sessions") ?: return null
            val parse = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val list = ArrayList<Session>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val at: Long = (
                    try {
                        parse.parse(o.optString("at"))?.time
                    } catch (e: Exception) {
                        null
                    }
                    ) ?: continue
                list.add(Session(o.optString("name"), at))
            }
            list.sortBy { it.at }
            if (list.isEmpty()) null else Event(next.optString("name"), list)
        } catch (e: Exception) {
            null
        }
    }

    /** Sesión en curso (hasta 2 h después de empezar) o la siguiente. */
    fun current(e: Event, now: Long): Session? = e.sessions.firstOrNull { it.at + 2 * 3600_000L >= now }

    /** De 12 h antes de la primera sesión a 3 h después de la última: es "fin de semana de F1". */
    fun isWeekend(e: Event, now: Long): Boolean =
        now >= e.sessions.first().at - 12 * 3600_000L && now <= e.sessions.last().at + 3 * 3600_000L

    fun countdown(at: Long, now: Long): String {
        val mins = (at - now) / 60_000
        return when {
            mins <= 0 -> "EN DIRECTO"
            mins < 60 -> "EN $mins MIN"
            mins < 48 * 60 -> "EN " + (mins / 60) + " H " + (mins % 60) + " MIN"
            else -> "EN " + (mins / 1440) + " DÍAS"
        }
    }
}
