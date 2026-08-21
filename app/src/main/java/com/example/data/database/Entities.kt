package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_items")
data class MediaEntity(
    @PrimaryKey val uriString: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val isVideo: Boolean,
    val path: String,
    val mimeType: String?,
    val subtitleUri: String? = null,
    val genre: String? = "Unknown"
)

val MediaEntity.displayArtist: String
    get() = if (artist.isNullOrBlank() || 
        artist.equals("unknown", ignoreCase = true) || 
        artist.equals("<unknown>", ignoreCase = true) || 
        artist.equals("Unknown Artist", ignoreCase = true)) {
        "Local Media"
    } else {
        artist
    }

@Entity(tableName = "playback_history")
data class HistoryEntity(
    @PrimaryKey val uriString: String,
    val title: String,
    val isVideo: Boolean,
    val duration: Long,
    val lastPlayedTime: Long,
    val progressMs: Long
)

@Entity(tableName = "app_preferences")
data class PreferenceEntity(
    @PrimaryKey val id: Int = 1,
    val themeMode: String = "System", // "System", "Light", "Dark"
    val listStyle: String = "Grid", // "List", "Grid"
    val sortBy: String = "title", // "title", "date", "size"
    val sortAscending: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val resizeMode: Int = 0, // 0 = Fit, 1 = Fill, 2 = Zoom, 3 = Stretch
    val subtitleSize: Float = 16f,
    val subtitleColor: String = "#FFFFFF",
    val autoScanEnabled: Boolean = true,
    val mediaLibraryFoldersJson: String = "[\"Internal Storage\", \"Movies\", \"Download\", \"DCIM\", \"Music\", \"WhatsApp\"]",
    val defaultOrientation: String = "Landscape", // "Automatic (sensor)", "Locked at start", "Landscape", "Portrait", "Reverse landscape", "Reverse portrait"
    val rotationLock: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val doubleTapSeekSeconds: Int = 10,
    val bannedFoldersJson: String = "[]",
    val favoriteFoldersJson: String = "[\"Movies\", \"Music\", \"WhatsApp\"]",
    val playlistsJson: String = "{}",
    val meteredNetworkAction: String = "Warn me (the warning may be missed for audio playback)", // "Warn me (the warning may be missed for audio playback)", "Block streaming", "Allow streaming"
    val playHistoryEnabled: Boolean = true,
    val saveVideoQueueHistory: Boolean = true,
    val saveAudioQueueHistory: Boolean = true,
    val hwAcceleration: String = "Full", // "Automatic", "Disabled", "Decoding", "Full"
    val backgroundMode: String = "STOP_PLAYBACK", // "STOP_PLAYBACK", "PLAY_BACKGROUND_AUDIO", "LAUNCH_PIP_MODE"
    val resumePlaybackBehavior: String = "Always", // "Always", "Ask", "Never"
    val usePerVideoSettings: Boolean = false,
    val perVideoSettingsJson: String = "{}",
    val defaultSubtitleLanguage: String = "No language preference",
    val autoLoadSubtitles: Boolean = true,
    val subtitleBackground: String = "#00000000",
    val subtitleBackgroundEnabled: Boolean = false,
    val subtitleTextColor: String = "#FFFFFF",
    val subtitleFontStyle: String = "Normal",
    val subtitleBold: Boolean = false,
    val subtitleShadowEnabled: Boolean = true,
    val subtitleShadowColor: String = "#FF000000",
    val subtitleShadowRadius: Float = 3f,
    val subtitleShadowOpacity: Float = 1.0f,
    val subtitleOutlineColor: String = "#FF000000",
    val subtitleOutlineWidth: Float = 2f,
    val subtitleOutlineOpacity: Float = 1.0f,
    val subtitleOpacity: Float = 1.0f,
    val subtitlePreset: String = "Custom",
    val subtitleEncoding: String = "Default (Windows-1252)",
    val subtitleVerticalOffset: Float = 0.08f,
    // Video settings
    val videoOutput: String = "Automatic", // "Automatic", "OpenGL ES 2.0", "OpenGL ES 3.0", "Android SurfaceView"
    val alwaysFastSeek: Boolean = false,
    val useCustomPipPopup: Boolean = false,
    val restoreVideoFromBackground: Boolean = false,
    val matchDisplayFrameRate: Boolean = true,
    val preferredVideoResolution: String = "Best available",
    val preferCloneSecondaryDisplay: Boolean = false,
    // Interface settings
    val showMissingMedia: Boolean = true,
    val sleepTimerDuration: String = "Disabled",
    val incognitoMode: Boolean = false,
    val persistentIncognitoMode: Boolean = true,
    val showSeenVideoMarker: Boolean = true,
    val showVideoThumbnails: Boolean = true,
    val showLastPlaylistTip: Boolean = true,
    val mediaCoverOnLockscreen: Boolean = true,
    val seekButtonsInNotification: Boolean = false,
    // Audio settings
    val audioOutput: String = "AudioTrack", // "AudioTrack", "OpenSL ES", "AAudio"
    val resumePlaybackAfterCall: Boolean = true,
    val stopOnAppSwipe: Boolean = false,
    val digitalAudioPassthrough: Boolean = false,
    val preferredAudioLanguage: String = "No language preference",
    val resumePlayedAudio: String = "Always", // "Always", "Ask", "Never"
    val detectHeadset: Boolean = true,
    val resumeOnHeadsetInsertion: Boolean = false,
    val ignoreHeadsetButtons: Boolean = false,
    val enableReplayGain: Boolean = false,
    val replayGainMode: String = "Track mode", // "Track mode", "Album mode", "None"
    // Advanced & Network settings
    val networkCachingMs: Int = 1500,
    val preferSmb1: Boolean = true,
    val httpUserAgent: String = "Not set",
    // Parental Control
    val parentalControlEnabled: Boolean = false,
    val parentalPin: String = "0000",
    val parentalLockSettings: Boolean = true,
    val parentalLockStreams: Boolean = true,
    val parentalLockSensitiveFolders: Boolean = true,
    // System & General
    val saveVolumeBrightnessBehavior: String = "None", // "None", "Global", "Individual"
    val globalVolume: Float = 1.0f,
    val globalBrightness: Float = 0.5f,
    val useDynamicColor: Boolean = true,
    val useGroupWiseFolderStyle: Boolean = true,
    val groupByStyle: String = "none",
    val deletedUrisJson: String = "[]",
    val isCastEnabled: Boolean = true,
    val selectedCastDevice: String = "Living Room TV (Chromecast)",
    val castProtocol: String = "Chromecast / DLNA",
    val castQuality: String = "High (320kbps / 1080p)",
    val castBufferSize: String = "Standard (3s)",
    val autoConnectCast: Boolean = false,
    val castAudioDelayMs: Int = 0,
    val castVolume: Float = 1.0f,
    val useOpenGlNetworkRemote: Boolean = false,
    val openGlRemoteUrl: String = "gl://192.168.1.100:8080",
    val openGlRenderMode: String = "Hardware Accelerated GL (Network ES 3.0)",
    val pauseOnScreenSleep: Boolean = true,
    val keepCastingOnScreenSleep: Boolean = true,
    val defaultPlayerEngine: String = "Auto (Smart Format Detection)" // "Auto (Smart Format Detection)", "VLC Engine (vlcjni)", "Media3 ExoPlayer"
)
