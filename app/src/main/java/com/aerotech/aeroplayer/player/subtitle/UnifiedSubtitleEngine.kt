package com.aerotech.aeroplayer.player.subtitle

import android.content.Context
import android.util.Log
import androidx.media3.common.text.Cue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified Subtitle Engine that orchestrates text extraction, parsing, synchronization,
 * and dynamic styling for both LibVLC and ExoPlayer playback pipelines.
 */
class UnifiedSubtitleEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())
) {
    companion object {
        private const val TAG = "UnifiedSubtitleEngine"
    }

    private val _state = MutableStateFlow(SubtitleEngineState())
    val state: StateFlow<SubtitleEngineState> = _state.asStateFlow()

    private var parsedCues: List<SubtitleCue> = emptyList()
    private var lastQueryPositionMs: Long = -1L

    /**
     * Load an external subtitle file (SRT, VTT, ASS, SSA) and parse it into native cues.
     */
    fun loadExternalSubtitle(
        context: Context,
        uriString: String,
        encoding: String = "UTF-8",
        trackName: String = "External Subtitle"
    ) {
        scope.launch {
            try {
                val cues = withContext(Dispatchers.IO) {
                    SubtitleParser.parseFromUri(context, uriString, encoding)
                }
                parsedCues = cues
                val track = SubtitleTrack(
                    id = uriString,
                    name = trackName,
                    isExternal = true,
                    isBitmap = false,
                    uriString = uriString
                )
                _state.update { current ->
                    current.copy(
                        isEnabled = true,
                        isBitmapSubtitleActive = false,
                        activeTrack = track,
                        lastLoadedSource = uriString
                    )
                }
                // Refresh cues at current position
                if (lastQueryPositionMs >= 0L) {
                    updatePosition(lastQueryPositionMs)
                }
            } catch (e: Throwable) {
                // Silently ignore parsing errors
                Log.w(TAG, "Silently handled subtitle load error for $uriString: ${e.message}")
            }
        }
    }

    /**
     * Set raw subtitle text directly.
     */
    fun loadSubtitleText(content: String, formatHint: String? = null, trackName: String = "Loaded Subtitle") {
        scope.launch {
            try {
                val cues = withContext(Dispatchers.IO) {
                    SubtitleParser.parseText(content, formatHint)
                }
                parsedCues = cues
                val track = SubtitleTrack(
                    id = "raw_content_${System.currentTimeMillis()}",
                    name = trackName,
                    isExternal = true,
                    isBitmap = false
                )
                _state.update { current ->
                    current.copy(
                        isEnabled = true,
                        isBitmapSubtitleActive = false,
                        activeTrack = track
                    )
                }
                if (lastQueryPositionMs >= 0L) {
                    updatePosition(lastQueryPositionMs)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Silently handled subtitle text load error: ${e.message}")
            }
        }
    }

    /**
     * Update playback position from video player to extract active cues in real-time.
     */
    fun updatePosition(positionMs: Long) {
        lastQueryPositionMs = positionMs
        if (!_state.value.isEnabled || _state.value.isBitmapSubtitleActive) {
            if (_state.value.currentCues.isNotEmpty()) {
                _state.update { it.copy(currentCues = emptyList()) }
            }
            return
        }

        if (parsedCues.isEmpty()) return

        val effectivePosition = positionMs + _state.value.subDelayMs
        val matchingCues = findActiveCues(effectivePosition)

        if (_state.value.currentCues != matchingCues) {
            _state.update { it.copy(currentCues = matchingCues) }
        }
    }

    /**
     * Receive cues decoded directly by ExoPlayer (for embedded text tracks like WebVTT / SRT / tx3g).
     */
    fun onExoCues(exoCues: List<Cue>) {
        if (!_state.value.isEnabled) {
            if (_state.value.currentCues.isNotEmpty()) {
                _state.update { it.copy(currentCues = emptyList()) }
            }
            return
        }

        // If we have custom parsed cues from an external subtitle, prefer those for exact styling
        if (parsedCues.isNotEmpty()) {
            return
        }

        if (exoCues.isEmpty()) {
            if (_state.value.currentCues.isNotEmpty()) {
                _state.update { it.copy(currentCues = emptyList()) }
            }
            return
        }

        // Check if any cues contain bitmap drawables or images
        val hasBitmap = exoCues.any { it.bitmap != null }
        if (hasBitmap) {
            _state.update { it.copy(isBitmapSubtitleActive = true, currentCues = emptyList()) }
            return
        }

        val textCues = exoCues.mapNotNull { cue ->
            val rawText = cue.text?.toString()?.trim() ?: ""
            val clean = SubtitleParser.cleanCueText(rawText)
            if (clean.isNotBlank()) {
                SubtitleCue(
                    startMs = 0L,
                    endMs = 0L,
                    text = clean,
                    plainText = clean
                )
            } else {
                null
            }
        }

        _state.update { it.copy(isBitmapSubtitleActive = false, currentCues = textCues) }
    }

    /**
     * Set subtitle sync delay in milliseconds (positive or negative).
     */
    fun setSubtitleDelay(delayMs: Long) {
        _state.update { it.copy(subDelayMs = delayMs) }
        if (lastQueryPositionMs >= 0L) {
            updatePosition(lastQueryPositionMs)
        }
    }

    /**
     * Mark bitmap subtitle active (PGS, VobSub, DVB, DVD subtitle).
     */
    fun setBitmapSubtitleActive(isBitmap: Boolean) {
        _state.update { it.copy(isBitmapSubtitleActive = isBitmap) }
    }

    /**
     * Enable or disable subtitle display.
     */
    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(isEnabled = enabled) }
        if (!enabled) {
            _state.update { it.copy(currentCues = emptyList()) }
        } else if (lastQueryPositionMs >= 0L) {
            updatePosition(lastQueryPositionMs)
        }
    }

    /**
     * Set available subtitle tracks.
     */
    fun setAvailableTracks(tracks: List<SubtitleTrack>, activeTrackId: String? = null) {
        val active = tracks.find { it.id == activeTrackId }
        val isBitmap = active?.isBitmap == true
        _state.update {
            it.copy(
                availableTracks = tracks,
                activeTrack = active,
                isBitmapSubtitleActive = isBitmap
            )
        }
    }

    /**
     * Clear all cues and loaded subtitle data.
     */
    fun clear() {
        parsedCues = emptyList()
        lastQueryPositionMs = -1L
        _state.update {
            it.copy(
                currentCues = emptyList(),
                activeTrack = null,
                isBitmapSubtitleActive = false,
                lastLoadedSource = null
            )
        }
    }

    private fun findActiveCues(positionMs: Long): List<SubtitleCue> {
        val list = parsedCues
        if (list.isEmpty()) return emptyList()

        // Binary search or linear scan since subtitle tracks usually have < 3000 cues
        val result = ArrayList<SubtitleCue>(2)
        for (i in list.indices) {
            val cue = list[i]
            if (positionMs in cue.startMs..cue.endMs) {
                result.add(cue)
            } else if (cue.startMs > positionMs) {
                // Since cues are sorted by startMs, we can break early if past current position
                if (result.isNotEmpty()) break
            }
        }
        return result
    }
}
