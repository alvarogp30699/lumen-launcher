package com.alvaro.launcher

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.media.AudioManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.concurrent.thread

/** Reproductor en directo. ▲/▼ cambian de canal, OK muestra la información, Atrás sale. */
class PlayerActivity : Activity() {

    companion object {
        /** Partido que se está viendo ahora (para no avisar del gol a quien ya lo está viendo) */
        @Volatile
        var watchingMatch: String? = null
    }

    private lateinit var root: FrameLayout
    private var foot: FootSync? = null
    private lateinit var choice: ChoicePanel
    private lateinit var bandQuality: TextView
    private lateinit var pauseCard: LinearLayout
    private lateinit var pauseText: TextView
    private var paused = false
    private var pausedAt = 0L
    private var behindMs = 0L
    private var readyOnce = false
    private val stalls = ArrayList<Long>()
    private var suggestedFor = -1
    private val listEpg = HashMap<Int, Pair<String, Float>>()
    private val listAsked = HashSet<Int>()
    private val listPool = java.util.concurrent.Executors.newSingleThreadExecutor()

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var playerView: PlayerView
    private lateinit var overlay: FrameLayout
    private lateinit var chip: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowTime: TextView
    private lateinit var progress: ProgressBar
    private lateinit var nextTitle: TextView

    // ── PITWALL (panel de F1/MotoGP sobre el vídeo) ──
    private var pwView: WebView? = null
    private lateinit var pwHint: TextView
    private var pwOpen = false
    private var pwAdjust = false
    private var pwDelay = 10
    private var pwSeries: String? = null
    private lateinit var pwAlert: TextView
    private var remote: RemoteServer? = null
    private var autoOpenedFor = ""
    private var summaryFor = ""
    private var prevIndex = -1
    private var playedOnce = false

    private var player: ExoPlayer? = null
    private var queue: List<Channel> = emptyList()
    private var index = 0
    private var epgToken = 0
    private var retries = 0
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)
        prefs = Prefs(this)

        playerView = PlayerView(this)
        playerView.useController = false
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)

        overlay = buildOverlay()

        PitwallOverlay.stop(this)
        root = FrameLayout(this)
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        listPanel = buildListPanel()
        root.addView(listPanel, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.32f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        })
        pwHint = TextView(this).apply {
            setTextColor(Color.parseColor("#1A1203"))
            setSizePx(ui, 26)
            typeface = Fonts.bold
            setPadding(ui.px(22), ui.px(10), ui.px(22), ui.px(10))
            background = GradientDrawable().apply {
                cornerRadius = ui.px(16).toFloat()
                setColor(Color.parseColor("#FFD400"))
            }
            visibility = View.GONE
        }
        root.addView(pwHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = ui.px(72)
            topMargin = ui.px(56)
        })
        reconn = TextView(this).apply {
            setTextColor(Colors.TEXT)
            setSizePx(ui, 30)
            typeface = Fonts.bold
            setPadding(ui.px(28), ui.px(14), ui.px(28), ui.px(14))
            background = GradientDrawable().apply {
                cornerRadius = ui.px(18).toFloat()
                setColor(Color.argb(217, 11, 13, 18))
            }
            visibility = View.GONE
        }
        root.addView(reconn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = ui.px(56)
        })
        pwAlert = TextView(this).apply {
            setTextColor(Color.parseColor("#1A1203"))
            setSizePx(ui, 34)
            typeface = Fonts.bold
            maxWidth = ui.px(760)
            setPadding(ui.px(30), ui.px(22), ui.px(30), ui.px(22))
            background = GradientDrawable().apply {
                cornerRadius = ui.px(22).toFloat()
                setColor(Color.parseColor("#FFD400"))
            }
            visibility = View.GONE
        }
        root.addView(pwAlert, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = ui.px(72)
            topMargin = ui.px(120)
        })
        choice = ChoicePanel(this, ui)
        root.addView(choice.view, FrameLayout.LayoutParams(ui.px(660), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = ui.px(64)
            topMargin = ui.px(64)
        })
        pauseText = lbl(this, ui, "", 36, Colors.ACCENT, true)
        pauseCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(ui, Color.argb(240, 11, 13, 19), 28)
            setPadding(ui.px(28), ui.px(22), ui.px(36), ui.px(22))
            visibility = View.GONE
        }
        val pIc = FrameLayout(this).apply { background = roundRect(ui, Colors.SURFACE2, 40) }
        pIc.addView(vIcon(this, R.drawable.ic_pause, Colors.TEXT), FrameLayout.LayoutParams(ui.px(40), ui.px(40), Gravity.CENTER))
        pauseCard.addView(pIc, LinearLayout.LayoutParams(ui.px(80), ui.px(80)).apply { rightMargin = ui.px(26) })
        pauseCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pauseText)
            addView(lbl(this@PlayerActivity, ui, "OK · seguir     ▶ · volver al directo     Se guardan hasta 2 min", 24, Colors.MUTED))
        })
        root.addView(pauseCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        setContentView(root)

        val wantedId = intent.getIntExtra("id", -1)
        thread {
            if (prefs.autoFree) freeOthers()
            if (Xtream.channels.isEmpty()) Xtream.load(applicationContext, prefs, false)
            runOnUiThread { start(wantedId) }
        }
    }

    /** Cierra en segundo plano las apps instaladas por ti (no las del sistema): más memoria libre para el vídeo. */
    @Suppress("DEPRECATION")
    private fun freeOthers() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            for (app in packageManager.getInstalledApplications(0)) {
                if (app.packageName == packageName) continue
                if ((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
                am.killBackgroundProcesses(app.packageName)
            }
        } catch (e: Exception) {
            // si Android no lo permite, se sigue sin más
        }
    }

    private lateinit var bandName: TextView
    private lateinit var bandLogo: android.widget.ImageView
    private lateinit var bandInitials: TextView

    /** Banda inferior del canal: logo, nombre, programa, progreso y lo siguiente. Arriba a la izquierda, la hora. */
    private fun buildOverlay(): FrameLayout {
        chip = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 34)
            typeface = Fonts.bold
            setPadding(ui.px(26), ui.px(12), ui.px(26), ui.px(12))
            background = roundRect(ui, Color.argb(200, 8, 9, 13), 18)
        }
        bandInitials = TextView(this).apply {
            setTextColor(Colors.MUTED)
            setSizePx(ui, 30)
            typeface = Fonts.bold
            gravity = Gravity.CENTER
        }
        bandLogo = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
        val logoBox = FrameLayout(this).apply {
            background = roundRect(ui, Color.argb(255, 21, 25, 34), 16)
            setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10))
            addView(bandInitials, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bandLogo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        bandName = TextView(this).apply {
            setTextColor(Colors.ACCENT)
            setSizePx(ui, 28)
            typeface = Fonts.bold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nowTitle = TextView(this).apply {
            setTextColor(Colors.TEXT)
            setSizePx(ui, 46)
            typeface = Fonts.bold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(28), 0, 0, 0)
            addView(LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(bandName)
                bandQuality = pill(this@PlayerActivity, ui, "", Colors.TEXT, Color.parseColor("#0B0D12"), 22).apply { visibility = View.GONE }
                addView(bandQuality, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(14) })
            })
            addView(nowTitle)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logoBox, LinearLayout.LayoutParams(ui.px(180), ui.px(100)))
            addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Colors.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#2B3345"))
        }
        nowTime = TextView(this).apply {
            setTextColor(Colors.MUTED)
            setSizePx(ui, 24)
        }
        nextTitle = TextView(this).apply {
            setTextColor(Colors.MUTED)
            setSizePx(ui, 24)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(nowTime)
            addView(nextTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.px(40) })
        }
        val hint = TextView(this).apply {
            text = "OK: lista de canales   ·   ▲▼: cambiar   ·   ◀: anterior   ·   ▶: PITWALL"
            setTextColor(Color.parseColor("#6B7385"))
            setSizePx(ui, 20)
            setPadding(0, ui.px(16), 0, 0)
        }
        val band = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(72), ui.px(110), ui.px(72), ui.px(44))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(245, 8, 9, 13), Color.argb(200, 8, 9, 13), Color.argb(0, 8, 9, 13))
            )
            addView(top)
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.px(8)).apply {
                topMargin = ui.px(22)
                bottomMargin = ui.px(12)
            })
            addView(bottom)
            addView(hint)
        }
        val box = FrameLayout(this)
        box.addView(chip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = ui.px(72)
            topMargin = ui.px(56)
        })
        box.addView(band, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })
        return box
    }

    // ───────────── Lista lateral de canales (zapping sin perder el vídeo) ─────────────

    private lateinit var listPanel: LinearLayout
    private lateinit var listTitle: TextView
    private lateinit var chList: android.widget.ListView
    private var listOpen = false
    private var listSel = 0
    private val listAdapter = ListAdapter()
    private val hideList = Runnable { closeList() }

    private class ListRow(val logo: android.widget.ImageView, val initials: TextView, val num: TextView, val name: TextView, val sub: TextView, val track: FrameLayout, val fill: View)

    private inner class ListAdapter : android.widget.BaseAdapter() {
        override fun getCount(): Int = queue.size
        override fun getItem(position: Int): Any = queue[position]
        override fun getItemId(position: Int): Long = queue[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeListRow()
            val h = row.tag as ListRow
            val ch = queue[position]
            val sel = position == listSel
            val playing = position == index
            row.background = if (sel) selBg else null
            h.num.text = ch.num.toString()
            h.num.setTextColor(if (sel) Colors.onColor() else Colors.ACCENT)
            h.name.text = (if (playing) "▶ " else "") + ch.name
            h.name.setTextColor(if (sel) Colors.onColor() else Colors.TEXT)
            h.initials.text = ch.name.split(" ", "-", "|", ":").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
            // programa en emisión y su progreso (se pide solo para las filas que se ven)
            val e = listEpg[ch.id]
            h.sub.setTextColor(if (sel) Colors.onColor() else Colors.MUTED)
            h.sub.text = e?.first ?: ""
            h.sub.visibility = if (e == null || e.first.isBlank()) View.GONE else View.VISIBLE
            h.track.visibility = if (e == null || e.first.isBlank()) View.GONE else View.VISIBLE
            h.track.background = roundRect(ui, if (sel) Color.argb(60, 26, 18, 3) else Color.parseColor("#232838"), 3)
            h.fill.background = roundRect(ui, if (sel) Colors.onColor() else Color.parseColor("#8C95A8"), 3)
            if (e != null) h.track.post {
                h.fill.layoutParams = FrameLayout.LayoutParams((h.track.width * e.second).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
            }
            if (e == null && listAsked.add(ch.id)) {
                val id = ch.id
                listPool.execute {
                    val nowS = System.currentTimeMillis() / 1000
                    val cur = Xtream.epg(prefs, id).firstOrNull { it.start <= nowS && nowS < it.end }
                    val v = if (cur == null) Pair("", 0f) else Pair(cur.title, ((nowS - cur.start).toFloat() / maxOf(1L, cur.end - cur.start)).coerceIn(0f, 1f))
                    runOnUiThread {
                        listEpg[id] = v
                        if (listOpen) notifyDataSetChanged()
                    }
                }
            }
            Logos.load(ch.icon, h.logo, ui.px(100)) { ok -> h.initials.visibility = if (ok) View.INVISIBLE else View.VISIBLE }
            return row
        }
    }

    private val selBg by lazy { roundRect(ui, Colors.ACCENT, 14) }

    private fun makeListRow(): LinearLayout {
        val initials = TextView(this).apply {
            setTextColor(Colors.MUTED)
            setSizePx(ui, 20)
            typeface = Fonts.bold
            gravity = Gravity.CENTER
        }
        val logo = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
        val logoBox = FrameLayout(this).apply {
            background = roundRect(ui, Color.argb(255, 21, 25, 34), 10)
            setPadding(ui.px(6), ui.px(4), ui.px(6), ui.px(4))
            addView(initials, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(logo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val num = TextView(this).apply {
            setSizePx(ui, 22)
            typeface = Fonts.bold
            gravity = Gravity.END
        }
        val name = TextView(this).apply {
            setSizePx(ui, 26)
            typeface = Fonts.bold
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(this).apply {
            setSizePx(ui, 24)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val track = FrameLayout(this)
        val fill = View(this)
        track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(sub)
            addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(6)).apply { topMargin = ui.px(6) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(14), 0, ui.px(18), 0)
            layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(112))
            addView(logoBox, LinearLayout.LayoutParams(ui.px(100), ui.px(60)))
            addView(num, LinearLayout.LayoutParams(ui.px(70), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.px(18) })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tag = ListRow(logo, initials, num, name, sub, track, fill)
        }
    }

    private fun buildListPanel(): LinearLayout {
        listTitle = TextView(this).apply {
            setTextColor(Colors.MUTED)
            setSizePx(ui, 24)
            typeface = Fonts.bold
            setPadding(ui.px(14), 0, 0, ui.px(16))
        }
        chList = android.widget.ListView(this).apply {
            adapter = listAdapter
            isFocusable = false
            isFocusableInTouchMode = false
            divider = null
            dividerHeight = ui.px(4)
            isVerticalScrollBarEnabled = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(40), ui.px(56), ui.px(28), ui.px(40))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(245, 8, 9, 13), Color.argb(225, 8, 9, 13))
            )
            visibility = View.GONE
            addView(listTitle)
            addView(chList, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun openList() {
        if (queue.isEmpty()) return
        listSel = index
        listOpen = true
        listTitle.text = "Canales  ·  " + queue.size
        listAdapter.notifyDataSetChanged()
        listPanel.visibility = View.VISIBLE
        // con la lista abierta no se ve la banda de abajo: la imagen queda más limpia
        overlay.visibility = View.GONE
        handler.removeCallbacks(hideOverlay)
        chList.setSelectionFromTop(listSel, ui.px(116) * 3)
        handler.removeCallbacks(hideList)
        handler.postDelayed(hideList, 8000)
    }

    private fun moveList(d: Int) {
        if (queue.isEmpty()) return
        listSel = (listSel + d + queue.size) % queue.size
        listAdapter.notifyDataSetChanged()
        chList.setSelectionFromTop(listSel, ui.px(116) * 3)
        handler.removeCallbacks(hideList)
        handler.postDelayed(hideList, 8000)
    }

    private fun closeList() {
        listOpen = false
        handler.removeCallbacks(hideList)
        listPanel.visibility = View.GONE
    }

    /** Teclas con la lista abierta: ▲▼ moverse, OK ver, ◀ o Atrás cerrar. */
    private fun listKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> moveList(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> moveList(1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> moveList(-8)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> moveList(8)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val target = listSel
                closeList()
                if (target != index) play(target) else showInfo(queue[index])
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_RIGHT -> closeList()
            else -> return false
        }
        return true
    }

    /** Canales por los que se puede hacer zapping: sin ocultos ni bloqueados (el pedido sí se incluye si no está bloqueado). */
    private fun playable(wantedId: Int): List<Channel> {
        val visible = prefs.visibleChannels(Xtream.channels)
        val wanted = Xtream.channels.firstOrNull { it.id == wantedId }
        return if (wanted != null && visible.none { it.id == wantedId } && wanted.cat !in prefs.lockedCats) listOf(wanted) + visible else visible
    }

    private fun start(wantedId: Int) {
        val all = playable(wantedId)
        var q = Xtream.queue
        if (q.none { it.id == wantedId }) q = all
        if (q.isEmpty()) {
            Toast.makeText(this, "No hay canales. Configura la lista en Lumen IPTV.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        queue = q
        index = q.indexOfFirst { it.id == wantedId }.coerceAtLeast(0)
        createPlayer()
        play(index)
        if (remote == null) {
            remote = RemoteServer(prefs.remoteToken, { cmd -> runOnUiThread { remoteCmd(cmd) } }, { q -> remoteSearch(q) }, { remoteState() })
            remote?.start()
        }
        setSleep(prefs.sleepMinutes, false)
    }

    private lateinit var reconn: TextView
    private val stallCheck = Runnable {
        // más de 20 s cargando sin imagen: se vuelve a pedir el canal
        if (player?.playbackState == Player.STATE_BUFFERING) reconnect("sin datos")
    }

    private fun createPlayer() {
        // Búfer corto para directos: cambia de canal rápido y no gasta memoria de más
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8000, 130000, 1500, 3000)
            .setTargetBufferBytes(100 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, rf).setLoadControl(lc).build()
        var params = p.trackSelectionParameters.buildUpon()
        if (prefs.quality == "1080p") params = params.setMaxVideoSize(1920, 1080)
        if (prefs.quality == "4k") params = params.setForceHighestSupportedBitrate(true)
        p.trackSelectionParameters = params.build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                handler.removeCallbacks(stallCheck)
                if (playbackState == Player.STATE_READY) {
                    retries = 0
                    reconn.visibility = View.GONE
                    readyOnce = true
                    updateQuality()
                } else if (playbackState == Player.STATE_BUFFERING) {
                    handler.postDelayed(stallCheck, 20_000)
                    if (readyOnce && !paused) onStall()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                ErrorLog.add(this@PlayerActivity, "Reproductor · " + (queue.getOrNull(index)?.name ?: "?") + " · " + error.errorCodeName + " · " + (error.message ?: ""))
                reconnect(error.errorCodeName)
            }
        })
        playerView.player = p
        player = p
    }

    /** Reintenta solo, esperando cada vez un poco más (hasta 8 veces) y avisando en pantalla. */
    private fun reconnect(reason: String) {
        foot?.onReconnect()
        behindMs = 0L
        retries++
        if (retries > 8) {
            reconn.visibility = View.GONE
            Toast.makeText(
                this,
                "No se puede reproducir este canal ($reason). Prueba otro con ▲▼ o cambia el formato en Ajustes.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        reconn.text = "⟳  Reconectando…  ($retries/8)"
        reconn.visibility = View.VISIBLE
        handler.postDelayed({
            val ch = queue.getOrNull(index) ?: return@postDelayed
            val p = player ?: return@postDelayed
            p.setMediaItem(MediaItem.fromUri(Xtream.streamUrl(prefs, ch.id)))
            p.prepare()
            p.playWhenReady = true
        }, minOf(1500L * retries, 8000L))
    }

    private fun play(i: Int) {
        val p = player ?: return
        if (playedOnce && i != index) prevIndex = index
        playedOnce = true
        index = i
        val ch = queue[i]
        retries = 0
        reconn.visibility = View.GONE
        stopFoot()
        readyOnce = false
        stalls.clear()
        paused = false
        behindMs = 0L
        pauseCard.visibility = View.GONE
        applyTrackPrefs(ch.id)
        p.setMediaItem(MediaItem.fromUri(Xtream.streamUrl(prefs, ch.id)))
        p.prepare()
        p.playWhenReady = true
        prefs.lastChannelId = ch.id
        prefs.lastChannelName = ch.name
        pwDelay = prefs.pitwallDelay(ch.id)
        hidePanel()
        detectSeries(ch.name, ch.cat)
        showInfo(ch)
    }

    private var sleepRun: Runnable? = null
    private var sleepAt = 0L

    /** Programa (o cancela con 0) el apagado automático de la reproducción. */
    private fun setSleep(min: Int, announce: Boolean) {
        sleepRun?.let { handler.removeCallbacks(it) }
        sleepRun = null
        sleepAt = 0L
        if (min > 0) {
            val r = Runnable {
                Toast.makeText(this, "Temporizador: reproducción detenida", Toast.LENGTH_LONG).show()
                finish()
            }
            sleepRun = r
            sleepAt = System.currentTimeMillis() + min * 60_000L
            handler.postDelayed(r, min * 60_000L)
        }
        if (announce) {
            Toast.makeText(this, if (min > 0) "Se apagará en $min min" else "Temporizador desactivado", Toast.LENGTH_SHORT).show()
        }
    }

    /** Cada pulsación pasa a 30, 60, 90 min y vuelve a desactivado. */
    private fun cycleSleep() {
        val opts = listOf(0, 30, 60, 90)
        val left = if (sleepAt > 0) ((sleepAt - System.currentTimeMillis()) / 60_000L).toInt() + 1 else 0
        val cur = when {
            left <= 0 -> 0
            left <= 30 -> 30
            left <= 60 -> 60
            else -> 90
        }
        setSleep(opts[(opts.indexOf(cur) + 1) % opts.size], true)
    }

    private fun previousChannel() {
        if (prevIndex in queue.indices && prevIndex != index) {
            play(prevIndex)
        } else {
            Toast.makeText(this, "Todavía no has cambiado de canal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun zap(delta: Int) {
        if (queue.isEmpty()) return
        play((index + delta + queue.size) % queue.size)
    }

    private fun showInfo(ch: Channel) {
        bandName.text = ch.num.toString() + "   ·   " + ch.name
        bandInitials.text = ch.name.split(" ", "-", "|", ":").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        Logos.load(ch.icon, bandLogo, ui.px(180)) { ok -> bandInitials.visibility = if (ok) View.INVISIBLE else View.VISIBLE }
        nowTitle.text = ch.name
        nowTime.text = ""
        nextTitle.text = ""
        progress.progress = 0
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 6000)

        val token = ++epgToken
        thread {
            val list = Xtream.epg(prefs, ch.id)
            if (token != epgToken) return@thread
            val nowSec = System.currentTimeMillis() / 1000
            val now = list.firstOrNull { it.start <= nowSec && nowSec < it.end }
            val next = list.firstOrNull { it.start >= (now?.end ?: nowSec) }
            runOnUiThread {
                if (token == epgToken) {
                    if (now != null) {
                        nowTitle.text = now.title
                        detectSeries(ch.name, ch.cat, now.title)
                        detectFootball(ch, now.title)
                        nowTime.text = hhmm(now.start) + " – " + hhmm(now.end) + "   ·   quedan " + ((now.end - nowSec) / 60) + " min"
                        val span = (now.end - now.start).coerceAtLeast(1)
                        progress.progress = (((nowSec - now.start) * 100) / span).toInt().coerceIn(0, 100)
                    }
                    if (next != null) nextTitle.text = "A continuación: " + next.title + "  (" + hhmm(next.start) + ")"
                }
            }
        }
    }

    private fun hhmm(sec: Long): String {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return f.format(java.util.Date(sec * 1000))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val isVolume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (choice.isOpen && choice.key(keyCode)) return true
        foot?.let { f -> if (f.isOpen && !isVolume) return f.key(keyCode) }
        if (paused && !isVolume) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> goLive()
                KeyEvent.KEYCODE_BACK -> return super.onKeyDown(keyCode, event)
                else -> togglePause()
            }
            return true
        }
        if (pwOpen && !isVolume) return panelKey(keyCode)
        if (listOpen && !isVolume && listKey(keyCode)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> {
                cycleSleep()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                previousChannel()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_PROG_GREEN -> {
                showAudioMenu()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                togglePause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_INFO -> {
                val f = foot
                if (f != null) {
                    overlay.visibility = View.GONE
                    f.toggle()
                } else if (prefs.pitwallUrl.isNotBlank()) {
                    openPanel()
                } else {
                    Toast.makeText(this, "Configura la dirección de PITWALL desde el móvil (Ajustes → QR).", Toast.LENGTH_LONG).show()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                zap(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                zap(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (queue.isNotEmpty()) {
                    showInfo(queue[index])
                    openList()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ───────────────────────── Fútbol sincronizado ─────────────────────────

    private fun detectFootball(ch: Channel, title: String) {
        if (foot != null && foot?.match?.let { Football.titleMatches(it, title) } == true) return
        thread {
            val m = Football.matchForTitle(title) ?: return@thread
            runOnUiThread {
                if (isFinishing || queue.getOrNull(index)?.id != ch.id || foot != null) return@runOnUiThread
                val f = FootSync(this, ui, prefs, { playerView.videoSurfaceView }, m, ch.id)
                root.addView(f.panel, FrameLayout.LayoutParams(ui.px(660), ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.END })
                foot = f
                watchingMatch = m.id
                destroyPanel()
            }
        }
    }

    private fun stopFoot() {
        foot?.let {
            it.stop()
            root.removeView(it.panel)
        }
        foot = null
        watchingMatch = null
    }

    // ───────────────────────── Audio, subtítulos y calidad ─────────────────────────

    private fun langName(code: String?, label: String?, i: Int): String {
        val c = (code ?: "").lowercase()
        val base = when {
            c.startsWith("es") || c.startsWith("spa") -> "Castellano"
            c.startsWith("en") || c.startsWith("eng") -> "English"
            c.startsWith("ca") || c.startsWith("cat") -> "Català"
            c.startsWith("fr") || c.startsWith("fra") || c.startsWith("fre") -> "Français"
            c.startsWith("pt") || c.startsWith("por") -> "Português"
            c.isBlank() || c == "und" -> "Pista " + (i + 1)
            else -> c.uppercase()
        }
        return if (!label.isNullOrBlank() && !label.equals(base, true)) "$base · $label" else base
    }

    private fun applyTrackPrefs(chId: Int) {
        val p = player ?: return
        val a = prefs.audioFor(chId)
        val t = prefs.subsFor(chId)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguage(a.ifBlank { null })
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, t.isBlank())
            .setPreferredTextLanguage(t.ifBlank { null })
            .build()
    }

    private fun showAudioMenu() {
        val p = player ?: return
        val ch = queue.getOrNull(index) ?: return
        val items = ArrayList<ChoicePanel.Item>()
        var ai = 0
        var ti = 0
        val textOn = !p.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)
        for (g in p.currentTracks.groups) {
            for (k in 0 until g.length) {
                val f = g.getTrackFormat(k)
                if (g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                    val name = langName(f.language, f.label, ai++)
                    val sub = (if (f.channelCount >= 6) "5.1" else if (f.channelCount == 2) "estéreo" else if (f.channelCount == 1) "mono" else "") +
                        (if (f.sampleMimeType?.contains("ac3") == true) " · Dolby" else "")
                    items.add(ChoicePanel.Item(name, sub.trim(' ', '·'), "Audio", g.isTrackSelected(k)) {
                        prefs.setAudioFor(ch.id, f.language ?: "")
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, k)).build()
                    })
                } else if (g.type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                    val name = langName(f.language, f.label, ti++)
                    items.add(ChoicePanel.Item(name, "", "Subtítulos", textOn && g.isTrackSelected(k)) {
                        prefs.setSubsFor(ch.id, f.language ?: "und")
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, k)).build()
                    })
                }
            }
        }
        if (ti > 0) {
            items.add(ChoicePanel.Item("Desactivados", "", "Subtítulos", !textOn) {
                prefs.setSubsFor(ch.id, "")
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
            })
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "Este canal solo tiene una pista de audio", Toast.LENGTH_SHORT).show()
            return
        }
        overlay.visibility = View.GONE
        choice.show("Audio y subtítulos", null, items, "Se recuerda para este canal   ·   Atrás cerrar")
    }

    private fun updateQuality() {
        val f = player?.videoFormat
        if (f == null || f.height <= 0) {
            bandQuality.visibility = View.GONE
            return
        }
        bandQuality.text = when {
            f.height >= 2000 -> "4K"
            f.height >= 1000 -> "FHD"
            f.height >= 700 -> "HD"
            else -> "SD"
        }
        bandQuality.visibility = View.VISIBLE
    }

    /** Se queda cargando a menudo: se ofrecen otras versiones del mismo canal (FHD, HD, SD, otro servidor). */
    private fun onStall() {
        val now = System.currentTimeMillis()
        stalls.add(now)
        stalls.removeAll { now - it > 90_000L }
        val ch = queue.getOrNull(index) ?: return
        if (stalls.size < 3 || suggestedFor == ch.id) return
        suggestedFor = ch.id
        val base = baseName(ch.name)
        val alts = prefs.visibleChannels(Xtream.channels).filter { it.id != ch.id && baseName(it.name) == base }.take(4)
        val kbps = try {
            (player?.let { _ -> bandwidth.bitrateEstimate } ?: 0L) / 1000
        } catch (e: Exception) {
            0L
        }
        val need = (player?.videoFormat?.bitrate ?: -1).let { if (it > 0) it / 1000 else -1 }
        val sub = "Tu conexión va a " + String.format(java.util.Locale("es", "ES"), "%.1f", kbps / 1000.0) + " Mbps" +
            (if (need > 0) " y el canal necesita " + String.format(java.util.Locale("es", "ES"), "%.1f", need / 1000.0) else "") +
            (if (alts.isNotEmpty()) ". Hay otras versiones de este canal en tu lista:" else ". No hay otras versiones de este canal en tu lista.")
        val items = alts.map { c ->
            val q = qualityTag(c.name)
            ChoicePanel.Item(c.name, q ?: "otra versión", null, false) {
                val i = queue.indexOfFirst { it.id == c.id }
                if (i >= 0) play(i) else {
                    queue = playable(c.id)
                    play(queue.indexOfFirst { it.id == c.id }.coerceAtLeast(0))
                }
            }
        }
        overlay.visibility = View.GONE
        choice.show("Va a tirones", sub, items.ifEmpty { listOf(ChoicePanel.Item("Seguir con este canal", "", null, true) { }) }, "Atrás · seguir con este")
    }

    private val bandwidth by lazy { androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this) }

    private val qualityWords = listOf("fhd", "uhd", "4k", "hd", "sd", "1080p", "1080", "720p", "720", "hevc", "h265", "backup", "multi", "vip", "(2)", "(3)", "2", "3")

    private fun baseName(n: String): String =
        normText(n).replace(Regex("[|\\[\\]()_.:-]"), " ").split(" ").filter { it.isNotBlank() && it !in qualityWords }.joinToString(" ")

    private fun qualityTag(n: String): String? {
        val t = normText(n)
        return when {
            t.contains("4k") || t.contains("uhd") -> "4K"
            t.contains("fhd") || t.contains("1080") -> "FHD"
            Regex("\\bhd\\b").containsMatchIn(t) || t.contains("720") -> "HD"
            Regex("\\bsd\\b").containsMatchIn(t) -> "SD"
            else -> null
        }
    }

    // ───────────────────────── Pausa del directo ─────────────────────────

    private val pauseTick = object : Runnable {
        override fun run() {
            if (!paused) return
            val behind = behindMs + (System.currentTimeMillis() - pausedAt)
            pauseText.text = "−" + mmss(behind) + " del directo" + (if (behind > 120_000L) " · al seguir vuelves al directo" else "")
            handler.postDelayed(this, 1000)
        }
    }

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
    }

    private fun togglePause() {
        val p = player ?: return
        if (!paused) {
            paused = true
            pausedAt = System.currentTimeMillis()
            p.playWhenReady = false
            overlay.visibility = View.GONE
            pauseCard.visibility = View.VISIBLE
            pauseCard.bringToFront()
            handler.post(pauseTick)
        } else {
            val behind = behindMs + (System.currentTimeMillis() - pausedAt)
            paused = false
            pauseCard.visibility = View.GONE
            if (behind > 120_000L) {
                goLive()
            } else {
                behindMs = behind
                p.playWhenReady = true
                if (behindMs > 0) Toast.makeText(this, "Vas " + mmss(behindMs) + " por detrás del directo · ▶▶ o pausa+▶ para volver", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goLive() {
        paused = false
        behindMs = 0L
        pauseCard.visibility = View.GONE
        val ch = queue.getOrNull(index) ?: return
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(Xtream.streamUrl(prefs, ch.id)))
        p.prepare()
        p.playWhenReady = true
        foot?.onReconnect()
        Toast.makeText(this, "En directo", Toast.LENGTH_SHORT).show()
    }

    // ───────────────────────── PITWALL ─────────────────────────

    private val f1Words = listOf("formula 1", "fórmula 1", "formula1", "fórmula1", "f1 ", " f1", "grand prix", "gran premio de f", "gran premio de f1")
    private val sessionWords = listOf("carrera", "clasificación", "clasificacion", "libres", "sprint", "qualifying", "race", "practice")

    /** Detecta si se está viendo F1 por el nombre del canal, la categoría o el título del programa (MotoGP se ignora). */
    private fun detectSeries(name: String, cat: String, epgTitle: String = "") {
        val t = " " + (name + " " + cat + " " + epgTitle).lowercase() + " "
        val moto = t.contains("motogp") || t.contains("moto gp") || t.contains("moto2") || t.contains("moto3")
        val isF1 = !moto && (f1Words.any { t.contains(it) } || name.lowercase().contains("f1"))
        val live = epgTitle.isEmpty() || sessionWords.any { t.contains(it) } || isF1
        pwSeries = if (isF1 && live) "f1" else null
        updateHint()
        syncPanel()
    }

    private fun updateHint() {
        if (prefs.pitwallUrl.isBlank() || pwSeries == null || pwOpen) {
            pwHint.visibility = View.GONE
            return
        }
        pwHint.text = "▶ PITWALL  (pulsa →)"
        pwHint.visibility = View.VISIBLE
    }

    private fun panelUrl(): String {
        val base = prefs.pitwallUrl.trim()
        val sep = if (base.contains("?")) "&" else "?"
        return base + sep + "panel=1&mode=live&series=f1&delay=" + pwDelay + "&zoom=1.25&hidden=1&fine=1&favs=" + java.net.URLEncoder.encode(prefs.pitwallFavs, "UTF-8")
    }

    /** Puente JavaScript: PITWALL avisa aquí (ya con el retraso aplicado) de banderas, safety car, etc. */
    private inner class Bridge {
        /** PITWALL avisa del estado de la sesión: "on" al empezar, "off" al terminar (con el retraso aplicado) */
        @JavascriptInterface
        fun session(state: String, title: String) {
            runOnUiThread { onSession(state, title) }
        }

        @JavascriptInterface
        fun alert(type: String, text: String) {
            runOnUiThread { showAlert(text) }
        }
    }

    private fun onSession(state: String, title: String) {
        if (!prefs.pitwallAuto) return
        if (state == "on" && autoOpenedFor != title) {
            autoOpenedFor = title
            openPanel()
        } else if (state == "off") {
            val race = title.contains("carrera", true) || title.contains("race", true) || title.contains("sprint", true)
            if (race && summaryFor != title) {
                // al terminar la carrera se abre solo el resumen (podio, vueltas rápidas…)
                summaryFor = title
                openPanel()
                js("pwPanel.show('sum')")
            } else if (pwOpen && autoOpenedFor == title) {
                hidePanel()
            }
        }
    }

    private fun showAlert(text: String) {
        if (!prefs.pitwallAlerts || pwOpen || text.isBlank()) return
        pwAlert.text = text
        pwAlert.visibility = View.VISIBLE
        pwAlert.bringToFront()
        handler.removeCallbacks(hideAlert)
        handler.postDelayed(hideAlert, 7000)
    }

    private val hideAlert = Runnable { pwAlert.visibility = View.GONE }

    /** Mantiene el panel cargado (invisible) mientras se ve F1, para recibir avisos aunque esté cerrado. */
    private fun syncPanel() {
        if (prefs.pitwallUrl.isBlank() || pwSeries == null) {
            destroyPanel()
            return
        }
        if (pwView == null) createPanel() else js("pwPanel.setDelay($pwDelay)")
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun createPanel() {
        val root = playerView.parent as FrameLayout
        val w = WebView(this)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        w.alpha = 0f
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        w.addJavascriptInterface(Bridge(), "PWBridge")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                // si el panel se abrió antes de terminar de cargar, se activa el dibujado ahora
                if (pwOpen) view.evaluateJavascript("pwPanel.hidden(false)", null)
            }

            override fun onReceivedError(v: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (pwOpen) {
                    Toast.makeText(this@PlayerActivity, "No se alcanza PITWALL. ¿Está encendido el PC y es correcta la dirección?", Toast.LENGTH_LONG).show()
                }
            }
        }
        val width = (resources.displayMetrics.widthPixels * 0.35f).toInt()
        root.addView(w, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.END })
        pwAlert.bringToFront()
        pwHint.bringToFront()
        w.loadUrl(panelUrl())
        pwView = w
    }

    private fun openPanel() {
        if (pwView == null) createPanel()
        pwView?.alpha = 1f
        js("pwPanel.hidden(false)")
        pwOpen = true
        pwAdjust = false
        pwHint.visibility = View.GONE
        pwAlert.visibility = View.GONE
        overlay.visibility = View.GONE
    }

    /** Oculta el panel (sigue cargado en segundo plano para los avisos). */
    private fun hidePanel() {
        if (pwOpen || pwAdjust) js("pwPanel.adjust(false)")
        pwOpen = false
        pwAdjust = false
        pwView?.alpha = 0f
        js("pwPanel.hidden(true)")
        updateHint()
    }

    private fun destroyPanel() {
        val w = pwView
        if (w != null) {
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        pwView = null
        pwOpen = false
        pwAdjust = false
        updateHint()
    }

    private fun js(code: String, cb: ((String) -> Unit)? = null) {
        pwView?.evaluateJavascript(code) { r -> cb?.invoke(r ?: "") }
    }

    /**
     * Teclas con el panel abierto: ◀▶ vista, ▲▼ desplazar, OK ajusta el retraso, Atrás cierra.
     * En el ajuste: ◀▶ ±1 s, ▲▼ ±5 s, MENU sincroniza con el último evento (púlsalo justo cuando lo veas en la tele).
     */
    private fun panelKey(keyCode: Int): Boolean {
        val chId = queue.getOrNull(index)?.id ?: -1
        if (pwAdjust) {
            val d = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_UP -> 5
                KeyEvent.KEYCODE_DPAD_DOWN -> -5
                else -> 0
            }
            if (d != 0) {
                js("pwPanel.delay($d)") { r ->
                    r.trim().toDoubleOrNull()?.let {
                        pwDelay = it.toInt()
                        prefs.setPitwallDelay(chId, pwDelay)
                    }
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_PROG_BLUE) {
                js("pwPanel.sync()") { r ->
                    val v = r.trim().toDoubleOrNull() ?: -1.0
                    if (v < 0) {
                        Toast.makeText(this, "No hay ningún evento reciente (bandera, safety car…). Prueba cuando ocurra uno.", Toast.LENGTH_LONG).show()
                    } else {
                        pwDelay = v.toInt()
                        prefs.setPitwallDelay(chId, pwDelay)
                        Toast.makeText(this, "Retraso sincronizado: " + pwDelay + " s", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BACK) {
                pwAdjust = false
                js("pwPanel.adjust(false)")
                return true
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> js("pwPanel.view(1)")
            KeyEvent.KEYCODE_DPAD_LEFT -> js("pwPanel.view(-1)")
            KeyEvent.KEYCODE_DPAD_UP -> js("pwPanel.scroll(-260)")
            KeyEvent.KEYCODE_DPAD_DOWN -> js("pwPanel.scroll(260)")
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                pwAdjust = true
                js("pwPanel.adjust(true)")
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_INFO -> hidePanel()
            else -> return true
        }
        return true
    }

    // ───────────────────────── mando del móvil ─────────────────────────

    private fun remoteState(): String {
        val name = queue.getOrNull(index)?.name ?: "—"
        val pw = foot?.let { "  ·  " + it.match.home.short + " – " + it.match.away.short + " · " + it.status() }
            ?: if (pwView == null) "" else "  ·  PITWALL " + (if (pwOpen) "abierto" else "oculto") + " · retraso " + pwDelay + " s"
        val sl = if (sleepAt > 0) "  ·  dormir en " + (((sleepAt - System.currentTimeMillis()) / 60_000L).toInt() + 1) + " min" else ""
        return "▶ " + name + pw + sl
    }

    private fun remoteSearch(q: String): String {
        val words = normText(q).split(" ").filter { it.isNotBlank() }
        val arr = org.json.JSONArray()
        if (words.isEmpty()) return arr.toString()
        var n = 0
        for (c in prefs.visibleChannels(Xtream.channels)) {
            if (words.all { c.key.contains(it) }) {
                arr.put(org.json.JSONObject().put("id", c.id).put("name", c.name))
                n++
                if (n >= 30) break
            }
        }
        return arr.toString()
    }

    private fun adjustDelay(d: Int) {
        val chId = queue.getOrNull(index)?.id ?: -1
        js("pwPanel.delay($d)") { r ->
            r.trim().toDoubleOrNull()?.let {
                pwDelay = it.toInt()
                prefs.setPitwallDelay(chId, pwDelay)
            }
        }
    }

    private fun remoteCmd(cmd: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        when {
            cmd == "up" -> zap(1)
            cmd == "down" -> zap(-1)
            cmd == "prev" -> previousChannel()
            cmd == "sleep" -> cycleSleep()
            cmd == "panel" -> if (foot != null) foot?.toggle() else if (pwOpen) hidePanel() else if (prefs.pitwallUrl.isNotBlank()) openPanel()
            cmd == "list" -> if (listOpen) closeList() else openList()
            cmd == "pause" -> togglePause()
            cmd == "live" -> goLive()
            cmd.startsWith("adj:") && foot != null -> {
                val d = cmd.substring(4).toIntOrNull() ?: 0
                if (d != 0) foot?.adjust(d)
            }
            cmd == "vol:+" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            cmd == "vol:-" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            cmd == "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            cmd == "adj:0" -> if (pwOpen) {
                pwAdjust = !pwAdjust
                js("pwPanel.adjust(" + pwAdjust + ")")
            }
            cmd.startsWith("adj:") -> {
                val d = cmd.substring(4).toIntOrNull() ?: 0
                if (d != 0) adjustDelay(d)
            }
            cmd.startsWith("view:") -> if (pwOpen) js("pwPanel.view(" + (cmd.substring(5).toIntOrNull() ?: 1) + ")")
            cmd == "sync" -> {
                val chId = queue.getOrNull(index)?.id ?: -1
                js("pwPanel.sync()") { r ->
                    val v = r.trim().toDoubleOrNull() ?: -1.0
                    if (v < 0) {
                        Toast.makeText(this, "No hay ningún evento reciente para sincronizar", Toast.LENGTH_LONG).show()
                    } else {
                        pwDelay = v.toInt()
                        prefs.setPitwallDelay(chId, pwDelay)
                    }
                }
            }
            cmd.startsWith("play:") -> {
                val id = cmd.substring(5).toIntOrNull() ?: return
                var idx = queue.indexOfFirst { it.id == id }
                if (idx < 0) {
                    queue = playable(id)
                    idx = queue.indexOfFirst { it.id == id }
                }
                if (idx >= 0) play(idx)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        stopFoot()
        listPool.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        remote?.stop()
        remote = null
        destroyPanel()
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
