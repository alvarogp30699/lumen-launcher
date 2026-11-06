package com.alvaro.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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

        val title = TextView(this).apply {
            text = "Configurar desde el móvil"
            setTextColor(Colors.TEXT)
            setSizePx(ui, 44)
            typeface = Typeface.DEFAULT_BOLD
        }
        val steps = TextView(this).apply {
            text = "1. Conecta el móvil a la misma Wi-Fi que el stick.\n" +
                "2. Escanea el código QR con la cámara.\n" +
                "3. Rellena los datos de tu lista IPTV y pulsa Guardar."
            setTextColor(Colors.MUTED)
            setSizePx(ui, 28)
            setLineSpacing(0f, 1.3f)
            setPadding(0, ui.px(24), 0, ui.px(24))
        }
        val urlText = TextView(this).apply {
            text = "Preparando…"
            setTextColor(Colors.ACCENT)
            setSizePx(ui, 28)
            typeface = Typeface.DEFAULT_BOLD
        }
        val status = TextView(this).apply {
            text = ""
            setTextColor(Colors.TEXT)
            setSizePx(ui, 30)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, ui.px(24), 0, 0)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(steps)
            addView(urlText)
            addView(status)
        }

        val qr = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(ui.px(20), ui.px(20), ui.px(20), ui.px(20))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Colors.BG)
            setPadding(ui.px(120), ui.px(56), ui.px(120), ui.px(56))
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(qr, LinearLayout.LayoutParams(ui.px(500), ui.px(500)))
        }
        setContentView(root)

        val token = (100000..999999).random().toString()
        val s = SetupServer(applicationContext, prefs, token) {
            // datos nuevos: se descarta la lista guardada para que se recargue
            Xtream.clearCache(applicationContext)
            Xtream.channels = emptyList()
            runOnUiThread {
                status.text = "✔ Guardado"
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
                    urlText.text = url
                    qr.setImageBitmap(qrBitmap(url, ui.px(460)))
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
