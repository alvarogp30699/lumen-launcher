package com.alvaro.launcher

import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.Normalizer
import kotlin.concurrent.thread

fun normText(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

/**
 * Mando desde el móvil: mini servidor web que solo está activo mientras se reproduce un canal.
 * Se abre en el móvil con http://IP-DEL-STICK:8099/?k=CÓDIGO (el enlace sale en la página de configuración).
 */
class RemoteServer(
    private val token: String,
    private val onCommand: (String) -> Unit,
    private val onSearch: (String) -> String,
    private val onState: () -> String
) {
    @Volatile
    private var running = false
    private var socket: ServerSocket? = null

    fun start() {
        running = true
        thread {
            val s = try {
                ServerSocket(PORT)
            } catch (e: Exception) {
                null
            }
            socket = s
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

    private fun q(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in s.split("&")) {
            if (pair.isBlank()) continue
            val kv = pair.split("=", limit = 2)
            out[URLDecoder.decode(kv[0], "UTF-8")] = URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
        }
        return out
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 4000
            val ins = c.getInputStream().bufferedReader()
            val line = ins.readLine() ?: return
            while (true) {
                val h = ins.readLine() ?: break
                if (h.isEmpty()) break
            }
            val target = line.split(" ").getOrElse(1) { "/" }
            val path = target.substringBefore('?')
            val query = q(target.substringAfter('?', ""))
            if (query["k"] != token) {
                send(c, 403, "text/plain; charset=utf-8", "Código incorrecto")
                return
            }
            when (path) {
                "/" -> send(c, 200, "text/html; charset=utf-8", PAGE)
                "/cmd" -> {
                    onCommand(query["c"] ?: "")
                    send(c, 200, "text/plain; charset=utf-8", "ok")
                }
                "/search" -> send(c, 200, "application/json; charset=utf-8", onSearch(query["q"] ?: ""))
                "/state" -> send(c, 200, "text/plain; charset=utf-8", onState())
                else -> send(c, 404, "text/plain; charset=utf-8", "No encontrado")
            }
        } catch (e: Exception) {
            // petición rota
        } finally {
            try {
                c.close()
            } catch (e: Exception) {
                // nada
            }
        }
    }

    private fun send(c: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            403 -> "403 Forbidden"
            else -> "404 Not Found"
        }
        val out = c.getOutputStream()
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.size + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val PORT = 8099

        private val PAGE = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Mando Lumen</title>
<style>
body{font-family:system-ui,sans-serif;background:#0B0D12;color:#F3F4F7;margin:0;padding:18px;max-width:520px}
h1{font-size:22px;margin:0 0 6px}h3{margin:22px 0 8px;color:#9AA3B5;font-size:14px;font-weight:600}
#st{background:#151922;border-radius:12px;padding:12px;font-size:15px;color:#F5B544}
.g{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
button{padding:16px 6px;border:0;border-radius:14px;background:#1D2330;color:#F3F4F7;font-size:17px;font-weight:700}
button:active{background:#F5B544;color:#1A1203}
input{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #2B3345;background:#151922;color:#F3F4F7;font-size:17px}
#r button{width:100%;margin-top:8px;text-align:left;padding:14px;font-weight:600}
</style></head><body>
<h1>Mando Lumen</h1><div id="st">Conectando…</div>
<h3>CANAL Y VOLUMEN</h3>
<div class="g">
<button onclick="c('prev')">⏮ Anterior</button><button onclick="c('up')">▲ Canal +</button><button onclick="c('down')">▼ Canal −</button>
<button onclick="c('vol:-')">🔉 Vol −</button><button onclick="c('vol:+')">🔊 Vol +</button><button onclick="c('mute')">🔇 Silencio</button>
</div>
<h3>PITWALL (F1)</h3>
<div class="g">
<button onclick="c('sleep')">😴 Dormir</button><button onclick="c('panel')">Panel</button><button onclick="c('sync')">Sincronizar</button><button onclick="c('adj:0')">Retraso</button>
<button onclick="c('adj:-5')">−5 s</button><button onclick="c('adj:-1')">−1 s</button><button onclick="c('adj:1')">+1 s</button>
<button onclick="c('adj:5')">+5 s</button><button onclick="c('view:-1')">◀ Vista</button><button onclick="c('view:1')">Vista ▶</button>
</div>
<h3>BUSCAR CANAL</h3>
<input id="q" placeholder="Escribe un canal…" oninput="s()" autocomplete="off">
<div id="r"></div>
<script>
var K=new URLSearchParams(location.search).get('k');
function c(x){fetch('/cmd?k='+K+'&c='+encodeURIComponent(x)).then(function(){setTimeout(st,300)});}
function st(){fetch('/state?k='+K).then(function(r){return r.text()}).then(function(t){document.getElementById('st').textContent=t}).catch(function(){document.getElementById('st').textContent='Sin conexión con el stick (¿hay un canal en marcha?)'});}
var tm;function s(){clearTimeout(tm);tm=setTimeout(function(){var v=document.getElementById('q').value;if(v.length<2){document.getElementById('r').innerHTML='';return;}
fetch('/search?k='+K+'&q='+encodeURIComponent(v)).then(function(r){return r.json()}).then(function(l){var b=document.getElementById('r');b.innerHTML='';l.forEach(function(x){var e=document.createElement('button');e.textContent=x.name;e.onclick=function(){c('play:'+x.id)};b.appendChild(e);});});},300);}
st();setInterval(st,3000);
</script></body></html>"""
    }
}
