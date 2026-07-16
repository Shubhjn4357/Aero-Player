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

    private val db = AppDatabase.getDatabase(application)
    private val mediaRepository = MediaRepository(db.mediaDao())
    private val historyRepository = HistoryRepository(db.historyDao())
    private val preferenceRepository = PreferenceRepository(db.preferenceDao())

    // 1. AeroPlaybackManager State Flow implementations
    private val _currentQueue = MutableStateFlow<List<VideoFile>>(emptyList())
    override val currentQueue: StateFlow<List<VideoFile>> = _currentQueue.asStateFlow()

    private val _currentPlayingVideo = MutableStateFlow<VideoFile?>(null)
    override val currentPlayingVideo: StateFlow<VideoFile?> = _currentPlayingVideo.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // 2. Browse Screen and Selection States
    private val _browseScreenState = MutableStateFlow<BrowseScreenState>(BrowseScreenState.FolderList)
    val browseScreenState: StateFlow<BrowseScreenState> = _browseScreenState.asStateFlow()

    private val _selectionState = MutableStateFlow(SelectionState())
    val selectionState: StateFlow<SelectionState> = _selectionState.asStateFlow()

    // Picture-in-Picture & System Notification State
    val isInPipMode = MutableStateFlow(false)
    private val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "aero_player_channel"
    private var mediaSession: androidx.media3.session.MediaSession? = null

    // Real Equalizer States
    private var androidEqualizer: android.media.audiofx.Equalizer? = null
    
    private val _equalizerEnabled = MutableStateFlow(true)
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()
    
    private val _equalizerBands = MutableStateFlow<List<EqualizerBand>>(emptyList())
    val equalizerBands: StateFlow<List<EqualizerBand>> = _equalizerBands.asStateFlow()
    
    private val _currentEqualizerPreset = MutableStateFlow("Normal")
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
            } catch (e: Exception) {
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
    private val _rawMediaList = mediaRepository.getMediaFlow()

    // UI control states (Search, filter, sort)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTab = MutableStateFlow("Play") // "Play", "More"
    val selectedTab: StateFlow<String> = _selectedTab.asStateFlow()

    private val _playSubTab = MutableStateFlow("Video") // "Video", "Audio"
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

        val filtered = media.filter { item ->
            // Exclude banned folders
            val isBanned = bannedFolders.any { bannedPath ->
                item.path.startsWith(bannedPath)
            }
            if (isBanned) return@filter false

            // Filter by Tab (Video / Audio) in the Play tab
            val matchesTab = if (subTab == "Video") item.isVideo else !item.isVideo
            
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
    private val _currentPlayingItem = MutableStateFlow<MediaEntity?>(null)
    val currentPlayingItem: StateFlow<MediaEntity?> = _currentPlayingItem.asStateFlow()

    // Active playback queue
    private val _playQueue = MutableStateFlow<List<MediaEntity>>(emptyList())
    val playQueue: StateFlow<List<MediaEntity>> = _playQueue.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    // Media Scanning State
    private val _isScanning = MutableStateFlow(false)
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

    fun setPlayingItem(item: MediaEntity?) {
        _currentPlayingItem.value = item
        if (item != null) {
            // If item played is not already in queue, reset queue to just this item
            val queue = _playQueue.value
            val idx = queue.indexOfFirst { it.uriString == item.uriString }
            if (idx >= 0) {
                _currentQueueIndex.value = idx
            } else {
                _playQueue.value = listOf(item)
                _currentQueueIndex.value = 0
            }
        }
    }

    fun clearPlayingItem() {
        try {
            exoPlayer.stop()
        } catch (e: Exception) {}
        _currentPlayingItem.value = null
    }

    fun setPlayingItemWithQueue(item: MediaEntity, queue: List<MediaEntity>) {
        _playQueue.value = queue
        val idx = queue.indexOfFirst { it.uriString == item.uriString }
        _currentQueueIndex.value = if (idx >= 0) idx else 0
        _currentPlayingItem.value = item
    }

    fun downloadFileFromWeb(mediaItem: MediaEntity) {
        val context = getApplication<android.app.Application>()
        try {
            val uri = android.net.Uri.parse(mediaItem.uriString)
            if (!mediaItem.uriString.startsWith("http")) {
                android.widget.Toast.makeText(context, "Cannot download local file", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            
            val request = android.app.DownloadManager.Request(uri).apply {
                setTitle("Downloading ${mediaItem.title}")
                setDescription("Aero Player Web Downloader")
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                
                val fileName = mediaItem.uriString.substringAfterLast('/')
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "AeroPlayer/$fileName")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            downloadManager.enqueue(request)
            android.widget.Toast.makeText(context, "Download started for: ${mediaItem.title}", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(context, "Download failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun playAll(items: List<MediaEntity>) {
        _playQueue.value = items
        _currentQueueIndex.value = 0
        if (items.isNotEmpty()) {
            _currentPlayingItem.value = items[0]
        }
    }

    fun addToQueue(items: List<MediaEntity>) {
        val currentList = _playQueue.value.toMutableList()
        currentList.addAll(items)
        _playQueue.value = currentList
    }

    fun insertNext(item: MediaEntity) {
        val currentList = _playQueue.value.toMutableList()
        val index = _currentQueueIndex.value
        if (currentList.isEmpty()) {
            currentList.add(item)
            _playQueue.value = currentList
            _currentQueueIndex.value = 0
            setPlayingItem(item)
        } else {
            currentList.add(index + 1, item)
            _playQueue.value = currentList
        }
    }

    fun playNext() {
        if (_playQueue.value.isEmpty()) return
        val nextIndex = (_currentQueueIndex.value + 1) % _playQueue.value.size
        _currentQueueIndex.value = nextIndex
        _currentPlayingItem.value = _playQueue.value[nextIndex]
    }

    fun playPrevious() {
        if (_playQueue.value.isEmpty()) return
        val prevIndex = if (_currentQueueIndex.value - 1 >= 0) _currentQueueIndex.value - 1 else _playQueue.value.size - 1
        _currentQueueIndex.value = prevIndex
        _currentPlayingItem.value = _playQueue.value[prevIndex]
    }

    fun clearQueue() {
        val currentItem = _currentPlayingItem.value
        if (currentItem != null) {
            _playQueue.value = listOf(currentItem)
            _currentQueueIndex.value = 0
        } else {
            _playQueue.value = emptyList()
            _currentQueueIndex.value = 0
        }
    }

    fun removeFromQueue(index: Int) {
        val currentList = _playQueue.value.toMutableList()
        if (index >= 0 && index < currentList.size) {
            currentList.removeAt(index)
            _playQueue.value = currentList
            val currentIndex = _currentQueueIndex.value
            if (currentIndex == index) {
                if (currentList.isNotEmpty()) {
                    val newIndex = index.coerceAtMost(currentList.size - 1)
                    _currentQueueIndex.value = newIndex
                    _currentPlayingItem.value = currentList[newIndex]
                } else {
                    _currentQueueIndex.value = 0
                    _currentPlayingItem.value = null
                }
            } else if (currentIndex > index) {
                _currentQueueIndex.value = currentIndex - 1
            }
        }
    }

    fun addNetworkStream(title: String, url: String, isVideo: Boolean) {
        viewModelScope.launch {
            val streamItem = MediaEntity(
                uriString = url,
                title = title,
                artist = "Custom Stream",
                album = "Network",
                duration = 0,
                size = 0,
                dateAdded = System.currentTimeMillis() / 1000,
                isVideo = isVideo,
                path = url,
                mimeType = when {
                    url.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                    url.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                    url.contains(".m3u", ignoreCase = true) -> "audio/mpegurl"
                    else -> if (isVideo) "video/mp4" else "audio/mp3"
                },
                genre = "Live Stream"
            )
            mediaRepository.addMediaItem(streamItem)
        }
    }

    // Playback History Actions
    fun deleteMedia(item: MediaEntity) {
        viewModelScope.launch {
            mediaRepository.deleteMedia(item.uriString)
            try {
                // 1. First always try direct file deletion if path exists
                if (item.path.isNotBlank()) {
                    val file = java.io.File(item.path)
                    if (file.exists()) {
                        val deleted = file.delete()
                        Log.d("MainViewModel", "Physical file deletion for ${item.path}: $deleted")
                    }
                }
                // 2. Also try content resolver delete for content providers
                if (item.uriString.startsWith("content://")) {
                    val context = getApplication<Application>()
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

    // Preference Updates
    fun updateTheme(themeName: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(themeMode = themeName))
        }
    }

    fun updateDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(useDynamicColor = enabled))
        }
    }

    fun updateGroupWiseFolderStyle(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(useGroupWiseFolderStyle = enabled))
        }
    }

    fun updateListStyle(style: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(listStyle = style))
        }
    }

    fun updateUseGroupWiseFolderStyle(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(useGroupWiseFolderStyle = enabled))
        }
    }

    fun updateSorting(sortBy: String, ascending: Boolean) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(sortBy = sortBy, sortAscending = ascending))
        }
    }

    fun updateGroupByStyle(style: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(groupByStyle = style))
        }
    }

    fun updatePlaybackSettings(speed: Float, resizeMode: Int) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(playbackSpeed = speed, resizeMode = resizeMode))
        }
    }

    fun updateSubtitleSettings(size: Float, color: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(subtitleSize = size, subtitleColor = color))
        }
    }

    fun updateSubtitleLanguage(language: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(defaultSubtitleLanguage = language))
        }
    }

    fun updateSubtitleCustomization(background: String, textColor: String, size: Float, fontStyle: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(
                subtitleBackground = background,
                subtitleTextColor = textColor,
                subtitleSize = size,
                subtitleFontStyle = fontStyle
            ))
        }
    }

    fun toggleAutoScan(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(autoScanEnabled = enabled))
        }
    }

    fun toggleUsePerVideoSettings(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(usePerVideoSettings = enabled))
        }
    }

    fun updateSaveVolumeBrightnessBehavior(behavior: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(saveVolumeBrightnessBehavior = behavior))
        }
    }

    fun updateGlobalVolume(volume: Float) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(globalVolume = volume))
        }
    }

    fun updateGlobalBrightness(brightness: Float) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(globalBrightness = brightness))
        }
    }

    fun updatePerVideoVolumeBrightness(uriString: String, volume: Float, brightness: Float) {
        viewModelScope.launch {
            val current = preferencesState.value
            try {
                val json = if (current.perVideoSettingsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(current.perVideoSettingsJson)
                val videoObj = json.optJSONObject(uriString) ?: org.json.JSONObject()
                videoObj.put("volume", volume.toDouble())
                if (brightness >= 0f) {
                    videoObj.put("brightness", brightness.toDouble())
                }
                json.put(uriString, videoObj)
                preferenceRepository.updatePreferences(current.copy(perVideoSettingsJson = json.toString()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updatePerVideoSettings(uriString: String, speed: Float, resizeMode: Int, volume: Float, eqPreset: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            try {
                val json = if (current.perVideoSettingsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(current.perVideoSettingsJson)
                val videoObj = org.json.JSONObject().apply {
                    put("speed", speed.toDouble())
                    put("resizeMode", resizeMode)
                    put("volume", volume.toDouble())
                    put("eqPreset", eqPreset)
                }
                json.put(uriString, videoObj)
                preferenceRepository.updatePreferences(current.copy(perVideoSettingsJson = json.toString()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateDefaultOrientation(orientation: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(defaultOrientation = orientation))
        }
    }

    fun updateDoubleTapSeekSeconds(seconds: Int) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(doubleTapSeekSeconds = seconds))
        }
    }

    fun toggleRotationLock(locked: Boolean) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(rotationLock = locked))
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(onboardingCompleted = true))
        }
    }

    fun toggleBannedFolder(folderPath: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            val array = try {
                org.json.JSONArray(current.bannedFoldersJson)
            } catch (e: Exception) {
                org.json.JSONArray()
            }
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            if (list.contains(folderPath)) {
                list.remove(folderPath)
            } else {
                list.add(folderPath)
            }
            val newArray = org.json.JSONArray(list)
            preferenceRepository.updatePreferences(current.copy(bannedFoldersJson = newArray.toString()))
        }
    }

    fun toggleFavoriteFolder(folderPath: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            val array = try {
                org.json.JSONArray(current.favoriteFoldersJson)
            } catch (e: Exception) {
                org.json.JSONArray()
            }
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            if (list.contains(folderPath)) {
                list.remove(folderPath)
            } else {
                list.add(folderPath)
            }
            val newArray = org.json.JSONArray(list)
            preferenceRepository.updatePreferences(current.copy(favoriteFoldersJson = newArray.toString()))
        }
    }

    fun updateMeteredNetworkAction(action: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(meteredNetworkAction = action))
        }
    }

    fun togglePlayHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(playHistoryEnabled = enabled))
        }
    }

    fun toggleSaveVideoQueueHistory(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(saveVideoQueueHistory = enabled))
        }
    }

    fun toggleSaveAudioQueueHistory(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(saveAudioQueueHistory = enabled))
        }
    }

    fun updateResumePlaybackBehavior(behavior: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(resumePlaybackBehavior = behavior))
        }
    }

    fun updateHwAcceleration(mode: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(hwAcceleration = mode))
        }
    }

    fun updateBackgroundMode(mode: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(backgroundMode = mode))
        }
    }

    fun addMediaToPlaylist(playlistName: String, mediaUri: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            val jsonObj = try {
                org.json.JSONObject(current.playlistsJson)
            } catch (e: Exception) {
                org.json.JSONObject()
            }
            val array = if (jsonObj.has(playlistName)) {
                jsonObj.getJSONArray(playlistName)
            } else {
                org.json.JSONArray()
            }
            var exists = false
            for (i in 0 until array.length()) {
                if (array.getString(i) == mediaUri) {
                    exists = true
                    break
                }
            }
            if (!exists) {
                array.put(mediaUri)
            }
            jsonObj.put(playlistName, array)
            preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
        }
    }

    fun addMultipleMediaToPlaylist(playlistName: String, mediaUris: List<String>) {
        viewModelScope.launch {
            val current = preferencesState.value
            val jsonObj = try {
                org.json.JSONObject(current.playlistsJson)
            } catch (e: Exception) {
                org.json.JSONObject()
            }
            val array = if (jsonObj.has(playlistName)) {
                jsonObj.getJSONArray(playlistName)
            } else {
                org.json.JSONArray()
            }
            val existingSet = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                existingSet.add(array.getString(i))
            }
            for (uri in mediaUris) {
                if (uri.isNotBlank() && !existingSet.contains(uri)) {
                    array.put(uri)
                    existingSet.add(uri)
                }
            }
            jsonObj.put(playlistName, array)
            preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
        }
    }

    fun updatePlaylistTracks(playlistName: String, mediaUris: List<String>) {
        viewModelScope.launch {
            val current = preferencesState.value
            val jsonObj = try {
                org.json.JSONObject(current.playlistsJson)
            } catch (e: Exception) {
                org.json.JSONObject()
            }
            val array = org.json.JSONArray()
            for (uri in mediaUris) {
                if (uri.isNotBlank()) {
                    array.put(uri)
                }
            }
            jsonObj.put(playlistName, array)
            preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
        }
    }

    fun deletePlaylist(playlistName: String) {
        viewModelScope.launch {
            val current = preferencesState.value
            val jsonObj = try {
                org.json.JSONObject(current.playlistsJson)
            } catch (e: Exception) {
                org.json.JSONObject()
            }
            if (jsonObj.has(playlistName)) {
                jsonObj.remove(playlistName)
            }
            preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
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

    fun initEqualizer(audioSessionId: Int) {
        if (audioSessionId == 0) return
        try {
            androidEqualizer?.release()
            val eq = android.media.audiofx.Equalizer(0, audioSessionId).apply {
                enabled = _equalizerEnabled.value
            }
            androidEqualizer = eq
            
            val bands = mutableListOf<EqualizerBand>()
            val numBands = eq.numberOfBands
            val range = eq.bandLevelRange
            val minLevel = range[0]
            val maxLevel = range[1]
            
            for (i in 0 until numBands.toInt()) {
                val centerFreq = eq.getCenterFreq(i.toShort()) / 1000 // mHz to Hz
                val currentLevel = eq.getBandLevel(i.toShort())
                bands.add(EqualizerBand(i, centerFreq, minLevel, maxLevel, currentLevel))
            }
            _equalizerBands.value = bands
            applySavedEqualizerSettings()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to initialize Equalizer with session $audioSessionId", e)
        }
    }

    private fun applySavedEqualizerSettings() {
        val preset = _currentEqualizerPreset.value
        if (preset != "Custom") {
            applyPreset(preset)
        } else {
            val eq = androidEqualizer ?: return
            _equalizerBands.value.forEach { band ->
                try {
                    eq.setBandLevel(band.index.toShort(), band.currentLevelMilliBel)
                } catch (e: Exception) {}
            }
        }
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        _equalizerEnabled.value = enabled
        try {
            androidEqualizer?.enabled = enabled
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to set equalizer enabled state", e)
        }
    }

    fun setEqualizerBandLevel(bandIndex: Int, levelMilliBel: Short) {
        try {
            androidEqualizer?.setBandLevel(bandIndex.toShort(), levelMilliBel)
            _equalizerBands.value = _equalizerBands.value.map { band ->
                if (band.index == bandIndex) band.copy(currentLevelMilliBel = levelMilliBel) else band
            }
            _currentEqualizerPreset.value = "Custom"
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to set band level", e)
        }
    }

    fun applyPreset(presetName: String) {
        _currentEqualizerPreset.value = presetName
        val eq = androidEqualizer ?: return
        try {
            val bands = _equalizerBands.value
            if (bands.isEmpty()) return
            
            val numBands = eq.numberOfBands.toInt()
            val newBands = bands.map { band ->
                val factor = when (presetName) {
                    "Bass Booster" -> {
                        when (band.index) {
                            0 -> 0.7f
                            1 -> 0.5f
                            else -> 0f
                        }
                    }
                    "Vocal Enhancer" -> {
                        when (band.index) {
                            2 -> 0.6f
                            3 -> 0.4f
                            else -> -0.1f
                        }
                    }
                    "Jazz Stage" -> {
                        when (band.index) {
                            0 -> 0.4f
                            1 -> 0.1f
                            2 -> -0.2f
                            3 -> 0.2f
                            4 -> 0.5f
                            else -> 0f
                        }
                    }
                    "Classic Room" -> {
                        when (band.index) {
                            1 -> 0.5f
                            2 -> 0.3f
                            3 -> -0.2f
                            4 -> -0.5f
                            else -> 0f
                        }
                    }
                    "Studio Flat" -> {
                        0f
                    }
                    else -> 0f // "Normal"
                }
                
                val maxL = band.maxLevelMilliBel.toFloat()
                val minL = band.minLevelMilliBel.toFloat()
                val targetLevel = if (factor >= 0) {
                    (factor * maxL).toInt().toShort()
                } else {
                    (-factor * minL).toInt().toShort()
                }
                
                eq.setBandLevel(band.index.toShort(), targetLevel)
                band.copy(currentLevelMilliBel = targetLevel)
            }
            _equalizerBands.value = newBands
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to apply preset", e)
        }
    }

    // Converter: MediaEntity -> VideoFile
    fun MediaEntity.toVideoFile(): VideoFile {
        return VideoFile(
            id = this.uriString,
            title = this.title,
            absolutePath = this.path,
            duration = this.duration,
            resolution = this.genre ?: "1080p",
            size = this.size,
            parentFolderName = java.io.File(this.path).parentFile?.name ?: "Root Folder"
        )
    }

    // Converter: VideoFile -> MediaEntity
    fun VideoFile.toMediaEntity(): MediaEntity {
        return MediaEntity(
            uriString = this.id,
            title = this.title,
            artist = "Local Media",
            album = "Local Album",
            duration = this.duration,
            size = this.size,
            dateAdded = System.currentTimeMillis(),
            isVideo = true,
            path = this.absolutePath,
            mimeType = "video/mp4"
        )
    }

    // AeroPlaybackManager Implementation
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

    // Browse Screen State Machine functions
    fun onFolderClick(folderName: String) {
        val sel = _selectionState.value
        if (sel.isInSelectionMode) {
            toggleFolderSelection(folderName)
        } else {
            _browseScreenState.value = BrowseScreenState.FileList(folderName)
        }
    }

    fun onBackPress() {
        if (_selectionState.value.isInSelectionMode) {
            clearSelection()
        } else if (_browseScreenState.value is BrowseScreenState.FileList) {
            _browseScreenState.value = BrowseScreenState.FolderList
        }
    }

    fun toggleFolderSelection(folderPath: String) {
        val current = _selectionState.value
        val updated = if (current.selectedFolderPaths.contains(folderPath)) {
            current.selectedFolderPaths - folderPath
        } else {
            current.selectedFolderPaths + folderPath
        }
        val inSelection = updated.isNotEmpty() || current.selectedVideoIds.isNotEmpty()
        _selectionState.value = current.copy(
            isInSelectionMode = inSelection,
            selectedFolderPaths = updated
        )
    }

    fun toggleVideoSelection(videoId: String) {
        val current = _selectionState.value
        val updated = if (current.selectedVideoIds.contains(videoId)) {
            current.selectedVideoIds - videoId
        } else {
            current.selectedVideoIds + videoId
        }
        val inSelection = current.selectedFolderPaths.isNotEmpty() || updated.isNotEmpty()
        _selectionState.value = current.copy(
            isInSelectionMode = inSelection,
            selectedVideoIds = updated
        )
    }

    fun selectAllVideos(videoIds: List<String>) {
        val current = _selectionState.value
        _selectionState.value = current.copy(
            isInSelectionMode = true,
            selectedVideoIds = videoIds.toSet()
        )
    }

    fun clearSelection() {
        _selectionState.value = SelectionState(
            isInSelectionMode = false,
            selectedFolderPaths = emptySet(),
            selectedVideoIds = emptySet()
        )
    }

    fun deleteSelectedItems() {
        val sel = _selectionState.value
        viewModelScope.launch {
            val list = filteredMediaList.value
            val videosToDelete = list.filter { sel.selectedVideoIds.contains(it.uriString) }
            videosToDelete.forEach { item ->
                deleteMedia(item)
            }
            
            if (sel.selectedFolderPaths.isNotEmpty()) {
                val foldersToDelete = list.filter { item ->
                    val parentName = java.io.File(item.path).parentFile?.name ?: "Root Folder"
                    sel.selectedFolderPaths.contains(parentName)
                }
                foldersToDelete.forEach { item ->
                    deleteMedia(item)
                }
            }
            
            clearSelection()
        }
    }
}

data class EqualizerBand(
    val index: Int,
    val centerFrequencyHz: Int,
    val minLevelMilliBel: Short,
    val maxLevelMilliBel: Short,
    val currentLevelMilliBel: Short
)
