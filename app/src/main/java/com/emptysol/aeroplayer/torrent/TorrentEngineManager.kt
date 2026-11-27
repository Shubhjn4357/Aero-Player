package com.emptysol.aeroplayer.torrent

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.emptysol.aeroplayer.data.database.AppDatabase
import com.emptysol.aeroplayer.data.database.MediaEntity
import com.emptysol.aeroplayer.data.database.PreferenceEntity
import com.emptysol.aeroplayer.data.database.RssFeedEntity
import com.emptysol.aeroplayer.data.database.TorrentTaskEntity
import com.emptysol.aeroplayer.data.repository.MediaRepository
import com.emptysol.aeroplayer.data.repository.PreferenceRepository
import com.emptysol.aeroplayer.service.TorrentDownloadService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TorrentEngineStatus(
    val vpnStatus: VpnStatusInfo = VpnStatusInfo(false, "Checking network...", false, null),
    val networkCondition: NetworkConditionRecommendation = NetworkConditionRecommendation("Checking...", false, 0, 0, 0, 1024),
    val remoteServerState: RemoteServerState = RemoteServerState(),
    val scheduleDirective: ScheduleDirective = ScheduleDirective.DOWNLOAD_AND_SEED,
    val totalDownloadBps: Long = 0L,
    val totalUploadBps: Long = 0L,
    val peakDownloadBps: Long = 0L,
    val activeCount: Int = 0,
    val activePeerCount: Int = 0,
    val speedBoostEnabled: Boolean = true,
    val pipelineDepth: Int = 64
)

/**
 * Central BitTorrent Engine Manager bound to the app's lifecycle/ViewModel.
 * Owns active PeerWire coordinators, global token-bucket bandwidth throttlers,
 * IP filter blocklist, ConnectivityManager VPN kill-switch observer,
 * Scheduler loop, RSS auto-downloader loop, LAN Remote Server, and post-completion actions.
 */
class TorrentEngineManager(
    private val context: Context,
    private val database: AppDatabase,
    private val mediaRepository: MediaRepository,
    private val preferenceRepository: PreferenceRepository,
    private val scope: CoroutineScope
) {
    private val torrentDao = database.torrentDao()
    private val rssFeedDao = database.rssFeedDao()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val trackerClient = TrackerAndDhtClient(okHttpClient)
    private val rssDownloader = RssAutoDownloader(okHttpClient)
    private val ipFilterManager = IpFilterManager()
    private val globalDlThrottler = BandwidthThrottler(0)
    private val globalUlThrottler = BandwidthThrottler(512)

    private val activeCoordinators = ConcurrentHashMap<String, TorrentDownloadCoordinator>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val metainfoCache = ConcurrentHashMap<String, TorrentMetainfo>()
    private val deletedHashes = ConcurrentHashMap.newKeySet<String>()

    val torrentsFlow: StateFlow<List<TorrentTaskEntity>> = torrentDao.getAllTorrentsFlow()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val rssFeedsFlow: StateFlow<List<RssFeedEntity>> = rssFeedDao.getAllFeedsFlow()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val boostPrefs by lazy {
        context.getSharedPreferences("aero_torrent_engine_prefs", Context.MODE_PRIVATE)
    }

    @Volatile
    private var speedBoostEnabledFlag: Boolean = try {
        context.getSharedPreferences("aero_torrent_engine_prefs", Context.MODE_PRIVATE)
            .getBoolean("torrent_speed_boost_enabled", true)
    } catch (_: Throwable) {
        true
    }

    private val _engineStatus = MutableStateFlow(
        TorrentEngineStatus(
            speedBoostEnabled = speedBoostEnabledFlag,
            pipelineDepth = if (speedBoostEnabledFlag) 64 else 32
        )
    )
    val engineStatus: StateFlow<TorrentEngineStatus> = _engineStatus.asStateFlow()

    fun isSpeedBoostEnabled(): Boolean = speedBoostEnabledFlag

    suspend fun setSpeedBoostEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        speedBoostEnabledFlag = enabled
        try {
            boostPrefs.edit().putBoolean("torrent_speed_boost_enabled", enabled).apply()
        } catch (_: Throwable) {
        }
        _engineStatus.update {
            it.copy(
                speedBoostEnabled = enabled,
                pipelineDepth = if (enabled) 64 else 32
            )
        }
        val prefs = preferenceRepository.getPreferencesDirect()
        applyPreferences(prefs)
        // Tune active coordinators live without dropping peer connections or resetting progress
        activeCoordinators.values.forEach { it.setSpeedBoostLive(enabled) }
        // Start any active torrents whose workers are not yet running
        ensureActiveTorrentsRunning()
    }

    fun hasActiveWorkers(): Boolean = activeJobs.values.any { it.isActive }

    suspend fun ensureActiveTorrentsRunning() = withContext(Dispatchers.IO) {
        resumePersistedActiveTorrents()
    }

    private val _lastRssPreviewItems = MutableStateFlow<Map<Long, List<RssFeedItem>>>(emptyMap())
    val lastRssPreviewItems: StateFlow<Map<Long, List<RssFeedItem>>> = _lastRssPreviewItems.asStateFlow()

    private var remoteServer: RemoteAccessServer? = null
    private var lastRemoteSignature: String = ""
    private var networkCallbackRegistered = false
    private var registeredNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var automationJob: Job? = null
    private var preferencesObserverJob: Job? = null

    fun startMonitoring() {
        scope.launch(Dispatchers.IO) {
            refreshVpnStatusOnly()
            registerVpnNetworkCallback()
            resumePersistedActiveTorrents()
        }
        if (automationJob == null) {
            automationJob = scope.launch(Dispatchers.IO) {
                var rssTick = 0
                while (isActive) {
                    try {
                        refreshEnvironmentAndThrottlers()
                        enforceScheduleAndVpnRules()
                        if (rssTick % 60 == 0) {
                            pollEnabledRssFeeds()
                        }
                        rssTick++
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Throwable) {
                        android.util.Log.w("TorrentEngine", "Automation loop tick warning: ${e.message}")
                    }
                    delay(5_000L)
                }
            }
        }

        if (preferencesObserverJob == null) {
            // Observe preference changes to reconfigure Remote Server, IP Filter, Throttlers immediately
            preferencesObserverJob = scope.launch(Dispatchers.IO) {
                preferenceRepository.getPreferencesFlow().collect { prefs ->
                    try {
                        applyPreferences(prefs)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        android.util.Log.w("TorrentEngine", "Preference apply warning: ${t.message}")
                    }
                }
            }
        }
    }

    private fun registerVpnNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    try { refreshVpnStatusOnly() } catch (_: Throwable) {}
                }
                override fun onLost(network: Network) {
                    try { refreshVpnStatusOnly() } catch (_: Throwable) {}
                }
                override fun onCapabilitiesChanged(network: Network, nc: android.net.NetworkCapabilities) {
                    try { refreshVpnStatusOnly() } catch (_: Throwable) {}
                }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            registeredNetworkCallback = callback
            networkCallbackRegistered = true
        } catch (_: Throwable) {
        }
    }

    fun refreshVpnStatusOnly() {
        try {
            val vpn = VpnAndCyberGhostHelper.inspectStatus(context)
            val net = AutoBandwidthOptimizer.evaluate(context)
            _engineStatus.update { it.copy(vpnStatus = vpn, networkCondition = net) }
        } catch (_: Throwable) {
        }
    }

    private suspend fun applyPreferences(prefs: PreferenceEntity) {
        ipFilterManager.loadRules(if (prefs.torrentIpFilterEnabled) prefs.torrentIpFilterRules else "")
        val net = AutoBandwidthOptimizer.evaluate(context)
        val turbo = speedBoostEnabledFlag
        if (prefs.torrentAutoBandwidth) {
            val dlCap = when {
                prefs.torrentGlobalDownloadCapKbps > 0 -> prefs.torrentGlobalDownloadCapKbps
                turbo -> 0 // Turbo Speed Boost: never artificially throttle downloads
                else -> net.recommendedDownloadCapKbps
            }
            val ulCap = if (prefs.torrentGlobalUploadCapKbps > 0) {
                minOf(prefs.torrentGlobalUploadCapKbps, net.recommendedUploadCapKbps)
            } else {
                net.recommendedUploadCapKbps
            }
            globalDlThrottler.setLimitKbps(dlCap)
            globalUlThrottler.setLimitKbps(ulCap)
        } else {
            globalDlThrottler.setLimitKbps(prefs.torrentGlobalDownloadCapKbps)
            globalUlThrottler.setLimitKbps(prefs.torrentGlobalUploadCapKbps)
        }

        val remoteSig = "${prefs.torrentRemoteEnabled}:${prefs.torrentRemotePort}:${prefs.torrentRemotePin}"
        if (remoteSig != lastRemoteSignature) {
            lastRemoteSignature = remoteSig
            if (prefs.torrentRemoteEnabled) {
                ensureRemoteServer().start(prefs.torrentRemotePort, prefs.torrentRemotePin)
            } else {
                remoteServer?.stop()
            }
        }
    }

    private fun ensureRemoteServer(): RemoteAccessServer {
        return remoteServer ?: RemoteAccessServer(
            getTasksSnapshot = { torrentsFlow.value },
            onAddMagnetOrUrl = { uri -> addTorrentFromInput(uri) },
            onPauseTorrent = { hash -> pauseTorrent(hash) },
            onResumeTorrent = { hash -> resumeTorrent(hash) },
            onRemoveTorrent = { hash -> removeTorrent(hash, deleteFiles = false) },
            onStateChanged = { state -> _engineStatus.update { it.copy(remoteServerState = state) } }
        ).also { remoteServer = it }
    }

    private suspend fun refreshEnvironmentAndThrottlers() {
        val prefs = preferenceRepository.getPreferencesDirect()
        val vpn = VpnAndCyberGhostHelper.inspectStatus(context)
        val net = AutoBandwidthOptimizer.evaluate(context)
        val sched = TorrentScheduler.evaluate(
            prefs.torrentSchedulerEnabled,
            prefs.torrentScheduleDaysJson,
            prefs.torrentScheduleStartMinute,
            prefs.torrentScheduleEndMinute,
            prefs.torrentScheduleAction
        )
        val list = torrentsFlow.value
        val active = list.count { it.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED") }
        val totalDl = list.sumOf { it.downloadRateBps }
        val totalUl = list.sumOf { it.uploadRateBps }
        val totalPeers = list.sumOf { it.peerCount }
        _engineStatus.update {
            it.copy(
                vpnStatus = vpn,
                networkCondition = net,
                scheduleDirective = sched,
                totalDownloadBps = totalDl,
                totalUploadBps = totalUl,
                peakDownloadBps = maxOf(it.peakDownloadBps, totalDl),
                activeCount = active,
                activePeerCount = totalPeers,
                speedBoostEnabled = speedBoostEnabledFlag,
                pipelineDepth = if (speedBoostEnabledFlag) 64 else 32
            )
        }
    }

    private suspend fun enforceScheduleAndVpnRules() {
        val prefs = preferenceRepository.getPreferencesDirect()
        val vpn = VpnAndCyberGhostHelper.inspectStatus(context)
        val directive = TorrentScheduler.evaluate(
            prefs.torrentSchedulerEnabled,
            prefs.torrentScheduleDaysJson,
            prefs.torrentScheduleStartMinute,
            prefs.torrentScheduleEndMinute,
            prefs.torrentScheduleAction
        )

        val tasks = torrentDao.getAllTorrentsDirect()
        for (task in tasks) {
            if (prefs.torrentRequireVpn && !vpn.isVpnActive && task.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED")) {
                stopWorker(task.infoHash)
                torrentDao.upsertTorrent(task.copy(status = "VPN_PAUSED", downloadRateBps = 0L, uploadRateBps = 0L))
            } else if (!prefs.torrentRequireVpn || vpn.isVpnActive) {
                if (task.status == "VPN_PAUSED") {
                    resumeTorrent(task.infoHash)
                }
            }

            if (directive == ScheduleDirective.PAUSE_ALL && task.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED")) {
                stopWorker(task.infoHash)
                torrentDao.upsertTorrent(task.copy(status = "SCHEDULE_PAUSED", downloadRateBps = 0L, uploadRateBps = 0L))
            } else if (directive != ScheduleDirective.PAUSE_ALL && task.status == "SCHEDULE_PAUSED") {
                resumeTorrent(task.infoHash)
            }
        }
    }

    suspend fun addTorrentFromInput(rawInput: String): Result<TorrentTaskEntity> = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty torrent link"))

        return@withContext try {
            val prefs = preferenceRepository.getPreferencesDirect()
            val folderLoc = resolveSaveLocation(prefs)
            val existingCount = torrentDao.getAllTorrentsDirect().size

            when {
                MagnetParser.isMagnetUri(trimmed) -> {
                    val magnet = MagnetParser.parse(trimmed)
                    val entity = TorrentTaskEntity(
                        infoHash = magnet.infoHashHex,
                        title = magnet.displayName,
                        sourceUri = trimmed,
                        saveLocation = folderLoc,
                        status = "METADATA",
                        totalBytes = magnet.exactLength,
                        queueOrder = existingCount,
                        isSequential = prefs.torrentSequentialByDefault,
                        encryptionMode = prefs.torrentEncryptionMode,
                        trackersJson = JSONArray(magnet.trackers).toString()
                    )
                    deletedHashes.remove(magnet.infoHashHex)
                    torrentDao.upsertTorrent(entity)
                    startWorker(entity, metainfo = null)
                    Result.success(entity)
                }
                trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> {
                    val req = Request.Builder().url(trimmed).get().build()
                    val bytes = okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} fetching .torrent")
                        resp.body?.bytes() ?: throw IllegalStateException("Empty .torrent file")
                    }
                    enqueueTorrentBytes(bytes, trimmed)
                }
                trimmed.startsWith("content://", true) || trimmed.startsWith("file://", true) -> {
                    val uri = Uri.parse(trimmed)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: File(uri.path ?: trimmed).readBytes()
                    enqueueTorrentBytes(bytes, trimmed)
                }
                else -> Result.failure(IllegalArgumentException("Provide a magnet:? link or .torrent file/URL"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun enqueueTorrentBytes(torrentBytes: ByteArray, sourceUri: String = ""): Result<TorrentTaskEntity> =
        withContext(Dispatchers.IO) {
            try {
                val meta = TorrentMetainfoParser.parse(torrentBytes)
                metainfoCache[meta.infoHashHex] = meta
                val prefs = preferenceRepository.getPreferencesDirect()
                val folderLoc = resolveSaveLocation(prefs)
                val existingCount = torrentDao.getAllTorrentsDirect().size

                val filesJson = serializeFilesJson(meta.files)
                val trackersJson = JSONArray(meta.trackers).toString()

                val entity = TorrentTaskEntity(
                    infoHash = meta.infoHashHex,
                    title = meta.name,
                    sourceUri = sourceUri.ifBlank { "torrent://${meta.infoHashHex}" },
                    saveLocation = folderLoc,
                    status = "DOWNLOADING",
                    totalBytes = meta.totalLength,
                    queueOrder = existingCount,
                    isSequential = prefs.torrentSequentialByDefault,
                    encryptionMode = prefs.torrentEncryptionMode,
                    filesJson = filesJson,
                    trackersJson = trackersJson,
                    pieceLength = meta.pieceLength,
                    totalPieces = meta.pieceHashes.size
                )
                deletedHashes.remove(meta.infoHashHex)
                torrentDao.upsertTorrent(entity)
                startWorker(entity, meta)
                Result.success(entity)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    internal fun isDirectoryWritable(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return false
            val probe = File(dir, ".aero_probe_${System.nanoTime()}")
            if (probe.createNewFile()) {
                probe.delete()
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun appScopedTorrentRoot(): File {
        val ext = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val candidate = File(ext ?: context.filesDir, "AeroTorrents")
        if (isDirectoryWritable(candidate)) return candidate
        return File(context.filesDir, "AeroTorrents").apply { mkdirs() }
    }

    private fun resolveSaveLocation(prefs: PreferenceEntity): String {
        if (prefs.torrentDownloadFolderUri.isNotBlank()) return prefs.torrentDownloadFolderUri
        val publicDl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val publicTarget = File(publicDl, "AeroTorrents")
        return if (isDirectoryWritable(publicTarget)) {
            publicTarget.absolutePath
        } else {
            appScopedTorrentRoot().absolutePath
        }
    }

    private fun resolveStagingDir(task: TorrentTaskEntity): File {
        if (task.saveLocation.startsWith("content://") || task.saveLocation.isBlank()) {
            return File(appScopedTorrentRoot(), task.infoHash)
        }
        val configuredDir = File(task.saveLocation, task.infoHash)
        val parentDir = File(task.saveLocation)
        // Verify real write access so Android 11+ Scoped Storage EACCES falls back cleanly
        if (isDirectoryWritable(parentDir)) {
            return configuredDir
        }
        return File(appScopedTorrentRoot(), task.infoHash)
    }

    fun resolveStagingDirForTask(task: TorrentTaskEntity): File = resolveStagingDir(task)

    fun listDownloadedFilesOnDisk(task: TorrentTaskEntity): List<File> {
        val dir = resolveStagingDir(task)
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile }.toList()
    }

    fun getDisplayFolderLabel(task: TorrentTaskEntity): String {
        val staging = resolveStagingDir(task)
        return if (task.saveLocation.startsWith("content://")) {
            val decoded = Uri.decode(task.saveLocation).substringAfterLast('/')
            "${decoded.ifBlank { "Selected SAF Folder" }} (Staging: ${staging.name})"
        } else {
            staging.absolutePath
        }
    }

    private suspend fun resumePersistedActiveTorrents() {
        try {
            val prefs = preferenceRepository.getPreferencesDirect()
            val tasks = torrentDao.getAllTorrentsDirect()
            val hasActive = tasks.any { it.status in setOf("DOWNLOADING", "METADATA", "QUEUED") }
            if (!hasActive) return
            if (prefs.torrentRequireVpn && !VpnAndCyberGhostHelper.inspectStatus(context).isVpnActive) return
            tasks.filter { it.status in setOf("DOWNLOADING", "METADATA", "QUEUED") }.forEach { task ->
                if (!activeJobs.containsKey(task.infoHash)) {
                    startWorker(task, metainfoCache[task.infoHash])
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
    }

    private suspend fun startWorker(task: TorrentTaskEntity, metainfo: TorrentMetainfo?) {
        val prefs = preferenceRepository.getPreferencesDirect()
        val vpn = VpnAndCyberGhostHelper.inspectStatus(context)
        if (prefs.torrentRequireVpn && !vpn.isVpnActive) {
            torrentDao.upsertTorrent(task.copy(status = "VPN_PAUSED"))
            return
        }
        val directive = TorrentScheduler.evaluate(
            prefs.torrentSchedulerEnabled,
            prefs.torrentScheduleDaysJson,
            prefs.torrentScheduleStartMinute,
            prefs.torrentScheduleEndMinute,
            prefs.torrentScheduleAction
        )
        if (directive == ScheduleDirective.PAUSE_ALL) {
            torrentDao.upsertTorrent(task.copy(status = "SCHEDULE_PAUSED"))
            return
        }

        stopWorker(task.infoHash)
        startForegroundServiceIfNeeded()

        val trackers = parseStringArray(task.trackersJson)
        val priorities = parseFilesJson(task.filesJson).associate { it.index to it.priority }
        val stagingDir = resolveStagingDir(task)

        val coordinator = TorrentDownloadCoordinator(
            context = context,
            infoHashHex = task.infoHash,
            infoHashBytes = BencodeCodec.fromHex(task.infoHash),
            peerId = trackerClient.generatePeerId(),
            initialMetainfo = metainfo ?: metainfoCache[task.infoHash],
            initialTrackers = trackers,
            stagingDir = stagingDir,
            safTreeUriString = task.saveLocation,
            filePriorities = priorities,
            isSequential = task.isSequential,
            encryptionMode = task.encryptionMode,
            dhtEnabled = prefs.torrentDhtEnabled,
            pexEnabled = prefs.torrentPexEnabled,
            seedOnlyMode = directive == ScheduleDirective.SEED_ONLY,
            globalDlThrottler = globalDlThrottler,
            globalUlThrottler = globalUlThrottler,
            taskDlThrottler = BandwidthThrottler(task.downloadCapKbps),
            taskUlThrottler = BandwidthThrottler(task.uploadCapKbps),
            ipFilterManager = ipFilterManager,
            trackerClient = trackerClient,
            speedBoostEnabled = speedBoostEnabledFlag,
            onFatalError = { errorMsg ->
                if (deletedHashes.contains(task.infoHash)) return@TorrentDownloadCoordinator
                val latest = torrentDao.getTorrentByHash(task.infoHash) ?: return@TorrentDownloadCoordinator
                torrentDao.upsertTorrent(
                    latest.copy(
                        status = "ERROR",
                        errorMessage = errorMsg,
                        downloadRateBps = 0L,
                        uploadRateBps = 0L
                    )
                )
            },
            onProgressUpdate = { meta, dlBytes, ulBytes, dlRate, ulRate, peers, seeds, donePieces, playableFile, isCompleted ->
                // Guard against resurrecting a torrent that was deleted while an update was in flight
                if (deletedHashes.contains(task.infoHash)) return@TorrentDownloadCoordinator
                if (meta != null) metainfoCache[task.infoHash] = meta
                val latest = torrentDao.getTorrentByHash(task.infoHash) ?: return@TorrentDownloadCoordinator
                if (latest.status == "PAUSED" || latest.status == "VPN_PAUSED" || latest.status == "SCHEDULE_PAUSED" || latest.status == "ERROR") {
                    return@TorrentDownloadCoordinator
                }
                val newStatus = when {
                    isCompleted -> "COMPLETED"
                    meta == null -> "METADATA"
                    else -> "DOWNLOADING"
                }
                val updatedFilesJson = if (meta != null && latest.filesJson == "[]") {
                    serializeFilesJson(meta.files)
                } else {
                    latest.filesJson
                }
                val totalLen = meta?.totalLength ?: latest.totalBytes
                val clampedDl = if (totalLen > 0L) dlBytes.coerceIn(0L, totalLen) else dlBytes.coerceAtLeast(0L)
                val updated = latest.copy(
                    title = meta?.name ?: latest.title,
                    status = newStatus,
                    errorMessage = null,
                    totalBytes = totalLen,
                    downloadedBytes = if (isCompleted && totalLen > 0L) totalLen else maxOf(latest.downloadedBytes.coerceAtMost(totalLen.coerceAtLeast(clampedDl)), clampedDl),
                    uploadedBytes = maxOf(latest.uploadedBytes, ulBytes),
                    downloadRateBps = dlRate,
                    uploadRateBps = ulRate,
                    peerCount = peers,
                    seedCount = seeds,
                    filesJson = updatedFilesJson,
                    pieceLength = meta?.pieceLength ?: latest.pieceLength,
                    totalPieces = meta?.pieceHashes?.size ?: latest.totalPieces,
                    completedPieces = maxOf(latest.completedPieces, donePieces),
                    primaryMediaPath = playableFile?.absolutePath ?: latest.primaryMediaPath,
                    completedAtMs = if (isCompleted && latest.completedAtMs == null) System.currentTimeMillis() else latest.completedAtMs
                )
                if (deletedHashes.contains(task.infoHash)) return@TorrentDownloadCoordinator
                torrentDao.upsertTorrent(updated)

                if (isCompleted) {
                    onTorrentCompleted(updated, stagingDir)
                }
            }
        )

        deletedHashes.remove(task.infoHash)
        activeCoordinators[task.infoHash] = coordinator
        activeJobs[task.infoHash] = scope.launch(Dispatchers.IO) {
            try {
                coordinator.runSession()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.e("TorrentEngine", "Torrent session failed for ${task.infoHash}", t)
                if (!deletedHashes.contains(task.infoHash)) {
                    torrentDao.getTorrentByHash(task.infoHash)?.let { cur ->
                        if (cur.status in setOf("DOWNLOADING", "METADATA", "QUEUED")) {
                            torrentDao.upsertTorrent(
                                cur.copy(
                                    status = "ERROR",
                                    errorMessage = t.message ?: "Download interrupted",
                                    downloadRateBps = 0L,
                                    uploadRateBps = 0L
                                )
                            )
                        }
                    }
                }
            } finally {
                activeCoordinators.remove(task.infoHash)
                activeJobs.remove(task.infoHash)
            }
        }
    }

    private suspend fun onTorrentCompleted(task: TorrentTaskEntity, stagingDir: File) {
        val prefs = preferenceRepository.getPreferencesDirect()
        // 1. Run real integrated malware/safety scan if enabled
        if (prefs.torrentAutoScanCompleted) {
            scanTorrentFiles(task.infoHash)
        }
        // 2. Import downloaded video/audio files into Aero Player MediaLibrary
        val mediaExts = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus")
        if (stagingDir.exists()) {
            stagingDir.walkTopDown().filter { it.isFile && it.extension.lowercase() in mediaExts }.forEach { f ->
                val isVideo = f.extension.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts")
                mediaRepository.addMediaItem(
                    MediaEntity(
                        uriString = Uri.fromFile(f).toString(),
                        title = f.nameWithoutExtension.ifBlank { f.name },
                        artist = "Aero Torrent",
                        album = task.title,
                        duration = 0L,
                        size = f.length(),
                        dateAdded = System.currentTimeMillis() / 1000L,
                        isVideo = isVideo,
                        path = f.absolutePath,
                        mimeType = if (isVideo) "video/*" else "audio/*",
                        genre = if (isVideo) "Video" else "Audio"
                    )
                )
            }
        }

        // 3. Evaluate Auto-Shutdown / Post-Completion actions when all active downloads have finished
        val remainingActive = torrentDao.getAllTorrentsDirect().count {
            it.infoHash != task.infoHash && it.status in setOf("DOWNLOADING", "METADATA", "QUEUED")
        }
        if (remainingActive == 0) {
            when (prefs.torrentPostCompleteAction) {
                "PAUSE_SEEDING" -> pauseAllTorrents()
                "STOP_SERVICE" -> {
                    context.stopService(Intent(context, TorrentDownloadService::class.java))
                }
                "EXIT_APP" -> {
                    pauseAllTorrents()
                    context.stopService(Intent(context, TorrentDownloadService::class.java))
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    }

    suspend fun scanTorrentFiles(infoHash: String): TorrentScanReport {
        val task = torrentDao.getTorrentByHash(infoHash)
            ?: return TorrentScanReport("UNSCANNED", "Task not found", emptyList())
        val dir = resolveStagingDir(task)
        val diskFiles = if (dir.exists()) dir.walkTopDown().filter { it.isFile }.toList() else emptyList()
        val report = FileSafetyScanner.scanPaths(diskFiles)
        torrentDao.upsertTorrent(task.copy(scanStatus = report.overallStatus, scanSummary = report.summary))
        return report
    }

    suspend fun pauseTorrent(infoHash: String) = withContext(Dispatchers.IO) {
        stopWorker(infoHash)
        torrentDao.getTorrentByHash(infoHash)?.let {
            torrentDao.upsertTorrent(it.copy(status = "PAUSED", downloadRateBps = 0L, uploadRateBps = 0L))
        }
    }

    suspend fun resumeTorrent(infoHash: String) = withContext(Dispatchers.IO) {
        deletedHashes.remove(infoHash)
        val task = torrentDao.getTorrentByHash(infoHash) ?: return@withContext
        val nextStatus = if (task.totalPieces > 0 || metainfoCache.containsKey(infoHash)) "DOWNLOADING" else "METADATA"
        val next = task.copy(status = nextStatus, errorMessage = null)
        torrentDao.upsertTorrent(next)
        startWorker(next, metainfoCache[infoHash])
    }

    suspend fun pauseAllTorrents() = withContext(Dispatchers.IO) {
        torrentDao.getAllTorrentsDirect().forEach { t ->
            if (t.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED")) {
                pauseTorrent(t.infoHash)
            }
        }
    }

    suspend fun resumeAllTorrents() = withContext(Dispatchers.IO) {
        torrentDao.getAllTorrentsDirect().forEach { t ->
            if (t.status in setOf("PAUSED", "VPN_PAUSED", "SCHEDULE_PAUSED", "QUEUED", "ERROR")) {
                resumeTorrent(t.infoHash)
            }
        }
    }

    suspend fun removeTorrent(infoHash: String, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        // Mark deleted FIRST so any in-flight progress callback can never re-insert the row
        deletedHashes.add(infoHash)
        val runningJob = activeJobs.remove(infoHash)
        activeCoordinators.remove(infoHash)?.stop()
        runningJob?.cancel()
        try {
            withTimeoutOrNull(1200L) { runningJob?.join() }
        } catch (_: Throwable) {
        }
        val task = torrentDao.getTorrentByHash(infoHash)
        torrentDao.deleteTorrent(infoHash)
        metainfoCache.remove(infoHash)
        if (deleteFiles && task != null) {
            val dirsToClean = linkedSetOf(resolveStagingDir(task))
            if (task.saveLocation.isNotBlank() && !task.saveLocation.startsWith("content://")) {
                dirsToClean.add(File(task.saveLocation, task.infoHash))
            }
            dirsToClean.forEach { stagingDir ->
                parseFilesJson(task.filesJson).forEach { entry ->
                    try {
                        mediaRepository.deleteMedia(Uri.fromFile(File(stagingDir, entry.path)).toString())
                    } catch (_: Throwable) {
                    }
                }
                try {
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                } catch (_: Throwable) {
                }
            }
        }
    }

    suspend fun setTorrentSequential(infoHash: String, sequential: Boolean) {
        val task = torrentDao.getTorrentByHash(infoHash) ?: return
        val updated = task.copy(isSequential = sequential)
        torrentDao.upsertTorrent(updated)
        if (updated.status in setOf("DOWNLOADING", "METADATA")) {
            startWorker(updated, metainfoCache[infoHash])
        }
    }

    suspend fun setTorrentSpeedCaps(infoHash: String, downloadCapKbps: Int, uploadCapKbps: Int, encryptionMode: String) {
        val task = torrentDao.getTorrentByHash(infoHash) ?: return
        val updated = task.copy(
            downloadCapKbps = downloadCapKbps.coerceAtLeast(0),
            uploadCapKbps = uploadCapKbps.coerceAtLeast(0),
            encryptionMode = encryptionMode
        )
        torrentDao.upsertTorrent(updated)
        if (updated.status in setOf("DOWNLOADING", "METADATA")) {
            startWorker(updated, metainfoCache[infoHash])
        }
    }

    suspend fun updateFilePriority(infoHash: String, fileIndex: Int, newPriority: String) {
        val task = torrentDao.getTorrentByHash(infoHash) ?: return
        val files = parseFilesJson(task.filesJson).map {
            if (it.index == fileIndex) it.copy(priority = newPriority) else it
        }
        val updated = task.copy(filesJson = serializeFilesJson(files))
        torrentDao.upsertTorrent(updated)
        if (updated.status in setOf("DOWNLOADING", "METADATA")) {
            startWorker(updated, metainfoCache[infoHash])
        }
    }

    suspend fun removeTorrentFile(infoHash: String, fileIndex: Int, deleteFromDisk: Boolean) {
        val task = torrentDao.getTorrentByHash(infoHash) ?: return
        val currentFiles = parseFilesJson(task.filesJson)
        val removedEntry = currentFiles.firstOrNull { it.index == fileIndex } ?: return
        val remainingFiles = currentFiles.filterNot { it.index == fileIndex }

        val stagingDir = resolveStagingDir(task)
        val targetFile = File(stagingDir, removedEntry.path)
        val wasDownloading = task.status in setOf("DOWNLOADING", "METADATA")

        if (deleteFromDisk) {
            try {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                mediaRepository.deleteMedia(Uri.fromFile(targetFile).toString())
            } catch (_: Throwable) {
            }
        }

        val nextPrimaryPath = if (task.primaryMediaPath == targetFile.absolutePath) {
            val mediaExts = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus")
            remainingFiles
                .filter { it.priority != "SKIP" }
                .map { File(stagingDir, it.path) }
                .firstOrNull { f -> f.exists() && f.length() > 0L && f.extension.lowercase() in mediaExts }
                ?.absolutePath
        } else {
            task.primaryMediaPath
        }

        val updatedTotalBytes = remainingFiles.sumOf { it.length }.let { sum -> if (sum > 0L) sum else task.totalBytes }
        val hasActiveRemaining = remainingFiles.any { it.priority != "SKIP" }
        val updatedStatus = if (!hasActiveRemaining && wasDownloading) "PAUSED" else task.status

        val updated = task.copy(
            filesJson = serializeFilesJson(remainingFiles),
            totalBytes = updatedTotalBytes,
            primaryMediaPath = nextPrimaryPath,
            status = updatedStatus,
            downloadRateBps = if (updatedStatus == "PAUSED") 0L else task.downloadRateBps,
            uploadRateBps = if (updatedStatus == "PAUSED") 0L else task.uploadRateBps
        )
        torrentDao.upsertTorrent(updated)

        if (wasDownloading) {
            if (hasActiveRemaining) {
                startWorker(updated, metainfoCache[infoHash])
            } else {
                stopWorker(infoHash)
            }
        }
    }

    suspend fun moveQueueOrder(infoHash: String, delta: Int) {
        val list = torrentDao.getAllTorrentsDirect().sortedBy { it.queueOrder }.toMutableList()
        val idx = list.indexOfFirst { it.infoHash == infoHash }
        val target = (idx + delta).coerceIn(0, list.lastIndex)
        if (idx < 0 || idx == target) return
        val item = list.removeAt(idx)
        list.add(target, item)
        val reindexed = list.mapIndexed { i, t -> t.copy(queueOrder = i) }
        torrentDao.upsertTorrents(reindexed)
    }

    // RSS Auto-Downloader operations
    suspend fun addRssFeed(title: String, url: String, includeFilter: String, excludeFilter: String, autoDownload: Boolean) {
        val feed = RssFeedEntity(
            title = title.ifBlank { Uri.parse(url).host ?: "RSS Feed" },
            feedUrl = url.trim(),
            includeFilter = includeFilter.trim(),
            excludeFilter = excludeFilter.trim(),
            autoDownload = autoDownload,
            enabled = true
        )
        val id = rssFeedDao.upsertFeed(feed)
        refreshSingleFeed(feed.copy(id = id))
    }

    suspend fun deleteRssFeed(feedId: Long) {
        rssFeedDao.deleteFeed(feedId)
        _lastRssPreviewItems.update { it - feedId }
    }

    suspend fun pollEnabledRssFeeds() {
        rssFeedDao.getAllFeedsDirect().filter { it.enabled }.forEach { feed ->
            refreshSingleFeed(feed)
        }
    }

    suspend fun refreshSingleFeed(feed: RssFeedEntity): Result<List<RssFeedItem>> = withContext(Dispatchers.IO) {
        val res = rssDownloader.fetchFeedItems(feed.feedUrl, feed.includeFilter, feed.excludeFilter)
        res.onSuccess { items ->
            _lastRssPreviewItems.update { it + (feed.id to items) }
            val matched = items.filter { it.matchesFilter }
            var lastTitle = feed.lastMatchedTitle
            if (feed.autoDownload) {
                val existingSources = torrentDao.getAllTorrentsDirect().map { it.sourceUri }.toSet()
                matched.take(5).forEach { entry ->
                    if (entry.downloadUri !in existingSources && entry.title != feed.lastMatchedTitle) {
                        addTorrentFromInput(entry.downloadUri)
                        lastTitle = entry.title
                    }
                }
            }
            rssFeedDao.upsertFeed(
                feed.copy(
                    lastCheckedMs = System.currentTimeMillis(),
                    lastMatchedTitle = lastTitle.ifBlank { matched.firstOrNull()?.title ?: "" },
                    lastItemCount = items.size
                )
            )
        }
        res
    }

    fun getPlayableMediaEntity(task: TorrentTaskEntity): MediaEntity? {
        val path = task.primaryMediaPath ?: return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        return buildMediaEntityForFile(file, task.title)
    }

    fun getPlayableMediaForFile(task: TorrentTaskEntity, fileEntry: TorrentFileEntry): MediaEntity? {
        val file = File(resolveStagingDir(task), fileEntry.path)
        if (!file.exists() || file.length() <= 0L) return null
        return buildMediaEntityForFile(file, task.title)
    }

    fun getPlayableMediaForDiskFile(file: File, albumTitle: String = "Aero Torrent"): MediaEntity? {
        if (!file.exists() || file.length() <= 0L) return null
        return buildMediaEntityForFile(file, albumTitle)
    }

    private fun buildMediaEntityForFile(file: File, albumTitle: String): MediaEntity {
        val ext = file.extension.lowercase()
        val isVideo = ext !in setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus")
        return MediaEntity(
            uriString = Uri.fromFile(file).toString(),
            title = file.nameWithoutExtension.ifBlank { albumTitle },
            artist = "Aero Torrent (Streaming)",
            album = albumTitle,
            duration = 0L,
            size = file.length(),
            dateAdded = System.currentTimeMillis() / 1000L,
            isVideo = isVideo,
            path = file.absolutePath,
            mimeType = if (isVideo) "video/*" else "audio/*",
            genre = if (isVideo) "Video" else "Audio"
        )
    }

    private fun stopWorker(infoHash: String) {
        activeCoordinators.remove(infoHash)?.stop()
        activeJobs.remove(infoHash)?.cancel()
    }

    private fun startForegroundServiceIfNeeded() {
        try {
            val intent = Intent(context, TorrentDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("TorrentEngine", "Foreground service launch skipped: ${e.message}")
        }
    }

    fun shutdown() {
        automationJob?.cancel()
        preferencesObserverJob?.cancel()
        registeredNetworkCallback?.let { cb ->
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            } catch (_: Throwable) {
            }
        }
        registeredNetworkCallback = null
        networkCallbackRegistered = false
        remoteServer?.stop()
        activeCoordinators.values.forEach { it.stop() }
        activeJobs.values.forEach { it.cancel() }
    }

    companion object {
        fun parseFilesJson(json: String): List<TorrentFileEntry> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    TorrentFileEntry(
                        index = o.optInt("index", i),
                        path = o.optString("path", "file_$i"),
                        length = o.optLong("length", 0L),
                        priority = o.optString("priority", "NORMAL"),
                        downloadedBytes = o.optLong("downloadedBytes", 0L)
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun serializeFilesJson(files: List<TorrentFileEntry>): String {
            val arr = JSONArray()
            files.forEach { f ->
                arr.put(JSONObject().apply {
                    put("index", f.index)
                    put("path", f.path)
                    put("length", f.length)
                    put("priority", f.priority)
                    put("downloadedBytes", f.downloadedBytes)
                })
            }
            return arr.toString()
        }

        fun parseStringArray(json: String): List<String> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * Application-scoped holder so background P2P transfers, notifications, RSS auto-downloads,
 * and remote server continue running reliably even when the UI Activity/ViewModel recreates.
 */
object TorrentEngineHolder {
    @Volatile
    private var instance: TorrentEngineManager? = null
    private val lock = Any()
    private val engineHandler = CoroutineExceptionHandler { _, t ->
        android.util.Log.w("TorrentEngineHolder", "Handled engine error: ${t.message}")
    }
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + engineHandler)

    fun getOrCreate(context: Context): TorrentEngineManager {
        instance?.let { return it }
        return synchronized(lock) {
            instance ?: run {
                val appCtx = context.applicationContext
                val db = AppDatabase.getDatabase(appCtx)
                TorrentEngineManager(
                    context = appCtx,
                    database = db,
                    mediaRepository = MediaRepository(db.mediaDao()),
                    preferenceRepository = PreferenceRepository(db.preferenceDao()),
                    scope = engineScope
                ).also {
                    instance = it
                    it.startMonitoring()
                }
            }
        }
    }
}
