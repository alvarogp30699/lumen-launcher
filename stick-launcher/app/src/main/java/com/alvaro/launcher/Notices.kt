package com.alvaro.launcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** Servicios en primer plano de Lumen (Android los exige para seguir funcionando con otra app delante). */
object Fg {
    private const val CHANNEL = "lumen"

    fun start(s: Service, id: Int, text: String) {
        val nm = s.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lumen", NotificationManager.IMPORTANCE_MIN))
            }
            Notification.Builder(s, CHANNEL).setSmallIcon(R.drawable.ic_flag).setContentTitle("Lumen").setContentText(text).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(s).setSmallIcon(R.drawable.ic_flag).setContentTitle("Lumen").setContentText(text).build()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            s.startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            s.startForeground(id, n)
        }
    }

    fun startService(ctx: Context, i: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            ErrorLog.add(ctx, "No se pudo iniciar un servicio: " + (e.message ?: e.javaClass.simpleName))
        }
    }
}

/**
 * Aviso encima de cualquier app (SmartTube, DAZN…): necesita el permiso «Mostrar sobre otras apps».
 * Se puede pulsar OK para ir al partido o Atrás para descartarlo. Desaparece solo a los 15 s.
 */
object OverlayNotice {
    private var current: View? = null
    private val main = Handler(Looper.getMainLooper())

    fun canShow(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun show(ctx: Context, iconRes: Int, iconBg: Int, kicker: String, title: String, sub: String, okText: String, action: Intent?) {
        if (!canShow(ctx)) return
        main.post {
            dismiss(ctx)
            val app = ctx.applicationContext
            val ui = Ui(app)
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val card = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(ui, Color.argb(248, 11, 13, 19), 28)
                foreground = ring(ui, Colors.TEXT, 28)
                setPadding(ui.px(34), ui.px(28), ui.px(34), ui.px(28))
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val head = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(vIcon(app, R.drawable.ic_bell, Colors.ACCENT), LinearLayout.LayoutParams(ui.px(28), ui.px(28)).apply { rightMargin = ui.px(12) })
                addView(lbl(app, ui, kicker, 24, Colors.ACCENT, true))
            }
            card.addView(head)
            val body = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.px(18), 0, ui.px(20))
            }
            val badge = FrameLayout(app).apply {
                background = roundRect(ui, iconBg, 18)
                addView(vIcon(app, iconRes, Colors.onColor(iconBg)), FrameLayout.LayoutParams(ui.px(42), ui.px(42), Gravity.CENTER))
            }
            body.addView(badge, LinearLayout.LayoutParams(ui.px(76), ui.px(76)).apply { rightMargin = ui.px(22) })
            body.addView(LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(lbl(app, ui, title, 36, Colors.TEXT, true))
                addView(lbl(app, ui, sub, 24, Colors.MUTED))
            })
            card.addView(body)
            card.addView(LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill(app, ui, if (action != null) "OK · $okText" else "OK · entendido", Colors.ACCENT, size = 28).apply {
                    setPadding(ui.px(24), ui.px(12), ui.px(24), ui.px(12))
                })
                addView(pill(app, ui, "Atrás · descartar", Colors.SURFACE2, Colors.TEXT, 28).apply {
                    setPadding(ui.px(24), ui.px(12), ui.px(24), ui.px(12))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(14) })
            })
            card.setOnKeyListener { _, keyCode, ev ->
                if (ev.action != KeyEvent.ACTION_UP) return@setOnKeyListener true
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        dismiss(app)
                        if (action != null) {
                            try {
                                app.startActivity(action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) {
                                ErrorLog.add(app, "Aviso: no se pudo abrir " + (e.message ?: ""))
                            }
                        }
                    }
                    else -> dismiss(app)
                }
                true
            }
            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                ui.px(760), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = ui.px(64)
                y = ui.px(56)
            }
            try {
                wm.addView(card, lp)
                current = card
                card.requestFocus()
                main.postDelayed({ if (current === card) dismiss(app) }, 15_000)
            } catch (e: Exception) {
                ErrorLog.add(app, "Aviso sobre otras apps: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun dismiss(ctx: Context) {
        val v = current ?: return
        current = null
        try {
            (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            // ya no estaba
        }
    }
}

/**
 * Recordatorios («Avísame»): partidos y sesiones de F1. Saltan 10 min antes con un aviso encima de la app que estés.
 * Los partidos del Madrid se apuntan solos (si está activado en Ajustes), y también el vigilante de goles.
 */
object Reminders {
    class Item(val id: String, val at: Long, val kind: String, val title: String, val sub: String, val target: String)

    const val BEFORE = 10 * 60_000L

    fun list(ctx: Context): List<Item> = try {
        val a = JSONArray(Prefs(ctx).remindersJson)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Item(o.optString("id"), o.optLong("at"), o.optString("kind"), o.optString("title"), o.optString("sub"), o.optString("target"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, l: List<Item>) {
        val a = JSONArray()
        for (r in l) a.put(JSONObject().put("id", r.id).put("at", r.at).put("kind", r.kind).put("title", r.title).put("sub", r.sub).put("target", r.target))
        Prefs(ctx).remindersJson = a.toString()
    }

    fun has(ctx: Context, id: String): Boolean = list(ctx).any { it.id == id }

    fun add(ctx: Context, r: Item) {
        val l = list(ctx).filter { it.id != r.id } + r
        save(ctx, l)
        schedule(ctx, r)
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
        alarm(ctx).cancel(pending(ctx, id))
    }

    fun get(ctx: Context, id: String): Item? = list(ctx).firstOrNull { it.id == id }

    /** Al arrancar: se borran los pasados y se reprograman los futuros (Android los olvida al reiniciar). */
    fun scheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val keep = list(ctx).filter { it.at + 3 * 3600_000L > now }
        save(ctx, keep)
        for (r in keep) schedule(ctx, r)
    }

    /** Apunta solo el próximo partido del Madrid (aviso 10 min antes y vigilante de goles). */
    fun autoMadrid(ctx: Context, m: Football.Match?) {
        if (m == null || m.state != "pre") return
        val p = Prefs(ctx)
        val title = m.home.short + " – " + m.away.short
        if (p.notifyMadrid && !has(ctx, "auto_" + m.id)) {
            add(ctx, Item("auto_" + m.id, m.start, "match", title, m.league.name, m.id))
        }
        if (p.notifyGoals && !has(ctx, "watch_" + m.id)) {
            add(ctx, Item("watch_" + m.id, m.start, "watch", title, m.league.name, m.id))
        }
    }

    private fun alarm(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(ctx: Context, r: Item) {
        val t = if (r.kind == "watch") r.at else r.at - BEFORE
        if (t < System.currentTimeMillis() - 60_000L) return
        try {
            alarm(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(ctx, r.id))
        } catch (e: Exception) {
            ErrorLog.add(ctx, "Recordatorio: " + (e.message ?: e.javaClass.simpleName))
        }
    }
}

/** Salta a la hora de un recordatorio. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val r = Reminders.get(ctx, id) ?: return
        if (r.kind == "watch") {
            Fg.startService(ctx, Intent(ctx, MatchWatch::class.java).putExtra("match", r.target))
            Reminders.remove(ctx, id)
            return
        }
        Reminders.remove(ctx, id)
        val app = ctx.applicationContext
        val result = goAsync()
        thread {
            try {
                val prefs = Prefs(app)
                var action: Intent? = null
                var sub = r.sub
                if (r.kind == "match") {
                    val m = Football.find(r.target)
                    val ch = if (m != null && prefs.configured) Football.findChannel(prefs, m) else null
                    action = if (ch != null) {
                        sub = r.sub + " · " + ch.name
                        Intent(app, PlayerActivity::class.java).putExtra("id", ch.id)
                    } else {
                        Intent(app, FootballActivity::class.java).putExtra("match", r.target)
                    }
                } else if (r.kind == "f1") {
                    action = app.packageManager.getLeanbackLaunchIntentForPackage("com.dazn") ?: app.packageManager.getLaunchIntentForPackage("com.dazn")
                    if (action != null) PitwallOverlay.start(app)
                }
                val isMadrid = r.title.contains(Football.TEAM_NAME)
                val title = when {
                    r.kind == "f1" -> r.title + " empieza en 10 min"
                    isMadrid -> "El Madrid empieza en 10 min"
                    else -> r.title + " empieza en 10 min"
                }
                OverlayNotice.show(
                    app, if (r.kind == "f1") R.drawable.ic_flag else R.drawable.ic_ball,
                    if (r.kind == "f1") Color.parseColor("#E10600") else Color.parseColor("#5B4FD0"),
                    "Recordatorio · Lumen", title, if (isMadrid) r.title + " · " + sub else sub,
                    if (r.kind == "f1") "abrir DAZN" else "verlo ya", action
                )
            } finally {
                result.finish()
            }
        }
    }
}

/**
 * Vigila un partido del Madrid mientras se juega: si marca y no lo estás viendo en Lumen,
 * sale un aviso encima de la app en la que estés. Se para solo al acabar.
 */
class MatchWatch : Service() {
    @Volatile
    private var running = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Fg.start(this, 3, "Siguiendo el partido del " + Football.TEAM_NAME)
        val id = intent?.getStringExtra("match")
        if (id == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        thread {
            var m = Football.find(id)
            var lastMine = -1
            val until = System.currentTimeMillis() + 4 * 3600_000L
            while (running && System.currentTimeMillis() < until) {
                val cur = m?.let { Football.refresh(it) } ?: Football.find(id)
                if (cur != null) {
                    m = cur
                    val mine = (if (cur.home.id == Football.TEAM_ID) cur.home.score else cur.away.score).toIntOrNull() ?: 0
                    if (lastMine in 0 until mine && PlayerActivity.watchingMatch != cur.id && Prefs(this).notifyGoals) {
                        val g = cur.goals().lastOrNull { it.teamId == Football.TEAM_ID }
                        OverlayNotice.show(
                            this, R.drawable.ic_ball, Color.parseColor("#2FB36B"), "Real Madrid · " + cur.league.name,
                            "Gol del Madrid · " + cur.home.score + " – " + cur.away.score,
                            (g?.let { it.player + " " + it.minute + " · " } ?: "") + cur.home.short + " – " + cur.away.short,
                            "ir al partido", Intent(this, FootballActivity::class.java).putExtra("match", cur.id)
                        )
                    }
                    lastMine = mine
                    if (cur.done) break
                }
                try {
                    Thread.sleep(30_000)
                } catch (e: InterruptedException) {
                    break
                }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
