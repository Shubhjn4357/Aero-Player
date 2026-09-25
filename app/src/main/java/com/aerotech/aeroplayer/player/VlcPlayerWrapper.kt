package com.aerotech.aeroplayer.player

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
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
    private var currentAfd: android.content.res.AssetFileDescriptor? = null

    var currentPlayingUri: String? = null
        private set

    var isPlaying: Boolean = false
        private set

    var isBuffering: Boolean = false
        private set

    var isEnded: Boolean = false
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
    private var currentSubtitleBgColor: String = "#00000000"
    private var currentSubtitleOutlineColor: String = "#00000000"
    private var currentSubtitleShadowColor: String = "#00000000"
    private var currentSubtitleEncoding: String = "UTF-8"
    private var currentSubtitleVerticalOffset: Float = 0.08f
    private var currentSubtitleOpacity: Float = 1.0f
    private var currentSubtitleBgOpacity: Float = 0.8f
    private var currentSubtitleFontStyle: String = "Normal"

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
    private var attachedSurfaceView: SurfaceView? = null
    private var attachedSurfaceHolder: SurfaceHolder? = null

    private val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isInitialized = AtomicBoolean(false)

    init {
        // Pre-warm LibVLC natively on a background IO thread
        playerScope.launch {
            initLibVlcSync()
        }
    }

    suspend fun ensureInitialized(): Unit = withContext(Dispatchers.IO) {
        if (!isInitialized.get() || libVLC == null || mediaPlayer == null) {
            initLibVlcSync()
        }
    }

    fun ensureInitializedAsync(onInitialized: (() -> Unit)? = null) {
        if (isInitialized.get() && libVLC != null && mediaPlayer != null) {
            onInitialized?.invoke()
            return
        }
        playerScope.launch {
            initLibVlcSync()
            if (onInitialized != null) {
                withContext(Dispatchers.Main) {
                    onInitialized()
                }
            }
        }
    }

    private fun parseRgbColor(colorStr: String, defaultColor: Int): Int {
        return try {
            val clean = colorStr.trim().removePrefix("#")
            when (clean.length) {
                6 -> clean.toInt(16)
                8 -> clean.substring(2).toInt(16)
                else -> defaultColor
            }
        } catch (e: Exception) {
            defaultColor
        }
    }

    private var lastTrackNotifyTime: Long = 0L
    private var hasRetriedWithSoftwareDecoder: Boolean = false

    private fun buildLibVlcOptions(): ArrayList<String> {
        val options = ArrayList<String>()
        options.add("--no-drop-late-frames")
        options.add("--no-skip-frames")
        options.add("--audio-time-stretch")
        options.add("--http-reconnect")
        options.add("--network-caching=2000")
        options.add("--file-caching=2000")
        options.add("--clock-jitter=0")

        val encoding = if (currentSubtitleEncoding.isBlank()) "UTF-8" else currentSubtitleEncoding
        options.add("--subsdec-encoding=$encoding")

        return options
    }

    private fun initLibVlc() {
        initLibVlcSync()
    }

    @Synchronized
    private fun initLibVlcSync() {
        if (isInitialized.get() && libVLC != null && mediaPlayer != null) return

        var vlc: LibVLC? = null
        try {
            val options = buildLibVlcOptions()
            vlc = LibVLC(context, options)
        } catch (e: Throwable) {
            Log.w(TAG, "LibVLC failed with configured options, falling back to standard safe options", e)
            try {
                val fallbackOptions = arrayListOf(
                    "--no-drop-late-frames",
                    "--no-skip-frames",
                    "--audio-time-stretch",
                    "--http-reconnect"
                )
                vlc = LibVLC(context, fallbackOptions)
            } catch (e2: Throwable) {
                Log.w(TAG, "LibVLC failed with fallback options, falling back to default constructor", e2)
                try {
                    vlc = LibVLC(context)
                } catch (e3: Throwable) {
                    Log.e(TAG, "LibVLC failed to instantiate completely", e3)
                }
            }
        }

        if (vlc != null) {
            try {
                val player = MediaPlayer(vlc)
                setupMediaPlayerListeners(player)
                libVLC = vlc
                mediaPlayer = player
                isInitialized.set(true)
                Log.i(TAG, "LibVLC engine initialized successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to create MediaPlayer from LibVLC instance", e)
                try {
                    vlc.release()
                } catch (relEx: Exception) {
                    Log.w(TAG, "Error releasing unused LibVLC instance", relEx)
                }
            }
        }
    }

    private fun setupMediaPlayerListeners(player: MediaPlayer) {
        try {
            player.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        android.util.Log.d("VLC_LOG", "LibVLC successfully started playing tracks")
                        isPlaying = true
                        isBuffering = false
                        isEnded = false
                        val dur = player.length
                        if (dur > 0L) {
                            durationMs = dur
                            onDurationChanged?.invoke(dur)
                            onPositionUpdated?.invoke(currentPositionMs, dur)
                        }
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
                            player.updateVideoSurfaces()
                        } catch (e: Exception) {}
                        onIsPlayingChanged?.invoke(true)
                        onBufferingChanged?.invoke(false)
                        onPlaybackStateChanged?.invoke(true, false)
                        try {
                            val chapters = getMediaChapters()
                            if (chapters.isNotEmpty()) {
                                onChaptersDiscovered?.invoke(chapters)
                            }
                        } catch (e: Exception) {}
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
                        android.util.Log.d("VLC_LOG", "Buffering: $bufferPercent%")
                        isBuffering = bufferPercent < 100f
                        onBufferingChanged?.invoke(isBuffering)
                        onPlaybackStateChanged?.invoke(isPlaying, isBuffering)
                    }
                    MediaPlayer.Event.TimeChanged -> {
                        currentPositionMs = event.timeChanged
                        val dur = player.length
                        if (dur > 0L && (durationMs <= 0L || durationMs != dur)) {
                            durationMs = dur
                            onDurationChanged?.invoke(dur)
                        }
                        onPositionChanged?.invoke(currentPositionMs)
                        onPositionUpdated?.invoke(currentPositionMs, durationMs)
                    }
                    MediaPlayer.Event.PositionChanged -> {
                        val dur = player.length
                        if (dur > 0L) {
                            durationMs = dur
                            currentPositionMs = (event.positionChanged * dur).toLong()
                            onDurationChanged?.invoke(dur)
                            onPositionChanged?.invoke(currentPositionMs)
                            onPositionUpdated?.invoke(currentPositionMs, durationMs)
                        }
                    }
                    MediaPlayer.Event.LengthChanged -> {
                        val len = event.lengthChanged
                        if (len > 0L) {
                            durationMs = len
                            onDurationChanged?.invoke(len)
                            onPositionUpdated?.invoke(currentPositionMs, len)
                        }
                    }
                    MediaPlayer.Event.Vout -> {
                        try {
                            player.updateVideoSurfaces()
                        } catch (e: Exception) {}
                    }
                    MediaPlayer.Event.EndReached -> {
                        isPlaying = false
                        isBuffering = false
                        isEnded = true
                        onIsPlayingChanged?.invoke(false)
                        onBufferingChanged?.invoke(false)
                        onPlaybackEnded?.invoke()
                        onCompletion?.invoke()
                        onPlaybackStateChanged?.invoke(false, false)
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        android.util.Log.e("VLC_ERROR", "LibVLC encountered a critical playback error for uri: $currentPlayingUri")
                        isPlaying = false
                        isBuffering = false
                        onIsPlayingChanged?.invoke(false)
                        onBufferingChanged?.invoke(false)
                        val currentUri = currentPlayingUri
                        if (!hasRetriedWithSoftwareDecoder && currentUri != null) {
                            hasRetriedWithSoftwareDecoder = true
                            Log.w(TAG, "LibVLC encountered an error with hardware acceleration, falling back to software decoding")
                            playerScope.launch(Dispatchers.IO) {
                                playMediaUriInternal(
                                    uriString = currentUri,
                                    path = null,
                                    hardwareAccelerated = false,
                                    initialSeekMs = currentPositionMs.coerceAtLeast(pendingInitialSeekMs),
                                    subtitleSizeSp = currentSubtitleSizeSp,
                                    subtitleTextColorHex = currentSubtitleTextColor,
                                    subtitleBgColorHex = currentSubtitleBgColor,
                                    subtitleOutlineColorHex = currentSubtitleOutlineColor,
                                    subtitleShadowColorHex = currentSubtitleShadowColor,
                                    subtitleEncoding = currentSubtitleEncoding,
                                    subtitleVerticalOffset = currentSubtitleVerticalOffset,
                                    subtitleOpacity = currentSubtitleOpacity,
                                    subtitleBgOpacity = currentSubtitleBgOpacity,
                                    subtitleFontStyle = currentSubtitleFontStyle,
                                    initialAudioTrackId = targetAudioTrackId,
                                    initialSubtitleTrackId = targetSubtitleTrackId
                                )
                            }
                            return@setEventListener
                        }
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
                        val now = System.currentTimeMillis()
                        if (now - lastTrackNotifyTime > 300L) {
                            lastTrackNotifyTime = now
                            onTracksUpdated?.invoke()
                            onTracksChanged?.invoke()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup LibVLC event listener", e)
        }
    }

    fun attachSurface(surfaceView: SurfaceView, holder: SurfaceHolder) {
        try {
            attachedSurfaceView = surfaceView
            attachedSurfaceHolder = holder
            val player = mediaPlayer ?: run {
                initLibVlcSync()
                mediaPlayer
            } ?: return

            val vlcVout = player.vlcVout
            if (vlcVout.areViewsAttached()) {
                vlcVout.detachViews()
            }
            vlcVout.setVideoView(surfaceView)
            vlcVout.attachViews()
            if (surfaceView.width > 0 && surfaceView.height > 0) {
                vlcVout.setWindowSize(surfaceView.width, surfaceView.height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching SurfaceView to LibVLC", e)
        }
    }

    fun onSurfaceSizeChanged(width: Int, height: Int) {
        try {
            val player = mediaPlayer ?: return
            if (width > 0 && height > 0) {
                player.vlcVout.setWindowSize(width, height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating window size on LibVLC vlcVout", e)
        }
    }

    fun detachSurface(surfaceView: SurfaceView? = null) {
        try {
            if (surfaceView != null && attachedSurfaceView !== surfaceView) {
                return
            }
            val player = mediaPlayer
            if (player != null && player.vlcVout.areViewsAttached()) {
                player.vlcVout.detachViews()
            }
            attachedSurfaceView = null
            attachedSurfaceHolder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching SurfaceView from LibVLC", e)
        }
    }

    fun attachLayout(layout: VLCVideoLayout) {
        try {
            val previousLayout = attachedLayout
            attachedLayout = layout
            val player = mediaPlayer ?: run {
                initLibVlcSync()
                mediaPlayer
            } ?: return

            if (previousLayout === layout && player.vlcVout.areViewsAttached()) {
                return
            }
            if (player.vlcVout.areViewsAttached()) {
                if (previousLayout !== layout) {
                    player.detachViews()
                    player.attachViews(layout, null, true, true)
                }
            } else {
                player.attachViews(layout, null, true, true)
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
        val surface = attachedSurfaceView
        val layout = attachedLayout
        val player = mediaPlayer ?: return
        playerScope.launch(Dispatchers.Main) {
            try {
                if (surface != null) {
                    val vlcVout = player.vlcVout
                    if (!vlcVout.areViewsAttached()) {
                        vlcVout.setVideoView(surface)
                        vlcVout.attachViews()
                        if (surface.width > 0 && surface.height > 0) {
                            vlcVout.setWindowSize(surface.width, surface.height)
                        }
                    }
                } else if (layout != null) {
                    if (!player.vlcVout.areViewsAttached()) {
                        player.attachViews(layout, null, true, false)
                    }
                    layout.requestLayout()
                    layout.invalidate()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing video surface", e)
            }
        }
    }

    fun isAlreadyPlayingUri(uri: Uri): Boolean {
        return currentPlayingUri == uri.toString() && mediaPlayer != null && (isPlaying || currentPositionMs > 0)
    }

    suspend fun loadMedia(
        uriString: String,
        path: String? = null,
        initialSeekMs: Long = 0L,
        initialAudioTrackId: Int = -1,
        initialSubtitleTrackId: Int = -2,
        subtitleSizeSp: Float = currentSubtitleSizeSp,
        subtitleTextColorHex: String = currentSubtitleTextColor,
        subtitleBgColorHex: String = currentSubtitleBgColor,
        subtitleOutlineColorHex: String = currentSubtitleOutlineColor,
        subtitleShadowColorHex: String = currentSubtitleShadowColor,
        subtitleEncoding: String = currentSubtitleEncoding,
        subtitleVerticalOffset: Float = currentSubtitleVerticalOffset,
        subtitleOpacity: Float = currentSubtitleOpacity,
        subtitleBgOpacity: Float = currentSubtitleBgOpacity,
        subtitleFontStyle: String = currentSubtitleFontStyle
    ) = withContext(Dispatchers.IO) {
        hasRetriedWithSoftwareDecoder = false
        ensureInitialized()
        if (initialAudioTrackId >= 0) targetAudioTrackId = initialAudioTrackId
        if (initialSubtitleTrackId >= -1) targetSubtitleTrackId = initialSubtitleTrackId
        playMediaUriInternal(
            uriString = uriString,
            path = path,
            hardwareAccelerated = true,
            initialSeekMs = initialSeekMs,
            subtitleSizeSp = subtitleSizeSp,
            subtitleTextColorHex = subtitleTextColorHex,
            subtitleBgColorHex = subtitleBgColorHex,
            subtitleOutlineColorHex = subtitleOutlineColorHex,
            subtitleShadowColorHex = subtitleShadowColorHex,
            subtitleEncoding = subtitleEncoding,
            subtitleVerticalOffset = subtitleVerticalOffset,
            subtitleOpacity = subtitleOpacity,
            subtitleBgOpacity = subtitleBgOpacity,
            subtitleFontStyle = subtitleFontStyle,
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
        subtitleBgColorHex: String = currentSubtitleBgColor,
        subtitleOutlineColorHex: String = currentSubtitleOutlineColor,
        subtitleShadowColorHex: String = currentSubtitleShadowColor,
        subtitleEncoding: String = currentSubtitleEncoding,
        subtitleVerticalOffset: Float = currentSubtitleVerticalOffset,
        subtitleOpacity: Float = currentSubtitleOpacity,
        subtitleBgOpacity: Float = currentSubtitleBgOpacity,
        subtitleFontStyle: String = currentSubtitleFontStyle,
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
            subtitleBgColorHex = subtitleBgColorHex,
            subtitleOutlineColorHex = subtitleOutlineColorHex,
            subtitleShadowColorHex = subtitleShadowColorHex,
            subtitleEncoding = subtitleEncoding,
            subtitleVerticalOffset = subtitleVerticalOffset,
            subtitleOpacity = subtitleOpacity,
            subtitleBgOpacity = subtitleBgOpacity,
            subtitleFontStyle = subtitleFontStyle,
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
        subtitleBgColorHex: String = currentSubtitleBgColor,
        subtitleOutlineColorHex: String = currentSubtitleOutlineColor,
        subtitleShadowColorHex: String = currentSubtitleShadowColor,
        subtitleEncoding: String = currentSubtitleEncoding,
        subtitleVerticalOffset: Float = currentSubtitleVerticalOffset,
        subtitleOpacity: Float = currentSubtitleOpacity,
        subtitleBgOpacity: Float = currentSubtitleBgOpacity,
        subtitleFontStyle: String = currentSubtitleFontStyle,
        initialAudioTrackId: Int = -1,
        initialSubtitleTrackId: Int = -2
    ) {
        playerScope.launch(Dispatchers.IO) {
            ensureInitialized()
            playMediaUriInternal(
                uriString = uriString,
                path = path,
                hardwareAccelerated = hardwareAccelerated,
                initialSeekMs = initialSeekMs,
                subtitleSizeSp = subtitleSizeSp,
                subtitleTextColorHex = subtitleTextColorHex,
                subtitleBgColorHex = subtitleBgColorHex,
                subtitleOutlineColorHex = subtitleOutlineColorHex,
                subtitleShadowColorHex = subtitleShadowColorHex,
                subtitleEncoding = subtitleEncoding,
                subtitleVerticalOffset = subtitleVerticalOffset,
                subtitleOpacity = subtitleOpacity,
                subtitleBgOpacity = subtitleBgOpacity,
                subtitleFontStyle = subtitleFontStyle,
                initialAudioTrackId = initialAudioTrackId,
                initialSubtitleTrackId = initialSubtitleTrackId
            )
        }
    }

    private suspend fun playMediaUriInternal(
        uriString: String,
        path: String? = null,
        hardwareAccelerated: Boolean = true,
        initialSeekMs: Long = 0L,
        subtitleSizeSp: Float = currentSubtitleSizeSp,
        subtitleTextColorHex: String = currentSubtitleTextColor,
        subtitleBgColorHex: String = currentSubtitleBgColor,
        subtitleOutlineColorHex: String = currentSubtitleOutlineColor,
        subtitleShadowColorHex: String = currentSubtitleShadowColor,
        subtitleEncoding: String = currentSubtitleEncoding,
        subtitleVerticalOffset: Float = currentSubtitleVerticalOffset,
        subtitleOpacity: Float = currentSubtitleOpacity,
        subtitleBgOpacity: Float = currentSubtitleBgOpacity,
        subtitleFontStyle: String = currentSubtitleFontStyle,
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

            currentSubtitleSizeSp = subtitleSizeSp
            currentSubtitleTextColor = subtitleTextColorHex
            currentSubtitleBgColor = subtitleBgColorHex
            currentSubtitleOutlineColor = subtitleOutlineColorHex
            currentSubtitleShadowColor = subtitleShadowColorHex
            currentSubtitleEncoding = subtitleEncoding
            currentSubtitleVerticalOffset = subtitleVerticalOffset
            currentSubtitleOpacity = subtitleOpacity
            currentSubtitleBgOpacity = subtitleBgOpacity
            currentSubtitleFontStyle = subtitleFontStyle

            try {
                player.stop()
            } catch (e: Exception) {}

            // Clean up previous ParcelFileDescriptor or AssetFileDescriptor if any
            try {
                currentPfd?.close()
            } catch (e: Exception) {}
            currentPfd = null
            try {
                currentAfd?.close()
            } catch (e: Exception) {}
            currentAfd = null

            // Clean up previous file descriptors safely
            try {
                currentPfd?.close()
            } catch (e: Exception) {}
            currentPfd = null
            try {
                currentAfd?.close()
            } catch (e: Exception) {}
            currentAfd = null

            val parsedUri = try { Uri.parse(uriString) } catch (e: Exception) { Uri.EMPTY }
            val isRemote = uriString.startsWith("http://") || uriString.startsWith("https://") ||
                uriString.startsWith("rtsp://") || uriString.startsWith("rtmp://") || uriString.startsWith("mms://")
            val media: Media

            // Check if direct file path exists and is readable
            val directPath = path ?: (if (parsedUri.scheme == "file" || parsedUri.scheme == null) parsedUri.path else null)
            val directFile = if (!directPath.isNullOrBlank()) File(directPath) else null

            if (isRemote) {
                media = Media(vlc, Uri.parse(uriString))
            } else if (directFile != null && directFile.exists() && directFile.canRead()) {
                media = Media(vlc, directFile.absolutePath)
            } else if (parsedUri.scheme == "content") {
                // Priority 1: Resolve filesystem path via ContentResolver / MediaStore query
                var createdMedia: Media? = null
                var resolvedFilePath: String? = null
                try {
                    val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
                    context.contentResolver.query(parsedUri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dataIdx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                            if (dataIdx >= 0) {
                                val d = cursor.getString(dataIdx)
                                if (!d.isNullOrBlank() && File(d).let { it.exists() && it.canRead() }) {
                                    resolvedFilePath = d
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "ContentResolver DATA query skipped: ${e.message}")
                }

                if (!resolvedFilePath.isNullOrBlank()) {
                    try {
                        createdMedia = Media(vlc, resolvedFilePath)
                        Log.i(TAG, "VLC attached content URI via direct resolved path: $resolvedFilePath")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed loading via resolvedFilePath: $resolvedFilePath", e)
                    }
                }

                // Priority 2: Open ParcelFileDescriptor and use procfs fd link or native file descriptor
                if (createdMedia == null) {
                    try {
                        val pfd = context.contentResolver.openFileDescriptor(parsedUri, "r")
                        if (pfd != null) {
                            currentPfd = pfd
                            val fdPath = "/proc/self/fd/${pfd.fd}"
                            val procFile = File(fdPath)
                            if (procFile.exists()) {
                                try {
                                    createdMedia = Media(vlc, fdPath)
                                    Log.i(TAG, "VLC attached content URI via procfs fd link: $fdPath")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed creating Media from procfs fd link", e)
                                }
                            }
                            if (createdMedia == null) {
                                createdMedia = Media(vlc, pfd.fileDescriptor)
                                Log.i(TAG, "VLC attached content URI via ParcelFileDescriptor")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed creating Media from ParcelFileDescriptor for $parsedUri", e)
                    }
                }

                // Priority 3: Try AssetFileDescriptor only if declared length is known (> 0)
                if (createdMedia == null) {
                    try {
                        val afd = context.contentResolver.openAssetFileDescriptor(parsedUri, "r")
                        if (afd != null) {
                            currentAfd = afd
                            if (afd.declaredLength > 0) {
                                createdMedia = Media(vlc, afd)
                            } else {
                                createdMedia = Media(vlc, afd.fileDescriptor)
                            }
                            Log.i(TAG, "VLC attached content URI via AssetFileDescriptor")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed creating Media from AssetFileDescriptor for $parsedUri", e)
                    }
                }

                media = createdMedia ?: Media(vlc, parsedUri)
            } else {
                val filePath = parsedUri.path ?: uriString
                val f = File(filePath)
                if (f.exists() && f.canRead()) {
                    media = Media(vlc, f.absolutePath)
                } else {
                    var fallbackMedia: Media? = null
                    if (parsedUri.scheme == "content") {
                        try {
                            val afd = context.contentResolver.openAssetFileDescriptor(parsedUri, "r")
                            if (afd != null) {
                                currentAfd = afd
                                fallbackMedia = Media(vlc, afd)
                            }
                        } catch (ex: Exception) {}
                    }
                    media = fallbackMedia ?: Media(vlc, parsedUri)
                }
            }

            // Hardware decoding with software fallback
            if (hardwareAccelerated) {
                media.setHWDecoderEnabled(true, false)
            } else {
                media.setHWDecoderEnabled(false, false)
            }

            val sizeInt = subtitleSizeSp.toInt().coerceIn(10, 60)
            val colorInt = parseRgbColor(subtitleTextColorHex, 0xFFFFFF)
            val opacityInt = (subtitleOpacity * 255).toInt().coerceIn(0, 255)

            media.addOption(":file-caching=3000")
            media.addOption(":network-caching=3000")
            media.addOption(":clock-jitter=0")
            media.addOption(":http-reconnect=true")
            media.addOption(":video-fast-seek")
            media.addOption(":subsdec-encoding=${if (subtitleEncoding.isBlank()) "UTF-8" else subtitleEncoding}")
            media.addOption(":freetype-fontsize=$sizeInt")
            media.addOption(":freetype-rel-fontsize=$sizeInt")
            media.addOption(":freetype-color=$colorInt")
            media.addOption(":freetype-opacity=$opacityInt")

            if (subtitleBgColorHex.isNotBlank() && subtitleBgColorHex != "#00000000" && subtitleBgOpacity > 0.01f) {
                val bgColorInt = parseRgbColor(subtitleBgColorHex, 0x000000)
                val bgOpacityInt = (subtitleBgOpacity * 255).toInt().coerceIn(0, 255)
                media.addOption(":freetype-background-color=$bgColorInt")
                media.addOption(":freetype-background-opacity=$bgOpacityInt")
            } else {
                media.addOption(":freetype-background-opacity=0")
            }

            if (subtitleOutlineColorHex.isNotBlank() && subtitleOutlineColorHex != "#00000000") {
                val outlineColorInt = parseRgbColor(subtitleOutlineColorHex, 0x000000)
                media.addOption(":freetype-outline-color=$outlineColorInt")
                media.addOption(":freetype-outline-thickness=2")
            } else {
                media.addOption(":freetype-outline-thickness=0")
            }

            if (subtitleShadowColorHex.isNotBlank() && subtitleShadowColorHex != "#00000000") {
                val shadowColorInt = parseRgbColor(subtitleShadowColorHex, 0x000000)
                media.addOption(":freetype-shadow-color=$shadowColorInt")
                media.addOption(":freetype-shadow-angle=45")
                media.addOption(":freetype-shadow-distance=2")
            }

            if (subtitleFontStyle == "Bold" || subtitleFontStyle == "Bold Italic") {
                media.addOption(":freetype-bold")
            }

            val marginPx = (subtitleVerticalOffset * 200).toInt().coerceIn(0, 400)
            media.addOption(":sub-margin=$marginPx")

            if (initialSeekMs > 0L) {
                val startSec = initialSeekMs / 1000.0
                media.addOption(":start-time=$startSec")
            }

            player.media = media
            try {
                if (isRemote) {
                    media.parse(org.videolan.libvlc.interfaces.IMedia.Parse.ParseNetwork)
                } else {
                    media.parse(org.videolan.libvlc.interfaces.IMedia.Parse.ParseLocal)
                }
            } catch (e: Exception) {
                try {
                    media.parseAsync(if (isRemote) org.videolan.libvlc.interfaces.IMedia.Parse.ParseNetwork else org.videolan.libvlc.interfaces.IMedia.Parse.ParseLocal)
                } catch (ex: Exception) {}
            }
            media.release()

            // Ensure views attached on Main thread if SurfaceView or layout already available
            val surface = attachedSurfaceView
            val layout = attachedLayout
            if (surface != null || layout != null) {
                withContext(Dispatchers.Main) {
                    try {
                        if (surface != null) {
                            val vlcVout = player.vlcVout
                            if (!vlcVout.areViewsAttached()) {
                                vlcVout.setVideoView(surface)
                                vlcVout.attachViews()
                                if (surface.width > 0 && surface.height > 0) {
                                    vlcVout.setWindowSize(surface.width, surface.height)
                                }
                            }
                        } else if (layout != null) {
                            if (!player.vlcVout.areViewsAttached()) {
                                player.attachViews(layout, null, true, true)
                            }
                            player.updateVideoSurfaces()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed attaching views on Main thread", e)
                    }
                }
            }

            requestAudioFocus()
            registerNoisyReceiver()
            player.play()
            isPlaying = true
            onIsPlayingChanged?.invoke(true)
            onPlaybackStateChanged?.invoke(true, false)
            if (initialSeekMs > 0L) {
                pendingInitialSeekMs = initialSeekMs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing URI in LibVLC: $uriString", e)
            val errMsg = "VLC Error: ${e.localizedMessage ?: "Failed to open media"}"
            onError?.invoke(errMsg)
            onErrorOccurred?.invoke(errMsg)
        }
    }

    fun isEndedState(): Boolean = isEnded || (durationMs > 0L && currentPositionMs >= durationMs - 500L)

    fun play() {
        try {
            requestAudioFocus()
            registerNoisyReceiver()
            if (isEndedState()) {
                seekTo(0)
                isEnded = false
            }
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
            // Retain audio focus and noisy receiver during pause so headset buttons and bluetooth media keys can resume
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
            val res: Boolean
            if (uri.scheme == "file") {
                res = player.addSlave(IMedia.Slave.Type.Subtitle, uri.path ?: subtitleUri, select)
            } else if (uri.scheme == "content") {
                // If content URI, copy to cached temp file so native LibVLC can demux and render it
                var tempSubFile: File? = null
                try {
                    val input = context.contentResolver.openInputStream(uri)
                    if (input != null) {
                        val cacheDir = File(context.cacheDir, "subtitles").apply { mkdirs() }
                        val fileExt = uri.path?.substringAfterLast('.', "srt") ?: "srt"
                        val safeExt = if (fileExt.length in 2..5) fileExt else "srt"
                        val temp = File(cacheDir, "vlc_sub_${System.currentTimeMillis()}.$safeExt")
                        temp.outputStream().use { out -> input.copyTo(out) }
                        tempSubFile = temp
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed caching content URI subtitle for LibVLC", ex)
                }

                if (tempSubFile != null && tempSubFile.exists()) {
                    res = player.addSlave(IMedia.Slave.Type.Subtitle, tempSubFile.absolutePath, select)
                } else {
                    res = player.addSlave(IMedia.Slave.Type.Subtitle, uri, select)
                }
            } else {
                res = player.addSlave(IMedia.Slave.Type.Subtitle, uri, select)
            }
            if (select) {
                targetSubtitleTrackId = -2
            }
            res
        } catch (e: Exception) {
            Log.e(TAG, "Error adding subtitle track in LibVLC", e)
            false
        }
    }

    fun addExternalSubtitle(subtitleUri: String): Boolean {
        return addSubtitleTrack(subtitleUri, select = true)
    }

    fun disableInternalSpu() {
        try {
            targetSubtitleTrackId = -1
            mediaPlayer?.spuTrack = -1
        } catch (e: Exception) {}
    }

    fun setSubtitleSizeSp(sizeSp: Float) {
        currentSubtitleSizeSp = sizeSp
    }

    fun setSubtitleTextColor(colorHex: String) {
        currentSubtitleTextColor = colorHex
    }

    fun setSubtitleEncoding(encoding: String) {
        currentSubtitleEncoding = encoding
    }

    fun applySubtitlePreferences(
        subtitleSizeSp: Float,
        subtitleTextColorHex: String,
        subtitleBgColorHex: String = currentSubtitleBgColor,
        subtitleOutlineColorHex: String = currentSubtitleOutlineColor,
        subtitleShadowColorHex: String = currentSubtitleShadowColor,
        subtitleEncoding: String = currentSubtitleEncoding,
        subtitleVerticalOffset: Float = currentSubtitleVerticalOffset,
        subtitleOpacity: Float = currentSubtitleOpacity,
        subtitleBgOpacity: Float = currentSubtitleBgOpacity,
        subtitleFontStyle: String = currentSubtitleFontStyle
    ) {
        currentSubtitleSizeSp = subtitleSizeSp
        currentSubtitleTextColor = subtitleTextColorHex
        currentSubtitleBgColor = subtitleBgColorHex
        currentSubtitleOutlineColor = subtitleOutlineColorHex
        currentSubtitleShadowColor = subtitleShadowColorHex
        currentSubtitleEncoding = subtitleEncoding
        currentSubtitleVerticalOffset = subtitleVerticalOffset
        currentSubtitleOpacity = subtitleOpacity
        currentSubtitleBgOpacity = subtitleBgOpacity
        currentSubtitleFontStyle = subtitleFontStyle
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
        applySubtitlePreferences(
            subtitleSizeSp = subtitleSizeSp,
            subtitleTextColorHex = subtitleTextColorHex,
            subtitleBgColorHex = subtitleBgColorHex,
            subtitleOutlineColorHex = subtitleOutlineColorHex,
            subtitleShadowColorHex = subtitleShadowColorHex,
            subtitleEncoding = subtitleEncoding,
            subtitleVerticalOffset = subtitleVerticalOffset
        )
    }

    /**
     * Data holder representing a native media chapter parsed by LibVLC from MKV/MP4 files.
     */
    data class VlcChapter(
        val name: String,
        val timeOffsetMs: Long,
        val durationMs: Long
    )

    /**
     * Retrieves the native chapters from the current media if supported/embedded in the file (e.g. MKV/MP4).
     */
    fun getMediaChapters(): List<VlcChapter> {
        val player = mediaPlayer ?: return emptyList()
        return try {
            val titleIndex = player.title.coerceAtLeast(0)
            val chapters = player.getChapters(titleIndex)
            if (chapters != null && chapters.isNotEmpty()) {
                chapters.mapIndexed { idx, ch ->
                    val chapterName = if (ch.name.isNullOrBlank()) "Chapter ${idx + 1}" else ch.name
                    VlcChapter(
                        name = chapterName,
                        timeOffsetMs = ch.timeOffset,
                        durationMs = ch.duration
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get native chapters from LibVLC", e)
            emptyList()
        }
    }

    var onChaptersDiscovered: ((List<VlcChapter>) -> Unit)? = null


    fun release() {
        try {
            playerScope.cancel()
            abandonAudioFocus()
            unregisterNoisyReceiver()
            detachLayout()
            detachSurface()
            try {
                currentPfd?.close()
            } catch (e: Exception) {}
            currentPfd = null
            try {
                currentAfd?.close()
            } catch (e: Exception) {}
            currentAfd = null
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
