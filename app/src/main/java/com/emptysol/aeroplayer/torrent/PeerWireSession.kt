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
import java.io.IOException
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
private const val DISK_STREAM_PIECE_THRESHOLD = 512 * 1024 // Pieces > 512 KB stream directly to disk (zero OOM!)
private const val MAX_RAM_ASSEMBLY_BYTES = 8L * 1024L * 1024L // Hard 8 MB RAM ceiling for small-piece buffers
private const val PEER_READ_SCRATCH_BYTES = 32 * 1024 // Reusable 32 KB per-peer frame buffer

private data class PendingPieceVerify(
    val meta: TorrentMetainfo,
    val pieceIdx: Int,
    val pieceSize: Int,
    val ramBuffer: ByteArray? // null when streamed directly to disk
)

private class ActivePieceAssembly(
    val pieceIdx: Int,
    val size: Int,
    val ramBuffer: ByteArray? // null -> disk-backed streaming assembly
) {
    val blockCount: Int = (size + BLOCK_SIZE - 1) / BLOCK_SIZE
    val receivedBlocks = BitSet(blockCount)
    var receivedBytes: Int = 0

    fun isComplete(): Boolean = receivedBytes >= size || receivedBlocks.cardinality() >= blockCount

    /**
     * Finds the first missing block that is not already in-flight.
     * Ensures timed-out or rejected blocks are immediately re-requested so pieces NEVER stall at 99%.
     */
    fun findNextMissingBlockOffset(inFlightBlocks: Map<Long, Long>): Int {
        var blockIdx = receivedBlocks.nextClearBit(0)
        while (blockIdx in 0 until blockCount) {
            val offset = blockIdx * BLOCK_SIZE
            val key = (pieceIdx.toLong() shl 32) or (offset.toLong() and 0xFFFFFFFFL)
            if (!inFlightBlocks.containsKey(key)) {
                return offset
            }
            blockIdx = receivedBlocks.nextClearBit(blockIdx + 1)
        }
        return -1
    }
}

/**
 * uTorrent-grade, memory-safe, multi-threaded BitTorrent coordinator:
 * - Zero-OOM disk-backed streaming assembly for large pieces (> 512 KB) + 8 MB RAM cap for small pieces
 * - Missing-block bitset scanner (re-requests dropped/rejected blocks instantly; never sticks at 99%)
 * - Instant pre-connect disk resume verification (never double-downloads already completed torrents!)
 * - Full BEP-6 Fast Extension (HaveAll, HaveNone, SuggestPiece, RejectRequest) for seedbox speeds
 * - Incoming TCP peer handoff + UPnP/NAT-PMP + BEP-14 LSD + parallel BEP-5 DHT + BEP-11 PEX
 * - Up to 120 concurrent peers & 128-block deep pipelining (2 MB/peer in flight) in Turbo Boost mode
 * - Fast 12s dead/snubbed peer eviction to rotate in faster peers from the swarm
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
    private val natAndListenerManager: NatAndListenerManager? = null,
    speedBoostEnabled: Boolean = true,
    private val onFatalError: suspend (String) -> Unit = {},
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
    private val completionEmitted = AtomicBoolean(false)
    private val peerPool = ConcurrentHashMap<String, PeerEndpoint>()
    private val activePeerKeys = ConcurrentHashMap.newKeySet<String>()
    private val activeWorkerJobs = ConcurrentHashMap<String, Job>()
    private val activePeerCount = AtomicInteger(0)
    private val activeSeedCount = AtomicInteger(0)
    private val totalUploaded = AtomicLong(0L)
    private val bytesInWindowDl = AtomicLong(0L)
    private val bytesInWindowUl = AtomicLong(0L)
    private val verifiedDownloadedBytes = AtomicLong(0L)
    private val inFlightReceivedBytes = AtomicLong(0L)
    private val currentRamAssemblyBytes = AtomicLong(0L)

    @Volatile
    private var metainfo: TorrentMetainfo? = initialMetainfo

    @Volatile
    private var turboEnabled: Boolean = speedBoostEnabled

    private val pieceStateLock = Any()
    private val completedPieces = BitSet()
    private val inFlightPieces = ConcurrentHashMap<Int, Long>()
    private val metadataPieces = ConcurrentHashMap<Int, ByteArray>()
    private val resumeScanDone = AtomicBoolean(false)
    private val fatalErrorReported = AtomicBoolean(false)

    // Cached piece requirements & priority ranks to avoid per-pick heap churn
    @Volatile
    private var cachedRequiredMetaRef: TorrentMetainfo? = null
    @Volatile
    private var cachedRequiredPieces: IntArray = IntArray(0)
    @Volatile
    private var cachedPiecePriorityRanks: IntArray = IntArray(0)

    // Pooled NIO FileChannels for direct native disk I/O
    private val openFilesPool = ConcurrentHashMap<String, RandomAccessFile>()

    @Volatile
    private var metadataTotalSize: Int = -1

    private val maxConcurrentPeers: Int
        get() = if (turboEnabled) 120 else 48

    private val maxPipelineDepth: Int
        get() = if (turboEnabled) 128 else 48

    private val maxActivePiecesPerPeer: Int
        get() = if (turboEnabled) 3 else 2

    private val socketRecvBufBytes: Int
        get() = if (turboEnabled) 512 * 1024 else 256 * 1024

    private val diskWriterWorkers: Int = if (speedBoostEnabled) 4 else 2

    fun setSpeedBoostLive(enabled: Boolean) {
        turboEnabled = enabled
    }

    fun stop() {
        running.set(false)
    }

    suspend fun runSession() = coroutineScope {
        try {
            if (!stagingDir.exists() && !stagingDir.mkdirs() && !stagingDir.exists()) {
                reportFatalError("Unable to create download folder: ${stagingDir.absolutePath}")
                return@coroutineScope
            }
        } catch (t: Throwable) {
            reportFatalError("Storage unavailable: ${t.message ?: "Permission denied"}")
            return@coroutineScope
        }

        // 1. Scan previously downloaded pieces on disk BEFORE opening any peer sockets
        metainfo?.let { meta ->
            ensurePieceCache(meta)
            resumeVerifiedPiecesFromDisk(meta)
            // If every required piece is ALREADY verified on disk, mark complete immediately (zero double-download!)
            if (isDownloadComplete(meta)) {
                finishAndEmitCompleted(meta)
                closeAllOpenFiles()
                return@coroutineScope
            }
        }

        val scope = this
        val verifyChannel = Channel<PendingPieceVerify>(capacity = if (turboEnabled) 8 else 4)

        // Register for incoming TCP peer handshakes & BEP-14 LSD LAN peer discovery
        natAndListenerManager?.registerTorrent(
            infoHashHex = infoHashHex,
            onIncomingPeer = { incomingSocket, remoteHandshake, input, out ->
                if (!running.get() || activeWorkerJobs.size >= maxConcurrentPeers) {
                    try { incomingSocket.close() } catch (_: Throwable) {}
                } else {
                    val key = "in:${incomingSocket.inetAddress?.hostAddress}:${incomingSocket.port}"
                    if (activePeerKeys.add(key)) {
                        val job = scope.launch(Dispatchers.IO) {
                            try {
                                runPeerWireLoop(
                                    socket = incomingSocket,
                                    input = input,
                                    out = out,
                                    remoteHandshake = remoteHandshake,
                                    sendInitialHandshake = true,
                                    verifyChannel = verifyChannel
                                )
                            } finally {
                                activePeerKeys.remove(key)
                                activeWorkerJobs.remove(key)
                            }
                        }
                        activeWorkerJobs[key] = job
                    } else {
                        try { incomingSocket.close() } catch (_: Throwable) {}
                    }
                }
            },
            onLsdPeer = { ep ->
                if (running.get() && !ipFilterManager.isBlocked(ep.host)) {
                    peerPool[ep.key] = ep
                }
            }
        )

        // Multi-threaded SHA-1 verification + NIO FileChannel disk writers
        val writerJobs = (0 until diskWriterWorkers).map {
            scope.launch(Dispatchers.IO) {
                val hashScratch = ByteArray(64 * 1024)
                for (pending in verifyChannel) {
                    try {
                        val alreadyDone = synchronized(pieceStateLock) { completedPieces.get(pending.pieceIdx) }
                        if (alreadyDone) {
                            inFlightPieces.remove(pending.pieceIdx)
                            continue
                        }
                        val verified = if (pending.ramBuffer != null) {
                            verifyPieceHashRam(pending.meta, pending.pieceIdx, pending.ramBuffer)
                        } else {
                            verifyPieceHashFromDisk(pending.meta, pending.pieceIdx, pending.pieceSize, hashScratch)
                        }
                        if (verified) {
                            if (pending.ramBuffer != null) {
                                writeBytesRangeToDiskPooled(
                                    meta = pending.meta,
                                    absOffset = pending.pieceIdx.toLong() * pending.meta.pieceLength,
                                    bytes = pending.ramBuffer,
                                    srcOffset = 0,
                                    length = pending.ramBuffer.size
                                )
                            }
                            var newlyMarked = false
                            synchronized(pieceStateLock) {
                                if (!completedPieces.get(pending.pieceIdx)) {
                                    completedPieces.set(pending.pieceIdx)
                                    newlyMarked = true
                                }
                            }
                            if (newlyMarked) {
                                verifiedDownloadedBytes.addAndGet(pending.pieceSize.toLong())
                            }
                        }
                        inFlightPieces.remove(pending.pieceIdx)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ioe: IOException) {
                        inFlightPieces.remove(pending.pieceIdx)
                        if (isFatalDiskError(ioe)) {
                            reportFatalError("Storage write error: ${ioe.message ?: "Disk full or permission denied"}")
                            running.set(false)
                        }
                    } catch (_: Throwable) {
                        inFlightPieces.remove(pending.pieceIdx)
                    } finally {
                        pending.ramBuffer?.let { buf ->
                            currentRamAssemblyBytes.addAndGet(-buf.size.toLong())
                        }
                    }
                }
            }
        }

        // Parallel tracker + parallel DHT + LSD peer discovery loop
        val discoveryJob = scope.launch(Dispatchers.IO) {
            var firstPass = true
            val portToAdvertise = natAndListenerManager?.listenPort ?: 6881
            while (running.get() && isActive) {
                val meta = metainfo
                val baseTrackers = ((meta?.trackers ?: emptyList()) + initialTrackers).distinct()
                val trackers = if (baseTrackers.size < 6) {
                    (baseTrackers + trackerClient.publicFallbackTrackers).distinct()
                } else {
                    baseTrackers
                }
                val doneBytes = currentDownloadedEstimate(meta)
                val leftBytes = ((meta?.totalLength ?: 0L) - doneBytes).coerceAtLeast(0L)
                val event = if (firstPass) "started" else null

                natAndListenerManager?.announceLsd(infoHashHex)

                supervisorScope {
                    val announceJobs = trackers.take(24).map { trUrl ->
                        async(Dispatchers.IO) {
                            if (!running.get()) return@async
                            try {
                                val res = trackerClient.announceTracker(
                                    trackerUrl = trUrl,
                                    infoHashBytes = infoHashBytes,
                                    peerId = peerId,
                                    listenPort = portToAdvertise,
                                    uploaded = totalUploaded.get(),
                                    downloaded = doneBytes,
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
                                val dhtPeers = trackerClient.queryDhtPeersParallel(
                                    infoHashBytes = infoHashBytes,
                                    nodeId = peerId,
                                    maxWaves = if (turboEnabled) 5 else 3,
                                    waveConcurrency = if (turboEnabled) 14 else 8
                                )
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
                val nextDelayMs = if (activePeerCount.get() < 12) 10_000L else 28_000L
                delay(nextDelayMs)
            }
        }

        // Fast wave-based peer connection dialer
        val attemptedPeers = ConcurrentHashMap<String, Long>()
        val peerManagerJob = scope.launch(Dispatchers.IO) {
            while (running.get() && isActive) {
                val now = System.currentTimeMillis()
                val availableSlots = (maxConcurrentPeers - activeWorkerJobs.size).coerceAtLeast(0)
                if (availableSlots > 0 && peerPool.isNotEmpty()) {
                    val waveLimit = minOf(availableSlots, if (turboEnabled) 24 else 10)
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
                                connectOutboundPeer(ep, verifyChannel)
                            } finally {
                                activePeerKeys.remove(ep.key)
                                activeWorkerJobs.remove(ep.key)
                            }
                        }
                        activeWorkerJobs[ep.key] = workerJob
                    }
                }
                delay(if (activePeerCount.get() < 12) 250L else 700L)
            }
        }

        try {
            // Throttled 1Hz progress reporter loop (keeps UI/Room completely off peer socket threads)
            var lastTickTime = System.currentTimeMillis()
            while (running.get() && isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val dtSec = ((now - lastTickTime).coerceAtLeast(250L)) / 1000.0
                lastTickTime = now

                val dlBps = (bytesInWindowDl.getAndSet(0L) / dtSec).toLong()
                val ulBps = (bytesInWindowUl.getAndSet(0L) / dtSec).toLong()
                val meta = metainfo
                if (meta != null && !resumeScanDone.get()) {
                    ensurePieceCache(meta)
                    resumeVerifiedPiecesFromDisk(meta)
                }

                if (meta != null && isDownloadComplete(meta)) {
                    // Stop all peer & discovery workers IMMEDIATELY so no extra bytes are downloaded!
                    running.set(false)
                    discoveryJob.cancel()
                    peerManagerJob.cancel()
                    activeWorkerJobs.values.forEach { it.cancel() }
                    activeWorkerJobs.clear()
                    finishAndEmitCompleted(meta)
                    break
                }

                val donePcs = synchronized(pieceStateLock) { completedPieces.cardinality() }
                val playable = findPrimaryPlayableFile(meta)
                val effectiveDlBytes = currentDownloadedEstimate(meta)

                onProgressUpdate(
                    meta,
                    effectiveDlBytes,
                    totalUploaded.get(),
                    dlBps,
                    ulBps,
                    activePeerCount.get(),
                    activeSeedCount.get(),
                    donePcs,
                    playable,
                    false
                )
            }
        } finally {
            running.set(false)
            natAndListenerManager?.unregisterTorrent(infoHashHex)
            discoveryJob.cancel()
            peerManagerJob.cancel()
            activeWorkerJobs.values.forEach { it.cancel() }
            activeWorkerJobs.clear()
            verifyChannel.close()
            withContext(NonCancellable) {
                writerJobs.forEach {
                    withTimeoutOrNull(1500L) { it.join() }
                    it.cancel()
                }
                closeAllOpenFiles()
            }
        }
    }

    private suspend fun finishAndEmitCompleted(meta: TorrentMetainfo) {
        if (!completionEmitted.compareAndSet(false, true)) return
        flushAllOpenFiles()
        syncCompletedFilesToSafIfNeeded(meta)
        val playable = findPrimaryPlayableFile(meta)
        val totalPcs = meta.pieceHashes.size
        onProgressUpdate(
            meta,
            meta.totalLength,
            totalUploaded.get(),
            0L,
            0L,
            0,
            0,
            totalPcs,
            playable,
            true
        )
    }

    private fun currentDownloadedEstimate(meta: TorrentMetainfo?): Long {
        val verified = verifiedDownloadedBytes.get()
        val pending = inFlightReceivedBytes.get().coerceAtLeast(0L)
        val raw = verified + pending
        return if (meta != null && meta.totalLength > 0L) raw.coerceAtMost(meta.totalLength) else raw
    }

    private suspend fun reportFatalError(message: String) {
        if (fatalErrorReported.compareAndSet(false, true)) {
            try {
                onFatalError(message)
            } catch (_: Throwable) {
            }
        }
    }

    private fun isFatalDiskError(ioe: IOException): Boolean {
        val msg = ioe.message?.lowercase().orEmpty()
        return msg.contains("eacces") ||
            msg.contains("permission denied") ||
            msg.contains("enospc") ||
            msg.contains("no space left") ||
            msg.contains("read-only file system") ||
            msg.contains("erofs")
    }

    /**
     * Scans existing files on disk with a single reusable 64 KB buffer to restore verified pieces
     * on startup/resume. Uses O(1) memory regardless of torrent size.
     */
    private suspend fun resumeVerifiedPiecesFromDisk(meta: TorrentMetainfo) {
        if (!resumeScanDone.compareAndSet(false, true)) return
        val anyFileExists = meta.files.any { entry ->
            val f = File(stagingDir, entry.path)
            f.exists() && f.length() > 0L
        }
        if (!anyFileExists) return

        withContext(Dispatchers.IO) {
            val scratch = ByteArray(64 * 1024)
            var restoredBytes = 0L
            for (p in cachedRequiredPieces) {
                if (!running.get()) break
                val pSize = pieceSizeFor(meta, p)
                if (pSize <= 0) continue
                if (verifyPieceHashFromDisk(meta, p, pSize, scratch)) {
                    synchronized(pieceStateLock) {
                        if (!completedPieces.get(p)) {
                            completedPieces.set(p)
                            restoredBytes += pSize.toLong()
                        }
                    }
                }
            }
            if (restoredBytes > 0L) {
                verifiedDownloadedBytes.addAndGet(restoredBytes)
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
            val ranks = IntArray(totalPieces) { 3 }
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

    internal fun isDownloadComplete(meta: TorrentMetainfo?): Boolean {
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

    private fun allocateAssembly(pieceIdx: Int, pieceLen: Int): ActivePieceAssembly {
        if (pieceLen > DISK_STREAM_PIECE_THRESHOLD) {
            return ActivePieceAssembly(pieceIdx, pieceLen, ramBuffer = null)
        }
        val newTotal = currentRamAssemblyBytes.addAndGet(pieceLen.toLong())
        if (newTotal <= MAX_RAM_ASSEMBLY_BYTES) {
            return try {
                ActivePieceAssembly(pieceIdx, pieceLen, ramBuffer = ByteArray(pieceLen))
            } catch (_: OutOfMemoryError) {
                currentRamAssemblyBytes.addAndGet(-pieceLen.toLong())
                ActivePieceAssembly(pieceIdx, pieceLen, ramBuffer = null)
            }
        } else {
            currentRamAssemblyBytes.addAndGet(-pieceLen.toLong())
            return ActivePieceAssembly(pieceIdx, pieceLen, ramBuffer = null)
        }
    }

    private fun releaseAssembly(assembly: ActivePieceAssembly, handoffToVerifyChannel: Boolean) {
        if (assembly.receivedBytes > 0) {
            inFlightReceivedBytes.addAndGet(-assembly.receivedBytes.toLong())
        }
        if (!handoffToVerifyChannel) {
            assembly.ramBuffer?.let { buf ->
                currentRamAssemblyBytes.addAndGet(-buf.size.toLong())
            }
        }
    }

    private fun buildHandshakeBytes(): ByteArray {
        val handshake = ByteArray(68)
        handshake[0] = 19.toByte()
        "BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII).copyInto(handshake, 1)
        handshake[25] = 0x10.toByte() // BEP-10 Extended Protocol
        var lastByte = 0x04 // BEP-6 Fast Extension (HaveAll / HaveNone / RejectRequest)
        if (dhtEnabled) lastByte = lastByte or 0x01 // BEP-5 DHT
        handshake[27] = lastByte.toByte()
        infoHashBytes.copyInto(handshake, 28)
        peerId.copyInto(handshake, 48)
        return handshake
    }

    private suspend fun connectOutboundPeer(
        ep: PeerEndpoint,
        verifyChannel: Channel<PendingPieceVerify>
    ) {
        if (ipFilterManager.isBlocked(ep.host)) return
        var socket: Socket? = null
        try {
            socket = Socket().apply {
                tcpNoDelay = true
                receiveBufferSize = socketRecvBufBytes
                sendBufferSize = 128 * 1024
                soTimeout = 10_000
                keepAlive = true
            }
            socket.connect(InetSocketAddress(ep.host, ep.port), 2200)

            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16384))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 32768))

            out.write(buildHandshakeBytes())
            out.flush()

            val reply = ByteArray(68)
            input.readFully(reply)
            if (reply[0].toInt() != 19) return
            val remoteHash = reply.copyOfRange(28, 48)
            if (!remoteHash.contentEquals(infoHashBytes)) return

            runPeerWireLoop(
                socket = socket,
                input = input,
                out = out,
                remoteHandshake = reply,
                sendInitialHandshake = false,
                verifyChannel = verifyChannel
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            try { socket?.close() } catch (_: Throwable) {}
        }
    }

    private suspend fun runPeerWireLoop(
        socket: Socket,
        input: DataInputStream,
        out: DataOutputStream,
        remoteHandshake: ByteArray,
        sendInitialHandshake: Boolean,
        verifyChannel: Channel<PendingPieceVerify>
    ) {
        var wasSeed = false
        var registered = false
        val activePieces = LinkedHashMap<Int, ActivePieceAssembly>()
        val inFlightBlocks = HashMap<Long, Long>()

        try {
            if (sendInitialHandshake) {
                out.write(buildHandshakeBytes())
                out.flush()
            }

            activePeerCount.incrementAndGet()
            registered = true

            val supportsExtended = (remoteHandshake[25].toInt() and 0x10) != 0
            var peerUtMetadataId: Int? = null

            if (supportsExtended) {
                sendExtendedHandshake(out)
            }

            val peerHasPieces = BitSet()
            var peerHasAll = false
            var peerChoked = true
            var targetPipelineDepth = if (turboEnabled) 48 else 24
            var remoteReqq = maxPipelineDepth
            val connectedAtMs = System.currentTimeMillis()
            var lastUsefulBlockMs = connectedAtMs

            if (!seedOnlyMode) {
                sendSimpleMessage(out, 2) // interested
            }

            val reqScratch = ByteArray(17)
            val readScratch = ByteArray(PEER_READ_SCRATCH_BYTES)

            fun fillPeerPipeline() {
                val meta = metainfo ?: return
                if (peerChoked || seedOnlyMode || !running.get()) return
                val currentMaxPipeline = maxPipelineDepth
                val currentMaxPieces = maxActivePiecesPerPeer
                val effectiveDepth = minOf(targetPipelineDepth, remoteReqq, currentMaxPipeline)
                var queuedRequests = 0

                val now = System.currentTimeMillis()
                // Drop stale in-flight block requests after 4s so they get re-requested cleanly
                if (inFlightBlocks.isNotEmpty()) {
                    val staleIter = inFlightBlocks.entries.iterator()
                    while (staleIter.hasNext()) {
                        if (now - staleIter.next().value > 4_000L) {
                            staleIter.remove()
                        }
                    }
                }

                // Remove any active piece that was already completed by another peer
                val completedIter = activePieces.entries.iterator()
                while (completedIter.hasNext()) {
                    val (pIdx, asm) = completedIter.next()
                    val alreadyDone = synchronized(pieceStateLock) { completedPieces.get(pIdx) }
                    if (alreadyDone) {
                        completedIter.remove()
                        inFlightBlocks.keys.removeIf { (it ushr 32).toInt() == pIdx }
                        releaseAssembly(asm, handoffToVerifyChannel = false)
                    }
                }

                while (inFlightBlocks.size < effectiveDepth) {
                    var targetAssembly: ActivePieceAssembly? = null
                    var nextOffset = -1

                    for (assembly in activePieces.values) {
                        val candidateOffset = assembly.findNextMissingBlockOffset(inFlightBlocks)
                        if (candidateOffset >= 0) {
                            targetAssembly = assembly
                            nextOffset = candidateOffset
                            break
                        }
                    }

                    if (targetAssembly == null) {
                        if (activePieces.size >= currentMaxPieces) break
                        val nextIdx = pickNextPiece(peerHasPieces, peerHasAll, activePieces.keys)
                        if (nextIdx < 0) break
                        val pLen = pieceSizeFor(meta, nextIdx)
                        if (pLen <= 0) {
                            inFlightPieces.remove(nextIdx)
                            break
                        }
                        targetAssembly = allocateAssembly(nextIdx, pLen)
                        activePieces[nextIdx] = targetAssembly
                        nextOffset = targetAssembly.findNextMissingBlockOffset(inFlightBlocks)
                        if (nextOffset < 0) break
                    }

                    val blockLen = minOf(BLOCK_SIZE, targetAssembly.size - nextOffset)
                    if (blockLen <= 0) break

                    val blockKey = (targetAssembly.pieceIdx.toLong() shl 32) or (nextOffset.toLong() and 0xFFFFFFFFL)
                    inFlightBlocks[blockKey] = now

                    writeRequestFrame(out, reqScratch, targetAssembly.pieceIdx, nextOffset, blockLen)
                    queuedRequests++
                }

                if (queuedRequests > 0) {
                    out.flush()
                }
            }

            while (running.get() && !socket.isClosed) {
                // Evict snubbed/dead peers after 12s of zero data when fresher peers are waiting
                val loopNow = System.currentTimeMillis()
                if (!seedOnlyMode && metainfo != null && (loopNow - lastUsefulBlockMs > 12_000L) && peerPool.size > activePeerCount.get()) {
                    break
                }

                val len = try {
                    input.readInt()
                } catch (_: Exception) {
                    break
                }
                if (len == 0) continue // keep-alive
                if (len < 0 || len > 2 * 1024 * 1024) break

                val msgId = input.readByte().toInt() and 0xFF
                val payloadLen = len - 1
                val payload = if (payloadLen <= readScratch.size) readScratch else ByteArray(payloadLen)
                if (payloadLen > 0) {
                    input.readFully(payload, 0, payloadLen)
                }

                when (msgId) {
                    0 -> { // choke
                        peerChoked = true
                        inFlightBlocks.clear()
                        activePieces.values.forEach { assembly ->
                            inFlightPieces.remove(assembly.pieceIdx)
                            releaseAssembly(assembly, handoffToVerifyChannel = false)
                        }
                        activePieces.clear()
                        targetPipelineDepth = if (turboEnabled) 24 else 12
                    }
                    1 -> { // unchoke
                        peerChoked = false
                        fillPeerPipeline()
                    }
                    4 -> { // have
                        if (payloadLen >= 4) {
                            val idx = readBigEndianInt(payload, 0)
                            if (idx >= 0) peerHasPieces.set(idx)
                        }
                        if (!peerChoked) fillPeerPipeline()
                    }
                    5 -> { // bitfield
                        for (byteIdx in 0 until payloadLen) {
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
                    6 -> { // request from peer
                        if (payloadLen >= 12) {
                            val reqIdx = readBigEndianInt(payload, 0)
                            val reqBegin = readBigEndianInt(payload, 4)
                            val reqLen = readBigEndianInt(payload, 8)
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
                        if (payloadLen >= 8 && meta != null) {
                            val pIdx = readBigEndianInt(payload, 0)
                            val pBegin = readBigEndianInt(payload, 4)
                            val dataLen = payloadLen - 8

                            globalDlThrottler.acquire(dataLen)
                            taskDlThrottler.acquire(dataLen)
                            bytesInWindowDl.addAndGet(dataLen.toLong())
                            lastUsefulBlockMs = System.currentTimeMillis()

                            val blockKey = (pIdx.toLong() shl 32) or (pBegin.toLong() and 0xFFFFFFFFL)
                            inFlightBlocks.remove(blockKey)

                            if (targetPipelineDepth < maxPipelineDepth) {
                                targetPipelineDepth = (targetPipelineDepth + 2).coerceAtMost(maxPipelineDepth)
                            }

                            val assembly = activePieces[pIdx]
                            if (assembly != null && pBegin >= 0 && pBegin + dataLen <= assembly.size && pBegin % BLOCK_SIZE == 0) {
                                val blockIdx = pBegin / BLOCK_SIZE
                                if (blockIdx in 0 until assembly.blockCount && !assembly.receivedBlocks.get(blockIdx)) {
                                    if (assembly.ramBuffer != null) {
                                        System.arraycopy(payload, 8, assembly.ramBuffer, pBegin, dataLen)
                                    } else {
                                        val absPos = pIdx.toLong() * meta.pieceLength + pBegin.toLong()
                                        try {
                                            writeBytesRangeToDiskPooled(meta, absPos, payload, 8, dataLen)
                                        } catch (ioe: IOException) {
                                            if (isFatalDiskError(ioe)) {
                                                reportFatalError("Storage write error: ${ioe.message ?: "Disk full or permission denied"}")
                                                running.set(false)
                                                break
                                            }
                                            throw ioe
                                        }
                                    }
                                    assembly.receivedBlocks.set(blockIdx)
                                    assembly.receivedBytes += dataLen
                                    inFlightReceivedBytes.addAndGet(dataLen.toLong())
                                }
                                inFlightPieces[pIdx] = System.currentTimeMillis()

                                if (assembly.isComplete()) {
                                    activePieces.remove(pIdx)
                                    inFlightBlocks.keys.removeIf { (it ushr 32).toInt() == pIdx }
                                    releaseAssembly(assembly, handoffToVerifyChannel = true)
                                    verifyChannel.send(
                                        PendingPieceVerify(
                                            meta = meta,
                                            pieceIdx = pIdx,
                                            pieceSize = assembly.size,
                                            ramBuffer = assembly.ramBuffer
                                        )
                                    )
                                }
                            }
                            fillPeerPipeline()
                        }
                    }
                    13 -> { // BEP-6 Suggest Piece
                        if (payloadLen >= 4) {
                            val idx = readBigEndianInt(payload, 0)
                            if (idx >= 0) peerHasPieces.set(idx)
                        }
                    }
                    14 -> { // BEP-6 Have All (modern high-speed seedboxes!)
                        peerHasAll = true
                        if (!wasSeed) {
                            wasSeed = true
                            activeSeedCount.incrementAndGet()
                        }
                        if (!peerChoked) fillPeerPipeline()
                    }
                    15 -> { // BEP-6 Have None
                        peerHasAll = false
                        peerHasPieces.clear()
                    }
                    16 -> { // BEP-6 Reject Request -> immediately clear from inFlightBlocks so we don't wait 4s!
                        if (payloadLen >= 12) {
                            val rIdx = readBigEndianInt(payload, 0)
                            val rBegin = readBigEndianInt(payload, 4)
                            val blockKey = (rIdx.toLong() shl 32) or (rBegin.toLong() and 0xFFFFFFFFL)
                            inFlightBlocks.remove(blockKey)
                        }
                    }
                    20 -> { // BEP-10 extended message
                        if (payloadLen > 0) {
                            val extId = payload[0].toInt() and 0xFF
                            val extBody = payload.copyOfRange(1, payloadLen)
                            if (extId == 0) {
                                handleExtendedHandshakeReply(
                                    extBody,
                                    onUtMetadataId = { peerUtMetadataId = it },
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
                                val parsedMeta = metainfo
                                if (parsedMeta != null) {
                                    ensurePieceCache(parsedMeta)
                                    resumeVerifiedPiecesFromDisk(parsedMeta)
                                    if (!peerChoked) fillPeerPipeline()
                                }
                            }
                        }
                    }
                }

                if (!peerChoked && !seedOnlyMode && metainfo != null && inFlightBlocks.size < targetPipelineDepth / 2) {
                    fillPeerPipeline()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Peer closed, timed out, or malformed frame
        } finally {
            activePieces.values.forEach { assembly ->
                inFlightPieces.remove(assembly.pieceIdx)
                releaseAssembly(assembly, handoffToVerifyChannel = false)
            }
            activePieces.clear()
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            if (registered) activePeerCount.decrementAndGet()
            if (wasSeed) activeSeedCount.decrementAndGet()
        }
    }

    private fun readBigEndianInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    private fun pickNextPiece(peerHas: BitSet, peerHasAll: Boolean, alreadyOwnedByPeer: Set<Int>): Int {
        val meta = metainfo ?: return -1
        ensurePieceCache(meta)
        val required = cachedRequiredPieces
        val ranks = cachedPiecePriorityRanks
        if (required.isEmpty()) return -1

        val now = System.currentTimeMillis()
        // Release any stalled piece after 6 seconds so faster peers take over cleanly
        inFlightPieces.entries.removeIf { now - it.value > 6_000L }

        val peerHasEmpty = !peerHasAll && peerHas.isEmpty
        var bestIdx = -1
        var bestScore = Long.MAX_VALUE
        var remainingUncompleted = 0
        var endgameCandidate = -1
        val isLargePieceTorrent = meta.pieceLength > DISK_STREAM_PIECE_THRESHOLD

        synchronized(pieceStateLock) {
            val highCandidates = if (!isSequential) ArrayList<Int>(32) else null
            val normalCandidates = if (!isSequential) ArrayList<Int>(64) else null

            for (p in required) {
                if (completedPieces.get(p)) continue
                remainingUncompleted++
                if (p in alreadyOwnedByPeer) continue
                if (!peerHasAll && !peerHasEmpty && !peerHas.get(p)) continue

                if (inFlightPieces.containsKey(p)) {
                    // Only allow Endgame racing on small RAM-buffered pieces (never double-write large disk pieces!)
                    if (!isLargePieceTorrent && endgameCandidate == -1) endgameCandidate = p
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

            // Endgame Mode: race final tail pieces across fast peers (safe for RAM-buffered pieces)
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

    private suspend fun verifyPieceHashRam(
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

    internal fun verifyPieceHashFromDisk(
        meta: TorrentMetainfo,
        pieceIdx: Int,
        pieceSize: Int,
        scratch: ByteArray = ByteArray(64 * 1024)
    ): Boolean {
        val expectedHash = meta.pieceHashes.getOrNull(pieceIdx) ?: return false
        val pieceStart = pieceIdx.toLong() * meta.pieceLength
        val pieceEnd = pieceStart + pieceSize
        val md = MessageDigest.getInstance("SHA-1")
        var fileStart = 0L
        var hashedBytes = 0L

        for (fileEntry in meta.files) {
            val fileEnd = fileStart + fileEntry.length
            val overlapStart = maxOf(pieceStart, fileStart)
            val overlapEnd = minOf(pieceEnd, fileEnd)
            if (overlapStart < overlapEnd) {
                val srcFile = File(stagingDir, fileEntry.path)
                val neededEndInFile = overlapEnd - fileStart
                if (!srcFile.exists() || srcFile.length() < neededEndInFile) return false
                val raf = getOrOpenFile(srcFile)
                val channel = raf.channel
                var filePos = overlapStart - fileStart
                var remaining = overlapEnd - overlapStart
                while (remaining > 0L) {
                    val toRead = minOf(remaining, scratch.size.toLong()).toInt()
                    val buf = ByteBuffer.wrap(scratch, 0, toRead)
                    var readTotal = 0
                    while (buf.hasRemaining()) {
                        val r = channel.read(buf, filePos + readTotal)
                        if (r <= 0) return false
                        readTotal += r
                    }
                    md.update(scratch, 0, toRead)
                    filePos += toRead
                    remaining -= toRead
                    hashedBytes += toRead
                }
            }
            fileStart = fileEnd
        }

        if (hashedBytes != pieceSize.toLong()) return false
        return md.digest().contentEquals(expectedHash)
    }

    private fun getOrOpenFile(destFile: File): RandomAccessFile {
        val key = destFile.absolutePath
        return openFilesPool.computeIfAbsent(key) {
            destFile.parentFile?.mkdirs()
            RandomAccessFile(destFile, "rw")
        }
    }

    internal fun writeBytesRangeToDiskPooled(
        meta: TorrentMetainfo,
        absOffset: Long,
        bytes: ByteArray,
        srcOffset: Int,
        length: Int
    ) {
        val rangeEnd = absOffset + length
        var fileStart = 0L
        for (fileEntry in meta.files) {
            val fileEnd = fileStart + fileEntry.length
            val overlapStart = maxOf(absOffset, fileStart)
            val overlapEnd = minOf(rangeEnd, fileEnd)
            if (overlapStart < overlapEnd) {
                val prio = if (filePriorities.isEmpty()) fileEntry.priority else (filePriorities[fileEntry.index] ?: "SKIP")
                if (prio != "SKIP") {
                    val destFile = File(stagingDir, fileEntry.path)
                    val raf = getOrOpenFile(destFile)
                    val channel = raf.channel
                    val localSrcOffset = srcOffset + (overlapStart - absOffset).toInt()
                    val chunkLen = (overlapEnd - overlapStart).toInt()
                    val buf = ByteBuffer.wrap(bytes, localSrcOffset, chunkLen)
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
        onReqq: (Int) -> Unit
    ) {
        try {
            val map = BencodeCodec.decode(body) as? Map<String, Any?> ?: return
            val m = map["m"] as? Map<String, Any?>
            onUtMetadataId((m?.get("ut_metadata") as? Long)?.toInt())
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
