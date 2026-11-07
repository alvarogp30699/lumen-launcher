package com.alvaro.launcher

import android.widget.FrameLayout
import android.widget.ImageView
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
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
private class ChannelCard(ctx: Context, private val ui: Ui) : LinearLayout(ctx) {
    private val logoView = ImageView(ctx)
    private val initials = TextView(ctx)
    private val numView = TextView(ctx)
    private val nameView = TextView(ctx)
    private val starView = TextView(ctx)
    private val logoSize = ui.px(112)

    init {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.px(20), ui.px(18), ui.px(20), ui.px(16))
        background = roundRect(ui, Colors.SURFACE, 22)
        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(184))

        initials.setTextColor(Colors.MUTED)
        initials.setSizePx(ui, 28)
        initials.typeface = Fonts.bold
        initials.gravity = Gravity.CENTER
        logoView.scaleType = ImageView.ScaleType.FIT_CENTER
        val logoBox = FrameLayout(ctx).apply {
            background = roundRect(ui, Colors.SURFACE2, 14)
            setPadding(ui.px(8), ui.px(6), ui.px(8), ui.px(6))
            addView(initials, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(logoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        numView.setTextColor(Colors.ACCENT)
        numView.setSizePx(ui, 22)
        numView.typeface = Fonts.bold
        numView.gravity = Gravity.END
        starView.setTextColor(Colors.ACCENT)
        starView.setSizePx(ui, 26)
        starView.gravity = Gravity.END
        val side = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(numView)
            addView(starView)
        }

        nameView.setTextColor(Colors.TEXT)
        nameView.setSizePx(ui, 26)
        nameView.typeface = Fonts.bold
        nameView.maxLines = 2
        nameView.ellipsize = TextUtils.TruncateAt.END

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(logoBox, LinearLayout.LayoutParams(ui.px(132), ui.px(76)))
        top.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(side)
        addView(top)
        addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        addView(nameView)
    }

    fun bind(ch: Channel, fav: Boolean) {
        numView.text = ch.num.toString()
        nameView.text = ch.name
        starView.text = if (fav) "★" else ""
        initials.text = ch.name.split(" ", "-", "|", ":").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        Logos.load(ch.icon, logoView, logoSize) { ok -> initials.visibility = if (ok) View.INVISIBLE else View.VISIBLE }
    }
}

/** Lista de canales: categorías a la izquierda, parrilla a la derecha y programa actual abajo. */
class IptvActivity : Activity() {

    companion object {
        const val FAV = "__fav"
        const val ALL = "__all"
        const val SEARCH = "__search"
        private const val REQ_VOICE = 41

        /** Categorías bloqueadas que ya se desbloquearon con el PIN (se olvidan al salir de Lumen IPTV) */
        val unlocked = HashSet<String>()
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
    private var visible: List<Channel> = emptyList()
    private var catId = ALL
    private var longHandled = false
    @Volatile
    private var epgToken = 0
    private val epgHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val epgPool = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val catRows = ArrayList<Pair<String, LinearLayout>>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
    }

    override fun onDestroy() {
        epgHandler.removeCallbacksAndMessages(null)
        epgPool.shutdownNow()
        unlocked.clear()
        super.onDestroy()
    }

    private fun refreshVisible() {
        visible = prefs.visibleChannels(Xtream.channels, unlocked)
    }

    override fun onResume() {
        super.onResume()
        prefs = Prefs(this)
        favs = prefs.favorites
        refreshVisible()
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
            buildCategories()
            selectCategory(if (catId == SEARCH) ALL else catId)
        }
    }

    private fun promptView(): View {
        val msg = TextView(this).apply {
            text = "Aún no hay ninguna lista configurada"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 44)
            typeface = Fonts.bold
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
            typeface = Fonts.bold
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
        val voice = makeButton(this, ui, "🎤 Buscar", false) { startVoice() }
        val guide = makeButton(this, ui, "Guía", false) {
            Xtream.queue = current
            startActivity(Intent(this, GuideActivity::class.java))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(voice, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.px(16) })
            addView(guide, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.px(16) })
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
            typeface = Fonts.bold
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
            background = homeBackground(ui)
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
                    refreshVisible()
                    buildCategories()
                    selectCategory(if (favs.isNotEmpty()) FAV else ALL)
                    statusView.text = visible.size.toString() + " canales"
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
        val hiddenCh = prefs.hiddenChannels
        val hiddenCat = prefs.hiddenCats
        val locked = prefs.lockedCats
        val counts = HashMap<String, Int>()
        for (c in Xtream.channels) if (c.id !in hiddenCh) counts[c.cat] = (counts[c.cat] ?: 0) + 1
        addCatRow(FAV, "★   Favoritos", favs.size, false)
        addCatRow(ALL, "▤   Todos", visible.size, false)
        val order = prefs.catOrder
        val cats = Xtream.categories.filter { it.id !in hiddenCat && (counts[it.id] ?: 0) > 0 }
        val sorted = cats.sortedBy { val i = order.indexOf(it.id); if (i < 0) Int.MAX_VALUE else i }
        for (cat in sorted) {
            val isLocked = cat.id in locked && cat.id !in unlocked
            addCatRow(cat.id, (if (isLocked) "🔒" else catIcon(cat.name)) + "   " + cat.name, counts[cat.id] ?: 0, true)
        }
    }

    /** Icono según el nombre de la categoría (deportes, cine, noticias…). */
    private fun catIcon(name: String): String {
        val n = normText(name)
        return when {
            listOf("f1", "formula", "motor", "motogp", "racing").any { n.contains(it) } -> "🏁"
            listOf("futbol", "football", "soccer", "laliga", "liga", "champions").any { n.contains(it) } -> "⚽"
            listOf("deport", "sport", "dazn", "eurosport", "espn", "nba", "tenis", "golf").any { n.contains(it) } -> "🏆"
            listOf("cine", "movie", "film", "pelicul").any { n.contains(it) } -> "🎬"
            listOf("serie", "series").any { n.contains(it) } -> "📺"
            listOf("noticia", "news", "informativ").any { n.contains(it) } -> "📰"
            listOf("infantil", "kids", "niño", "nino", "cartoon", "dibujo").any { n.contains(it) } -> "🧸"
            listOf("music", "musica").any { n.contains(it) } -> "🎵"
            listOf("document", "discovery", "natura", "histor").any { n.contains(it) } -> "🌍"
            listOf("4k", "uhd").any { n.contains(it) } -> "✨"
            else -> "▸"
        }
    }

    private fun addCatRow(id: String, name: String, count: Int, manage: Boolean) {
        val label = TextView(this).apply {
            text = name
            setSizePx(ui, 26)
            typeface = Fonts.bold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val n = TextView(this).apply {
            text = count.toString()
            setSizePx(ui, 20)
            typeface = Fonts.bold
            setPadding(ui.px(12), ui.px(2), ui.px(12), ui.px(2))
            background = roundRect(ui, Colors.alpha(android.graphics.Color.WHITE, 0x14), 10)
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
            setOnClickListener {
                if (manage && id in prefs.lockedCats && id !in unlocked) {
                    askPinThen { unlocked.add(id); refreshVisible(); buildCategories(); selectCategory(id); catRows.firstOrNull { it.first == id }?.second?.requestFocus() }
                } else {
                    grid.requestFocus()
                }
            }
            if (manage) setOnLongClickListener {
                categoryMenu(id)
                true
            }
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
            views.first.setTextColor(if (selected) Colors.onColor() else Colors.TEXT)
            views.second.setTextColor(if (selected) Colors.onColor() else Colors.MUTED)
        }
    }

    private fun selectCategory(id: String) {
        catId = id
        val lockedNow = id != FAV && id != ALL && id in prefs.lockedCats && id !in unlocked
        current = when {
            lockedNow -> emptyList()
            id == FAV -> visible.filter { favs.contains(it.id) }
            id == ALL -> visible
            else -> visible.filter { it.cat == id }
        }
        adapter.items = current
        adapter.notifyDataSetChanged()
        styleCategories()
        if (lockedNow) {
            statusView.text = "🔒 Categoría bloqueada. Pulsa OK para desbloquear con el PIN"
        } else if (id == FAV && current.isEmpty()) {
            statusView.text = "Aún no tienes favoritos. Mantén OK pulsado sobre un canal."
        } else {
            statusView.text = current.size.toString() + " canales"
        }
        showEpg(current.firstOrNull())
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el nombre del canal")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Este stick no tiene reconocimiento de voz. Instala la app de Google o usa un mando con micrófono.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        search(text)
    }

    private fun search(q: String) {
        val words = normText(q).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty() || visible.isEmpty()) return
        val found = visible.filter { c -> words.all { c.key.contains(it) } }
        catId = SEARCH
        current = found
        adapter.items = found
        adapter.notifyDataSetChanged()
        styleCategories()
        statusView.text = if (found.isEmpty()) "Sin resultados para «$q»" else found.size.toString() + " resultados para «" + q + "»"
        grid.setSelection(0)
        if (found.isNotEmpty()) grid.requestFocus()
        showEpg(found.firstOrNull())
    }

    private fun openChannel(pos: Int) {
        val ch = current.getOrNull(pos) ?: return
        Xtream.queue = current
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", ch.id))
    }

    private fun focusCat(id: String) {
        catRows.firstOrNull { it.first == id }?.second?.requestFocus()
    }

    private fun askPinThen(action: () -> Unit) {
        if (prefs.pin.isBlank()) {
            Toast.makeText(this, "Primero define un PIN desde el móvil (Ajustes → Configurar IPTV)", Toast.LENGTH_LONG).show()
            return
        }
        PinDialog.ask(this, ui, prefs.pin, action)
    }

    private fun channelMenu() {
        val ch = current.getOrNull(grid.selectedItemPosition) ?: return
        val isFav = favs.contains(ch.id)
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(arrayOf(if (isFav) "Quitar de favoritos" else "★ Añadir a favoritos", "Ocultar este canal", "Cancelar")) { _, which ->
                when (which) {
                    0 -> toggleFavorite()
                    1 -> {
                        prefs.hiddenChannels = prefs.hiddenChannels + ch.id
                        refreshVisible()
                        buildCategories()
                        selectCategory(catId)
                        grid.requestFocus()
                        Toast.makeText(this, "Canal oculto. Para recuperarlo: Ajustes → Mostrar ocultos", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun categoryMenu(id: String) {
        val locked = id in prefs.lockedCats
        val name = Xtream.categories.firstOrNull { it.id == id }?.name ?: "Categoría"
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Subir al principio", "Ocultar categoría", if (locked) "Quitar bloqueo (pide PIN)" else "🔒 Bloquear con PIN", "Cancelar")) { _, which ->
                when (which) {
                    0 -> {
                        prefs.catOrder = listOf(id) + prefs.catOrder.filter { it != id }
                        buildCategories()
                        selectCategory(id)
                        focusCat(id)
                    }
                    1 -> {
                        prefs.hiddenCats = prefs.hiddenCats + id
                        refreshVisible()
                        buildCategories()
                        selectCategory(ALL)
                        focusCat(ALL)
                        Toast.makeText(this, "Categoría oculta. Para recuperarla: Ajustes → Mostrar ocultos", Toast.LENGTH_LONG).show()
                    }
                    2 -> {
                        if (locked) {
                            askPinThen {
                                prefs.lockedCats = prefs.lockedCats - id
                                unlocked.remove(id)
                                refreshVisible()
                                buildCategories()
                                selectCategory(id)
                                focusCat(id)
                            }
                        } else if (prefs.pin.isBlank()) {
                            Toast.makeText(this, "Primero define un PIN desde el móvil (Ajustes → Configurar IPTV)", Toast.LENGTH_LONG).show()
                        } else {
                            prefs.lockedCats = prefs.lockedCats + id
                            unlocked.remove(id)
                            refreshVisible()
                            buildCategories()
                            selectCategory(ALL)
                            focusCat(ALL)
                        }
                    }
                }
            }
            .show()
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
                    channelMenu()
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
        // al pasar rápido por la lista no se lanza nada: solo se pide la guía del canal donde te paras
        epgHandler.removeCallbacksAndMessages(null)
        epgHandler.postDelayed({ epgPool.execute { loadEpg(ch, token) } }, 250)
    }

    private fun loadEpg(ch: Channel, token: Int) {
        run {
            if (token != epgToken) return
            val list = Xtream.epg(prefs, ch.id)
            if (token != epgToken) return
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
