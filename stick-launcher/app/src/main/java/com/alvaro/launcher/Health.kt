package com.alvaro.launcher

import org.json.JSONObject

/** Comprobaciones rápidas al abrir el inicio: avisan del motivo cuando algo no responde. */
object Health {
    fun check(prefs: Prefs): List<String> {
        val out = ArrayList<String>()
        if (prefs.configured) {
            try {
                val url = prefs.server + "/player_api.php?username=" + java.net.URLEncoder.encode(prefs.user, "UTF-8") +
                    "&password=" + java.net.URLEncoder.encode(prefs.pass, "UTF-8")
                val ui = JSONObject(Xtream.http(url)).optJSONObject("user_info")
                if (ui == null || ui.optInt("auth", 1) == 0) {
                    out.add("⚠ IPTV: usuario o contraseña incorrectos")
                } else {
                    val status = ui.optString("status", "Active")
                    val exp = ui.optString("exp_date", "").toLongOrNull()
                    if (!status.equals("Active", true)) {
                        out.add("⚠ IPTV: cuenta " + status.lowercase())
                    } else if (exp != null && exp * 1000L < System.currentTimeMillis()) {
                        out.add("⚠ IPTV: la suscripción ha caducado")
                    }
                }
            } catch (e: Exception) {
                out.add("⚠ IPTV: el servidor no responde (" + (e.message ?: "sin conexión").take(40) + ")")
            }
        }
        if (prefs.pitwallUrl.isNotBlank()) {
            try {
                val u = java.net.URI(prefs.pitwallUrl.trim())
                Xtream.http(u.scheme + "://" + u.authority + "/api/health")
            } catch (e: Exception) {
                out.add("⚠ PITWALL: no responde (¿PC apagado o dirección distinta?)")
            }
        }
        return out
    }
}
