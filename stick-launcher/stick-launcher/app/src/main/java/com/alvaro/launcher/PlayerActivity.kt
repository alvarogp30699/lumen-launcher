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

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pwHint = TextView(this).apply {
            setTextColor(Color.parseColor("#1A1203"))
            setSizePx(ui, 26)
            typeface = Typeface.DEFAULT_BOLD
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
            typeface = Typeface.DEFAULT_BOLD
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
            typeface = Typeface.DEFAULT_BOLD
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
        setContentView(root)

        val wantedId = intent.getIntExtra("id", -1)
        thread {
            if (Xtream.channels.isEmpty()) Xtream.load(applicationContext, prefs, false)
            runOnUiThread { start(wantedId) }
        }
    }

    private fun buildOverlay(): FrameLayout {
        chip = TextView(this).apply {
            setTextColor(Colors.TEXT)
            setSizePx(ui, 34)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(ui.px(26), ui.px(14), ui.px(26), ui.px(14))
            background = GradientDrawable().apply {
                cornerRadius = ui.px(18).toFloat()
                setColor(Color.argb(217, 11, 13, 18))
            }
        }
        nowTitle = TextView(this).apply {
            setTextColor(Colors.TEXT)
            setSizePx(ui, 44)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nowTime = TextView(this).apply {
            setTextColor(Colors.MUTED)
            setSizePx(ui, 24)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Colors.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#2B3345"))
        }
        nextTitle = TextView(this).apply {
            setTextColor(Colors.MUTED)
            setSizePx(ui, 26)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(44), ui.px(32), ui.px(44), ui.px(32))
            background = GradientDrawable().apply {
                cornerRadius = ui.px(28).toFloat()
                setColor(Color.argb(235, 11, 13, 18))
            }
            addView(nowTitle)
            addView(nowTime)
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.px(10)).apply {
                topMargin = ui.px(14)
                bottomMargin = ui.px(14)
            })
            addView(nextTitle)
        }
        val box = FrameLayout(this)
        box.addView(chip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = ui.px(72)
            topMargin = ui.px(56)
        })
        box.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = ui.px(72)
            rightMargin = ui.px(72)
            bottomMargin = ui.px(56)
        })
        return box
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
            .setBufferDurationsMs(8000, 30000, 1500, 3000)
            .setPrioritizeTimeOverSizeThresholds(true)
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
                } else if (playbackState == Player.STATE_BUFFERING) {
                    handler.postDelayed(stallCheck, 20_000)
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
        chip.text = ch.num.toString() + "   " + ch.name
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
                        nowTime.text = hhmm(now.start) + " – " + hhmm(now.end)
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
        if (pwOpen && !isVolume) return panelKey(keyCode)
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> {
                cycleSleep()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                previousChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_INFO -> {
                if (prefs.pitwallUrl.isNotBlank()) {
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
                if (queue.isNotEmpty()) showInfo(queue[index])
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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

    private fun closePanel() = hidePanel()

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
        val pw = if (pwView == null) "" else "  ·  PITWALL " + (if (pwOpen) "abierto" else "oculto") + " · retraso " + pwDelay + " s"
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
            cmd == "panel" -> if (pwOpen) hidePanel() else if (prefs.pitwallUrl.isNotBlank()) openPanel()
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
