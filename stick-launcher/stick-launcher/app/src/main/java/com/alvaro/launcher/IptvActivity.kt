package com.alvaro.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Tarjeta de un canal en la parrilla. */
private class ChannelCard(ctx: Context, ui: Ui) : LinearLayout(ctx) {
    private val numView = TextView(ctx)
    private val nameView = TextView(ctx)
    private val starView = TextView(ctx)

    init {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.px(22), ui.px(18), ui.px(22), ui.px(18))
        background = GradientDrawable().apply {
            cornerRadius = ui.px(22).toFloat()
            setColor(Colors.SURFACE)
        }
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(150))

        numView.setTextColor(Colors.ACCENT)
        numView.setSizePx(ui, 22)
        numView.typeface = Typeface.DEFAULT_BOLD
        starView.setTextColor(Colors.ACCENT)
        starView.setSizePx(ui, 26)
        nameView.setTextColor(Colors.TEXT)
        nameView.setSizePx(ui, 28)
        nameView.typeface = Typeface.DEFAULT_BOLD
        nameView.maxLines = 2
        nameView.ellipsize = TextUtils.TruncateAt.END

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(numView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(starView)
        addView(top)
        val spacer = View(ctx)
        addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        addView(nameView)
    }

    fun bind(ch: Channel, fav: Boolean) {
        numView.text = ch.num.toString()
        nameView.text = ch.name
        starView.text = if (fav) "★" else ""
    }
}

/** Lista de canales: categorías a la izquierda, parrilla a la derecha y programa actual abajo. */
class IptvActivity : Activity() {

    companion object {
        const val FAV = "__fav"
        const val ALL = "__all"
    }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var grid: GridView
    private lateinit var adapter: ChAdapter
    private lateinit var catBox: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowTime: TextView
    private lateinit var nextTitle: TextView

    private var mainShown = false
    private var favs: Set<Int> = emptySet()
    private var current: List<Channel> = emptyList()
    private var catId = ALL
    private var longHandled = false
    private var epgToken = 0
    private val catRows = ArrayList<Pair<String, LinearLayout>>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
    }

    override fun onResume() {
        super.onResume()
        prefs = Prefs(this)
        favs = prefs.favorites
        if (!prefs.configured) {
            mainShown = false
            setContentView(promptView())
            return
        }
        if (!mainShown) {
            setContentView(mainView())
            mainShown = true
            loadData(false)
        } else if (Xtream.channels.isEmpty()) {
            loadData(false)
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    private fun promptView(): View {
        val msg = TextView(this).apply {
            text = "Aún no hay ninguna lista configurada"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 44)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = "Se configura desde el móvil, sin escribir nada con el mando."
            setTextColor(Colors.MUTED)
            setSizePx(ui, 28)
            gravity = Gravity.CENTER
            setPadding(0, ui.px(16), 0, ui.px(40))
        }
        val button = makeButton(this, ui, "Configurar desde el móvil", true) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Colors.BG)
            addView(msg)
            addView(sub)
            addView(button)
        }
        button.post { button.requestFocus() }
        return box
    }

    private fun mainView(): View {
        // Panel izquierdo
        val title = TextView(this).apply {
            text = "Lumen IPTV"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 34)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(ui.px(12), 0, 0, ui.px(28))
        }
        catBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val catScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(catBox)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Colors.SURFACE)
            setPadding(ui.px(32), ui.px(56), ui.px(32), ui.px(32))
            addView(title)
            addView(catScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // Cabecera derecha
        statusView = TextView(this).apply {
            text = ""
            setTextColor(Colors.MUTED)
            setSizePx(ui, 24)
        }
        val refresh = makeButton(this, ui, "Actualizar lista", false) { loadData(true) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(refresh)
        }

        // Parrilla
        adapter = ChAdapter()
        grid = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = ui.px(24)
            verticalSpacing = ui.px(24)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isVerticalScrollBarEnabled = false
            setSelector(GradientDrawable().apply {
                cornerRadius = ui.px(22).toFloat()
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(ui.px(4), Colors.TEXT)
            })
            setDrawSelectorOnTop(true)
            this.adapter = this@IptvActivity.adapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                if (longHandled) {
                    longHandled = false
                } else {
                    openChannel(pos)
                }
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    showEpg(current.getOrNull(position))
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        // Barra inferior con el programa
        nowTitle = TextView(this).apply {
            setTextColor(Colors.TEXT)
            setSizePx(ui, 30)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nowTime = TextView(this).apply {
            setTextColor(Colors.MUTED)
            setSizePx(ui, 20)
        }
        nextTitle = TextView(this).apply {
            setTextColor(Colors.MUTED)
            setSizePx(ui, 24)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = ui.px(22).toFloat()
                setColor(Colors.SURFACE)
            }
            setPadding(ui.px(32), ui.px(20), ui.px(32), ui.px(20))
            addView(nowTitle)
            addView(nowTime)
            addView(nextTitle)
        }
        val hint = TextView(this).apply {
            text = "OK: ver   ·   Mantener OK o Menú: favorito"
            setTextColor(Colors.MUTED)
            setSizePx(ui, 20)
            setPadding(ui.px(8), ui.px(14), 0, 0)
        }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(64), ui.px(48), ui.px(64), ui.px(32))
            addView(header)
            addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = ui.px(20)
                bottomMargin = ui.px(20)
            })
            addView(bottom)
            addView(hint)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Colors.BG)
            addView(left, LinearLayout.LayoutParams(ui.px(360), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun loadData(force: Boolean) {
        statusView.text = "Cargando canales…"
        thread {
            val ok = Xtream.load(applicationContext, prefs, force)
            runOnUiThread {
                if (ok) {
                    buildCategories()
                    selectCategory(if (favs.isNotEmpty()) FAV else ALL)
                    statusView.text = Xtream.channels.size.toString() + " canales"
                    catRows.firstOrNull { it.first == catId }?.second?.requestFocus()
                } else {
                    statusView.text = "No se pudieron cargar los canales. Revisa la configuración en Ajustes."
                }
            }
        }
    }

    private fun buildCategories() {
        catBox.removeAllViews()
        catRows.clear()
        val counts = HashMap<String, Int>()
        for (c in Xtream.channels) counts[c.cat] = (counts[c.cat] ?: 0) + 1
        addCatRow(FAV, "★ Favoritos", favs.size)
        addCatRow(ALL, "Todos", Xtream.channels.size)
        for (cat in Xtream.categories) {
            val n = counts[cat.id] ?: 0
            if (n > 0) addCatRow(cat.id, cat.name, n)
        }
    }

    private fun addCatRow(id: String, name: String, count: Int) {
        val label = TextView(this).apply {
            text = name
            setSizePx(ui, 26)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val n = TextView(this).apply {
            text = count.toString()
            setSizePx(ui, 22)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(20), ui.px(14), ui.px(20), ui.px(14))
            isFocusable = true
            isClickable = true
            tag = Pair(label, n)
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(n)
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) selectCategory(id) }
            setOnClickListener { grid.requestFocus() }
        }
        catBox.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ui.px(8)
        })
        catRows.add(Pair(id, row))
    }

    private fun styleCategories() {
        for ((id, row) in catRows) {
            val selected = id == catId
            row.background = GradientDrawable().apply {
                cornerRadius = ui.px(16).toFloat()
                setColor(if (selected) Colors.ACCENT else android.graphics.Color.TRANSPARENT)
            }
            @Suppress("UNCHECKED_CAST")
            val views = row.tag as Pair<TextView, TextView>
            views.first.setTextColor(if (selected) android.graphics.Color.parseColor("#1A1203") else Colors.TEXT)
            views.second.setTextColor(if (selected) android.graphics.Color.parseColor("#3A2A06") else Colors.MUTED)
        }
    }

    private fun selectCategory(id: String) {
        catId = id
        current = when (id) {
            FAV -> Xtream.channels.filter { favs.contains(it.id) }
            ALL -> Xtream.channels
            else -> Xtream.channels.filter { it.cat == id }
        }
        adapter.items = current
        adapter.notifyDataSetChanged()
        styleCategories()
        if (id == FAV && current.isEmpty()) {
            statusView.text = "Aún no tienes favoritos. Mantén OK pulsado sobre un canal."
        } else {
            statusView.text = current.size.toString() + " canales"
        }
        showEpg(current.firstOrNull())
    }

    private fun openChannel(pos: Int) {
        val ch = current.getOrNull(pos) ?: return
        Xtream.queue = current
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", ch.id))
    }

    private fun toggleFavorite() {
        val pos = grid.selectedItemPosition
        val ch = current.getOrNull(pos) ?: return
        val set = favs.toMutableSet()
        val added = set.add(ch.id)
        if (!added) set.remove(ch.id)
        favs = set
        prefs.favorites = set
        Toast.makeText(this, if (added) "Añadido a favoritos" else "Quitado de favoritos", Toast.LENGTH_SHORT).show()
        val keep = pos
        if (catId == FAV) {
            selectCategory(FAV)
            grid.setSelection(minOf(keep, maxOf(current.size - 1, 0)))
        } else {
            adapter.notifyDataSetChanged()
        }
        if (catRows.isNotEmpty()) {
            val favRow = catRows[0].second
            @Suppress("UNCHECKED_CAST")
            val views = favRow.tag as Pair<TextView, TextView>
            views.second.text = favs.size.toString()
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (mainShown && grid.hasFocus()) {
            val k = e.keyCode
            if (k == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                toggleFavorite()
                return true
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) longHandled = false
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 1) {
                    longHandled = true
                    toggleFavorite()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun showEpg(ch: Channel?) {
        val token = ++epgToken
        if (ch == null) {
            nowTitle.text = ""
            nowTime.text = ""
            nextTitle.text = ""
            return
        }
        nowTitle.text = ch.name
        nowTime.text = ""
        nextTitle.text = ""
        thread {
            Thread.sleep(250)
            if (token != epgToken) return@thread
            val list = Xtream.epg(prefs, ch.id)
            if (token != epgToken) return@thread
            val nowSec = System.currentTimeMillis() / 1000
            val now = list.firstOrNull { it.start <= nowSec && nowSec < it.end }
            val next = list.firstOrNull { it.start >= (now?.end ?: nowSec) }
            runOnUiThread {
                if (token == epgToken) {
                    if (now != null) {
                        nowTitle.text = ch.name + " · " + now.title
                        nowTime.text = "Ahora  " + fmt(now.start) + " – " + fmt(now.end)
                    }
                    if (next != null) nextTitle.text = "A continuación: " + next.title + "  (" + fmt(next.start) + ")"
                }
            }
        }
    }

    private fun fmt(sec: Long): String = timeFormat.format(Date(sec * 1000))

    private inner class ChAdapter : BaseAdapter() {
        var items: List<Channel> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ch = items[position]
            val card = (convertView as? ChannelCard) ?: ChannelCard(this@IptvActivity, ui)
            card.bind(ch, favs.contains(ch.id))
            return card
        }
    }
}
