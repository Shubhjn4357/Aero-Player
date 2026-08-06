package com.example.ui.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.database.HistoryEntity
import com.example.data.database.MediaEntity
import com.example.data.database.PreferenceEntity
import com.example.data.database.displayArtist
import com.example.data.repository.HistoryRepository
import com.example.data.repository.MediaRepository
import com.example.data.repository.PreferenceRepository
import com.example.data.database.AppDatabase
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.data.model.VideoFile
import com.example.data.model.Folder
import com.example.data.model.AeroPlaybackManager
import com.example.data.model.BrowseScreenState
import com.example.data.model.SelectionState

class MainViewModel(application: Application) : AndroidViewModel(application), AeroPlaybackManager {

    internal val db = AppDatabase.getDatabase(application)
    internal val mediaRepository = MediaRepository(db.mediaDao())
    internal val historyRepository = HistoryRepository(db.historyDao())
    internal val preferenceRepository = PreferenceRepository(db.preferenceDao())

    // 1. AeroPlaybackManager State Flow implementations
    internal val _currentQueue = MutableStateFlow<List<VideoFile>>(emptyList())
    override val currentQueue: StateFlow<List<VideoFile>> = _currentQueue.asStateFlow()

    internal val _currentPlayingVideo = MutableStateFlow<VideoFile?>(null)
    override val currentPlayingVideo: StateFlow<VideoFile?> = _currentPlayingVideo.asStateFlow()

    internal val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // 2. Browse Screen and Selection States
    internal val _browseScreenState = MutableStateFlow<BrowseScreenState>(BrowseScreenState.FolderList)
    val browseScreenState: StateFlow<BrowseScreenState> = _browseScreenState.asStateFlow()

    internal val _selectionState = MutableStateFlow(SelectionState())
    val selectionState: StateFlow<SelectionState> = _selectionState.asStateFlow()

    // Picture-in-Picture & System Notification State
    val isInPipMode = MutableStateFlow(false)

    internal val _pendingDeleteIntent = MutableStateFlow<android.content.IntentSender?>(null)
    val pendingDeleteIntent: StateFlow<android.content.IntentSender?> = _pendingDeleteIntent.asStateFlow()

    fun clearPendingDeleteIntent() {
        _pendingDeleteIntent.value = null
    }
    internal val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    internal val CHANNEL_ID = "aero_player_channel"
    internal var mediaSession: androidx.media3.session.MediaSession? = null

    // Real Equalizer States
    internal var androidEqualizer: android.media.audiofx.Equalizer? = null
    
    internal val _equalizerEnabled = MutableStateFlow(true)
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()
    
    internal val _equalizerBands = MutableStateFlow<List<EqualizerBand>>(listOf(
        EqualizerBand(0, 60, -1500, 1500, 0),
        EqualizerBand(1, 230, -1500, 1500, 0),
        EqualizerBand(2, 910, -1500, 1500, 0),
        EqualizerBand(3, 4000, -1500, 1500, 0),
        EqualizerBand(4, 14000, -1500, 1500, 0)
    ))
    val equalizerBands: StateFlow<List<EqualizerBand>> = _equalizerBands.asStateFlow()
    
    internal val _currentEqualizerPreset = MutableStateFlow("Normal")
    val currentEqualizerPreset: StateFlow<String> = _currentEqualizerPreset.asStateFlow()

    private var isExoPlayerInitialized = false

    val exoPlayer: ExoPlayer by lazy {
        isExoPlayerInitialized = true
        var player: ExoPlayer? = null
        try {
            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(application).apply {
                setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setEnableDecoderFallback(true)
                setAllowedVideoJoiningTimeMs(5000L)
                setMediaCodecSelector(androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT)
            }

            val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(application).apply {
                parameters = buildUponParameters()
                    .setSelectUndeterminedTextLanguage(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedRendererCapabilitiesIfNecessary(false)
                    .setAllowAudioMixedChannelCountAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedSampleRateAdaptiveness(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                    .build()
            }

            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1500,  // minBufferMs (ultra fast start)
                    30000, // maxBufferMs
                    200,   // bufferForPlaybackMs (start after 200ms buffering)
                    500    // bufferForPlaybackAfterRebufferMs
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(10000, true)
                .build()

            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setKeepPostFor302Redirects(true)

            val baseDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
                application,
                httpDataSourceFactory
            )
            val dataSourceFactory = com.example.util.SanitizingDataSourceFactory(baseDataSourceFactory)

            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)

            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                dataSourceFactory,
                extractorsFactory
            ).setLoadErrorHandlingPolicy(
                    object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                            return if (loadErrorInfo.errorCount <= 6) (1000L * loadErrorInfo.errorCount) else androidx.media3.common.C.TIME_UNSET
                        }
                        override fun getMinimumLoadableRetryCount(dataType: Int): Int {
                            return 6
                        }
                    }
                )

            player = ExoPlayer.Builder(application)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
        } catch (t: Throwable) {
            Log.e("MainViewModel", "Failed to build optimized ExoPlayer, using simple fallback", t)
            try {
                player = ExoPlayer.Builder(application).build()
            } catch (t2: Throwable) {
                Log.e("MainViewModel", "Fatal: Failed to build even simple ExoPlayer", t2)
            }
        }

        val finalPlayer = player ?: throw IllegalStateException("Could not initialize ExoPlayer")

        finalPlayer.apply {
            playWhenReady = true
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateNotificationState()
                    _isPlaying.value = isPlaying
                }
                override fun onPlaybackStateChanged(state: Int) {
                    updateNotificationState()
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val currentUri = mediaItem?.localConfiguration?.uri?.toString() ?: mediaItem?.mediaId
                    if (currentUri != null) {
                        val currentVideo = _currentQueue.value.find { it.id == currentUri }
                        if (currentVideo != null) {
                            _currentPlayingVideo.value = currentVideo
                        }
                        
                        val matchingMediaEntity = _playQueue.value.find { it.uriString == currentUri || it.path == currentUri }
                            ?: filteredMediaList.value.find { it.uriString == currentUri || it.path == currentUri }
                            ?: mediaItem?.let { mi ->
                                com.example.data.database.MediaEntity(
                                    uriString = currentUri,
                                    title = mi.mediaMetadata.title?.toString() ?: currentUri.substringAfterLast('/'),
                                    artist = mi.mediaMetadata.artist?.toString() ?: "Local Media",
                                    album = mi.mediaMetadata.albumTitle?.toString() ?: "Local Album",
                                    duration = 0L,
                                    size = 0L,
                                    dateAdded = System.currentTimeMillis(),
                                    isVideo = true,
                                    path = currentUri,
                                    mimeType = "video/mp4"
                                )
                            }
                        if (matchingMediaEntity != null) {
                            _currentPlayingItem.value = matchingMediaEntity
                            val qIdx = _playQueue.value.indexOfFirst { it.uriString == matchingMediaEntity.uriString }
                            if (qIdx >= 0) {
                                _currentQueueIndex.value = qIdx
                            }
                        }
                    }
                    updateNotificationState()
                    updateWidgets()
                }
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    initEqualizer(audioSessionId)
                }
            })
            try {
                val mediaSessionCallback = object : androidx.media3.session.MediaSession.Callback {
                    override fun onMediaButtonEvent(
                        session: androidx.media3.session.MediaSession,
                        controllerInfo: androidx.media3.session.MediaSession.ControllerInfo,
                        intent: Intent
                    ): Boolean {
                        val keyEvent = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? android.view.KeyEvent
                        }
                        if (keyEvent != null && keyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                            when (keyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                android.view.KeyEvent.KEYCODE_HEADSETHOOK,
                                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                    PlayerControlBridge.playPause()
                                    return true
                                }
                                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                    PlayerControlBridge.next()
                                    return true
                                }
                                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                    PlayerControlBridge.prev()
                                    return true
                                }
                                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    PlayerControlBridge.playPause()
                                    return true
                                }
                            }
                        }
                        return super.onMediaButtonEvent(session, controllerInfo, intent)
                    }

                    override fun onPlayerCommandRequest(
                        session: androidx.media3.session.MediaSession,
                        controllerInfo: androidx.media3.session.MediaSession.ControllerInfo,
                        playerCommand: Int
                    ): Int {
                        when (playerCommand) {
                            androidx.media3.common.Player.COMMAND_PLAY_PAUSE -> {
                                PlayerControlBridge.playPause()
                                return androidx.media3.session.SessionResult.RESULT_SUCCESS
                            }
                            androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
                            androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                                PlayerControlBridge.next()
                                return androidx.media3.session.SessionResult.RESULT_SUCCESS
                            }
                            androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
                            androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                                PlayerControlBridge.prev()
                                return androidx.media3.session.SessionResult.RESULT_SUCCESS
                            }
                        }
                        return super.onPlayerCommandRequest(session, controllerInfo, playerCommand)
                    }
                }

                mediaSession = androidx.media3.session.MediaSession.Builder(application, this)
                    .setCallback(mediaSessionCallback)
                    .build()
            } catch (e: Throwable) {
                Log.e("MainViewModel", "Error building MediaSession", e)
            }
            PlayerControlBridge.instance = this@MainViewModel
        }
    }

    // Preferences State
    val preferencesState: StateFlow<PreferenceEntity> = preferenceRepository.getPreferencesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = run {
                val isCompleted = try {
                    application.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                        .getBoolean("onboarding_completed", false)
                } catch (e: Exception) { false }
                PreferenceEntity(onboardingCompleted = isCompleted)
            }
        )

    // Raw media from DB
    internal val _rawMediaList = mediaRepository.getMediaFlow()

    // UI control states (Search, filter, sort)
    internal val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    internal val _selectedTab = MutableStateFlow("Play") // "Play", "More"
    val selectedTab: StateFlow<String> = _selectedTab.asStateFlow()

    internal val _playSubTab = MutableStateFlow("Video") // "Video", "Audio"
    val playSubTab: StateFlow<String> = _playSubTab.asStateFlow()

    // Combine raw media, search query, tab selection, and preference sorting for the output flow
    val filteredMediaList: StateFlow<List<MediaEntity>> = combine(
        _rawMediaList,
        _searchQuery,
        _selectedTab,
        _playSubTab,
        preferencesState
    ) { media, search, tab, subTab, prefs ->
        val bannedFolders = try {
            val array = org.json.JSONArray(prefs.bannedFoldersJson)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList<String>()
        }

        val deletedUris = try {
            val array = org.json.JSONArray(prefs.deletedUrisJson)
            val set = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                set.add(array.getString(i))
            }
            set
        } catch (e: Exception) {
            emptySet<String>()
        }

        val filtered = media.filter { item ->
            // Exclude deleted items
            if (deletedUris.contains(item.uriString)) return@filter false

            // Exclude local files physically deleted from storage
            if (item.path.startsWith("/") && !item.uriString.startsWith("android.resource://") && !item.uriString.startsWith("http://") && !item.uriString.startsWith("https://") && !java.io.File(item.path).exists()) {
                return@filter false
            }

            // Exclude banned folders
            val isBanned = bannedFolders.any { banned ->
                if (banned.isBlank()) false
                else if (banned.startsWith("/")) {
                    item.path.startsWith(banned)
                } else {
                    item.path.contains("/$banned/", ignoreCase = true) ||
                    java.io.File(item.path).parentFile?.name?.equals(banned, ignoreCase = true) == true
                }
            }
            if (isBanned) return@filter false

            // Filter by Tab (Video / Audio) in the Play tab
            val matchesTab = when (subTab) {
                "Video" -> item.isVideo
                "Audio" -> !item.isVideo
                else -> true
            }
            
            // Filter by Search
            val matchesSearch = if (search.isEmpty()) {
                true
            } else {
                item.title.contains(search, ignoreCase = true) || 
                (item.artist?.contains(search, ignoreCase = true) ?: false)
            }
            matchesTab && matchesSearch
        }

        // Apply Sorting based on Preferences (including merge group wise options)
        when (prefs.sortBy) {
            "title" -> {
                if (prefs.sortAscending) filtered.sortedBy { it.title.lowercase() }
                else filtered.sortedByDescending { it.title.lowercase() }
            }
            "date" -> {
                if (prefs.sortAscending) filtered.sortedBy { it.dateAdded }
                else filtered.sortedByDescending { it.dateAdded }
            }
            "size" -> {
                if (prefs.sortAscending) filtered.sortedBy { it.size }
                else filtered.sortedByDescending { it.size }
            }
            "folder" -> {
                // Merge/Group folder-wise
                val getParentFolder = { path: String ->
                    val file = java.io.File(path)
                    file.parent ?: "Root"
                }
                if (prefs.sortAscending) {
                    filtered.sortedWith(compareBy<MediaEntity> { getParentFolder(it.path).lowercase() }.thenBy { it.title.lowercase() })
                } else {
                    filtered.sortedWith(compareByDescending<MediaEntity> { getParentFolder(it.path).lowercase() }.thenByDescending { it.title.lowercase() })
                }
            }
            "artist" -> {
                // Merge/Group artist-wise
                if (prefs.sortAscending) {
                    filtered.sortedWith(compareBy<MediaEntity> { (it.artist ?: "Unknown").lowercase() }.thenBy { it.title.lowercase() })
                } else {
                    filtered.sortedWith(compareByDescending<MediaEntity> { (it.artist ?: "Unknown").lowercase() }.thenByDescending { it.title.lowercase() })
                }
            }
            else -> filtered
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Playback History Flow
    val historyState: StateFlow<List<HistoryEntity>> = historyRepository.getHistoryFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current playing item (selected for full screen player)
    internal val _currentPlayingItem = MutableStateFlow<MediaEntity?>(null)
    val currentPlayingItem: StateFlow<MediaEntity?> = _currentPlayingItem.asStateFlow()

    // Active playback queue
    internal val _playQueue = MutableStateFlow<List<MediaEntity>>(emptyList())
    val playQueue: StateFlow<List<MediaEntity>> = _playQueue.asStateFlow()

    internal val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    // Media Scanning State
    internal val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Request flag for audio-only video playback
    var audioOnlyPlaybackRequested = false

    private var mediaStoreObserver: android.database.ContentObserver? = null
    private var autoScanDebounceJob: kotlinx.coroutines.Job? = null

    fun registerMediaStoreObserver() {
        if (mediaStoreObserver != null) return
        val app = getApplication<Application>()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        mediaStoreObserver = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                super.onChange(selfChange, uri)
                autoScanDebounceJob?.cancel()
                autoScanDebounceJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(1500)
                    Log.d("MainViewModel", "Dynamic ContentObserver triggered auto scan for URI: $uri")
                    scanLocalMedia()
                }
            }
        }
        try {
            val resolver = app.contentResolver
            resolver.registerContentObserver(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaStoreObserver!!
            )
            resolver.registerContentObserver(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaStoreObserver!!
            )
            Log.d("MainViewModel", "Registered MediaStore ContentObserver successfully")
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to register MediaStore ContentObserver", e)
        }
    }

    fun unregisterMediaStoreObserver() {
        mediaStoreObserver?.let { observer ->
            try {
                getApplication<Application>().contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to unregister ContentObserver", e)
            }
            mediaStoreObserver = null
        }
    }

    init {
        createNotificationChannel()
        registerMediaStoreObserver()
        // Playback progress notification ticker
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                try {
                    if (_currentPlayingItem.value != null && _isPlaying.value) {
                        updateNotificationState()
                    }
                } catch (e: Throwable) {
                    Log.e("MainViewModel", "Ticker update failed", e)
                }
            }
        }
        // Trigger initial media scan if preferences say so and onboarding is completed
        viewModelScope.launch {
            try {
                val prefs = preferenceRepository.getPreferencesDirect()
                if (prefs.autoScanEnabled && prefs.onboardingCompleted) {
                    scanLocalMedia()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Initial media scan check failed", e)
            }
        }
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Aero Player Status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Media Playback & Scanning Progress"
                }
                notificationManager.createNotificationChannel(channel)
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to create notification channel", e)
        }
    }

    fun showScanningProgressNotification(progress: Int, total: Int) {
        val appIcon = try {
            getApplication<Application>().applicationInfo.icon.takeIf { it != 0 } ?: com.example.R.mipmap.ic_launcher
        } catch (e: Exception) {
            com.example.R.mipmap.ic_launcher
        }

        val builder = NotificationCompat.Builder(getApplication(), CHANNEL_ID)
            .setSmallIcon(appIcon)
            .setContentTitle("Scanning Local Media")
            .setContentText(if (total > 0) "Scanned $progress / $total files" else "Found $progress files...")
            .setProgress(total.coerceAtLeast(100), progress, total <= 0)
            .setOngoing(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            notificationManager.notify(101, builder.build())
        } catch (e: Exception) {
            Log.e("MainViewModel", "Notification error", e)
        }
    }

    fun dismissScanningNotification() {
        try {
            notificationManager.cancel(101)
            val appIcon = try {
                getApplication<Application>().applicationInfo.icon.takeIf { it != 0 } ?: com.example.R.mipmap.ic_launcher
            } catch (e: Exception) {
                com.example.R.mipmap.ic_launcher
            }
            val builder = NotificationCompat.Builder(getApplication(), CHANNEL_ID)
                .setSmallIcon(appIcon)
                .setContentTitle("Scan Completed")
                .setContentText("Your media library is updated.")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
            notificationManager.notify(102, builder.build())

            // Auto dismiss "Scan Completed" notification after 1.5 seconds so it doesn't get stuck
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    notificationManager.cancel(102)
                } catch (e: Exception) {}
            }, 1500)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Notification error", e)
        }
    }

    fun updatePlaybackNotification(title: String, artist: String, progressMs: Long, durationMs: Long, isPlaying: Boolean) {
        val app = getApplication<Application>()
        val appIcon = try {
            app.applicationInfo.icon.takeIf { it != 0 } ?: com.example.R.mipmap.ic_launcher
        } catch (e: Exception) {
            com.example.R.mipmap.ic_launcher
        }

        val openAppIntent = android.app.PendingIntent.getActivity(
            app, 0,
            android.content.Intent(app, com.example.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = android.app.PendingIntent.getBroadcast(
            app, 1,
            android.content.Intent(app, com.example.receiver.PlayerActionReceiver::class.java).apply {
                action = com.example.receiver.PlayerActionReceiver.ACTION_PREV
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = android.app.PendingIntent.getBroadcast(
            app, 2,
            android.content.Intent(app, com.example.receiver.PlayerActionReceiver::class.java).apply {
                action = com.example.receiver.PlayerActionReceiver.ACTION_PLAY_PAUSE
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = android.app.PendingIntent.getBroadcast(
            app, 3,
            android.content.Intent(app, com.example.receiver.PlayerActionReceiver::class.java).apply {
                action = com.example.receiver.PlayerActionReceiver.ACTION_NEXT
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val prevAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_previous, "Previous", prevIntent
        ).build()

        val playPauseAction = NotificationCompat.Action.Builder(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pause" else "Play",
            playPauseIntent
        ).build()

        val nextAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_next, "Next", nextIntent
        ).build()

        val progressPercent = if (durationMs > 0) ((progressMs * 1000) / durationMs).toInt().coerceIn(0, 1000) else 0

        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(appIcon)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openAppIntent)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setProgress(1000, progressPercent, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(try { app.getColor(android.R.color.system_accent1_500) } catch (e: Exception) { 0xFF00F0FF.toInt() })
            .setColorized(true)

        mediaSession?.let { session ->
            try {
                builder.setStyle(
                    androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(session)
                        .setShowActionsInCompactView(0, 1, 2)
                )
            } catch (e: Throwable) {
                Log.e("MainViewModel", "MediaStyle error", e)
            }
        }

        val notification = builder.build()
        com.example.service.MediaPlaybackService.activeNotification = notification

        val serviceIntent = Intent(app, com.example.service.MediaPlaybackService::class.java)
        try {
            if (_currentPlayingItem.value != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(serviceIntent)
                } else {
                    app.startService(serviceIntent)
                }
            } else {
                com.example.service.MediaPlaybackService.activeNotification = null
                app.stopService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Service start/stop error", e)
        }

        try {
            notificationManager.notify(201, notification)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Playback Notification error", e)
        }

        updateWidgets()
    }

    fun updateWidgets() {
        val item = _currentPlayingItem.value
        val title = item?.title ?: "Aero Player"
        val artist = item?.displayArtist ?: "Select track to play"
        val isPlaying = if (PlayerControlBridge.isVlcActive && PlayerControlBridge.vlcPlayerRef != null) {
            PlayerControlBridge.vlcPlayerRef!!.isPlaying
        } else {
            exoPlayer.isPlaying
        }
        val pos = if (PlayerControlBridge.isVlcActive && PlayerControlBridge.vlcPlayerRef != null) {
            PlayerControlBridge.vlcPlayerRef!!.currentPositionMs
        } else {
            exoPlayer.currentPosition
        }
        val dur = if (PlayerControlBridge.isVlcActive && PlayerControlBridge.vlcPlayerRef != null) {
            PlayerControlBridge.vlcPlayerRef!!.durationMs
        } else {
            exoPlayer.duration
        }
        com.example.widget.PlayerWidgetProvider.updateAll(
            getApplication(),
            title,
            artist,
            isPlaying,
            art = null,
            progressMs = if (pos > 0) pos else 0L,
            durationMs = if (dur > 0) dur else (item?.duration ?: 0L)
        )
    }

    fun updateVlcPlayingState(playing: Boolean) {
        _isPlaying.value = playing
        updateNotificationState()
        updateWidgets()
    }

    fun cancelPlaybackNotification() {
        com.example.service.MediaPlaybackService.activeNotification = null
        try {
            notificationManager.cancel(201)
        } catch (e: Exception) {}
        try {
            val app = getApplication<Application>()
            app.stopService(Intent(app, com.example.service.MediaPlaybackService::class.java))
        } catch (e: Exception) {}
        updateWidgets()
    }

    fun updateNotificationState() {
        val item = _currentPlayingItem.value ?: run {
            cancelPlaybackNotification()
            return
        }
        val isPlaying = if (PlayerControlBridge.isVlcActive && PlayerControlBridge.vlcPlayerRef != null) {
            PlayerControlBridge.vlcPlayerRef!!.isPlaying
        } else {
            exoPlayer.isPlaying
        }
        val pos = if (PlayerControlBridge.isVlcActive && PlayerControlBridge.vlcPlayerRef != null) {
            PlayerControlBridge.vlcPlayerRef!!.currentPositionMs
        } else {
            exoPlayer.currentPosition
        }
        val dur = if (PlayerControlBridge.isVlcActive && PlayerControlBridge.vlcPlayerRef != null) {
            PlayerControlBridge.vlcPlayerRef!!.durationMs
        } else {
            exoPlayer.duration
        }
        updatePlaybackNotification(
            title = item.title,
            artist = item.displayArtist,
            progressMs = pos,
            durationMs = if (dur > 0) dur else item.duration,
            isPlaying = isPlaying
        )
    }

    fun scanLocalMedia() {
        if (!_isScanning.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            showScanningProgressNotification(0, 0)
            try {
                mediaRepository.scanMedia(getApplication())
                // Get updated count
                val list = mediaRepository.getMediaFlow().first()
                showScanningProgressNotification(list.size, list.size)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Scan error", e)
            } finally {
                _isScanning.value = false
                dismissScanningNotification()
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectTab(tab: String) {
        _selectedTab.value = tab
    }

    fun selectPlaySubTab(subTab: String) {
        _playSubTab.value = subTab
    }

    // Playback History Actions
    fun deleteMedia(item: MediaEntity) {
        deleteMediaBatch(listOf(item))
    }

    fun deleteMediaBatch(items: List<MediaEntity>) {
        viewModelScope.launch {
            val deletedUriSet = items.map { it.uriString }.toSet()
            val deletedPathSet = items.map { it.path }.filter { it.isNotBlank() }.toSet()
            val currentItem = _currentPlayingItem.value
            if (currentItem != null) {
                val isCurrentDeleted = deletedUriSet.contains(currentItem.uriString) ||
                        deletedPathSet.contains(currentItem.path) ||
                        deletedPathSet.any { folder -> folder.isNotBlank() && currentItem.path.startsWith(folder) }
                if (isCurrentDeleted) {
                    clearPlayingItem()
                }
            }
            _playQueue.value = _playQueue.value.filterNot { item ->
                deletedUriSet.contains(item.uriString) ||
                        deletedPathSet.contains(item.path) ||
                        deletedPathSet.any { folder -> folder.isNotBlank() && item.path.startsWith(folder) }
            }

            try {
                val currentPrefs = preferencesState.value
                val array = try {
                    org.json.JSONArray(currentPrefs.deletedUrisJson)
                } catch (e: Exception) {
                    org.json.JSONArray()
                }
                val currentSet = mutableSetOf<String>()
                for (i in 0 until array.length()) {
                    currentSet.add(array.getString(i))
                }
                var modified = false
                items.forEach { item ->
                    if (!currentSet.contains(item.uriString)) {
                        array.put(item.uriString)
                        currentSet.add(item.uriString)
                        modified = true
                    }
                }
                if (modified) {
                    preferenceRepository.updatePreferences(currentPrefs.copy(deletedUrisJson = array.toString()))
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error updating deletedUrisJson preference", e)
            }

            val context = getApplication<Application>()
            val urisThatNeedPrompt = mutableListOf<android.net.Uri>()

            items.forEach { item ->
                mediaRepository.deleteMedia(item.uriString)
                var deletedSilently = false
                if (item.path.isNotBlank()) {
                    try {
                        val file = java.io.File(item.path)
                        if (file.exists()) {
                            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                            Log.d("MainViewModel", "Direct physical file deletion for ${item.path}: $deleted")
                            if (deleted) {
                                deletedSilently = true
                                android.media.MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(item.path),
                                    null
                                ) { path, uri ->
                                    Log.d("MainViewModel", "MediaStore updated for directly deleted file: $path -> $uri")
                                    viewModelScope.launch {
                                        scanLocalMedia()
                                    }
                                }
                            }
                        } else {
                            deletedSilently = true
                        }
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Direct file delete failed: ${item.path}", e)
                    }
                }

                val isExternalStorageMgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.os.Environment.isExternalStorageManager()
                } else false

                if (!deletedSilently && item.uriString.startsWith("content://")) {
                    val uri = android.net.Uri.parse(item.uriString)
                    try {
                        context.contentResolver.delete(uri, null, null)
                        deletedSilently = true
                        Log.d("MainViewModel", "ContentResolver deleted URI silently: $uri")
                    } catch (securityException: SecurityException) {
                        Log.w("MainViewModel", "SecurityException on silent delete for URI: $uri.", securityException)
                        if (!deletedSilently && !isExternalStorageMgr) {
                            urisThatNeedPrompt.add(uri)
                        }
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Error deleting URI: $uri", e)
                        if (!deletedSilently && !isExternalStorageMgr) {
                            urisThatNeedPrompt.add(uri)
                        }
                    }
                }
            }

            if (urisThatNeedPrompt.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val pendingIntent = android.provider.MediaStore.createDeleteRequest(context.contentResolver, urisThatNeedPrompt)
                        _pendingDeleteIntent.value = pendingIntent.intentSender
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "MediaStore.createDeleteRequest failed, falling back", e)
                        deleteUrisOneByOne(urisThatNeedPrompt, context)
                    }
                } else {
                    deleteUrisOneByOne(urisThatNeedPrompt, context)
                }
            } else {
                scanLocalMedia()
            }
        }
    }

    private fun deleteUrisOneByOne(uris: List<android.net.Uri>, context: Context) {
        uris.forEach { uri ->
            try {
                context.contentResolver.delete(uri, null, null)
                Log.d("MainViewModel", "ContentResolver deletion for URI: $uri")
            } catch (securityException: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val rse = securityException as? android.app.RecoverableSecurityException
                    if (rse != null) {
                        _pendingDeleteIntent.value = rse.userAction.actionIntent.intentSender
                    }
                } else {
                    Log.e("MainViewModel", "SecurityException deleting URI: $uri", securityException)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error deleting URI: $uri", e)
            }
        }
    }

    suspend fun getHistoryByUri(uriString: String): HistoryEntity? {
        return historyRepository.getHistoryByUri(uriString)
    }

    fun addPlaybackHistory(item: MediaEntity, progressMs: Long) {
        viewModelScope.launch {
            historyRepository.addHistory(
                uriString = item.uriString,
                title = item.title,
                isVideo = item.isVideo,
                duration = item.duration,
                progressMs = progressMs
            )
        }
    }

    fun deleteHistory(uriString: String) {
        viewModelScope.launch {
            historyRepository.deleteHistory(uriString)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterMediaStoreObserver()
        try {
            mediaSession?.release()
        } catch (e: Exception) {}
        try {
            androidEqualizer?.release()
        } catch (e: Exception) {}
        if (isExoPlayerInitialized) {
            try {
                exoPlayer.release()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error releasing ExoPlayer in onCleared", e)
            }
        }
    }

    // AeroPlaybackManager Interface Overrides
    override fun playFolder(allVideosInFolder: List<VideoFile>, startVideoIndex: Int) {
        playList(allVideosInFolder, startVideoIndex)
    }

    override fun playList(videoList: List<VideoFile>, startIndex: Int) {
        _currentQueue.value = videoList
        
        // Keep legacy queue systems in sync
        val mediaEntities = videoList.map { it.toMediaEntity() }
        _playQueue.value = mediaEntities
        _currentQueueIndex.value = startIndex
        
        if (videoList.isNotEmpty()) {
            val startVideo = videoList[startIndex]
            _currentPlayingVideo.value = startVideo
            
            val startEntity = mediaEntities[startIndex]
            _currentPlayingItem.value = startEntity
            
            // Set playlist in ExoPlayer
            val context = getApplication<Application>().applicationContext
            val mediaItems = mediaEntities.map { entity ->
                com.example.ui.screens.buildMediaItemWithSubtitles(entity.uriString, context, entity.path)
            }
            exoPlayer.setMediaItems(mediaItems, startIndex, 0L)
            exoPlayer.prepare()
            if (PlayerControlBridge.activeEngineName == "ExoPlayer") {
                exoPlayer.play()
            } else {
                exoPlayer.pause()
            }
        }
    }

    override fun next() {
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
        } else {
            playNext()
        }
    }

    override fun previous() {
        if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPreviousMediaItem()
        } else {
            playPrevious()
        }
    }

    // Built-in Volume Control Overlay state
    val volumeOverlayPercent = MutableStateFlow<Int?>(null)
    val volumeOverlayTime = MutableStateFlow(0L)

    fun onVolumeKeyPressed(keyCode: Int): Boolean {
        val context = getApplication<Application>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)

        val newVol = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> (curVol + 1).coerceAtMost(maxVol)
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> (curVol - 1).coerceAtLeast(0)
            android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> 0
            else -> curVol
        }

        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVol, 0)
        val percent = (newVol.toFloat() / maxVol.toFloat() * 100).toInt()
        volumeOverlayPercent.value = percent
        volumeOverlayTime.value = System.currentTimeMillis()
        return true
    }

    fun shareMediaItems(context: Context, items: List<MediaEntity>) {
        if (items.isEmpty()) return
        try {
            val uris = items.mapNotNull { item ->
                if (item.uriString.startsWith("content://")) {
                    android.net.Uri.parse(item.uriString)
                } else if (item.path.isNotBlank()) {
                    val file = java.io.File(item.path)
                    if (file.exists()) {
                        androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } else null
                } else null
            }
            if (uris.isNotEmpty()) {
                val intent = if (uris.size == 1) {
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = items.first().mimeType ?: if (items.first().isVideo) "video/*" else "audio/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Share Media Via"))
            } else {
                android.widget.Toast.makeText(context, "No shareable file URIs found", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Share failed", e)
            android.widget.Toast.makeText(context, "Share error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun renameMediaFile(context: Context, item: MediaEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            try {
                val oldFile = java.io.File(item.path)
                if (oldFile.exists()) {
                    val ext = oldFile.extension
                    val formattedName = if (ext.isNotEmpty() && !newName.endsWith(".$ext", ignoreCase = true)) {
                        "$newName.$ext"
                    } else newName

                    val parent = oldFile.parentFile ?: return@launch
                    val newFile = java.io.File(parent, formattedName)
                    val success = oldFile.renameTo(newFile)
                    if (success) {
                        val updatedItem = item.copy(
                            title = newFile.nameWithoutExtension,
                            path = newFile.absolutePath,
                            uriString = android.net.Uri.fromFile(newFile).toString()
                        )
                        mediaRepository.deleteMedia(item.uriString)
                        mediaRepository.addMediaItem(updatedItem)

                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(newFile.absolutePath, oldFile.absolutePath),
                            null
                        ) { _, _ -> }

                        android.widget.Toast.makeText(context, "Renamed to ${newFile.name}", android.widget.Toast.LENGTH_SHORT).show()
                        scanLocalMedia()
                    } else {
                        android.widget.Toast.makeText(context, "Rename failed. File permission required.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.widget.Toast.makeText(context, "File does not exist on storage path", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Rename exception", e)
                android.widget.Toast.makeText(context, "Rename error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun setAsRingtone(context: Context, item: MediaEntity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.System.canWrite(context)) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    android.widget.Toast.makeText(context, "Grant Write System Settings permission to set ringtone", android.widget.Toast.LENGTH_LONG).show()
                    return
                }
            }
            val uri = android.net.Uri.parse(item.uriString)
            android.media.RingtoneManager.setActualDefaultRingtoneUri(
                context,
                android.media.RingtoneManager.TYPE_RINGTONE,
                uri
            )
            android.widget.Toast.makeText(context, "Set '${item.title}' as default Ringtone!", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Ringtone set failed", e)
            android.widget.Toast.makeText(context, "Ringtone error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

object PlayerControlBridge {
    var instance: MainViewModel? = null
    var vlcPlayerRef: com.example.player.VlcPlayerWrapper? = null
    var activeEngineName: String = "VLC"

    val isVlcActive: Boolean
        get() = activeEngineName == "VLC"

    fun onPlayerStateChanged(isPlaying: Boolean) {
        instance?.updateVlcPlayingState(isPlaying)
    }

    fun playPause() {
        instance?.let { vm ->
            if (isVlcActive && vlcPlayerRef != null) {
                // Ensure ExoPlayer is paused so no double sound occurs
                try {
                    if (vm.exoPlayer.isPlaying) vm.exoPlayer.pause()
                } catch (e: Exception) {}

                val vlc = vlcPlayerRef!!
                if (vlc.isPlaying) {
                    vlc.pause()
                } else {
                    vlc.play()
                }
            } else {
                // Ensure VLC is paused so no double sound occurs
                try {
                    if (vlcPlayerRef?.isPlaying == true) vlcPlayerRef?.pause()
                } catch (e: Exception) {}

                if (vm.exoPlayer.isPlaying) {
                    vm.exoPlayer.pause()
                } else {
                    vm.exoPlayer.play()
                }
            }
            vm.updateNotificationState()
            vm.updateWidgets()
        }
    }

    fun prev() {
        instance?.let { vm ->
            vm.playPrevious()
            vm.updateNotificationState()
            vm.updateWidgets()
        }
    }

    fun next() {
        instance?.let { vm ->
            vm.playNext()
            vm.updateNotificationState()
            vm.updateWidgets()
        }
    }
}
