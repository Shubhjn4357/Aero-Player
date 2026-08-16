package com.example.ui.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.database.AppDatabase
import com.example.data.database.HistoryEntity
import com.example.data.database.MediaEntity
import com.example.data.database.PreferenceEntity
import com.example.data.repository.HistoryRepository
import com.example.data.repository.MediaRepository
import com.example.data.repository.PreferenceRepository
import com.example.util.downloadFileFromWeb as utilDownloadFileFromWeb
import com.example.util.setAsRingtone as utilSetAsRingtone
import com.example.util.shareMediaItems as utilShareMediaItems
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class EqualizerBand(
    val index: Short,
    val centerFrequencyHz: Int,
    val minLevelMilliBel: Short = -1500,
    val maxLevelMilliBel: Short = 1500,
    val currentLevelMilliBel: Short = 0
) {
    val centerFreq: Int get() = centerFrequencyHz
    val minLevel: Short get() = minLevelMilliBel
    val maxLevel: Short get() = maxLevelMilliBel
    val currentLevel: Short get() = currentLevelMilliBel
}

data class SelectionState(
    val isInSelectionMode: Boolean = false,
    val selectedFolderPaths: Set<String> = emptySet(),
    val selectedVideoIds: Set<String> = emptySet()
)

data class BrowseScreenState(
    val currentBrowsePath: String = "",
    val folderGroupingStyle: String = "flat"
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val database = AppDatabase.getDatabase(application)
    val mediaRepository = MediaRepository(database.mediaDao())
    val historyRepository = HistoryRepository(database.historyDao())
    val preferenceRepository = PreferenceRepository(database.preferenceDao())

    val preferencesState: StateFlow<PreferenceEntity> = preferenceRepository.getPreferencesFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, PreferenceEntity())

    val mediaList: StateFlow<List<MediaEntity>> = mediaRepository.getMediaFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val historyList: StateFlow<List<HistoryEntity>> = historyRepository.getHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val historyState: StateFlow<List<HistoryEntity>> get() = historyList

    private val _selectedTab = MutableStateFlow("Play")
    val selectedTab: StateFlow<String> = _selectedTab.asStateFlow()

    private val _playSubTab = MutableStateFlow("Video")
    val playSubTab: StateFlow<String> = _playSubTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredMediaList: StateFlow<List<MediaEntity>> = combine(
        mediaRepository.getMediaFlow(),
        _searchQuery
    ) { list, query ->
        if (query.isBlank()) {
            list
        } else {
            list.filter {
                it.title.contains(query, ignoreCase = true) ||
                (it.artist?.contains(query, ignoreCase = true) == true) ||
                (it.album?.contains(query, ignoreCase = true) == true) ||
                (it.genre?.contains(query, ignoreCase = true) == true) ||
                it.path.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectionState = MutableStateFlow(SelectionState())
    val selectionState: StateFlow<SelectionState> = _selectionState.asStateFlow()

    private val _browseScreenState = MutableStateFlow(BrowseScreenState())
    val browseScreenState: StateFlow<BrowseScreenState> = _browseScreenState.asStateFlow()

    private val _currentPlayingItem = MutableStateFlow<MediaEntity?>(null)
    val currentPlayingItem: StateFlow<MediaEntity?> = _currentPlayingItem.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playQueue = MutableStateFlow<List<MediaEntity>>(emptyList())
    val playQueue: StateFlow<List<MediaEntity>> = _playQueue.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    private val _isMediaScanning = MutableStateFlow(false)
    val isMediaScanning: StateFlow<Boolean> = _isMediaScanning.asStateFlow()
    val isScanning: StateFlow<Boolean> get() = _isMediaScanning

    val isInPipMode = MutableStateFlow(false)

    val volumeOverlayPercent = MutableStateFlow<Float?>(null)
    val volumeOverlayTime = MutableStateFlow<Long>(0L)
    var audioOnlyPlaybackRequested: Boolean = false

    val exoPlayer: ExoPlayer by lazy {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()
        ExoPlayer.Builder(application)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private val _equalizerEnabled = MutableStateFlow(false)
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()

    private val _currentEqualizerPreset = MutableStateFlow("Flat")
    val currentEqualizerPreset: StateFlow<String> = _currentEqualizerPreset.asStateFlow()

    private val _equalizerBands = MutableStateFlow<List<EqualizerBand>>(
        listOf(
            EqualizerBand(0, 60, -1500, 1500, 0),
            EqualizerBand(1, 230, -1500, 1500, 0),
            EqualizerBand(2, 910, -1500, 1500, 0),
            EqualizerBand(3, 3600, -1500, 1500, 0),
            EqualizerBand(4, 14000, -1500, 1500, 0)
        )
    )
    val equalizerBands: StateFlow<List<EqualizerBand>> = _equalizerBands.asStateFlow()

    private val _pendingDeleteIntent = MutableStateFlow<PendingIntent?>(null)
    val pendingDeleteIntent: StateFlow<PendingIntent?> = _pendingDeleteIntent.asStateFlow()

    val selectedCastDevice = MutableStateFlow("Living Room TV (Chromecast)")
    val isCasting = MutableStateFlow(false)

    init {
        PlayerControlBridge.viewModelRef = WeakReference(this)
        PlayerControlBridge.exoPlayerRef = WeakReference(exoPlayer)
        PlayerControlBridge.isPlayingListener = { playing ->
            _isPlaying.value = playing
        }
        PlayerControlBridge.onPlayPauseListener = {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
                _isPlaying.value = false
            } else {
                exoPlayer.play()
                _isPlaying.value = true
            }
        }
        PlayerControlBridge.onPlayListener = {
            exoPlayer.play()
            _isPlaying.value = true
        }
        PlayerControlBridge.onPauseListener = {
            exoPlayer.pause()
            _isPlaying.value = false
        }
        PlayerControlBridge.onNextListener = {
            playNext()
        }
        PlayerControlBridge.onPrevListener = {
            playPrevious()
        }

        viewModelScope.launch {
            scanLocalMedia()
        }
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun selectTab(tab: String) {
        _selectedTab.value = tab
    }

    fun selectPlaySubTab(subTab: String) {
        _playSubTab.value = subTab
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSelection() {
        _selectionState.value = SelectionState()
    }

    fun toggleFolderSelection(path: String) {
        val current = _selectionState.value
        val paths = current.selectedFolderPaths.toMutableSet()
        if (paths.contains(path)) {
            paths.remove(path)
        } else {
            paths.add(path)
        }
        val inMode = paths.isNotEmpty() || current.selectedVideoIds.isNotEmpty()
        _selectionState.value = current.copy(
            isInSelectionMode = inMode,
            selectedFolderPaths = paths
        )
    }

    fun toggleVideoSelection(id: String) {
        val current = _selectionState.value
        val ids = current.selectedVideoIds.toMutableSet()
        if (ids.contains(id)) {
            ids.remove(id)
        } else {
            ids.add(id)
        }
        val inMode = ids.isNotEmpty() || current.selectedFolderPaths.isNotEmpty()
        _selectionState.value = current.copy(
            isInSelectionMode = inMode,
            selectedVideoIds = ids
        )
    }

    fun selectAllFoldersAndFiles(folderPaths: List<String>, videoIds: List<String>) {
        _selectionState.value = SelectionState(
            isInSelectionMode = true,
            selectedFolderPaths = folderPaths.toSet(),
            selectedVideoIds = videoIds.toSet()
        )
    }

    fun getDirectoryFiles(path: String): List<MediaEntity> {
        return mediaList.value.filter {
            it.path.startsWith(path)
        }
    }

    fun scanLocalMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            _isMediaScanning.value = true
            try {
                mediaRepository.scanMedia(getApplication())
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isMediaScanning.value = false
            }
        }
    }

    fun refreshMedia() {
        scanLocalMedia()
    }

    fun setPlayingItem(item: MediaEntity?) {
        _currentPlayingItem.value = item
        if (item != null) {
            val currentQueue = _playQueue.value
            val existingIndex = currentQueue.indexOfFirst { it.uriString == item.uriString }
            if (existingIndex >= 0) {
                _currentQueueIndex.value = existingIndex
            } else if (currentQueue.isEmpty()) {
                // Auto-load all files from the same folder or same type into queue in sequence
                val allList = mediaList.value
                val itemFolder = item.path.substringBeforeLast('/', missingDelimiterValue = "")
                val folderItems = if (itemFolder.isNotBlank()) {
                    allList.filter { it.isVideo == item.isVideo && it.path.substringBeforeLast('/', missingDelimiterValue = "") == itemFolder }
                } else {
                    allList.filter { it.isVideo == item.isVideo }
                }
                val queueToSet = if (folderItems.isNotEmpty() && folderItems.any { it.uriString == item.uriString }) {
                    folderItems
                } else {
                    listOf(item)
                }
                _playQueue.value = queueToSet
                val newIndex = queueToSet.indexOfFirst { it.uriString == item.uriString }.coerceAtLeast(0)
                _currentQueueIndex.value = newIndex
            } else {
                _playQueue.value = currentQueue + item
                _currentQueueIndex.value = _playQueue.value.size - 1
            }
            recordPlaybackHistory(item, 0L)
        }
    }

    fun setPlayingItemWithQueue(item: MediaEntity, queue: List<MediaEntity>, index: Int = 0) {
        _playQueue.value = queue
        val safeIndex = if (index in queue.indices) index else queue.indexOfFirst { it.uriString == item.uriString }.coerceAtLeast(0)
        _currentQueueIndex.value = safeIndex
        _currentPlayingItem.value = item
        recordPlaybackHistory(item, 0L)
    }

    fun autoLoadFolderToQueue(item: MediaEntity) {
        val allList = mediaList.value
        val itemFolder = item.path.substringBeforeLast('/', missingDelimiterValue = "")
        val folderItems = if (itemFolder.isNotBlank()) {
            allList.filter { it.isVideo == item.isVideo && it.path.substringBeforeLast('/', missingDelimiterValue = "") == itemFolder }
        } else {
            allList.filter { it.isVideo == item.isVideo }
        }
        val targetQueue = if (folderItems.isNotEmpty()) folderItems else listOf(item)
        _playQueue.value = targetQueue
        val newIndex = targetQueue.indexOfFirst { it.uriString == item.uriString }.coerceAtLeast(0)
        _currentQueueIndex.value = newIndex
        _currentPlayingItem.value = item
    }

    fun autoLoadAllMediaToQueue(isVideo: Boolean) {
        val allList = mediaList.value.filter { it.isVideo == isVideo }
        if (allList.isNotEmpty()) {
            val current = _currentPlayingItem.value
            _playQueue.value = allList
            val idx = if (current != null) allList.indexOfFirst { it.uriString == current.uriString }.coerceAtLeast(0) else 0
            _currentQueueIndex.value = idx
            if (current == null) {
                _currentPlayingItem.value = allList[0]
            }
        }
    }

    fun deloadQueue() {
        _playQueue.value = emptyList()
        _currentQueueIndex.value = 0
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val list = _playQueue.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices && fromIndex != toIndex) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _playQueue.value = list
            val currentItem = _currentPlayingItem.value
            if (currentItem != null) {
                _currentQueueIndex.value = list.indexOfFirst { it.uriString == currentItem.uriString }.coerceAtLeast(0)
            }
        }
    }

    fun playAll(items: List<MediaEntity>) {
        if (items.isNotEmpty()) {
            setPlayingItemWithQueue(items[0], items, 0)
        }
    }

    fun insertNext(item: MediaEntity) {
        insertNext(listOf(item))
    }

    fun insertNext(items: List<MediaEntity>) {
        val currentQueue = _playQueue.value.toMutableList()
        val currentIndex = _currentQueueIndex.value
        val insertAt = (currentIndex + 1).coerceAtMost(currentQueue.size)
        val existingUris = currentQueue.map { it.uriString }.toSet()
        val toAdd = items.filter { !existingUris.contains(it.uriString) }
        currentQueue.addAll(insertAt, toAdd)
        _playQueue.value = currentQueue
    }

    fun clearPlayingItem() {
        _currentPlayingItem.value = null
        _isPlaying.value = false
        PlayerControlBridge.onPlayerStateChanged(false)
    }

    fun setPlayingState(playing: Boolean) {
        _isPlaying.value = playing
        PlayerControlBridge.onPlayerStateChanged(playing)
    }

    fun clearQueue() {
        _playQueue.value = emptyList()
        _currentQueueIndex.value = 0
    }

    fun addToQueue(item: MediaEntity) {
        val list = _playQueue.value.toMutableList()
        if (list.none { it.uriString == item.uriString }) {
            list.add(item)
            _playQueue.value = list
        }
    }

    fun addToQueue(items: List<MediaEntity>) {
        val list = _playQueue.value.toMutableList()
        val existingUris = list.map { it.uriString }.toSet()
        val newItems = items.filter { !existingUris.contains(it.uriString) }
        list.addAll(newItems)
        _playQueue.value = list
    }

    fun removeFromQueue(index: Int) {
        val list = _playQueue.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _playQueue.value = list
            if (_currentQueueIndex.value >= list.size) {
                _currentQueueIndex.value = maxOf(0, list.size - 1)
            }
        }
    }

    fun setQueue(items: List<MediaEntity>, startIndex: Int = 0) {
        _playQueue.value = items
        val safeIndex = startIndex.coerceIn(0, maxOf(0, items.size - 1))
        _currentQueueIndex.value = safeIndex
        if (items.isNotEmpty()) {
            setPlayingItem(items[safeIndex])
        }
    }

    fun playNext(): MediaEntity? = playNextInQueue()
    fun playPrevious(): MediaEntity? = playPreviousInQueue()

    fun playNextInQueue(): MediaEntity? {
        val queue = _playQueue.value
        if (queue.isEmpty()) return null
        val nextIndex = (_currentQueueIndex.value + 1) % queue.size
        _currentQueueIndex.value = nextIndex
        val nextItem = queue[nextIndex]
        _currentPlayingItem.value = nextItem
        return nextItem
    }

    fun playPreviousInQueue(): MediaEntity? {
        val queue = _playQueue.value
        if (queue.isEmpty()) return null
        val prevIndex = if (_currentQueueIndex.value - 1 < 0) queue.size - 1 else _currentQueueIndex.value - 1
        _currentQueueIndex.value = prevIndex
        val prevItem = queue[prevIndex]
        _currentPlayingItem.value = prevItem
        return prevItem
    }

    var sleepTimerJob: Job? = null
    val sleepTimerRemainingSeconds = MutableStateFlow<Int?>(null)

    suspend fun getHistoryByUri(uriString: String): HistoryEntity? = historyRepository.getHistoryByUri(uriString)

    fun addPlaybackHistory(item: MediaEntity, progressMs: Long) {
        recordPlaybackHistory(item, progressMs)
    }

    fun recordPlaybackHistory(item: MediaEntity, progressMs: Long) {
        val prefs = preferencesState.value
        if (prefs.incognitoMode || !prefs.playHistoryEnabled) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyRepository.addHistory(
                    uriString = item.uriString,
                    title = item.title,
                    isVideo = item.isVideo,
                    duration = item.duration,
                    progressMs = progressMs
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearPlaybackHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyRepository.clearHistory()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearHistory() {
        clearPlaybackHistory()
    }

    fun deletePlaybackHistoryItem(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyRepository.deleteHistory(uriString)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteHistory(uriString: String) {
        deletePlaybackHistoryItem(uriString)
    }

    fun completeOnboarding() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = preferencesState.value
            preferenceRepository.updatePreferences(current.copy(onboardingCompleted = true))
        }
    }

    fun clearPendingDeleteIntent() {
        _pendingDeleteIntent.value = null
    }

    fun deleteMedia(item: MediaEntity) {
        deleteMediaItem(item)
    }

    fun deleteMedia(uriString: String) {
        val found = mediaList.value.find { it.uriString == uriString }
        if (found != null) {
            deleteMediaItem(found)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                mediaRepository.deleteMedia(uriString)
            }
        }
    }

    fun deleteMediaBatch(items: List<MediaEntity>) {
        deleteMediaItems(items)
    }

    fun deleteMediaItem(item: MediaEntity) {
        deleteMediaItems(listOf(item))
    }

    fun deleteMediaItems(items: List<MediaEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val context: Context = getApplication()
            val uris = items.map { android.net.Uri.parse(it.uriString) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    _pendingDeleteIntent.value = pi
                } catch (e: Exception) {
                    items.forEach { mediaRepository.deleteMedia(it.uriString) }
                }
            } else {
                items.forEach { item ->
                    try {
                        context.contentResolver.delete(android.net.Uri.parse(item.uriString), null, null)
                    } catch (e: Exception) {}
                    mediaRepository.deleteMedia(item.uriString)
                }
            }
        }
    }

    fun addMediaItem(item: MediaEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            mediaRepository.addMediaItem(item)
        }
    }

    fun loadDemoMediaPack() {
        viewModelScope.launch(Dispatchers.IO) {
            val samples = listOf(
                MediaEntity(
                    uriString = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    title = "Big Buck Bunny (1080p)",
                    artist = "Blender Open Movie",
                    album = "Sample Media Pack",
                    duration = 596000L,
                    size = 158008374L,
                    dateAdded = System.currentTimeMillis() / 1000,
                    isVideo = true,
                    path = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    mimeType = "video/mp4",
                    genre = "Animation"
                ),
                MediaEntity(
                    uriString = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                    title = "Elephants Dream (4K)",
                    artist = "Orange Open Movie",
                    album = "Sample Media Pack",
                    duration = 653000L,
                    size = 178008374L,
                    dateAdded = System.currentTimeMillis() / 1000,
                    isVideo = true,
                    path = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                    mimeType = "video/mp4",
                    genre = "Sci-Fi"
                ),
                MediaEntity(
                    uriString = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
                    title = "Sintel (Fantasy Adventure)",
                    artist = "Durian Open Movie",
                    album = "Sample Media Pack",
                    duration = 888000L,
                    size = 208008374L,
                    dateAdded = System.currentTimeMillis() / 1000,
                    isVideo = true,
                    path = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
                    mimeType = "video/mp4",
                    genre = "Fantasy"
                ),
                MediaEntity(
                    uriString = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                    title = "Tears of Steel (VFX Demo)",
                    artist = "Mango Open Movie",
                    album = "Sample Media Pack",
                    duration = 734000L,
                    size = 168008374L,
                    dateAdded = System.currentTimeMillis() / 1000,
                    isVideo = true,
                    path = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                    mimeType = "video/mp4",
                    genre = "Sci-Fi"
                ),
                MediaEntity(
                    uriString = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                    title = "For Bigger Blazes (Action Trailer)",
                    artist = "Chromecast Demos",
                    album = "Sample Media Pack",
                    duration = 15000L,
                    size = 15000000L,
                    dateAdded = System.currentTimeMillis() / 1000,
                    isVideo = true,
                    path = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                    mimeType = "video/mp4",
                    genre = "Trailer"
                ),
                MediaEntity(
                    uriString = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                    title = "SoundHelix Acoustic Flow 1",
                    artist = "SoundHelix Studio",
                    album = "Nordic Lounge",
                    duration = 372000L,
                    size = 8900000L,
                    dateAdded = System.currentTimeMillis() / 1000,
                    isVideo = false,
                    path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                    mimeType = "audio/mpeg",
                    genre = "Electronic"
                ),
                MediaEntity(
                    uriString = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                    title = "SoundHelix Melodic Horizon 2",
                    artist = "SoundHelix Studio",
                    album = "Nordic Lounge",
                    duration = 423000L,
                    size = 10100000L,
                    dateAdded = System.currentTimeMillis() / 1000,
                    isVideo = false,
                    path = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                    mimeType = "audio/mpeg",
                    genre = "Ambient"
                )
            )
            samples.forEach { sample ->
                mediaRepository.addMediaItem(sample)
            }
        }
    }

    fun addNetworkStream(title: String, url: String, isVideo: Boolean, group: String = "Live Stream") {
        addCustomStream(title, url, isVideo, group)
    }

    fun addCustomStream(title: String, url: String, isVideo: Boolean, group: String = "Live Stream") {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = MediaEntity(
                uriString = url,
                title = title,
                artist = "Custom Stream",
                album = group,
                duration = 0L,
                size = 0L,
                dateAdded = System.currentTimeMillis() / 1000,
                isVideo = isVideo,
                path = url,
                mimeType = if (isVideo) "video/x-stream" else "audio/x-stream",
                genre = group
            )
            mediaRepository.addMediaItem(entity)
        }
    }

    fun renameMediaFile(context: Context, item: MediaEntity, newDisplayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(item.path)
                if (file.exists()) {
                    val ext = file.extension
                    val newName = if (ext.isNotBlank() && !newDisplayName.endsWith(".$ext")) "$newDisplayName.$ext" else newDisplayName
                    val newFile = java.io.File(file.parentFile, newName)
                    if (file.renameTo(newFile)) {
                        val updated = item.copy(
                            title = newDisplayName,
                            path = newFile.absolutePath,
                            uriString = if (item.uriString.startsWith("file://")) android.net.Uri.fromFile(newFile).toString() else item.uriString
                        )
                        mediaRepository.addMediaItem(updated)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        _equalizerEnabled.value = enabled
    }

    fun applyPreset(presetName: String) {
        _currentEqualizerPreset.value = presetName
        val presetBands = when (presetName) {
            "Bass Boost" -> listOf(600, 350, 0, 0, 0)
            "Vocal Boost" -> listOf(-200, 200, 600, 300, -100)
            "Treble Boost" -> listOf(-300, 0, 100, 500, 800)
            "Rock" -> listOf(500, 250, -100, 300, 500)
            "Pop" -> listOf(-150, 200, 500, 200, -150)
            "Jazz" -> listOf(300, 100, -200, 200, 400)
            "Classical" -> listOf(400, 200, -100, 250, 350)
            else -> listOf(0, 0, 0, 0, 0)
        }
        val current = _equalizerBands.value
        _equalizerBands.value = current.mapIndexed { idx, band ->
            val lvl = presetBands.getOrElse(idx) { 0 }.toShort()
            band.copy(currentLevelMilliBel = lvl)
        }
    }

    fun setEqualizerBandLevel(bandIndex: Short, level: Short) {
        setEqualizerBandLevel(bandIndex.toInt(), level)
    }

    fun setEqualizerBandLevel(bandIndex: Int, level: Short) {
        val current = _equalizerBands.value.toMutableList()
        if (bandIndex in current.indices) {
            current[bandIndex] = current[bandIndex].copy(currentLevelMilliBel = level)
            _equalizerBands.value = current
            _currentEqualizerPreset.value = "Custom"
        }
    }

    fun ensureDefaultBands() {
        if (_equalizerBands.value.isEmpty()) {
            _equalizerBands.value = listOf(
                EqualizerBand(0, 60, -1500, 1500, 0),
                EqualizerBand(1, 230, -1500, 1500, 0),
                EqualizerBand(2, 910, -1500, 1500, 0),
                EqualizerBand(3, 3600, -1500, 1500, 0),
                EqualizerBand(4, 14000, -1500, 1500, 0)
            )
        }
    }

    fun updatePerVideoAudio(uriString: String, volume: Float, audioDelayMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = preferencesState.value
            try {
                val json = if (current.perVideoSettingsJson.isBlank()) JSONObject() else JSONObject(current.perVideoSettingsJson)
                val videoObj = json.optJSONObject(uriString) ?: JSONObject()
                videoObj.put("volume", volume.toDouble())
                videoObj.put("audioDelayMs", audioDelayMs)
                json.put(uriString, videoObj)
                preferenceRepository.updatePreferences(current.copy(perVideoSettingsJson = json.toString()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updatePerVideoSubtitle(uriString: String, subUri: String?, subDelayMs: Long, subTrackIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = preferencesState.value
            try {
                val json = if (current.perVideoSettingsJson.isBlank()) JSONObject() else JSONObject(current.perVideoSettingsJson)
                val videoObj = json.optJSONObject(uriString) ?: JSONObject()
                if (subUri != null) videoObj.put("subUri", subUri)
                videoObj.put("subDelayMs", subDelayMs)
                videoObj.put("subTrackIndex", subTrackIndex)
                json.put(uriString, videoObj)
                preferenceRepository.updatePreferences(current.copy(perVideoSettingsJson = json.toString()))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateSubtitlePreferences(
        subtitleSize: Float? = null,
        subtitleTextColor: String? = null,
        subtitleBackground: String? = null,
        subtitleFontStyle: String? = null,
        subtitleOutlineColor: String? = null,
        subtitleOutlineWidth: Float? = null,
        subtitleShadowColor: String? = null,
        subtitleShadowRadius: Float? = null,
        subtitleShadowOpacity: Float? = null,
        subtitleOutlineOpacity: Float? = null,
        subtitleOpacity: Float? = null,
        subtitlePreset: String? = null,
        subtitleEncoding: String? = null,
        subtitleVerticalOffset: Float? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = preferencesState.value
            val updated = current.copy(
                subtitleSize = subtitleSize ?: current.subtitleSize,
                subtitleTextColor = subtitleTextColor ?: current.subtitleTextColor,
                subtitleBackground = subtitleBackground ?: current.subtitleBackground,
                subtitleFontStyle = subtitleFontStyle ?: current.subtitleFontStyle,
                subtitleOutlineColor = subtitleOutlineColor ?: current.subtitleOutlineColor,
                subtitleOutlineWidth = subtitleOutlineWidth ?: current.subtitleOutlineWidth,
                subtitleShadowColor = subtitleShadowColor ?: current.subtitleShadowColor,
                subtitleShadowRadius = subtitleShadowRadius ?: current.subtitleShadowRadius,
                subtitleShadowOpacity = subtitleShadowOpacity ?: current.subtitleShadowOpacity,
                subtitleOutlineOpacity = subtitleOutlineOpacity ?: current.subtitleOutlineOpacity,
                subtitleOpacity = subtitleOpacity ?: current.subtitleOpacity,
                subtitlePreset = subtitlePreset ?: current.subtitlePreset,
                subtitleEncoding = subtitleEncoding ?: current.subtitleEncoding,
                subtitleVerticalOffset = subtitleVerticalOffset ?: current.subtitleVerticalOffset
            )
            preferenceRepository.updatePreferences(updated)
        }
    }

    fun applySubtitlePreset(presetName: String) {
        when (presetName) {
            "White on Black" -> {
                updateSubtitlePreferences(
                    subtitleTextColor = "#FFFFFF",
                    subtitleBackground = "#CC000000",
                    subtitleOutlineColor = "#00000000",
                    subtitleOutlineWidth = 0f,
                    subtitleShadowColor = "#00000000",
                    subtitleShadowRadius = 0f,
                    subtitlePreset = "White on Black"
                )
            }
            "Yellow on Black" -> {
                updateSubtitlePreferences(
                    subtitleTextColor = "#FFFF00",
                    subtitleBackground = "#CC000000",
                    subtitleOutlineColor = "#00000000",
                    subtitleOutlineWidth = 0f,
                    subtitleShadowColor = "#00000000",
                    subtitleShadowRadius = 0f,
                    subtitlePreset = "Yellow on Black"
                )
            }
            "White Outline" -> {
                updateSubtitlePreferences(
                    subtitleTextColor = "#FFFFFF",
                    subtitleBackground = "#00000000",
                    subtitleOutlineColor = "#FF000000",
                    subtitleOutlineWidth = 2f,
                    subtitleShadowColor = "#80000000",
                    subtitleShadowRadius = 3f,
                    subtitlePreset = "White Outline"
                )
            }
            "Yellow Outline" -> {
                updateSubtitlePreferences(
                    subtitleTextColor = "#FFFF00",
                    subtitleBackground = "#00000000",
                    subtitleOutlineColor = "#FF000000",
                    subtitleOutlineWidth = 2f,
                    subtitleShadowColor = "#80000000",
                    subtitleShadowRadius = 3f,
                    subtitlePreset = "Yellow Outline"
                )
            }
            "Soft Shadow" -> {
                updateSubtitlePreferences(
                    subtitleTextColor = "#FFFFFF",
                    subtitleBackground = "#00000000",
                    subtitleOutlineColor = "#00000000",
                    subtitleOutlineWidth = 0f,
                    subtitleShadowColor = "#CC000000",
                    subtitleShadowRadius = 6f,
                    subtitlePreset = "Soft Shadow"
                )
            }
            else -> {
                updateSubtitlePreferences(subtitlePreset = "Custom")
            }
        }
    }

    fun onVolumeKeyPressed(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                PlayerControlBridge.onVolumeKeyPressed(true)
                false
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                PlayerControlBridge.onVolumeKeyPressed(false)
                false
            }
            else -> false
        }
    }

    fun downloadFileFromWeb(item: MediaEntity) {
        utilDownloadFileFromWeb(getApplication(), item.uriString)
    }

    fun downloadFileFromWeb(context: Context, url: String) {
        utilDownloadFileFromWeb(context, url)
    }

    fun shareMediaItems(context: Context, items: List<MediaEntity>) {
        utilShareMediaItems(context, items)
    }

    fun setAsRingtone(context: Context, item: MediaEntity) {
        utilSetAsRingtone(context, item)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            exoPlayer.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
