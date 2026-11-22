package com.emptysol.aeroplayer.torrent

import com.emptysol.aeroplayer.data.database.TorrentTaskEntity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class RemoteServerState(
    val isRunning: Boolean = false,
    val port: Int = 8090,
    val url: String = "",
    val requestCount: Int = 0,
    val error: String? = null
)

/**
 * Real LAN HTTP ServerSocket Remote Web UI so users can monitor, add magnets, pause,
 * resume, and remove torrents from a PC or mobile browser on the same Wi-Fi/hotspot network.
 */
class RemoteAccessServer(
    private val getTasksSnapshot: () -> List<TorrentTaskEntity>,
    private val onAddMagnetOrUrl: suspend (String) -> Unit,
    private val onPauseTorrent: suspend (String) -> Unit,
    private val onResumeTorrent: suspend (String) -> Unit,
    private val onRemoveTorrent: suspend (String) -> Unit,
    private val onStateChanged: (RemoteServerState) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var scope: CoroutineScope? = null

    @Volatile
    private var requiredPin: String = ""

    @Volatile
    private var currentPort: Int = 8090

    @Volatile
    private var requestCount: Int = 0

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.w("RemoteAccessServer", "Handled server coroutine exception: ${throwable.message}")
    }

    fun start(port: Int, pin: String) {
        stop()
        currentPort = port.coerceIn(1024, 65535)
        requiredPin = pin.trim()
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
        scope = serverScope

        serverJob = serverScope.launch {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(currentPort))
                }
                serverSocket = ss
                val localIp = findLocalIpv4Address() ?: "127.0.0.1"
                val url = "http://$localIp:${ss.localPort}"
                onStateChanged(RemoteServerState(isRunning = true, port = ss.localPort, url = url, requestCount = requestCount))

                while (isActive && !ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    serverScope.launch {
                        try {
                            handleClient(client)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: SocketTimeoutException) {
                            // Idle pre-connect or keep-alive socket timed out cleanly
                        } catch (_: IOException) {
                            // Client disconnected cleanly or reset socket
                        } catch (t: Throwable) {
                            android.util.Log.w("RemoteAccessServer", "Ignored client error: ${t.message}")
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                onStateChanged(
                    RemoteServerState(
                        isRunning = false,
                        port = currentPort,
                        url = "",
                        requestCount = requestCount,
                        error = e.message ?: "Unable to start Remote Web UI"
                    )
                )
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        scope?.cancel()
        scope = null
        onStateChanged(RemoteServerState(isRunning = false, port = currentPort, url = "", requestCount = requestCount))
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 5000
                val input = BufferedInputStream(s.getInputStream())
                val rawHeaderBytes = ByteArrayOutputStream()
                var prev4 = 0
                while (rawHeaderBytes.size() < 16384) {
                    val b = try {
                        input.read()
                    } catch (_: SocketTimeoutException) {
                        return
                    } catch (_: IOException) {
                        return
                    }
                    if (b == -1) break
                    rawHeaderBytes.write(b)
                    prev4 = (prev4 shl 8) or (b and 0xFF)
                    if (prev4 == 0x0D0A0D0A) break
                }
                if (rawHeaderBytes.size() == 0) return

                val headerText = rawHeaderBytes.toString(StandardCharsets.UTF_8.name())
                val lines = headerText.split("\r\n")
                if (lines.isEmpty()) return
                val requestLineParts = lines[0].split(" ")
                if (requestLineParts.size < 2) return
                val method = requestLineParts[0].uppercase()
                val fullTarget = requestLineParts[1]
                val path = fullTarget.substringBefore("?")
                val query = fullTarget.substringAfter("?", "")

                val headers = HashMap<String, String>()
                for (i in 1 until lines.size) {
                    val idx = lines[i].indexOf(":")
                    if (idx > 0) {
                        headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 65536) ?: 0
                val bodyBytes = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val r = try {
                        input.read(bodyBytes, totalRead, contentLength - totalRead)
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: IOException) {
                        break
                    }
                    if (r <= 0) break
                    totalRead += r
                }
                val bodyStr = String(bodyBytes, 0, totalRead, StandardCharsets.UTF_8)

                requestCount++
                val ss = serverSocket
                val localIp = findLocalIpv4Address() ?: "127.0.0.1"
                onStateChanged(
                    RemoteServerState(
                        isRunning = ss != null && !ss.isClosed,
                        port = ss?.localPort ?: currentPort,
                        url = "http://$localIp:${ss?.localPort ?: currentPort}",
                        requestCount = requestCount
                    )
                )

                // PIN / Auth verification on /api/* when PIN is configured
                val suppliedPin = headers["x-aero-pin"]
                    ?: parseFormOrQuery(query)["pin"]
                    ?: parseFormOrQuery(bodyStr)["pin"]
                    ?: ""

                if (path.startsWith("/api/") && requiredPin.isNotBlank() && suppliedPin != requiredPin) {
                    writeResponse(s, 401, "application/json", """{"error":"Unauthorized: Invalid PIN"}""")
                    return
                }

            when {
                method == "GET" && path == "/" -> {
                    writeResponse(s, 200, "text/html; charset=utf-8", buildWebUiHtml())
                }
                method == "GET" && path == "/api/status" -> {
                    val arr = JSONArray()
                    getTasksSnapshot().forEach { t ->
                        arr.put(JSONObject().apply {
                            put("infoHash", t.infoHash)
                            put("title", t.title)
                            put("status", t.status)
                            put("totalBytes", t.totalBytes)
                            put("downloadedBytes", t.downloadedBytes)
                            put("downloadRateBps", t.downloadRateBps)
                            put("uploadRateBps", t.uploadRateBps)
                            put("peerCount", t.peerCount)
                            put("seedCount", t.seedCount)
                            put("isSequential", t.isSequential)
                            put("scanStatus", t.scanStatus)
                        })
                    }
                    val root = JSONObject().apply {
                        put("requiresPin", requiredPin.isNotBlank())
                        put("torrents", arr)
                    }
                    writeResponse(s, 200, "application/json", root.toString())
                }
                method == "POST" && path == "/api/add" -> {
                    val params = parseJsonOrForm(bodyStr)
                    val uri = params["uri"] ?: parseFormOrQuery(query)["uri"] ?: ""
                    if (uri.isNotBlank()) {
                        onAddMagnetOrUrl(uri)
                        writeResponse(s, 200, "application/json", """{"ok":true}""")
                    } else {
                        writeResponse(s, 400, "application/json", """{"error":"Missing uri"}""")
                    }
                }
                method == "POST" && path == "/api/pause" -> {
                    val hash = parseJsonOrForm(bodyStr)["infoHash"] ?: parseFormOrQuery(query)["infoHash"] ?: ""
                    if (hash.isNotBlank()) onPauseTorrent(hash)
                    writeResponse(s, 200, "application/json", """{"ok":true}""")
                }
                method == "POST" && path == "/api/resume" -> {
                    val hash = parseJsonOrForm(bodyStr)["infoHash"] ?: parseFormOrQuery(query)["infoHash"] ?: ""
                    if (hash.isNotBlank()) onResumeTorrent(hash)
                    writeResponse(s, 200, "application/json", """{"ok":true}""")
                }
                method == "POST" && path == "/api/remove" -> {
                    val hash = parseJsonOrForm(bodyStr)["infoHash"] ?: parseFormOrQuery(query)["infoHash"] ?: ""
                    if (hash.isNotBlank()) onRemoveTorrent(hash)
                    writeResponse(s, 200, "application/json", """{"ok":true}""")
                }
                else -> {
                    writeResponse(s, 404, "application/json", """{"error":"Not found"}""")
                }
            }
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        } catch (_: Throwable) {
        }
    }

    private fun parseJsonOrForm(body: String): Map<String, String> {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            return try {
                val obj = JSONObject(trimmed)
                val map = HashMap<String, String>()
                obj.keys().forEach { k -> map[k] = obj.optString(k, "") }
                map
            } catch (_: Exception) {
                emptyMap()
            }
        }
        return parseFormOrQuery(trimmed)
    }

    private fun parseFormOrQuery(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        text.split("&").forEach { pair ->
            if (pair.contains("=")) {
                val k = URLDecoder.decode(pair.substringBefore("="), StandardCharsets.UTF_8.name())
                val v = URLDecoder.decode(pair.substringAfter("="), StandardCharsets.UTF_8.name())
                map[k] = v
            }
        }
        return map
    }

    private fun writeResponse(socket: Socket, code: Int, contentType: String, body: String) {
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        out.write(bodyBytes)
        out.flush()
    }

    private fun buildWebUiHtml(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width,initial-scale=1"/>
          <title>Aero Player Remote Torrent UI</title>
          <style>
            body{font-family:system-ui,-apple-system,sans-serif;background:#111318;color:#e4e6eb;margin:0;padding:20px}
            .wrap{max-width:780px;margin:0 auto}
            h1{font-size:20px;margin:0 0 12px;color:#ff8a3d}
            .card{background:#1b1f27;border:1px solid #2b313c;border-radius:14px;padding:14px;margin-bottom:12px}
            input,button{border-radius:10px;border:1px solid #353c49;padding:10px 12px;font-size:14px}
            input{background:#111318;color:#fff;width:100%;box-sizing:border-box;margin-bottom:8px}
            button{background:#ff8a3d;color:#111318;font-weight:700;cursor:pointer;border:none;margin-right:6px}
            button.sec{background:#2c3340;color:#e4e6eb}
            .meta{font-size:12px;color:#9da5b4;margin-top:6px}
            progress{width:100%;height:8px;margin-top:8px}
          </style>
        </head>
        <body>
          <div class="wrap">
            <h1>Aero Player • Remote Torrent Control</h1>
            <div class="card">
              <input id="pin" type="password" placeholder="Access PIN (if enabled)"/>
              <input id="uri" type="text" placeholder="Paste magnet:?xt=urn:btih:... or .torrent URL"/>
              <button onclick="addTorrent()">Add Torrent</button>
              <button class="sec" onclick="refresh()">Refresh</button>
            </div>
            <div id="list"></div>
          </div>
          <script>
            function hdr(){return {'Content-Type':'application/json','X-Aero-Pin':document.getElementById('pin').value||''};}
            async function addTorrent(){
              const uri=document.getElementById('uri').value.trim();
              if(!uri)return;
              await fetch('/api/add',{method:'POST',headers:hdr(),body:JSON.stringify({uri})});
              document.getElementById('uri').value='';
              refresh();
            }
            async function act(endpoint,hash){
              await fetch(endpoint,{method:'POST',headers:hdr(),body:JSON.stringify({infoHash:hash})});
              refresh();
            }
            async function refresh(){
              const r=await fetch('/api/status',{headers:hdr()});
              const el=document.getElementById('list');
              if(r.status===401){el.innerHTML='<div class="card">Enter valid PIN above to view torrents.</div>';return;}
              const data=await r.json();
              el.innerHTML=(data.torrents||[]).map(t=>{
                const pct=t.totalBytes>0?Math.round((t.downloadedBytes*100)/t.totalBytes):0;
                return '<div class="card"><strong>'+t.title+'</strong><div class="meta">'+t.status+' • '+pct+'% • Peers: '+t.peerCount+' • DL: '+Math.round(t.downloadRateBps/1024)+' KB/s</div><progress value="'+pct+'" max="100"></progress><div style="margin-top:10px"><button class="sec" onclick="act(\'/api/pause\',\''+t.infoHash+'\')">Pause</button><button onclick="act(\'/api/resume\',\''+t.infoHash+'\')">Resume</button><button class="sec" onclick="act(\'/api/remove\',\''+t.infoHash+'\')">Remove</button></div></div>';
              }).join('')||'<div class="card">No active torrents.</div>';
            }
            refresh();
            setInterval(refresh,3000);
          </script>
        </body>
        </html>
    """.trimIndent()

    companion object {
        fun findLocalIpv4Address(): String? {
            return try {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
                var candidate: String? = null
                while (ifaces.hasMoreElements()) {
                    val nif = ifaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                return ip
                            }
                            candidate = ip
                        }
                    }
                }
                candidate
            } catch (_: Exception) {
                null
            }
        }
    }
}
