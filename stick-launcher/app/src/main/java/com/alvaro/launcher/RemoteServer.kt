package com.alvaro.launcher

import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.Normalizer
import kotlin.concurrent.thread

private val accents = Regex("\\p{InCombiningDiacriticalMarks}+")

/** Texto en minúsculas y sin tildes para buscar. Los nombres sin tildes (la mayoría) no pasan por el normalizador. */
fun normText(s: String): String {
    val low = s.lowercase()
    if (low.all { it.code < 128 }) return low
    return accents.replace(Normalizer.normalize(low, Normalizer.Form.NFD), "")
}

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
            // si otro mando (p. ej. el de PITWALL sobre DAZN) aún está cerrando, se reintenta un momento
            var s: ServerSocket? = null
            for (i in 0 until 8) {
                s = try {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(PORT))
                    }
                } catch (e: Exception) {
                    null
                }
                if (s != null || !running) break
                Thread.sleep(500)
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
*{box-sizing:border-box}body{font-family:Inter,system-ui,sans-serif;background:#07080C;color:#F3F4F7;margin:0;padding:22px 18px;max-width:520px}
.top{display:flex;align-items:center;gap:10px}.top b{font-size:22px}.dot{width:26px;height:26px;border-radius:50%;border:3px solid #F5B544;display:flex;align-items:center;justify-content:center}.dot i{width:10px;height:10px;border-radius:50%;background:#F5B544}
.ok{margin-left:auto;font-size:14px;color:#7DD3A0}
#st{margin-top:14px;background:#12151D;border-radius:20px;padding:16px;font-size:16px;font-weight:600;line-height:1.4}
h3{margin:20px 0 10px;color:#A3ABBA;font-size:13px;font-weight:700;letter-spacing:.06em}
.g{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}.g2{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}
button{font:inherit;padding:16px 6px;border:0;border-radius:16px;background:#1A1F2B;color:#F3F4F7;font-size:16px;font-weight:700}
button:active,button.on{background:#F5B544;color:#1A1203}
.big{width:100%;height:150px;border-radius:28px;background:#F5B544;color:#1A1203;font-size:36px;font-weight:800;margin-bottom:10px}
.big small{display:block;font-size:14px;font-weight:600;margin-top:6px}
input{width:100%;padding:14px;border-radius:14px;border:1px solid #2B3345;background:#12151D;color:#F3F4F7;font-size:17px}
#r button{width:100%;margin-top:8px;text-align:left;padding:14px;font-weight:600;background:#12151D}
</style></head><body>
<div class="top"><div class="dot"><i></i></div><b>Mando Lumen</b><span class="ok" id="ok">Conectando…</span></div>
<div id="st">…</div>
<h3>CANAL Y VOLUMEN</h3>
<div class="g">
<button onclick="c('up')">▲ Canal</button><button onclick="c('prev')">◀ Anterior</button><button onclick="c('list')">Lista</button>
<button onclick="c('down')">▼ Canal</button><button onclick="c('vol:-')">Vol −</button><button onclick="c('vol:+')">Vol +</button>
</div>
<h3>PANEL Y RETRASO</h3>
<div class="g2"><button onclick="c('panel')">Mostrar u ocultar panel</button><button onclick="c('sync')">Sincronizar</button></div>
<div class="g" style="margin-top:10px"><button onclick="c('adj:-5')">−5 s</button><button onclick="c('adj:-1')">−1 s</button><button onclick="c('adj:1')">+1 s</button>
<button onclick="c('adj:5')">+5 s</button><button onclick="c('view:-1')">◀ Vista</button><button onclick="c('view:1')">Vista ▶</button></div>
<h3>F1 EN DAZN</h3>
<button class="big" onclick="c('vuelta')">¡Vuelta!<small>Tócalo cuando cambie el contador de vueltas de la tele</small></button>
<div class="g"><button onclick="c('salida')">Salida</button><button onclick="c('undo')">Deshacer</button><button onclick="c('stop')">Quitar PITWALL</button></div>
<div class="g" style="margin-top:10px"><button id="m_compact" onclick="c('mode:compact')">Compacto</button><button id="m_full" onclick="c('mode:full')">Completo</button><button id="m_alerts" onclick="c('mode:alerts')">Solo avisos</button></div>
<h3>BUSCAR CANAL</h3>
<input id="q" placeholder="Escribe un canal…" oninput="s()" autocomplete="off">
<div id="r"></div>
<h3>OTROS</h3>
<div class="g2"><button onclick="c('sleep')">Dormir</button><button onclick="c('mute')">Silencio</button></div>
<script>
var K=new URLSearchParams(location.search).get('k');
function c(x){if(navigator.vibrate)navigator.vibrate(30);fetch('/cmd?k='+K+'&c='+encodeURIComponent(x)).then(function(){setTimeout(st,300)});}
function st(){fetch('/state?k='+K).then(function(r){return r.text()}).then(function(t){document.getElementById('st').textContent=t;document.getElementById('ok').textContent='Conectado';
['compact','full','alerts'].forEach(function(m){var n={compact:'compacto',full:'completo',alerts:'solo avisos'}[m];document.getElementById('m_'+m).className=t.indexOf('PITWALL '+n)>=0?'on':'';});})
.catch(function(){document.getElementById('ok').textContent='Sin conexión';document.getElementById('st').textContent='No hay nada abierto en el stick que use el mando (un canal o DAZN con PITWALL).'});}
var tm;function s(){clearTimeout(tm);tm=setTimeout(function(){var v=document.getElementById('q').value;if(v.length<2){document.getElementById('r').innerHTML='';return;}
fetch('/search?k='+K+'&q='+encodeURIComponent(v)).then(function(r){return r.json()}).then(function(l){var b=document.getElementById('r');b.innerHTML='';l.forEach(function(x){var e=document.createElement('button');e.textContent=x.name;e.onclick=function(){c('play:'+x.id)};b.appendChild(e);});});},300);}
st();setInterval(st,3000);
</script></body></html>"""
    }
}
