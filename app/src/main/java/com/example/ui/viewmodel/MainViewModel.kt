package com.example.ui.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

    val exoPlayer: ExoPlayer by lazy {
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(application).apply {
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50000, // minBufferMs
                120000, // maxBufferMs (4k buffer boost)
                1500, // bufferForPlaybackMs
                2500  // bufferForPlaybackAfterRebufferMs
            )
            .build()
        ExoPlayer.Builder(application)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateNotificationState()
                    _isPlaying.value = isPlaying
                }
                override fun onPlaybackStateChanged(state: Int) {
                    updateNotificationState()
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateNotificationState()
                    val currentUri = mediaItem?.localConfiguration?.uri?.toString()
                    if (currentUri != null) {
                        val currentVideo = _currentQueue.value.find { it.id == currentUri }
                        _currentPlayingVideo.value = currentVideo
                        
                        val matchingMediaEntity = filteredMediaList.value.find { it.uriString == currentUri }
                        if (matchingMediaEntity != null) {
                            _currentPlayingItem.value = matchingMediaEntity
                        }
                    }
                }
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    initEqualizer(audioSessionId)
                }
            })
            try {
                mediaSession = androidx.media3.session.MediaSession.Builder(application, this).build()
            } catch (e: Throwable) {
                Log.e("MainViewModel", "Error building MediaSession", e)
            }
        }
    }

    // Preferences State
    val preferencesState: StateFlow<PreferenceEntity> = preferenceRepository.getPreferencesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PreferenceEntity()
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

            // Exclude banned folders
            val isBanned = bannedFolders.any { bannedPath ->
                item.path.startsWith(bannedPath)
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

    init {
        createNotificationChannel()
        // Playback progress notification ticker
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                try {
                    if (exoPlayer.isPlaying) {
                        updateNotificationState()
                    }
                } catch (e: Exception) {
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
        val builder = NotificationCompat.Builder(getApplication(), CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Scanning Local Media")
            .setContentText(if (total > 0) "Scanned $progress / $total files" else "Found $progress files...")
            .setProgress(total.coerceAtLeast(100), progress, total <= 0)
            .setOngoing(true)
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
            // Show a "Scan Completed" notification briefly
            val builder = NotificationCompat.Builder(getApplication(), CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Scan Completed")
                .setContentText("Your media library is updated.")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
            notificationManager.notify(102, builder.build())
        } catch (e: Exception) {
            Log.e("MainViewModel", "Notification error", e)
        }
    }

    fun updatePlaybackNotification(title: String, artist: String, progressMs: Long, durationMs: Long, isPlaying: Boolean) {
        val progressPercent = if (durationMs > 0) ((progressMs * 100) / durationMs).toInt() else 0
        
        val builder = NotificationCompat.Builder(getApplication(), CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(title)
            .setContentText(artist)
            .setProgress(100, progressPercent, durationMs <= 0)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            
        try {
            notificationManager.notify(201, builder.build())
        } catch (e: Exception) {
            Log.e("MainViewModel", "Playback Notification error", e)
        }
    }

    fun cancelPlaybackNotification() {
        try {
            notificationManager.cancel(201)
        } catch (e: Exception) {}
    }

    fun updateNotificationState() {
        val item = _currentPlayingItem.value ?: return
        val pos = exoPlayer.currentPosition
        val dur = exoPlayer.duration
        updatePlaybackNotification(
            title = item.title,
            artist = item.displayArtist,
            progressMs = pos,
            durationMs = if (dur > 0) dur else item.duration,
            isPlaying = exoPlayer.isPlaying
        )
    }

    fun scanLocalMedia() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
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

            items.forEach { item ->
                mediaRepository.deleteMedia(item.uriString)
                try {
                    val context = getApplication<Application>()
                    // 1. First always try direct file deletion if path exists
                    if (item.path.isNotBlank()) {
                        val file = java.io.File(item.path)
                        if (file.exists()) {
                            val deleted = file.delete()
                            Log.d("MainViewModel", "Physical file deletion for ${item.path}: $deleted")
                            
                            // Notify Android Media Store to update and remove file from index
                            android.media.MediaScannerConnection.scanFile(
                                context,
                                arrayOf(item.path),
                                null
                            ) { path, uri ->
                                Log.d("MainViewModel", "MediaStore updated for deleted file: $path -> $uri")
                            }
                        }
                    }
                    // 2. Also try content resolver delete for content providers
                    if (item.uriString.startsWith("content://")) {
                        val contentResolver = context.contentResolver
                        val uri = android.net.Uri.parse(item.uriString)
                        contentResolver.delete(uri, null, null)
                        Log.d("MainViewModel", "ContentResolver deletion for URI: ${item.uriString}")
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error physically deleting file: ${item.path}", e)
                }
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
        try {
            mediaSession?.release()
        } catch (e: Exception) {}
        try {
            androidEqualizer?.release()
        } catch (e: Exception) {}
        try {
            exoPlayer.release()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error releasing ExoPlayer in onCleared", e)
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
                com.example.ui.screens.buildMediaItemWithSubtitles(entity.uriString, context)
            }
            exoPlayer.setMediaItems(mediaItems, startIndex, 0L)
            exoPlayer.prepare()
            exoPlayer.play()
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
}
