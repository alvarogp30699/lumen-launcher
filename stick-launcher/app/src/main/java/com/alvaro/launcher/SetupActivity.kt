package com.alvaro.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/** Muestra un QR: se escanea con el móvil y se rellenan los datos desde allí. */
class SetupActivity : Activity() {

    private var server: SetupServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val prefs = Prefs(this)

        fun step(n: Int, t: String, d: String, done: Boolean, now: Boolean): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, ui.px(28))
            }
            val circ: View = if (done) {
                FrameLayout(this).apply {
                    background = roundRect(ui, Color.parseColor("#2FB36B"), 28)
                    addView(vIcon(this@SetupActivity, R.drawable.ic_check, Color.parseColor("#07080C")), FrameLayout.LayoutParams(ui.px(28), ui.px(28), Gravity.CENTER))
                }
            } else {
                TextView(this).apply {
                    text = n.toString()
                    gravity = Gravity.CENTER
                    setSizePx(ui, 28)
                    typeface = Fonts.bold
                    if (now) {
                        setTextColor(Colors.onColor())
                        background = roundRect(ui, Colors.ACCENT, 28)
                    } else {
                        setTextColor(Colors.MUTED)
                        background = ring(ui, Color.parseColor("#3A4050"), 28)
                    }
                }
            }
            row.addView(circ, LinearLayout.LayoutParams(ui.px(56), ui.px(56)).apply { rightMargin = ui.px(22) })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(lbl(this@SetupActivity, ui, t, 36, if (done || now) Colors.TEXT else Colors.MUTED, true))
                addView(lbl(this@SetupActivity, ui, d, 24, Colors.MUTED))
            })
            return row
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@SetupActivity).apply { setImageResource(R.drawable.splash_logo) }, LinearLayout.LayoutParams(ui.px(56), ui.px(56)).apply { rightMargin = ui.px(18) })
            addView(lbl(this@SetupActivity, ui, "Lumen", 48, Colors.TEXT, true))
        }
        left.addView(brand)
        left.addView(lbl(this, ui, "Configúralo desde el móvil", 72, Colors.TEXT, true).apply { setPadding(0, ui.px(36), 0, 0) })
        left.addView(lbl(this, ui, "Conecta el móvil a la misma Wi-Fi y escanea el código con la cámara. No hace falta escribir nada con el mando.", 28, Colors.MUTED).apply {
            setPadding(0, ui.px(16), 0, ui.px(40))
            setLineSpacing(ui.px(6).toFloat(), 1f)
        })
        val iptvOk = prefs.configured
        left.addView(step(1, "Lista IPTV", "Servidor, usuario y contraseña", iptvOk, !iptvOk))
        left.addView(step(2, "Tu equipo y competiciones", Football.TEAM_NAME + " · LaLiga, Champions y Copa", iptvOk, false))
        left.addView(step(3, "PITWALL (opcional)", "La dirección de tu PC para la F1", prefs.pitwallUrl.isNotBlank(), iptvOk && prefs.pitwallUrl.isBlank()))
        val status = lbl(this, ui, "", 36, Color.parseColor("#7DD3A0"), true)
        left.addView(status)

        val qr = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(ui.px(36), ui.px(36), ui.px(36), ui.px(36))
            background = roundRect(ui, Color.WHITE, 36)
        }
        val urlText = lbl(this, ui, "Preparando…", 36, Colors.ACCENT, true).apply { gravity = Gravity.CENTER }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(qr, LinearLayout.LayoutParams(ui.px(470), ui.px(470)))
            addView(lbl(this@SetupActivity, ui, "o entra en", 28, Colors.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.px(24), 0, ui.px(6))
            })
            addView(urlText)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.px(120), ui.px(80), ui.px(120), ui.px(80))
            background = homeBackground(ui)
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(120) })
        }
        setContentView(root)

        val token = (100000..999999).random().toString()
        val s = SetupServer(applicationContext, prefs, token) {
            // datos nuevos: se descarta la lista guardada para que se recargue
            Xtream.clearCache(applicationContext)
            Xtream.channels = emptyList()
            runOnUiThread {
                status.text = "Guardado. Volviendo al inicio…"
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
            }
        }
        server = s
        s.start { port ->
            val ip = localIp()
            runOnUiThread {
                if (ip == null || port < 0) {
                    urlText.text = "No hay conexión de red"
                } else {
                    val url = "http://$ip:$port/?k=$token"
                    urlText.text = "$ip:$port/?k=$token"
                    qr.setImageBitmap(qrBitmap(url, ui.px(400)))
                }
            }
        }
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    private fun localIp(): String? {
        val nets = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        for (ni in nets) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses.toList()) {
                if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
            }
        }
        return null
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
