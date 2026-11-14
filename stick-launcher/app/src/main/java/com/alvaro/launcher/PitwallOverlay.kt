package com.alvaro.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * PITWALL encima de la app de DAZN. Tres modos: compacto (columna estrecha), completo y solo avisos.
 * Las teclas del mando las recibe DAZN, así que se maneja con el mando del móvil
 * o con la tecla de «¡Vuelta!» (servicio de accesibilidad LumenKeys).
 */
class PitwallOverlay : Service() {

    companion object {
        @Volatile
        var instance: PitwallOverlay? = null

        fun canShow(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            if (Prefs(ctx).pitwallUrl.isBlank() || !canShow(ctx) || instance != null) return
            Fg.startService(ctx, Intent(ctx, PitwallOverlay::class.java))
        }

        fun stop(ctx: Context) {
            if (instance != null) ctx.stopService(Intent(ctx, PitwallOverlay::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private var web: WebView? = null
    private var webLp: WindowManager.LayoutParams? = null
    private var toastView: View? = null
    private var remote: RemoteServer? = null
    private var delay = 30.0
    private var mode = "compact"
    private var hidden = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Fg.start(this, 2, "PITWALL sobre DAZN")
        prefs = Prefs(this)
        ui = Ui(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        delay = prefs.daznDelay().toDouble()
        mode = prefs.overlayMode
        createWeb()
        remote = RemoteServer(prefs.remoteToken, { c -> main.post { cmd(c) } }, { "[]" }, { state() })
        remote?.start()
        main.postDelayed({
            toast(R.drawable.ic_sync, Color.parseColor("#232838"), "PITWALL sobre DAZN · retraso " + delay.toInt() + " s",
                "Mantén " + keyName() + " o toca «¡Vuelta!» en el móvil cuando cambie el contador de vueltas")
        }, 2500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        remote?.stop()
        remote = null
        removeToast()
        web?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // ya quitado
            }
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    private fun keyName(): String = when (prefs.vueltaKey) {
        "INFO" -> "INFO"
        "AZUL" -> "el botón azul"
        else -> "MENÚ"
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

    private fun panelUrl(): String {
        val base = prefs.pitwallUrl.trim()
        val sep = if (base.contains("?")) "&" else "?"
        val zoom = if (mode == "full") "1.25" else "0.95"
        return base + sep + "panel=1&mode=live&series=f1&delay=" + delay.toInt() + "&zoom=" + zoom + "&hidden=0&fine=1&favs=" +
            java.net.URLEncoder.encode(prefs.pitwallFavs, "UTF-8")
    }

    private fun createWeb() {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.cacheMode = WebSettings.LOAD_DEFAULT
        w.setBackgroundColor(Color.TRANSPARENT)
        w.addJavascriptInterface(Bridge(), "PWBridge")
        w.webViewClient = WebViewClient()
        val lp = WindowManager.LayoutParams(
            ui.px(440), WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END }
        webLp = lp
        web = w
        applyMode(lp, w)
        try {
            wm.addView(w, lp)
        } catch (e: Exception) {
            ErrorLog.add(this, "PITWALL sobre DAZN: " + (e.message ?: e.javaClass.simpleName))
            stopSelf()
            return
        }
        w.loadUrl(panelUrl())
    }

    private fun applyMode(lp: WindowManager.LayoutParams, w: WebView) {
        val sw = resources.displayMetrics.widthPixels
        when (mode) {
            "full" -> {
                lp.width = (sw * 0.35f).toInt()
                lp.x = 0
                lp.y = 0
            }
            else -> {
                lp.width = ui.px(440)
                lp.x = ui.px(32)
                lp.y = ui.px(32)
            }
        }
        w.alpha = if (mode == "alerts" || hidden) 0f else 1f
    }

    private fun setMode(m: String) {
        val w = web ?: return
        val lp = webLp ?: return
        val reload = (m == "full") != (mode == "full")
        mode = m
        prefs.overlayMode = m
        hidden = false
        applyMode(lp, w)
        try {
            wm.updateViewLayout(w, lp)
        } catch (e: Exception) {
            // ventana ya quitada
        }
        if (reload) w.loadUrl(panelUrl())
    }

    private fun js(code: String, cb: ((String) -> Unit)? = null) {
        val w = web ?: return
        w.evaluateJavascript("(function(){try{return String($code)}catch(e){return ''}})()") { r -> cb?.invoke(r.trim('"')) }
    }

    /** «¡Vuelta!»: el contador de vueltas de la tele acaba de cambiar. */
    fun vuelta() {
        js("pwPanel.vuelta()") { r -> onMeasure(r, "Vuelta") }
    }

    fun salida() {
        js("pwPanel.salida()") { r -> onMeasure(r, "Salida") }
    }

    private fun onMeasure(r: String, what: String) {
        val o = try {
            JSONObject(r.replace("\\\"", "\""))
        } catch (e: Exception) {
            null
        }
        if (o == null || !o.optBoolean("ok")) {
            toast(R.drawable.ic_alert, Color.parseColor("#E5484D"), "No encuentro esa " + what.lowercase() + " en los datos",
                "¿Está PITWALL recibiendo la sesión? Prueba otra vez en la próxima vuelta")
            return
        }
        delay = o.optDouble("delay", delay)
        val l = prefs.daznDelays.toMutableList()
        l.add(delay)
        prefs.daznDelays = l
        val n = o.optInt("n", 1)
        val lap = o.optInt("lap", 0)
        val d = String.format(java.util.Locale("es", "ES"), "%.1f", delay)
        toast(R.drawable.ic_check, Color.parseColor("#2FB36B"),
            (if (what == "Vuelta" && lap > 0) "¡Vuelta $lap!" else "¡$what!") + " · retraso $d s",
            if (n > 1) "Media de $n toques" else "Toca otra vez en la próxima vuelta para afinar")
    }

    private fun cmd(c: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        when {
            c == "vuelta" -> vuelta()
            c == "salida" -> salida()
            c == "undo" -> js("pwPanel.undo()") { r -> r.toDoubleOrNull()?.let { delay = it } }
            c == "panel" -> {
                hidden = !hidden
                web?.let { w -> webLp?.let { applyMode(it, w) } }
            }
            c.startsWith("mode:") -> setMode(c.substring(5))
            c == "sync" -> js("pwPanel.sync()") { r -> r.toDoubleOrNull()?.takeIf { it >= 0 }?.let { delay = it } }
            c.startsWith("adj:") -> {
                val d = c.substring(4).toIntOrNull() ?: 0
                if (d != 0) js("pwPanel.delay($d)") { r -> r.toDoubleOrNull()?.let { delay = it } }
            }
            c.startsWith("view:") -> js("pwPanel.view(" + (c.substring(5).toIntOrNull() ?: 1) + ")")
            c == "vol:+" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            c == "vol:-" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            c == "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            c == "stop" -> stopSelf()
        }
    }

    private fun state(): String {
        val m = when {
            hidden -> "oculto"
            mode == "full" -> "completo"
            mode == "alerts" -> "solo avisos"
            else -> "compacto"
        }
        return "DAZN · PITWALL " + m + " · retraso " + String.format(java.util.Locale("es", "ES"), "%.1f", delay) + " s"
    }

    // ---- avisos arriba en el centro (no tapan los rótulos de DAZN) ----

    private fun toast(iconRes: Int, iconBg: Int, title: String, sub: String) {
        main.post {
            removeToast()
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundRect(ui, Color.argb(245, 11, 13, 19), 22)
                setPadding(ui.px(20), ui.px(18), ui.px(32), ui.px(18))
            }
            val badge = FrameLayout(this).apply {
                background = roundRect(ui, iconBg, 14)
                addView(vIcon(this@PitwallOverlay, iconRes, Colors.onColor(iconBg)), FrameLayout.LayoutParams(ui.px(32), ui.px(32), Gravity.CENTER))
            }
            card.addView(badge, LinearLayout.LayoutParams(ui.px(56), ui.px(56)).apply { rightMargin = ui.px(18) })
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(lbl(this@PitwallOverlay, ui, title, 32, Colors.TEXT, true))
                addView(lbl(this@PitwallOverlay, ui, sub, 24, Colors.MUTED))
            })
            showToastView(card, 7000)
        }
    }

    /** Aviso de PITWALL (bandera, safety car…): amarillo, arriba en el centro. */
    private fun flagToast(text: String) {
        main.post {
            removeToast()
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(Color.parseColor("#1A1203"))
                setSizePx(ui, 34)
                typeface = Fonts.bold
                maxWidth = ui.px(900)
                setPadding(ui.px(30), ui.px(20), ui.px(30), ui.px(20))
                background = roundRect(ui, Color.parseColor("#FFD400"), 22)
            }
            showToastView(tv, 7000)
        }
    }

    private fun showToastView(v: View, ms: Long) {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ui.px(40)
        }
        try {
            wm.addView(v, lp)
            toastView = v
            main.postDelayed({ if (toastView === v) removeToast() }, ms)
        } catch (e: Exception) {
            // sin permiso
        }
    }

    private fun removeToast() {
        val v = toastView ?: return
        toastView = null
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            // ya quitado
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun alert(type: String, text: String) {
            if (prefs.pitwallAlerts && text.isNotBlank()) flagToast(text)
        }

        @JavascriptInterface
        fun session(state: String, title: String) {
            // en DAZN el panel no se abre ni se cierra solo
        }
    }
}

/**
 * Servicio de accesibilidad de Lumen: solo sirve para que una tecla del mando (mantenida)
 * haga de «¡Vuelta!» mientras PITWALL está encima de DAZN. No lee la pantalla ni nada más.
 */
class LumenKeys : AccessibilityService() {
    private var swallow = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val ov = PitwallOverlay.instance ?: return false
        val key = when (Prefs(this).vueltaKey) {
            "INFO" -> KeyEvent.KEYCODE_INFO
            "AZUL" -> KeyEvent.KEYCODE_PROG_BLUE
            else -> KeyEvent.KEYCODE_MENU
        }
        if (event.keyCode != key) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 1) {
            swallow = true
            ov.vuelta()
            return true
        }
        if (swallow) {
            if (event.action == KeyEvent.ACTION_UP) swallow = false
            return true
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // no se usa
    }

    override fun onInterrupt() {
        // nada
    }
}
