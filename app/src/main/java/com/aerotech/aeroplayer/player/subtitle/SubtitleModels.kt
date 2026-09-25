package com.aerotech.aeroplayer.player.subtitle

import androidx.compose.runtime.Immutable

/**
 * Text subtitle cue with millisecond timestamps and cleaned display text.
 */
@Immutable
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val plainText: String = text,
    val alignment: SubtitleAlignment = SubtitleAlignment.BOTTOM_CENTER
)

enum class SubtitleAlignment {
    BOTTOM_CENTER,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP_CENTER,
    CENTER
}

/**
 * Information about a subtitle track (internal container track or external file).
 */
@Immutable
data class SubtitleTrack(
    val id: String,
    val name: String,
    val language: String? = null,
    val isExternal: Boolean = false,
    val isBitmap: Boolean = false,
    val uriString: String? = null
)

/**
 * Consolidated subtitle engine state.
 */
@Immutable
data class SubtitleEngineState(
    val isEnabled: Boolean = true,
    val isBitmapSubtitleActive: Boolean = false,
    val activeTrack: SubtitleTrack? = null,
    val availableTracks: List<SubtitleTrack> = emptyList(),
    val currentCues: List<SubtitleCue> = emptyList(),
    val subDelayMs: Long = 0L,
    val lastLoadedSource: String? = null
)
