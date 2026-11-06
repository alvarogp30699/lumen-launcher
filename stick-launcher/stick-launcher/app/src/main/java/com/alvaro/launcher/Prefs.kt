package com.alvaro.launcher

import android.content.Context

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
        val DEFAULT_PINS = listOf(
            "com.teamsmart.videomanager.tv",
            "com.dazn",
            "com.esaba.downloader",
            "com.android.vending"
        )
    }
}
