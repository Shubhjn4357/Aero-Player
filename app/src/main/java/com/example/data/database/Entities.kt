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
    val defaultOrientation: String = "System Auto", // "System Auto", "Portrait", "Landscape", "Reverse Portrait", "Reverse Landscape"
    val rotationLock: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val doubleTapSeekSeconds: Int = 10,
    val bannedFoldersJson: String = "[]",
    val favoriteFoldersJson: String = "[\"Movies\", \"Music\", \"WhatsApp\"]",
    val playlistsJson: String = "{}",
    val meteredNetworkAction: String = "Warn", // "Warn", "Block", "Allow"
    val playHistoryEnabled: Boolean = true,
    val saveVideoQueueHistory: Boolean = true,
    val saveAudioQueueHistory: Boolean = true,
    val hwAcceleration: String = "Full", // "Disabled", "Decoding", "Full"
    val backgroundMode: String = "Play in Background", // "Stop", "Play in Background", "PiP"
    val resumePlaybackBehavior: String = "Ask Every Time", // "Ask Every Time", "Always Resume", "Always Start from Beginning"
    val usePerVideoSettings: Boolean = false,
    val perVideoSettingsJson: String = "{}",
    val defaultSubtitleLanguage: String = "English",
    val subtitleBackground: String = "#00000000",
    val subtitleTextColor: String = "#FFFFFF",
    val subtitleFontStyle: String = "Normal",
    val subtitleShadowColor: String = "#FF000000",
    val subtitleShadowRadius: Float = 3f,
    val subtitleShadowOpacity: Float = 1.0f,
    val subtitleOutlineColor: String = "#FF000000",
    val subtitleOutlineWidth: Float = 2f,
    val subtitleOutlineOpacity: Float = 1.0f,
    val subtitleOpacity: Float = 1.0f,
    val subtitlePreset: String = "Custom",
    val subtitleEncoding: String = "UTF-8",
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
    val keepCastingOnScreenSleep: Boolean = true
)
