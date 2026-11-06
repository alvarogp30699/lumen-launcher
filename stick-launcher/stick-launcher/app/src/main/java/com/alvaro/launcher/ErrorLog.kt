package com.alvaro.launcher

import android.app.Application
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro de errores en un archivo del propio stick (máx. ~100 KB). */
object ErrorLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "errores.log")

    @Synchronized
    fun add(ctx: Context, msg: String) {
        try {
            val f = file(ctx)
            if (f.exists() && f.length() > 100_000) {
                val keep = f.readText().takeLast(50_000)
                f.writeText(keep)
            }
            val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("[" + t + "] " + msg + "\n")
        } catch (e: Exception) {
            // si el registro falla, no se hace nada más
        }
    }

    fun read(ctx: Context): String = try {
        val f = file(ctx)
        if (f.exists()) f.readText() else ""
    } catch (e: Exception) {
        ""
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (e: Exception) {
            // nada que borrar
        }
    }
}

/** Anota los cierres inesperados de la app en el registro antes de que Android la cierre. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            ErrorLog.add(this, "CIERRE INESPERADO en " + t.name + ": " + android.util.Log.getStackTraceString(e).take(3000))
            old?.uncaughtException(t, e)
        }
    }
}
