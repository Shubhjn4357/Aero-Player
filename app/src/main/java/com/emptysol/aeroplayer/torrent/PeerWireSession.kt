package com.emptysol.aeroplayer.torrent

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates piece selection, multi-file disk I/O (including SAF tree sync),
 * SHA-1 verification, BEP-9 metadata fetch for magnets, BEP-11 PEX, and TCP peer sessions.
 */
class TorrentDownloadCoordinator(
    private val context: Context,
    private val infoHashHex: String,
    private val infoHashBytes: ByteArray,
    private val peerId: ByteArray,
    private var initialMetainfo: TorrentMetainfo?,
    private val initialTrackers: List<String>,
    private val stagingDir: File,
    private val safTreeUriString: String,
    private val filePriorities: Map<Int, String>,
    private val isSequential: Boolean,
    private val encryptionMode: String,
    private val dhtEnabled: Boolean,
    private val pexEnabled: Boolean,
    private val seedOnlyMode: Boolean,
    private val globalDlThrottler: BandwidthThrottler,
    private val globalUlThrottler: BandwidthThrottler,
    private val taskDlThrottler: BandwidthThrottler,
    private val taskUlThrottler: BandwidthThrottler,
    private val ipFilterManager: IpFilterManager,
    private val trackerClient: TrackerAndDhtClient,
    private val onProgressUpdate: suspend (
        metainfo: TorrentMetainfo?,
        downloadedBytes: Long,
        uploadedBytes: Long,
        dlRateBps: Long,
        ulRateBps: Long,
        peerCount: Int,
        seedCount: Int,
        completedPieces: Int,
        primaryPlayableFile: File?,
        isCompleted: Boolean
    ) -> Unit
) {
    private val running = AtomicBoolean(true)
    private val peerPool = ConcurrentHashMap<String, PeerEndpoint>()
    private val activePeerCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val activeSeedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalDownloaded = AtomicLong(0L)
    private val totalUploaded = AtomicLong(0L)
    private val bytesInWindowDl = AtomicLong(0L)
    private val bytesInWindowUl = AtomicLong(0L)

    @Volatile
    private var metainfo: TorrentMetainfo? = initialMetainfo

    private val completedPieces = BitSet()
    private val inFlightPieces = ConcurrentHashMap<Int, Long>()
    private val metadataPieces = ConcurrentHashMap<Int, ByteArray>()

    @Volatile
    private var metadataTotalSize: Int = -1

    fun stop() {
        running.set(false)
    }

    suspend fun runSession() = coroutineScope {
        stagingDir.mkdirs()
        val scope = this

        // Seed initial peer list from trackers + DHT
        val discoveryJob = scope.launch(Dispatchers.IO) {
            var firstPass = true
            while (running.get() && isActive) {
                val meta = metainfo
                val trackers = ((meta?.trackers ?: emptyList()) + initialTrackers).distinct()
                val leftBytes = (meta?.totalLength ?: 0L) - totalDownloaded.get()

                trackers.take(12).forEach { trUrl ->
                    if (!running.get()) return@forEach
                    try {
                        val res = trackerClient.announceTracker(
                            trackerUrl = trUrl,
                            infoHashBytes = infoHashBytes,
                            peerId = peerId,
                            listenPort = 6881,
                            uploaded = totalUploaded.get(),
                            downloaded = totalDownloaded.get(),
                            left = leftBytes.coerceAtLeast(0L),
                            event = if (firstPass) "started" else null
                        )
                        res.peers.forEach { ep ->
                            if (!ipFilterManager.isBlocked(ep.host)) {
                                peerPool[ep.key] = ep
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    }
                }
                if (dhtEnabled) {
                    try {
                        val dhtPeers = trackerClient.queryDhtPeers(infoHashBytes, peerId)
                        dhtPeers.forEach { ep ->
                            if (!ipFilterManager.isBlocked(ep.host)) {
                                peerPool[ep.key] = ep
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    }
                }
                firstPass = false
                delay(45_000L)
            }
        }

        // Maintain concurrent peer wire connections up to 16 active workers
        val workerJobs = mutableListOf<Job>()
        val attemptedPeers = ConcurrentHashMap<String, Long>()
        val peerManagerJob = scope.launch(Dispatchers.IO) {
            while (running.get() && isActive) {
                val now = System.currentTimeMillis()
                val candidates = peerPool.values.filter { ep ->
                    val lastAttempt = attemptedPeers[ep.key] ?: 0L
                    now - lastAttempt > 20_000L && !ipFilterManager.isBlocked(ep.host)
                }.take(16 - activePeerCount.get().coerceAtLeast(0))

                for (ep in candidates) {
                    if (!running.get()) break
                    attemptedPeers[ep.key] = now
                    val j = scope.launch(Dispatchers.IO) {
                        connectPeer(ep)
                    }
                    workerJobs.add(j)
                }
                delay(1500L)
            }
        }

        // Progress reporter loop (1Hz)
        var lastTickTime = System.currentTimeMillis()
        while (running.get() && isActive) {
            delay(1000L)
            val now = System.currentTimeMillis()
            val dtSec = ((now - lastTickTime).coerceAtLeast(250L)) / 1000.0
            lastTickTime = now

            val dlBps = (bytesInWindowDl.getAndSet(0L) / dtSec).toLong()
            val ulBps = (bytesInWindowUl.getAndSet(0L) / dtSec).toLong()
            val meta = metainfo
            val totalPcs = meta?.pieceHashes?.size ?: 0
            val donePcs = completedPieces.cardinality()
            val allRequiredDone = isDownloadComplete(meta)
            val playable = findPrimaryPlayableFile(meta)

            onProgressUpdate(
                meta,
                totalDownloaded.get(),
                totalUploaded.get(),
                dlBps,
                ulBps,
                activePeerCount.get(),
                activeSeedCount.get(),
                donePcs,
                playable,
                allRequiredDone
            )

            if (allRequiredDone && meta != null) {
                syncCompletedFilesToSafIfNeeded(meta)
                break
            }
        }

        discoveryJob.cancel()
        peerManagerJob.cancel()
        workerJobs.forEach { it.cancel() }
    }

    private fun isDownloadComplete(meta: TorrentMetainfo?): Boolean {
        if (meta == null || meta.pieceHashes.isEmpty()) return false
        val requiredPieces = computeRequiredPieceSet(meta)
        if (requiredPieces.isEmpty()) return false
        for (p in requiredPieces) {
            if (!completedPieces.get(p)) return false
        }
        return true
    }

    private fun computeRequiredPieceSet(meta: TorrentMetainfo): List<Int> {
        val totalPieces = meta.pieceHashes.size
        if (totalPieces == 0) return emptyList()
        if (filePriorities.isEmpty()) {
            return (0 until totalPieces).toList()
        }
        val pieceLen = meta.pieceLength.coerceAtLeast(1L)
        val required = linkedSetOf<Int>()
        var offset = 0L
        meta.files.forEach { file ->
            val prio = filePriorities[file.index] ?: "SKIP"
            if (prio != "SKIP" && file.length > 0L) {
                val startPiece = (offset / pieceLen).toInt()
                val endPiece = ((offset + file.length - 1L) / pieceLen).toInt()
                for (p in startPiece..endPiece) {
                    if (p in 0 until totalPieces) required.add(p)
                }
            }
            offset += file.length
        }
        return required.toList()
    }

    private suspend fun connectPeer(ep: PeerEndpoint) {
        if (ipFilterManager.isBlocked(ep.host)) return
        var socket: Socket? = null
        var wasSeed = false
        var registered = false
        try {
            socket = Socket()
            socket.soTimeout = 10_000
            socket.connect(InetSocketAddress(ep.host, ep.port), 4000)

            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // Send 68-byte BitTorrent handshake (BEP-3 + BEP-10 extended + BEP-5 DHT)
            val handshake = ByteArray(68)
            handshake[0] = 19.toByte()
            "BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII).copyInto(handshake, 1)
            // Reserved bits: byte 5 bit 0x10 (BEP-10 Extension Protocol), byte 7 bit 0x01 (DHT)
            handshake[25] = 0x10.toByte()
            if (dhtEnabled) handshake[27] = 0x01.toByte()
            infoHashBytes.copyInto(handshake, 28)
            peerId.copyInto(handshake, 48)
            out.write(handshake)
            out.flush()

            val reply = ByteArray(68)
            input.readFully(reply)
            if (reply[0].toInt() != 19) return
            val remoteHash = reply.copyOfRange(28, 48)
            if (!remoteHash.contentEquals(infoHashBytes)) return

            activePeerCount.incrementAndGet()
            registered = true

            val supportsExtended = (reply[25].toInt() and 0x10) != 0
            var peerUtMetadataId: Int? = null
            var peerUtPexId: Int? = null

            if (supportsExtended) {
                sendExtendedHandshake(out)
            }

            // If we already have metainfo, send our bitfield / interested
            val peerHasPieces = BitSet()
            var peerChoked = true

            if (!seedOnlyMode) {
                sendSimpleMessage(out, 2) // interested
            }

            // Track active piece assembly per peer
            var currentPieceIdx = -1
            var currentPieceBuf = ByteArray(0)
            var currentPieceReceived = 0

            while (running.get() && !socket.isClosed) {
                val len = try {
                    input.readInt()
                } catch (_: Exception) {
                    break
                }
                if (len == 0) continue // keep-alive
                if (len < 0 || len > 4 * 1024 * 1024) break

                val msgId = input.readByte().toInt() and 0xFF
                val payloadLen = len - 1
                val payload = ByteArray(payloadLen)
                if (payloadLen > 0) {
                    input.readFully(payload)
                }

                when (msgId) {
                    0 -> peerChoked = true // choke
                    1 -> { // unchoke
                        peerChoked = false
                        if (!seedOnlyMode && metainfo != null && currentPieceIdx == -1) {
                            currentPieceIdx = pickNextPiece(peerHasPieces)
                            if (currentPieceIdx >= 0) {
                                val pLen = pieceSizeFor(metainfo!!, currentPieceIdx)
                                currentPieceBuf = ByteArray(pLen)
                                currentPieceReceived = 0
                                requestNextBlock(out, currentPieceIdx, 0, pLen)
                            }
                        }
                    }
                    4 -> { // have
                        if (payload.size >= 4) {
                            val idx = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int
                            if (idx >= 0) peerHasPieces.set(idx)
                        }
                    }
                    5 -> { // bitfield
                        for (byteIdx in payload.indices) {
                            val b = payload[byteIdx].toInt() and 0xFF
                            for (bit in 0 until 8) {
                                if ((b and (1 shl (7 - bit))) != 0) {
                                    peerHasPieces.set(byteIdx * 8 + bit)
                                }
                            }
                        }
                        val totalPcs = metainfo?.pieceHashes?.size ?: 0
                        if (!wasSeed && totalPcs > 0 && peerHasPieces.cardinality() >= totalPcs) {
                            wasSeed = true
                            activeSeedCount.incrementAndGet()
                        }
                    }
                    6 -> { // request from peer -> serve block if we have the piece
                        if (payload.size >= 12) {
                            val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                            val reqIdx = bb.int
                            val reqBegin = bb.int
                            val reqLen = bb.int
                            val meta = metainfo
                            if (meta != null && completedPieces.get(reqIdx) && reqLen in 1..32768) {
                                val block = readPieceSlice(meta, reqIdx, reqBegin, reqLen)
                                if (block != null) {
                                    globalUlThrottler.acquire(block.size)
                                    taskUlThrottler.acquire(block.size)
                                    sendPieceBlock(out, reqIdx, reqBegin, block)
                                    totalUploaded.addAndGet(block.size.toLong())
                                    bytesInWindowUl.addAndGet(block.size.toLong())
                                }
                            }
                        }
                    }
                    7 -> { // piece block
                        if (payload.size >= 8 && metainfo != null) {
                            val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                            val pIdx = bb.int
                            val pBegin = bb.int
                            val dataLen = payload.size - 8
                            globalDlThrottler.acquire(dataLen)
                            taskDlThrottler.acquire(dataLen)
                            totalDownloaded.addAndGet(dataLen.toLong())
                            bytesInWindowDl.addAndGet(dataLen.toLong())

                            if (pIdx == currentPieceIdx && pBegin + dataLen <= currentPieceBuf.size) {
                                System.arraycopy(payload, 8, currentPieceBuf, pBegin, dataLen)
                                currentPieceReceived += dataLen
                                val meta = metainfo!!
                                if (currentPieceReceived >= currentPieceBuf.size) {
                                    if (verifyAndWritePiece(meta, pIdx, currentPieceBuf)) {
                                        completedPieces.set(pIdx)
                                    }
                                    inFlightPieces.remove(pIdx)
                                    currentPieceIdx = -1
                                    if (!peerChoked && !seedOnlyMode) {
                                        currentPieceIdx = pickNextPiece(peerHasPieces)
                                        if (currentPieceIdx >= 0) {
                                            val pLen = pieceSizeFor(meta, currentPieceIdx)
                                            currentPieceBuf = ByteArray(pLen)
                                            currentPieceReceived = 0
                                            requestNextBlock(out, currentPieceIdx, 0, pLen)
                                        }
                                    }
                                } else {
                                    requestNextBlock(out, currentPieceIdx, pBegin + dataLen, currentPieceBuf.size)
                                }
                            }
                        }
                    }
                    20 -> { // BEP-10 extended message
                        if (payload.isNotEmpty()) {
                            val extId = payload[0].toInt() and 0xFF
                            val extBody = payload.copyOfRange(1, payload.size)
                            if (extId == 0) {
                                handleExtendedHandshakeReply(extBody, onUtMetadataId = { peerUtMetadataId = it }, onUtPexId = { peerUtPexId = it })
                                if (metainfo == null && peerUtMetadataId != null && metadataTotalSize > 0) {
                                    val piecesNeeded = (metadataTotalSize + 16383) / 16384
                                    for (i in 0 until piecesNeeded) {
                                        sendUtMetadataRequest(out, peerUtMetadataId!!, i)
                                    }
                                }
                            } else if (extId == 1 && pexEnabled) {
                                trackerClient.parsePexPayload(extBody).forEach { discovered ->
                                    if (!ipFilterManager.isBlocked(discovered.host)) {
                                        peerPool[discovered.key] = discovered
                                    }
                                }
                            } else if (extId == 2 && metainfo == null) {
                                handleUtMetadataData(extBody)
                            }
                        }
                    }
                }

                // Request next piece if unchoked and idle
                if (!peerChoked && !seedOnlyMode && metainfo != null && currentPieceIdx == -1) {
                    currentPieceIdx = pickNextPiece(peerHasPieces)
                    if (currentPieceIdx >= 0) {
                        val pLen = pieceSizeFor(metainfo!!, currentPieceIdx)
                        currentPieceBuf = ByteArray(pLen)
                        currentPieceReceived = 0
                        requestNextBlock(out, currentPieceIdx, 0, pLen)
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Peer connection closed, timed out, or malformed frame
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            if (registered) activePeerCount.decrementAndGet()
            if (wasSeed) activeSeedCount.decrementAndGet()
        }
    }

    @Synchronized
    private fun pickNextPiece(peerHas: BitSet): Int {
        val meta = metainfo ?: return -1
        val total = meta.pieceHashes.size
        if (total == 0) return -1
        val now = System.currentTimeMillis()
        inFlightPieces.entries.removeIf { now - it.value > 25_000L }

        val required = computeRequiredPieceSet(meta)
        val candidates = required.filter { p ->
            !completedPieces.get(p) && !inFlightPieces.containsKey(p) && (peerHas.isEmpty || peerHas.get(p))
        }
        if (candidates.isEmpty()) return -1

        // Weight pieces by file priority ("HIGH" > "NORMAL" > "LOW") while respecting sequential order
        val chosen = if (isSequential) {
            candidates.minByOrNull { piecePriorityRank(meta, it) * 1_000_000L + it } ?: candidates.first()
        } else {
            val highOnly = candidates.filter { piecePriorityRank(meta, it) == 0 }
            if (highOnly.isNotEmpty()) highOnly.random() else candidates.random()
        }
        inFlightPieces[chosen] = now
        return chosen
    }

    private fun piecePriorityRank(meta: TorrentMetainfo, pieceIdx: Int): Int {
        val pieceStart = pieceIdx.toLong() * meta.pieceLength
        var cursor = 0L
        var bestRank = 1 // NORMAL
        meta.files.forEach { file ->
            val end = cursor + file.length
            if (pieceStart < end && pieceStart + meta.pieceLength > cursor) {
                val prio = if (filePriorities.isEmpty()) file.priority else (filePriorities[file.index] ?: "SKIP")
                val rank = when (prio) {
                    "HIGH" -> 0
                    "LOW" -> 2
                    "SKIP" -> 3
                    else -> 1
                }
                if (rank < bestRank) bestRank = rank
            }
            cursor = end
        }
        return bestRank
    }

    private fun pieceSizeFor(meta: TorrentMetainfo, pieceIdx: Int): Int {
        val start = pieceIdx.toLong() * meta.pieceLength
        val remaining = (meta.totalLength - start).coerceAtLeast(0L)
        return minOf(meta.pieceLength, remaining).toInt()
    }

    private fun verifyAndWritePiece(meta: TorrentMetainfo, pieceIdx: Int, bytes: ByteArray): Boolean {
        val expectedHash = meta.pieceHashes.getOrNull(pieceIdx) ?: return false
        val actualHash = MessageDigest.getInstance("SHA-1").digest(bytes)
        if (!actualHash.contentEquals(expectedHash)) return false

        val pieceOffset = pieceIdx.toLong() * meta.pieceLength
        val pieceEnd = pieceOffset + bytes.size
        var fileStart = 0L
        for (fileEntry in meta.files) {
            val fileEnd = fileStart + fileEntry.length
            val overlapStart = maxOf(pieceOffset, fileStart)
            val overlapEnd = minOf(pieceEnd, fileEnd)
            if (overlapStart < overlapEnd) {
                val prio = if (filePriorities.isEmpty()) fileEntry.priority else (filePriorities[fileEntry.index] ?: "SKIP")
                if (prio != "SKIP") {
                    val destFile = File(stagingDir, fileEntry.path)
                    destFile.parentFile?.mkdirs()
                    RandomAccessFile(destFile, "rw").use { raf ->
                        raf.seek(overlapStart - fileStart)
                        raf.write(
                            bytes,
                            (overlapStart - pieceOffset).toInt(),
                            (overlapEnd - overlapStart).toInt()
                        )
                    }
                }
            }
            fileStart = fileEnd
        }
        return true
    }

    private fun readPieceSlice(meta: TorrentMetainfo, pieceIdx: Int, begin: Int, len: Int): ByteArray? {
        val absStart = pieceIdx.toLong() * meta.pieceLength + begin
        val result = ByteArray(len)
        var written = 0
        var fileStart = 0L
        for (fileEntry in meta.files) {
            val fileEnd = fileStart + fileEntry.length
            val overlapStart = maxOf(absStart, fileStart)
            val overlapEnd = minOf(absStart + len, fileEnd)
            if (overlapStart < overlapEnd) {
                val srcFile = File(stagingDir, fileEntry.path)
                if (!srcFile.exists()) return null
                val chunkLen = (overlapEnd - overlapStart).toInt()
                RandomAccessFile(srcFile, "r").use { raf ->
                    raf.seek(overlapStart - fileStart)
                    raf.readFully(result, written, chunkLen)
                }
                written += chunkLen
            }
            fileStart = fileEnd
        }
        return if (written == len) result else null
    }

    private fun findPrimaryPlayableFile(meta: TorrentMetainfo?): File? {
        val entries = meta?.files ?: return null
        val mediaExts = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "mp3", "flac", "aac", "m4a", "ogg", "opus")
        val preferred = entries
            .filter {
                val prio = if (filePriorities.isEmpty()) it.priority else (filePriorities[it.index] ?: "SKIP")
                prio != "SKIP"
            }
            .sortedByDescending { entry ->
                val ext = entry.path.substringAfterLast('.', "").lowercase()
                if (ext in mediaExts) entry.length + 1_000_000_000L else entry.length
            }
            .firstOrNull() ?: return null
        val file = File(stagingDir, preferred.path)
        return if (file.exists() && file.length() > 0L) file else null
    }

    private fun syncCompletedFilesToSafIfNeeded(meta: TorrentMetainfo) {
        if (!safTreeUriString.startsWith("content://")) return
        try {
            val treeUri = Uri.parse(safTreeUriString)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            meta.files.forEach { fileEntry ->
                val prio = if (filePriorities.isEmpty()) fileEntry.priority else (filePriorities[fileEntry.index] ?: "SKIP")
                if (prio == "SKIP") return@forEach
                val src = File(stagingDir, fileEntry.path)
                if (!src.exists() || !src.isFile) return@forEach
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    rootDocUri,
                    "application/octet-stream",
                    src.name
                ) ?: return@forEach
                context.contentResolver.openOutputStream(createdUri)?.use { out ->
                    FileInputStream(src).use { input ->
                        input.copyTo(out)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("TorrentSAF", "SAF tree sync skipped: ${e.message}")
        }
    }

    private fun sendSimpleMessage(out: DataOutputStream, id: Int) {
        val buf = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).putInt(1).put(id.toByte()).array()
        out.write(buf)
        out.flush()
    }

    private fun requestNextBlock(out: DataOutputStream, pieceIdx: Int, offset: Int, pieceSize: Int) {
        val blockLen = minOf(16384, pieceSize - offset)
        if (blockLen <= 0) return
        val buf = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN)
            .putInt(13)
            .put(6.toByte())
            .putInt(pieceIdx)
            .putInt(offset)
            .putInt(blockLen)
            .array()
        out.write(buf)
        out.flush()
    }

    private fun sendPieceBlock(out: DataOutputStream, pieceIdx: Int, begin: Int, data: ByteArray) {
        val buf = ByteBuffer.allocate(13 + data.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(9 + data.size)
            .put(7.toByte())
            .putInt(pieceIdx)
            .putInt(begin)
            .put(data)
            .array()
        out.write(buf)
        out.flush()
    }

    private fun sendExtendedHandshake(out: DataOutputStream) {
        val handshakeDict = linkedMapOf<String, Any>(
            "m" to linkedMapOf(
                "ut_pex" to 1L,
                "ut_metadata" to 2L
            ),
            "v" to "AeroPlayer 1.4.6".toByteArray(StandardCharsets.UTF_8),
            "reqq" to 64L
        )
        val encoded = BencodeCodec.encode(handshakeDict)
        val msg = ByteBuffer.allocate(6 + encoded.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(2 + encoded.size)
            .put(20.toByte())
            .put(0.toByte())
            .put(encoded)
            .array()
        out.write(msg)
        out.flush()
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleExtendedHandshakeReply(
        body: ByteArray,
        onUtMetadataId: (Int?) -> Unit,
        onUtPexId: (Int?) -> Unit
    ) {
        try {
            val map = BencodeCodec.decode(body) as? Map<String, Any?> ?: return
            val m = map["m"] as? Map<String, Any?>
            onUtMetadataId((m?.get("ut_metadata") as? Long)?.toInt())
            onUtPexId((m?.get("ut_pex") as? Long)?.toInt())
            (map["metadata_size"] as? Long)?.toInt()?.let { metadataTotalSize = it }
        } catch (_: Exception) {
        }
    }

    private fun sendUtMetadataRequest(out: DataOutputStream, remoteId: Int, pieceIndex: Int) {
        val reqDict = linkedMapOf<String, Any>(
            "msg_type" to 0L,
            "piece" to pieceIndex.toLong()
        )
        val encoded = BencodeCodec.encode(reqDict)
        val msg = ByteBuffer.allocate(6 + encoded.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(2 + encoded.size)
            .put(20.toByte())
            .put(remoteId.toByte())
            .put(encoded)
            .array()
        out.write(msg)
        out.flush()
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleUtMetadataData(body: ByteArray) {
        try {
            // Decode first bencoded dictionary, remaining bytes are metadata piece payload
            var dictEnd = 0
            // Parse via BencodeCodec slice by finding end after balanced d...e
            val decoded = BencodeCodec.decode(body) as? Map<String, Any?> ?: return
            val msgType = (decoded["msg_type"] as? Long)?.toInt() ?: return
            if (msgType != 1) return
            val pieceIdx = (decoded["piece"] as? Long)?.toInt() ?: return
            val reEncoded = BencodeCodec.encode(decoded)
            dictEnd = reEncoded.size
            if (dictEnd >= body.size) return
            val pieceBytes = body.copyOfRange(dictEnd, body.size)
            metadataPieces[pieceIdx] = pieceBytes

            val totalSize = metadataTotalSize
            if (totalSize > 0) {
                val expectedPieces = (totalSize + 16383) / 16384
                if (metadataPieces.size >= expectedPieces) {
                    val assembled = ByteArray(totalSize)
                    var pos = 0
                    for (i in 0 until expectedPieces) {
                        val chunk = metadataPieces[i] ?: return
                        val copyLen = minOf(chunk.size, totalSize - pos)
                        System.arraycopy(chunk, 0, assembled, pos, copyLen)
                        pos += copyLen
                    }
                    if (BencodeCodec.sha1(assembled).contentEquals(infoHashBytes)) {
                        metainfo = TorrentMetainfoParser.parseInfoDictionary(infoHashBytes, assembled)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
