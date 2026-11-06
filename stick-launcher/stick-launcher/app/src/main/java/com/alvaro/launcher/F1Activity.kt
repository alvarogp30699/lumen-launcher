package com.alvaro.launcher

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/** Calendario de la temporada de F1 (datos de tu PITWALL) con las horas de cada sesión en hora local. */
class F1Activity : Activity() {

    private class Race(val round: Int, val name: String, val place: String, val sessions: List<Pair<String, Long>>)

    private lateinit var ui: Ui
    private lateinit var listBox: LinearLayout
    private lateinit var detailTitle: TextView
    private lateinit var detailBody: TextView
    private val dayFmt = SimpleDateFormat("EEEE d MMM · HH:mm", Locale("es", "ES"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val prefs = Prefs(this)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(listBox)
        }
        detailTitle = TextView(this).apply {
            setTextColor(Colors.TEXT)
            setSizePx(ui, 40)
            typeface = Typeface.DEFAULT_BOLD
        }
        detailBody = TextView(this).apply {
            setTextColor(Colors.MUTED)
            setSizePx(ui, 28)
            setLineSpacing(ui.px(10).toFloat(), 1f)
            setPadding(0, ui.px(20), 0, 0)
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(48), 0, 0, 0)
            addView(detailTitle)
            addView(detailBody)
        }
        val head = TextView(this).apply {
            text = "Calendario de F1"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 44)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, ui.px(24))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scroll, LinearLayout.LayoutParams(ui.px(760), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.BG)
            setPadding(ui.px(100), ui.px(56), ui.px(100), ui.px(48))
            addView(head)
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        detailTitle.text = "Cargando…"

        thread {
            val races = try {
                val u = java.net.URI(prefs.pitwallUrl.trim())
                val tok = (u.query ?: "").split("&").firstOrNull { it.startsWith("token=") }
                val url = u.scheme + "://" + u.authority + "/api/f1/calendar" + (if (tok != null) "?$tok" else "")
                parse(JSONObject(Xtream.http(url)))
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread { if (!isFinishing) show(races) }
        }
    }

    private fun parse(j: JSONObject): List<Race> {
        val parseUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val arr = j.optJSONArray("races") ?: return emptyList()
        val out = ArrayList<Race>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ss = o.optJSONArray("sessions")
            val sessions = ArrayList<Pair<String, Long>>()
            if (ss != null) {
                for (k in 0 until ss.length()) {
                    val s = ss.getJSONObject(k)
                    val t = try {
                        parseUtc.parse(s.optString("at"))?.time
                    } catch (e: Exception) {
                        null
                    }
                    if (t != null) sessions.add(Pair(s.optString("name"), t))
                }
            }
            out.add(Race(o.optInt("round"), o.optString("name"), o.optString("locality") + ", " + o.optString("country"), sessions))
        }
        return out
    }

    private fun show(races: List<Race>) {
        if (races.isEmpty()) {
            detailTitle.text = "Sin datos"
            detailBody.text = "No se pudo cargar el calendario. Comprueba que PITWALL está encendido y que tiene internet."
            return
        }
        val now = System.currentTimeMillis()
        var firstUpcoming: View? = null
        val dateFmt = SimpleDateFormat("d MMM", Locale("es", "ES"))
        for (r in races) {
            val last = r.sessions.maxOfOrNull { it.second } ?: 0L
            val first = r.sessions.minOfOrNull { it.second } ?: 0L
            val past = last + 3 * 3600_000L < now
            val tv = TextView(this).apply {
                text = "R" + r.round + "  ·  " + r.name + "   (" + dateFmt.format(Date(first)) + ")"
                setTextColor(if (past) Colors.MUTED else Colors.TEXT)
                setSizePx(ui, 26)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                setPadding(ui.px(22), ui.px(16), ui.px(22), ui.px(16))
                isFocusable = true
                isClickable = true
                val normal = GradientDrawable().apply { cornerRadius = ui.px(16).toFloat(); setColor(Colors.SURFACE) }
                val focus = GradientDrawable().apply { cornerRadius = ui.px(16).toFloat(); setColor(Colors.SURFACE2); setStroke(ui.px(3), Colors.ACCENT) }
                background = normal
                setOnFocusChangeListener { v, has ->
                    v.background = if (has) focus else normal
                    if (has) detail(r, now)
                }
            }
            listBox.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ui.px(10)
            })
            if (firstUpcoming == null && !past) firstUpcoming = tv
        }
        (firstUpcoming ?: listBox.getChildAt(0))?.requestFocus()
    }

    private fun detail(r: Race, now: Long) {
        detailTitle.text = r.name
        val sb = StringBuilder(r.place).append("\n\n")
        for ((name, t) in r.sessions) {
            val mark = if (t + 2 * 3600_000L < now) "✓  " else if (t <= now) "●  " else "     "
            sb.append(mark).append(name).append("  ·  ").append(dayFmt.format(Date(t))).append("\n")
        }
        detailBody.text = sb.toString()
    }
}
