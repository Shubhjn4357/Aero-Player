package com.example.data.model

import kotlinx.coroutines.flow.StateFlow

// 1. Domain Data Models
data class VideoFile(
    val id: String,               // Unique ID
    val title: String,            // Title of the video
    val absolutePath: String,     // Absolute path of the video file
    val duration: Long,           // Duration in ms
    val resolution: String,       // Resolution (e.g. "1080p")
    val size: Long,               // Size in bytes
    val parentFolderName: String  // Parent folder name
)

data class Folder(
    val name: String,                    // Folder name
    val absolutePath: String,            // Absolute path
    val videoCount: Int,                 // Count of videos inside
    val thumbnailVideoIds: List<String>  // Thumbnail video IDs supporting 4-quadrant preview
)

// 2. Global Playback Queue Engine Interface
interface AeroPlaybackManager {
    val currentQueue: StateFlow<List<VideoFile>>
    val currentPlayingVideo: StateFlow<VideoFile?>
    val isPlaying: StateFlow<Boolean>
    
    fun playFolder(allVideosInFolder: List<VideoFile>, startVideoIndex: Int = 0)
    fun playList(videoList: List<VideoFile>, startIndex: Int = 0)
    fun next()
    fun previous()
}

// 3. Browse Screen State Machine States
sealed interface BrowseScreenState {
    object FolderList : BrowseScreenState
    data class FileList(val folderName: String) : BrowseScreenState
}

data class SelectionState(
    val isInSelectionMode: Boolean = false,
    val selectedFolderPaths: Set<String> = emptySet(),
    val selectedVideoIds: Set<String> = emptySet()
)
