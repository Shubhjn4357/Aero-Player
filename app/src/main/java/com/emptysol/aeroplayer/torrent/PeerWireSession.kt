package com.emptysol.aeroplayer.torrent

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val BLOCK_SIZE = 16384

private data class PendingPieceWrite(
    val meta: TorrentMetainfo,
    val pieceIdx: Int,
    val bytes: ByteArray
)

private class ActivePieceAssembly(
    val pieceIdx: Int,
    val size: Int
) {
    val buffer = ByteArray(size)
    val blockCount: Int = (size + BLOCK_SIZE - 1) / BLOCK_SIZE
    val receivedBlocks = BitSet(blockCount)
    var nextRequestOffset: Int = 0
    var receivedBytes: Int = 0
}

/**
 * High-throughput multi-threaded BitTorrent coordinator featuring:
 * - Adaptive deep BEP-3 block pipelining (up to 64 blocks / 1 MB in flight per peer in Speed Boost)
 * - Batched TCP writes over tuned 512 KB receive / 256 KB send sockets with TCP_NODELAY
 * - Parallel HTTP/UDP tracker + DHT + BEP-11 PEX peer discovery
 * - Offloaded multi-core SHA-1 verification + pooled NIO FileChannel positional disk writes
 * - Endgame racing on the final tail pieces + instant piece release on choke/disconnect
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
    private val speedBoostEnabled: Boolean = true,
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
    private val activePeerKeys = ConcurrentHashMap.newKeySet<String>()
    private val activeWorkerJobs = ConcurrentHashMap<String, Job>()
    private val activePeerCount = AtomicInteger(0)
    private val activeSeedCount = AtomicInteger(0)
    private val totalDownloaded = AtomicLong(0L)
    private val totalUploaded = AtomicLong(0L)
    private val bytesInWindowDl = AtomicLong(0L)
    private val bytesInWindowUl = AtomicLong(0L)

    @Volatile
    private var metainfo: TorrentMetainfo? = initialMetainfo

    private val pieceStateLock = Any()
    private val completedPieces = BitSet()
    private val inFlightPieces = ConcurrentHashMap<Int, Long>()
    private val metadataPieces = ConcurrentHashMap<Int, ByteArray>()

    // Cached piece requirements & priority ranks to avoid per-pick heap churn
    @Volatile
    private var cachedRequiredMetaRef: TorrentMetainfo? = null
    @Volatile
    private var cachedRequiredPieces: IntArray = IntArray(0)
    @Volatile
    private var cachedPiecePriorityRanks: IntArray = IntArray(0)

    // Pooled NIO FileChannels for zero-open-overhead concurrent disk I/O
    private val openFilesPool = ConcurrentHashMap<String, RandomAccessFile>()

    @Volatile
    private var metadataTotalSize: Int = -1

    private val maxConcurrentPeers: Int = if (speedBoostEnabled) 72 else 48
    private val maxPipelineDepth: Int = if (speedBoostEnabled) 64 else 32
    private val maxActivePiecesPerPeer: Int = if (speedBoostEnabled) 3 else 2
    private val socketRecvBufBytes: Int = if (speedBoostEnabled) 512 * 1024 else 256 * 1024
    private val diskWriterWorkers: Int = if (speedBoostEnabled) 4 else 2

    fun stop() {
        running.set(false)
    }

    suspend fun runSession() = coroutineScope {
        stagingDir.mkdirs()
        val scope = this
        val diskWriteChannel = Channel<PendingPieceWrite>(capacity = if (speedBoostEnabled) 64 else 32)

        // Multi-threaded SHA-1 verification + NIO FileChannel disk writers
        val writerJobs = (0 until diskWriterWorkers).map {
            scope.launch(Dispatchers.IO) {
                for (pending in diskWriteChannel) {
                    try {
                        val alreadyDone = synchronized(pieceStateLock) { completedPieces.get(pending.pieceIdx) }
                        if (alreadyDone) {
                            inFlightPieces.remove(pending.pieceIdx)
                            continue
                        }
                        val verified = verifyPieceHashAsync(pending.meta, pending.pieceIdx, pending.bytes)
                        if (verified) {
                            writePieceToDiskPooled(pending.meta, pending.pieceIdx, pending.bytes)
                            synchronized(pieceStateLock) {
                                completedPieces.set(pending.pieceIdx)
                            }
                        }
                        inFlightPieces.remove(pending.pieceIdx)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        inFlightPieces.remove(pending.pieceIdx)
                    }
                }
            }
        }

        // Parallel tracker + DHT peer discovery loop
        val discoveryJob = scope.launch(Dispatchers.IO) {
            var firstPass = true
            while (running.get() && isActive) {
                val meta = metainfo
                val baseTrackers = ((meta?.trackers ?: emptyList()) + initialTrackers).distinct()
                val trackers = if (baseTrackers.size < 4) {
                    (baseTrackers + trackerClient.publicFallbackTrackers).distinct()
                } else {
                    baseTrackers
                }
                val leftBytes = ((meta?.totalLength ?: 0L) - totalDownloaded.get()).coerceAtLeast(0L)
                val event = if (firstPass) "started" else null

                supervisorScope {
                    val announceJobs = trackers.take(20).map { trUrl ->
                        async(Dispatchers.IO) {
                            if (!running.get()) return@async
                            try {
                                val res = trackerClient.announceTracker(
                                    trackerUrl = trUrl,
                                    infoHashBytes = infoHashBytes,
                                    peerId = peerId,
                                    listenPort = 6881,
                                    uploaded = totalUploaded.get(),
                                    downloaded = totalDownloaded.get(),
                                    left = leftBytes,
                                    event = event
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
                    }

                    val dhtDeferred = if (dhtEnabled) {
                        async(Dispatchers.IO) {
                            if (!running.get()) return@async
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
                    } else null

                    announceJobs.awaitAll()
                    dhtDeferred?.await()
                }

                firstPass = false
                val nextDelayMs = if (activePeerCount.get() < 10) 15_000L else 35_000L
                delay(nextDelayMs)
            }
        }

        // Fast wave-based peer connection manager (no leaked Job references)
        val attemptedPeers = ConcurrentHashMap<String, Long>()
        val peerManagerJob = scope.launch(Dispatchers.IO) {
            while (running.get() && isActive) {
                val now = System.currentTimeMillis()
                val availableSlots = (maxConcurrentPeers - activeWorkerJobs.size).coerceAtLeast(0)
                if (availableSlots > 0 && peerPool.isNotEmpty()) {
                    val waveLimit = minOf(availableSlots, if (speedBoostEnabled) 20 else 12)
                    val candidates = peerPool.values
                        .asSequence()
                        .filter { ep ->
                            !activePeerKeys.contains(ep.key) &&
                                (now - (attemptedPeers[ep.key] ?: 0L) > 15_000L) &&
                                !ipFilterManager.isBlocked(ep.host)
                        }
                        .sortedBy { attemptedPeers[it.key] ?: 0L }
                        .take(waveLimit)
                        .toList()

                    for (ep in candidates) {
                        if (!running.get()) break
                        if (!activePeerKeys.add(ep.key)) continue
                        attemptedPeers[ep.key] = now
                        val workerJob = scope.launch(Dispatchers.IO) {
                            try {
                                connectPeer(ep, diskWriteChannel)
                            } finally {
                                activePeerKeys.remove(ep.key)
                                activeWorkerJobs.remove(ep.key)
                            }
                        }
                        activeWorkerJobs[ep.key] = workerJob
                    }
                }
                delay(if (activePeerCount.get() < 8) 300L else 800L)
            }
        }

        try {
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
                val donePcs = synchronized(pieceStateLock) { completedPieces.cardinality() }
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
                    flushAllOpenFiles()
                    syncCompletedFilesToSafIfNeeded(meta)
                    break
                }
            }
        } finally {
            running.set(false)
            discoveryJob.cancel()
            peerManagerJob.cancel()
            activeWorkerJobs.values.forEach { it.cancel() }
            activeWorkerJobs.clear()
            diskWriteChannel.close()
            withContext(NonCancellable) {
                writerJobs.forEach {
                    withTimeoutOrNull(1500L) { it.join() }
                    it.cancel()
                }
                closeAllOpenFiles()
            }
        }
    }

    private fun ensurePieceCache(meta: TorrentMetainfo) {
        if (cachedRequiredMetaRef === meta) return
        synchronized(pieceStateLock) {
            if (cachedRequiredMetaRef === meta) return
            val totalPieces = meta.pieceHashes.size
            if (totalPieces == 0) {
                cachedRequiredPieces = IntArray(0)
                cachedPiecePriorityRanks = IntArray(0)
                cachedRequiredMetaRef = meta
                return
            }

            val pieceLen = meta.pieceLength.coerceAtLeast(1L)
            val ranks = IntArray(totalPieces) { 3 } // default SKIP
            val requiredFlags = BooleanArray(totalPieces)

            if (filePriorities.isEmpty()) {
                var offset = 0L
                meta.files.forEach { file ->
                    val rank = priorityToRank(file.priority)
                    if (file.length > 0L) {
                        val startPiece = (offset / pieceLen).toInt().coerceIn(0, totalPieces - 1)
                        val endPiece = ((offset + file.length - 1L) / pieceLen).toInt().coerceIn(0, totalPieces - 1)
                        for (p in startPiece..endPiece) {
                            requiredFlags[p] = true
                            if (rank < ranks[p]) ranks[p] = rank
                        }
                    }
                    offset += file.length
                }
                // Fallback if files list was empty
                if (meta.files.isEmpty()) {
                    for (p in 0 until totalPieces) {
                        requiredFlags[p] = true
                        ranks[p] = 1
                    }
                }
            } else {
                var offset = 0L
                meta.files.forEach { file ->
                    val prio = filePriorities[file.index] ?: "SKIP"
                    if (prio != "SKIP" && file.length > 0L) {
                        val rank = priorityToRank(prio)
                        val startPiece = (offset / pieceLen).toInt().coerceIn(0, totalPieces - 1)
                        val endPiece = ((offset + file.length - 1L) / pieceLen).toInt().coerceIn(0, totalPieces - 1)
                        for (p in startPiece..endPiece) {
                            requiredFlags[p] = true
                            if (rank < ranks[p]) ranks[p] = rank
                        }
                    }
                    offset += file.length
                }
            }

            var count = 0
            for (flag in requiredFlags) if (flag) count++
            val reqArr = IntArray(count)
            var idx = 0
            for (p in 0 until totalPieces) {
                if (requiredFlags[p]) reqArr[idx++] = p
            }

            cachedPiecePriorityRanks = ranks
            cachedRequiredPieces = reqArr
            cachedRequiredMetaRef = meta
        }
    }

    private fun priorityToRank(prio: String): Int = when (prio) {
        "HIGH" -> 0
        "LOW" -> 2
        "SKIP" -> 3
        else -> 1
    }

    private fun isDownloadComplete(meta: TorrentMetainfo?): Boolean {
        if (meta == null || meta.pieceHashes.isEmpty()) return false
        ensurePieceCache(meta)
        val required = cachedRequiredPieces
        if (required.isEmpty()) return false
        synchronized(pieceStateLock) {
            for (p in required) {
                if (!completedPieces.get(p)) return false
            }
        }
        return true
    }

    private suspend fun connectPeer(
        ep: PeerEndpoint,
        diskWriteChannel: Channel<PendingPieceWrite>
    ) {
        if (ipFilterManager.isBlocked(ep.host)) return
        var socket: Socket? = null
        var wasSeed = false
        var registered = false
        val activePieces = LinkedHashMap<Int, ActivePieceAssembly>()
        val inFlightBlocks = HashMap<Long, Long>()

        try {
            socket = Socket().apply {
                tcpNoDelay = true
                receiveBufferSize = socketRecvBufBytes
                sendBufferSize = 256 * 1024
                soTimeout = 12_000
                keepAlive = true
            }
            socket.connect(InetSocketAddress(ep.host, ep.port), 2500)

            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 65536))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536))

            // Send 68-byte BitTorrent handshake (BEP-3 + BEP-10 extended + BEP-5 DHT)
            val handshake = ByteArray(68)
            handshake[0] = 19.toByte()
            "BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII).copyInto(handshake, 1)
            handshake[25] = 0x10.toByte()
            if (dhtEnabled) handshake[27] = 0x01.toByte()
            // Preserve protocol encryption preference flag for future MSE negotiation
            if (encryptionMode.equals("FORCE", ignoreCase = true)) {
                handshake[20] = 0x00.toByte()
            }
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

            val peerHasPieces = BitSet()
            var peerChoked = true
            var targetPipelineDepth = if (speedBoostEnabled) 24 else 16
            var remoteReqq = maxPipelineDepth

            if (!seedOnlyMode) {
                sendSimpleMessage(out, 2) // interested
            }

            val reqScratch = ByteArray(17)

            fun fillPeerPipeline() {
                val meta = metainfo ?: return
                if (peerChoked || seedOnlyMode) return
                val effectiveDepth = minOf(targetPipelineDepth, remoteReqq, maxPipelineDepth)
                var queuedRequests = 0

                // Prune any timed-out in-flight block requests (> 6s)
                val now = System.currentTimeMillis()
                if (inFlightBlocks.isNotEmpty()) {
                    val staleIter = inFlightBlocks.entries.iterator()
                    while (staleIter.hasNext()) {
                        if (now - staleIter.next().value > 6_000L) {
                            staleIter.remove()
                        }
                    }
                }

                while (inFlightBlocks.size < effectiveDepth) {
                    // Find an active piece that still has unrequested blocks
                    var targetAssembly: ActivePieceAssembly? = null
                    for (assembly in activePieces.values) {
                        if (assembly.nextRequestOffset < assembly.size) {
                            targetAssembly = assembly
                            break
                        }
                    }

                    // Otherwise acquire a new piece up to maxActivePiecesPerPeer
                    if (targetAssembly == null) {
                        if (activePieces.size >= maxActivePiecesPerPeer) break
                        val nextIdx = pickNextPiece(peerHasPieces, activePieces.keys)
                        if (nextIdx < 0) break
                        val pLen = pieceSizeFor(meta, nextIdx)
                        if (pLen <= 0) {
                            inFlightPieces.remove(nextIdx)
                            break
                        }
                        targetAssembly = ActivePieceAssembly(nextIdx, pLen)
                        activePieces[nextIdx] = targetAssembly
                    }

                    val offset = targetAssembly.nextRequestOffset
                    val blockLen = minOf(BLOCK_SIZE, targetAssembly.size - offset)
                    if (blockLen <= 0) break
                    targetAssembly.nextRequestOffset += blockLen

                    val blockKey = (targetAssembly.pieceIdx.toLong() shl 32) or (offset.toLong() and 0xFFFFFFFFL)
                    if (inFlightBlocks.containsKey(blockKey)) continue
                    inFlightBlocks[blockKey] = now

                    writeRequestFrame(out, reqScratch, targetAssembly.pieceIdx, offset, blockLen)
                    queuedRequests++
                }

                if (queuedRequests > 0) {
                    out.flush()
                }
            }

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
                    0 -> { // choke: release active pieces so faster peers pick them immediately
                        peerChoked = true
                        inFlightBlocks.clear()
                        activePieces.keys.forEach { inFlightPieces.remove(it) }
                        activePieces.clear()
                        targetPipelineDepth = if (speedBoostEnabled) 16 else 12
                    }
                    1 -> { // unchoke
                        peerChoked = false
                        fillPeerPipeline()
                    }
                    4 -> { // have
                        if (payload.size >= 4) {
                            val idx = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int
                            if (idx >= 0) peerHasPieces.set(idx)
                        }
                        if (!peerChoked) fillPeerPipeline()
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
                        if (!peerChoked) fillPeerPipeline()
                    }
                    6 -> { // request from peer -> serve block if we have the piece
                        if (payload.size >= 12) {
                            val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                            val reqIdx = bb.int
                            val reqBegin = bb.int
                            val reqLen = bb.int
                            val meta = metainfo
                            val hasPiece = synchronized(pieceStateLock) { completedPieces.get(reqIdx) }
                            if (meta != null && hasPiece && reqLen in 1..32768) {
                                val block = readPieceSlicePooled(meta, reqIdx, reqBegin, reqLen)
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
                        val meta = metainfo
                        if (payload.size >= 8 && meta != null) {
                            val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                            val pIdx = bb.int
                            val pBegin = bb.int
                            val dataLen = payload.size - 8

                            globalDlThrottler.acquire(dataLen)
                            taskDlThrottler.acquire(dataLen)
                            totalDownloaded.addAndGet(dataLen.toLong())
                            bytesInWindowDl.addAndGet(dataLen.toLong())

                            val blockKey = (pIdx.toLong() shl 32) or (pBegin.toLong() and 0xFFFFFFFFL)
                            inFlightBlocks.remove(blockKey)

                            // Smoothly ramp pipeline depth up to maxPipelineDepth on healthy flow
                            if (targetPipelineDepth < maxPipelineDepth) {
                                targetPipelineDepth = (targetPipelineDepth + 1).coerceAtMost(maxPipelineDepth)
                            }

                            val assembly = activePieces[pIdx]
                            if (assembly != null && pBegin >= 0 && pBegin + dataLen <= assembly.size) {
                                val blockIdx = pBegin / BLOCK_SIZE
                                if (blockIdx in 0 until assembly.blockCount && !assembly.receivedBlocks.get(blockIdx)) {
                                    System.arraycopy(payload, 8, assembly.buffer, pBegin, dataLen)
                                    assembly.receivedBlocks.set(blockIdx)
                                    assembly.receivedBytes += dataLen
                                }
                                // Keep piece timestamp fresh while actively receiving blocks
                                inFlightPieces[pIdx] = System.currentTimeMillis()

                                if (assembly.receivedBytes >= assembly.size) {
                                    activePieces.remove(pIdx)
                                    inFlightBlocks.keys.removeIf { (it ushr 32).toInt() == pIdx }
                                    // Offload SHA-1 + FileChannel write to async worker pool!
                                    diskWriteChannel.send(PendingPieceWrite(meta, pIdx, assembly.buffer))
                                }
                            }
                            // Immediately top up the pipeline!
                            fillPeerPipeline()
                        }
                    }
                    20 -> { // BEP-10 extended message
                        if (payload.isNotEmpty()) {
                            val extId = payload[0].toInt() and 0xFF
                            val extBody = payload.copyOfRange(1, payload.size)
                            if (extId == 0) {
                                handleExtendedHandshakeReply(
                                    extBody,
                                    onUtMetadataId = { peerUtMetadataId = it },
                                    onUtPexId = { peerUtPexId = it },
                                    onReqq = { r -> if (r > 0) remoteReqq = r.coerceIn(16, 250) }
                                )
                                if (metainfo == null && peerUtMetadataId != null && metadataTotalSize > 0) {
                                    val piecesNeeded = (metadataTotalSize + 16383) / 16384
                                    for (i in 0 until piecesNeeded) {
                                        sendUtMetadataRequest(out, peerUtMetadataId!!, i)
                                    }
                                    out.flush()
                                }
                            } else if (extId == 1 && pexEnabled) {
                                trackerClient.parsePexPayload(extBody).forEach { discovered ->
                                    if (!ipFilterManager.isBlocked(discovered.host)) {
                                        peerPool[discovered.key] = discovered
                                    }
                                }
                            } else if (extId == 2 && metainfo == null) {
                                handleUtMetadataData(extBody)
                                if (metainfo != null && !peerChoked) {
                                    fillPeerPipeline()
                                }
                            }
                        }
                    }
                }

                // Keep peer pipeline saturated whenever unchoked and metainfo is ready
                if (!peerChoked && !seedOnlyMode && metainfo != null && inFlightBlocks.size < targetPipelineDepth / 2) {
                    fillPeerPipeline()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Peer closed, timed out, or malformed frame
        } finally {
            activePieces.keys.forEach { inFlightPieces.remove(it) }
            activePieces.clear()
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            if (registered) activePeerCount.decrementAndGet()
            if (wasSeed) activeSeedCount.decrementAndGet()
        }
    }

    private fun pickNextPiece(peerHas: BitSet, alreadyOwnedByPeer: Set<Int>): Int {
        val meta = metainfo ?: return -1
        ensurePieceCache(meta)
        val required = cachedRequiredPieces
        val ranks = cachedPiecePriorityRanks
        if (required.isEmpty()) return -1

        val now = System.currentTimeMillis()
        inFlightPieces.entries.removeIf { now - it.value > 8_000L }

        val peerHasEmpty = peerHas.isEmpty
        var bestIdx = -1
        var bestScore = Long.MAX_VALUE
        var remainingUncompleted = 0
        var endgameCandidate = -1

        synchronized(pieceStateLock) {
            val highCandidates = if (!isSequential) ArrayList<Int>(32) else null
            val normalCandidates = if (!isSequential) ArrayList<Int>(64) else null

            for (p in required) {
                if (completedPieces.get(p)) continue
                remainingUncompleted++
                if (p in alreadyOwnedByPeer) continue
                if (!peerHasEmpty && !peerHas.get(p)) continue

                if (inFlightPieces.containsKey(p)) {
                    if (endgameCandidate == -1) endgameCandidate = p
                    continue
                }

                val rank = if (p < ranks.size) ranks[p] else 1
                if (isSequential) {
                    val score = rank * 1_000_000L + p
                    if (score < bestScore) {
                        bestScore = score
                        bestIdx = p
                    }
                } else {
                    if (rank == 0) {
                        highCandidates!!.add(p)
                    } else if (normalCandidates!!.size < 128) {
                        normalCandidates.add(p)
                    }
                }
            }

            if (!isSequential) {
                bestIdx = when {
                    highCandidates != null && highCandidates.isNotEmpty() -> highCandidates.random()
                    normalCandidates != null && normalCandidates.isNotEmpty() -> normalCandidates.random()
                    else -> -1
                }
            }

            // Endgame Mode: when <= 12 required pieces remain, race in-flight tail pieces across fast peers
            if (bestIdx < 0 && remainingUncompleted <= 12 && endgameCandidate >= 0) {
                bestIdx = endgameCandidate
            }

            if (bestIdx >= 0) {
                inFlightPieces[bestIdx] = now
            }
        }
        return bestIdx
    }

    private fun pieceSizeFor(meta: TorrentMetainfo, pieceIdx: Int): Int {
        val start = pieceIdx.toLong() * meta.pieceLength
        val remaining = (meta.totalLength - start).coerceAtLeast(0L)
        return minOf(meta.pieceLength, remaining).toInt()
    }

    private suspend fun verifyPieceHashAsync(
        meta: TorrentMetainfo,
        pieceIdx: Int,
        bytes: ByteArray
    ): Boolean {
        val expectedHash = meta.pieceHashes.getOrNull(pieceIdx) ?: return false
        val actualHash = withContext(Dispatchers.Default) {
            MessageDigest.getInstance("SHA-1").digest(bytes)
        }
        return actualHash.contentEquals(expectedHash)
    }

    private fun getOrOpenFile(destFile: File): RandomAccessFile {
        val key = destFile.absolutePath
        return openFilesPool.computeIfAbsent(key) {
            destFile.parentFile?.mkdirs()
            RandomAccessFile(destFile, "rw")
        }
    }

    private fun writePieceToDiskPooled(meta: TorrentMetainfo, pieceIdx: Int, bytes: ByteArray) {
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
                    val raf = getOrOpenFile(destFile)
                    val channel = raf.channel
                    val srcOffset = (overlapStart - pieceOffset).toInt()
                    val length = (overlapEnd - overlapStart).toInt()
                    val buf = ByteBuffer.wrap(bytes, srcOffset, length)
                    var filePos = overlapStart - fileStart
                    while (buf.hasRemaining()) {
                        val written = channel.write(buf, filePos)
                        if (written <= 0) break
                        filePos += written
                    }
                }
            }
            fileStart = fileEnd
        }
    }

    private fun readPieceSlicePooled(meta: TorrentMetainfo, pieceIdx: Int, begin: Int, len: Int): ByteArray? {
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
                val raf = getOrOpenFile(srcFile)
                val channel = raf.channel
                val buf = ByteBuffer.wrap(result, written, chunkLen)
                var filePos = overlapStart - fileStart
                while (buf.hasRemaining()) {
                    val r = channel.read(buf, filePos)
                    if (r <= 0) return null
                    filePos += r
                }
                written += chunkLen
            }
            fileStart = fileEnd
        }
        return if (written == len) result else null
    }

    private fun flushAllOpenFiles() {
        openFilesPool.values.forEach { raf ->
            try {
                raf.channel.force(false)
            } catch (_: Throwable) {
            }
        }
    }

    private fun closeAllOpenFiles() {
        openFilesPool.values.forEach { raf ->
            try {
                raf.channel.force(false)
            } catch (_: Throwable) {
            }
            try {
                raf.close()
            } catch (_: Throwable) {
            }
        }
        openFilesPool.clear()
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

    private fun writeRequestFrame(
        out: DataOutputStream,
        scratch: ByteArray,
        pieceIdx: Int,
        offset: Int,
        blockLen: Int
    ) {
        val bb = ByteBuffer.wrap(scratch).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(13)
        bb.put(6.toByte())
        bb.putInt(pieceIdx)
        bb.putInt(offset)
        bb.putInt(blockLen)
        out.write(scratch)
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
            "v" to "AeroPlayer 1.4.8".toByteArray(StandardCharsets.UTF_8),
            "reqq" to 250L
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
        onUtPexId: (Int?) -> Unit,
        onReqq: (Int) -> Unit
    ) {
        try {
            val map = BencodeCodec.decode(body) as? Map<String, Any?> ?: return
            val m = map["m"] as? Map<String, Any?>
            onUtMetadataId((m?.get("ut_metadata") as? Long)?.toInt())
            onUtPexId((m?.get("ut_pex") as? Long)?.toInt())
            (map["metadata_size"] as? Long)?.toInt()?.let { metadataTotalSize = it }
            (map["reqq"] as? Long)?.toInt()?.let { onReqq(it) }
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
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleUtMetadataData(body: ByteArray) {
        try {
            val decoded = BencodeCodec.decode(body) as? Map<String, Any?> ?: return
            val msgType = (decoded["msg_type"] as? Long)?.toInt() ?: return
            if (msgType != 1) return
            val pieceIdx = (decoded["piece"] as? Long)?.toInt() ?: return
            val reEncoded = BencodeCodec.encode(decoded)
            val dictEnd = reEncoded.size
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
