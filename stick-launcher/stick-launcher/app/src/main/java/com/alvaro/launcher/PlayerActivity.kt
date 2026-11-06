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
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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

    private fun start(wantedId: Int) {
        val all = Xtream.channels
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
        if (prefs.sleepMinutes > 0) {
            handler.postDelayed({
                Toast.makeText(this, "Temporizador: reproducción detenida", Toast.LENGTH_LONG).show()
                finish()
            }, prefs.sleepMinutes * 60_000L)
        }
    }

    private fun createPlayer() {
        val p = ExoPlayer.Builder(this).build()
        var params = p.trackSelectionParameters.buildUpon()
        if (prefs.quality == "1080p") params = params.setMaxVideoSize(1920, 1080)
        if (prefs.quality == "4k") params = params.setForceHighestSupportedBitrate(true)
        p.trackSelectionParameters = params.build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) retries = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                retries++
                if (retries <= 3) {
                    handler.postDelayed({
                        player?.prepare()
                    }, 2000)
                } else {
                    Toast.makeText(
                        this@PlayerActivity,
                        "No se puede reproducir este canal. Prueba otro con ▲▼ o cambia el formato en Ajustes.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
        playerView.player = p
        player = p
    }

    private fun play(i: Int) {
        val p = player ?: return
        index = i
        val ch = queue[i]
        retries = 0
        p.setMediaItem(MediaItem.fromUri(Xtream.streamUrl(prefs, ch.id)))
        p.prepare()
        p.playWhenReady = true
        prefs.lastChannelId = ch.id
        prefs.lastChannelName = ch.name
        closePanel()
        detectSeries(ch.name, ch.cat)
        pwDelay = prefs.pitwallDelay(ch.id)
        showInfo(ch)
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
        if (pwOpen) return panelKey(keyCode)
        when (keyCode) {
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

    private val f1Words = listOf("formula 1", "fórmula 1", "formula1", "fórmula1", "f1 ", " f1", "gran premio", "gp de", "grand prix")
    private val motoWords = listOf("motogp", "moto gp", "moto2", "moto3", "gran premio de", "mundial de motociclismo")
    private val sessionWords = listOf("carrera", "clasificación", "clasificacion", "libres", "sprint", "qualifying", "race", "practice", "warm up", "warm-up")

    /** Detecta automáticamente si se está viendo F1 o MotoGP por el nombre del canal, la categoría o el título del programa. */
    private fun detectSeries(name: String, cat: String, epgTitle: String = "") {
        val t = " " + (name + " " + cat + " " + epgTitle).lowercase() + " "
        val isMoto = motoWords.any { t.contains(it) } && !t.contains("formula") && !t.contains("fórmula")
        val isF1 = f1Words.any { t.contains(it) } || (name.lowercase().contains("f1"))
        val series = when {
            isMoto && !t.contains(" f1") -> "motogp"
            isF1 -> "f1"
            else -> null
        }
        val live = epgTitle.isEmpty() || sessionWords.any { t.contains(it) } || series != null
        pwSeries = if (live) series else null
        updateHint()
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
        val series = pwSeries ?: "f1"
        return base + sep + "panel=1&mode=live&series=" + series + "&delay=" + pwDelay + "&zoom=1.25"
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openPanel() {
        val root = playerView.parent as FrameLayout
        val w = WebView(this)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        w.settings.mediaPlaybackRequiresUserGesture = true
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(v: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                Toast.makeText(this@PlayerActivity, "No se alcanza PITWALL. ¿Está encendido el PC y es correcta la dirección?", Toast.LENGTH_LONG).show()
            }
        }
        val width = (resources.displayMetrics.widthPixels * 0.35f).toInt()
        root.addView(w, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.END })
        w.loadUrl(panelUrl())
        pwView = w
        pwOpen = true
        pwAdjust = false
        pwHint.visibility = View.GONE
        overlay.visibility = View.GONE
    }

    private fun closePanel() {
        val w = pwView ?: run { pwOpen = false; return }
        (w.parent as? ViewGroup)?.removeView(w)
        w.destroy()
        pwView = null
        pwOpen = false
        pwAdjust = false
        updateHint()
    }

    private fun js(code: String, cb: ((String) -> Unit)? = null) {
        pwView?.evaluateJavascript(code) { r -> cb?.invoke(r ?: "") }
    }

    /** Teclas con el panel abierto: ◀▶ cambia de vista, ▲▼ desplaza, OK entra/sale del ajuste de retraso, Atrás cierra. */
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
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_INFO -> closePanel()
            else -> return true
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closePanel()
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
