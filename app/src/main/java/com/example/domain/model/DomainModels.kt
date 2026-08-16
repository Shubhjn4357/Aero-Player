package com.example.domain.model

/**
 * Domain representation of Media Items decoupled from Room or UI frameworks.
 */
data class MediaItemDomain(
    val uriString: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val isVideo: Boolean,
    val path: String,
    val mimeType: String?,
    val subtitleUri: String? = null,
    val genre: String? = "Unknown"
) {
    val displayArtistName: String
        get() = if (artist.isNullOrBlank() ||
            artist.equals("unknown", ignoreCase = true) ||
            artist.equals("<unknown>", ignoreCase = true) ||
            artist.equals("Unknown Artist", ignoreCase = true)) {
            "Local Media"
        } else {
            artist
        }
}

/**
 * Domain model for Playback History.
 */
data class PlaybackHistoryDomain(
    val uriString: String,
    val title: String,
    val isVideo: Boolean,
    val durationMs: Long,
    val lastPlayedTimeMs: Long,
    val progressMs: Long
)

/**
 * Domain model for Equalizer Bands.
 */
data class EqualizerBandDomain(
    val index: Int,
    val centerFrequencyHz: Int,
    val minLevelMilliBel: Short,
    val maxLevelMilliBel: Short,
    val currentLevelMilliBel: Short
)
