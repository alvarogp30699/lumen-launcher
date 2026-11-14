package com.alvaro.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Semana deportiva: los próximos 7 días en columnas, con el Madrid, la F1, LaLiga, Champions y Copa.
 * OK abre la ficha; mantener OK pone o quita el aviso (campana).
 */
class SemanaActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var grid: LinearLayout
    private val es = Locale("es", "ES")

    private class Ev(val kind: String, val at: Long, val title: String, val sub: String, val match: Football.Match?, val session: F1Data.Session?, val event: F1Data.Event?)

    private val kinds = linkedMapOf(
        "rma" to Pair("#2C2A7A", "Real Madrid"),
        "f1" to Pair("#E10600", "F1"),
        "ucl" to Pair("#4C9AFF", "Champions"),
        "copa" to Pair("#8B7CFF", "Copa"),
        "liga" to Pair("#F5B544", "LaLiga")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(96), ui.px(56), ui.px(96), ui.px(40))
            setBackgroundColor(Color.parseColor("#07080C"))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(lbl(this, ui, "Semana deportiva", 48, Colors.TEXT, true))
        head.addView(lbl(this, ui, "en hora de España", 28, Colors.MUTED).apply { setPadding(ui.px(22), 0, 0, 0) })
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        for ((_, v) in kinds) {
            head.addView(View(this).apply { background = roundRect(ui, Color.parseColor(v.first), 5) }, LinearLayout.LayoutParams(ui.px(16), ui.px(16)).apply {
                leftMargin = ui.px(24)
                rightMargin = ui.px(10)
            })
            head.addView(lbl(this, ui, v.second, 24, Colors.MUTED))
        }
        root.addView(head)
        grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.px(30) })
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.px(16), 0, 0)
        }
        foot.addView(lbl(this, ui, "OK · ficha      Mantén OK · avisarme", 24, Color.parseColor("#8C95A8")))
        foot.addView(vIcon(this, R.drawable.ic_bell, Colors.ACCENT), LinearLayout.LayoutParams(ui.px(24), ui.px(24)).apply {
            leftMargin = ui.px(30)
            rightMargin = ui.px(10)
        })
        foot.addView(lbl(this, ui, "con aviso puesto", 24, Color.parseColor("#8C95A8")))
        root.addView(foot)
        setContentView(root)
        skeleton()
        load()
    }

    private fun skeleton() {
        grid.removeAllViews()
        for (i in 0 until 7) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (k in 0 until 2) col.addView(View(this).apply { background = roundRect(ui, Color.parseColor("#151924"), 18) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(180)).apply { bottomMargin = ui.px(14) })
            grid.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = ui.px(20) })
        }
    }

    private fun load() {
        thread {
            val prefs = Prefs(this)
            val evs = ArrayList<Ev>()
            for (m in Football.week()) {
                val kind = when {
                    m.hasTeam(Football.TEAM_ID) -> "rma"
                    m.league.code == "uefa.champions" -> "ucl"
                    m.league.code == "esp.copa_del_rey" -> "copa"
                    else -> "liga"
                }
                evs.add(Ev(kind, m.start, m.home.short + " – " + m.away.short, if (m.state == "pre") m.league.name else m.score() + " · " + m.status(), m, null, null))
            }
            val f1 = F1Data.next(prefs)
            if (f1 != null) for (s in f1.sessions) evs.add(Ev("f1", s.at, s.name, f1.name, null, s, f1))
            runOnUiThread { if (!isFinishing) show(evs) }
        }
    }

    private fun show(evs: List<Ev>) {
        grid.removeAllViews()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var first: View? = null
        val order = kinds.keys.toList()
        for (i in 0 until 7) {
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val end = cal.timeInMillis
            val day = evs.filter { it.at in start until end }
                .sortedWith(compareBy({ order.indexOf(it.kind) }, { it.at }))
                .take(4)
                .sortedBy { it.at }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val today = i == 0
            val headBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.px(4), 0, 0, ui.px(14))
            }
            headBox.addView(lbl(this, ui, if (today) "Hoy" else SimpleDateFormat("EEEE", es).format(Date(start)).replaceFirstChar { it.uppercase() }, 28,
                if (today) Colors.ACCENT else Colors.TEXT, true))
            headBox.addView(lbl(this, ui, SimpleDateFormat("d MMM", es).format(Date(start)), 24, Colors.MUTED))
            col.addView(headBox)
            col.addView(View(this).apply { setBackgroundColor(if (today) Colors.ACCENT else Color.parseColor("#1E2330")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(3)).apply { bottomMargin = ui.px(14) })
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (day.isEmpty()) list.addView(lbl(this, ui, "Sin partidos", 24, Color.parseColor("#8C95A8")).apply { setPadding(ui.px(4), ui.px(16), 0, 0) })
            for (e in day) {
                val c = evCard(e)
                list.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.px(14) })
                if (first == null || e.kind == "rma" && first.tag != "rma") {
                    first = c
                    c.tag = e.kind
                }
            }
            col.addView(ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                clipChildren = false
                addView(list)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            grid.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = ui.px(20) })
        }
        first?.requestFocus()
    }

    private fun reminderId(e: Ev): String = if (e.match != null) "m_" + e.match.id else "f1_" + e.at

    private fun evCard(e: Ev): View {
        val k = kinds[e.kind]!!
        val col = Color.parseColor(k.first)
        val bg = if (e.kind == "rma") Color.parseColor("#1E2050") else Colors.SURFACE
        val normal = roundRect(ui, bg, 18)
        val edge = ring(ui, Colors.TEXT, 18)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = normal
            setPadding(ui.px(16), ui.px(14), ui.px(16), ui.px(16))
            isFocusable = true
            isClickable = true
            isLongClickable = true
            setOnFocusChangeListener { v, has ->
                v.foreground = if (has) edge else null
                v.translationZ = if (has) ui.px(12).toFloat() else 0f
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(pill(this, ui, k.second, col))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val bell = vIcon(this, R.drawable.ic_bell, Colors.ACCENT)
        bell.visibility = if (Reminders.has(this, reminderId(e)) || (e.match != null && Reminders.has(this, "auto_" + e.match.id))) View.VISIBLE else View.INVISIBLE
        head.addView(bell, LinearLayout.LayoutParams(ui.px(24), ui.px(24)))
        card.addView(head)
        card.addView(lbl(this, ui, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.at)), 28, Colors.TEXT, true).apply { setPadding(0, ui.px(8), 0, 0) })
        card.addView(lbl(this, ui, e.title, 24, Colors.TEXT, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(lbl(this, ui, e.sub, 24, Colors.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.setOnClickListener {
            if (e.match != null) startActivity(Intent(this, FootballActivity::class.java).putExtra("match", e.match.id))
            else startActivity(Intent(this, F1Activity::class.java))
        }
        card.setOnLongClickListener {
            val id = reminderId(e)
            if (Reminders.has(this, id)) {
                Reminders.remove(this, id)
                bell.visibility = View.INVISIBLE
                Toast.makeText(this, "Aviso quitado", Toast.LENGTH_SHORT).show()
            } else if (e.at - Reminders.BEFORE > System.currentTimeMillis()) {
                Reminders.add(this, Reminders.Item(id, e.at, if (e.match != null) "match" else "f1", e.title, if (e.event != null) e.event.name else e.sub, e.match?.id ?: ""))
                bell.visibility = View.VISIBLE
                Toast.makeText(this, "Te avisaré 10 min antes", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Ya no da tiempo a avisarte", Toast.LENGTH_SHORT).show()
            }
            true
        }
        return card
    }
}
