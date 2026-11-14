package com.alvaro.launcher

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Salvapantallas de Lumen: reloj, fecha y próxima sesión de F1 sobre negro.
 * El texto cambia de sitio cada minuto para que no se quede marcado en la tele.
 */
class LumenDream : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private var drift: ValueAnimator? = null
    private lateinit var root: FrameLayout
    private lateinit var box: LinearLayout
    private lateinit var f1: TextView
    private lateinit var rm: TextView

    private val move = object : Runnable {
        override fun run() {
            if (root.width > 0 && box.width > 0) {
                box.animate().alpha(0f).setDuration(600).withEndAction {
                    box.x = Random.nextInt(0, maxOf(1, root.width - box.width)).toFloat()
                    box.y = Random.nextInt(0, maxOf(1, root.height - box.height)).toFloat()
                    box.animate().alpha(1f).setDuration(600).start()
                }.start()
            }
            handler.postDelayed(this, 60_000)
        }
    }

    private val refreshF1 = object : Runnable {
        override fun run() {
            loadF1()
            handler.postDelayed(this, 300_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        val ui = Ui(this)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = ui.px(700).toFloat()
                colors = intArrayOf(Colors.alpha(Colors.ACCENT, 0x2A), Colors.alpha(Colors.ACCENT, 0x00))
            }
        }
        root.addView(glow, FrameLayout.LayoutParams(ui.px(1400), ui.px(1400)))

        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Color.parseColor("#D8DBE2"))
            setSizePx(ui, 170)
            typeface = Fonts.light
            includeFontPadding = false
        })
        box.addView(TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"
            format12Hour = "EEEE, d MMMM"
            setTextColor(Colors.MUTED)
            setSizePx(ui, 32)
        })
        f1 = TextView(this).apply {
            setTextColor(Colors.ACCENT)
            setSizePx(ui, 30)
            typeface = Fonts.bold
            setPadding(0, ui.px(20), 0, 0)
            visibility = View.GONE
        }
        box.addView(f1)
        rm = TextView(this).apply {
            setTextColor(Color.parseColor("#C3C7E6"))
            setSizePx(ui, 30)
            typeface = Fonts.bold
            setPadding(0, ui.px(12), 0, 0)
            visibility = View.GONE
        }
        box.addView(rm)
        root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        // el brillo de fondo se desplaza muy despacio (solo se mueve una capa: casi no gasta)
        drift = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 90_000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val f = it.animatedValue as Float
                glow.translationX = -ui.px(400) + f * (root.width - ui.px(1000))
                glow.translationY = -ui.px(500) + f * ui.px(500)
            }
            start()
        }
        handler.postDelayed(move, 1000)
        handler.post(refreshF1)
    }

    private fun loadF1() {
        thread {
            val m = Football.teamFocus() ?: return@thread
            val rival = if (m.home.id == Football.TEAM_ID) m.away.short else m.home.short
            val t = when {
                m.live -> Football.TEAM_NAME + " " + m.score() + " " + rival + " · " + m.status()
                m.done -> Football.TEAM_NAME + " " + m.score() + " " + rival + " · final"
                else -> Football.TEAM_NAME + " vs " + rival + " · " + SimpleDateFormat("EEEE HH:mm", Locale("es", "ES")).format(Date(m.start))
            }
            handler.post {
                rm.text = t
                rm.visibility = View.VISIBLE
            }
        }
        val prefs = Prefs(this)
        if (prefs.pitwallUrl.isBlank()) return
        thread {
            val e = F1Data.next(prefs) ?: return@thread
            val now = System.currentTimeMillis()
            val s = F1Data.current(e, now) ?: return@thread
            val day = SimpleDateFormat("EEEE HH:mm", Locale("es", "ES")).format(Date(s.at))
            val txt = "F1 · " + e.name + " · " + s.name + " · " + day + " · " + F1Data.countdown(s.at, now).lowercase()
            handler.post {
                f1.text = txt
                f1.visibility = View.VISIBLE
            }
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        drift?.cancel()
        drift = null
        super.onDetachedFromWindow()
    }
}
