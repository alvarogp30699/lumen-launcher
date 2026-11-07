package com.alvaro.launcher

import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Mini servidor web que solo está activo mientras la pantalla "Configurar desde el móvil" está abierta.
 * Cada sesión usa un código aleatorio en el enlace del QR; sin él responde 403.
 */
class SetupServer(
    private val appCtx: android.content.Context,
    private val prefs: Prefs,
    private val token: String,
    private val onSaved: () -> Unit
) {
    @Volatile
    private var running = false
    private var socket: ServerSocket? = null

    /** Abre el puerto (8080-8090) en segundo plano y avisa con el puerto elegido (-1 si falla). */
    fun start(onReady: (Int) -> Unit) {
        running = true
        thread {
            var s: ServerSocket? = null
            for (p in 8080..8090) {
                try {
                    s = ServerSocket(p)
                    break
                } catch (e: Exception) {
                    // puerto ocupado, se prueba el siguiente
                }
            }
            socket = s
            onReady(s?.localPort ?: -1)
            if (s == null) return@thread
            while (running) {
                try {
                    handle(s.accept())
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            // nada que hacer
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun parseForm(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (s.isBlank()) return out
        for (pair in s.split("&")) {
            val kv = pair.split("=", limit = 2)
            val k = URLDecoder.decode(kv[0], "UTF-8")
            val v = URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            out[k] = v
        }
        return out
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 5000
            val ins = c.getInputStream().buffered()
            val requestLine = readLine(ins) ?: return
            var length = 0
            while (true) {
                val h = readLine(ins) ?: break
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("content-length:")) length = h.substring(15).trim().toIntOrNull() ?: 0
            }
            var body = ""
            if (length > 0 && length < 400000) {
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = ins.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read, Charsets.UTF_8)
            }
            val parts = requestLine.split(" ")
            val method = parts[0]
            val target = parts.getOrElse(1) { "/" }
            val path = target.substringBefore('?')
            val query = parseForm(target.substringAfter('?', ""))

            if (method == "POST" && path == "/save") {
                val form = parseForm(body)
                if (form["k"] != token) {
                    respond(c, 403, "<p>Código incorrecto. Vuelve a escanear el QR.</p>")
                    return
                }
                save(form)
                onSaved()
                respond(c, 200, page("Guardado. Ya puedes mirar la tele."))
            } else if (method == "POST" && path == "/restore") {
                val form = parseForm(body)
                if (form["k"] != token) {
                    respond(c, 403, "<p>Código incorrecto. Vuelve a escanear el QR.</p>")
                    return
                }
                val n = prefs.importJson(form["data"] ?: "")
                Xtream.channels = emptyList()
                onSaved()
                respond(c, 200, page(if (n > 0) "Copia restaurada ($n ajustes)." else "Ese archivo no es una copia válida."))
            } else if (method == "GET" && path == "/log") {
                if (query["k"] != token) {
                    respond(c, 403, "<p>Código incorrecto. Vuelve a escanear el QR.</p>")
                    return
                }
                val txt = ErrorLog.read(appCtx)
                respond(c, 200, if (txt.isBlank()) "Sin errores registrados." else txt, "text/plain; charset=utf-8", "lumen-errores.txt")
            } else if (method == "GET" && path == "/backup") {
                if (query["k"] != token) {
                    respond(c, 403, "<p>Código incorrecto. Vuelve a escanear el QR.</p>")
                    return
                }
                respond(c, 200, prefs.exportJson(), "application/json; charset=utf-8", "lumen-copia.json")
            } else if (method == "GET" && path == "/") {
                if (query["k"] != token) {
                    respond(c, 403, "<p>Código incorrecto. Vuelve a escanear el QR.</p>")
                    return
                }
                respond(c, 200, page(""))
            } else {
                respond(c, 404, "<p>No encontrado</p>")
            }
        } catch (e: Exception) {
            // petición rota: se ignora
        } finally {
            try {
                c.close()
            } catch (e: Exception) {
                // nada que hacer
            }
        }
    }

    private fun save(f: Map<String, String>) {
        f["server"]?.let { if (it.isNotBlank()) prefs.server = it }
        f["user"]?.let { if (it.isNotBlank()) prefs.user = it }
        f["pass"]?.let { if (it.isNotBlank()) prefs.pass = it }
        f["repo"]?.let { prefs.updateRepo = it }
        f["pitwall"]?.let { prefs.pitwallUrl = it }
        f["alerts"]?.let { prefs.pitwallAlerts = (it == "1") }
        f["auto"]?.let { prefs.pitwallAuto = (it == "1") }
        f["favs"]?.let { prefs.pitwallFavs = it }
        f["profiles"]?.let { if (it.isNotBlank()) prefs.profileNames = it.split(",") }
        f["pin"]?.let { prefs.pin = it }
        f["quality"]?.let { if (it in listOf("auto", "4k", "1080p")) prefs.quality = it }
        f["format"]?.let { if (it in listOf("m3u8", "ts")) prefs.format = it }
        f["sleep"]?.toIntOrNull()?.let { prefs.sleepMinutes = it }
        f["accent"]?.let { a ->
            if (Colors.ACCENTS.any { it.first == a }) {
                prefs.accent = a
                Colors.apply(a)
            }
        }
        // Se fuerza recargar la lista con los datos nuevos
        Xtream.channels = emptyList()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun opt(v: String, cur: String, label: String): String {
        val sel = if (v == cur) " selected" else ""
        return "<option value='$v'$sel>$label</option>"
    }

    private fun respond(c: Socket, code: Int, html: String, type: String = "text/html; charset=utf-8", download: String? = null) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            403 -> "403 Forbidden"
            else -> "404 Not Found"
        }
        val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\n" +
            (if (download != null) "Content-Disposition: attachment; filename=\"$download\"\r\n" else "") +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun page(msg: String): String {
        val note = if (msg.isEmpty()) "" else "<div class='ok'>$msg</div>"
        val sleepOptions = listOf(0, 30, 60, 90, 120).joinToString("") {
            opt(it.toString(), prefs.sleepMinutes.toString(), if (it == 0) "Desactivado" else "$it min")
        }
        return """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Lumen IPTV</title>
<style>
body{font-family:system-ui,sans-serif;background:#0B0D12;color:#F3F4F7;margin:0;padding:24px;max-width:560px}
h1{font-size:26px}
label{display:block;margin:18px 0 6px;color:#9AA3B5;font-size:14px}
input,select{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #2B3345;background:#151922;color:#F3F4F7;font-size:17px}
button{margin-top:26px;width:100%;padding:16px;border:0;border-radius:14px;background:#F5B544;color:#1A1203;font-size:18px;font-weight:800}
.ok{background:#1D2330;padding:14px;border-radius:12px;margin-bottom:12px}
small{color:#9AA3B5}
</style></head><body>
<h1>Lumen IPTV</h1>
$note
<form method="post" action="/save">
<input type="hidden" name="k" value="$token">
<label>Servidor (http://dominio:puerto)</label>
<input name="server" value="${esc(prefs.server)}" autocapitalize="off" autocomplete="off">
<label>Usuario</label>
<input name="user" value="${esc(prefs.user)}" autocapitalize="off" autocomplete="off">
<label>Contraseña <small>(vacía = mantener la actual)</small></label>
<input name="pass" type="password" autocomplete="off">
<label>Calidad de vídeo</label>
<select name="quality">${opt("auto", prefs.quality, "Auto")}${opt("4k", prefs.quality, "4K")}${opt("1080p", prefs.quality, "Máximo 1080p")}</select>
<label>Formato del canal <small>(si un canal no carga, prueba el otro)</small></label>
<select name="format">${opt("m3u8", prefs.format, "m3u8 (HLS)")}${opt("ts", prefs.format, "ts")}</select>
<label>Color de acento del launcher</label>
<select name="accent">${Colors.ACCENTS.joinToString("") { opt(it.first, prefs.accent.uppercase(), it.second) }}</select>
<label>Temporizador de reproducción</label>
<select name="sleep">$sleepOptions</select>
<label>PITWALL <small>(dirección de tu PC, p. ej. http://192.168.0.33:3100)</small></label>
<input name="pitwall" value="${esc(prefs.pitwallUrl)}" autocapitalize="off" autocomplete="off">
<label>Avisos de F1 sobre el vídeo <small>(bandera amarilla, safety car… con tu retraso)</small></label>
<select name="alerts">${opt("1", if (prefs.pitwallAlerts) "1" else "0", "Activados")}${opt("0", if (prefs.pitwallAlerts) "1" else "0", "Desactivados")}</select>
<label>Pilotos favoritos de F1 <small>(siglas separadas por coma, p. ej. VER,ALO. Vacío = sin favoritos)</small></label>
<input name="favs" value="${esc(prefs.pitwallFavs)}" autocapitalize="characters" autocomplete="off">
<label>Panel automático <small>(se abre al empezar la sesión y se cierra al acabar)</small></label>
<select name="auto">${opt("1", if (prefs.pitwallAuto) "1" else "0", "Activado")}${opt("0", if (prefs.pitwallAuto) "1" else "0", "Desactivado")}</select>
<label>Perfiles <small>(3 nombres separados por coma; cada perfil tiene sus favoritos, canales ocultos y apps)</small></label>
<input name="profiles" value="${esc(prefs.profileNames.joinToString(","))}" autocomplete="off">
<label>PIN para categorías bloqueadas <small>(4 cifras; vacío = sin PIN)</small></label>
<input name="pin" type="password" inputmode="numeric" maxlength="6" value="${esc(prefs.pin)}" autocomplete="off">
<label>Repositorio de actualizaciones <small>(usuario/repositorio de GitHub)</small></label>
<input name="repo" value="${esc(prefs.updateRepo)}" autocapitalize="off" autocomplete="off">
<button type="submit">Guardar</button>
</form>
<h2 style="margin-top:40px;font-size:20px">Mando y diagnóstico</h2>
<a id="rl" href="#" style="display:block;text-align:center;padding:16px;border-radius:14px;background:#1D2330;color:#F3F4F7;text-decoration:none;font-weight:700">Abrir el mando del móvil (guárdalo en favoritos)</a>
<p><small>Solo funciona mientras ves un canal en el stick.</small></p>
<a href="/log?k=$token" style="display:block;text-align:center;padding:16px;border-radius:14px;background:#1D2330;color:#F3F4F7;text-decoration:none;font-weight:700">Descargar registro de errores</a>
<script>document.getElementById('rl').href='http://'+location.hostname+':8099/?k=${prefs.remoteToken}';</script>
<h2 style="margin-top:40px;font-size:20px">Copia de seguridad</h2>
<p><small>Guarda favoritos, retrasos por canal, apps ocultas y la configuración (incluida la contraseña de la lista: guárdala en un sitio privado).</small></p>
<a href="/backup?k=$token" style="display:block;text-align:center;padding:16px;border-radius:14px;background:#1D2330;color:#F3F4F7;text-decoration:none;font-weight:700">Descargar copia</a>
<form method="post" action="/restore" id="rf">
<input type="hidden" name="k" value="$token"><input type="hidden" name="data" id="rd">
<label>Restaurar desde un archivo de copia</label>
<input type="file" id="rfile" accept=".json,application/json">
</form>
<script>
document.getElementById('rfile').addEventListener('change',function(e){var f=e.target.files[0];if(!f)return;var r=new FileReader();r.onload=function(){document.getElementById('rd').value=r.result;document.getElementById('rf').submit();};r.readAsText(f);});
</script>
</body></html>"""
    }
}
