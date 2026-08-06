package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.data.database.MediaEntity
import com.example.data.model.VideoFile
import kotlinx.coroutines.launch

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

fun MainViewModel.setPlayingItem(item: MediaEntity?) {
    _currentPlayingItem.value = item
    if (item != null) {
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

fun MainViewModel.clearPlayingItem() {
    try {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    } catch (e: Exception) {}
    try {
        PlayerControlBridge.vlcPlayerRef?.stop()
    } catch (e: Exception) {}
    _currentPlayingItem.value = null
}

fun MainViewModel.setPlayingItemWithQueue(item: MediaEntity, queue: List<MediaEntity>) {
    _playQueue.value = queue
    val idx = queue.indexOfFirst { it.uriString == item.uriString }
    _currentQueueIndex.value = if (idx >= 0) idx else 0
    _currentPlayingItem.value = item
}

fun MainViewModel.downloadFileFromWeb(mediaItem: MediaEntity) {
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

fun MainViewModel.playAll(items: List<MediaEntity>) {
    val distinctItems = items.distinctBy { it.uriString }
    _playQueue.value = distinctItems
    _currentQueueIndex.value = 0
    if (distinctItems.isNotEmpty()) {
        _currentPlayingItem.value = distinctItems[0]
    }
}

fun MainViewModel.addToQueue(items: List<MediaEntity>) {
    val currentQueue = _playQueue.value.toMutableList()
    val existingUris = currentQueue.map { it.uriString }.toSet()
    val newItems = items.distinctBy { it.uriString }.filter { it.uriString !in existingUris }

    if (newItems.isEmpty()) {
        if (_currentPlayingItem.value == null && currentQueue.isNotEmpty()) {
            _currentQueueIndex.value = 0
            _currentPlayingItem.value = currentQueue[0]
        }
        return
    }

    val wasEmptyOrIdle = currentQueue.isEmpty() || _currentPlayingItem.value == null
    val firstNewIndex = currentQueue.size
    currentQueue.addAll(newItems)
    _playQueue.value = currentQueue

    if (wasEmptyOrIdle) {
        _currentQueueIndex.value = firstNewIndex
        _currentPlayingItem.value = currentQueue[firstNewIndex]
    }
}

fun MainViewModel.insertNext(item: MediaEntity) {
    val currentQueue = _playQueue.value.toMutableList()
    currentQueue.removeAll { it.uriString == item.uriString }
    val index = _currentQueueIndex.value
    if (currentQueue.isEmpty() || _currentPlayingItem.value == null) {
        currentQueue.add(item)
        _playQueue.value = currentQueue
        _currentQueueIndex.value = 0
        setPlayingItem(item)
    } else {
        val insertIndex = (index + 1).coerceAtMost(currentQueue.size)
        currentQueue.add(insertIndex, item)
        _playQueue.value = currentQueue
    }
}

fun MainViewModel.playNext() {
    val queue = _playQueue.value
    if (queue.isEmpty()) return
    val nextIndex = (_currentQueueIndex.value + 1) % queue.size
    _currentQueueIndex.value = nextIndex
    val nextItem = queue[nextIndex]
    _currentPlayingItem.value = nextItem

    if (PlayerControlBridge.isVlcActive && PlayerControlBridge.vlcPlayerRef != null) {
        try {
            val context = getApplication<Application>()
            val resolvedUri = com.example.util.ContentResolverUtils.resolvePlayableUri(
                context,
                nextItem.uriString,
                nextItem.path
            )
            PlayerControlBridge.vlcPlayerRef?.playMediaUri(uri = resolvedUri)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    } else if (exoPlayer.mediaItemCount > nextIndex) {
        try {
            exoPlayer.seekToDefaultPosition(nextIndex)
            if (!exoPlayer.isPlaying) {
                exoPlayer.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    updateNotificationState()
    updateWidgets()
}

fun MainViewModel.playPrevious() {
    val queue = _playQueue.value
    if (queue.isEmpty()) return
    val prevIndex = if (_currentQueueIndex.value - 1 >= 0) _currentQueueIndex.value - 1 else queue.size - 1
    _currentQueueIndex.value = prevIndex
    val prevItem = queue[prevIndex]
    _currentPlayingItem.value = prevItem

    if (PlayerControlBridge.isVlcActive && PlayerControlBridge.vlcPlayerRef != null) {
        try {
            val context = getApplication<Application>()
            val resolvedUri = com.example.util.ContentResolverUtils.resolvePlayableUri(
                context,
                prevItem.uriString,
                prevItem.path
            )
            PlayerControlBridge.vlcPlayerRef?.playMediaUri(uri = resolvedUri)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    } else if (exoPlayer.mediaItemCount > prevIndex) {
        try {
            exoPlayer.seekToDefaultPosition(prevIndex)
            if (!exoPlayer.isPlaying) {
                exoPlayer.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    updateNotificationState()
    updateWidgets()
}

fun MainViewModel.clearQueue() {
    val currentItem = _currentPlayingItem.value
    if (currentItem != null) {
        _playQueue.value = listOf(currentItem)
        _currentQueueIndex.value = 0
    } else {
        _playQueue.value = emptyList()
        _currentQueueIndex.value = 0
    }
}

fun MainViewModel.removeFromQueue(index: Int) {
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

fun MainViewModel.addNetworkStream(title: String, url: String, isVideo: Boolean) {
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

fun MainViewModel.formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    val hours = durationMs / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
