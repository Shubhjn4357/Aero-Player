package com.emptysol.aeroplayer.data.database

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
        artist.equals("Unknown Artist", ignoreCase = true) ||
        artist.contains("Local", ignoreCase = true) ||
        artist.contains("Online", ignoreCase = true)) {
        ""
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
    val selectedCastDevice: String = "",
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
    val defaultPlayerEngine: String = "Auto (Smart Format Detection)", // "Auto (Smart Format Detection)", "VLC Engine (vlcjni)", "Media3 ExoPlayer"
    // BitTorrent Client & Downloader Preferences
    val torrentDownloadFolderUri: String = "",
    val torrentDownloadFolderLabel: String = "Default (Downloads/AeroTorrents)",
    val torrentAutoBandwidth: Boolean = true,
    val torrentGlobalDownloadCapKbps: Int = 0, // 0 = Unlimited / Auto
    val torrentGlobalUploadCapKbps: Int = 512,
    val torrentDhtEnabled: Boolean = true,
    val torrentPexEnabled: Boolean = true,
    val torrentEncryptionMode: String = "Prefer", // "Allow", "Prefer", "Require"
    val torrentIpFilterEnabled: Boolean = false,
    val torrentIpFilterRules: String = "",
    val torrentRequireVpn: Boolean = false,
    val torrentAutoScanCompleted: Boolean = true,
    val torrentSequentialByDefault: Boolean = true,
    val torrentRemoteEnabled: Boolean = false,
    val torrentRemotePort: Int = 8090,
    val torrentRemotePin: String = "",
    val torrentSchedulerEnabled: Boolean = false,
    val torrentScheduleDaysJson: String = "[1,2,3,4,5,6,7]",
    val torrentScheduleStartMinute: Int = 0,
    val torrentScheduleEndMinute: Int = 1439,
    val torrentScheduleAction: String = "DOWNLOAD_AND_SEED", // "DOWNLOAD_AND_SEED", "SEED_ONLY", "PAUSE_ALL"
    val torrentPostCompleteAction: String = "NONE" // "NONE", "PAUSE_SEEDING", "STOP_SERVICE", "EXIT_APP"
)

@Entity(tableName = "torrent_tasks")
data class TorrentTaskEntity(
    @PrimaryKey val infoHash: String,
    val title: String,
    val sourceUri: String,
    val saveLocation: String,
    val status: String = "QUEUED", // "QUEUED", "METADATA", "DOWNLOADING", "PAUSED", "SEEDING", "COMPLETED", "ERROR", "VPN_PAUSED", "SCHEDULE_PAUSED"
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val downloadRateBps: Long = 0L,
    val uploadRateBps: Long = 0L,
    val peerCount: Int = 0,
    val seedCount: Int = 0,
    val queueOrder: Int = 0,
    val isSequential: Boolean = true,
    val downloadCapKbps: Int = 0,
    val uploadCapKbps: Int = 0,
    val encryptionMode: String = "Prefer",
    val filesJson: String = "[]",
    val trackersJson: String = "[]",
    val pieceLength: Long = 0L,
    val totalPieces: Int = 0,
    val completedPieces: Int = 0,
    val scanStatus: String = "UNSCANNED", // "UNSCANNED", "CLEAN", "WARNING", "BLOCKED"
    val scanSummary: String = "",
    val errorMessage: String? = null,
    val primaryMediaPath: String? = null,
    val addedAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long? = null
)

@Entity(tableName = "rss_feeds")
data class RssFeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val feedUrl: String,
    val includeFilter: String = "",
    val excludeFilter: String = "",
    val autoDownload: Boolean = true,
    val enabled: Boolean = true,
    val lastCheckedMs: Long = 0L,
    val lastMatchedTitle: String = "",
    val lastItemCount: Int = 0
)

