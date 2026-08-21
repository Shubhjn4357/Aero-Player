package com.example.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

data class VlcTrackInfo(
    val id: Int,
    val name: String,
    val selected: Boolean
)

/**
 * High-performance LibVLC engine wrapper for universal codec decoding (MKV, AVI, FLV, WMV, DTS, AC3, etc.)
 */
class VlcPlayerWrapper(private val context: Context) {

    companion object {
        private const val TAG = "VlcPlayerWrapper"
    }

    private var libVLC: LibVLC? = null
    var mediaPlayer: MediaPlayer? = null
        private set

    private var currentPfd: ParcelFileDescriptor? = null

    var currentPlayingUri: String? = null
        private set

    var isPlaying: Boolean = false
        private set

    var isBuffering: Boolean = false
        private set

    var currentPositionMs: Long = 0L
        private set

    var durationMs: Long = 0L
        private set

    var targetPlaybackSpeed: Float = 1.0f
        private set

    var playbackSpeed: Float
        get() = mediaPlayer?.rate ?: targetPlaybackSpeed
        set(value) {
            targetPlaybackSpeed = value
            try {
                mediaPlayer?.rate = value
            } catch (e: Exception) {
                Log.e(TAG, "Error setting speed in LibVLC", e)
            }
        }

    var targetVolume: Float = 1.0f
        private set

    var volume: Float
        get() = (mediaPlayer?.volume ?: 100) / 100f
        set(value) {
            targetVolume = value
            try {
                val volInt = (value * 100).toInt().coerceIn(0, 200)
                mediaPlayer?.volume = volInt
            } catch (e: Exception) {
                Log.e(TAG, "Error setting volume in LibVLC", e)
            }
        }

    private var targetAudioTrackId: Int = -1
    private var targetSubtitleTrackId: Int = -2
    private var pendingInitialSeekMs: Long = 0L
    private var currentAspectRatio: String? = null
    private var currentScale: Float = 0f

    private var currentSubtitleSizeSp: Float = 18f
    private var currentSubtitleTextColor: String = "#FFFFFFFF"

    // Audio Focus & Earbuds Audio Becoming Noisy Management
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeOnFocusGain = false
    private var isNoisyReceiverRegistered = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying) {
                    resumeOnFocusGain = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    val currentVol = (targetVolume * 100).toInt()
                    mediaPlayer?.volume = (currentVol * 0.35f).toInt().coerceAtLeast(10)
                } catch (e: Exception) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    val volInt = (targetVolume * 100).toInt().coerceIn(0, 200)
                    mediaPlayer?.volume = volInt
                } catch (e: Exception) {}
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private val audioBecomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                // Earbuds / Headphones disconnected
                pause()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    val attr = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attr)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener(audioFocusChangeListener)
                        .build()
                }
                val res = am.requestAudioFocus(audioFocusRequest!!)
                hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                hasAudioFocus
            } else {
                @Suppress("DEPRECATION")
                val res = am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                hasAudioFocus
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting audio focus in VLC", e)
            true
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error abandoning audio focus in VLC", e)
        }
        hasAudioFocus = false
        resumeOnFocusGain = false
    }

    private fun registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            try {
                val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                context.registerReceiver(audioBecomingNoisyReceiver, filter)
                isNoisyReceiverRegistered = true
            } catch (e: Exception) {}
        }
    }

    private fun unregisterNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            try {
                context.unregisterReceiver(audioBecomingNoisyReceiver)
            } catch (e: Exception) {}
            isNoisyReceiverRegistered = false
        }
    }

    var onIsPlayingChanged: ((Boolean) -> Unit)? = null
    var onBufferingChanged: ((Boolean) -> Unit)? = null
    var onDurationChanged: ((Long) -> Unit)? = null
    var onPositionChanged: ((Long) -> Unit)? = null
    var onPlaybackEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onTracksUpdated: (() -> Unit)? = null

    var onPlaybackStateChanged: ((isPlaying: Boolean, isBuffering: Boolean) -> Unit)? = null
    var onPositionUpdated: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onErrorOccurred: ((errorMessage: String) -> Unit)? = null
    var onTracksChanged: (() -> Unit)? = null

    private var attachedLayout: VLCVideoLayout? = null

    init {
        initLibVlc()
    }

    fun ensureInitialized() {
        if (libVLC == null || mediaPlayer == null) {
            initLibVlc()
            attachedLayout?.let { layout ->
                attachLayout(layout)
            }
        }
    }

    private fun initLibVlc() {
        try {
            val options = ArrayList<String>().apply {
                add("--audio-time-stretch")
                add("--subsdec-encoding=UTF-8")
                add("--freetype-fontsize=18")
                add("--freetype-rel-fontsize=18")
                add("-vv")
            }
            libVLC = LibVLC(context, options)
            val player = MediaPlayer(libVLC)
            mediaPlayer = player

            player.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        isPlaying = true
                        isBuffering = false
                        try {
                            if (targetPlaybackSpeed != 1.0f) {
                                player.rate = targetPlaybackSpeed
                            }
                            if (targetAudioTrackId >= 0) {
                                player.audioTrack = targetAudioTrackId
                            }
                            if (targetSubtitleTrackId >= -1) {
                                player.spuTrack = targetSubtitleTrackId
                            }
                            if (currentAspectRatio != null) {
                                player.aspectRatio = currentAspectRatio
                            }
                            player.scale = currentScale
                            if (pendingInitialSeekMs > 0L) {
                                val seekTarget = pendingInitialSeekMs
                                pendingInitialSeekMs = 0L
                                player.time = seekTarget
                            }
                        } catch (e: Exception) {}
                        onIsPlayingChanged?.invoke(true)
                        onBufferingChanged?.invoke(false)
                        onPlaybackStateChanged?.invoke(true, false)
                    }
                    MediaPlayer.Event.Paused -> {
                        isPlaying = false
                        isBuffering = false
                        onIsPlayingChanged?.invoke(false)
                        onBufferingChanged?.invoke(false)
                        onPlaybackStateChanged?.invoke(false, false)
                    }
                    MediaPlayer.Event.Stopped -> {
                        isPlaying = false
                        isBuffering = false
                        onIsPlayingChanged?.invoke(false)
                        onBufferingChanged?.invoke(false)
                        onPlaybackStateChanged?.invoke(false, false)
                    }
                    MediaPlayer.Event.Buffering -> {
                        val bufferPercent = event.buffering
                        isBuffering = bufferPercent < 100f
                        onBufferingChanged?.invoke(isBuffering)
                        onPlaybackStateChanged?.invoke(isPlaying, isBuffering)
                    }
                    MediaPlayer.Event.TimeChanged -> {
                        currentPositionMs = event.timeChanged
                        val dur = player.length
                        if (dur > 0) {
                            durationMs = dur
                            onDurationChanged?.invoke(dur)
                        }
                        onPositionChanged?.invoke(currentPositionMs)
                        onPositionUpdated?.invoke(currentPositionMs, durationMs)
                    }
                    MediaPlayer.Event.PositionChanged -> {
                        val dur = player.length
                        if (dur > 0) {
                            durationMs = dur
                            currentPositionMs = (event.positionChanged * dur).toLong()
                            onDurationChanged?.invoke(dur)
                            onPositionChanged?.invoke(currentPositionMs)
                            onPositionUpdated?.invoke(currentPositionMs, durationMs)
                        }
                    }
                    MediaPlayer.Event.EndReached -> {
                        isPlaying = false
                        isBuffering = false
                        onIsPlayingChanged?.invoke(false)
                        onBufferingChanged?.invoke(false)
                        onPlaybackEnded?.invoke()
                        onCompletion?.invoke()
                        onPlaybackStateChanged?.invoke(false, false)
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        isPlaying = false
                        isBuffering = false
                        onIsPlayingChanged?.invoke(false)
                        onBufferingChanged?.invoke(false)
                        val msg = "VLC encountered an error during media playback"
                        onError?.invoke(msg)
                        onErrorOccurred?.invoke(msg)
                        onPlaybackStateChanged?.invoke(false, false)
                    }
                    MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                        try {
                            if (targetAudioTrackId >= 0 && player.audioTrack != targetAudioTrackId) {
                                player.audioTrack = targetAudioTrackId
                            }
                            if (targetSubtitleTrackId >= -1 && player.spuTrack != targetSubtitleTrackId) {
                                player.spuTrack = targetSubtitleTrackId
                            }
                        } catch (e: Exception) {}
                        onTracksUpdated?.invoke()
                        onTracksChanged?.invoke()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LibVLC engine", e)
        }
    }

    fun attachLayout(layout: VLCVideoLayout) {
        try {
            val player = mediaPlayer ?: return
            if (attachedLayout === layout && player.vlcVout.areViewsAttached()) {
                return
            }
            val previousLayout = attachedLayout
            attachedLayout = layout
            if (player.vlcVout.areViewsAttached()) {
                if (previousLayout !== layout) {
                    player.detachViews()
                    player.attachViews(layout, null, true, false)
                }
            } else {
                player.attachViews(layout, null, true, false)
            }
            layout.post {
                layout.requestLayout()
                layout.invalidate()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching VLCVideoLayout", e)
        }
    }

    fun detachLayout(layout: VLCVideoLayout? = null) {
        try {
            if (layout != null && attachedLayout !== layout) {
                return
            }
            val player = mediaPlayer
            if (player != null && player.vlcVout.areViewsAttached()) {
                player.detachViews()
            }
            if (layout == null || attachedLayout === layout) {
                attachedLayout = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching VLCVideoLayout", e)
        }
    }

    fun refreshVideoSurface() {
        val layout = attachedLayout ?: return
        try {
            val player = mediaPlayer ?: return
            if (!player.vlcVout.areViewsAttached()) {
                player.attachViews(layout, null, true, false)
            }
            layout.post {
                layout.requestLayout()
                layout.invalidate()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing video surface", e)
        }
    }

    fun isAlreadyPlayingUri(uri: Uri): Boolean {
        return currentPlayingUri == uri.toString() && mediaPlayer != null && (isPlaying || currentPositionMs > 0)
    }

    fun loadMedia(
        uriString: String,
        path: String? = null,
        initialSeekMs: Long = 0L,
        initialAudioTrackId: Int = -1,
        initialSubtitleTrackId: Int = -2
    ) {
        if (initialAudioTrackId >= 0) targetAudioTrackId = initialAudioTrackId
        if (initialSubtitleTrackId >= -1) targetSubtitleTrackId = initialSubtitleTrackId
        playMediaUri(
            uriString = uriString,
            path = path,
            hardwareAccelerated = true,
            initialSeekMs = initialSeekMs,
            initialAudioTrackId = initialAudioTrackId,
            initialSubtitleTrackId = initialSubtitleTrackId
        )
    }

    fun playMediaUri(
        uri: Uri,
        hardwareAccelerated: Boolean = true,
        initialSeekMs: Long = 0L,
        subtitleSizeSp: Float = currentSubtitleSizeSp,
        subtitleTextColorHex: String = currentSubtitleTextColor,
        subtitleBgColorHex: String = "#CC000000",
        subtitleOutlineColorHex: String = "#FF000000",
        subtitleShadowColorHex: String = "#80000000",
        subtitleEncoding: String = "UTF-8",
        subtitleVerticalOffset: Float = 0.08f,
        initialAudioTrackId: Int = -1,
        initialSubtitleTrackId: Int = -2
    ) {
        playMediaUri(
            uriString = uri.toString(),
            path = if (uri.scheme == "file" || uri.scheme == null) uri.path else null,
            hardwareAccelerated = hardwareAccelerated,
            initialSeekMs = initialSeekMs,
            subtitleSizeSp = subtitleSizeSp,
            subtitleTextColorHex = subtitleTextColorHex,
            subtitleEncoding = subtitleEncoding,
            initialAudioTrackId = initialAudioTrackId,
            initialSubtitleTrackId = initialSubtitleTrackId
        )
    }

    fun playMediaUri(
        uriString: String,
        path: String? = null,
        hardwareAccelerated: Boolean = true,
        initialSeekMs: Long = 0L,
        subtitleSizeSp: Float = currentSubtitleSizeSp,
        subtitleTextColorHex: String = currentSubtitleTextColor,
        subtitleBgColorHex: String = "#CC000000",
        subtitleOutlineColorHex: String = "#FF000000",
        subtitleShadowColorHex: String = "#80000000",
        subtitleEncoding: String = "UTF-8",
        subtitleVerticalOffset: Float = 0.08f,
        initialAudioTrackId: Int = -1,
        initialSubtitleTrackId: Int = -2
    ) {
        val vlc = libVLC ?: run {
            initLibVlc()
            libVLC ?: return
        }
        val player = mediaPlayer ?: run {
            initLibVlc()
            mediaPlayer ?: return
        }

        try {
            currentPlayingUri = uriString
            pendingInitialSeekMs = initialSeekMs
            if (initialAudioTrackId >= 0) targetAudioTrackId = initialAudioTrackId
            if (initialSubtitleTrackId >= -1) targetSubtitleTrackId = initialSubtitleTrackId

            try {
                player.stop()
            } catch (e: Exception) {}

            // Clean up previous ParcelFileDescriptor if any
            try {
                currentPfd?.close()
            } catch (e: Exception) {}
            currentPfd = null

            val parsedUri = try { Uri.parse(uriString) } catch (e: Exception) { Uri.EMPTY }
            val media: Media

            // Check if direct file path exists and is readable
            val directPath = path ?: (if (parsedUri.scheme == "file" || parsedUri.scheme == null) parsedUri.path else null)
            val directFile = if (!directPath.isNullOrBlank()) File(directPath) else null

            if (directFile != null && directFile.exists() && directFile.canRead()) {
                media = Media(vlc, directFile.absolutePath)
            } else if (parsedUri.scheme == "content") {
                var openedPfd: ParcelFileDescriptor? = null
                try {
                    openedPfd = context.contentResolver.openFileDescriptor(parsedUri, "r")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open ParcelFileDescriptor for content URI: $uriString", e)
                }

                if (openedPfd != null) {
                    currentPfd = openedPfd
                    media = Media(vlc, openedPfd.fileDescriptor)
                } else {
                    media = Media(vlc, parsedUri)
                }
            } else if (parsedUri.scheme == "http" || parsedUri.scheme == "https" ||
                parsedUri.scheme == "rtsp" || parsedUri.scheme == "rtmp" || parsedUri.scheme == "mms") {
                media = Media(vlc, parsedUri)
                media.addOption(":network-caching=3000")
                media.addOption(":http-reconnect=true")
            } else {
                val filePath = parsedUri.path ?: uriString
                val f = File(filePath)
                if (f.exists() && f.canRead()) {
                    media = Media(vlc, f.absolutePath)
                } else {
                    media = Media(vlc, parsedUri)
                }
            }

            // Hardware decoding with software fallback
            if (hardwareAccelerated) {
                media.setHWDecoderEnabled(true, false)
            } else {
                media.setHWDecoderEnabled(false, false)
            }

            media.addOption(":file-caching=1500")
            media.addOption(":subsdec-encoding=$subtitleEncoding")
            media.addOption(":freetype-fontsize=${subtitleSizeSp.toInt()}")
            media.addOption(":freetype-rel-fontsize=${subtitleSizeSp.toInt()}")

            val hexColor = subtitleTextColorHex.removePrefix("#").takeLast(6)
            val intColor = hexColor.toIntOrNull(16) ?: 0xFFFFFF
            media.addOption(":freetype-color=$intColor")

            if (initialSeekMs > 0L) {
                val startSec = initialSeekMs / 1000.0
                media.addOption(":start-time=$startSec")
            }

            player.media = media
            media.release()

            // Ensure views attached if layout is present
            attachedLayout?.let { layout ->
                if (!player.vlcVout.areViewsAttached()) {
                    player.attachViews(layout, null, true, false)
                }
            }

            player.play()
            if (initialSeekMs > 0L) {
                player.time = initialSeekMs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing URI in LibVLC: $uriString", e)
            val errMsg = "VLC Error: ${e.localizedMessage ?: "Failed to open media"}"
            onError?.invoke(errMsg)
            onErrorOccurred?.invoke(errMsg)
        }
    }

    fun play() {
        try {
            requestAudioFocus()
            registerNoisyReceiver()
            mediaPlayer?.play()
            isPlaying = true
            onIsPlayingChanged?.invoke(true)
            onPlaybackStateChanged?.invoke(true, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering play()", e)
        }
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
            isPlaying = false
            abandonAudioFocus()
            unregisterNoisyReceiver()
            onIsPlayingChanged?.invoke(false)
            onPlaybackStateChanged?.invoke(false, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering pause()", e)
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            isPlaying = false
            abandonAudioFocus()
            unregisterNoisyReceiver()
            onIsPlayingChanged?.invoke(false)
            onPlaybackStateChanged?.invoke(false, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering stop()", e)
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            currentPositionMs = positionMs
            mediaPlayer?.time = positionMs
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking in LibVLC", e)
        }
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
    }

    fun setVolumeLevel(volume0to100: Int) {
        try {
            mediaPlayer?.volume = volume0to100.coerceIn(0, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume in LibVLC", e)
        }
    }

    fun setAspectRatio(aspect: String?) {
        if (currentAspectRatio == aspect) return
        currentAspectRatio = aspect
        try {
            mediaPlayer?.aspectRatio = aspect
        } catch (e: Exception) {
            Log.e(TAG, "Error setting aspect ratio in LibVLC", e)
        }
    }

    fun setScale(scale: Float) {
        if (currentScale == scale) return
        currentScale = scale
        try {
            mediaPlayer?.scale = scale
        } catch (e: Exception) {
            Log.e(TAG, "Error setting scale in LibVLC", e)
        }
    }

    fun getAudioTracks(): List<VlcTrackInfo> {
        val tracks = mediaPlayer?.audioTracks ?: return emptyList()
        val currentTrack = mediaPlayer?.audioTrack ?: -1
        return tracks.map { track ->
            VlcTrackInfo(
                id = track.id,
                name = track.name ?: "Track ${track.id}",
                selected = (track.id == currentTrack)
            )
        }
    }

    fun getSelectedAudioTrack(): Int {
        return try {
            mediaPlayer?.audioTrack ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    fun selectAudioTrack(trackId: Int): Boolean {
        targetAudioTrackId = trackId
        return try {
            mediaPlayer?.setAudioTrack(trackId) ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun setAudioTrack(trackId: Int): Boolean {
        return selectAudioTrack(trackId)
    }

    fun setAudioOutput(aout: String): Boolean {
        return try {
            val mappedAout = when {
                aout.contains("OpenSL", ignoreCase = true) -> "opensles_android"
                aout.contains("AAudio", ignoreCase = true) -> "aaudio"
                else -> "audiotrack"
            }
            mediaPlayer?.setAudioOutput(mappedAout) ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun getSubtitleTracks(): List<VlcTrackInfo> {
        val tracks = mediaPlayer?.spuTracks ?: return emptyList()
        val currentTrack = mediaPlayer?.spuTrack ?: -1
        return tracks.map { track ->
            VlcTrackInfo(
                id = track.id,
                name = track.name ?: "Subtitle ${track.id}",
                selected = (track.id == currentTrack)
            )
        }
    }

    fun getSelectedSubtitleTrack(): Int {
        return try {
            mediaPlayer?.spuTrack ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    fun selectSubtitleTrack(trackId: Int): Boolean {
        targetSubtitleTrackId = trackId
        return try {
            mediaPlayer?.setSpuTrack(trackId) ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun setSubtitleDelay(delayMs: Long): Boolean {
        return try {
            mediaPlayer?.setSpuDelay(delayMs * 1000L) ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun getSubtitleDelay(): Long {
        return try {
            (mediaPlayer?.spuDelay ?: 0L) / 1000L
        } catch (e: Exception) {
            0L
        }
    }

    fun setSubtitleTrack(trackId: Int): Boolean {
        return selectSubtitleTrack(trackId)
    }

    fun getVideoTracks(): List<VlcTrackInfo> {
        val tracks = mediaPlayer?.videoTracks ?: return emptyList()
        val currentTrack = mediaPlayer?.videoTrack ?: -1
        return tracks.filter { it.id != -1 }.map { track ->
            val trackName = if (track.name.isNullOrBlank() || track.name.equals("Track ${track.id}", ignoreCase = true)) {
                "Video Track ${track.id}"
            } else {
                track.name
            }
            VlcTrackInfo(
                id = track.id,
                name = trackName,
                selected = (track.id == currentTrack)
            )
        }
    }

    fun getSelectedVideoTrack(): Int {
        return try {
            mediaPlayer?.videoTrack ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    fun selectVideoTrack(trackId: Int): Boolean {
        return try {
            mediaPlayer?.setVideoTrack(trackId) ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun setVideoTrack(trackId: Int): Boolean {
        return selectVideoTrack(trackId)
    }

    fun loadSubtitle(subtitleUri: String): Boolean {
        return addSubtitleTrack(subtitleUri, select = true)
    }

    fun addSubtitleTrack(subtitleUri: String, select: Boolean = true): Boolean {
        val player = mediaPlayer ?: return false
        return try {
            val uri = Uri.parse(subtitleUri)
            player.addSlave(IMedia.Slave.Type.Subtitle, uri, select)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding subtitle track in LibVLC", e)
            false
        }
    }

    fun addExternalSubtitle(subtitleUri: String): Boolean {
        return addSubtitleTrack(subtitleUri, select = true)
    }

    fun setSubtitleSizeSp(sizeSp: Float) {
        currentSubtitleSizeSp = sizeSp
    }

    fun setSubtitleTextColor(colorHex: String) {
        currentSubtitleTextColor = colorHex
    }

    fun updateSubtitleOptions(
        subtitleSizeSp: Float,
        subtitleTextColorHex: String,
        subtitleBgColorHex: String,
        subtitleOutlineColorHex: String,
        subtitleShadowColorHex: String,
        subtitleEncoding: String,
        subtitleVerticalOffset: Float
    ) {
        currentSubtitleSizeSp = subtitleSizeSp
        currentSubtitleTextColor = subtitleTextColorHex
    }

    fun release() {
        try {
            abandonAudioFocus()
            unregisterNoisyReceiver()
            detachLayout()
            try {
                currentPfd?.close()
            } catch (e: Exception) {}
            currentPfd = null
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            libVLC?.release()
            libVLC = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LibVLC resources", e)
        }
    }
}
