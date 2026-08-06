package com.example.player

import android.content.Context
import android.net.Uri
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Robust LibVLC (vlcjni) wrapper for AeroPlayer.
 * Provides direct internal video/audio decoding for all container formats,
 * including compressed MKVs (ContentCompAlgo 7), exotic audio codecs, and custom subtitle streams.
 */
class VlcPlayerWrapper(private val context: Context) {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    var isPlaying: Boolean = false
        private set

    var isBuffering: Boolean = false
        private set

    var currentPositionMs: Long = 0L
        private set

    var durationMs: Long = 0L
        private set

    private var lastPlayedUri: Uri? = null
    private var currentHwAccel: Boolean = true
    private var pendingSeekMs: Long = -1L

    // Callback listeners
    var onPlaybackStateChanged: ((isPlaying: Boolean, isBuffering: Boolean) -> Unit)? = null
    var onPositionUpdated: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    var onErrorOccurred: ((errorMessage: String) -> Unit)? = null
    var onCompletion: (() -> Unit)? = null

    init {
        initLibVLC()
    }

    private fun initLibVLC() {
        try {
            val options = arrayListOf(
                "--no-drop-late-frames",
                "--no-skip-frames",
                "--rtsp-tcp",
                "-vvv"
            )
            libVLC = LibVLC(context, options)
            val mp = MediaPlayer(libVLC)
            
            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        isPlaying = true
                        isBuffering = false
                        com.example.ui.viewmodel.PlayerControlBridge.onPlayerStateChanged(true)
                        if (pendingSeekMs > 0L) {
                            val seek = pendingSeekMs
                            pendingSeekMs = -1L
                            mp.time = seek
                            currentPositionMs = seek
                        }
                        onPlaybackStateChanged?.invoke(true, false)
                    }
                    MediaPlayer.Event.Paused -> {
                        isPlaying = false
                        isBuffering = false
                        com.example.ui.viewmodel.PlayerControlBridge.onPlayerStateChanged(false)
                        onPlaybackStateChanged?.invoke(false, false)
                    }
                    MediaPlayer.Event.Stopped -> {
                        isPlaying = false
                        isBuffering = false
                        com.example.ui.viewmodel.PlayerControlBridge.onPlayerStateChanged(false)
                        onPlaybackStateChanged?.invoke(false, false)
                    }
                    MediaPlayer.Event.EndReached -> {
                        isPlaying = false
                        isBuffering = false
                        com.example.ui.viewmodel.PlayerControlBridge.onPlayerStateChanged(false)
                        onPlaybackStateChanged?.invoke(false, false)
                        onCompletion?.invoke()
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        isBuffering = false
                        Log.e("VlcPlayerWrapper", "Encountered VLC playback error. Auto recovering...")
                        val savedPos = currentPositionMs
                        if (currentHwAccel && lastPlayedUri != null) {
                            currentHwAccel = false
                            playMediaUri(lastPlayedUri!!, hardwareAccelerated = false)
                            if (savedPos > 0) {
                                seekTo(savedPos)
                            }
                        } else {
                            isPlaying = false
                            onPlaybackStateChanged?.invoke(false, false)
                        }
                    }
                    MediaPlayer.Event.TimeChanged -> {
                        val time = mp.time
                        if (time >= 0) {
                            currentPositionMs = time
                            val dur = mp.length
                            if (dur > 0) durationMs = dur
                            onPositionUpdated?.invoke(currentPositionMs, durationMs)
                        }
                    }
                    MediaPlayer.Event.PositionChanged -> {
                        val dur = mp.length
                        if (dur > 0) durationMs = dur
                    }
                    MediaPlayer.Event.Buffering -> {
                        isBuffering = event.buffering < 100f
                        onPlaybackStateChanged?.invoke(isPlaying, isBuffering)
                    }
                }
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e("VlcPlayerWrapper", "Initialization error", e)
        }
    }

    private var currentVideoLayout: VLCVideoLayout? = null

    fun attachLayout(vlcLayout: VLCVideoLayout) {
        try {
            val mp = mediaPlayer ?: run {
                currentVideoLayout = vlcLayout
                return
            }
            if (currentVideoLayout == vlcLayout && mp.vlcVout.areViewsAttached()) {
                return
            }
            currentVideoLayout = vlcLayout
            if (!mp.vlcVout.areViewsAttached()) {
                mp.attachViews(vlcLayout, null, true, false)
            }
        } catch (e: Exception) {
            Log.e("VlcPlayerWrapper", "Failed attaching layout", e)
        }
    }

    fun detachLayout() {
        // Retain view association during transient Compose recompositions
    }

    fun refreshVideoSurface() {
        try {
            val mp = mediaPlayer ?: return
            val layout = currentVideoLayout ?: return
            if (!mp.vlcVout.areViewsAttached()) {
                mp.attachViews(layout, null, true, false)
            }
        } catch (e: Exception) {
            Log.e("VlcPlayerWrapper", "Failed refreshing video surface", e)
        }
    }

    private var lastSubSize: Float = -1f
    private var lastSubTextColor: String = ""
    private var lastSubBgColor: String = ""
    private var lastSubEncoding: String = ""

    fun updateSubtitleOptions(
        subtitleSizeSp: Float = 18f,
        subtitleTextColorHex: String = "#FFFFFF",
        subtitleBgColorHex: String = "#00000000",
        subtitleEncoding: String = "UTF-8"
    ) {
        if (subtitleSizeSp == lastSubSize &&
            subtitleTextColorHex == lastSubTextColor &&
            subtitleBgColorHex == lastSubBgColor &&
            subtitleEncoding == lastSubEncoding) {
            return
        }
        val uri = lastPlayedUri ?: return
        val currentPos = currentPositionMs
        val isCurrentlyPlaying = isPlaying

        lastSubSize = subtitleSizeSp
        lastSubTextColor = subtitleTextColorHex
        lastSubBgColor = subtitleBgColorHex
        lastSubEncoding = subtitleEncoding

        playMediaUri(
            uri = uri,
            hardwareAccelerated = currentHwAccel,
            subtitleSizeSp = subtitleSizeSp,
            subtitleTextColorHex = subtitleTextColorHex,
            subtitleBgColorHex = subtitleBgColorHex,
            subtitleEncoding = subtitleEncoding,
            initialSeekMs = currentPos
        )
        if (!isCurrentlyPlaying) {
            pause()
        }
    }

    fun playMediaUri(
        uri: Uri,
        hardwareAccelerated: Boolean = true,
        subtitleSizeSp: Float = 18f,
        subtitleTextColorHex: String = "#FFFFFF",
        subtitleBgColorHex: String = "#00000000",
        subtitleEncoding: String = "UTF-8",
        initialSeekMs: Long = -1L
    ) {
        val lib = libVLC ?: return
        val mp = mediaPlayer ?: return
        lastPlayedUri = uri
        currentHwAccel = hardwareAccelerated
        if (initialSeekMs > 0L) {
            pendingSeekMs = initialSeekMs
        }
        try {
            mp.stop()
            val media = Media(lib, uri)
            if (hardwareAccelerated) {
                media.setHWDecoderEnabled(true, true)
            } else {
                media.setHWDecoderEnabled(false, false)
            }
            media.addOption(":network-caching=500")
            media.addOption(":file-caching=300")
            media.addOption(":live-caching=300")

            // Configure VLC Subtitle Freetype module options
            val fontSizePx = (subtitleSizeSp * 2.5f).toInt().coerceIn(12, 140)
            media.addOption(":freetype-fontsize=$fontSizePx")
            media.addOption(":freetype-rel-fontsize=${(subtitleSizeSp * 1.8f).toInt().coerceIn(10, 80)}")

            val textColorDec = parseHexToDecimalColor(subtitleTextColorHex, 0xFFFFFF)
            media.addOption(":freetype-color=$textColorDec")

            if (subtitleBgColorHex.isNotEmpty() && subtitleBgColorHex != "#00000000") {
                val bgColorDec = parseHexToDecimalColor(subtitleBgColorHex, 0x000000)
                media.addOption(":freetype-background-color=$bgColorDec")
                media.addOption(":freetype-background-opacity=180")
            } else {
                media.addOption(":freetype-background-opacity=0")
            }

            if (subtitleEncoding.isNotEmpty()) {
                media.addOption(":subsdec-encoding=$subtitleEncoding")
            }

            mp.media = media
            media.release()
            mp.play()
            isPlaying = true
        } catch (e: Exception) {
            Log.e("VlcPlayerWrapper", "Error playing URI $uri", e)
            if (hardwareAccelerated) {
                currentHwAccel = false
                try {
                    val media = Media(lib, uri)
                    media.setHWDecoderEnabled(false, false)
                    mp.media = media
                    media.release()
                    mp.play()
                    isPlaying = true
                } catch (ex: Exception) {
                    Log.e("VlcPlayerWrapper", "Software decoder fallback failed", ex)
                }
            }
        }
    }

    private fun parseHexToDecimalColor(hex: String, defaultDec: Int): Int {
        return try {
            val colorInt = android.graphics.Color.parseColor(hex)
            colorInt and 0x00FFFFFF
        } catch (e: Exception) {
            defaultDec
        }
    }

    fun play() {
        mediaPlayer?.play()
        isPlaying = true
    }

    fun pause() {
        mediaPlayer?.pause()
        isPlaying = false
    }

    fun stop() {
        mediaPlayer?.stop()
        isPlaying = false
    }

    fun seekTo(timeMs: Long) {
        val target = timeMs.coerceAtLeast(0L)
        val mp = mediaPlayer
        if (mp != null && (mp.isPlaying || mp.time >= 0)) {
            mp.time = target
        } else {
            pendingSeekMs = target
        }
        currentPositionMs = target
        onPositionUpdated?.invoke(target, durationMs)
    }

    fun setSpeed(speed: Float) {
        try {
            mediaPlayer?.rate = speed
        } catch (e: Exception) {
            Log.e("VlcPlayerWrapper", "Error setting playback speed", e)
        }
    }

    fun setVolume(volPercent: Int) {
        try {
            mediaPlayer?.volume = volPercent.coerceIn(0, 100)
        } catch (e: Exception) {
            Log.e("VlcPlayerWrapper", "Error setting volume", e)
        }
    }

    fun addExternalSubtitle(subtitleUri: Uri) {
        val mp = mediaPlayer ?: return
        try {
            mp.addSlave(IMedia.Slave.Type.Subtitle, subtitleUri, true)
        } catch (e: Exception) {
            Log.e("VlcPlayerWrapper", "Error adding external subtitle slave", e)
        }
    }

    fun getAudioTracks(): Array<MediaPlayer.TrackDescription>? {
        return mediaPlayer?.audioTracks
    }

    fun getSelectedAudioTrack(): Int {
        return mediaPlayer?.audioTrack ?: -1
    }

    fun selectAudioTrack(id: Int) {
        mediaPlayer?.setAudioTrack(id)
    }

    fun getSubtitleTracks(): Array<MediaPlayer.TrackDescription>? {
        return mediaPlayer?.spuTracks
    }

    fun getSelectedSubtitleTrack(): Int {
        return mediaPlayer?.spuTrack ?: -1
    }

    fun selectSubtitleTrack(id: Int) {
        mediaPlayer?.setSpuTrack(id)
    }

    fun setAspectRatio(aspectRatio: String?) {
        try {
            mediaPlayer?.aspectRatio = aspectRatio
        } catch (e: Exception) {
            Log.e("VlcPlayerWrapper", "Error setting aspect ratio", e)
        }
    }

    fun release() {
        try {
            mediaPlayer?.detachViews()
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            libVLC?.release()
            libVLC = null
        } catch (e: Exception) {
            Log.e("VlcPlayerWrapper", "Error releasing VlcPlayerWrapper", e)
        }
    }
}
