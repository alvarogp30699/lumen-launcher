package com.alvaro.launcher

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Datos de fútbol (ESPN, gratis y sin clave): partidos, marcadores en directo, eventos,
 * estadísticas, alineaciones y clasificaciones de LaLiga, Champions y Copa del Rey.
 * Todo se guarda unos segundos en memoria para no repetir peticiones.
 */
object Football {
    class League(val code: String, val name: String)

    val LEAGUES = listOf(
        League("esp.1", "LaLiga"),
        League("uefa.champions", "Champions League"),
        League("esp.copa_del_rey", "Copa del Rey")
    )
    const val TEAM_ID = "86"
    const val TEAM_NAME = "Real Madrid"

    class Side(val id: String, val name: String, val short: String, val abbr: String, val score: String, val logo: String, val color: String = "") {
        /** Color del equipo (ESPN lo da sin #); gris si no viene */
        fun colorInt(): Int = try {
            android.graphics.Color.parseColor("#" + color.trim().removePrefix("#").ifBlank { "5A6070" })
        } catch (e: Exception) {
            android.graphics.Color.parseColor("#5A6070")
        }
    }
    class Event(val minute: String, val kind: String, val player: String, val teamId: String, val goal: Boolean, val yellow: Boolean, val red: Boolean) {
        /** Minuto como número (45'+2 → 45) para colocarlo en la barra de tiempo */
        fun minuteInt(): Int = minute.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    class Match(
        val id: String,
        val league: League,
        val start: Long,
        val state: String,
        val clock: String,
        val detail: String,
        val home: Side,
        val away: Side,
        val events: List<Event>,
        val venue: String = ""
    ) {
        /** Minuto en juego como número (para la barra de tiempo); 0 si no ha empezado */
        fun minuteNow(): Int = if (done) 90 else clock.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        fun goals(): List<Event> = events.filter { it.goal }
        val live: Boolean get() = state == "in"
        val done: Boolean get() = state == "post"
        fun hasTeam(id: String): Boolean = home.id == id || away.id == id
        fun score(): String = if (state == "pre") "vs" else home.score + " – " + away.score
        fun reds(): Int = events.count { it.red }

        /** Texto corto del estado: minuto en directo, "Descanso", "Final" u hora de inicio. */
        fun status(): String = when {
            live -> if (detail.contains("HT", true) || detail.contains("Half", true)) "Descanso" else clock.ifBlank { "En juego" }
            done -> "Final"
            else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(start))
        }
    }

    class Stat(val label: String, val home: String, val away: String)
    class Player(val num: String, val name: String, val place: Int)
    class Lineup(val formation: String, val players: List<String>, val starters: List<Player> = emptyList())
    class Detail(val stats: List<Stat>, val home: Lineup?, val away: Lineup?, val homeForm: List<String> = emptyList(), val awayForm: List<String> = emptyList())
    class Row(val rank: Int, val team: String, val teamId: String, val played: Int, val w: Int, val d: Int, val l: Int, val gd: String, val pts: Int, val logo: String = "")

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/"
    private const val DAY = 24 * 3600_000L

    // ---- memoria corta ----
    private val memo = HashMap<String, Pair<Long, Any>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> memo(key: String, maxAge: Long, load: () -> T): T {
        val now = System.currentTimeMillis()
        synchronized(memo) { memo[key]?.let { if (now - it.first < maxAge) return it.second as T } }
        val v = load()
        synchronized(memo) {
            if (memo.size > 40) memo.clear()
            memo[key] = Pair(now, v)
        }
        return v
    }

    fun clear() {
        synchronized(memo) { memo.clear() }
    }

    // ---- partidos ----

    private fun ymd(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))

    private fun scoreboard(lg: League, from: Long, to: Long): List<Match> {
        val d = if (ymd(from) == ymd(to)) ymd(from) else ymd(from) + "-" + ymd(to)
        return parseEvents(JSONObject(Xtream.http(BASE + lg.code + "/scoreboard?limit=300&dates=" + d)), lg)
    }

    private fun parseEvents(j: JSONObject, lg: League): List<Match> {
        val out = ArrayList<Match>()
        val arr = j.optJSONArray("events") ?: return out
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            parseEvent(e, lg)?.let { out.add(it) }
        }
        out.sortBy { it.start }
        return out
    }

    private fun parseDate(s: String): Long {
        for (f in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                val d = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)
                if (d != null) return d.time
            } catch (e: Exception) {
                // se prueba el otro formato
            }
        }
        return 0L
    }

    private fun parseEvent(e: JSONObject, lg: League): Match? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val cs = comp.optJSONArray("competitors") ?: return null
        var home: Side? = null
        var away: Side? = null
        for (k in 0 until cs.length()) {
            val c = cs.optJSONObject(k) ?: continue
            val t = c.optJSONObject("team") ?: continue
            val name = t.optString("displayName")
            val side = Side(
                t.optString("id"), name, t.optString("shortDisplayName", name),
                t.optString("abbreviation"), c.optString("score", "0").ifBlank { "0" }, t.optString("logo"), t.optString("color")
            )
            if (c.optString("homeAway") == "home") home = side else away = side
        }
        val h = home ?: return null
        val a = away ?: return null
        val st = e.optJSONObject("status") ?: comp.optJSONObject("status")
        val type = st?.optJSONObject("type")
        val evs = ArrayList<Event>()
        val det = comp.optJSONArray("details")
        if (det != null) {
            for (k in 0 until det.length()) {
                val d = det.optJSONObject(k) ?: continue
                val text = d.optJSONObject("type")?.optString("text") ?: ""
                val goal = d.optBoolean("scoringPlay", false)
                val yellow = d.optBoolean("yellowCard", false)
                val red = d.optBoolean("redCard", false)
                if (!goal && !yellow && !red) continue
                val kind = when {
                    goal && text.contains("Own", true) -> "Gol en propia"
                    goal && text.contains("Penalty", true) -> "Gol de penalti"
                    goal -> "Gol"
                    red -> "Roja"
                    else -> "Amarilla"
                }
                val who = d.optJSONArray("athletesInvolved")?.optJSONObject(0)?.optString("displayName") ?: ""
                evs.add(Event(d.optJSONObject("clock")?.optString("displayValue") ?: "", kind, who, d.optJSONObject("team")?.optString("id") ?: "", goal, yellow, red))
            }
        }
        return Match(
            e.optString("id"), lg, parseDate(e.optString("date")),
            type?.optString("state", "pre") ?: "pre",
            st?.optString("displayClock", "") ?: "",
            type?.optString("shortDetail", "") ?: "",
            h, a, evs,
            comp.optJSONObject("venue")?.optString("fullName") ?: ""
        )
    }

    private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Partidos de hoy (hora de España) de las tres competiciones, más los que sigan en juego. */
    fun today(): List<Match> {
        val now = System.currentTimeMillis()
        val from = dayStart(now)
        val out = ArrayList<Match>()
        for (lg in LEAGUES) {
            out.addAll(memo("t_" + lg.code, 30_000L) {
                try {
                    scoreboard(lg, now - DAY, now + DAY)
                } catch (e: Exception) {
                    emptyList()
                }
            })
        }
        return out.filter { it.live || it.start in from until from + DAY }.sortedBy { it.start }
    }

    /** Partidos del Real Madrid: últimas 3 semanas y próximas 5. */
    fun teamMatches(): List<Match> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Match>()
        for (lg in LEAGUES) {
            out.addAll(memo("tm_" + lg.code, 90_000L) {
                try {
                    scoreboard(lg, now - 21 * DAY, now + 35 * DAY).filter { it.hasTeam(TEAM_ID) }
                } catch (e: Exception) {
                    emptyList()
                }
            })
        }
        return out.distinctBy { it.id }.sortedBy { it.start }
    }

    /** Partidos de los próximos 7 días de las tres competiciones (semana deportiva). */
    fun week(): List<Match> {
        val now = System.currentTimeMillis()
        val from = dayStart(now)
        val out = ArrayList<Match>()
        for (lg in LEAGUES) {
            out.addAll(memo("w_" + lg.code, 600_000L) {
                try {
                    scoreboard(lg, from, from + 7 * DAY)
                } catch (e: Exception) {
                    emptyList()
                }
            })
        }
        return out.filter { it.start in from until from + 7 * DAY }.distinctBy { it.id }.sortedBy { it.start }
    }

    /** El partido que importa ahora: en directo, si no el próximo, si no el último jugado. */
    fun teamFocus(): Match? {
        val l = teamMatches()
        val now = System.currentTimeMillis()
        return l.firstOrNull { it.live } ?: l.firstOrNull { it.state == "pre" && it.start > now - 3 * 3600_000L } ?: l.lastOrNull { it.done }
    }

    /** Estado actualizado de un partido (cada 15 s como mucho). */
    fun refresh(m: Match): Match = memo("m_" + m.id, 15_000L) {
        try {
            scoreboard(m.league, m.start, m.start).firstOrNull { it.id == m.id } ?: m
        } catch (e: Exception) {
            m
        }
    }

    fun find(id: String): Match? = (today() + teamMatches()).firstOrNull { it.id == id }

    // ---- estadísticas y alineaciones ----

    private val STAT_ORDER = listOf(
        "possessionPct" to "Posesión",
        "totalShots" to "Tiros",
        "shotsOnTarget" to "Tiros a puerta",
        "wonCorners" to "Córners",
        "foulsCommitted" to "Faltas",
        "offsides" to "Fueras de juego",
        "saves" to "Paradas",
        "yellowCards" to "Amarillas",
        "redCards" to "Rojas"
    )

    fun detail(m: Match): Detail = memo("d_" + m.id, 30_000L) {
        try {
            val j = JSONObject(Xtream.http(BASE + m.league.code + "/summary?event=" + m.id))
            val byTeam = HashMap<String, Map<String, String>>()
            val teams = j.optJSONObject("boxscore")?.optJSONArray("teams")
            if (teams != null) {
                for (i in 0 until teams.length()) {
                    val t = teams.optJSONObject(i) ?: continue
                    val side = t.optString("homeAway").ifBlank { if (t.optJSONObject("team")?.optString("id") == m.home.id) "home" else "away" }
                    val map = HashMap<String, String>()
                    val st = t.optJSONArray("statistics")
                    if (st != null) {
                        for (k in 0 until st.length()) {
                            val o = st.optJSONObject(k) ?: continue
                            map[o.optString("name")] = o.optString("displayValue")
                        }
                    }
                    byTeam[side] = map
                }
            }
            val stats = ArrayList<Stat>()
            val hs = byTeam["home"]
            val aws = byTeam["away"]
            if (hs != null && aws != null) {
                for ((name, label) in STAT_ORDER) {
                    val a = hs[name] ?: continue
                    val b = aws[name] ?: continue
                    val pct = if (name == "possessionPct") "%" else ""
                    stats.add(Stat(label, a + pct, b + pct))
                }
            }
            var hl: Lineup? = null
            var al: Lineup? = null
            val rosters = j.optJSONArray("rosters")
            if (rosters != null) {
                for (i in 0 until rosters.length()) {
                    val r = rosters.optJSONObject(i) ?: continue
                    val list = r.optJSONArray("roster") ?: continue
                    val players = ArrayList<String>()
                    val starters = ArrayList<Player>()
                    for (k in 0 until list.length()) {
                        val p = list.optJSONObject(k) ?: continue
                        if (!p.optBoolean("starter", false)) continue
                        val ath = p.optJSONObject("athlete") ?: continue
                        val name = ath.optString("displayName")
                        val shortName = ath.optString("lastName").ifBlank { name.substringAfterLast(' ') }
                        val num = p.optString("jersey").ifBlank { "·" }
                        players.add("$num  $name")
                        starters.add(Player(num, shortName, p.optString("formationPlace").toIntOrNull() ?: (starters.size + 1)))
                    }
                    if (players.isEmpty()) continue
                    val lu = Lineup(r.optString("formation"), players, starters.sortedBy { it.place })
                    if (r.optString("homeAway") == "home") hl = lu else al = lu
                }
            }
            // forma: últimos 5 resultados de cada equipo (G/E/P)
            val forms = HashMap<String, List<String>>()
            val l5 = j.optJSONArray("lastFiveGames")
            if (l5 != null) {
                for (i in 0 until l5.length()) {
                    val t = l5.optJSONObject(i) ?: continue
                    val tid = t.optJSONObject("team")?.optString("id") ?: continue
                    val evs = t.optJSONArray("events") ?: continue
                    val out = ArrayList<String>()
                    for (k in 0 until evs.length()) {
                        when (evs.optJSONObject(k)?.optString("gameResult")) {
                            "W" -> out.add("G")
                            "D" -> out.add("E")
                            "L" -> out.add("P")
                        }
                    }
                    forms[tid] = out.take(5)
                }
            }
            Detail(stats, hl, al, forms[m.home.id] ?: emptyList(), forms[m.away.id] ?: emptyList())
        } catch (e: Exception) {
            Detail(emptyList(), null, null)
        }
    }

    // ---- clasificación ----

    fun standings(lg: League): List<Row> = memo("s_" + lg.code, 600_000L) {
        try {
            val j = JSONObject(Xtream.http("https://site.api.espn.com/apis/v2/sports/soccer/" + lg.code + "/standings"))
            val entries = j.optJSONArray("children")?.optJSONObject(0)?.optJSONObject("standings")?.optJSONArray("entries")
                ?: j.optJSONObject("standings")?.optJSONArray("entries")
            val out = ArrayList<Row>()
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val t = e.optJSONObject("team") ?: continue
                    val st = HashMap<String, JSONObject>()
                    val arr = e.optJSONArray("stats")
                    if (arr != null) for (k in 0 until arr.length()) arr.optJSONObject(k)?.let { st[it.optString("name")] = it }
                    fun n(name: String) = st[name]?.optDouble("value", 0.0)?.toInt() ?: 0
                    out.add(Row(
                        n("rank").takeIf { it > 0 } ?: (i + 1), t.optString("displayName"), t.optString("id"),
                        n("gamesPlayed"), n("wins"), n("ties"), n("losses"),
                        st["pointDifferential"]?.optString("displayValue") ?: "0", n("points"),
                        t.optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: ""
                    ))
                }
            }
            out.sortedBy { it.rank }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Últimos 5 resultados del Madrid (G/E/P), del más antiguo al más reciente */
    fun teamForm(): List<String> = teamMatches().filter { it.done }.takeLast(5).map { m ->
        val mine = if (m.home.id == TEAM_ID) m.home.score.toIntOrNull() ?: 0 else m.away.score.toIntOrNull() ?: 0
        val other = if (m.home.id == TEAM_ID) m.away.score.toIntOrNull() ?: 0 else m.home.score.toIntOrNull() ?: 0
        when {
            mine > other -> "G"
            mine < other -> "P"
            else -> "E"
        }
    }

    /** Puesto y puntos de un equipo en una competición, o null */
    fun position(lg: League, teamId: String): Row? = standings(lg).firstOrNull { it.teamId == teamId }

    /** Color de cada competición */
    fun leagueColor(lg: League): Int = android.graphics.Color.parseColor(
        when (lg.code) {
            "esp.1" -> "#F5B544"
            "uefa.champions" -> "#5B4FD0"
            else -> "#8B7CFF"
        }
    )

    // ---- en qué canal de tu lista se ve ----

    private val GENERIC = setOf("real", "club", "futbol", "football", "deportivo", "sporting", "united", "calcio", "olympique", "athletico")
    private val ALIAS = mapOf("internazionale" to "inter", "munich" to "bayern", "atletico" to "atletico", "celta vigo" to "celta")

    private fun tokens(name: String): Set<String> {
        val n = normText(name)
        val out = HashSet<String>()
        for (w in n.split(" ", "-", ".", "'")) {
            if (w.length >= 4 && w !in GENERIC) out.add(ALIAS[w] ?: w)
        }
        ALIAS[n]?.let { out.add(it) }
        if (out.isEmpty() && n.isNotBlank()) out.add(n)
        return out
    }

    /** ¿Este título de la guía es este partido? (aparece al menos un nombre de cada equipo) */
    fun titleMatches(m: Match, title: String): Boolean {
        val t = normText(title)
        val h = tokens(m.home.name) + tokens(m.home.short)
        val a = tokens(m.away.name) + tokens(m.away.short)
        return h.any { t.contains(it) } && a.any { t.contains(it) }
    }

    private val CH_WORDS = listOf("laliga", "liga de campeones", "campeones", "champions", "copa", "dazn", "m+", "movistar", "deport", "futbol", "gol", "la 1", "tve")
    private val chFound = HashMap<String, Int>()

    /** Busca en la guía de tus canales de deportes dónde dan el partido. Tarda la primera vez; luego se recuerda. */
    fun findChannel(prefs: Prefs, m: Match): Channel? {
        synchronized(chFound) { chFound[m.id]?.let { id -> return if (id < 0) null else Xtream.channels.firstOrNull { it.id == id } } }
        if (Xtream.channels.isEmpty()) Xtream.load(Ctx.app ?: return null, prefs, false)
        val chans = prefs.visibleChannels(Xtream.channels)
        val first = listOf("laliga", "campeones", "champions", "copa")
        val cand = chans.filter { c -> CH_WORDS.any { c.key.contains(it) } }
            .sortedBy { c -> if (first.any { c.key.contains(it) }) 0 else 1 }
            .take(40)
        val startSec = m.start / 1000
        var found: Channel? = null
        for (c in cand) {
            val list = Xtream.epg(prefs, c.id, 10)
            if (list.any { e -> e.end > startSec - 1800 && e.start < startSec + 3 * 3600 && titleMatches(m, e.title) }) {
                found = c
                break
            }
        }
        synchronized(chFound) { chFound[m.id] = found?.id ?: -1 }
        return found
    }

    /** ¿Lo que se ve ahora en este canal es un partido nuestro? */
    fun matchForTitle(title: String): Match? {
        if (title.isBlank()) return null
        val now = System.currentTimeMillis()
        return today().firstOrNull { m ->
            titleMatches(m, title) && (m.live || (m.state == "pre" && m.start - now < 45 * 60_000L) || (m.done && now - m.start < 4 * 3600_000L))
        }
    }
}

/** Contexto de la app para cargar la lista desde código sin pantalla (lo fija App al arrancar). */
object Ctx {
    @Volatile
    var app: android.content.Context? = null
}
