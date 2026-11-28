package com.emptysol.aeroplayer.torrent

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class PeerEndpoint(
    val host: String,
    val port: Int,
    val source: String = "tracker"
) {
    val key: String get() = "$host:$port"
}

data class AnnounceResult(
    val peers: List<PeerEndpoint>,
    val seeders: Int = 0,
    val leechers: Int = 0,
    val intervalSec: Int = 1800,
    val error: String? = null
)

/**
 * High-speed BitTorrent peer discovery over:
 * 1) HTTP/HTTPS Tracker Announce (BEP-3 + BEP-23 compact peers, numwant=200)
 * 2) UDP Tracker Protocol (BEP-15)
 * 3) Parallel Mainline DHT KRPC over UDP (BEP-5) with persistent routing table
 * 4) Peer Exchange ut_pex compact parser (BEP-11)
 */
class TrackerAndDhtClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    private val random = SecureRandom()

    // Persistent DHT routing table populated across queries so DHT climbs past 50+ live nodes quickly
    private val knownDhtNodes = ConcurrentHashMap<String, Pair<String, Int>>()
    private val liveDhtNodeCount = AtomicInteger(0)

    val dhtNodeCount: Int
        get() = maxOf(liveDhtNodeCount.get(), knownDhtNodes.size)

    val publicBootstrapDhtNodes = listOf(
        "router.bittorrent.com" to 6881,
        "dht.transmissionbt.com" to 6881,
        "router.utorrent.com" to 6881,
        "dht.aelitis.com" to 6881,
        "dht.libtorrent.org" to 25401,
        "router.silotis.us" to 6881
    )

    val publicFallbackTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "https://tracker.tamersunion.org:443/announce",
        "https://tracker.gbitt.info:443/announce",
        "http://tracker.opentrackr.org:1337/announce"
    )

    fun generatePeerId(): ByteArray {
        val prefix = "-AP1480-".toByteArray(StandardCharsets.US_ASCII)
        val tail = ByteArray(12)
        random.nextBytes(tail)
        return prefix + tail
    }

    fun announceTracker(
        trackerUrl: String,
        infoHashBytes: ByteArray,
        peerId: ByteArray,
        listenPort: Int,
        uploaded: Long,
        downloaded: Long,
        left: Long,
        event: String? = null
    ): AnnounceResult {
        return try {
            when {
                trackerUrl.startsWith("http://", ignoreCase = true) ||
                    trackerUrl.startsWith("https://", ignoreCase = true) -> {
                    announceHttp(trackerUrl, infoHashBytes, peerId, listenPort, uploaded, downloaded, left, event)
                }
                trackerUrl.startsWith("udp://", ignoreCase = true) -> {
                    announceUdp(trackerUrl, infoHashBytes, peerId, listenPort, uploaded, downloaded, left, event)
                }
                else -> AnnounceResult(emptyList(), error = "Unsupported tracker protocol")
            }
        } catch (e: Exception) {
            AnnounceResult(emptyList(), error = e.message ?: "Tracker error")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun announceHttp(
        trackerUrl: String,
        infoHashBytes: ByteArray,
        peerId: ByteArray,
        listenPort: Int,
        uploaded: Long,
        downloaded: Long,
        left: Long,
        event: String?
    ): AnnounceResult {
        val sep = if (trackerUrl.contains("?")) "&" else "?"
        val encodedHash = percentEncodeBinary(infoHashBytes)
        val encodedPeerId = percentEncodeBinary(peerId)
        val url = buildString {
            append(trackerUrl)
            append(sep)
            append("info_hash=").append(encodedHash)
            append("&peer_id=").append(encodedPeerId)
            append("&port=").append(listenPort)
            append("&uploaded=").append(uploaded)
            append("&downloaded=").append(downloaded)
            append("&left=").append(left.coerceAtLeast(0L))
            append("&compact=1")
            append("&numwant=200")
            if (!event.isNullOrBlank()) {
                append("&event=").append(URLEncoder.encode(event, "UTF-8"))
            }
        }

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AeroPlayer/1.4.8")
            .get()
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return AnnounceResult(emptyList(), error = "HTTP ${resp.code}")
            }
            val body = resp.body?.bytes() ?: return AnnounceResult(emptyList(), error = "Empty tracker body")
            val dict = BencodeCodec.decode(body) as? Map<String, Any?>
                ?: return AnnounceResult(emptyList(), error = "Invalid bencoded tracker reply")

            (dict["failure reason"] as? ByteArray)?.let { reason ->
                return AnnounceResult(emptyList(), error = String(reason, StandardCharsets.UTF_8))
            }

            val seeders = (dict["complete"] as? Long)?.toInt() ?: 0
            val leechers = (dict["incomplete"] as? Long)?.toInt() ?: 0
            val interval = (dict["interval"] as? Long)?.toInt() ?: 1800

            val peers = mutableListOf<PeerEndpoint>()
            when (val rawPeers = dict["peers"]) {
                is ByteArray -> peers.addAll(parseCompactPeersIpv4(rawPeers, "http-tracker"))
                is List<*> -> {
                    rawPeers.forEach { entry ->
                        val pMap = entry as? Map<String, Any?> ?: return@forEach
                        val ip = (pMap["ip"] as? ByteArray)?.let { String(it, StandardCharsets.UTF_8) }
                        val port = (pMap["port"] as? Long)?.toInt() ?: 0
                        if (!ip.isNullOrBlank() && port in 1..65535) {
                            peers.add(PeerEndpoint(ip, port, "http-tracker"))
                        }
                    }
                }
            }
            return AnnounceResult(peers = peers.distinctBy { it.key }, seeders = seeders, leechers = leechers, intervalSec = interval)
        }
    }

    private fun announceUdp(
        trackerUrl: String,
        infoHashBytes: ByteArray,
        peerId: ByteArray,
        listenPort: Int,
        uploaded: Long,
        downloaded: Long,
        left: Long,
        event: String?
    ): AnnounceResult {
        val uri = URI(trackerUrl)
        val host = uri.host ?: return AnnounceResult(emptyList(), error = "Missing UDP tracker host")
        val port = if (uri.port > 0) uri.port else 80
        val addr = InetAddress.getByName(host)

        DatagramSocket().use { socket ->
            socket.soTimeout = 2000
            val txId = random.nextInt()

            // Step 1: Connect Request (BEP-15)
            val connReq = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(0x41727101980L) // protocol_id
                putInt(0) // action = connect
                putInt(txId)
            }.array()
            socket.send(DatagramPacket(connReq, connReq.size, addr, port))

            val connRespBuf = ByteArray(64)
            val connRespPacket = DatagramPacket(connRespBuf, connRespBuf.size)
            socket.receive(connRespPacket)
            if (connRespPacket.length < 16) {
                return AnnounceResult(emptyList(), error = "Short UDP connect reply")
            }
            val connResp = ByteBuffer.wrap(connRespBuf, 0, connRespPacket.length).order(ByteOrder.BIG_ENDIAN)
            val respAction = connResp.int
            val respTx = connResp.int
            if (respAction != 0 || respTx != txId) {
                return AnnounceResult(emptyList(), error = "Invalid UDP connect handshake")
            }
            val connectionId = connResp.long

            // Step 2: Announce Request
            val annTxId = random.nextInt()
            val eventCode = when (event) {
                "completed" -> 1
                "started" -> 2
                "stopped" -> 3
                else -> 0
            }
            val annReq = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(connectionId)
                putInt(1) // action = announce
                putInt(annTxId)
                put(infoHashBytes)
                put(peerId)
                putLong(downloaded)
                putLong(left.coerceAtLeast(0L))
                putLong(uploaded)
                putInt(eventCode)
                putInt(0) // IP address default
                putInt(random.nextInt()) // key
                putInt(200) // num_want
                putShort(listenPort.toShort())
            }.array()
            socket.send(DatagramPacket(annReq, annReq.size, addr, port))

            val annRespBuf = ByteArray(2048)
            val annRespPacket = DatagramPacket(annRespBuf, annRespBuf.size)
            socket.receive(annRespPacket)
            if (annRespPacket.length < 20) {
                return AnnounceResult(emptyList(), error = "Short UDP announce reply")
            }
            val annResp = ByteBuffer.wrap(annRespBuf, 0, annRespPacket.length).order(ByteOrder.BIG_ENDIAN)
            val action = annResp.int
            val tx = annResp.int
            if (action != 1 || tx != annTxId) {
                return AnnounceResult(emptyList(), error = "UDP announce rejected (action=$action)")
            }
            val interval = annResp.int
            val leechers = annResp.int
            val seeders = annResp.int
            val peerBytes = ByteArray(annRespPacket.length - 20)
            annResp.get(peerBytes)

            return AnnounceResult(
                peers = parseCompactPeersIpv4(peerBytes, "udp-tracker").distinctBy { it.key },
                seeders = seeders,
                leechers = leechers,
                intervalSec = interval.coerceAtLeast(60)
            )
        }
    }

    /**
     * High-speed parallel BEP-5 DHT KRPC `get_peers` crawler.
     * Fires concurrent UDP waves against bootstrap routers + persistent routing table
     * so DHT climbs above 50+ live nodes within seconds without serial timeout stalls.
     */
    suspend fun queryDhtPeersParallel(
        infoHashBytes: ByteArray,
        nodeId: ByteArray,
        maxWaves: Int = 4,
        waveConcurrency: Int = 12
    ): List<PeerEndpoint> = supervisorScope {
        val discoveredPeers = ConcurrentHashMap<String, PeerEndpoint>()
        val visited = ConcurrentHashMap.newKeySet<String>()
        val frontier = ConcurrentHashMap<String, Pair<String, Int>>()

        publicBootstrapDhtNodes.forEach { (h, p) -> frontier["$h:$p"] = h to p }
        knownDhtNodes.values.take(40).forEach { (h, p) -> frontier["$h:$p"] = h to p }

        repeat(maxWaves) {
            if (discoveredPeers.size >= 250) return@repeat
            val batch = frontier.entries
                .asSequence()
                .filter { visited.add(it.key) }
                .take(waveConcurrency)
                .map { it.value }
                .toList()
            if (batch.isEmpty()) return@repeat

            val waveResults = batch.map { (host, port) ->
                async(Dispatchers.IO) {
                    querySingleDhtNode(host, port, infoHashBytes, nodeId)
                }
            }.awaitAll()

            waveResults.forEach { (peers, nextNodes) ->
                peers.forEach { p -> discoveredPeers[p.key] = p }
                nextNodes.forEach { (nh, np) ->
                    val nk = "$nh:$np"
                    if (knownDhtNodes.size < 200) {
                        knownDhtNodes[nk] = nh to np
                    }
                    if (!visited.contains(nk) && frontier.size < 160) {
                        frontier[nk] = nh to np
                    }
                }
            }
            liveDhtNodeCount.set(knownDhtNodes.size)
        }
        discoveredPeers.values.toList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun querySingleDhtNode(
        host: String,
        port: Int,
        infoHashBytes: ByteArray,
        nodeId: ByteArray
    ): Pair<List<PeerEndpoint>, List<Pair<String, Int>>> {
        val foundPeers = mutableListOf<PeerEndpoint>()
        val foundNodes = mutableListOf<Pair<String, Int>>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 900
                val txBytes = ByteArray(2).also { random.nextBytes(it) }
                val query = linkedMapOf<String, Any>(
                    "t" to txBytes,
                    "y" to "q".toByteArray(StandardCharsets.US_ASCII),
                    "q" to "get_peers".toByteArray(StandardCharsets.US_ASCII),
                    "a" to linkedMapOf(
                        "id" to nodeId,
                        "info_hash" to infoHashBytes
                    )
                )
                val payload = BencodeCodec.encode(query)
                val addr = InetAddress.getByName(host)
                socket.send(DatagramPacket(payload, payload.size, addr, port))

                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                val reply = BencodeCodec.decode(buf.copyOfRange(0, pkt.length)) as? Map<String, Any?> ?: return foundPeers to foundNodes
                val r = reply["r"] as? Map<String, Any?> ?: return foundPeers to foundNodes

                val values = r["values"] as? List<*>
                values?.forEach { entry ->
                    (entry as? ByteArray)?.let { compact ->
                        foundPeers.addAll(parseCompactPeersIpv4(compact, "dht"))
                    }
                }

                val nodesBytes = r["nodes"] as? ByteArray
                if (nodesBytes != null) {
                    var off = 0
                    while (off + 26 <= nodesBytes.size) {
                        val ipStr = "${nodesBytes[off + 20].toInt() and 0xFF}.${nodesBytes[off + 21].toInt() and 0xFF}.${nodesBytes[off + 22].toInt() and 0xFF}.${nodesBytes[off + 23].toInt() and 0xFF}"
                        val p = ((nodesBytes[off + 24].toInt() and 0xFF) shl 8) or (nodesBytes[off + 25].toInt() and 0xFF)
                        if (p in 1..65535 && ipStr != "0.0.0.0") {
                            foundNodes.add(ipStr to p)
                        }
                        off += 26
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return foundPeers to foundNodes
    }

    /**
     * BEP-11 PEX (ut_pex) compact peer list parser.
     */
    @Suppress("UNCHECKED_CAST")
    fun parsePexPayload(payload: ByteArray): List<PeerEndpoint> {
        return try {
            val map = BencodeCodec.decode(payload) as? Map<String, Any?> ?: return emptyList()
            val added = map["added"] as? ByteArray ?: return emptyList()
            parseCompactPeersIpv4(added, "pex")
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseCompactPeersIpv4(bytes: ByteArray, source: String): List<PeerEndpoint> {
        val list = mutableListOf<PeerEndpoint>()
        var idx = 0
        while (idx + 6 <= bytes.size) {
            val ip = "${bytes[idx].toInt() and 0xFF}.${bytes[idx + 1].toInt() and 0xFF}.${bytes[idx + 2].toInt() and 0xFF}.${bytes[idx + 3].toInt() and 0xFF}"
            val port = ((bytes[idx + 4].toInt() and 0xFF) shl 8) or (bytes[idx + 5].toInt() and 0xFF)
            if (port in 1..65535 && ip != "0.0.0.0") {
                list.add(PeerEndpoint(ip, port, source))
            }
            idx += 6
        }
        return list
    }

    private fun percentEncodeBinary(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '.' || c == '-' || c == '_' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%')
                sb.append("%02X".format(v))
            }
        }
        return sb.toString()
    }
}
