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
import java.util.Collections
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
    private val failed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pool = Executors.newFixedThreadPool(3)
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
        if (url.isBlank() || url in failed) return
        pool.execute {
            // si la tarjeta ya muestra otro canal (has bajado rápido), no se descarga
            if (iv.tag != url) return@execute
            val bmp = fetch(url, size)
            if (bmp == null) failed.add(url) else cache.put(url, bmp)
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
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "LumenLauncher/0.2")
            try {
                if (c.responseCode !in 200..299) return null
                val bytes = c.inputStream.use { readLimited(it, 700_000) } ?: return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0) return null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= size) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                c.disconnect()
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
