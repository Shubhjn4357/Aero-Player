package com.emptysol.aeroplayer.player.subtitle

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
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified Subtitle Engine that orchestrates CPU-offloaded text extraction/parsing,
 * O(log N) binary-search synchronization, and dynamic styling across both LibVLC and ExoPlayer pipelines.
 */
class UnifiedSubtitleEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())
) {
    companion object {
        private const val TAG = "UnifiedSubtitleEngine"
    }

    private val _state = MutableStateFlow(SubtitleEngineState())
    val state: StateFlow<SubtitleEngineState> = _state.asStateFlow()

    @Volatile
    private var parsedCues: List<SubtitleCue> = emptyList()

    @Volatile
    private var maxCueDurationMs: Long = 0L

    @Volatile
    private var lastQueryPositionMs: Long = -1L

    private val loadGeneration = AtomicLong(0L)
    private var loadJob: Job? = null

    fun hasParsedCues(): Boolean = parsedCues.isNotEmpty()

    /**
     * Load an external subtitle file (SRT, VTT, ASS, SSA, LRC, SUB, SBV, SMI, MPL2, TMPlayer) cleanly.
     * Cancels any in-flight subtitle parse job to prevent out-of-order race conditions.
     */
    fun loadExternalSubtitle(
        context: Context,
        uriString: String,
        encoding: String = "UTF-8",
        trackName: String = "External Subtitle"
    ) {
        loadJob?.cancel()
        val generation = loadGeneration.incrementAndGet()
        loadJob = scope.launch {
            try {
                // Step 1: Read bytes on IO, Step 2: Parse on CPU Default dispatcher
                val cues = withContext(Dispatchers.IO) {
                    SubtitleParser.parseFromUri(context, uriString, encoding)
                }
                if (generation != loadGeneration.get()) return@launch

                applyParsedCues(
                    cues = cues,
                    track = SubtitleTrack(
                        id = uriString,
                        name = trackName,
                        isExternal = true,
                        isBitmap = false,
                        uriString = uriString
                    ),
                    source = uriString
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Silently handled subtitle load error for $uriString: ${e.message}")
            }
        }
    }

    /**
     * Set raw subtitle text directly using CPU Default dispatcher.
     */
    fun loadSubtitleText(
        content: String,
        formatHint: String? = null,
        trackName: String = "Loaded Subtitle"
    ) {
        loadJob?.cancel()
        val generation = loadGeneration.incrementAndGet()
        loadJob = scope.launch {
            try {
                val cues = withContext(Dispatchers.Default) {
                    SubtitleParser.parseText(content, formatHint)
                }
                if (generation != loadGeneration.get()) return@launch

                applyParsedCues(
                    cues = cues,
                    track = SubtitleTrack(
                        id = "raw_content_${System.currentTimeMillis()}",
                        name = trackName,
                        isExternal = true,
                        isBitmap = false
                    ),
                    source = trackName
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Silently handled subtitle text load error: ${e.message}")
            }
        }
    }

    private fun applyParsedCues(cues: List<SubtitleCue>, track: SubtitleTrack, source: String) {
        var maxDur = 0L
        for (i in cues.indices) {
            val d = cues[i].endMs - cues[i].startMs
            if (d > maxDur) maxDur = d
        }
        maxCueDurationMs = maxDur
        parsedCues = cues

        _state.update { current ->
            current.copy(
                isEnabled = true,
                isBitmapSubtitleActive = false,
                activeTrack = track,
                lastLoadedSource = source
            )
        }
        val pos = lastQueryPositionMs
        if (pos >= 0L) {
            updatePosition(pos)
        }
    }

    /**
     * Update playback position to extract active cues using O(log N) binary search.
     */
    fun updatePosition(positionMs: Long) {
        lastQueryPositionMs = positionMs
        val currentState = _state.value
        if (!currentState.isEnabled || currentState.isBitmapSubtitleActive) {
            if (currentState.currentCues.isNotEmpty()) {
                _state.update { it.copy(currentCues = emptyList()) }
            }
            return
        }

        val list = parsedCues
        if (list.isEmpty()) return

        val effectivePosition = positionMs + currentState.subDelayMs
        val matchingCues = deduplicateCues(findActiveCues(list, maxCueDurationMs, effectivePosition))

        if (_state.value.currentCues != matchingCues) {
            _state.update { it.copy(currentCues = matchingCues) }
        }
    }

    /**
     * Receive cues decoded directly by ExoPlayer (for embedded text tracks like WebVTT / SRT / tx3g / ASS).
     */
    fun onExoCues(exoCues: List<Cue>) {
        if (!_state.value.isEnabled) {
            if (_state.value.currentCues.isNotEmpty()) {
                _state.update { it.copy(currentCues = emptyList()) }
            }
            return
        }

        // Prefer custom parsed cues when an external subtitle file is active
        if (parsedCues.isNotEmpty()) {
            return
        }

        if (exoCues.isEmpty()) {
            if (_state.value.currentCues.isNotEmpty()) {
                _state.update { it.copy(currentCues = emptyList()) }
            }
            return
        }

        val hasBitmap = exoCues.any { it.bitmap != null }
        if (hasBitmap) {
            _state.update { it.copy(isBitmapSubtitleActive = true, currentCues = emptyList()) }
            return
        }

        val textCues = deduplicateCues(
            exoCues.mapNotNull { cue ->
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
        )

        if (_state.value.isBitmapSubtitleActive || _state.value.currentCues != textCues) {
            _state.update { it.copy(isBitmapSubtitleActive = false, currentCues = textCues) }
        }
    }

    /**
     * Deduplicates cues with identical normalized text (common in multi-layer ASS/SSA shadow/border tracks
     * and overlapping SRT timestamps) so the same line is never rendered twice.
     */
    internal fun deduplicateCues(cues: List<SubtitleCue>): List<SubtitleCue> {
        if (cues.size <= 1) return cues
        val seen = LinkedHashSet<String>(cues.size)
        val result = ArrayList<SubtitleCue>(cues.size)
        for (cue in cues) {
            val normalizedKey = cue.text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            if (normalizedKey.isNotEmpty() && seen.add(normalizedKey)) {
                result.add(cue)
            }
        }
        return result
    }

    fun setSubtitleDelay(delayMs: Long) {
        _state.update { it.copy(subDelayMs = delayMs) }
        val pos = lastQueryPositionMs
        if (pos >= 0L) {
            updatePosition(pos)
        }
    }

    fun setBitmapSubtitleActive(isBitmap: Boolean) {
        _state.update { current ->
            current.copy(
                isBitmapSubtitleActive = isBitmap,
                currentCues = if (isBitmap) emptyList() else current.currentCues
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(isEnabled = enabled) }
        if (!enabled) {
            _state.update { it.copy(currentCues = emptyList()) }
        } else {
            val pos = lastQueryPositionMs
            if (pos >= 0L) updatePosition(pos)
        }
    }

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
     * Clear external parsed cues so embedded ExoPlayer/VLC tracks can render cleanly when switched back.
     */
    fun clearExternalParsedCues() {
        loadGeneration.incrementAndGet()
        loadJob?.cancel()
        parsedCues = emptyList()
        maxCueDurationMs = 0L
        _state.update { it.copy(currentCues = emptyList(), lastLoadedSource = null) }
    }

    /**
     * Clear all cues and loaded subtitle data.
     */
    fun clear() {
        loadGeneration.incrementAndGet()
        loadJob?.cancel()
        parsedCues = emptyList()
        maxCueDurationMs = 0L
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

    /**
     * Fast O(log N) binary search upper-bound on sorted cues by `startMs`, bounded by `maxDurMs` when scanning backward.
     */
    internal fun findActiveCues(
        list: List<SubtitleCue> = parsedCues,
        maxDurMs: Long = maxCueDurationMs,
        positionMs: Long
    ): List<SubtitleCue> {
        if (list.isEmpty() || positionMs < 0L) return emptyList()

        // Binary search first index where startMs > positionMs
        var low = 0
        var high = list.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (list[mid].startMs <= positionMs) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        val upperIdx = low - 1
        if (upperIdx < 0) return emptyList()

        val earliestPossibleStart = if (maxDurMs > 0L) positionMs - maxDurMs else 0L
        val matched = ArrayList<SubtitleCue>(2)
        var i = upperIdx
        while (i >= 0) {
            val cue = list[i]
            if (cue.startMs < earliestPossibleStart) {
                break
            }
            if (positionMs in cue.startMs..cue.endMs) {
                matched.add(cue)
            }
            i--
        }
        if (matched.size > 1) {
            matched.reverse()
        }
        return matched
    }
}
