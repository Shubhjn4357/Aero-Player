package com.emptysol.aeroplayer.torrent

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class NatMappingStatus(
    val listenPort: Int = 6881,
    val natMapped: Boolean = false,
    val mappingMethod: String = "None",
    val lsdActive: Boolean = false
)

/**
 * Manages:
 * 1) Real incoming BitTorrent TCP ServerSocket (ports 6881..6889) routing peers by 20-byte infoHash
 * 2) UPnP IGD (SSDP M-SEARCH + SOAP AddPortMapping) & NAT-PMP (RFC 6886) router port forwarding
 * 3) BEP-14 Local Service Discovery (LSD) multicast (239.192.152.143:6771) for full-speed LAN peers
 */
class NatAndListenerManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val started = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var lsdListenerJob: Job? = null
    private var natJob: Job? = null

    private val incomingHandlers = ConcurrentHashMap<String, (Socket, ByteArray, DataInputStream, DataOutputStream) -> Unit>()
    private val lsdPeerCallbacks = ConcurrentHashMap<String, (PeerEndpoint) -> Unit>()

    @Volatile
    var status: NatMappingStatus = NatMappingStatus()
        private set

    val listenPort: Int
        get() = status.listenPort

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val boundSocket = bindTcpListener() ?: return
        serverSocket = boundSocket
        val port = boundSocket.localPort
        status = status.copy(listenPort = port)

        // 1. Accept incoming BitTorrent peer handshakes and route by infoHash
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !boundSocket.isClosed) {
                try {
                    val client = boundSocket.accept()
                    launch(Dispatchers.IO) {
                        handleIncomingConnection(client)
                    }
                } catch (_: SocketException) {
                    break
                } catch (_: Throwable) {
                }
            }
        }

        // 2. Attempt UPnP IGD + NAT-PMP port mapping on the LAN gateway
        natJob = scope.launch(Dispatchers.IO) {
            tryNatPmpAndUpnp(port)
        }

        // 3. Start BEP-14 Local Service Discovery (LSD) listener on 239.192.152.143:6771
        lsdListenerJob = scope.launch(Dispatchers.IO) {
            runLsdListener()
        }
    }

    fun registerTorrent(
        infoHashHex: String,
        onIncomingPeer: (Socket, ByteArray, DataInputStream, DataOutputStream) -> Unit,
        onLsdPeer: (PeerEndpoint) -> Unit
    ) {
        val key = infoHashHex.lowercase()
        incomingHandlers[key] = onIncomingPeer
        lsdPeerCallbacks[key] = onLsdPeer
        if (!started.get()) start()
        scope.launch(Dispatchers.IO) {
            broadcastLsdAnnounce(key, listenPort)
        }
    }

    fun unregisterTorrent(infoHashHex: String) {
        val key = infoHashHex.lowercase()
        incomingHandlers.remove(key)
        lsdPeerCallbacks.remove(key)
    }

    fun announceLsd(infoHashHex: String) {
        scope.launch(Dispatchers.IO) {
            broadcastLsdAnnounce(infoHashHex.lowercase(), listenPort)
        }
    }

    private fun bindTcpListener(): ServerSocket? {
        for (port in 6881..6889) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (_: Throwable) {
            }
        }
        return try {
            ServerSocket(0)
        } catch (_: Throwable) {
            null
        }
    }

    private fun handleIncomingConnection(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 10_000
            val input = DataInputStream(BufferedInputStream(client.getInputStream(), 32768))
            val out = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 16384))
            val handshake = ByteArray(68)
            input.readFully(handshake)
            if (handshake[0].toInt() != 19) {
                client.close()
                return
            }
            val infoHashHex = BencodeCodec.toHex(handshake.copyOfRange(28, 48)).lowercase()
            val handler = incomingHandlers[infoHashHex]
            if (handler != null) {
                handler(client, handshake, input, out)
            } else {
                client.close()
            }
        } catch (_: Throwable) {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun tryNatPmpAndUpnp(port: Int) {
        val localIp = findLocalIpv4Address() ?: return
        // Try NAT-PMP first (fast single UDP round-trip to gateway:5351)
        if (tryNatPmp(localIp, port)) {
            status = status.copy(natMapped = true, mappingMethod = "NAT-PMP")
            return
        }
        // Fallback to UPnP IGD SSDP + SOAP AddPortMapping
        if (tryUpnpIgd(localIp, port)) {
            status = status.copy(natMapped = true, mappingMethod = "UPnP")
        }
    }

    private fun tryNatPmp(localIp: String, port: Int): Boolean {
        return try {
            val parts = localIp.split('.')
            if (parts.size != 4) return false
            val gatewayIp = "${parts[0]}.${parts[1]}.${parts[2]}.1"
            val gwAddr = InetAddress.getByName(gatewayIp)
            DatagramSocket().use { socket ->
                socket.soTimeout = 1200
                // RFC 6886: version=0, opcode=2 (TCP), reserved=0, internalPort, externalPort, lifetime=3600s
                val req = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
                    put(0.toByte())
                    put(2.toByte())
                    putShort(0)
                    putShort(port.toShort())
                    putShort(port.toShort())
                    putInt(3600)
                }.array()
                socket.send(DatagramPacket(req, req.size, gwAddr, 5351))
                val resp = ByteArray(16)
                val pkt = DatagramPacket(resp, resp.size)
                socket.receive(pkt)
                if (pkt.length >= 16) {
                    val bb = ByteBuffer.wrap(resp, 0, pkt.length).order(ByteOrder.BIG_ENDIAN)
                    bb.get() // version
                    val op = bb.get().toInt() and 0xFF
                    val resultCode = bb.short.toInt() and 0xFFFF
                    return op == 130 && resultCode == 0
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryUpnpIgd(localIp: String, port: Int): Boolean {
        return try {
            val mSearch = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)

            var locationUrl: String? = null
            DatagramSocket().use { socket ->
                socket.soTimeout = 1800
                val group = InetAddress.getByName("239.255.255.250")
                socket.send(DatagramPacket(mSearch, mSearch.size, group, 1900))
                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                val reply = String(buf, 0, pkt.length, StandardCharsets.UTF_8)
                locationUrl = reply.lineSequence()
                    .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
            }

            val descUrl = locationUrl ?: return false
            val descConn = (URL(descUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
            }
            val xml = descConn.inputStream.bufferedReader().use { it.readText() }
            descConn.disconnect()

            val serviceType = when {
                xml.contains("WANIPConnection:2") -> "urn:schemas-upnp-org:service:WANIPConnection:2"
                xml.contains("WANIPConnection:1") -> "urn:schemas-upnp-org:service:WANIPConnection:1"
                xml.contains("WANPPPConnection:1") -> "urn:schemas-upnp-org:service:WANPPPConnection:1"
                else -> return false
            }
            val serviceIdx = xml.indexOf(serviceType)
            val ctrlStart = xml.indexOf("<controlURL>", serviceIdx)
            val ctrlEnd = xml.indexOf("</controlURL>", ctrlStart)
            if (ctrlStart < 0 || ctrlEnd < 0) return false
            val controlPath = xml.substring(ctrlStart + 12, ctrlEnd).trim()
            val baseUri = URI(descUrl)
            val controlUrl = baseUri.resolve(controlPath).toURL()

            val soapBody = """
                <?xml version="1.0"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                  <s:Body>
                    <u:AddPortMapping xmlns:u="$serviceType">
                      <NewRemoteHost></NewRemoteHost>
                      <NewExternalPort>$port</NewExternalPort>
                      <NewProtocol>TCP</NewProtocol>
                      <NewInternalPort>$port</NewInternalPort>
                      <NewInternalClient>$localIp</NewInternalClient>
                      <NewEnabled>1</NewEnabled>
                      <NewPortMappingDescription>AeroPlayer P2P</NewPortMappingDescription>
                      <NewLeaseDuration>3600</NewLeaseDuration>
                    </u:AddPortMapping>
                  </s:Body>
                </s:Envelope>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)

            val soapConn = (controlUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 2500
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$serviceType#AddPortMapping\"")
                outputStream.use { it.write(soapBody) }
            }
            val code = soapConn.responseCode
            soapConn.disconnect()
            code in 200..299
        } catch (_: Throwable) {
            false
        }
    }

    private fun broadcastLsdAnnounce(infoHashHex: String, port: Int) {
        try {
            val msg = (
                "BT-SEARCH * HTTP/1.1\r\n" +
                    "Host: 239.192.152.143:6771\r\n" +
                    "Port: $port\r\n" +
                    "Infohash: ${infoHashHex.uppercase()}\r\n" +
                    "cookie: aeroplayer\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
            DatagramSocket().use { socket ->
                val group = InetAddress.getByName("239.192.152.143")
                socket.send(DatagramPacket(msg, msg.size, group, 6771))
            }
        } catch (_: Throwable) {
        }
    }

    private suspend fun runLsdListener() {
        var multicastLock: WifiManager.MulticastLock? = null
        var socket: MulticastSocket? = null
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("AeroPlayer:LsdMulticast")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val group = InetAddress.getByName("239.192.152.143")
            socket = MulticastSocket(6771).apply {
                reuseAddress = true
                soTimeout = 3000
                @Suppress("DEPRECATION")
                joinGroup(group)
            }
            status = status.copy(lsdActive = true)
            val localIp = findLocalIpv4Address()
            val buf = ByteArray(1024)
            while (currentCoroutineContext().isActive && started.get()) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val senderIp = pkt.address?.hostAddress ?: continue
                    if (senderIp == localIp) continue
                    val text = String(buf, 0, pkt.length, StandardCharsets.US_ASCII)
                    if (!text.startsWith("BT-SEARCH")) continue
                    var parsedPort = 0
                    val hashes = mutableListOf<String>()
                    text.lineSequence().forEach { line ->
                        when {
                            line.startsWith("Port:", ignoreCase = true) -> {
                                parsedPort = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                            line.startsWith("Infohash:", ignoreCase = true) -> {
                                val h = line.substringAfter(':').trim().lowercase()
                                if (h.length == 40) hashes.add(h)
                            }
                        }
                    }
                    if (parsedPort in 1..65535) {
                        hashes.forEach { hash ->
                            lsdPeerCallbacks[hash]?.invoke(PeerEndpoint(senderIp, parsedPort, "lsd"))
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // normal poll wakeup
                } catch (_: Throwable) {
                    break
                }
            }
        } catch (_: Throwable) {
        } finally {
            status = status.copy(lsdActive = false)
            try { socket?.close() } catch (_: Throwable) {}
            try { if (multicastLock?.isHeld == true) multicastLock.release() } catch (_: Throwable) {}
        }
    }

    private fun findLocalIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        acceptJob?.cancel()
        lsdListenerJob?.cancel()
        natJob?.cancel()
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        incomingHandlers.clear()
        lsdPeerCallbacks.clear()
    }
}
