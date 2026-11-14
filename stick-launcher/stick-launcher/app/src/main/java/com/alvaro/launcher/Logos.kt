package com.alvaro.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Logos de canal: se descargan solo cuando se ven, se reducen al tamaño del mosaico
 * y se guardan en memoria con un límite fijo (10 MB), así nunca ahogan los 2 GB del stick.
 */
object Logos {
    private val cache = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    // logos que fallaron y cuándo: se reintentan pasado un minuto (un fallo de red no es para siempre)
    private val failed = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** Pone el logo en [iv]. [done] recibe true cuando hay imagen (para ocultar las iniciales). */
    fun load(url: String, iv: ImageView, size: Int, done: (Boolean) -> Unit) {
        iv.tag = url
        val hit = if (url.isBlank()) null else cache.get(url)
        if (hit != null) {
            iv.setImageBitmap(hit)
            done(true)
            return
        }
        iv.setImageDrawable(null)
        done(false)
        if (url.isBlank()) return
        val failedAt = failed[url]
        if (failedAt != null && System.currentTimeMillis() - failedAt < 60_000L) return
        pool.execute {
            // si la tarjeta ya muestra otro canal (has bajado rápido), no se descarga
            if (iv.tag != url) return@execute
            val bmp = fetch(url, size)
            if (bmp == null) failed[url] = System.currentTimeMillis() else { failed.remove(url); cache.put(url, bmp) }
            main.post {
                if (bmp != null && iv.tag == url) {
                    iv.setImageBitmap(bmp)
                    done(true)
                }
            }
        }
    }

    fun clear() {
        cache.evictAll()
    }

    private fun fetch(url: String, size: Int): Bitmap? {
        return try {
            var target = url
            var c: HttpURLConnection? = null
            // HttpURLConnection no sigue redirecciones de http a https: se siguen a mano
            for (i in 0..4) {
                val conn = URL(target).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) LumenLauncher/0.2")
                val code = conn.responseCode
                if (code in 300..399) {
                    val next = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (next.isNullOrBlank()) return null
                    target = URL(URL(target), next).toString()
                    continue
                }
                c = conn
                break
            }
            val conn = c ?: return null
            try {
                if (conn.responseCode !in 200..299) return null
                val bytes = conn.inputStream.use { readLimited(it, 2_500_000) } ?: return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0) return null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= size) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            clear()
            null
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }
}
