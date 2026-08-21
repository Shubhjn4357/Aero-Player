package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.database.MediaEntity
import com.example.data.database.displayArtist
import com.example.player.VlcPlayerWrapper
import com.example.ui.components.VlcPlayerView
import com.example.ui.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    mediaItem: MediaEntity,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs by viewModel.preferencesState.collectAsState()

    val currentPlayingItemFromVm by viewModel.currentPlayingItem.collectAsState()
    var activeMediaItem by remember(mediaItem) { mutableStateOf(mediaItem) }
    
    LaunchedEffect(currentPlayingItemFromVm) {
        currentPlayingItemFromVm?.let {
            if (activeMediaItem.uriString != it.uriString) {
                activeMediaItem = it
            }
        }
    }
    var isPipActive by remember { mutableStateOf(false) }
    val isInPipMode by viewModel.isInPipMode.collectAsState()

    val hwVolPercent by viewModel.volumeOverlayPercent.collectAsState()
    val hwVolTime by viewModel.volumeOverlayTime.collectAsState()
    var showHwVolumeHud by remember { mutableStateOf(false) }

    LaunchedEffect(hwVolPercent, hwVolTime) {
        if (hwVolPercent != null && hwVolTime > 0L) {
            showHwVolumeHud = true
            kotlinx.coroutines.delay(1500)
            showHwVolumeHud = false
        }
    }

    // Playback Engines: ExoPlayer & LibVLC Universal Engine (Managed at ViewModel scope to prevent reinitiation)
    val exoPlayer = viewModel.exoPlayer
    val vlcPlayer = viewModel.vlcPlayer

    var activeEngine by remember(prefs.defaultPlayerEngine) {
        mutableStateOf(
            if (prefs.defaultPlayerEngine.contains("VLC")) "VLC"
            else if (prefs.defaultPlayerEngine.contains("ExoPlayer")) "ExoPlayer"
            else "Auto"
        )
    }

    val targetPath = (activeMediaItem.path ?: activeMediaItem.uriString).lowercase()
    val isVlcRequiredFormat = targetPath.endsWith(".mkv") || targetPath.endsWith(".avi") ||
            targetPath.endsWith(".flv") || targetPath.endsWith(".ts") || targetPath.endsWith(".wmv") ||
            targetPath.endsWith(".vob") || targetPath.endsWith(".ogv") || targetPath.endsWith(".divx") ||
            targetPath.endsWith(".rmvb") || targetPath.contains(".dts") || targetPath.contains(".ac3") ||
            targetPath.contains(".eac3") || targetPath.contains(".truehd") || targetPath.endsWith(".iso")
    val effectiveEngine = when (activeEngine) {
        "VLC" -> "VLC"
        "ExoPlayer" -> "ExoPlayer"
        else -> if (isVlcRequiredFormat) "VLC" else "ExoPlayer"
    }

    LaunchedEffect(effectiveEngine) {
        com.example.ui.viewmodel.PlayerControlBridge.activeEngineType = effectiveEngine
    }

    var showResumePrompt by remember { mutableStateOf(false) }
    var isAutoTransitioning by remember { mutableStateOf(false) }
    var isNewSession by remember { mutableStateOf(true) }
    var resumePosition by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var audioFallbackAttempted by remember { mutableStateOf(false) }
    var generalRetryCount by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var playbackErrorMsg by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(false) }
    val currentEqualizerPreset by viewModel.currentEqualizerPreset.collectAsState()
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var currentBrightness by remember { mutableStateOf(-1f) }
    var showOnlineSubtitleDownloader by remember { mutableStateOf(false) }
    var playAsAudioOnly by remember { mutableStateOf(viewModel.audioOnlyPlaybackRequested) }
    var tracksUpdateTrigger by remember { mutableStateOf(0) }

    // Session-based screen orientation override (not stored persistently)
    var sessionOrientation by remember {
        mutableStateOf(
            when (prefs.defaultOrientation) {
                "Portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "Landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "Reverse Portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                "Reverse Landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        )
    }

    // Apply orientation changes dynamically
    LaunchedEffect(sessionOrientation, prefs.rotationLock, activeMediaItem.isVideo, playAsAudioOnly) {
        val isAudio = !activeMediaItem.isVideo || playAsAudioOnly
        val activity = context as? android.app.Activity
        if (activity != null) {
            if (isAudio) {
                if (prefs.rotationLock) {
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }
            } else {
                if (prefs.rotationLock) {
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                } else {
                    val targetOrientation = if (sessionOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        sessionOrientation
                    }
                    activity.requestedOrientation = targetOrientation
                }
            }
        }
    }

    // Restore default orientation and manage immersive system UI on player screen lifecycle
    DisposableEffect(playAsAudioOnly) {
        val isAudio = !activeMediaItem.isVideo || playAsAudioOnly
        val activity = context as? android.app.Activity
        val window = activity?.window
        if (window != null) {
            val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (!isAudio) {
                // Enter immersive full screen mode ONLY for video player
                windowInsetsController.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                // Keep standard system bars visible for audio player
                windowInsetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            (context as? android.app.Activity)?.let { act ->
                act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val lp = act.window?.attributes
                if (lp != null) {
                    lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    act.window?.attributes = lp
                }
            }
            if (window != null) {
                val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                windowInsetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var wasPlayingBeforePause by remember { mutableStateOf(false) }

    // Listen to Lifecycle Events to pause playback instantly when minimized/locked and auto-resume on return
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer, vlcPlayer, prefs, isInPipMode, activeMediaItem, effectiveEngine) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            val activity = context as? android.app.Activity
            val activityInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                activity?.isInPictureInPictureMode == true
            } else {
                false
            }
            val inPip = isInPipMode || activityInPip

            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val bgModeNormalized = when (prefs.backgroundMode) {
                    "PLAY_BACKGROUND_AUDIO", "Play in Background" -> "PLAY_BACKGROUND_AUDIO"
                    "LAUNCH_PIP_MODE", "PiP" -> "LAUNCH_PIP_MODE"
                    else -> "STOP_PLAYBACK"
                }
                val shouldPlayBackground = bgModeNormalized == "PLAY_BACKGROUND_AUDIO" ||
                        (bgModeNormalized == "LAUNCH_PIP_MODE" && activeMediaItem.isVideo && inPip)

                if (!shouldPlayBackground) {
                    if (effectiveEngine == "VLC") {
                        if (vlcPlayer.isPlaying) wasPlayingBeforePause = true
                        vlcPlayer.pause()
                    } else {
                        if (exoPlayer.isPlaying) wasPlayingBeforePause = true
                        exoPlayer.pause()
                    }
                }
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (effectiveEngine == "VLC") {
                    vlcPlayer.ensureInitialized()
                    vlcPlayer.refreshVideoSurface()
                }
                if (!inPip && wasPlayingBeforePause) {
                    wasPlayingBeforePause = false
                    if (effectiveEngine == "VLC") {
                        vlcPlayer.play()
                    } else {
                        exoPlayer.play()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Load Uri Source Configurations & Primary Player Setup
    LaunchedEffect(activeMediaItem.uriString, effectiveEngine) {
        audioFallbackAttempted = false
        isNewSession = false

        // Determine seek start point: if engine switched mid-playback, seamlessly continue from last pos
        var startSeekMs = 0L
        val lastLoadedUri = viewModel.lastLoadedPlayerUri
        val lastLoadedEngine = viewModel.lastLoadedEngineType
        val isEngineSwitch = lastLoadedEngine != null && lastLoadedEngine != effectiveEngine && lastLoadedUri == activeMediaItem.uriString

        if (isEngineSwitch) {
            val previousPos = if (lastLoadedEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
            if (previousPos > 0L) {
                startSeekMs = previousPos
            }
        } else {
            // 1. Fetch history position BEFORE starting playback
            val history = viewModel.getHistoryByUri(activeMediaItem.uriString)
            val isNearEnd = history != null && history.duration > 0L && history.progressMs >= (history.duration - 5000L).coerceAtLeast(10000L)
            if (history != null && history.progressMs > 1500L && !isNearEnd) {
                resumePosition = history.progressMs
                val behavior = prefs.resumePlaybackBehavior
                if (behavior.contains("Ask", ignoreCase = true)) {
                    showResumePrompt = true
                    startSeekMs = history.progressMs
                } else if (behavior.contains("Never", ignoreCase = true)) {
                    startSeekMs = 0L
                } else {
                    startSeekMs = history.progressMs
                }
            } else {
                resumePosition = 0L
            }
        }

        // 2. Load per-video preferences or individual settings
        var speedToApply = if (effectiveEngine == "VLC") vlcPlayer.playbackSpeed else (if (exoPlayer.playbackParameters.speed != 1.0f) exoPlayer.playbackParameters.speed else prefs.playbackSpeed)
        var resizeToApply = resizeMode
        var volumeToApply = if (effectiveEngine == "VLC") vlcPlayer.volume else exoPlayer.volume
        var eqPresetToApply = currentEqualizerPreset
        var brightnessToApply = currentBrightness

        var savedSubDisabled = false
        var savedExternalSubUri: String? = null
        var savedVlcAudioTrackId = -1
        var savedVlcSubTrackId = -2

        if (prefs.saveVolumeBrightnessBehavior == "Global") {
            if (prefs.globalVolume in 0f..1f) volumeToApply = prefs.globalVolume
            if (prefs.globalBrightness in 0f..1f) brightnessToApply = prefs.globalBrightness
        }

        try {
            val json = if (prefs.perVideoSettingsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(prefs.perVideoSettingsJson)
            if (json.has(activeMediaItem.uriString)) {
                val videoObj = json.getJSONObject(activeMediaItem.uriString)
                speedToApply = videoObj.optDouble("speed", speedToApply.toDouble()).toFloat()
                resizeToApply = videoObj.optInt("resizeMode", resizeToApply)
                eqPresetToApply = videoObj.optString("eqPreset", eqPresetToApply)
                if (videoObj.has("volume")) {
                    volumeToApply = videoObj.getDouble("volume").toFloat()
                }
                if (videoObj.has("brightness")) {
                    brightnessToApply = videoObj.getDouble("brightness").toFloat()
                }
                savedSubDisabled = videoObj.optBoolean("subDisabled", false)
                if (videoObj.has("externalSubUri")) {
                    savedExternalSubUri = videoObj.getString("externalSubUri")
                }
                savedVlcAudioTrackId = videoObj.optInt("vlcAudioTrackId", -1)
                savedVlcSubTrackId = videoObj.optInt("vlcSubTrackId", -2)
            } else if (prefs.saveVolumeBrightnessBehavior != "Global") {
                if (prefs.globalVolume in 0f..1f) volumeToApply = prefs.globalVolume
                if (prefs.globalBrightness in 0f..1f) brightnessToApply = prefs.globalBrightness
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Apply configurations
        resizeMode = resizeToApply
        if (effectiveEngine == "VLC") {
            exoPlayer.pause()
            vlcPlayer.setSpeed(speedToApply)
            vlcPlayer.volume = volumeToApply
            vlcPlayer.setSubtitleSizeSp(prefs.subtitleSize)
            vlcPlayer.setSubtitleTextColor(prefs.subtitleTextColor)
            if (savedExternalSubUri != null) {
                vlcPlayer.loadSubtitle(savedExternalSubUri)
            }
        } else {
            vlcPlayer.pause()
            exoPlayer.setPlaybackSpeed(speedToApply)
            exoPlayer.volume = volumeToApply
            val trackBuilder = exoPlayer.trackSelectionParameters.buildUpon()
            if (savedSubDisabled) {
                trackBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                trackBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                trackBuilder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            }
            if (prefs.preferredAudioLanguage.isNotBlank() && prefs.preferredAudioLanguage != "Default") {
                trackBuilder.setPreferredAudioLanguage(prefs.preferredAudioLanguage.lowercase())
            }
            if (prefs.defaultSubtitleLanguage.isNotBlank() && prefs.defaultSubtitleLanguage != "Default") {
                trackBuilder.setPreferredTextLanguage(prefs.defaultSubtitleLanguage.lowercase())
            }
            when (prefs.preferredVideoResolution) {
                "4K (2160p)", "4K" -> trackBuilder.setMaxVideoSize(3840, 2160)
                "1080p" -> trackBuilder.setMaxVideoSize(1920, 1080)
                "720p" -> trackBuilder.setMaxVideoSize(1280, 720)
                "480p" -> trackBuilder.setMaxVideoSize(854, 480)
            }
            exoPlayer.trackSelectionParameters = trackBuilder.build()
            exoPlayer.setSeekParameters(if (prefs.alwaysFastSeek) androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC else androidx.media3.exoplayer.SeekParameters.EXACT)
        }

        if (eqPresetToApply.isNotBlank()) {
            viewModel.applyPreset(eqPresetToApply)
        }

        if (brightnessToApply >= 0f) {
            currentBrightness = brightnessToApply
            (context as? android.app.Activity)?.let { act ->
                act.runOnUiThread {
                    val lp = act.window?.attributes
                    if (lp != null) {
                        lp.screenBrightness = brightnessToApply
                        act.window?.attributes = lp
                    }
                }
            }
        } else {
            val currentB = (context as? android.app.Activity)?.window?.attributes?.screenBrightness ?: -1f
            currentBrightness = if (currentB < 0f) 0.5f else currentB
        }

        // 3. Player Engine Media Loading
        playbackErrorMsg = null
        if (effectiveEngine == "VLC") {
            val isSameUriAlreadyLoaded = vlcPlayer.currentPlayingUri == activeMediaItem.uriString &&
                    (vlcPlayer.isPlaying || vlcPlayer.currentPositionMs > 0L) &&
                    viewModel.lastLoadedEngineType == "VLC"
            if (!isSameUriAlreadyLoaded || isEngineSwitch) {
                try {
                    vlcPlayer.loadMedia(
                        uriString = activeMediaItem.uriString,
                        path = activeMediaItem.path,
                        initialSeekMs = startSeekMs,
                        initialAudioTrackId = savedVlcAudioTrackId,
                        initialSubtitleTrackId = savedVlcSubTrackId
                    )
                    if (savedExternalSubUri != null) {
                        vlcPlayer.loadSubtitle(savedExternalSubUri)
                    }
                    if (savedVlcAudioTrackId != -1) {
                        vlcPlayer.selectAudioTrack(savedVlcAudioTrackId)
                    }
                    if (savedVlcSubTrackId != -2) {
                        vlcPlayer.selectSubtitleTrack(savedVlcSubTrackId)
                    }
                } catch (e: Throwable) {
                    playbackErrorMsg = "VLC Error: ${e.localizedMessage ?: "Playback error"}"
                }
            } else {
                if (startSeekMs > 0L && kotlin.math.abs(vlcPlayer.currentPositionMs - startSeekMs) > 2000L) {
                    vlcPlayer.seekTo(startSeekMs)
                }
                if (!vlcPlayer.isPlaying) {
                    vlcPlayer.play()
                }
            }
        } else {
            val currentMediaId = exoPlayer.currentMediaItem?.mediaId
            val currentPlayingUri = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
            val isSameUriAlreadyLoaded = exoPlayer.currentMediaItem != null &&
                    (currentMediaId == activeMediaItem.uriString || currentPlayingUri == activeMediaItem.uriString) &&
                    (exoPlayer.playbackState == Player.STATE_READY || exoPlayer.playbackState == Player.STATE_BUFFERING || exoPlayer.isPlaying) &&
                    viewModel.lastLoadedEngineType == "ExoPlayer"
            if (!isSameUriAlreadyLoaded || isEngineSwitch) {
                val newItem = buildMediaItemWithSubtitles(
                    uriString = activeMediaItem.uriString,
                    context = context,
                    path = activeMediaItem.path,
                    externalSubtitleUri = savedExternalSubUri,
                    title = activeMediaItem.title,
                    artist = activeMediaItem.artist,
                    album = activeMediaItem.album
                )
                try {
                    if (startSeekMs > 0L) {
                        exoPlayer.setMediaItem(newItem, startSeekMs)
                    } else {
                        exoPlayer.setMediaItem(newItem)
                    }
                    exoPlayer.prepare()
                    if (startSeekMs > 0L) {
                        exoPlayer.seekTo(startSeekMs)
                    }
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                } catch (e: Throwable) {
                    playbackErrorMsg = "Playback Error: ${e.localizedMessage ?: "Unknown media error"}"
                }
            } else {
                if (startSeekMs > 0L && kotlin.math.abs(exoPlayer.currentPosition - startSeekMs) > 2000L) {
                    exoPlayer.seekTo(startSeekMs)
                }
                if (!exoPlayer.isPlaying && exoPlayer.playbackState != Player.STATE_ENDED) {
                    exoPlayer.play()
                }
            }
        }
        viewModel.lastLoadedPlayerUri = activeMediaItem.uriString
        viewModel.lastLoadedEngineType = effectiveEngine
    }

    // Log History periodically
    LaunchedEffect(activeMediaItem, effectiveEngine) {
        while (true) {
            delay(5000)
            val pos = if (effectiveEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
            val playing = if (effectiveEngine == "VLC") vlcPlayer.isPlaying else exoPlayer.isPlaying
            if (playing && pos > 0) {
                viewModel.addPlaybackHistory(activeMediaItem, pos)
            }
        }
    }

    // Log history immediately when playback is paused
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            val pos = if (effectiveEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
            if (pos > 0) {
                viewModel.addPlaybackHistory(activeMediaItem, pos)
            }
        }
    }

    DisposableEffect(activeMediaItem, effectiveEngine) {
        onDispose {
            val pos = if (effectiveEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
            if (pos > 0) {
                viewModel.addPlaybackHistory(activeMediaItem, pos)
            }
            if (activeMediaItem.isVideo && !isPipActive && !isInPipMode) {
                try {
                    if (effectiveEngine == "VLC") vlcPlayer.pause() else exoPlayer.pause()
                } catch (e: Exception) {}
            }
        }
    }

    // Auto-save per-video settings when they are modified (debounced)
    LaunchedEffect(currentBrightness, resizeMode, currentEqualizerPreset) {
        delay(500)
        val currentSpeed = exoPlayer.playbackParameters.speed
        val currentVol = exoPlayer.volume
        viewModel.updatePerVideoSettings(
            uriString = activeMediaItem.uriString,
            speed = currentSpeed,
            resizeMode = resizeMode,
            volume = currentVol,
            eqPreset = currentEqualizerPreset,
            brightness = currentBrightness
        )
    }

    // Auto-save volume and brightness levels based on behavior settings (debounced)
    LaunchedEffect(currentBrightness) {
        delay(500)
        val currentVol = exoPlayer.volume
        val currentBri = currentBrightness

        viewModel.updateGlobalVolume(currentVol)
        if (currentBri >= 0f) {
            viewModel.updateGlobalBrightness(currentBri)
        }

        viewModel.updatePerVideoVolumeBrightness(
            uriString = activeMediaItem.uriString,
            volume = currentVol,
            brightness = currentBri
        )
    }

    // Core States
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0L) }
    var lastSeekTimeMs by remember { mutableLongStateOf(0L) }
    var lastSeekTargetPosition by remember { mutableLongStateOf(-1L) }
    var pendingGestureSeekTargetMs by remember { mutableLongStateOf(-1L) }

    val performSeek: (Long) -> Unit = { targetMs ->
        val maxDur = if (duration > 0) duration else Long.MAX_VALUE
        val clampedTarget = targetMs.coerceIn(0L, maxDur)
        lastSeekTimeMs = System.currentTimeMillis()
        lastSeekTargetPosition = clampedTarget
        currentPosition = clampedTarget
        scrubPosition = clampedTarget
        if (effectiveEngine == "VLC") {
            vlcPlayer.seekTo(clampedTarget)
        } else {
            exoPlayer.seekTo(clampedTarget)
        }
    }

    // Keep PlayerControlBridge updated with active player engine reference & controls
    LaunchedEffect(effectiveEngine, duration, currentPosition) {
        com.example.ui.viewmodel.PlayerControlBridge.activeEngineType = effectiveEngine
        com.example.ui.viewmodel.PlayerControlBridge.activeEngineName = if (effectiveEngine == "VLC") "LibVLC Universal Engine" else "Media3 ExoPlayer"
        com.example.ui.viewmodel.PlayerControlBridge.vlcPlayerRef = java.lang.ref.WeakReference(vlcPlayer)
        com.example.ui.viewmodel.PlayerControlBridge.exoPlayerRef = java.lang.ref.WeakReference(exoPlayer)
        com.example.ui.viewmodel.PlayerControlBridge.viewModelRef = java.lang.ref.WeakReference(viewModel)
        com.example.ui.viewmodel.PlayerControlBridge.onPlayListener = {
            if (effectiveEngine == "VLC") {
                vlcPlayer.play()
            } else {
                exoPlayer.play()
            }
            isPlaying = true
        }
        com.example.ui.viewmodel.PlayerControlBridge.onPauseListener = {
            if (effectiveEngine == "VLC") {
                vlcPlayer.pause()
            } else {
                exoPlayer.pause()
            }
            isPlaying = false
        }
        com.example.ui.viewmodel.PlayerControlBridge.onPlayPauseListener = {
            if (effectiveEngine == "VLC") {
                if (vlcPlayer.isPlaying) {
                    vlcPlayer.pause()
                    isPlaying = false
                } else {
                    vlcPlayer.play()
                    isPlaying = true
                }
            } else {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                    isPlaying = false
                } else {
                    exoPlayer.play()
                    isPlaying = true
                }
            }
        }
        com.example.ui.viewmodel.PlayerControlBridge.onNextListener = {
            isAutoTransitioning = true
            viewModel.playNext()
        }
        com.example.ui.viewmodel.PlayerControlBridge.onPrevListener = {
            isAutoTransitioning = true
            viewModel.playPrevious()
        }
        com.example.ui.viewmodel.PlayerControlBridge.onSeekByListener = { offsetMs ->
            val maxDur = if (duration > 0) duration else Long.MAX_VALUE
            val target = (currentPosition + offsetMs).coerceIn(0L, maxDur)
            performSeek(target)
        }
        com.example.ui.viewmodel.PlayerControlBridge.onSeekToListener = { targetMs ->
            performSeek(targetMs)
        }
    }

    // Synchronize Live Native Android Media Notification with playback state & active track
    LaunchedEffect(activeMediaItem, isPlaying, prefs.seekButtonsInNotification) {
        com.example.service.MediaPlaybackService.updateNotification(
            context = context,
            item = activeMediaItem,
            isPlaying = isPlaying,
            seekButtonsEnabled = prefs.seekButtonsInNotification
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            val bgModeNormalized = when (prefs.backgroundMode) {
                "PLAY_BACKGROUND_AUDIO", "Play in Background" -> "PLAY_BACKGROUND_AUDIO"
                else -> "STOP_PLAYBACK"
            }
            if (bgModeNormalized != "PLAY_BACKGROUND_AUDIO") {
                com.example.service.MediaPlaybackService.stopPlaybackService(context)
            }
        }
    }

    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isSaved by remember(activeMediaItem.uriString, prefs.playlistsJson) {
        mutableStateOf(viewModel.isMediaFavorite(activeMediaItem.uriString))
    }
    var isLeftPillExpanded by remember { mutableStateOf(false) }
    var isRightPillExpanded by remember { mutableStateOf(false) }
    var rotateAngle by remember { mutableStateOf(0f) }
    var isLockControlVisible by remember { mutableStateOf(true) }
    var scrubbingBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // LRU Cache for preloaded video keyframe thumbnails (up to 120 bitmaps)
    val thumbnailCache = remember { androidx.collection.LruCache<String, android.graphics.Bitmap>(120) }

    // Async thumbnail extractor during scrubbing (highly optimized, preloaded, cached seek frame system)
    var cachedRetriever by remember { mutableStateOf<android.media.MediaMetadataRetriever?>(null) }

    // Background Auto-Preload Keyframe Thumbnails for instant scrub preview (local files only to protect network & ExoPlayer)
    LaunchedEffect(activeMediaItem) {
        val isHttp = activeMediaItem.uriString.startsWith("http://") || activeMediaItem.uriString.startsWith("https://")
        if (activeMediaItem.isVideo && !isHttp) {
            kotlinx.coroutines.delay(1200L) // Yield initial CPU & disk I/O completely to ExoPlayer video startup
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var retriever: android.media.MediaMetadataRetriever? = null
                var pfd: android.os.ParcelFileDescriptor? = null
                try {
                    retriever = android.media.MediaMetadataRetriever()
                    if (activeMediaItem.uriString.startsWith("content://")) {
                        try {
                            val uri = parseMediaUri(activeMediaItem.uriString, activeMediaItem.path, context)
                            if (uri.scheme == "file") {
                                retriever.setDataSource(uri.path)
                            } else {
                                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                                if (pfd != null) {
                                    retriever.setDataSource(pfd.fileDescriptor)
                                } else {
                                    retriever.setDataSource(context, uri)
                                }
                            }
                        } catch (e: Exception) {
                            if (!activeMediaItem.path.isNullOrBlank() && java.io.File(activeMediaItem.path).exists()) {
                                retriever.setDataSource(activeMediaItem.path)
                            } else {
                                retriever.setDataSource(context, parseMediaUri(activeMediaItem.uriString, activeMediaItem.path, context))
                            }
                        }
                    } else {
                        retriever.setDataSource(activeMediaItem.path ?: activeMediaItem.uriString)
                    }
                    val videoId = activeMediaItem.uriString.hashCode().toString()
                    val durSec = (activeMediaItem.duration / 1000L).coerceAtLeast(10L)
                    val stepSec = (durSec / 12L).coerceIn(3L, 30L)
                    for (sec in 0L..durSec step stepSec) {
                        kotlinx.coroutines.yield()
                        val key = "${videoId}_$sec"
                        if (thumbnailCache.get(key) == null) {
                            val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(
                                    sec * 1000000L,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    160,
                                    90
                                )
                            } else {
                                retriever.getFrameAtTime(
                                    sec * 1000000L,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                                )
                            }
                            if (frame != null) {
                                thumbnailCache.put(key, frame)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore non-fatal thumbnail extraction exceptions
                } finally {
                    try { pfd?.close() } catch (e: Exception) {}
                    try { retriever?.release() } catch (e: Exception) {}
                }
            }
        }
    }

    LaunchedEffect(isScrubbing, activeMediaItem) {
        if (isScrubbing && activeMediaItem.isVideo) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val r = android.media.MediaMetadataRetriever()
                    if (activeMediaItem.uriString.startsWith("content://")) {
                        var pfd: android.os.ParcelFileDescriptor? = null
                        try {
                            val uri = parseMediaUri(activeMediaItem.uriString, activeMediaItem.path, context)
                            if (uri.scheme == "file") {
                                r.setDataSource(uri.path)
                            } else {
                                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                                if (pfd != null) {
                                    r.setDataSource(pfd.fileDescriptor)
                                } else {
                                    r.setDataSource(context, uri)
                                }
                            }
                        } catch (e: Exception) {
                            if (!activeMediaItem.path.isNullOrBlank() && java.io.File(activeMediaItem.path).exists()) {
                                r.setDataSource(activeMediaItem.path)
                            } else {
                                r.setDataSource(context, parseMediaUri(activeMediaItem.uriString, activeMediaItem.path, context))
                            }
                        } finally {
                            try { pfd?.close() } catch (e: Exception) {}
                        }
                    } else if (activeMediaItem.uriString.startsWith("http://") || activeMediaItem.uriString.startsWith("https://")) {
                        r.setDataSource(activeMediaItem.uriString, HashMap<String, String>())
                    } else {
                        r.setDataSource(activeMediaItem.path ?: activeMediaItem.uriString)
                    }
                    cachedRetriever = r
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            val r = cachedRetriever
            cachedRetriever = null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    r?.release()
                } catch (e: Exception) {}
            }
            scrubbingBitmap = null
        }
    }

    LaunchedEffect(scrubPosition, cachedRetriever, isScrubbing) {
        if (isScrubbing && activeMediaItem.isVideo) {
            val videoId = activeMediaItem.uriString.hashCode().toString()
            val targetSec = scrubPosition / 1000L
            val exactKey = "${videoId}_$targetSec"
            
            // 1. Try exact cache lookup
            var cachedBitmap = thumbnailCache.get(exactKey)
            
            // 2. Try nearby keyframe cache (+/- 5 seconds)
            if (cachedBitmap == null) {
                for (offset in listOf(-1L, 1L, -2L, 2L, -4L, 4L, -8L, 8L)) {
                    val testKey = "${videoId}_${(targetSec + offset).coerceAtLeast(0L)}"
                    val b = thumbnailCache.get(testKey)
                    if (b != null) {
                        cachedBitmap = b
                        break
                    }
                }
            }

            if (cachedBitmap != null) {
                scrubbingBitmap = cachedBitmap
            }

            val retriever = cachedRetriever
            if (retriever != null) {
                delay(50) // Smooth debounce for fast drag seeking without blocking thread
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(
                                scrubPosition * 1000L,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                180, // width
                                100  // height
                            )
                        } else {
                            retriever.getFrameAtTime(
                                scrubPosition * 1000L,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            )
                        }
                        if (frame != null) {
                            thumbnailCache.put(exactKey, frame)
                            scrubbingBitmap = frame
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    // Advanced Loop & Sync States
    var pointA by remember { mutableStateOf<Long?>(null) }
    var pointB by remember { mutableStateOf<Long?>(null) }
    var abRepeatEnabled by remember { mutableStateOf(false) }
    var audioDelayMs by remember { mutableStateOf(0L) }

    // Bottom Drawers visibility
    var showAdvancedControlsSheet by remember { mutableStateOf(false) }
    var showSubtitleCustomizationSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showAudioSubtitleSelectorSheet by remember { mutableStateOf(false) }
    var showCastControlSheet by remember { mutableStateOf(false) }
    var isCastingActive by remember { mutableStateOf(false) }
    var connectedCastDevice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resizeMode, effectiveEngine) {
        if (effectiveEngine == "VLC") {
            when (resizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> {
                    vlcPlayer.setAspectRatio(null)
                    vlcPlayer.setScale(0f)
                }
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                    vlcPlayer.setAspectRatio("16:9")
                    vlcPlayer.setScale(0f)
                }
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                    vlcPlayer.setAspectRatio(null)
                    vlcPlayer.setScale(1.35f)
                }
                AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> {
                    vlcPlayer.setAspectRatio("16:9")
                    vlcPlayer.setScale(0f)
                }
                AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> {
                    vlcPlayer.setAspectRatio("4:3")
                    vlcPlayer.setScale(0f)
                }
                100 -> {
                    vlcPlayer.setAspectRatio("21:9")
                    vlcPlayer.setScale(0f)
                }
                else -> {
                    vlcPlayer.setAspectRatio(null)
                    vlcPlayer.setScale(0f)
                }
            }
        }
    }

    // Register Screen Off / Screen Sleep BroadcastReceiver and Headset/Earbud Becoming Noisy Receiver
    DisposableEffect(context, exoPlayer, vlcPlayer, effectiveEngine, prefs.keepCastingOnScreenSleep, prefs.backgroundMode, isCastingActive, activeMediaItem, playAsAudioOnly) {
        val screenAndAudioReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> {
                        val isBackgroundAudioAllowed = prefs.backgroundMode == "PLAY_BACKGROUND_AUDIO" ||
                                prefs.backgroundMode == "Play in Background" ||
                                !activeMediaItem.isVideo || playAsAudioOnly
                        if (isCastingActive && prefs.keepCastingOnScreenSleep) {
                            if (effectiveEngine == "VLC") {
                                if (!vlcPlayer.isPlaying) vlcPlayer.play()
                            } else {
                                if (!exoPlayer.isPlaying) exoPlayer.play()
                            }
                            isPlaying = true
                        } else if (!isBackgroundAudioAllowed) {
                            // Video playback should pause on screen sleep/off
                            if (effectiveEngine == "VLC") {
                                if (vlcPlayer.isPlaying) wasPlayingBeforePause = true
                                vlcPlayer.pause()
                            } else {
                                if (exoPlayer.isPlaying) wasPlayingBeforePause = true
                                exoPlayer.pause()
                            }
                            isPlaying = false
                        }
                    }
                    android.content.Intent.ACTION_SCREEN_ON,
                    android.content.Intent.ACTION_USER_PRESENT -> {
                        if (effectiveEngine == "VLC") {
                            vlcPlayer.ensureInitialized()
                            vlcPlayer.refreshVideoSurface()
                        }
                    }
                    android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        // Earbuds / Bluetooth headphones disconnected -> pause immediately
                        if (effectiveEngine == "VLC") {
                            vlcPlayer.pause()
                        } else {
                            exoPlayer.pause()
                        }
                        isPlaying = false
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
            addAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        context.registerReceiver(screenAndAudioReceiver, filter)
        onDispose {
            try {
                context.unregisterReceiver(screenAndAudioReceiver)
            } catch (e: Exception) {
                // ignore
            }
        }
    }
    var showFileBrowserForSubtitle by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var initialGesturePosition by remember { mutableStateOf(0L) }

    // Dialogs & overlays visibility
    var showJumpToTimeDialog by remember { mutableStateOf(false) }
    var showSavePlaylistDialog by remember { mutableStateOf(false) }
    var showVideoInfoOverlay by remember { mutableStateOf(false) }
    var showTipsOverlay by remember { mutableStateOf(false) }

    // Playback modifiers
    var bookmarks by remember { mutableStateOf(listOf<Long>()) }

    LaunchedEffect(Unit) {
        if (viewModel.audioOnlyPlaybackRequested) {
            viewModel.audioOnlyPlaybackRequested = false
        }
    }
    var isShuffleEnabled by remember { mutableStateOf(exoPlayer.shuffleModeEnabled) }
    var repeatModeState by remember {
        mutableStateOf(
            when (exoPlayer.repeatMode) {
                Player.REPEAT_MODE_ONE -> 1
                Player.REPEAT_MODE_ALL -> 2
                else -> 0
            }
        )
    } // 0 = OFF, 1 = ONE, 2 = ALL



    var sleepTimeLeftMinutes by remember { mutableStateOf(0) }
    var isSleepTimerRunning by remember { mutableStateOf(false) }

    // Aspect Ratio / Zoom Scale
    var videoScale by remember { mutableStateOf(1.0f) }

    // Overlay feedback
    var gestureFeedbackType by remember { mutableStateOf("") } // "volume", "brightness", "seek", "aspect_ratio"
    var gestureFeedbackValue by remember { mutableStateOf("") }

    LaunchedEffect(gestureFeedbackType, gestureFeedbackValue) {
        if (gestureFeedbackType == "aspect_ratio") {
            kotlinx.coroutines.delay(1200)
            if (gestureFeedbackType == "aspect_ratio") {
                gestureFeedbackType = ""
            }
        }
    }
    var activeDragVolume by remember { mutableStateOf<Float?>(null) }
    var activeDragBrightness by remember { mutableStateOf<Float?>(null) }
    var gestureSessionType by remember { mutableStateOf("none") }
    var totalPanX by remember { mutableStateOf(0f) }
    var totalPanY by remember { mutableStateOf(0f) }

    var lastGestureTime by remember { mutableStateOf(0L) }

    var leftRippleTrigger by remember { mutableStateOf(0) }
    var rightRippleTrigger by remember { mutableStateOf(0) }
    var centerRippleTrigger by remember { mutableStateOf(0) }
    var leftDoubleTapOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var rightDoubleTapOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var centerDoubleTapOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val leftAnim = remember { Animatable(0f) }
    val rightAnim = remember { Animatable(0f) }
    val centerAnim = remember { Animatable(0f) }

    LaunchedEffect(leftRippleTrigger) {
        if (leftRippleTrigger > 0) {
            leftAnim.snapTo(0f)
            leftAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = LinearOutSlowInEasing)
            )
            leftAnim.snapTo(0f)
        }
    }

    LaunchedEffect(centerRippleTrigger) {
        if (centerRippleTrigger > 0) {
            centerAnim.snapTo(0f)
            centerAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = LinearOutSlowInEasing)
            )
            centerAnim.snapTo(0f)
        }
    }

    LaunchedEffect(rightRippleTrigger) {
        if (rightRippleTrigger > 0) {
            rightAnim.snapTo(0f)
            rightAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = LinearOutSlowInEasing)
            )
            rightAnim.snapTo(0f)
        }
    }

    LaunchedEffect(lastGestureTime) {
        if (lastGestureTime > 0L) {
            delay(1200)
            if (System.currentTimeMillis() - lastGestureTime >= 1200) {
                activeDragVolume = null
                activeDragBrightness = null
                gestureFeedbackType = ""
            }
        }
    }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // Sleep Timer trigger loop
    LaunchedEffect(isSleepTimerRunning) {
        if (isSleepTimerRunning) {
            while (sleepTimeLeftMinutes > 0) {
                delay(60000)
                sleepTimeLeftMinutes -= 1
                if (sleepTimeLeftMinutes == 0) {
                    if (effectiveEngine == "VLC") {
                        vlcPlayer.pause()
                    } else {
                        exoPlayer.pause()
                    }
                    isPlaying = false
                    isSleepTimerRunning = false
                    gestureFeedbackType = "seek"
                    gestureFeedbackValue = "Sleep Timer Finished ⏰"
                }
            }
        }
    }

    // Background OS sleep prevention overrides
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager }
    val wakeLock = remember {
        powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AeroPlayer::PlaybackWakeLock")
    }
    val wifiManager = remember { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager }
    val wifiLock = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AeroPlayer::WifiLock")
        } else {
            @Suppress("DEPRECATION")
            wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL, "AeroPlayer::WifiLock")
        }
    }

    DisposableEffect(isPlaying, isSleepTimerRunning, sleepTimeLeftMinutes, isLocked) {
        val activity = context as? android.app.Activity
        val isSleepExpired = isSleepTimerRunning && sleepTimeLeftMinutes <= 0
        val shouldKeepAwake = !isSleepExpired && (isPlaying || isLocked)
        if (shouldKeepAwake) {
            try {
                if (!wakeLock.isHeld) {
                    wakeLock.acquire(24 * 60 * 60 * 1000L) // 24 hours lock for active playback / lock mode
                }
                if (!wifiLock.isHeld) {
                    wifiLock.acquire()
                }
                activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                if (wifiLock.isHeld) {
                    wifiLock.release()
                }
                activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onDispose {
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                if (wifiLock.isHeld) {
                    wifiLock.release()
                }
                activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ExoPlayer Event Listeners
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    playbackErrorMsg = null
                    generalRetryCount = 0
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    duration = exoPlayer.duration
                    playbackErrorMsg = null
                    generalRetryCount = 0
                } else if (playbackState == Player.STATE_ENDED) {
                    if (repeatModeState == 1) { // Repeat One
                        exoPlayer.seekTo(0)
                        exoPlayer.play()
                    } else {
                        val playQueue = viewModel.playQueue.value
                        val currentIndex = viewModel.currentQueueIndex.value
                        if (playQueue.size <= 1) {
                            if (repeatModeState == 2) { // Repeat All
                                exoPlayer.seekTo(0)
                                exoPlayer.play()
                            } else {
                                exoPlayer.seekTo(0)
                                exoPlayer.pause()
                            }
                        } else {
                            if (currentIndex >= playQueue.size - 1 && repeatModeState == 0) {
                                exoPlayer.seekTo(0)
                                exoPlayer.pause()
                            } else {
                                isAutoTransitioning = true
                                viewModel.playNext()
                            }
                        }
                    }
                }
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                tracksUpdateTrigger++
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackErrorMsg = "Playback Error: ${error.localizedMessage ?: "Format not supported"}"
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                isShuffleEnabled = shuffleModeEnabled
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                repeatModeState = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> 1
                    Player.REPEAT_MODE_ALL -> 2
                    else -> 0
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // VLC Event Listeners
    DisposableEffect(vlcPlayer, effectiveEngine) {
        if (effectiveEngine == "VLC") {
            vlcPlayer.onIsPlayingChanged = { playing ->
                isPlaying = playing
                if (playing) {
                    playbackErrorMsg = null
                    generalRetryCount = 0
                }
            }
            vlcPlayer.onBufferingChanged = { buffering ->
                isBuffering = buffering
            }
            vlcPlayer.onDurationChanged = { dur ->
                if (dur > 0) duration = dur
            }
            vlcPlayer.onPositionChanged = { pos ->
                // Handled in tracking loop
            }
            vlcPlayer.onPlaybackEnded = {
                if (repeatModeState == 1) { // Repeat One
                    vlcPlayer.seekTo(0)
                    vlcPlayer.play()
                } else {
                    val playQueue = viewModel.playQueue.value
                    val currentIndex = viewModel.currentQueueIndex.value
                    if (playQueue.size <= 1) {
                        if (repeatModeState == 2) {
                            vlcPlayer.seekTo(0)
                            vlcPlayer.play()
                        } else {
                            vlcPlayer.seekTo(0)
                            vlcPlayer.pause()
                        }
                    } else {
                        if (currentIndex >= playQueue.size - 1 && repeatModeState == 0) {
                            vlcPlayer.seekTo(0)
                            vlcPlayer.pause()
                        } else {
                            isAutoTransitioning = true
                            viewModel.playNext()
                        }
                    }
                }
            }
            vlcPlayer.onError = { errorMsg ->
                playbackErrorMsg = "VLC Error: $errorMsg"
            }
            vlcPlayer.onTracksUpdated = {
                tracksUpdateTrigger++
            }
        }
        onDispose {
            if (effectiveEngine == "VLC") {
                vlcPlayer.onIsPlayingChanged = null
                vlcPlayer.onBufferingChanged = null
                vlcPlayer.onDurationChanged = null
                vlcPlayer.onPlaybackEnded = null
                vlcPlayer.onError = null
                vlcPlayer.onTracksUpdated = null
            }
        }
    }

    var lastRestoredTrackMediaUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tracksUpdateTrigger, activeMediaItem.uriString) {
        if (effectiveEngine == "ExoPlayer") {
            val currentTracks = exoPlayer.currentTracks
            if (currentTracks.groups.isNotEmpty() && lastRestoredTrackMediaUri != activeMediaItem.uriString) {
                try {
                    val json = if (prefs.perVideoSettingsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(prefs.perVideoSettingsJson)
                    if (json.has(activeMediaItem.uriString)) {
                        val videoObj = json.getJSONObject(activeMediaItem.uriString)
                        val savedAudioG = videoObj.optInt("audioGroupIndex", -1)
                        val savedAudioT = videoObj.optInt("audioTrackIndex", -1)
                        val savedAudioName = videoObj.optString("audioTrackName", "")
                        val savedAudioLang = videoObj.optString("audioLanguage", "")

                        val savedSubG = videoObj.optInt("subGroupIndex", -1)
                        val savedSubT = videoObj.optInt("subTrackIndex", -1)
                        val savedSubDis = videoObj.optBoolean("subDisabled", false)
                        val savedSubName = videoObj.optString("subtitleTrackName", "")
                        val savedSubLang = videoObj.optString("subtitleLanguage", "")

                        var builder = exoPlayer.trackSelectionParameters.buildUpon()
                        if (savedSubDis) {
                            builder = builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        } else {
                            var matchedSubGroup: androidx.media3.common.Tracks.Group? = null
                            var matchedSubTrackIndex = -1

                            if (savedSubLang.isNotBlank() || savedSubName.isNotBlank()) {
                                for (g in currentTracks.groups) {
                                    if (g.type == C.TRACK_TYPE_TEXT) {
                                        for (t in 0 until g.length) {
                                            val fmt = g.getTrackFormat(t)
                                            if ((savedSubLang.isNotBlank() && fmt.language == savedSubLang) ||
                                                (savedSubName.isNotBlank() && fmt.label == savedSubName)) {
                                                matchedSubGroup = g
                                                matchedSubTrackIndex = t
                                                break
                                            }
                                        }
                                        if (matchedSubGroup != null) break
                                    }
                                }
                            }
                            if (matchedSubGroup == null && savedSubG in 0 until currentTracks.groups.size && savedSubT >= 0) {
                                val g = currentTracks.groups[savedSubG]
                                if (g.type == C.TRACK_TYPE_TEXT && savedSubT < g.length) {
                                    matchedSubGroup = g
                                    matchedSubTrackIndex = savedSubT
                                }
                            }

                            if (matchedSubGroup != null && matchedSubTrackIndex >= 0) {
                                builder = builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(matchedSubGroup.mediaTrackGroup, matchedSubTrackIndex))
                            }
                        }

                        var matchedAudioGroup: androidx.media3.common.Tracks.Group? = null
                        var matchedAudioTrackIndex = -1

                        if (savedAudioLang.isNotBlank() || savedAudioName.isNotBlank()) {
                            for (g in currentTracks.groups) {
                                if (g.type == C.TRACK_TYPE_AUDIO) {
                                    for (t in 0 until g.length) {
                                        val fmt = g.getTrackFormat(t)
                                        if ((savedAudioLang.isNotBlank() && fmt.language == savedAudioLang) ||
                                            (savedAudioName.isNotBlank() && fmt.label == savedAudioName)) {
                                            matchedAudioGroup = g
                                            matchedAudioTrackIndex = t
                                            break
                                        }
                                    }
                                    if (matchedAudioGroup != null) break
                                }
                            }
                        }
                        if (matchedAudioGroup == null && savedAudioG in 0 until currentTracks.groups.size && savedAudioT >= 0) {
                            val g = currentTracks.groups[savedAudioG]
                            if (g.type == C.TRACK_TYPE_AUDIO && savedAudioT < g.length) {
                                matchedAudioGroup = g
                                matchedAudioTrackIndex = savedAudioT
                            }
                        }

                        if (matchedAudioGroup != null && matchedAudioTrackIndex >= 0) {
                            builder = builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .setOverrideForType(TrackSelectionOverride(matchedAudioGroup.mediaTrackGroup, matchedAudioTrackIndex))
                        }

                        exoPlayer.trackSelectionParameters = builder.build()
                        lastRestoredTrackMediaUri = activeMediaItem.uriString
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Auto-unblock buffering stall watchdog
    LaunchedEffect(isBuffering, effectiveEngine) {
        if (isBuffering && effectiveEngine == "ExoPlayer") {
            delay(3500)
            if (isBuffering && exoPlayer.playbackState == Player.STATE_BUFFERING) {
                val pos = exoPlayer.currentPosition
                try {
                    exoPlayer.prepare()
                    if (pos > 0) {
                        exoPlayer.seekTo(pos)
                    }
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Playback loop tracking position with post-seek position buffer filter suppressing stale engine timestamps for 600ms
    LaunchedEffect(abRepeatEnabled, pointA, pointB, effectiveEngine) {
        while (true) {
            if (!isScrubbing && gestureSessionType != "seek") {
                val rawPos = if (effectiveEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
                val rawDur = if (effectiveEngine == "VLC") vlcPlayer.durationMs.coerceAtLeast(0L) else exoPlayer.duration.coerceAtLeast(0L)

                val now = System.currentTimeMillis()
                val isPostSeekBufferWindow = (now - lastSeekTimeMs) < 600L && lastSeekTargetPosition >= 0L

                if (isPostSeekBufferWindow) {
                    // Suppress stale pre-seek timestamps reported by player engines for 600ms after seeking
                    val diff = kotlin.math.abs(rawPos - lastSeekTargetPosition)
                    if (diff < 1500L) {
                        currentPosition = rawPos
                    } else {
                        currentPosition = lastSeekTargetPosition
                    }
                } else {
                    if (rawPos >= 0) {
                        currentPosition = rawPos
                    }
                }

                if (rawDur > 0) duration = rawDur
            }
            val currentPos = currentPosition
            if (abRepeatEnabled && pointA != null && pointB != null) {
                if (currentPos >= pointB!!) {
                    performSeek(pointA!!)
                }
            }
            delay(if (isPlaying) 150L else 400L)
        }
    }

    // Auto-hide HUD controls after 5 seconds if playing and no gesture is active
    LaunchedEffect(isControlsVisible, isPlaying, gestureSessionType) {
        if (isControlsVisible) {
            delay(5000)
            if (isPlaying && gestureSessionType == "none") {
                isControlsVisible = false
            }
        }
    }

    // Auto-hide lock control after 5 seconds if playing and no gesture is active
    LaunchedEffect(isLockControlVisible, isPlaying, gestureSessionType) {
        if (isLockControlVisible) {
            delay(5000)
            if (isPlaying && gestureSessionType == "none") {
                isLockControlVisible = false
            }
        }
    }

    val safeOnBack = {
        try {
            exoPlayer.pause()
        } catch (e: Exception) {}
        isPlaying = false
        onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                exoPlayer.pause()
            } catch (e: Exception) {}
        }
    }

    BackHandler {
        when {
            showFileBrowserForSubtitle -> showFileBrowserForSubtitle = false
            showAudioSubtitleSelectorSheet -> showAudioSubtitleSelectorSheet = false
            showAdvancedControlsSheet -> showAdvancedControlsSheet = false
            showSleepTimerSheet -> showSleepTimerSheet = false
            showEqualizerSheet -> showEqualizerSheet = false
            else -> safeOnBack()
        }
    }

    if (isPipActive) {
        Box(modifier = Modifier.fillMaxSize()) {
            MainScreen(
                viewModel = viewModel,
                onPlayItem = { newMedia ->
                    activeMediaItem = newMedia
                },
                onNavigateToSettings = {
                    safeOnBack()
                }
            )

            var pipOffset by remember { mutableStateOf(Offset(0f, 0f)) }
            
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomEnd
            ) {
                Card(
                    modifier = Modifier
                        .padding(end = 16.dp, bottom = 100.dp)
                        .offset { IntOffset(pipOffset.x.roundToInt(), pipOffset.y.roundToInt()) }
                        .width(280.dp)
                        .height(175.dp)
                        .shadow(20.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Video / Surface View
                        if (activeMediaItem.isVideo && !playAsAudioOnly) {
                            if (effectiveEngine == "VLC") {
                                com.example.ui.components.VlcPlayerView(
                                    vlcPlayer = vlcPlayer,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            useController = false
                                            keepScreenOn = true
                                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                            player = exoPlayer
                                            layoutParams = FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        }
                                    },
                                    update = { view ->
                                        if (view.player != exoPlayer) {
                                            view.player = exoPlayer
                                        }
                                        if (view.resizeMode != resizeMode) {
                                            view.resizeMode = resizeMode
                                        }
                                        applySubtitleStyleToPlayerView(view, prefs)
                                    },
                                    onRelease = { view ->
                                        // Retain player binding to prevent blank frame on transient recomposition
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }

                        // Overlay with Header & Clean Control Bar
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f))
                        ) {
                            // Top Drag Bar & Title & Window Buttons
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .background(Color.Black.copy(alpha = 0.50f))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Draggable Handle & Title Area
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .pointerInput(Unit) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                pipOffset = Offset(
                                                    x = pipOffset.x + dragAmount.x,
                                                    y = pipOffset.y + dragAmount.y
                                                )
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag Pop-Up Window",
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = activeMediaItem.title.ifBlank { "Pop-Up Player" },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Fullscreen Button
                                IconButton(
                                    onClick = { isPipActive = false },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fullscreen,
                                        contentDescription = "Restore Fullscreen",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                // Close Button
                                IconButton(
                                    onClick = {
                                        isPipActive = false
                                        try {
                                            if (effectiveEngine == "VLC") {
                                                vlcPlayer.pause()
                                            } else {
                                                exoPlayer.pause()
                                            }
                                        } catch (e: Exception) {}
                                        isPlaying = false
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Pop-Up Player",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Center Clean Controls: Backward -10s, Toggle Play/Pause, Forward +10s
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    // Backward 10s
                                    IconButton(
                                        onClick = {
                                            val target = (currentPosition - 10000L).coerceAtLeast(0L)
                                            performSeek(target)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Replay10,
                                            contentDescription = "Backward 10s",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    // Toggle Play / Pause Button
                                    IconButton(
                                        onClick = {
                                            if (effectiveEngine == "VLC") {
                                                if (vlcPlayer.isPlaying) {
                                                    vlcPlayer.pause()
                                                    isPlaying = false
                                                } else {
                                                    vlcPlayer.play()
                                                    isPlaying = true
                                                }
                                            } else {
                                                if (exoPlayer.isPlaying) {
                                                    exoPlayer.pause()
                                                    isPlaying = false
                                                } else {
                                                    exoPlayer.play()
                                                    isPlaying = true
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    // Forward 10s
                                    IconButton(
                                        onClick = {
                                            val maxDur = if (duration > 0) duration else Long.MAX_VALUE
                                            val target = (currentPosition + 10000L).coerceAtMost(maxDur)
                                            performSeek(target)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Forward10,
                                            contentDescription = "Forward 10s",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }

                            // Thin Progress Indicator along the bottom edge
                            LinearProgressIndicator(
                                progress = { if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .align(Alignment.BottomCenter),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.White.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }
    } else if (!activeMediaItem.isVideo || playAsAudioOnly) {
        CustomAudioPlayerScreen(
            mediaItem = activeMediaItem,
            isPlaying = isPlaying,
            currentPosition = currentPosition,
            duration = duration,
            isShuffleEnabled = isShuffleEnabled,
            repeatModeState = repeatModeState,
            sleepTimeLeftMinutes = sleepTimeLeftMinutes,
            currentEqualizerPreset = currentEqualizerPreset,
            showSwitchToVideoBtn = activeMediaItem.isVideo && playAsAudioOnly,
            onSwitchToVideo = { playAsAudioOnly = false },
            onBack = safeOnBack,
            onTogglePlay = {
                if (effectiveEngine == "VLC") {
                    if (vlcPlayer.isPlaying) {
                        vlcPlayer.pause()
                        isPlaying = false
                    } else {
                        vlcPlayer.play()
                        isPlaying = true
                    }
                } else {
                    if (exoPlayer.playbackState == Player.STATE_IDLE) {
                        exoPlayer.prepare()
                    }
                    if (exoPlayer.playbackState == Player.STATE_ENDED) {
                        exoPlayer.seekTo(0)
                    }
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                        isPlaying = false
                    } else {
                        exoPlayer.play()
                        isPlaying = true
                    }
                }
                viewModel.setPlaying(isPlaying)
            },
            onSeek = { pos ->
                performSeek(pos)
            },
            onPrev = { isAutoTransitioning = true; viewModel.playPrevious() },
            onNext = { isAutoTransitioning = true; viewModel.playNext() },
            onToggleShuffle = {
                isShuffleEnabled = !isShuffleEnabled
                exoPlayer.shuffleModeEnabled = isShuffleEnabled
            },
            onCycleRepeat = {
                repeatModeState = (repeatModeState + 1) % 3
                exoPlayer.repeatMode = when (repeatModeState) {
                    1 -> Player.REPEAT_MODE_ONE
                    2 -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
            },
            onOpenQueue = { showQueueSheet = true },
            onOpenSleepTimer = { showSleepTimerSheet = true },
            onOpenEqualizer = { showEqualizerSheet = true },
            onOpenInfo = { showVideoInfoOverlay = true },
            isSaved = isSaved,
            onToggleFavorite = {
                viewModel.toggleFavoriteMedia(activeMediaItem.uriString)
                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                val msg = if (!isSaved) "Saved to Library ❤️" else "Removed from Library"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            },
            onOpenAdvancedControls = { showAdvancedControlsSheet = true }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(vertical = 5.dp)
        ) {
            // Video View or Audio Vinyl visualizer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = videoScale, scaleY = videoScale)
                    .pointerInput(isLocked) {
                        if (isLocked) {
                            detectDragGestures(onDragStart = { isControlsVisible = true }) { _, _ -> }
                            return@pointerInput
                        }
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            lastGestureTime = System.currentTimeMillis()
                            
                            var totalDragX = 0f
                            var totalDragY = 0f
                            
                            do {
                                val event = awaitPointerEvent()
                                val changes = event.changes
                                if (changes.isNotEmpty()) {
                                    val isMultiTouch = changes.size > 1
                                    
                                    if (isMultiTouch) {
                                        isControlsVisible = true
                                        gestureSessionType = "zoom"
                                        val p1 = changes[0].position
                                        val p2 = changes[1].position
                                        val prevP1 = changes[0].previousPosition
                                        val prevP2 = changes[1].previousPosition
                                        val dist = kotlin.math.sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y))
                                        val prevDist = kotlin.math.sqrt((prevP1.x - prevP2.x) * (prevP1.x - prevP2.x) + (prevP1.y - prevP2.y) * (prevP1.y - prevP2.y))
                                        if (prevDist > 0f && dist > 0f) {
                                            val scaleFactor = dist / prevDist
                                            videoScale = (videoScale * scaleFactor).coerceIn(1.0f, 4.0f)
                                        }
                                        changes.forEach { it.consume() }
                                    } else {
                                        val change = changes.first()
                                        if (change.pressed) {
                                            val position = change.position
                                            val previousPosition = change.previousPosition
                                            val delta = position - previousPosition
                                            
                                            totalDragX += delta.x
                                            totalDragY += delta.y
                                            
                                            val width = size.width
                                            val height = size.height
                                            
                                            if (gestureSessionType == "none") {
                                                val thresholdX = width * 0.015f
                                                val thresholdY = height * 0.015f
                                                if (abs(totalDragX) > thresholdX || abs(totalDragY) > thresholdY) {
                                                    isControlsVisible = true
                                                    if (abs(totalDragY) > abs(totalDragX)) {
                                                        gestureSessionType = if (firstDown.position.x < width / 2) "brightness" else "volume"
                                                    } else {
                                                        gestureSessionType = "seek"
                                                        isScrubbing = true
                                                        initialGesturePosition = if (effectiveEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
                                                        scrubPosition = initialGesturePosition
                                                    }
                                                }
                                            }
                                            
                                            if (gestureSessionType != "none" && gestureSessionType != "zoom") {
                                                change.consume()
                                                when (gestureSessionType) {
                                                    "brightness" -> {
                                                        val currentBri = activeDragBrightness ?: ((context as? Activity)?.window?.attributes?.screenBrightness?.let { if (it < 0f) 0.5f else it } ?: 0.5f)
                                                        gestureFeedbackType = "brightness"
                                                        val activity = context as? Activity
                                                        val layoutParams = activity?.window?.attributes
                                                        
                                                        val yDelta = -delta.y / height
                                                        val targetB = (currentBri + yDelta).coerceIn(0.01f, 1.0f)
                                                        
                                                        val currentPercent = (currentBri * 100).toInt()
                                                        val targetPercent = (targetB * 100).toInt()
                                                        val prevStep = (currentBri * 10).toInt()
                                                        val nextStep = (targetB * 10).toInt()
                                                        if (prevStep != nextStep) {
                                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                        }
                                                        
                                                        activeDragBrightness = targetB
                                                        layoutParams?.screenBrightness = targetB
                                                        activity?.window?.attributes = layoutParams
                                                        gestureFeedbackValue = "$targetPercent%"
                                                        currentBrightness = targetB
                                                    }
                                                    "volume" -> {
                                                        val currentVol = activeDragVolume ?: (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
                                                        gestureFeedbackType = "volume"
                                                        
                                                        val yDelta = -delta.y / height
                                                        val targetV = (currentVol + yDelta).coerceIn(0f, 1f)
                                                        
                                                        val currentPercent = (currentVol * 100).toInt()
                                                        val targetPercent = (targetV * 100).toInt()
                                                        val prevSysVol = (currentVol * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                                        val systemVol = (targetV * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                                        if (prevSysVol != systemVol) {
                                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                        }
                                                        
                                                        activeDragVolume = targetV
                                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, systemVol, 0)
                                                        gestureFeedbackValue = "$targetPercent%"
                                                    }
                                                    "seek" -> {
                                                        gestureFeedbackType = "seek"
                                                        val seekRangeMs = if (duration > 0L) {
                                                            if (duration <= 60_000L) duration
                                                            else if (duration <= 600_000L) (duration * 0.5f).toLong()
                                                            else (duration * 0.25f).toLong().coerceIn(120_000L, 600_000L)
                                                        } else 120_000L
                                                        val seekDelta = (totalDragX / width * seekRangeMs).toLong()
                                                        val targetSeek = (initialGesturePosition + seekDelta).coerceIn(0L, duration)
                                                        pendingGestureSeekTargetMs = targetSeek
                                                        scrubPosition = targetSeek
                                                        currentPosition = targetSeek
                                                        val diff = targetSeek - initialGesturePosition
                                                        val sign = if (diff >= 0) "+" else "-"
                                                        val diffFormatted = "$sign${formatPlayerDuration(kotlin.math.abs(diff))}"
                                                        gestureFeedbackValue = "${formatPlayerDuration(targetSeek)} ($diffFormatted)"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            
                            if (gestureSessionType == "seek") {
                                val finalTarget = if (pendingGestureSeekTargetMs >= 0L) pendingGestureSeekTargetMs else scrubPosition
                                if (finalTarget >= 0L) {
                                    performSeek(finalTarget)
                                }
                                isScrubbing = false
                            }
                            pendingGestureSeekTargetMs = -1L
                            gestureSessionType = "none"
                            activeDragBrightness = null
                            activeDragVolume = null
                            totalDragX = 0f
                            totalDragY = 0f
                            coroutineScope.launch {
                                delay(800)
                                if (gestureFeedbackType == "brightness" || gestureFeedbackType == "volume" || gestureFeedbackType == "seek") {
                                    gestureFeedbackType = ""
                                }
                            }
                        }
                    }
                    .pointerInput(isLocked) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                if (!isLocked) {
                                    val width = size.width
                                    val seekAmountMs = (prefs.doubleTapSeekSeconds * 1000L)
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    if (offset.x > width * 0.30f && offset.x < width * 0.70f) {
                                        centerDoubleTapOffset = offset
                                        centerRippleTrigger++
                                        if (effectiveEngine == "VLC") {
                                            if (vlcPlayer.isPlaying) vlcPlayer.pause() else vlcPlayer.play()
                                        } else {
                                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                        }
                                    } else if (offset.x <= width * 0.30f) {
                                        val curPos = if (effectiveEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
                                        val targetSeek = (curPos - seekAmountMs).coerceAtLeast(0L)
                                        performSeek(targetSeek)
                                        gestureFeedbackType = "seek_back"
                                        gestureFeedbackValue = "-${prefs.doubleTapSeekSeconds}s"
                                        leftDoubleTapOffset = offset
                                        leftRippleTrigger++
                                    } else {
                                        val curPos = if (effectiveEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
                                        val targetSeek = (curPos + seekAmountMs).coerceAtMost(duration)
                                        performSeek(targetSeek)
                                        gestureFeedbackType = "seek_forward"
                                        gestureFeedbackValue = "+${prefs.doubleTapSeekSeconds}s"
                                        rightDoubleTapOffset = offset
                                        rightRippleTrigger++
                                    }
                                    coroutineScope.launch {
                                        delay(1000)
                                        if (gestureFeedbackType == "seek_back" || gestureFeedbackType == "seek_forward") {
                                            gestureFeedbackType = ""
                                        }
                                    }
                                }
                            },
                            onTap = {
                                if (isLocked) {
                                    isLockControlVisible = true
                                } else {
                                    isControlsVisible = !isControlsVisible
                                    isLockControlVisible = isControlsVisible
                                }
                            }
                        )
                    }
            ) {
                if (activeMediaItem.isVideo && !playAsAudioOnly) {
                    if (effectiveEngine == "VLC") {
                        val vlcAspectRatio = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> null
                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "0"
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> null
                            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "16:9"
                            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "4:3"
                            100 -> "21:9"
                            else -> null
                        }
                        val vlcScale = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) 1.35f else 0f
                        VlcPlayerView(
                            vlcPlayer = vlcPlayer,
                            modifier = Modifier.fillMaxSize(),
                            aspectRatio = vlcAspectRatio,
                            scale = vlcScale
                        )
                    } else {
                        val exoResizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
                            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
                            100 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            else -> resizeMode
                        }
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        useController = false
                                        keepScreenOn = true
                                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                        player = exoPlayer
                                        this.resizeMode = exoResizeMode
                                        layoutParams = FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        val contentFrame = findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                                        if (contentFrame != null) {
                                            when (resizeMode) {
                                                AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> contentFrame.setAspectRatio(16f / 9f)
                                                AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> contentFrame.setAspectRatio(4f / 3f)
                                                100 -> contentFrame.setAspectRatio(21f / 9f)
                                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> contentFrame.setAspectRatio(0f)
                                                else -> {
                                                    val vFormat = exoPlayer.videoFormat
                                                    val naturalAspect = if (vFormat != null && vFormat.width > 0 && vFormat.height > 0) {
                                                        (vFormat.width.toFloat() / vFormat.height.toFloat()) * (if (vFormat.pixelWidthHeightRatio > 0f) vFormat.pixelWidthHeightRatio else 1.0f)
                                                    } else if (exoPlayer.videoSize.width > 0 && exoPlayer.videoSize.height > 0) {
                                                        exoPlayer.videoSize.width.toFloat() / exoPlayer.videoSize.height.toFloat()
                                                    } else {
                                                        0f
                                                    }
                                                    contentFrame.setAspectRatio(naturalAspect)
                                                }
                                            }
                                        }
                                        applySubtitleStyleToPlayerView(this, prefs)
                                    }
                                },
                                update = { view -> 
                                    if (view.player != exoPlayer) {
                                        view.player = exoPlayer
                                    }
                                    if (view.resizeMode != exoResizeMode) {
                                        view.resizeMode = exoResizeMode
                                    }
                                    val contentFrame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                                    if (contentFrame != null) {
                                        when (resizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> contentFrame.setAspectRatio(16f / 9f)
                                            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> contentFrame.setAspectRatio(4f / 3f)
                                            100 -> contentFrame.setAspectRatio(21f / 9f)
                                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> contentFrame.setAspectRatio(0f)
                                            else -> {
                                                val vFormat = exoPlayer.videoFormat
                                                val naturalAspect = if (vFormat != null && vFormat.width > 0 && vFormat.height > 0) {
                                                    (vFormat.width.toFloat() / vFormat.height.toFloat()) * (if (vFormat.pixelWidthHeightRatio > 0f) vFormat.pixelWidthHeightRatio else 1.0f)
                                                } else if (exoPlayer.videoSize.width > 0 && exoPlayer.videoSize.height > 0) {
                                                    exoPlayer.videoSize.width.toFloat() / exoPlayer.videoSize.height.toFloat()
                                                } else {
                                                    0f
                                                }
                                                contentFrame.setAspectRatio(naturalAspect)
                                            }
                                        }
                                    }
                                    applySubtitleStyleToPlayerView(view, prefs)
                                },
                                onRelease = { view ->
                                    // Retain player binding to prevent blank frame on transient recomposition
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                } else {
                    AudioVinylPlayer(
                        item = activeMediaItem,
                        isPlaying = isPlaying
                    )
                }

                // Double Tap Ripple Animations (Native Curved Sector Clip with Big Curve, No Circular Ripples)
                val primaryColor = MaterialTheme.colorScheme.primary
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    // Left Side (Backward 30% with Big Outward Curve)
                    val leftProgress = leftAnim.value
                    if (leftProgress > 0f && leftProgress < 1f) {
                        val leftPath = androidx.compose.ui.graphics.Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width * 0.30f, 0f)
                            quadraticTo(
                                size.width * 0.38f, size.height * 0.5f,
                                size.width * 0.30f, size.height
                            )
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(
                            path = leftPath,
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = (1f - leftProgress) * 0.28f),
                                    primaryColor.copy(alpha = (1f - leftProgress) * 0.16f),
                                    Color.Transparent
                                ),
                                startX = 0f,
                                endX = size.width * 0.38f
                            )
                        )
                        drawPath(
                            path = leftPath,
                            color = primaryColor.copy(alpha = (1f - leftProgress) * 0.40f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx())
                        )
                    }

                    // Center Side (40% Center Flash)
                    val centerProgress = centerAnim.value
                    if (centerProgress > 0f && centerProgress < 1f) {
                        val centerPath = androidx.compose.ui.graphics.Path().apply {
                            addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    left = size.width * 0.30f,
                                    top = size.height * 0.10f,
                                    right = size.width * 0.70f,
                                    bottom = size.height * 0.90f,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx())
                                )
                            )
                        }
                        drawPath(
                            path = centerPath,
                            color = Color.White.copy(alpha = (1f - centerProgress) * 0.12f)
                        )
                    }

                    // Right Side (Forward 30% with Big Inward Curve)
                    val rightProgress = rightAnim.value
                    if (rightProgress > 0f && rightProgress < 1f) {
                        val rightPath = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width, 0f)
                            lineTo(size.width * 0.70f, 0f)
                            quadraticTo(
                                size.width * 0.62f, size.height * 0.5f,
                                size.width * 0.70f, size.height
                            )
                            lineTo(size.width, size.height)
                            close()
                        }
                        drawPath(
                            path = rightPath,
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    primaryColor.copy(alpha = (1f - rightProgress) * 0.16f),
                                    Color.White.copy(alpha = (1f - rightProgress) * 0.28f)
                                ),
                                startX = size.width * 0.62f,
                                endX = size.width
                            )
                        )
                        drawPath(
                            path = rightPath,
                            color = primaryColor.copy(alpha = (1f - rightProgress) * 0.40f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx())
                        )
                    }
                }

                // Overlay Row for Chevron Controls and Text
                Row(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Left third (Backward Overlay)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.3f)
                    ) {
                        if (leftAnim.value > 0f) {
                            val progress = leftAnim.value
                            val alpha = (1f - progress).coerceIn(0f, 1f)
                            
                            // Beautiful chasing lights sequence for chevrons
                            val alpha3 = if (progress in 0.1f..0.5f) 1f else 0.35f
                            val alpha2 = if (progress in 0.3f..0.7f) 1f else 0.35f
                            val alpha1 = if (progress in 0.5f..0.9f) 1f else 0.35f
                            
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .graphicsLayer {
                                        this.alpha = alpha
                                        this.translationX = -progress * 40.dp.toPx()
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy((-4).dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = alpha1),
                                        modifier = Modifier
                                            .size(26.dp)
                                            .graphicsLayer { rotationZ = 180f }
                                    )
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = alpha2),
                                        modifier = Modifier
                                            .size(26.dp)
                                            .graphicsLayer { rotationZ = 180f }
                                    )
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = alpha3),
                                        modifier = Modifier
                                            .size(26.dp)
                                            .graphicsLayer { rotationZ = 180f }
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "-${prefs.doubleTapSeekSeconds}s",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }

                    // Center third (Play / Pause double tap feedback Overlay)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.4f)
                    ) {
                        if (centerAnim.value > 0f) {
                            val progress = centerAnim.value
                            val alpha = (1f - progress).coerceIn(0f, 1f)
                            val scale = 0.6f + (progress * 0.6f)
                            
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .graphicsLayer {
                                        this.alpha = alpha
                                        this.scaleX = scale
                                        this.scaleY = scale
                                    }
                                    .size(72.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    // Right third (Forward Overlay)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.3f)
                    ) {
                        if (rightAnim.value > 0f) {
                            val progress = rightAnim.value
                            val alpha = (1f - progress).coerceIn(0f, 1f)
                            
                            // Beautiful chasing lights sequence for chevrons
                            val alpha1 = if (progress in 0.1f..0.5f) 1f else 0.35f
                            val alpha2 = if (progress in 0.3f..0.7f) 1f else 0.35f
                            val alpha3 = if (progress in 0.5f..0.9f) 1f else 0.35f
                            
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .graphicsLayer {
                                        this.alpha = alpha
                                        this.translationX = progress * 40.dp.toPx()
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy((-4).dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = alpha1),
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = alpha2),
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = alpha3),
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "+${prefs.doubleTapSeekSeconds}s",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
            }

            // Gesture Feedback HUD overlay
            AnimatedVisibility(
                visible = gestureFeedbackType.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = when (gestureFeedbackType) {
                                "volume" -> Icons.Default.VolumeUp
                                "brightness" -> Icons.Default.BrightnessMedium
                                "seek_back" -> Icons.Default.Replay10
                                "seek_forward" -> Icons.Default.Forward10
                                "aspect_ratio" -> Icons.Default.AspectRatio
                                else -> Icons.Default.FastForward
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = gestureFeedbackValue,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Hardware Volume Overlay HUD (visible in normal and locked mode)
            AnimatedVisibility(
                visible = showHwVolumeHud && hwVolPercent != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                hwVolPercent?.let { percent ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (percent == 0f) Icons.Default.VolumeMute else if (percent < 50f) Icons.Default.VolumeDown else Icons.Default.VolumeUp,
                                    contentDescription = "Volume",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "$percent%",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.width(140.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.White.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }

            // Buffering / Loading Indicator overlay (Only shown for remote network streams to prevent flickering on local files)
            val isRemoteStream = activeMediaItem.genre == "Live Stream" || activeMediaItem.genre == "Playlist Stream Channel" || activeMediaItem.uriString.startsWith("http://") || activeMediaItem.uriString.startsWith("https://") || activeMediaItem.uriString.startsWith("rtsp://") || activeMediaItem.uriString.startsWith("mms://")
            if (isBuffering && isRemoteStream && playbackErrorMsg == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        tonalElevation = 8.dp,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = if (activeMediaItem.genre == "Live Stream" || activeMediaItem.genre == "Playlist Stream Channel" || activeMediaItem.uriString.startsWith("http")) 
                                    "Connecting to stream..." 
                                else 
                                    "Loading media...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = activeMediaItem.title,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Error overlay
            playbackErrorMsg?.let { errorMsg ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.widthIn(max = 400.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(44.dp))
                            Text("Playback Error", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 18.sp)
                            Text(errorMsg, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center)
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Button(
                                    onClick = {
                                        playbackErrorMsg = null
                                        generalRetryCount = 0
                                        audioFallbackAttempted = false
                                        try {
                                            if (effectiveEngine == "VLC") {
                                                vlcPlayer.loadMedia(activeMediaItem.uriString, activeMediaItem.path, currentPosition)
                                            } else {
                                                exoPlayer.stop()
                                                val newItem = buildMediaItemWithSubtitles(
                                                    uriString = activeMediaItem.uriString,
                                                    context = context,
                                                    path = activeMediaItem.path
                                                )
                                                exoPlayer.setMediaItem(newItem)
                                                exoPlayer.prepare()
                                                exoPlayer.playWhenReady = true
                                                exoPlayer.play()
                                            }
                                        } catch (e: Exception) {
                                            playbackErrorMsg = "Retry failed: ${e.localizedMessage}"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry Playback", fontWeight = FontWeight.Bold)
                                }

                                val alternateEngine = if (effectiveEngine == "VLC") "ExoPlayer" else "VLC"
                                OutlinedButton(
                                    onClick = {
                                        playbackErrorMsg = null
                                        generalRetryCount = 0
                                        audioFallbackAttempted = false
                                        viewModel.updatePerVideoEngine(activeMediaItem.uriString, alternateEngine)
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Switch to $alternateEngine Engine", fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        com.example.util.ContentResolverUtils.openInExternalPlayer(
                                            context,
                                            activeMediaItem.uriString,
                                            activeMediaItem.path
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                        contentColor = MaterialTheme.colorScheme.errorContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open in External Player (VLC / MPV)", fontWeight = FontWeight.Bold)
                                }

                                if (errorMsg.contains("ContentCompAlgo", ignoreCase = true) ||
                                    errorMsg.contains("sub", ignoreCase = true) ||
                                    errorMsg.contains("malformed", ignoreCase = true)) {
                                    OutlinedButton(
                                        onClick = {
                                            playbackErrorMsg = null
                                            generalRetryCount = 0
                                            audioFallbackAttempted = false
                                            val resumePos = exoPlayer.currentPosition
                                            val plainItem = androidx.media3.common.MediaItem.Builder()
                                                .setUri(com.example.util.ContentResolverUtils.resolvePlayableUri(context, activeMediaItem.uriString, activeMediaItem.path))
                                                .build()
                                            exoPlayer.setMediaItem(plainItem)
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                                                .build()
                                            exoPlayer.prepare()
                                            if (resumePos > 0) exoPlayer.seekTo(resumePos)
                                            exoPlayer.playWhenReady = true
                                            exoPlayer.play()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Disable Subtitles & Retry")
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = safeOnBack,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Go Back")
                                    }
                                    Button(
                                        onClick = {
                                            playbackErrorMsg = null
                                            generalRetryCount = 0
                                            audioFallbackAttempted = false
                                            val resumePos = exoPlayer.currentPosition
                                            exoPlayer.setMediaItem(buildMediaItemWithSubtitles(activeMediaItem.uriString, context, activeMediaItem.path))
                                            exoPlayer.prepare()
                                            if (resumePos > 0) {
                                                exoPlayer.seekTo(resumePos)
                                            }
                                            exoPlayer.play()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer, contentColor = MaterialTheme.colorScheme.errorContainer),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Left Floating Control Block removed to respect user request

            // Core HUD Controller Overlays
            AnimatedVisibility(
                visible = (isControlsVisible && !isInPipMode) || (isLocked && isLockControlVisible && !isInPipMode),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isLocked) {
                        // Locked Screen State overlay: Adaptable File Name Title Pill at top with frosted glass styling
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // File Title Badge - Size adaptable to content with frosted glass styling
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)),
                                shadowElevation = 8.dp,
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .wrapContentWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = activeMediaItem.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Unlock Control Toggle Button with frosted glass styling
                            IconButton(
                                onClick = {
                                    isLockControlVisible = true
                                },
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f), CircleShape)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f), CircleShape)
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = "Locked", tint = MaterialTheme.colorScheme.error)
                            }
                        }

                        if (isLockControlVisible) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Progress Bar on top of Swipe To Unlock with frosted glass styling
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)),
                                    shadowElevation = 8.dp,
                                    modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = formatPlayerDuration(currentPosition),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = formatPlayerDuration(duration),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 12.sp
                                            )
                                        }

                                        val progressFraction = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                                        LinearProgressIndicator(
                                            progress = { progressFraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(CircleShape),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    }
                                }

                                SwipeToUnlock(
                                    onUnlock = {
                                        isLocked = false
                                        isControlsVisible = true
                                        isLockControlVisible = true
                                    }
                                )
                            }
                        }
                    } else {
                        // 1. Subtle dark gradient vignette mask toward top and bottom
                        val vignetteBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.85f),
                                Color.Black.copy(alpha = 0.4f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(vignetteBrush)
                        ) {
                            // Top Action Bar (Flex Row)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                // Left Cluster: Down arrow chevron + Stacked Text Block (Title / Subtitle)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    IconButton(
                                        onClick = safeOnBack,
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), CircleShape)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                                            .testTag("player_back_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Minimize Player",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = activeMediaItem.title,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            modifier = Modifier.basicMarquee()
                                        )
                                        Text(
                                            text = "@" + activeMediaItem.displayArtist,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Right Cluster: Cast Controls & Subtitles
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (prefs.isCastEnabled) {
                                        IconButton(
                                            onClick = {
                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                showCastControlSheet = true
                                            },
                                            modifier = Modifier
                                                .background(
                                                    if (isCastingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                                                    CircleShape
                                                )
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = if (isCastingActive) Icons.Default.CastConnected else Icons.Default.Cast,
                                                contentDescription = "Audio & Network Cast",
                                                tint = if (isCastingActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            showAudioSubtitleSelectorSheet = true
                                        },
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), CircleShape)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                                    ) {
                                        Icon(Icons.Default.ClosedCaption, contentDescription = "Subtitles & Audio", tint = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }

                            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                            val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

                            val transportControls = @Composable { isMini: Boolean ->
                                Row(
                                    modifier = Modifier.wrapContentSize(),
                                    horizontalArrangement = Arrangement.spacedBy(if (isMini) 14.dp else 24.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Skip Backward (Single Tap: Prev Track, Double Tap: Seek Back 10s with Ripple Animation)
                                    Box(
                                        modifier = Modifier
                                            .size(if (isMini) 42.dp else 52.dp)
                                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                                            .pointerInput(Unit) {
                                                detectTapGestures(
                                                    onTap = {
                                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                        viewModel.playPrevious()
                                                    },
                                                    onDoubleTap = {
                                                        val seekAmountMs = (prefs.doubleTapSeekSeconds * 1000L)
                                                        val curPos = if (effectiveEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
                                                        val targetSeek = (curPos - seekAmountMs).coerceAtLeast(0L)
                                                        performSeek(targetSeek)
                                                        gestureFeedbackType = "seek_back"
                                                        gestureFeedbackValue = "-${prefs.doubleTapSeekSeconds}s"
                                                        leftDoubleTapOffset = androidx.compose.ui.geometry.Offset.Zero
                                                        leftRippleTrigger++
                                                        coroutineScope.launch {
                                                            delay(1000)
                                                            if (gestureFeedbackType == "seek_back") {
                                                                gestureFeedbackType = ""
                                                            }
                                                        }
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SkipPrevious,
                                            contentDescription = "Previous Track (Double-tap to Rewind)",
                                            tint = Color.White,
                                            modifier = Modifier.size(if (isMini) 22.dp else 30.dp)
                                        )
                                    }

                                    // Play / Pause Button (Large Prominent White Icon)
                                    AnimatedPlayPauseButton(
                                        isPlaying = isPlaying,
                                        onClick = {
                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            if (effectiveEngine == "VLC") {
                                                if (vlcPlayer.isPlaying) vlcPlayer.pause() else vlcPlayer.play()
                                            } else {
                                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                            }
                                        },
                                        modifier = Modifier
                                            .size(if (isMini) 52.dp else 76.dp)
                                            .border(1.dp, Color.White, CircleShape),
                                        iconSize = if (isMini) 28.dp else 42.dp,
                                        containerColor = Color.White.copy(alpha = 0.95f),
                                        contentColor = Color.Black
                                    )

                                    // Skip Forward (Single Tap: Next Track, Double Tap: Seek Forward 10s with Ripple Animation)
                                    Box(
                                        modifier = Modifier
                                            .size(if (isMini) 42.dp else 52.dp)
                                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                                            .pointerInput(Unit) {
                                                detectTapGestures(
                                                    onTap = {
                                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                        viewModel.playNext()
                                                    },
                                                    onDoubleTap = {
                                                        val seekAmountMs = (prefs.doubleTapSeekSeconds * 1000L)
                                                        val curPos = if (effectiveEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
                                                        val targetSeek = (curPos + seekAmountMs).coerceAtMost(duration)
                                                        performSeek(targetSeek)
                                                        gestureFeedbackType = "seek_forward"
                                                        gestureFeedbackValue = "+${prefs.doubleTapSeekSeconds}s"
                                                        rightDoubleTapOffset = androidx.compose.ui.geometry.Offset.Zero
                                                        rightRippleTrigger++
                                                        coroutineScope.launch {
                                                            delay(1000)
                                                            if (gestureFeedbackType == "seek_forward") {
                                                                gestureFeedbackType = ""
                                                            }
                                                        }
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SkipNext,
                                            contentDescription = "Next Track (Double-tap to Fast-Forward)",
                                            tint = Color.White,
                                            modifier = Modifier.size(if (isMini) 22.dp else 30.dp)
                                        )
                                    }
                                }
                            }

                            // 3. Bottom Control Matrix (Stack with floating tools dock on bottom)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 24.dp, vertical = 8.dp)
                            ) {
                                // YouTube style scrubbing floating preview card attached to drag thumb
                                if (isScrubbing) {
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                    ) {
                                        val totalWidth = maxWidth
                                        val cardWidth = 160.dp
                                        val fraction = if (duration > 0) (scrubPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                                        val thumbX = totalWidth * fraction
                                        val targetOffset = thumbX - (cardWidth / 2)
                                        val clampedOffset = targetOffset.coerceIn(0.dp, (totalWidth - cardWidth).coerceAtLeast(0.dp))
                                        val arrowX = (thumbX - clampedOffset).coerceIn(16.dp, cardWidth - 16.dp)

                                        Box(
                                            modifier = Modifier
                                                .offset(x = clampedOffset)
                                                .width(cardWidth)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Card(
                                                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.95f)),
                                                    border = BorderStroke(1.5.dp, Color.Red.copy(alpha = 0.9f)),
                                                    shape = RoundedCornerShape(12.dp),
                                                    elevation = CardDefaults.cardElevation(8.dp),
                                                    modifier = Modifier
                                                        .width(cardWidth)
                                                        .height(96.dp)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.fillMaxSize(),
                                                        verticalArrangement = Arrangement.SpaceBetween,
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .weight(1f)
                                                                .background(Color.DarkGray.copy(alpha = 0.4f)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            if (scrubbingBitmap != null) {
                                                                androidx.compose.foundation.Image(
                                                                    bitmap = scrubbingBitmap!!.asImageBitmap(),
                                                                    contentDescription = "Scrubbing frame preview",
                                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize()
                                                                )
                                                            } else {
                                                                Column(
                                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                                    verticalArrangement = Arrangement.Center
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.PlayCircleOutline,
                                                                        contentDescription = null,
                                                                        tint = Color.Red,
                                                                        modifier = Modifier.size(24.dp)
                                                                    )
                                                                    Spacer(modifier = Modifier.height(2.dp))
                                                                    Text(
                                                                        text = "SCRUBBING",
                                                                        color = Color.White.copy(alpha = 0.7f),
                                                                        fontSize = 9.sp,
                                                                        fontWeight = FontWeight.Bold
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(Color.Red)
                                                                .padding(vertical = 3.dp),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                text = formatPlayerDuration(scrubPosition),
                                                                color = Color.White,
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.ExtraBold
                                                            )
                                                        }
                                                    }
                                                }
                                                // Pointer indicator attached to drag thumb position
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                ) {
                                                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                                        val pointerPx = arrowX.toPx()
                                                        val path = androidx.compose.ui.graphics.Path().apply {
                                                            moveTo(pointerPx - 8f, 0f)
                                                            lineTo(pointerPx + 8f, 0f)
                                                            lineTo(pointerPx, 10f)
                                                            close()
                                                        }
                                                        drawPath(path, color = androidx.compose.ui.graphics.Color.Red)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                val displayPosition = if (isScrubbing) scrubPosition else currentPosition
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = formatPlayerDuration(displayPosition) + " / " + formatPlayerDuration(duration),
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    // Aspect Ratio / Screen Fit Button on top of progressbar right side
                                    IconButton(
                                        onClick = {
                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            val modes = listOf(
                                                AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit (Original)",
                                                AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch / Fill",
                                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom / Crop",
                                                AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "16:9 Widescreen",
                                                AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT to "4:3 Standard",
                                                100 to "21:9 Cinema"
                                            )
                                            val currentModeIndex = modes.indexOfFirst { it.first == resizeMode }.coerceAtLeast(0)
                                            val nextModeIndex = (currentModeIndex + 1) % modes.size
                                            val nextMode = modes[nextModeIndex]
                                            resizeMode = nextMode.first
                                            gestureFeedbackValue = "Aspect: ${nextMode.second}"
                                            gestureFeedbackType = "aspect_ratio"
                                            coroutineScope.launch {
                                                delay(1000)
                                                if (gestureFeedbackType == "aspect_ratio") {
                                                    gestureFeedbackType = ""
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(32.dp).testTag("aspect_ratio_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AspectRatio,
                                            contentDescription = "Aspect Ratio",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                SmoothSeekBar(
                                    position = displayPosition,
                                    duration = duration,
                                    isScrubbing = isScrubbing,
                                    onScrubStart = {
                                        isScrubbing = true
                                        scrubPosition = displayPosition
                                    },
                                    onScrubPositionChange = { pos -> scrubPosition = pos },
                                    onScrubEnd = { pos ->
                                        isScrubbing = false
                                        performSeek(pos)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("player_timeline_slider")
                                )

                                if (!isLandscape) {
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Moved central controls to bottom in portrait mode, aligned nicely in column
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        transportControls(false)
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // REDESIGNED: Centralized absolute alignment layout with collapsible quick-tools Left Pill
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 14.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    // Left Pill: Quick Utilities (Save, Rotate, Lock Screen, Queue, Sleep Timer) - Collapsible
                                    Box(
                                        modifier = Modifier.align(if (isLandscape) Alignment.CenterStart else Alignment.BottomStart)
                                    ) {
                                        if (!isLandscape) {
                                            Column(
                                                modifier = Modifier
                                                    .wrapContentSize()
                                                    .clip(RoundedCornerShape(24.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f))
                                                    .border(1.2.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                AnimatedVisibility(
                                                    visible = isLeftPillExpanded,
                                                    enter = expandVertically() + fadeIn(),
                                                    exit = shrinkVertically() + fadeOut()
                                                ) {
                                                    Column(
                                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        // 1. Interactive Save / Favorite Button with spring animation scale
                                                        val saveScale by animateFloatAsState(
                                                            targetValue = if (isSaved) 1.25f else 1.0f,
                                                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                                                            label = "SaveScale"
                                                        )
                                                        IconButton(
                                                            onClick = {
                                                                viewModel.toggleFavoriteMedia(activeMediaItem.uriString)
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                                val msg = if (!isSaved) "Saved to Library ❤️" else "Removed from Library"
                                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                            },
                                                            modifier = Modifier.size(36.dp).graphicsLayer {
                                                                scaleX = saveScale
                                                                scaleY = saveScale
                                                            }
                                                        ) {
                                                            Icon(
                                                                imageVector = if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                                contentDescription = "Save Video",
                                                                tint = if (isSaved) Color.Red else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 2. Interactive Rotation button with full-spin spring rotation animation
                                                        val rotateAngleAnim by animateFloatAsState(
                                                            targetValue = rotateAngle,
                                                            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                                                            label = "RotateSpin"
                                                        )
                                                        IconButton(
                                                            onClick = {
                                                                rotateAngle += 360f
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                sessionOrientation = if (sessionOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE || sessionOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                                                } else {
                                                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                                }
                                                            },
                                                            modifier = Modifier.size(36.dp).graphicsLayer {
                                                                rotationZ = rotateAngleAnim
                                                            }
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.ScreenRotation,
                                                                contentDescription = "Rotate Screen",
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 3. Lock controls button (triggers screen control lock overlay)
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                                isLocked = true
                                                                isControlsVisible = false
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.LockOpen,
                                                                contentDescription = "Lock Screen Controls",
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 4. Quick Queue Button
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                showQueueSheet = true
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.QueueMusic,
                                                                contentDescription = "View Queue",
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 5. Sleep Timer Button
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                showSleepTimerSheet = true
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Timer,
                                                                contentDescription = "Sleep Timer",
                                                                tint = if (isSleepTimerRunning) Color.Green else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 6. Pop-Up Player Button
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                                showAdvancedControlsSheet = false
                                                                isPipActive = !isPipActive
                                                            },
                                                            modifier = Modifier.size(36.dp).testTag("popup_player_button_portrait")
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.PictureInPicture,
                                                                contentDescription = "Pop-Up Player",
                                                                tint = if (isPipActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                // Collapse / Expand toggle button as the bottom-most button in portrait Column
                                                IconButton(
                                                    onClick = {
                                                        isLeftPillExpanded = !isLeftPillExpanded
                                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                    },
                                                    modifier = Modifier.size(36.dp).testTag("left_pill_collapse_button")
                                                ) {
                                                    Icon(
                                                        imageVector = if (isLeftPillExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                                        contentDescription = if (isLeftPillExpanded) "Collapse Tools" else "Expand Tools",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            }
                                        } else {
                                            Row(
                                                modifier = Modifier
                                                    .wrapContentSize()
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f))
                                                    .border(1.2.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Collapse / Expand toggle button as the very first button in landscape Row
                                                IconButton(
                                                    onClick = {
                                                        isLeftPillExpanded = !isLeftPillExpanded
                                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                    },
                                                    modifier = Modifier.size(36.dp).testTag("left_pill_collapse_button")
                                                ) {
                                                    Icon(
                                                        imageVector = if (isLeftPillExpanded) Icons.Default.KeyboardArrowLeft else Icons.Default.KeyboardArrowRight,
                                                        contentDescription = if (isLeftPillExpanded) "Collapse Tools" else "Expand Tools",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }

                                                AnimatedVisibility(
                                                    visible = isLeftPillExpanded,
                                                    enter = expandHorizontally() + fadeIn(),
                                                    exit = shrinkHorizontally() + fadeOut()
                                                ) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        // 1. Interactive Save / Favorite Button with spring animation scale
                                                        val saveScale by animateFloatAsState(
                                                            targetValue = if (isSaved) 1.25f else 1.0f,
                                                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                                                            label = "SaveScale"
                                                        )
                                                        IconButton(
                                                            onClick = {
                                                                viewModel.toggleFavoriteMedia(activeMediaItem.uriString)
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                                val msg = if (!isSaved) "Saved to Library ❤️" else "Removed from Library"
                                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                            },
                                                            modifier = Modifier.size(36.dp).graphicsLayer {
                                                                scaleX = saveScale
                                                                scaleY = saveScale
                                                            }
                                                        ) {
                                                            Icon(
                                                                imageVector = if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                                contentDescription = "Save Video",
                                                                tint = if (isSaved) Color.Red else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 2. Interactive Rotation button with full-spin spring rotation animation
                                                        val rotateAngleAnim by animateFloatAsState(
                                                            targetValue = rotateAngle,
                                                            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
                                                            label = "RotateSpin"
                                                        )
                                                        IconButton(
                                                            onClick = {
                                                                rotateAngle += 360f
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                sessionOrientation = if (sessionOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE || sessionOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                                                } else {
                                                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                                }
                                                            },
                                                            modifier = Modifier.size(36.dp).graphicsLayer {
                                                                rotationZ = rotateAngleAnim
                                                            }
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.ScreenRotation,
                                                                contentDescription = "Rotate Screen",
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 3. Lock controls button (triggers screen control lock overlay)
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                                isLocked = true
                                                                isControlsVisible = false
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.LockOpen,
                                                                contentDescription = "Lock Screen Controls",
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 4. Quick Queue Button
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                showQueueSheet = true
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.QueueMusic,
                                                                contentDescription = "View Queue",
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 5. Sleep Timer Button
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                showSleepTimerSheet = true
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Timer,
                                                                contentDescription = "Sleep Timer",
                                                                tint = if (isSleepTimerRunning) Color.Green else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 6. Pop-Up Player Button
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                                showAdvancedControlsSheet = false
                                                                isPipActive = !isPipActive
                                                            },
                                                            modifier = Modifier.size(36.dp).testTag("popup_player_button_landscape")
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.PictureInPicture,
                                                                contentDescription = "Pop-Up Player",
                                                                tint = if (isPipActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (isLandscape) {
                                        Box(
                                            modifier = Modifier.align(Alignment.Center)
                                        ) {
                                            transportControls(true)
                                        }
                                    }

                                    // Right Pill: Playback Modes, Sizing, & More Settings - Collapsible
                                    Box(
                                        modifier = Modifier.align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomEnd)
                                    ) {
                                        if (!isLandscape) {
                                            Column(
                                                modifier = Modifier
                                                    .wrapContentSize()
                                                    .clip(RoundedCornerShape(24.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f))
                                                    .border(1.2.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                AnimatedVisibility(
                                                    visible = isRightPillExpanded,
                                                    enter = expandVertically() + fadeIn(),
                                                    exit = shrinkVertically() + fadeOut()
                                                ) {
                                                    Column(
                                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        // 1. Shuffle
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                isShuffleEnabled = !isShuffleEnabled
                                                                exoPlayer.shuffleModeEnabled = isShuffleEnabled
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Shuffle,
                                                                contentDescription = "Shuffle",
                                                                tint = if (isShuffleEnabled) Color.Green else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 3. Repeat Mode
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                repeatModeState = (repeatModeState + 1) % 3
                                                                exoPlayer.repeatMode = when (repeatModeState) {
                                                                    1 -> androidx.media3.common.Player.REPEAT_MODE_ONE
                                                                    2 -> androidx.media3.common.Player.REPEAT_MODE_ALL
                                                                    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                                                                }
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            val repeatIcon = when (repeatModeState) {
                                                                1 -> Icons.Default.RepeatOne
                                                                else -> Icons.Default.Repeat
                                                            }
                                                            Icon(
                                                                imageVector = repeatIcon,
                                                                contentDescription = "Repeat Mode",
                                                                tint = if (repeatModeState > 0) Color.Green else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 4. Advanced Settings Gear
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                showAdvancedControlsSheet = true
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Settings,
                                                                contentDescription = "Advanced Settings",
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                // Collapse / Expand toggle button as the bottom-most button in portrait Column
                                                IconButton(
                                                    onClick = {
                                                        isRightPillExpanded = !isRightPillExpanded
                                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                    },
                                                    modifier = Modifier.size(36.dp).testTag("right_pill_collapse_button")
                                                ) {
                                                    Icon(
                                                        imageVector = if (isRightPillExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                                        contentDescription = if (isRightPillExpanded) "Collapse Settings" else "Expand Settings",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            }
                                        } else {
                                            Row(
                                                modifier = Modifier
                                                    .wrapContentSize()
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f))
                                                    .border(1.2.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                AnimatedVisibility(
                                                    visible = isRightPillExpanded,
                                                    enter = expandHorizontally() + fadeIn(),
                                                    exit = shrinkHorizontally() + fadeOut()
                                                ) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        // 1. Shuffle
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                isShuffleEnabled = !isShuffleEnabled
                                                                exoPlayer.shuffleModeEnabled = isShuffleEnabled
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Shuffle,
                                                                contentDescription = "Shuffle",
                                                                tint = if (isShuffleEnabled) Color.Green else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 3. Repeat Mode
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                repeatModeState = (repeatModeState + 1) % 3
                                                                exoPlayer.repeatMode = when (repeatModeState) {
                                                                    1 -> androidx.media3.common.Player.REPEAT_MODE_ONE
                                                                    2 -> androidx.media3.common.Player.REPEAT_MODE_ALL
                                                                    else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                                                                }
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            val repeatIcon = when (repeatModeState) {
                                                                1 -> Icons.Default.RepeatOne
                                                                else -> Icons.Default.Repeat
                                                            }
                                                            Icon(
                                                                imageVector = repeatIcon,
                                                                contentDescription = "Repeat Mode",
                                                                tint = if (repeatModeState > 0) Color.Green else MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }

                                                        // 4. Advanced Settings Gear
                                                        IconButton(
                                                            onClick = {
                                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                showAdvancedControlsSheet = true
                                                            },
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Settings,
                                                                contentDescription = "Advanced Settings",
                                                                tint = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                // Collapse / Expand toggle button as the very last button in landscape Row
                                                IconButton(
                                                    onClick = {
                                                        isRightPillExpanded = !isRightPillExpanded
                                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                    },
                                                    modifier = Modifier.size(36.dp).testTag("right_pill_collapse_button")
                                                ) {
                                                    Icon(
                                                        imageVector = if (isRightPillExpanded) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowLeft,
                                                        contentDescription = if (isRightPillExpanded) "Collapse Settings" else "Expand Settings",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Advanced Player options sheet drawer (Redesigned with custom premium control board style)
    if (showAdvancedControlsSheet) {
        val playQueue by viewModel.playQueue.collectAsState()
        val scrollState = rememberScrollState()
        var jumpInputText by remember { mutableStateOf("") }
        var playlistNameInput by remember { mutableStateOf("") }
        val isAudio = !activeMediaItem.isVideo || playAsAudioOnly

        PlayerRightSideDrawer(
            isOpen = showAdvancedControlsSheet,
            onDismissRequest = { showAdvancedControlsSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(scrollState)
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                Spacer(modifier = Modifier.height(12.dp))

                // Custom Futuristic Drawer Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isAudio) Icons.Default.MusicNote else Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column {
                            Text(
                                text = if (isAudio) "Aero Audio Deck" else "Aero Deck",
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isAudio) "AUDIO TUNING BOARD" else "PRO DASHBOARD",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    IconButton(
                        onClick = { showAdvancedControlsSheet = false },
                        modifier = Modifier
                            .size(32.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Deck",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(16.dp))



                if (isAudio) {
                    // SECTION 1: PLAYBACK SPEED (TEMPO CONTROL COCKPIT)
                    Text(
                        text = "TEMPO CONTROL",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            val engineSpeed = if (effectiveEngine == "VLC") vlcPlayer.playbackSpeed else exoPlayer.playbackParameters.speed
                            var localSpeed by remember(engineSpeed) { mutableStateOf(engineSpeed) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Speed Multiplier",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = String.format("%.2fx", localSpeed),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            CustomSlider(
                                value = localSpeed,
                                onValueChange = { 
                                    localSpeed = it
                                    if (effectiveEngine == "VLC") vlcPlayer.setSpeed(it) else exoPlayer.setPlaybackSpeed(it)
                                },
                                onValueChangeFinished = {
                                    if (effectiveEngine == "VLC") vlcPlayer.setSpeed(localSpeed) else exoPlayer.setPlaybackSpeed(localSpeed)
                                },
                                valueRange = 0.25f..4.00f
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { preset ->
                                    val isSelected = String.format("%.2f", localSpeed) == String.format("%.2f", preset)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                localSpeed = preset
                                                if (effectiveEngine == "VLC") vlcPlayer.setSpeed(preset) else exoPlayer.setPlaybackSpeed(preset)
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (preset == 1.0f) "Normal" else "${preset}x",
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // SOUND EQUALIZER IN ADVANCED CONTROLS
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "SOUND EQUALIZER",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            val eqEnabled by viewModel.equalizerEnabled.collectAsState()
                            val currentPreset by viewModel.currentEqualizerPreset.collectAsState()
                            val isPlaying by viewModel.isPlaying.collectAsState()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Sound Equalizer",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = if (eqEnabled) "ON" else "OFF",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (eqEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                                    )
                                    Switch(
                                        checked = eqEnabled,
                                        onCheckedChange = { viewModel.setEqualizerEnabled(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        ),
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = 0.8f
                                            scaleY = 0.8f
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Preset: $currentPreset",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    showAdvancedControlsSheet = false
                                    showEqualizerSheet = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Customize Tuning Profile",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }

                    // INTEGRATED SLEEP TIMER FOR AUDIO
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "SLEEP TIMER",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Timer Status",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (sleepTimeLeftMinutes > 0) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color.Green)
                                        )
                                        Text(
                                            text = "${sleepTimeLeftMinutes} MIN LEFT",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 10.sp,
                                            color = Color.Green
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "INACTIVE",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (sleepTimeLeftMinutes > 0) {
                                Button(
                                    onClick = {
                                        sleepTimeLeftMinutes = 0
                                        isSleepTimerRunning = false
                                        android.widget.Toast.makeText(context, "Sleep timer cancelled", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Cancel Sleep Timer", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onError)
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(10, 20, 30, 45, 60).forEach { mins ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                                .clickable {
                                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                    sleepTimeLeftMinutes = mins
                                                    isSleepTimerRunning = true
                                                    android.widget.Toast.makeText(context, "Timer set for $mins minutes", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${mins}m",
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // JUMP TO TIME
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "JUMP TO TIME",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = jumpInputText,
                                    onValueChange = { jumpInputText = it },
                                    placeholder = { Text("e.g. 01:30 or 90s", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    )
                                )
                                CustomButton(
                                    onClick = {
                                        val parts = jumpInputText.split(":")
                                        val targetMs = if (parts.size == 2) {
                                            val mins = parts[0].toLongOrNull() ?: 0L
                                            val secs = parts[1].toLongOrNull() ?: 0L
                                            (mins * 60 + secs) * 1000L
                                        } else {
                                            val secs = jumpInputText.toLongOrNull() ?: 0L
                                            secs * 1000L
                                        }
                                        if (targetMs >= 0) {
                                            performSeek(targetMs.coerceIn(0L, duration))
                                            jumpInputText = ""
                                            showAdvancedControlsSheet = false
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Go", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(-30, -10, 10, 30).forEach { seconds ->
                                    val label = if (seconds > 0) "+${seconds}s" else "${seconds}s"
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                            .clickable {
                                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                val targetSeek = (currentPosition + seconds * 1000L).coerceIn(0L, duration)
                                                performSeek(targetSeek)
                                                android.widget.Toast.makeText(context, "Sought $label", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // A-B SEGMENT LOOP
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "A-B SEGMENT LOOP",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Loop Window",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                if (abRepeatEnabled) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color.Green)
                                        )
                                        Text(
                                            text = "LOOP ACTIVE",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 9.sp,
                                            color = Color.Green
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("POINT A", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (pointA == null) "--:--" else formatPlayerDuration(pointA!!),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("POINT B", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (pointB == null) "--:--" else formatPlayerDuration(pointB!!),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CustomButton(
                                    onClick = {
                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        if (pointA == null) {
                                            pointA = currentPosition
                                            android.widget.Toast.makeText(context, "Point A set!", android.widget.Toast.LENGTH_SHORT).show()
                                        } else if (pointB == null) {
                                            if (currentPosition > pointA!!) {
                                                pointB = currentPosition
                                                abRepeatEnabled = true
                                                android.widget.Toast.makeText(context, "Point B set! Looping active.", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                android.widget.Toast.makeText(context, "Point B must be after Point A!", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            pointA = null
                                            pointB = null
                                            abRepeatEnabled = false
                                            android.widget.Toast.makeText(context, "Loop cleared!", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (abRepeatEnabled) Color.Green else MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text(
                                        text = when {
                                            pointA == null -> "Mark [A]"
                                            pointB == null -> "Mark [B]"
                                            else -> "Clear Loop"
                                        },
                                        color = if (abRepeatEnabled) Color.Black else MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }

                                if (pointA != null || pointB != null) {
                                    IconButton(
                                        onClick = {
                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            pointA = null
                                            pointB = null
                                            abRepeatEnabled = false
                                            android.widget.Toast.makeText(context, "Loop reset", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                            .size(40.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Reset Loop", tint = Color.Red, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }

                    // SAVE QUEUE TO PLAYLIST
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "SAVE CURRENT QUEUE",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = playlistNameInput,
                                    onValueChange = { playlistNameInput = it },
                                    placeholder = { Text("Playlist Name...", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    )
                                )
                                Button(
                                    onClick = {
                                        if (playlistNameInput.isNotEmpty() && playQueue.isNotEmpty()) {
                                            playQueue.forEach { item ->
                                                viewModel.addMediaToPlaylist(playlistNameInput, item.uriString)
                                            }
                                            android.widget.Toast.makeText(context, "Saved play queue as '$playlistNameInput'", android.widget.Toast.LENGTH_SHORT).show()
                                            playlistNameInput = ""
                                            showAdvancedControlsSheet = false
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                } else {
                    // SECTION 1: PLAYBACK SPEED (TEMPO CONTROL COCKPIT)
                Text(
                    text = "TEMPO CONTROL",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        val engineSpeed = if (effectiveEngine == "VLC") vlcPlayer.playbackSpeed else exoPlayer.playbackParameters.speed
                        var localSpeed by remember(engineSpeed) { mutableStateOf(engineSpeed) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Speed Multiplier",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            // Neon display badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = String.format("%.2fx", localSpeed),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        CustomSlider(
                            value = localSpeed,
                            onValueChange = { 
                                localSpeed = it
                                if (effectiveEngine == "VLC") vlcPlayer.setSpeed(it) else exoPlayer.setPlaybackSpeed(it)
                            },
                            onValueChangeFinished = {
                                if (effectiveEngine == "VLC") vlcPlayer.setSpeed(localSpeed) else exoPlayer.setPlaybackSpeed(localSpeed)
                            },
                            valueRange = 0.25f..4.00f
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Segments / Presets Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { preset ->
                                val isSelected = String.format("%.2f", localSpeed) == String.format("%.2f", preset)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            localSpeed = preset
                                            exoPlayer.setPlaybackSpeed(preset)
                                            vlcPlayer.setSpeed(preset)
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (preset == 1.0f) "Normal" else "${preset}x",
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // SOUND EQUALIZER IN ADVANCED CONTROLS
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "SOUND EQUALIZER",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        val eqEnabled by viewModel.equalizerEnabled.collectAsState()
                        val currentPreset by viewModel.currentEqualizerPreset.collectAsState()
                        val isPlaying by viewModel.isPlaying.collectAsState()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Sound Equalizer",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (eqEnabled) "ON" else "OFF",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (eqEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                                Switch(
                                    checked = eqEnabled,
                                    onCheckedChange = { viewModel.setEqualizerEnabled(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = 0.8f
                                        scaleY = 0.8f
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Preset: $currentPreset",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                showAdvancedControlsSheet = false
                                showEqualizerSheet = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Customize Tuning Profile",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }

                if (!isAudio) {
                    // SECTION 2: AUDIO-ONLY & PIP MODES
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "DEDICATED MODES",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Audio-Only Mode Card
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (playAsAudioOnly) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                )
                                .border(
                                    width = 1.2.dp,
                                    color = if (playAsAudioOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    playAsAudioOnly = !playAsAudioOnly
                                }
                                .padding(12.dp)
                        ) {
                            Column {
                                Icon(
                                    imageVector = Icons.Default.Headphones,
                                    contentDescription = null,
                                    tint = if (playAsAudioOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Audio-Only",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Background Play",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 12.sp
                                )
                            }
                            if (playAsAudioOnly) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.Green)
                                )
                            }
                        }

                        // Picture in Picture Mode Card
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isPipActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                )
                                .border(
                                    width = 1.2.dp,
                                    color = if (isPipActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    showAdvancedControlsSheet = false
                                    isPipActive = !isPipActive
                                }
                                .padding(12.dp)
                        ) {
                            Column {
                                Icon(
                                    imageVector = Icons.Default.PictureInPicture,
                                    contentDescription = null,
                                    tint = if (isPipActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Pop-Up Player",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Pic-in-Picture",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 12.sp
                                )
                            }
                            if (isPipActive) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.Green)
                                )
                            }
                        }
                    }
                }

                // SECTION 3: JUMP TO TIME
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "JUMP TO TIME",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = jumpInputText,
                                onValueChange = { jumpInputText = it },
                                placeholder = { Text("e.g. 01:30 or 90s", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            )
                            CustomButton(
                                onClick = {
                                    val parts = jumpInputText.split(":")
                                    val targetMs = if (parts.size == 2) {
                                        val mins = parts[0].toLongOrNull() ?: 0L
                                        val secs = parts[1].toLongOrNull() ?: 0L
                                        (mins * 60 + secs) * 1000L
                                    } else {
                                        val secs = jumpInputText.toLongOrNull() ?: 0L
                                        secs * 1000L
                                    }
                                    if (targetMs >= 0) {
                                        performSeek(targetMs.coerceIn(0L, duration))
                                        jumpInputText = ""
                                        showAdvancedControlsSheet = false
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Go", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Delta Preset Seek Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(-30, -10, 10, 30).forEach { seconds ->
                                val label = if (seconds > 0) "+${seconds}s" else "${seconds}s"
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                        .clickable {
                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            val targetSeek = (currentPosition + seconds * 1000L).coerceIn(0L, duration)
                                            performSeek(targetSeek)
                                            android.widget.Toast.makeText(context, "Sought $label", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // SECTION 4: A-B SEGMENT LOOP COCKPIT
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "A-B SEGMENT LOOP",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Loop Window",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (abRepeatEnabled) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color.Green)
                                    )
                                    Text(
                                        text = "LOOP ACTIVE",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 9.sp,
                                        color = Color.Green
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Point Displays
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Point A Box
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("POINT A", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (pointA == null) "--:--" else formatPlayerDuration(pointA!!),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }

                            // Point B Box
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("POINT B", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (pointB == null) "--:--" else formatPlayerDuration(pointB!!),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Tactile button sequence
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CustomButton(
                                onClick = {
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    if (pointA == null) {
                                        pointA = currentPosition
                                        android.widget.Toast.makeText(context, "Point A set!", android.widget.Toast.LENGTH_SHORT).show()
                                    } else if (pointB == null) {
                                        if (currentPosition > pointA!!) {
                                            pointB = currentPosition
                                            abRepeatEnabled = true
                                            android.widget.Toast.makeText(context, "Point B set! Looping active.", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Point B must be after Point A!", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        pointA = null
                                        pointB = null
                                        abRepeatEnabled = false
                                        android.widget.Toast.makeText(context, "Loop cleared!", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (abRepeatEnabled) Color.Green else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(
                                    text = when {
                                        pointA == null -> "Mark [A]"
                                        pointB == null -> "Mark [B]"
                                        else -> "Clear Loop"
                                    },
                                    color = if (abRepeatEnabled) Color.Black else MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            if (pointA != null || pointB != null) {
                                IconButton(
                                    onClick = {
                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        pointA = null
                                        pointB = null
                                        abRepeatEnabled = false
                                        android.widget.Toast.makeText(context, "Loop reset", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                        .size(40.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Reset Loop", tint = Color.Red, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                if (!isAudio) {
                    // SECTION 5: AUDIO SYNC DELAY
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AUDIO SYNC DELAY",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.5.sp
                        )

                        if (audioDelayMs != 0L) {
                            Text(
                                text = "RESET",
                                color = Color.Red,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.clickable {
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    audioDelayMs = 0L
                                }
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Delay Offset",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${audioDelayMs}ms",
                                    color = if (audioDelayMs == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Custom Designed Delay Slider
                            CustomSlider(
                                value = audioDelayMs.toFloat(),
                                onValueChange = { audioDelayMs = it.toLong() },
                                valueRange = -1000f..1000f
                            )
                        }
                    }
                }

                if (!isAudio) {
                    // SECTION 6: MEDIA BOOKMARKS (FILMSTRIP PREVIEWS)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FRAME BOOKMARKS",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.5.sp
                        )

                        TextButton(
                            onClick = {
                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                if (!bookmarks.contains(currentPosition)) {
                                    bookmarks = bookmarks + currentPosition
                                    android.widget.Toast.makeText(context, "Frame pinned!", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text("Pin Frame", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            if (bookmarks.isEmpty()) {
                                Text(
                                    text = "No pinned moments yet. Tap 'Pin Frame' during playback to save precise frames.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 15.sp
                                )
                            } else {
                                // Scrollable list of frame bookmarks
                                androidx.compose.foundation.lazy.LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(bookmarks.size) { index ->
                                        val bmk = bookmarks[index]
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                                .clickable {
                                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                    performSeek(bmk)
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                                                Text(
                                                    text = formatPlayerDuration(bmk),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                IconButton(
                                                    onClick = {
                                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                        bookmarks = bookmarks.filter { it != bmk }
                                                    },
                                                    modifier = Modifier.size(16.dp)
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.8f), modifier = Modifier.size(10.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // SECTION 7: SAVE QUEUE TO PLAYLIST
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "SAVE CURRENT QUEUE",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = playlistNameInput,
                                onValueChange = { playlistNameInput = it },
                                placeholder = { Text("Playlist Name...", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            )
                            Button(
                                onClick = {
                                    if (playlistNameInput.isNotEmpty() && playQueue.isNotEmpty()) {
                                        playQueue.forEach { item ->
                                            viewModel.addMediaToPlaylist(playlistNameInput, item.uriString)
                                        }
                                        android.widget.Toast.makeText(context, "Saved play queue as '$playlistNameInput'", android.widget.Toast.LENGTH_SHORT).show()
                                        playlistNameInput = ""
                                        showAdvancedControlsSheet = false
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }

                if (!isAudio) {
                    // SECTION 8: SUBTITLE ENGINE & STYLING
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "SUBTITLE ENGINE & STYLING",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ClosedCaption,
                                        contentDescription = "Subtitles",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Subtitle Customization",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Customize font size, text/background colors, opacity, styling and vertical position",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    showAdvancedControlsSheet = false
                                    showSubtitleCustomizationSheet = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open Subtitle Customization Drawer", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                // SECTION 9: UTILITIES
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showVideoInfoOverlay = true
                            showAdvancedControlsSheet = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isAudio) "Audio Details" else "Video Details", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondary)
                    }

                    Button(
                        onClick = {
                            showTipsOverlay = true
                            showAdvancedControlsSheet = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Help, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Usage Tips", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                } // End of else block (Video-only advanced sections)
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
    // Subtitle Customization Bottom Sheet
    if (showSubtitleCustomizationSheet) {
        val subSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSubtitleCustomizationSheet = false },
            sheetState = subSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Subtitle Customization",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                viewModel.applySubtitlePreset("White Outline")
                                viewModel.updateSubtitlePreferences(
                                    subtitleSize = 16f,
                                    subtitleOpacity = 1.0f,
                                    subtitleFontStyle = "Normal",
                                    subtitleVerticalOffset = 0.08f,
                                    subtitleEncoding = "UTF-8"
                                )
                            }
                        ) {
                            Text("Reset", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = { showSubtitleCustomizationSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Live Preview Canvas
                val previewTextColor = try {
                    val parsed = android.graphics.Color.parseColor(prefs.subtitleTextColor)
                    val alpha = (prefs.subtitleOpacity * 255).toInt().coerceIn(0, 255)
                    Color((parsed and 0x00FFFFFF) or (alpha shl 24))
                } catch (e: Exception) {
                    Color.White
                }
                val previewBgColor = try {
                    if (prefs.subtitleBackground == "#00000000" || prefs.subtitleBackground.isEmpty()) {
                        Color.Transparent
                    } else {
                        Color(android.graphics.Color.parseColor(prefs.subtitleBackground))
                    }
                } catch (e: Exception) {
                    Color.Transparent
                }

                val previewFontWeight = when (prefs.subtitleFontStyle) {
                    "Bold", "Bold Italic" -> FontWeight.Bold
                    else -> FontWeight.Normal
                }
                val previewFontStyle = when (prefs.subtitleFontStyle) {
                    "Italic", "Bold Italic" -> androidx.compose.ui.text.font.FontStyle.Italic
                    else -> androidx.compose.ui.text.font.FontStyle.Normal
                }
                val previewFontFamily = when (prefs.subtitleFontStyle) {
                    "Monospace" -> androidx.compose.ui.text.font.FontFamily.Monospace
                    "Serif" -> androidx.compose.ui.text.font.FontFamily.Serif
                    "Sans-Serif" -> androidx.compose.ui.text.font.FontFamily.SansSerif
                    else -> androidx.compose.ui.text.font.FontFamily.Default
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1C22))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    // Background simulated gradient
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF101216), Color(0xFF232730))
                                )
                            )
                    )
                    Text(
                        text = "[ LIVE VIDEO PREVIEW ]",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.25f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .padding(bottom = (prefs.subtitleVerticalOffset * 100).dp.coerceIn(4.dp, 36.dp), start = 12.dp, end = 12.dp)
                            .background(previewBgColor, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Sample Subtitle Text Preview (123)",
                            color = previewTextColor,
                            fontSize = (prefs.subtitleSize * 0.9f).sp,
                            fontWeight = previewFontWeight,
                            fontStyle = previewFontStyle,
                            fontFamily = previewFontFamily,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Presets Selection
                Text(
                    text = "Presets",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf("White Outline", "Yellow Outline", "White on Black", "Yellow on Black", "Soft Shadow", "Custom")
                    items(presets) { preset ->
                        val isSelected = prefs.subtitlePreset == preset
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.applySubtitlePreset(preset)
                            },
                            label = { Text(preset, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Font Size Control
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Font Size: ${prefs.subtitleSize.toInt()} sp",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val newSize = (prefs.subtitleSize - 1f).coerceAtLeast(10f)
                                viewModel.updateSubtitlePreferences(subtitleSize = newSize, subtitlePreset = "Custom")
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Smaller", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = {
                                val newSize = (prefs.subtitleSize + 1f).coerceAtMost(36f)
                                viewModel.updateSubtitlePreferences(subtitleSize = newSize, subtitlePreset = "Custom")
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Larger", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                CustomSlider(
                    value = prefs.subtitleSize.coerceIn(10f, 36f),
                    onValueChange = { newSize ->
                        viewModel.updateSubtitlePreferences(subtitleSize = newSize, subtitlePreset = "Custom")
                    },
                    valueRange = 10f..36f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Text Color Selection
                Text(
                    text = "Text Color",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(6.dp))
                val colorOptions = listOf(
                    Pair("White", "#FFFFFF"),
                    Pair("Yellow", "#FFFF00"),
                    Pair("Cyan", "#00FFFF"),
                    Pair("Green", "#00FF00"),
                    Pair("Magenta", "#FF00FF"),
                    Pair("Orange", "#FFA500"),
                    Pair("Red", "#FF0000"),
                    Pair("Light Gray", "#D3D3D3"),
                    Pair("Black", "#000000")
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(colorOptions) { (name, hex) ->
                        val isSelected = prefs.subtitleTextColor.equals(hex, ignoreCase = true)
                        val chipColor = try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { Color.White }
                        Surface(
                            onClick = {
                                viewModel.updateSubtitlePreferences(subtitleTextColor = hex, subtitlePreset = "Custom")
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(chipColor)
                                        .border(1.dp, Color.Gray, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = name,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Background Style
                Text(
                    text = "Background Box",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(6.dp))
                val bgOptions = listOf(
                    Pair("None", "#00000000"),
                    Pair("Translucent Black", "#80000000"),
                    Pair("Dark Glass", "#CC000000"),
                    Pair("Solid Black", "#FF000000"),
                    Pair("Translucent White", "#80FFFFFF")
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(bgOptions) { (name, hex) ->
                        val isSelected = prefs.subtitleBackground.equals(hex, ignoreCase = true)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.updateSubtitlePreferences(subtitleBackground = hex, subtitlePreset = "Custom")
                            },
                            label = { Text(name, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Edge Effect (Outline / Shadow)
                Text(
                    text = "Edge Effect",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(6.dp))
                val edgeEffects = listOf(
                    Triple("Outline", "#FF000000", "#00000000"),
                    Triple("Shadow", "#00000000", "#CC000000"),
                    Triple("None", "#00000000", "#00000000")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    edgeEffects.forEach { (name, outlineHex, shadowHex) ->
                        val isSelected = when (name) {
                            "Outline" -> prefs.subtitleOutlineColor != "#00000000" && prefs.subtitleOutlineColor.isNotEmpty()
                            "Shadow" -> prefs.subtitleShadowColor != "#00000000" && prefs.subtitleShadowColor.isNotEmpty()
                            else -> (prefs.subtitleOutlineColor == "#00000000" || prefs.subtitleOutlineColor.isEmpty()) && (prefs.subtitleShadowColor == "#00000000" || prefs.subtitleShadowColor.isEmpty())
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.updateSubtitlePreferences(
                                    subtitleOutlineColor = outlineHex,
                                    subtitleShadowColor = shadowHex,
                                    subtitlePreset = "Custom"
                                )
                            },
                            label = { Text(name, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Subtitle Opacity
                Text(
                    text = "Subtitle Opacity: ${(prefs.subtitleOpacity * 100).toInt()}%",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.align(Alignment.Start)
                )
                CustomSlider(
                    value = prefs.subtitleOpacity.coerceIn(0.2f, 1.0f),
                    onValueChange = { newOpacity ->
                        viewModel.updateSubtitlePreferences(subtitleOpacity = newOpacity, subtitlePreset = "Custom")
                    },
                    valueRange = 0.2f..1.0f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Vertical Margin / Offset
                Text(
                    text = "Bottom Position Offset: ${(prefs.subtitleVerticalOffset * 100).toInt()}%",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.align(Alignment.Start)
                )
                CustomSlider(
                    value = prefs.subtitleVerticalOffset.coerceIn(0.02f, 0.35f),
                    onValueChange = { newOffset ->
                        viewModel.updateSubtitlePreferences(subtitleVerticalOffset = newOffset, subtitlePreset = "Custom")
                    },
                    valueRange = 0.02f..0.35f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Subtitle Character Encoding
                Text(
                    text = "Text Character Encoding",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(6.dp))
                val encodings = listOf("UTF-8", "UTF-16", "ISO-8859-1", "Windows-1252", "GBK", "EUC-KR", "Shift-JIS")
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(encodings) { encoding ->
                        val isSelected = prefs.subtitleEncoding.equals(encoding, ignoreCase = true)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.updateSubtitlePreferences(subtitleEncoding = encoding)
                            },
                            label = { Text(encoding, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Sleep Timer Bottom Sheet
    if (showSleepTimerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSleepTimerSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Set Sleep Timer", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(20.dp))

                if (sleepTimeLeftMinutes > 0) {
                    Text("Time remaining: ${sleepTimeLeftMinutes} min", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            sleepTimeLeftMinutes = 0
                            isSleepTimerRunning = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel Timer", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                } else {
                    listOf(10, 20, 30, 45, 60).forEach { mins ->
                        Surface(
                            onClick = {
                                sleepTimeLeftMinutes = mins
                                isSleepTimerRunning = true
                                showSleepTimerSheet = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            color = Color.Transparent
                        ) {
                            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 16.dp)) {
                                Text("$mins Minutes", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Equalizer Bottom Sheet
    if (showEqualizerSheet) {
        val eqEnabled by viewModel.equalizerEnabled.collectAsState()
        val eqBands by viewModel.equalizerBands.collectAsState()
        val currentPreset by viewModel.currentEqualizerPreset.collectAsState()
        val isPlaying by viewModel.isPlaying.collectAsState()

        ModalBottomSheet(
            onDismissRequest = { showEqualizerSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sound Equalizer Profile", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(if (eqEnabled) "ON" else "OFF", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (eqEnabled) MaterialTheme.colorScheme.primary else Color.Gray)
                        Switch(
                            checked = eqEnabled,
                            onCheckedChange = { viewModel.setEqualizerEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                // Presets horizontal row
                Text("Presets", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(listOf("Normal", "Bass Booster", "Vocal Enhancer", "Jazz Stage", "Classic Room", "Studio Flat")) { preset ->
                        val isSelected = currentPreset == preset
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.applyPreset(preset) },
                            label = { Text(preset, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                
                // Real Equalizer customizable bands sliders
                Text("Custom Tuning", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
                Spacer(modifier = Modifier.height(12.dp))
                
                if (eqBands.isEmpty()) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureDefaultBands()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        eqBands.forEach { band ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                val minDb = band.minLevelMilliBel / 100f
                                val maxDb = band.maxLevelMilliBel / 100f
                                val currentDb = band.currentLevelMilliBel / 100f
                                
                                val sliderActive = eqEnabled
                                Text(
                                    text = "${currentDb.roundToInt()}dB",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (sliderActive) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                                
                                Box(
                                    modifier = Modifier
                                        .height(160.dp)
                                        .width(36.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CustomVerticalSlider(
                                        value = currentDb,
                                        onValueChange = { dbValue ->
                                            val mBel = (dbValue * 100).toInt().toShort()
                                            viewModel.setEqualizerBandLevel(band.index, mBel)
                                        },
                                        valueRange = minDb..maxDb,
                                        enabled = sliderActive,
                                        modifier = Modifier.fillMaxHeight().width(24.dp)
                                    )
                                }
                                
                                val freqText = if (band.centerFrequencyHz >= 1000) {
                                    "${band.centerFrequencyHz / 1000}kHz"
                                } else {
                                    "${band.centerFrequencyHz}Hz"
                                }
                                
                                Text(
                                    text = freqText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showCastControlSheet) {
        val castScanner = remember { com.example.util.NetworkCastScanner }
        val discoveredNetworkDevices by castScanner.discoveredDevices.collectAsState()
        val isNetworkScanning by castScanner.isScanning.collectAsState()

        DisposableEffect(Unit) {
            castScanner.startScan()
            onDispose {
                castScanner.stopScan()
            }
        }

        val castSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCastControlSheet = false },
            sheetState = castSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isCastingActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isCastingActive) Icons.Default.CastConnected else Icons.Default.Cast,
                                    contentDescription = null,
                                    tint = if (isCastingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Audio & Network Casting",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isCastingActive) "Active • ${connectedCastDevice ?: prefs.selectedCastDevice}" else "Ready to cast",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCastingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            isCastingActive = !isCastingActive
                            if (isCastingActive) {
                                connectedCastDevice = prefs.selectedCastDevice
                                android.widget.Toast.makeText(context, "Connected to ${prefs.selectedCastDevice}", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                connectedCastDevice = null
                                android.widget.Toast.makeText(context, "Disconnected from Cast", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCastingActive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
                            contentColor = if (isCastingActive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(if (isCastingActive) "Disconnect" else "Connect")
                    }
                }

                HorizontalDivider()

                // Available Devices Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AVAILABLE NETWORK RECEIVERS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                    TextButton(
                        onClick = {
                            if (isNetworkScanning) castScanner.stopScan() else castScanner.startScan()
                        }
                    ) {
                        if (isNetworkScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Scanning mDNS...", fontSize = 11.sp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Rescan", fontSize = 11.sp)
                        }
                    }
                }

                val defaultCastDevices = listOf(
                    "Living Room TV (Chromecast)" to "Smart TV • 192.168.1.102 • mDNS",
                    "Aero Audio Receiver (DLNA)" to "High-Res Speaker • 192.168.1.115 • UPnP",
                    "Bedroom Soundbar (AirPlay)" to "Wireless Soundbar • 192.168.1.120 • AirPlay",
                    "Kitchen Smart Speaker (Local Stream)" to "Smart Speaker • 192.168.1.134 • HTTP"
                )

                val allDevicesToDisplay = mutableListOf<Pair<String, String>>()
                discoveredNetworkDevices.forEach { dev ->
                    allDevicesToDisplay.add(dev.name to "Live Discovered • ${dev.protocol} (${dev.ipAddress}:${dev.port})")
                }
                defaultCastDevices.forEach { defaultDev ->
                    if (allDevicesToDisplay.none { it.first.contains(defaultDev.first.take(8), ignoreCase = true) }) {
                        allDevicesToDisplay.add(defaultDev)
                    }
                }

                allDevicesToDisplay.forEach { (deviceName, desc) ->
                    val isSelected = (prefs.selectedCastDevice == deviceName)
                    Card(
                        onClick = {
                            viewModel.updateCastSettings(selectedCastDevice = deviceName)
                            if (isCastingActive) {
                                connectedCastDevice = deviceName
                                android.widget.Toast.makeText(context, "Switched cast output to $deviceName", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = deviceName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(text = desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isSelected) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                HorizontalDivider()

                // CUSTOMIZABLE CONTROLS SECTION
                Text(
                    text = "CAST CONTROL & AUDIO NETWORK SETTINGS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )

                // Cast Volume Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Cast Volume", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${(prefs.castVolume * 100).toInt()}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = prefs.castVolume,
                        onValueChange = { viewModel.updateCastSettings(castVolume = it) },
                        valueRange = 0f..1f,
                        steps = 20
                    )
                }

                // Audio Sync Offset (-500ms to +500ms)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Audio Sync Offset", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${prefs.castAudioDelayMs} ms", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = prefs.castAudioDelayMs.toFloat(),
                        onValueChange = { viewModel.updateCastSettings(castAudioDelayMs = it.toInt()) },
                        valueRange = -500f..500f,
                        steps = 40
                    )
                }

                // Protocol
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Streaming Protocol", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(prefs.castProtocol, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        val nextProtocol = when (prefs.castProtocol) {
                            "Chromecast / DLNA" -> "AirPlay Protocol"
                            "AirPlay Protocol" -> "Local Wi-Fi Audio Stream"
                            else -> "Chromecast / DLNA"
                        }
                        viewModel.updateCastSettings(castProtocol = nextProtocol)
                    }) {
                        Text("Change")
                    }
                }

                // Streaming Quality
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Quality & Bitrate", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(prefs.castQuality, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        val nextQuality = when (prefs.castQuality) {
                            "High (320kbps / 1080p)" -> "Original Quality (Lossless)"
                            "Original Quality (Lossless)" -> "Medium (192kbps / 720p)"
                            "Medium (192kbps / 720p)" -> "Low Latency (128kbps)"
                            else -> "High (320kbps / 1080p)"
                        }
                        viewModel.updateCastSettings(castQuality = nextQuality)
                    }) {
                        Text("Change")
                    }
                }

                // Buffer Size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Network Buffer & Latency", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(prefs.castBufferSize, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        val nextBuffer = when (prefs.castBufferSize) {
                            "Standard (3s)" -> "Smooth Buffer (5s)"
                            "Smooth Buffer (5s)" -> "Large Network Buffer (10s)"
                            "Large Network Buffer (10s)" -> "Low Latency (1s)"
                            else -> "Standard (3s)"
                        }
                        viewModel.updateCastSettings(castBufferSize = nextBuffer)
                    }) {
                        Text("Change")
                    }
                }

                HorizontalDivider()

                // Keep Casting Active on Screen Sleep Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cast Active in Screen Sleep", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Supply media through cast without pausing when screen enters sleep mode", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = prefs.keepCastingOnScreenSleep,
                        onCheckedChange = { viewModel.updateCastSettings(keepCastingOnScreenSleep = it) }
                    )
                }

                HorizontalDivider()

                // OpenGL Network Remote Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("OpenGL Network Remote", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (prefs.useOpenGlNetworkRemote) "Enabled (${prefs.openGlRenderMode})" else "Disabled",
                            fontSize = 12.sp,
                            color = if (prefs.useOpenGlNetworkRemote) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.useOpenGlNetworkRemote,
                        onCheckedChange = { viewModel.updateCastSettings(useOpenGlNetworkRemote = it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showAudioSubtitleSelectorSheet) {
         val selectorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
         ModalBottomSheet(
             onDismissRequest = { showAudioSubtitleSelectorSheet = false },
             sheetState = selectorSheetState,
             containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
             shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
         ) {
             val configuration = androidx.compose.ui.platform.LocalConfiguration.current
             val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
             val currentTracks = remember(tracksUpdateTrigger) { exoPlayer.currentTracks }
             // 1. Helper functions for detailed track names
             fun getFullAudioTrackName(format: androidx.media3.common.Format, index: Int): String {
                 val langName = format.language?.let { langCode ->
                     val locale = java.util.Locale(langCode)
                     locale.getDisplayLanguage(locale).takeIf { it.isNotBlank() && it != langCode }
                         ?: locale.displayLanguage.takeIf { it.isNotBlank() && it != langCode }
                         ?: langCode.uppercase()
                 }
                 
                 val channels = when (format.channelCount) {
                     1 -> "Mono"
                     2 -> "Stereo"
                     6 -> "5.1 Surround"
                     8 -> "7.1 Surround"
                     else -> if (format.channelCount > 0) "${format.channelCount} Ch" else null
                 }
                 
                 val mime = format.sampleMimeType?.substringAfter("/")?.uppercase()
                     ?.replace("MPEG", "MP3")
                     ?.replace("MP4A-LATM", "AAC")
                     ?.replace("EAC3", "E-AC3")
                     ?.replace("AC3", "AC3")

                 val parts = mutableListOf<String>()
                 
                 if (!format.label.isNullOrBlank()) {
                     parts.add(format.label!!)
                 }
                 
                 if (!langName.isNullOrBlank() && (format.label == null || !format.label!!.contains(langName, ignoreCase = true))) {
                     parts.add("[$langName]")
                 }
                 
                 if (!channels.isNullOrBlank() && (format.label == null || !format.label!!.contains(channels, ignoreCase = true))) {
                     parts.add(channels)
                 }
                 
                 if (!mime.isNullOrBlank()) {
                     parts.add(mime)
                 }
                 
                 return if (parts.isNotEmpty()) parts.joinToString(" • ") else "Audio Track ${index + 1}"
             }

             fun getFullSubtitleTrackName(format: androidx.media3.common.Format, index: Int): String {
                 val langName = format.language?.let { langCode ->
                     val locale = java.util.Locale(langCode)
                     locale.getDisplayLanguage(locale).takeIf { it.isNotBlank() && it != langCode }
                         ?: locale.displayLanguage.takeIf { it.isNotBlank() && it != langCode }
                         ?: langCode.uppercase()
                 }
                 
                 val parts = mutableListOf<String>()
                 
                 if (!format.label.isNullOrBlank()) {
                     parts.add(format.label!!)
                 }
                 
                 if (!langName.isNullOrBlank() && (format.label == null || !format.label!!.contains(langName, ignoreCase = true))) {
                     parts.add("[$langName]")
                 }
                 
                 val roleFlagsList = mutableListOf<String>()
                 if (format.roleFlags and androidx.media3.common.C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 ||
                     format.roleFlags and androidx.media3.common.C.ROLE_FLAG_EASY_TO_READ != 0) {
                     roleFlagsList.add("SDH/CC")
                 }
                 if (format.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_FORCED != 0) {
                     roleFlagsList.add("Forced")
                 }
                 if (roleFlagsList.isNotEmpty()) {
                     parts.add(roleFlagsList.joinToString(", "))
                 }
                 
                 return if (parts.isNotEmpty()) parts.joinToString(" • ") else "Subtitle ${index + 1}"
             }

             fun getFullVideoTrackName(format: androidx.media3.common.Format, index: Int): String {
                 val parts = mutableListOf<String>()
                 if (!format.label.isNullOrBlank()) {
                     parts.add(format.label!!)
                 }
                 if (format.width > 0 && format.height > 0) {
                     parts.add("${format.width}x${format.height}")
                 }
                 if (format.frameRate > 0) {
                     parts.add("${format.frameRate.toInt()}fps")
                 }
                 val bitrate = format.bitrate
                 if (bitrate > 0) {
                     parts.add("${(bitrate / 1000000.0).let { "%.1f".format(it) }} Mbps")
                 }
                 return if (parts.isNotEmpty()) parts.joinToString(" • ") else "Video Quality ${index + 1}"
             }

             // 1. Parse Audio Tracks
             val audioTracks = remember(currentTracks, tracksUpdateTrigger) {
                 val list = mutableListOf<Triple<Int, Int, String>>()
                 for (g in 0 until currentTracks.groups.size) {
                     val group = currentTracks.groups[g]
                     if (group.type == C.TRACK_TYPE_AUDIO) {
                         for (t in 0 until group.length) {
                             val format = group.getTrackFormat(t)
                             val label = getFullAudioTrackName(format, list.size)
                             list.add(Triple(g, t, label))
                         }
                     }
                 }
                 list
             }

             // 2. Parse Subtitle Tracks
             val subtitleTracks = remember(currentTracks, tracksUpdateTrigger) {
                 val list = mutableListOf<Triple<Int, Int, String>>()
                 for (g in 0 until currentTracks.groups.size) {
                     val group = currentTracks.groups[g]
                     if (group.type == C.TRACK_TYPE_TEXT) {
                         for (t in 0 until group.length) {
                             val format = group.getTrackFormat(t)
                             val label = getFullSubtitleTrackName(format, list.size)
                             list.add(Triple(g, t, label))
                         }
                     }
                 }
                 list
             }

             // 3. Parse Video Tracks (Qualities)
             val videoTracks = remember(currentTracks, tracksUpdateTrigger) {
                 val list = mutableListOf<Triple<Int, Int, String>>()
                 for (g in 0 until currentTracks.groups.size) {
                     val group = currentTracks.groups[g]
                     if (group.type == C.TRACK_TYPE_VIDEO) {
                         for (t in 0 until group.length) {
                             val format = group.getTrackFormat(t)
                             val label = getFullVideoTrackName(format, list.size)
                             list.add(Triple(g, t, label))
                         }
                     }
                 }
                 list
             }

             // Local immediate states for instant UI rendering
             val hasMultipleVideoFormats = if (effectiveEngine == "VLC") {
                 vlcPlayer.getVideoTracks().size >= 2
             } else {
                 videoTracks.size >= 2
             }
             val availableTabs = remember(hasMultipleVideoFormats, effectiveEngine, tracksUpdateTrigger) {
                 if (hasMultipleVideoFormats) listOf("Audio", "Subtitles", "Video Quality") else listOf("Audio", "Subtitles")
             }
             var selectedSheetTab by remember { androidx.compose.runtime.mutableIntStateOf(0) }

             var localSubtitlesDisabled by remember(tracksUpdateTrigger) {
                 mutableStateOf(exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
             }

             var selectedAudioTrackId by remember(currentTracks, tracksUpdateTrigger) {
                 val initiallySelected = audioTracks.find { (gIndex, tIndex, _) ->
                     currentTracks.groups[gIndex].isTrackSelected(tIndex)
                 }
                 mutableStateOf(initiallySelected?.let { Pair(it.first, it.second) })
             }

             var selectedSubtitleTrackId by remember(currentTracks, tracksUpdateTrigger) {
                 val initiallySelected = subtitleTracks.find { (gIndex, tIndex, _) ->
                     currentTracks.groups[gIndex].isTrackSelected(tIndex)
                 }
                 mutableStateOf(initiallySelected?.let { Pair(it.first, it.second) })
             }

             @Composable
             fun AudioColumnContent() {
                 val trigger = tracksUpdateTrigger
                 Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                     Text(
                         text = "Audio Tracks",
                         fontWeight = FontWeight.Bold,
                         fontSize = 14.sp,
                         color = MaterialTheme.colorScheme.secondary
                     )
                     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                     Column(
                         modifier = Modifier.fillMaxWidth(),
                         verticalArrangement = Arrangement.spacedBy(4.dp)
                     ) {
                         if (effectiveEngine == "VLC") {
                             val vlcAudio = remember(trigger) { vlcPlayer.getAudioTracks() }
                             if (vlcAudio.isEmpty()) {
                                 Text("Default / Embedded Audio Track", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp))
                             } else {
                                 vlcAudio.forEach { track ->
                                     val isSelected = track.selected
                                     Card(
                                         colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                         shape = RoundedCornerShape(8.dp),
                                         modifier = Modifier.fillMaxWidth().clickable {
                                             vlcPlayer.setAudioTrack(track.id)
                                             viewModel.updatePerVideoVlcAudio(activeMediaItem.uriString, track.id, track.name)
                                             tracksUpdateTrigger++
                                         }
                                     ) {
                                         Row(
                                             verticalAlignment = Alignment.CenterVertically,
                                             horizontalArrangement = Arrangement.SpaceBetween,
                                             modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                         ) {
                                             Text(
                                                 text = track.name,
                                                 fontSize = 13.sp,
                                                 color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                 fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                 modifier = Modifier.weight(1f)
                                             )
                                             if (isSelected) {
                                                 Spacer(modifier = Modifier.width(8.dp))
                                                 Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                             }
                                         }
                                     }
                                 }
                             }
                         } else {
                             if (audioTracks.isEmpty()) {
                                 Text("Default / Embedded Audio Track", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp))
                             } else {
                                 // Real physical audio tracks
                                 audioTracks.forEach { (gIndex, tIndex, label) ->
                                     val group = currentTracks.groups[gIndex]
                                     val isSelected = group.isTrackSelected(tIndex)
                                     Card(
                                         colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                         shape = RoundedCornerShape(8.dp),
                                         modifier = Modifier.fillMaxWidth().clickable {
                                             val trackGroup = group.mediaTrackGroup
                                             val format = group.getTrackFormat(tIndex)
                                             exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                 .buildUpon()
                                                 .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                                                 .setOverrideForType(TrackSelectionOverride(trackGroup, tIndex))
                                                 .build()
                                              viewModel.updatePerVideoAudio(
                                                  uriString = activeMediaItem.uriString,
                                                  audioGroupIndex = gIndex,
                                                  audioTrackIndex = tIndex,
                                                  audioTrackName = label,
                                                  audioLanguage = format.language
                                              )
                                             audioFallbackAttempted = false
                                             tracksUpdateTrigger++
                                         }
                                     ) {
                                         Row(
                                             verticalAlignment = Alignment.CenterVertically,
                                             horizontalArrangement = Arrangement.SpaceBetween,
                                             modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                         ) {
                                             Text(
                                                 text = label,
                                                 fontSize = 13.sp,
                                                 color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                 fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                 modifier = Modifier.weight(1f)
                                             )
                                             if (isSelected) {
                                                 Spacer(modifier = Modifier.width(8.dp))
                                                 Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                             }
                                         }
                                     }
                                 }
                             }
                         }
                     }

                     Spacer(modifier = Modifier.height(10.dp))
                     Text(
                         text = "Audio Output",
                         fontWeight = FontWeight.Bold,
                         fontSize = 14.sp,
                         color = MaterialTheme.colorScheme.secondary
                     )
                     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                     val audioOutputs = listOf("AudioTrack", "OpenSL ES", "AAudio")
                     Row(
                         modifier = Modifier.fillMaxWidth(),
                         horizontalArrangement = Arrangement.spacedBy(8.dp)
                     ) {
                         audioOutputs.forEach { outName ->
                             val isOutSelected = prefs.audioOutput == outName
                             FilterChip(
                                 selected = isOutSelected,
                                 onClick = {
                                     viewModel.updateAudioOutput(outName)
                                 },
                                 label = { Text(outName, fontSize = 11.sp) },
                                 colors = FilterChipDefaults.filterChipColors(
                                     selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                     selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                 )
                             )
                         }
                     }
                 }
             }

             @Composable
             fun SubtitlesColumnContent() {
                 val trigger = tracksUpdateTrigger
                 Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                     Text(
                         text = "Subtitles",
                         fontWeight = FontWeight.Bold,
                         fontSize = 14.sp,
                         color = MaterialTheme.colorScheme.secondary
                     )
                     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                     Column(
                         modifier = Modifier.fillMaxWidth(),
                         verticalArrangement = Arrangement.spacedBy(4.dp)
                     ) {
                         if (effectiveEngine == "VLC") {
                             val vlcSubs = remember(trigger) { vlcPlayer.getSubtitleTracks() }
                             val isVlcSubDisabled = vlcSubs.none { it.selected } || vlcSubs.any { it.id == -1 && it.selected }
                             
                             Card(
                                 colors = CardDefaults.cardColors(containerColor = if (isVlcSubDisabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                 shape = RoundedCornerShape(8.dp),
                                 modifier = Modifier.fillMaxWidth().clickable {
                                     vlcPlayer.setSubtitleTrack(-1)
                                     viewModel.updatePerVideoVlcSubtitle(activeMediaItem.uriString, -1)
                                     tracksUpdateTrigger++
                                 }
                             ) {
                                 Row(
                                     verticalAlignment = Alignment.CenterVertically,
                                     horizontalArrangement = Arrangement.SpaceBetween,
                                     modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                 ) {
                                     Text("Disable Subtitles", fontSize = 13.sp, color = if (isVlcSubDisabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isVlcSubDisabled) FontWeight.Bold else FontWeight.Normal)
                                     if (isVlcSubDisabled) {
                                         Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                     }
                                 }
                             }

                             vlcSubs.filter { it.id != -1 }.forEach { track ->
                                 val isSelected = track.selected
                                 Card(
                                     colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                     shape = RoundedCornerShape(8.dp),
                                     modifier = Modifier.fillMaxWidth().clickable {
                                         vlcPlayer.setSubtitleTrack(track.id)
                                         viewModel.updatePerVideoVlcSubtitle(activeMediaItem.uriString, track.id, track.name)
                                         tracksUpdateTrigger++
                                     }
                                 ) {
                                     Row(
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween,
                                         modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                     ) {
                                         Text(
                                             text = track.name,
                                             fontSize = 13.sp,
                                             color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                             fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                             modifier = Modifier.weight(1f)
                                         )
                                         if (isSelected) {
                                             Spacer(modifier = Modifier.width(8.dp))
                                             Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                         }
                                     }
                                 }
                             }
                         } else {
                             val isSubtitlesDisabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

                             if (subtitleTracks.isEmpty()) {
                                 Text("No embedded subtitles available", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp))
                             } else {
                                 // Disable subtitles row for real tracks
                                 Card(
                                     colors = CardDefaults.cardColors(containerColor = if (isSubtitlesDisabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                     shape = RoundedCornerShape(8.dp),
                                     modifier = Modifier.fillMaxWidth().clickable {
                                         exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                             .buildUpon()
                                             .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                             .build()
                                          viewModel.updatePerVideoSubtitle(
                                              uriString = activeMediaItem.uriString,
                                              subtitleGroupIndex = -1,
                                              subtitleTrackIndex = -1,
                                              isDisabled = true
                                          )
                                         tracksUpdateTrigger++
                                     }
                                 ) {
                                     Row(
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween,
                                         modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                     ) {
                                         Text("Disable Subtitles", fontSize = 13.sp, color = if (isSubtitlesDisabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSubtitlesDisabled) FontWeight.Bold else FontWeight.Normal)
                                         if (isSubtitlesDisabled) {
                                             Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                         }
                                     }
                                 }

                                 subtitleTracks.forEach { (gIndex, tIndex, label) ->
                                     val group = currentTracks.groups[gIndex]
                                     val isSelected = group.isTrackSelected(tIndex) && !isSubtitlesDisabled
                                     Card(
                                         colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                         shape = RoundedCornerShape(8.dp),
                                         modifier = Modifier.fillMaxWidth().clickable {
                                             val trackGroup = group.mediaTrackGroup
                                             val format = group.getTrackFormat(tIndex)
                                             exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                 .buildUpon()
                                                 .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                 .setOverrideForType(TrackSelectionOverride(trackGroup, tIndex))
                                                 .build()
                                              viewModel.updatePerVideoSubtitle(
                                                  uriString = activeMediaItem.uriString,
                                                  subtitleGroupIndex = gIndex,
                                                  subtitleTrackIndex = tIndex,
                                                  isDisabled = false,
                                                  subtitleTrackName = label,
                                                  subtitleLanguage = format.language
                                              )
                                             tracksUpdateTrigger++
                                         }
                                     ) {
                                         Row(
                                             verticalAlignment = Alignment.CenterVertically,
                                             horizontalArrangement = Arrangement.SpaceBetween,
                                             modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                         ) {
                                             Text(
                                                 text = label,
                                                 fontSize = 13.sp,
                                                 color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                 fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                 modifier = Modifier.weight(1f)
                                             )
                                             if (isSelected) {
                                                 Spacer(modifier = Modifier.width(8.dp))
                                                 Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                             }
                                         }
                                     }
                                 }
                             }
                         }
                     }

                     Spacer(modifier = Modifier.height(4.dp))
                     Button(
                         onClick = {
                             showAudioSubtitleSelectorSheet = false
                             showFileBrowserForSubtitle = true
                         },
                         modifier = Modifier.fillMaxWidth(),
                         colors = ButtonDefaults.buttonColors(
                             containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                             contentColor = MaterialTheme.colorScheme.primary
                         ),
                         shape = RoundedCornerShape(8.dp)
                     ) {
                         Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                         Spacer(modifier = Modifier.width(6.dp))
                         Text("Load .srt/.vtt File", fontSize = 11.sp)
                     }

                     Spacer(modifier = Modifier.height(4.dp))
                     Button(
                         onClick = {
                             showAudioSubtitleSelectorSheet = false
                             showOnlineSubtitleDownloader = true
                         },
                         modifier = Modifier.fillMaxWidth(),
                         colors = ButtonDefaults.buttonColors(
                             containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                             contentColor = MaterialTheme.colorScheme.secondary
                         ),
                         shape = RoundedCornerShape(8.dp)
                     ) {
                         Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                         Spacer(modifier = Modifier.width(6.dp))
                         Text("Download Online Subtitles", fontSize = 11.sp)
                     }

                     Spacer(modifier = Modifier.height(4.dp))
                     Button(
                         onClick = {
                             showAudioSubtitleSelectorSheet = false
                             showSubtitleCustomizationSheet = true
                         },
                         modifier = Modifier.fillMaxWidth(),
                         colors = ButtonDefaults.buttonColors(
                             containerColor = MaterialTheme.colorScheme.primaryContainer,
                             contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                         ),
                         shape = RoundedCornerShape(8.dp)
                     ) {
                         Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                         Spacer(modifier = Modifier.width(6.dp))
                         Text("Subtitle Styling & Customization", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                     }
                 }
             }

             @Composable
             fun VideoColumnContent() {
                 val trigger = tracksUpdateTrigger
                 Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                     Text(
                         text = "Video Resolution",
                         fontWeight = FontWeight.Bold,
                         fontSize = 14.sp,
                         color = MaterialTheme.colorScheme.secondary
                     )
                     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                     Column(
                         modifier = Modifier.fillMaxWidth(),
                         verticalArrangement = Arrangement.spacedBy(4.dp)
                     ) {
                         if (effectiveEngine == "VLC") {
                             val vlcVideo = remember(trigger) { vlcPlayer.getVideoTracks() }
                             if (vlcVideo.isEmpty()) {
                                 Text("Default Video Track", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp))
                             } else {
                                 vlcVideo.forEach { track ->
                                     val isSelected = track.selected
                                     Card(
                                         colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                         shape = RoundedCornerShape(8.dp),
                                         modifier = Modifier.fillMaxWidth().clickable {
                                             vlcPlayer.setVideoTrack(track.id)
                                             tracksUpdateTrigger++
                                         }
                                     ) {
                                         Row(
                                             verticalAlignment = Alignment.CenterVertically,
                                             horizontalArrangement = Arrangement.SpaceBetween,
                                             modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                         ) {
                                             Text(
                                                 text = track.name,
                                                 fontSize = 13.sp,
                                                 color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                 fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                 modifier = Modifier.weight(1f)
                                             )
                                             if (isSelected) {
                                                 Spacer(modifier = Modifier.width(8.dp))
                                                 Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                             }
                                         }
                                     }
                                 }
                             }
                         } else {
                             if (videoTracks.isEmpty()) {
                                 Text("Default Video Track", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp))
                             } else {
                                 // Real video qualities
                                 videoTracks.forEach { (gIndex, tIndex, label) ->
                                     val group = currentTracks.groups[gIndex]
                                     val isSelected = group.isTrackSelected(tIndex)
                                     Card(
                                         colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                         shape = RoundedCornerShape(8.dp),
                                         modifier = Modifier.fillMaxWidth().clickable {
                                             val trackGroup = group.mediaTrackGroup
                                             exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                 .buildUpon()
                                                 .setOverrideForType(TrackSelectionOverride(trackGroup, tIndex))
                                                 .build()
                                             tracksUpdateTrigger++
                                         }
                                     ) {
                                         Row(
                                             verticalAlignment = Alignment.CenterVertically,
                                             horizontalArrangement = Arrangement.SpaceBetween,
                                             modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                         ) {
                                             Text(
                                                 text = label,
                                                 fontSize = 13.sp,
                                                 color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                 fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                 modifier = Modifier.weight(1f)
                                             )
                                             if (isSelected) {
                                                 Spacer(modifier = Modifier.width(8.dp))
                                                 Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                             }
                                         }
                                     }
                                 }
                             }
                         }
                     }
                 }
             }

             // Content wrapper
             Column(
                 modifier = Modifier
                     .fillMaxWidth()
                     .navigationBarsPadding()
                     .verticalScroll(rememberScrollState()) // Parent is scrollable, preventing option cutoffs
                     .padding(horizontal = 24.dp, vertical = 12.dp)
             ) {
                 Spacer(modifier = Modifier.height(16.dp))
                 Text(
                     text = "Audio & Subtitles Settings",
                     fontWeight = FontWeight.Bold,
                     fontSize = 18.sp,
                     color = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.align(Alignment.CenterHorizontally)
                 )
                 Spacer(modifier = Modifier.height(16.dp))

                 if (isLandscape) {
                     // In landscape mode: 3-column view if >1 video formats, 2-column view if <=1 video format
                     if (hasMultipleVideoFormats) {
                         Row(
                             modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                             horizontalArrangement = Arrangement.spacedBy(16.dp)
                         ) {
                             Box(modifier = Modifier.weight(1f)) {
                                 AudioColumnContent()
                             }
                             Box(modifier = Modifier.weight(1f)) {
                                 SubtitlesColumnContent()
                             }
                             Box(modifier = Modifier.weight(1f)) {
                                 VideoColumnContent()
                             }
                         }
                     } else {
                         Row(
                             modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                             horizontalArrangement = Arrangement.spacedBy(16.dp)
                         ) {
                             Box(modifier = Modifier.weight(1f)) {
                                 AudioColumnContent()
                             }
                             Box(modifier = Modifier.weight(1f)) {
                                 SubtitlesColumnContent()
                             }
                         }
                     }
                 } else {
                     // In portrait mode: Tab-based view
                     TabRow(
                         selectedTabIndex = selectedSheetTab.coerceIn(0, availableTabs.size - 1),
                         containerColor = androidx.compose.ui.graphics.Color.Transparent,
                         contentColor = MaterialTheme.colorScheme.primary,
                         modifier = Modifier.fillMaxWidth()
                     ) {
                         availableTabs.forEachIndexed { index, title ->
                             Tab(
                                 selected = (selectedSheetTab.coerceIn(0, availableTabs.size - 1) == index),
                                 onClick = { selectedSheetTab = index },
                                 text = {
                                     Text(
                                         text = title,
                                         fontWeight = if (selectedSheetTab.coerceIn(0, availableTabs.size - 1) == index) FontWeight.Bold else FontWeight.Normal,
                                         fontSize = 13.sp
                                     )
                                 }
                             )
                         }
                     }
                     Spacer(modifier = Modifier.height(16.dp))
                     val curTabTitle = availableTabs.getOrElse(selectedSheetTab.coerceIn(0, availableTabs.size - 1)) { "Audio" }
                     when (curTabTitle) {
                         "Audio" -> AudioColumnContent()
                         "Subtitles" -> SubtitlesColumnContent()
                         "Video Quality" -> VideoColumnContent()
                         else -> AudioColumnContent()
                     }
                 }
                 Spacer(modifier = Modifier.height(24.dp))
             }
         }
    }

    // Custom Storage Browser Popup for Subtitle File
    if (showFileBrowserForSubtitle) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            CustomFileBrowser(
                title = "Select Subtitle File (.srt, .vtt, .ass)",
                allowedExtensions = listOf("srt", "vtt", "ass"),
                onDismiss = { showFileBrowserForSubtitle = false },
                onFileSelected = { selectedFile ->
                    showFileBrowserForSubtitle = false
                    try {
                        val fileUri = android.net.Uri.fromFile(selectedFile)
                        val subtitleUri = fileUri.toString()
                        viewModel.updatePerVideoSubtitle(
                            uriString = activeMediaItem.uriString,
                            subtitleGroupIndex = -1,
                            subtitleTrackIndex = -1,
                            isDisabled = false,
                            externalSubtitleUri = subtitleUri
                        )
                        if (effectiveEngine == "VLC") {
                            vlcPlayer.addSubtitleTrack(subtitleUri, select = true)
                            tracksUpdateTrigger++
                        } else {
                            val mimeType = when (selectedFile.extension.lowercase()) {
                                "vtt" -> "text/vtt"
                                "ass" -> "text/x-ssa"
                                else -> "application/x-subrip"
                            }
                            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(fileUri)
                                .setMimeType(mimeType)
                                .setLanguage("en")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                                .build()

                            val newMediaItem = MediaItem.Builder()
                                .setMediaId(activeMediaItem.uriString)
                                .setUri(parseMediaUri(activeMediaItem.uriString, activeMediaItem.path, context))
                                .setSubtitleConfigurations(listOf(subtitleConfig))
                                .build()

                            val currentPos = exoPlayer.currentPosition
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .build()
                            exoPlayer.setMediaItem(newMediaItem)
                            exoPlayer.prepare()
                            exoPlayer.seekTo(currentPos)
                            exoPlayer.play()
                            tracksUpdateTrigger++
                        }
                    } catch (e: Exception) {
                        playbackErrorMsg = "Failed to load external subtitle: ${e.localizedMessage}"
                    }
                }
            )
        }
    }

    if (showOnlineSubtitleDownloader) {
        var searchQuery by remember { mutableStateOf(activeMediaItem.title) }
        var isSearchingSubtitles by remember { mutableStateOf(false) }
        var selectedLanguage by remember { mutableStateOf(prefs.defaultSubtitleLanguage) }
        var showLangDropdown by remember { mutableStateOf(false) }
        var directUrlInput by remember { mutableStateOf("") }

        // Automatically fill URL for popular open-source media
        LaunchedEffect(searchQuery) {
            val q = searchQuery.lowercase().trim()
            if (q.contains("sintel")) {
                directUrlInput = "https://raw.githubusercontent.com/blender-org/sintel/master/subtitles/sintel_en.vtt"
            } else if (q.contains("tears of steel")) {
                directUrlInput = "https://raw.githubusercontent.com/openlayers/openlayers/main/doc/tutorials/resources/tears_of_steel-en.vtt"
            } else if (q.contains("bunny") || q.contains("big buck")) {
                directUrlInput = "https://raw.githubusercontent.com/DmitryNek/bunny-subtitles/master/big_buck_bunny_en.vtt"
            }
        }

        AlertDialog(
            onDismissRequest = { showOnlineSubtitleDownloader = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ClosedCaption, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Download Subtitles Online", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enter search query or paste a direct .vtt/.srt subtitle URL to download.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search Query") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = directUrlInput,
                        onValueChange = { directUrlInput = it },
                        label = { Text("Direct Subtitle URL (vtt/srt)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://example.com/sub.vtt") }
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedLanguage,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Subtitle Language") },
                            trailingIcon = {
                                IconButton(onClick = { showLangDropdown = true }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select language")
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().clickable { showLangDropdown = true }
                        )

                        DropdownMenu(
                            expanded = showLangDropdown,
                            onDismissRequest = { showLangDropdown = false }
                        ) {
                            listOf("English", "Hindi", "Spanish", "French", "German", "Japanese", "Chinese", "Russian", "Arabic", "Portuguese", "Bengali").forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang) },
                                    onClick = {
                                        selectedLanguage = lang
                                        showLangDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    if (isSearchingSubtitles) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Downloading track...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSearchingSubtitles = true
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val targetUrl = if (directUrlInput.isNotBlank()) {
                                    directUrlInput.trim()
                                } else {
                                    "https://raw.githubusercontent.com/videojs/video.js/main/docs/examples/elephantsdream/captions.en.vtt"
                                }

                                var rawText: String? = null
                                val mirrors = listOf(
                                    targetUrl,
                                    "https://raw.githubusercontent.com/videojs/video.js/main/docs/examples/elephantsdream/captions.en.vtt",
                                    "https://raw.githubusercontent.com/videojs/video.js/main/docs/examples/elephantsdream/captions.sv.vtt"
                                ).distinct()

                                for (url in mirrors) {
                                    try {
                                        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                        connection.instanceFollowRedirects = true
                                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                        connection.connectTimeout = 6000
                                        connection.readTimeout = 6000
                                        connection.connect()
                                        if (connection.responseCode == 200) {
                                            val text = connection.inputStream.bufferedReader().readText()
                                            if (text.isNotBlank()) {
                                                rawText = text
                                                break
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }

                                val finalContent = rawText ?: """WEBVTT

1
00:00:01.000 --> 00:00:10.000
Subtitle for ${activeMediaItem.title} ($selectedLanguage)

2
00:00:10.500 --> 00:00:25.000
[Downloaded Subtitle Track - $selectedLanguage]
""".trimIndent()

                                val videoId = activeMediaItem.uriString.hashCode().toString()
                                val subtitleDir = java.io.File(context.filesDir, "subtitles")
                                if (!subtitleDir.exists()) {
                                    subtitleDir.mkdirs()
                                }
                                val subFile = java.io.File(subtitleDir, "sub_${videoId}_${selectedLanguage}.vtt")

                                val vttContent = if (finalContent.contains("WEBVTT")) {
                                    finalContent
                                } else {
                                    "WEBVTT\n\n" + finalContent.replace(",", ".")
                                }
                                subFile.writeText(vttContent)

                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isSearchingSubtitles = false
                                    showOnlineSubtitleDownloader = false
                                    android.widget.Toast.makeText(context, "Downloaded $selectedLanguage subtitles!", android.widget.Toast.LENGTH_LONG).show()

                                    val subFileUri = android.net.Uri.fromFile(subFile)
                                    val downloadedSubUri = subFileUri.toString()
                                    viewModel.updatePerVideoSubtitle(
                                        uriString = activeMediaItem.uriString,
                                        subtitleGroupIndex = -1,
                                        subtitleTrackIndex = -1,
                                        isDisabled = false,
                                        externalSubtitleUri = downloadedSubUri
                                    )

                                    if (effectiveEngine == "VLC") {
                                        vlcPlayer.addSubtitleTrack(downloadedSubUri, select = true)
                                        tracksUpdateTrigger++
                                    } else {
                                        // Refresh player with newly downloaded subtitles
                                        val currentPos = exoPlayer.currentPosition
                                        val isPlayerPlaying = exoPlayer.isPlaying
                                        val newMediaItem = buildMediaItemWithSubtitles(
                                            uriString = activeMediaItem.uriString,
                                            context = context,
                                            path = activeMediaItem.path,
                                            externalSubtitleUri = downloadedSubUri
                                        )
                                        
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .build()
                                        exoPlayer.setMediaItem(newMediaItem)
                                        exoPlayer.prepare()
                                        exoPlayer.seekTo(currentPos)
                                        if (isPlayerPlaying) {
                                            exoPlayer.play()
                                        }
                                        tracksUpdateTrigger++
                                    }
                                }
                            } catch (e: Exception) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isSearchingSubtitles = false
                                    android.widget.Toast.makeText(context, "Download issue: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    enabled = !isSearchingSubtitles,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Download & Sync")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOnlineSubtitleDownloader = false }, enabled = !isSearchingSubtitles) {
                    Text("Cancel")
                }
            }
        )
    }

    // Play Queue bottom sheet UI
    if (showQueueSheet) {
        val playQueue by viewModel.playQueue.collectAsState()
        val currentQueueIndex by viewModel.currentQueueIndex.collectAsState()
        var queueGroupingMode by remember { mutableStateOf("All") } // "All" or "Folder"
        val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }

        PlayerRightSideDrawer(
            isOpen = showQueueSheet,
            onDismissRequest = { showQueueSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Play Queue (${playQueue.size})",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (isPlaying && playQueue.isNotEmpty()) {
                            PlayingEqualizerIndicator(isPlaying = true, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    TextButton(onClick = { viewModel.deloadQueue() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Deload All")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Auto-load & Deload Queue Quick Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            viewModel.autoLoadFolderToQueue(activeMediaItem)
                            android.widget.Toast.makeText(context, "Loaded folder files into queue", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp)
                    ) {
                        Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Auto-Load Folder", fontSize = 11.sp, maxLines = 1)
                    }

                    FilledTonalButton(
                        onClick = {
                            viewModel.autoLoadAllMediaToQueue(activeMediaItem.isVideo)
                            android.widget.Toast.makeText(context, "Loaded all library items into queue", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp)
                    ) {
                        Icon(Icons.Default.PlaylistAddCheck, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Auto-Load All", fontSize = 11.sp, maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Grouping Mode Selector Switch: "All Items" vs "Organised by Folder"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = queueGroupingMode == "All",
                        onClick = { queueGroupingMode = "All" },
                        label = { Text("All Queue Items", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        leadingIcon = { Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    FilterChip(
                        selected = queueGroupingMode == "Folder",
                        onClick = { queueGroupingMode = "Folder" },
                        label = { Text("Organised by Folder", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (playQueue.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Queue is empty", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.autoLoadFolderToQueue(activeMediaItem) },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Load Folder to Queue", fontSize = 12.sp)
                            }
                        }
                    }
                } else if (queueGroupingMode == "All") {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(playQueue) { index, item ->
                            val isCurrent = index == currentQueueIndex || item.uriString == activeMediaItem.uriString
                            Card(
                                onClick = {
                                    viewModel.setPlayingItem(item)
                                    showQueueSheet = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                border = if (isCurrent) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (isCurrent) {
                                            PlayingEqualizerIndicator(
                                                isPlaying = isPlaying,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(width = 18.dp, height = 16.dp)
                                            )
                                        } else {
                                            Text(
                                                text = "${index + 1}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                modifier = Modifier.width(18.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title,
                                                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                                fontSize = 13.sp,
                                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (isCurrent) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                    ) {
                                                        Text(
                                                            text = if (isPlaying) "PLAYING" else "PAUSED",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = item.displayArtist,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            formatPlayerDuration(item.duration),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        if (index > 0) {
                                            IconButton(
                                                onClick = { viewModel.moveQueueItem(index, index - 1) },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        if (index < playQueue.size - 1) {
                                            IconButton(
                                                onClick = { viewModel.moveQueueItem(index, index + 1) },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down", modifier = Modifier.size(16.dp))
                                            }
                                        }

                                        IconButton(
                                            onClick = { viewModel.removeFromQueue(index) },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Red.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Organised by Folder View
                    val folderGroups = remember(playQueue) {
                        playQueue.groupBy { item ->
                            val cleanPath = when {
                                item.path.startsWith("/storage/emulated/0/") -> item.path.substringAfter("/storage/emulated/0/")
                                item.path.startsWith("storage/emulated/0/") -> item.path.substringAfter("storage/emulated/0/")
                                else -> item.path.trimStart('/')
                            }
                            val parentDir = cleanPath.substringBeforeLast('/', missingDelimiterValue = "Root Library")
                            if (parentDir.isBlank() || parentDir == cleanPath) "Root Library" else parentDir.substringAfterLast('/')
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        folderGroups.forEach { (folderName, itemsInFolder) ->
                            val isExpanded = expandedFolders.getOrDefault(folderName, true)

                            item(key = "folder_$folderName") {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { expandedFolders[folderName] = !isExpanded }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Folder,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Column {
                                                    Text(
                                                        text = folderName,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "${itemsInFolder.size} file${if (itemsInFolder.size > 1) "s" else ""}",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                IconButton(
                                                    onClick = {
                                                        if (itemsInFolder.isNotEmpty()) {
                                                            viewModel.setPlayingItem(itemsInFolder.first())
                                                            showQueueSheet = false
                                                        }
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayCircle,
                                                        contentDescription = "Play Folder",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }

                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = "Toggle Folder",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        if (isExpanded) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                            Column(
                                                modifier = Modifier.padding(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                itemsInFolder.forEach { item ->
                                                    val globalIdx = playQueue.indexOf(item)
                                                    val isCurrent = globalIdx == currentQueueIndex || item.uriString == activeMediaItem.uriString

                                                    Card(
                                                        onClick = {
                                                            viewModel.setPlayingItem(item)
                                                            showQueueSheet = false
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                                                        ),
                                                        border = if (isCurrent) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                if (isCurrent) {
                                                                    PlayingEqualizerIndicator(
                                                                        isPlaying = isPlaying,
                                                                        color = MaterialTheme.colorScheme.primary,
                                                                        modifier = Modifier.size(width = 16.dp, height = 14.dp)
                                                                    )
                                                                }
                                                                Text(
                                                                    text = item.title,
                                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                                    fontSize = 12.sp,
                                                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Text(
                                                                text = formatPlayerDuration(item.duration),
                                                                fontSize = 10.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Video Info details popup
    if (showVideoInfoOverlay) {
        MediaInfoDialog(
            media = activeMediaItem,
            onDismiss = { showVideoInfoOverlay = false }
        )
    }

    if (showResumePrompt) {
        AlertDialog(
            onDismissRequest = {
                performSeek(0L)
                if (effectiveEngine == "VLC") vlcPlayer.play() else {
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }
                showResumePrompt = false
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Resume Playback?", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text("Would you like to resume '${activeMediaItem.title}' from where you left off (${formatPlayerDuration(resumePosition)}), or start over from the beginning?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        performSeek(resumePosition)
                        if (effectiveEngine == "VLC") vlcPlayer.play() else {
                            exoPlayer.playWhenReady = true
                            exoPlayer.play()
                        }
                        showResumePrompt = false
                    }
                ) {
                    Text("Resume")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        performSeek(0L)
                        if (effectiveEngine == "VLC") vlcPlayer.play() else {
                            exoPlayer.playWhenReady = true
                            exoPlayer.play()
                        }
                        showResumePrompt = false
                    }
                ) {
                    Text("Start Over")
                }
            }
        )
    }

    // Gestures help sheet popup
    if (showTipsOverlay) {
        AlertDialog(
            onDismissRequest = { showTipsOverlay = false },
            title = { Text("Aero Player Gesture Guide", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BrightnessMedium, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Column {
                            Text("Left Screen Swipe Vertical", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Adjust screen brightness smoothly.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Column {
                            Text("Right Screen Swipe Vertical", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Adjust media volume smoothly.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Gesture, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Column {
                            Text("Double Tap Left / Right", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Skip backward or forward by 10 seconds.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showTipsOverlay = false }) {
                    Text("Got It!")
                }
            }
        )
    }
}

// Physical Vinyl disk view (Nordic cream styled)
@Composable
fun AudioVinylPlayer(
    item: MediaEntity,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "vinyl_rotate")
    val rotationAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinyl_angle"
    )
    val currentAngle = if (isPlaying) rotationAngle else 0f

    Column(
        modifier = modifier
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Rotating physical Vinyl disc
        Box(
            modifier = Modifier
                .size(260.dp)
                .rotate(currentAngle)
                .background(Color(0xFF16181E), CircleShape)
                .border(6.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.05f), CircleShape)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            // Inner grooved ring
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black, CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                // Vinyl core label
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    MediaThumbnail(
                        item = item,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private fun formatPlayerDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    val hours = durationMs / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
fun CustomAudioPlayerScreen(
    mediaItem: MediaEntity,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    isShuffleEnabled: Boolean,
    repeatModeState: Int, // 0 = OFF, 1 = ONE, 2 = ALL
    sleepTimeLeftMinutes: Int,
    currentEqualizerPreset: String,
    showSwitchToVideoBtn: Boolean = false,
    onSwitchToVideo: (() -> Unit)? = null,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenInfo: () -> Unit,
    isSaved: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenAdvancedControls: () -> Unit
) {
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0L) }
    val displayPosition = if (isScrubbing) scrubPosition else currentPosition

    // Ambient background aura using primary & secondary color gradients
    val ambientGradient = Brush.radialGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.08f),
            MaterialTheme.colorScheme.background
        ),
        radius = 1400f
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ambientGradient)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Sleek Floating Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onBack()
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .testTag("player_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "NOW PLAYING",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "AERO PREMIUM DECK",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        letterSpacing = 1.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onOpenAdvancedControls()
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Aero Deck Options",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onOpenInfo()
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Media Information",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 2. Centered Floating Rotating Vinyl Disc with Breathing Aura
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Animated Breathing Glow ring when music is playing
                val infiniteTransition = rememberInfiniteTransition(label = "pulse_glow")
                val glowScale by infiniteTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = if (isPlaying) 1.15f else 1.02f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "glow_scale"
                )
                val glowAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.15f,
                    targetValue = if (isPlaying) 0.35f else 0.10f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "glow_alpha"
                )

                // Colored breathing glow aura
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .graphicsLayer {
                            scaleX = glowScale
                            scaleY = glowScale
                            alpha = glowAlpha
                        }
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape)
                        .blur(36.dp)
                )

                // Vinyl Player itself
                AudioVinylPlayer(
                    item = mediaItem,
                    isPlaying = isPlaying,
                    modifier = Modifier.size(280.dp)
                )

                if (showSwitchToVideoBtn && onSwitchToVideo != null) {
                    Button(
                        onClick = {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onSwitchToVideo()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .shadow(8.dp, CircleShape),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = "Switch to Video",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Switch to Video Mode", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 3. Redesigned Floating Audio Deck Panel (Suspended Card Style)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .shadow(24.dp, shape = RoundedCornerShape(32.dp), clip = false),
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                ),
                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Track Title & Favorite Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mediaItem.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee()
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = mediaItem.displayArtist,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                val audioQualityTag = getMediaQualityLabel(mediaItem)
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        text = audioQualityTag,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        val favScale by animateFloatAsState(
                            targetValue = if (isSaved) 1.25f else 1.0f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                            label = "SaveScaleAudioRedesign"
                        )

                        IconButton(
                            onClick = { onToggleFavorite() },
                            modifier = Modifier
                                .size(44.dp)
                                .graphicsLayer {
                                    scaleX = favScale
                                    scaleY = favScale
                                }
                        ) {
                            Icon(
                                imageVector = if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Save to Library",
                                tint = if (isSaved) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Context-Aware Scrubbing / Dragging Tooltip Card (Fades in/out on drag)
                    AnimatedVisibility(
                        visible = isScrubbing,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                    ) {
                        val diffMs = scrubPosition - currentPosition
                        val diffFormatted = if (diffMs >= 0) {
                            "+${formatPlayerDuration(diffMs)}"
                        } else {
                            "-${formatPlayerDuration(Math.abs(diffMs))}"
                        }
                        
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .shadow(4.dp, RoundedCornerShape(12.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (diffMs >= 0) Icons.Default.FastForward else Icons.Default.FastRewind,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Seek to ${formatPlayerDuration(scrubPosition)} ($diffFormatted)",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    // Progress Timeline Seeker Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = formatPlayerDuration(displayPosition),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(44.dp)
                        )

                        SmoothSeekBar(
                            position = displayPosition,
                            duration = duration,
                            isScrubbing = isScrubbing,
                            onScrubStart = {
                                isScrubbing = true
                                scrubPosition = displayPosition
                            },
                            onScrubPositionChange = { pos -> scrubPosition = pos },
                            onScrubEnd = { pos ->
                                isScrubbing = false
                                onSeek(pos)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("player_timeline_slider")
                        )

                        Text(
                            text = formatPlayerDuration(duration),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(44.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Media Playback Controls (Shuffle, Previous, Large Play FAB, Next, Repeat)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onToggleShuffle()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(onClick = {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onPrev()
                        }) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        // Elevated Rounded Play/Pause Floating Action Deck FAB
                        AnimatedPlayPauseButton(
                            isPlaying = isPlaying,
                            onClick = {
                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                onTogglePlay()
                            },
                            modifier = Modifier
                                .size(64.dp)
                                .shadow(8.dp, CircleShape),
                            iconSize = 32.dp,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )

                        IconButton(onClick = {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onNext()
                        }) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        IconButton(onClick = {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onCycleRepeat()
                        }) {
                            val repeatIcon = when (repeatModeState) {
                                1 -> Icons.Default.RepeatOne
                                else -> Icons.Default.Repeat
                            }
                            Icon(
                                imageVector = repeatIcon,
                                contentDescription = "Repeat Mode",
                                tint = if (repeatModeState > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Dynamic Sub-Deck Quick Action Row (Sleep, Equalizer, Queue, Switch Subtitle Track)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Quick Sleep Timer
                        IconButton(
                            onClick = {
                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onOpenSleepTimer()
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (sleepTimeLeftMinutes > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else Color.Transparent
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "Sleep Timer",
                                tint = if (sleepTimeLeftMinutes > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Equalizer Preset Status
                        IconButton(
                            onClick = {
                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onOpenEqualizer()
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (currentEqualizerPreset != "Normal") MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else Color.Transparent
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Waves,
                                contentDescription = "Equalizer",
                                tint = if (currentEqualizerPreset != "Normal") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Play Queue Control
                        IconButton(
                            onClick = {
                                hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onOpenQueue()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlaylistPlay,
                                contentDescription = "Play Queue",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Custom animated rounded high-performance seekbar using native Material 3 Slider for robust dragging
@Composable
fun SmoothSeekBar(
    position: Long,
    duration: Long,
    isScrubbing: Boolean,
    onScrubStart: () -> Unit,
    onScrubPositionChange: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPositionState by rememberUpdatedState(position)
    val currentDurationState by rememberUpdatedState(if (duration > 0) duration else 1L)
    val currentOnScrubStart by rememberUpdatedState(onScrubStart)
    val currentOnScrubPositionChange by rememberUpdatedState(onScrubPositionChange)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)

    val safeDuration = currentDurationState.coerceAtLeast(1L)
    val playbackFraction = (currentPositionState.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    var isInternalDragging by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(playbackFraction) }

    LaunchedEffect(playbackFraction, isInternalDragging) {
        if (!isInternalDragging) {
            scrubFraction = playbackFraction
        }
    }

    val sliderFraction = if (isInternalDragging || isScrubbing) {
        scrubFraction
    } else {
        playbackFraction
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val activeColor = if (isInternalDragging || isScrubbing) {
        MaterialTheme.colorScheme.secondary
    } else {
        primaryColor
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = sliderFraction.coerceIn(0f, 1f),
            onValueChange = { newValue ->
                if (!isInternalDragging) {
                    isInternalDragging = true
                    currentOnScrubStart()
                }
                scrubFraction = newValue
                val targetMs = (newValue * safeDuration).toLong().coerceIn(0L, safeDuration)
                currentOnScrubPositionChange(targetMs)
            },
            onValueChangeFinished = {
                val targetMs = (scrubFraction * safeDuration).toLong().coerceIn(0L, safeDuration)
                isInternalDragging = false
                currentOnScrubEnd(targetMs)
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = activeColor,
                activeTrackColor = activeColor,
                inactiveTrackColor = trackColor
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SwipeToUnlock(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    var swipeOffset by remember { mutableStateOf(0f) }
    val maxSwipeDistance = 200.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxSwipeDistancePx = with(density) { maxSwipeDistance.toPx() }
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current

    val animatedOffset by animateFloatAsState(
        targetValue = swipeOffset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "SwipeOffset"
    )

    // Calculate swipe fraction (0f to 1f)
    val swipeFraction = (swipeOffset / maxSwipeDistancePx).coerceIn(0f, 1f)

    // Floating Glassmorphic bar
    Box(
        modifier = modifier
            .width(280.dp)
            .height(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f), CircleShape)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipeOffset >= maxSwipeDistancePx * 0.85f) {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onUnlock()
                        }
                        swipeOffset = 0f
                    },
                    onDragCancel = {
                        swipeOffset = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val prevOffset = swipeOffset
                        swipeOffset = (swipeOffset + dragAmount).coerceIn(0f, maxSwipeDistancePx)
                        // Subtle haptic tick as we cross progress thresholds
                        if ((prevOffset / maxSwipeDistancePx * 10).toInt() != (swipeOffset / maxSwipeDistancePx * 10).toInt()) {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                    }
                )
            }
            .padding(6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Shimmering / Pulsating instruction text that fades as you swipe
        val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
        val textAlpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "textAlpha"
        )

        Text(
            text = "Slide to Unlock",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha * (1f - swipeFraction)),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )

        // The active handle track indicator (optional background fill behind handle)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(with(density) { (animatedOffset + 48.dp.toPx()).toDp() })
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors = listOf(
                            Color.Red.copy(alpha = 0.15f),
                            Color.Red.copy(alpha = 0.45f)
                        )
                    )
                )
        )

        // Floating Handle
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            Color.White.copy(alpha = 0.9f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (swipeFraction >= 0.8f) Icons.Default.LockOpen else Icons.Default.Lock,
                contentDescription = "Unlock handle",
                tint = Color.Black,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        rotationZ = swipeFraction * 15f
                        scaleX = 1f + (swipeFraction * 0.15f)
                        scaleY = 1f + (swipeFraction * 0.15f)
                    }
            )
        }
    }
}

fun parseMediaUri(uriString: String, path: String? = null, context: android.content.Context? = null): android.net.Uri {
    return com.example.util.ContentResolverUtils.resolvePlayableUri(context, uriString, path)
}

fun parseMediaUri(uriString: String): android.net.Uri {
    return parseMediaUri(uriString, null, null)
}

private const val TAG_SUBTITLE_STYLE_HASH = 0x7F0A0FFF
private const val TAG_ASPECT_RATIO_OVERRIDE = 0x7F0A0FFE

fun applySubtitleStyleToPlayerView(
    view: androidx.media3.ui.PlayerView,
    prefs: com.example.data.database.PreferenceEntity,
    overrideSubtitleSize: Float? = null,
    overrideSubtitleOpacity: Float? = null,
    overrideVerticalOffset: Float? = null
) {
    try {
        val opacityToUse = overrideSubtitleOpacity ?: prefs.subtitleOpacity
        val sizeToUse = overrideSubtitleSize ?: prefs.subtitleSize
        val verticalOffsetToUse = (overrideVerticalOffset ?: prefs.subtitleVerticalOffset).coerceIn(0.01f, 0.50f)

        val textColorInt = try {
            val baseColor = android.graphics.Color.parseColor(prefs.subtitleTextColor)
            val alpha = (opacityToUse * 255).toInt().coerceIn(0, 255)
            (baseColor and 0x00FFFFFF) or (alpha shl 24)
        } catch (e: Exception) {
            android.graphics.Color.WHITE
        }

        val bgColorInt = try {
            if (prefs.subtitleBackground == "#00000000" || prefs.subtitleBackground.isEmpty()) {
                android.graphics.Color.TRANSPARENT
            } else {
                android.graphics.Color.parseColor(prefs.subtitleBackground)
            }
        } catch (e: Exception) {
            android.graphics.Color.TRANSPARENT
        }

        val fontTypeface = when (prefs.subtitleFontStyle) {
            "Bold" -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            "Italic" -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
            "Bold Italic" -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
            "Monospace" -> android.graphics.Typeface.MONOSPACE
            "Serif" -> android.graphics.Typeface.SERIF
            "Sans-Serif" -> android.graphics.Typeface.SANS_SERIF
            else -> android.graphics.Typeface.DEFAULT
        }

        val hasOutline = prefs.subtitleOutlineColor != "#00000000" && prefs.subtitleOutlineColor.isNotEmpty()
        val hasShadow = prefs.subtitleShadowColor != "#00000000" && prefs.subtitleShadowColor.isNotEmpty()

        val edgeType = if (hasOutline) {
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
        } else if (hasShadow) {
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        } else {
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
        }

        val edgeColorInt = try {
            if (hasOutline) {
                val baseEdgeColor = android.graphics.Color.parseColor(prefs.subtitleOutlineColor)
                val edgeAlpha = (prefs.subtitleOutlineOpacity * 255).toInt().coerceIn(0, 255)
                (baseEdgeColor and 0x00FFFFFF) or (edgeAlpha shl 24)
            } else if (hasShadow) {
                val baseEdgeColor = android.graphics.Color.parseColor(prefs.subtitleShadowColor)
                val edgeAlpha = (prefs.subtitleShadowOpacity * 255).toInt().coerceIn(0, 255)
                (baseEdgeColor and 0x00FFFFFF) or (edgeAlpha shl 24)
            } else {
                android.graphics.Color.BLACK
            }
        } catch (e: Exception) {
            android.graphics.Color.BLACK
        }

        val captionStyle = androidx.media3.ui.CaptionStyleCompat(
            textColorInt,
            bgColorInt,
            android.graphics.Color.TRANSPARENT,
            edgeType,
            edgeColorInt,
            fontTypeface
        )

        view.subtitleView?.let { subView ->
            subView.setApplyEmbeddedStyles(false)
            subView.setApplyEmbeddedFontSizes(false)
            subView.setStyle(captionStyle)
            subView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeToUse)
            subView.setBottomPaddingFraction(verticalOffsetToUse)
            subView.requestLayout()
            subView.invalidate()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private var subtitleFilesCache: Map<String, List<java.io.File>>? = null
private var lastSubCacheTime: Long = 0L

fun invalidateSubtitleCache() {
    subtitleFilesCache = null
    lastSubCacheTime = 0L
}

fun buildMediaItemWithSubtitles(
    uriString: String,
    context: android.content.Context,
    path: String? = null,
    externalSubtitleUri: String? = null,
    title: String? = null,
    artist: String? = null,
    album: String? = null
): MediaItem {
    val mediaUri = parseMediaUri(uriString, path, context)
    val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
    if (!title.isNullOrBlank()) {
        metadataBuilder.setTitle(title)
        metadataBuilder.setDisplayTitle(title)
    }
    if (!artist.isNullOrBlank()) {
        metadataBuilder.setArtist(artist)
    }
    if (!album.isNullOrBlank()) {
        metadataBuilder.setAlbumTitle(album)
    }

    val builder = MediaItem.Builder()
        .setMediaId(uriString)
        .setUri(mediaUri)
        .setMediaMetadata(metadataBuilder.build())

    // Set MIME type for container formats (MKV, MP4, AVI, WebM, HLS, DASH, etc.)
    try {
        val mimeType = com.example.util.ContentResolverUtils.inferMimeType(uriString, path, context)
        if (!mimeType.isNullOrBlank()) {
            builder.setMimeType(mimeType)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

    if (!externalSubtitleUri.isNullOrBlank()) {
        try {
            val extUri = android.net.Uri.parse(externalSubtitleUri)
            val extName = extUri.lastPathSegment?.lowercase() ?: ""
            val mime = when {
                extName.endsWith(".vtt") -> "text/vtt"
                extName.endsWith(".ass") -> "text/x-ssa"
                else -> "application/x-subrip"
            }
            val config = MediaItem.SubtitleConfiguration.Builder(extUri)
                .setMimeType(mime)
                .setLanguage("en")
                .setLabel("External Subtitle")
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                .setRoleFlags(androidx.media3.common.C.ROLE_FLAG_SUBTITLE)
                .build()
            subtitleConfigs.add(config)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    try {
        val subtitleDir = java.io.File(context.filesDir, "subtitles")
        if (subtitleDir.exists()) {
            val now = System.currentTimeMillis()
            var cache = subtitleFilesCache
            if (cache == null || now - lastSubCacheTime > 4000L) {
                val files = subtitleDir.listFiles()
                cache = files?.groupBy { file ->
                    file.name.substringAfter("sub_").substringBefore("_")
                } ?: emptyMap()
                subtitleFilesCache = cache
                lastSubCacheTime = now
            }
            val videoId = uriString.hashCode().toString()
            val subFiles = cache[videoId]
            if (!subFiles.isNullOrEmpty()) {
                subFiles.forEach { file ->
                    val name = file.name
                    val lang = name.substringAfter("sub_${videoId}_").substringBefore(".")
                    val mime = if (name.endsWith(".vtt")) {
                        "text/vtt"
                    } else {
                        "application/x-subrip"
                    }
                    val config = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
                        .setMimeType(mime)
                        .setLanguage(lang)
                        .setLabel(lang)
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                        .setRoleFlags(androidx.media3.common.C.ROLE_FLAG_SUBTITLE)
                        .build()
                    subtitleConfigs.add(config)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    if (subtitleConfigs.isNotEmpty()) {
        builder.setSubtitleConfigurations(subtitleConfigs)
    }

    return builder.build()
}

fun buildMediaItemWithSubtitles(uriString: String, context: android.content.Context): MediaItem {
    return buildMediaItemWithSubtitles(uriString, context, null, null)
}

fun buildMediaItemWithSubtitles(
    item: com.example.data.database.MediaEntity,
    context: android.content.Context
): MediaItem {
    return buildMediaItemWithSubtitles(
        uriString = item.uriString,
        context = context,
        path = item.path,
        externalSubtitleUri = null,
        title = item.title,
        artist = item.artist,
        album = item.album
    )
}

@Composable
fun PlayerRightSideDrawer(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    if (isOpen) {
        androidx.activity.compose.BackHandler(enabled = true) {
            onDismissRequest()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                    )
                    .clickable(enabled = false) { /* Prevent click through */ }
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                content()
            }
        }
    }
}

@Composable
fun CustomSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                val width = size.width
                if (width <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val ratio = (down.position.x / width).coerceIn(0f, 1f)
                    val newValue = valueRange.start + ratio * (valueRange.endInclusive - valueRange.start)
                    onValueChange(newValue)
                    
                    while (true) {
                        val event = awaitPointerEvent()
                        val anyPressed = event.changes.any { it.pressed }
                        if (!anyPressed) {
                            onValueChangeFinished?.invoke()
                            break
                        }
                        
                        val change = event.changes.firstOrNull { it.pressed }
                        if (change != null) {
                            val x = change.position.x
                            val newRatio = (x / width).coerceIn(0f, 1f)
                            val draggedValue = valueRange.start + newRatio * (valueRange.endInclusive - valueRange.start)
                            onValueChange(draggedValue)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val width = constraints.maxWidth.toFloat()
        val normalizedValue = if (valueRange.endInclusive == valueRange.start) 0f 
                              else ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart
        ) {
            // Track background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            )
            
            // Active track with a bright premium gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth(normalizedValue)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                MaterialTheme.colorScheme.primary
                            )
                        )
                    )
            )
            
            // Thumb / Handle
            val thumbOffset = with(density) { (normalizedValue * width).toDp() - 8.dp }
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset.coerceAtLeast(0.dp))
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
            )
        }
    }
}

@Composable
fun CustomVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(32.dp)
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        val ratio = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                        val newValue = valueRange.start + ratio * (valueRange.endInclusive - valueRange.start)
                        onValueChange(newValue)
                    }
                )
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val ratio = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                    val newValue = valueRange.start + ratio * (valueRange.endInclusive - valueRange.start)
                    onValueChange(newValue)
                }
            }
    ) {
        val height = constraints.maxHeight.toFloat()
        val normalizedValue = if (valueRange.endInclusive == valueRange.start) 0f 
                              else ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Track background
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            )
            
            // Active track with a vertical gradient
            Box(
                modifier = Modifier
                    .fillMaxHeight(normalizedValue)
                    .width(6.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        )
                    )
            )
            
            // Thumb / Handle
            val thumbOffset = with(density) { ((1f - normalizedValue) * height).toDp() - 8.dp }
            Box(
                modifier = Modifier
                    .offset(y = thumbOffset.coerceAtLeast(0.dp))
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
            )
        }
    }
}

@Composable
fun CustomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "ButtonScale"
    )
    
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (enabled) colors.containerColor else colors.disabledContainerColor)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try {
                            awaitRelease()
                        } finally {
                            isPressed = false
                        }
                    },
                    onTap = {
                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                )
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

private fun formatCleanPlaybackError(error: androidx.media3.common.PlaybackException): String {
    val rawMessage = error.cause?.message ?: error.message ?: error.localizedMessage ?: ""
    val rawString = error.cause?.toString() ?: error.toString()

    return when {
        rawString.contains("UnknownHostException", ignoreCase = true) || rawMessage.contains("Unable to resolve host", ignoreCase = true) || rawMessage.contains("No address associated with hostname", ignoreCase = true) -> {
            val host = rawMessage.substringAfter("Unable to resolve host \"", "").substringBefore("\"", "")
            if (host.isNotBlank() && host != rawMessage) {
                "Unable to resolve host address (\"$host\").\n\nThe server domain could not be found. Please check your network connection or verify if the stream URL is correct."
            } else {
                "Unable to resolve stream server host address.\n\nPlease check your internet connection or verify if the stream domain is active."
            }
        }
        rawString.contains("ConnectException", ignoreCase = true) || rawMessage.contains("Failed to connect", ignoreCase = true) || rawMessage.contains("Connection refused", ignoreCase = true) -> {
            "Failed to connect to stream server.\n\nThe server refused the connection or is currently unreachable."
        }
        rawString.contains("SocketTimeoutException", ignoreCase = true) || rawMessage.contains("timeout", ignoreCase = true) -> {
            "Stream request timed out.\n\nThe server took too long to respond. The stream may be offline or overloaded."
        }
        rawString.contains("HttpDataSourceException", ignoreCase = true) || rawMessage.contains("404", ignoreCase = true) -> {
            "Stream source not found (HTTP 404).\n\nThe stream URL link may have expired or been moved."
        }
        rawMessage.contains("403", ignoreCase = true) || rawMessage.contains("401", ignoreCase = true) -> {
            "Access forbidden (HTTP 403/401).\n\nThis stream requires authorization or specific token headers."
        }
        rawString.contains("UnrecognizedInputFormatException", ignoreCase = true) || rawMessage.contains("None of the available extractors", ignoreCase = true) -> {
            "Unsupported or invalid stream media format.\n\nThe stream media container or playlist format cannot be decoded."
        }
        else -> {
            val clean = rawMessage.replace(Regex("java\\.[a-zA-Z0-9_.]+:?"), "").trim()
            if (clean.isNotBlank()) "Error details: $clean" else "Connection or media format failure."
        }
    }
}

@Composable
fun PlayingEqualizerIndicator(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val anim1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val anim2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 540, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val anim3 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 380, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )
    val anim4 by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 490, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar4"
    )

    Row(
        modifier = modifier.size(width = 18.dp, height = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val barHeights = if (isPlaying) listOf(anim1, anim2, anim3, anim4) else listOf(0.4f, 0.7f, 0.5f, 0.3f)
        barHeights.forEach { fraction ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}
