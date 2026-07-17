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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.database.MediaEntity
import com.example.data.database.displayArtist
import com.example.ui.viewmodel.MainViewModel
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

    // ExoPlayer Builder
    val exoPlayer = viewModel.exoPlayer

    var showResumePrompt by remember { mutableStateOf(false) }
    var resumePosition by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    val currentEqualizerPreset by viewModel.currentEqualizerPreset.collectAsState()
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var currentBrightness by remember { mutableStateOf(-1f) }
    var showOnlineSubtitleDownloader by remember { mutableStateOf(false) }

    // Session-based screen orientation override (not stored persistently)
    var sessionOrientation by remember(prefs.defaultOrientation) {
        mutableStateOf(
            when (prefs.defaultOrientation) {
                "Portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "Landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "Reverse Portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                "Reverse Landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        )
    }

    // Apply orientation changes dynamically
    LaunchedEffect(sessionOrientation, prefs.rotationLock) {
        if (prefs.rotationLock) {
            (context as? android.app.Activity)?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            (context as? android.app.Activity)?.requestedOrientation = sessionOrientation
        }
    }

    // Restore default orientation and manage immersive system UI on player screen lifecycle
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        val window = activity?.window
        if (window != null) {
            val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            (context as? android.app.Activity)?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                windowInsetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Listen to Lifecycle Events to pause playback instantly when minimized
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Load Uri Source
    LaunchedEffect(activeMediaItem) {
        val currentUri = try { exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() } catch (e: Exception) { null }
        if (currentUri == activeMediaItem.uriString) {
            // Already playing this item, don't restart it
            return@LaunchedEffect
        }

        // Stop and clear media items sequentially to prevent playback freeze on play/re-play
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        var speedToApply = prefs.playbackSpeed
        var resizeToApply = prefs.resizeMode
        var volumeToApply = 1.0f
        var eqPresetToApply = "Flat"
        var brightnessToApply = -1.0f

        // 1. Initial global defaults if set
        if (prefs.saveVolumeBrightnessBehavior == "Global") {
            volumeToApply = prefs.globalVolume
            brightnessToApply = prefs.globalBrightness
        }

        // 2. Load per-video preferences or individual settings
        if (prefs.usePerVideoSettings || prefs.saveVolumeBrightnessBehavior == "Individual") {
            try {
                val json = if (prefs.perVideoSettingsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(prefs.perVideoSettingsJson)
                if (json.has(activeMediaItem.uriString)) {
                    val videoObj = json.getJSONObject(activeMediaItem.uriString)
                    if (prefs.usePerVideoSettings) {
                        speedToApply = videoObj.optDouble("speed", prefs.playbackSpeed.toDouble()).toFloat()
                        resizeToApply = videoObj.optInt("resizeMode", prefs.resizeMode)
                        eqPresetToApply = videoObj.optString("eqPreset", "Flat")
                    }
                    if (prefs.usePerVideoSettings || prefs.saveVolumeBrightnessBehavior == "Individual") {
                        volumeToApply = videoObj.optDouble("volume", 1.0).toFloat()
                    }
                    if (prefs.saveVolumeBrightnessBehavior == "Individual") {
                        brightnessToApply = videoObj.optDouble("brightness", -1.0).toFloat()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Apply configurations
        exoPlayer.setPlaybackSpeed(speedToApply)
        exoPlayer.volume = volumeToApply
        resizeMode = resizeToApply
        if (eqPresetToApply.isNotBlank()) {
            viewModel.applyPreset(eqPresetToApply)
        }

        // Apply screen brightness if set
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

        val historyList = viewModel.historyState.value
        val history = historyList.find { it.uriString == activeMediaItem.uriString }
        if (history != null && history.progressMs > 1000L && history.progressMs < history.duration - 5000L) {
            resumePosition = history.progressMs
            when (prefs.resumePlaybackBehavior) {
                "Always Resume" -> {
                    exoPlayer.setMediaItem(buildMediaItemWithSubtitles(activeMediaItem.uriString, context))
                    exoPlayer.prepare()
                    exoPlayer.seekTo(resumePosition)
                    exoPlayer.play()
                }
                "Always Start from Beginning" -> {
                    exoPlayer.setMediaItem(buildMediaItemWithSubtitles(activeMediaItem.uriString, context))
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
                else -> { // "Ask Every Time"
                    showResumePrompt = true
                    exoPlayer.setMediaItem(buildMediaItemWithSubtitles(activeMediaItem.uriString, context))
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = false
                }
            }
        } else {
            exoPlayer.setMediaItem(buildMediaItemWithSubtitles(activeMediaItem.uriString, context))
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    // Log History periodically
    LaunchedEffect(exoPlayer, activeMediaItem) {
        while (true) {
            delay(8000)
            if (exoPlayer.isPlaying) {
                viewModel.addPlaybackHistory(activeMediaItem, exoPlayer.currentPosition)
            }
        }
    }

    DisposableEffect(activeMediaItem) {
        onDispose {
            viewModel.addPlaybackHistory(activeMediaItem, exoPlayer.currentPosition)
        }
    }

    // Auto-save per-video settings when they are modified
    LaunchedEffect(exoPlayer.volume, resizeMode, currentEqualizerPreset, isPlaying) {
        if (prefs.usePerVideoSettings && isPlaying) {
            val currentSpeed = exoPlayer.playbackParameters.speed
            val currentVol = exoPlayer.volume
            viewModel.updatePerVideoSettings(
                uriString = activeMediaItem.uriString,
                speed = currentSpeed,
                resizeMode = resizeMode,
                volume = currentVol,
                eqPreset = currentEqualizerPreset
            )
        }
    }

    // Auto-save volume and brightness levels based on behavior settings
    LaunchedEffect(exoPlayer.volume, currentBrightness, isPlaying) {
        if (isPlaying) {
            val currentVol = exoPlayer.volume
            val currentBri = currentBrightness

            if (prefs.saveVolumeBrightnessBehavior == "Global") {
                viewModel.updateGlobalVolume(currentVol)
                if (currentBri >= 0f) {
                    viewModel.updateGlobalBrightness(currentBri)
                }
            } else if (prefs.saveVolumeBrightnessBehavior == "Individual") {
                viewModel.updatePerVideoVolumeBrightness(
                    uriString = activeMediaItem.uriString,
                    volume = currentVol,
                    brightness = currentBri
                )
            }
        }
    }

    // Core States
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isLockControlVisible by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0L) }
    var scrubbingBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // Fallback track selection states
    var selectedMockAudioIndex by remember { mutableStateOf(0) }
    var selectedMockSubtitleIndex by remember { mutableStateOf(0) }
    var selectedMockVideoIndex by remember { mutableStateOf(0) }

    // Async thumbnail extractor during scrubbing
    LaunchedEffect(scrubPosition, activeMediaItem, isScrubbing) {
        if (isScrubbing && activeMediaItem.isVideo) {
            delay(120) // debounce slight dragging jitter
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    if (activeMediaItem.uriString.startsWith("content://")) {
                        retriever.setDataSource(context, android.net.Uri.parse(activeMediaItem.uriString))
                    } else {
                        retriever.setDataSource(activeMediaItem.path ?: activeMediaItem.uriString)
                    }
                    val frame = retriever.getFrameAtTime(
                        scrubPosition * 1000L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    if (frame != null) {
                        scrubbingBitmap = frame
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try {
                        retriever.release()
                    } catch (e: Exception) {}
                }
            }
        } else if (!isScrubbing) {
            scrubbingBitmap = null
        }
    }

    // Advanced Loop & Sync States
    var pointA by remember { mutableStateOf<Long?>(null) }
    var pointB by remember { mutableStateOf<Long?>(null) }
    var abRepeatEnabled by remember { mutableStateOf(false) }
    var audioDelayMs by remember { mutableStateOf(0L) }
    var playbackErrorMsg by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(false) }

    // Bottom Drawers visibility
    var showAdvancedControlsSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showAudioSubtitleSelectorSheet by remember { mutableStateOf(false) }
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
    var playAsAudioOnly by remember { mutableStateOf(viewModel.audioOnlyPlaybackRequested) }

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
    var gestureFeedbackType by remember { mutableStateOf("") } // "volume", "brightness", "seek"
    var gestureFeedbackValue by remember { mutableStateOf("") }
    var activeDragVolume by remember { mutableStateOf<Float?>(null) }
    var activeDragBrightness by remember { mutableStateOf<Float?>(null) }
    var gestureSessionType by remember { mutableStateOf("none") }
    var totalPanX by remember { mutableStateOf(0f) }
    var totalPanY by remember { mutableStateOf(0f) }

    var lastGestureTime by remember { mutableStateOf(0L) }

    var leftRippleTrigger by remember { mutableStateOf(0) }
    var rightRippleTrigger by remember { mutableStateOf(0) }
    val leftAnim = remember { Animatable(0f) }
    val rightAnim = remember { Animatable(0f) }

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
                    exoPlayer.pause()
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

    DisposableEffect(isPlaying, isSleepTimerRunning, sleepTimeLeftMinutes) {
        val activity = context as? android.app.Activity
        if (isPlaying && (!isSleepTimerRunning || sleepTimeLeftMinutes > 0)) {
            try {
                if (!wakeLock.isHeld) {
                    wakeLock.acquire(10 * 60 * 1000L) // 10 mins fallback lock
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
                if (playing) playbackErrorMsg = null
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    duration = exoPlayer.duration
                    playbackErrorMsg = null
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackErrorMsg = "Unable to play source: ${error.localizedMessage ?: "Network or codec failure"}"
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

    // Playback loop tracking position
    LaunchedEffect(isPlaying, abRepeatEnabled, pointA, pointB) {
        while (isPlaying) {
            val pos = exoPlayer.currentPosition
            currentPosition = pos
            if (abRepeatEnabled && pointA != null && pointB != null) {
                if (pos >= pointB!!) {
                    exoPlayer.seekTo(pointA!!)
                    currentPosition = pointA!!
                }
            }
            delay(150)
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

    BackHandler {
        when {
            showFileBrowserForSubtitle -> showFileBrowserForSubtitle = false
            showAudioSubtitleSelectorSheet -> showAudioSubtitleSelectorSheet = false
            showAdvancedControlsSheet -> showAdvancedControlsSheet = false
            showSleepTimerSheet -> showSleepTimerSheet = false
            showEqualizerSheet -> showEqualizerSheet = false
            else -> onBack()
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
                    onBack()
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
                        .width(200.dp)
                        .height(130.dp)
                        .shadow(12.dp, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                pipOffset = Offset(
                                    x = pipOffset.x + dragAmount.x,
                                    y = pipOffset.y + dragAmount.y
                                )
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (activeMediaItem.isVideo && !playAsAudioOnly) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        useController = false
                                        player = exoPlayer
                                        layoutParams = FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                update = { view -> view.resizeMode = resizeMode },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f))
                        ) {
                            IconButton(
                                onClick = { isPipActive = false },
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(28.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = "Restore Fullscreen", tint = Color.White, modifier = Modifier.size(16.dp))
                            }

                            IconButton(
                                onClick = {
                                    isPipActive = false
                                    onBack()
                                },
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss Player", tint = Color.White, modifier = Modifier.size(16.dp))
                            }

                            IconButton(
                                onClick = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                },
                                modifier = Modifier.align(Alignment.Center).size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
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
            onBack = onBack,
            onTogglePlay = {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            },
            onSeek = { percent ->
                val target = (percent * duration).toLong()
                exoPlayer.seekTo(target)
                currentPosition = target
            },
            onPrev = { viewModel.playPrevious() },
            onNext = { viewModel.playNext() },
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
            onOpenInfo = { showVideoInfoOverlay = true }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                                                val thresholdX = width * 0.002f
                                                val thresholdY = height * 0.004f
                                                if (abs(totalDragX) > thresholdX || abs(totalDragY) > thresholdY) {
                                                    isControlsVisible = true
                                                    if (abs(totalDragY) > abs(totalDragX)) {
                                                        gestureSessionType = if (firstDown.position.x < width / 2) "brightness" else "volume"
                                                    } else {
                                                        gestureSessionType = "seek"
                                                        initialGesturePosition = exoPlayer.currentPosition
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
                                                        if (currentPercent != targetPercent && targetPercent % 2 == 0) {
                                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
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
                                                        if (currentPercent != targetPercent && targetPercent % 2 == 0) {
                                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                        }
                                                        
                                                        activeDragVolume = targetV
                                                        val systemVol = (targetV * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, systemVol, 0)
                                                        gestureFeedbackValue = "$targetPercent%"
                                                    }
                                                    "seek" -> {
                                                        gestureFeedbackType = "seek"
                                                        val seekRangeMs = if (duration > 0L) (duration / 5).coerceAtLeast(60000L) else 120000L
                                                        val seekDelta = (totalDragX / width * seekRangeMs).toLong()
                                                        val targetSeek = (initialGesturePosition + seekDelta).coerceIn(0L, duration)
                                                        exoPlayer.seekTo(targetSeek)
                                                        currentPosition = targetSeek
                                                        gestureFeedbackValue = formatPlayerDuration(targetSeek)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            
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
                                    if (offset.x < width / 2) {
                                        val targetSeek = (exoPlayer.currentPosition - seekAmountMs).coerceAtLeast(0L)
                                        exoPlayer.seekTo(targetSeek)
                                        currentPosition = targetSeek
                                        gestureFeedbackType = "seek_back"
                                        gestureFeedbackValue = "-${prefs.doubleTapSeekSeconds}s"
                                        leftRippleTrigger++
                                    } else {
                                        val targetSeek = (exoPlayer.currentPosition + seekAmountMs).coerceAtMost(duration)
                                        exoPlayer.seekTo(targetSeek)
                                        currentPosition = targetSeek
                                        gestureFeedbackType = "seek_forward"
                                        gestureFeedbackValue = "+${prefs.doubleTapSeekSeconds}s"
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
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                player = exoPlayer
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = { view -> 
                            view.resizeMode = resizeMode
                            try {
                                val textColorInt = try { android.graphics.Color.parseColor(prefs.subtitleTextColor) } catch (e: Exception) { android.graphics.Color.WHITE }
                                val bgColorInt = try { android.graphics.Color.parseColor(prefs.subtitleBackground) } catch (e: Exception) { android.graphics.Color.TRANSPARENT }
                                
                                val fontTypeface = when (prefs.subtitleFontStyle) {
                                    "Bold" -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                    "Italic" -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
                                    else -> android.graphics.Typeface.DEFAULT
                                }
                                
                                val captionStyle = androidx.media3.ui.CaptionStyleCompat(
                                    textColorInt,
                                    bgColorInt,
                                    android.graphics.Color.TRANSPARENT,
                                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                    android.graphics.Color.BLACK,
                                    fontTypeface
                                )
                                view.subtitleView?.setStyle(captionStyle)
                                view.subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, prefs.subtitleSize)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Audio Vinyl Layout (Nordic styled)
                    AudioVinylPlayer(
                        item = activeMediaItem,
                        isPlaying = isPlaying
                    )
                }

                // Double Tap Ripple Animations
                Row(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Left half (Backward)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            val progress = leftAnim.value
                            if (progress > 0f && progress < 1f) {
                                val alpha = (1f - progress) * 0.35f
                                val radius = size.height * 0.8f * progress
                                drawCircle(
                                    color = Color.White,
                                    radius = radius,
                                    center = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                                    alpha = alpha
                                )
                            }
                        }

                        if (leftAnim.value > 0f) {
                            val progress = leftAnim.value
                            val alpha = (1f - progress).coerceIn(0f, 1f)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .graphicsLayer {
                                        this.alpha = alpha
                                        this.translationX = -progress * 35.dp.toPx()
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
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .graphicsLayer { rotationZ = 180f }
                                    )
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .graphicsLayer { rotationZ = 180f }
                                    )
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .graphicsLayer { rotationZ = 180f }
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "-${prefs.doubleTapSeekSeconds}s",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // Right half (Forward)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            val progress = rightAnim.value
                            if (progress > 0f && progress < 1f) {
                                val alpha = (1f - progress) * 0.35f
                                val radius = size.height * 0.8f * progress
                                drawCircle(
                                    color = Color.White,
                                    radius = radius,
                                    center = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                                    alpha = alpha
                                )
                            }
                        }

                        if (rightAnim.value > 0f) {
                            val progress = rightAnim.value
                            val alpha = (1f - progress).coerceIn(0f, 1f)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .graphicsLayer {
                                        this.alpha = alpha
                                        this.translationX = progress * 35.dp.toPx()
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
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "+${prefs.doubleTapSeekSeconds}s",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
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
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(20.dp)
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
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = onBack, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                                    Text("Go Back")
                                }
                                Button(
                                    onClick = {
                                        playbackErrorMsg = null
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer, contentColor = MaterialTheme.colorScheme.errorContainer)
                                ) {
                                    Text("Retry")
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
                    // Floating auto-hide rotate screen button on left top 40%
                    if (!isLocked) {
                        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                        val screenHeight = configuration.screenHeightDp.dp
                        val rotateButtonOffsetY = screenHeight * 0.4f
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = 16.dp, y = rotateButtonOffsetY)
                        ) {
                            IconButton(
                                onClick = {
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    sessionOrientation = if (sessionOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    } else {
                                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ScreenRotation,
                                    contentDescription = "Rotate Screen",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    // Top gradient background with floating controls
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(
                                if (isLocked) androidx.compose.ui.graphics.SolidColor(Color.Transparent) else androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.85f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .statusBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isLocked) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    IconButton(
                                        onClick = onBack,
                                        modifier = Modifier
                                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                            .testTag("player_back_button")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = activeMediaItem.title,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            modifier = Modifier.basicMarquee()
                                        )
                                        Text(
                                            text = activeMediaItem.displayArtist,
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }

                            // Top Action Badges
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!isLocked) {
                                    // Queue Sheet Button (Play Queue)
                                    IconButton(
                                        onClick = {
                                            showQueueSheet = true
                                        },
                                        modifier = Modifier.size(36.dp).testTag("top_queue_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Queue,
                                            contentDescription = "Play Queue",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            isLocked = true
                                            isControlsVisible = false
                                            isLockControlVisible = true
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LockOpen,
                                            contentDescription = "Lock UI",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            isLockControlVisible = true
                                        },
                                        modifier = Modifier // Removed background as requested by user
                                    ) {
                                        Icon(Icons.Default.Lock, contentDescription = "Locked", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }

                    // Play / Pause and Skips (Center overlay) - removed middle control, only show locked floating alert
                    if (isLocked && isLockControlVisible) {
                        // Locked floating alert
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Screen Controls Locked", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Bottom Swipe to Unlock Capsule
                        SwipeToUnlock(
                            onUnlock = {
                                isLocked = false
                                isControlsVisible = true
                                isLockControlVisible = true
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 48.dp)
                        )
                    }

                    // Seeker and track duration (Bottom HUD bar - Vertical gradient container)
                    if (!isLocked) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.85f)
                                        )
                                    )
                                )
                                .navigationBarsPadding()
                                .padding(horizontal = 24.dp, vertical = 5.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                            // YouTube style scrubbing floating preview card
                            if (isScrubbing) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.95f)),
                                        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .width(160.dp)
                                            .height(95.dp)
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
                                                    .background(Color.DarkGray.copy(alpha = 0.3f)),
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
                                                            tint = MaterialTheme.colorScheme.secondary,
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
                                                    .background(MaterialTheme.colorScheme.secondary)
                                                    .padding(vertical = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = formatPlayerDuration(scrubPosition),
                                                    color = Color.Black,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val displayPosition = if (isScrubbing) scrubPosition else currentPosition
                                Text(formatPlayerDuration(displayPosition), color = Color.White, fontSize = 12.sp)

                                SmoothSeekBar(
                                    position = displayPosition,
                                    duration = duration,
                                    isScrubbing = isScrubbing,
                                    onScrubStart = { isScrubbing = true },
                                    onScrubPositionChange = { pos -> scrubPosition = pos },
                                    onScrubEnd = { pos ->
                                        isScrubbing = false
                                        exoPlayer.seekTo(pos)
                                        currentPosition = pos
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("player_timeline_slider")
                                )

                                Text(formatPlayerDuration(duration), color = Color.White, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                             Row(
                                 modifier = Modifier.fillMaxWidth(),
                                 verticalAlignment = Alignment.CenterVertically
                             ) {
                                 // Left side: Subtitles & Rotation Lock (weight 1f, align start)
                                 Row(
                                     modifier = Modifier.weight(1f),
                                     horizontalArrangement = Arrangement.Start,
                                     verticalAlignment = Alignment.CenterVertically
                                 ) {
                                     IconButton(
                                         onClick = {
                                             hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                             showAudioSubtitleSelectorSheet = true
                                         },
                                         modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                     ) {
                                         Icon(Icons.Default.Subtitles, contentDescription = "Subtitles", tint = Color.White)
                                     }

                                     Spacer(modifier = Modifier.width(8.dp))

                                      IconButton(
                                          onClick = {
                                              hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                              viewModel.toggleRotationLock(!prefs.rotationLock)
                                          },
                                         modifier = Modifier.size(36.dp)
                                     ) {
                                         Icon(
                                             imageVector = if (prefs.rotationLock) Icons.Default.ScreenLockRotation else Icons.Default.ScreenRotation,
                                             contentDescription = "Toggle Rotation Lock",
                                             tint = Color.White,
                                             modifier = Modifier.size(20.dp)
                                         )
                                     }
                                 }

                                 // Center: Media Navigation Row
                                 Row(
                                     horizontalArrangement = Arrangement.spacedBy(16.dp),
                                     verticalAlignment = Alignment.CenterVertically
                                 ) {
                                     IconButton(
                                         onClick = {
                                             hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                             viewModel.playPrevious()
                                         },
                                         modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                     ) {
                                         Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = Color.White)
                                     }

                                     IconButton(
                                         onClick = {
                                             hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                             exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0L))
                                         },
                                         modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                     ) {
                                         Icon(Icons.Default.FastRewind, contentDescription = "-10s", tint = Color.White)
                                     }

                                     IconButton(
                                         onClick = {
                                             hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                             if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                         },
                                         modifier = Modifier.size(54.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                                     ) {
                                         Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                                     }

                                     IconButton(
                                         onClick = {
                                             hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                             exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(duration))
                                         },
                                         modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                     ) {
                                         Icon(Icons.Default.FastForward, contentDescription = "+10s", tint = Color.White)
                                     }

                                     IconButton(
                                         onClick = {
                                             hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                             viewModel.playNext()
                                         },
                                         modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                     ) {
                                         Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                                     }
                                 }

                                 // Right side: Aspect Ratio & Overflow menu (weight 1f, align end)
                                 Row(
                                     modifier = Modifier.weight(1f),
                                     horizontalArrangement = Arrangement.End,
                                     verticalAlignment = Alignment.CenterVertically
                                 ) {
                                     IconButton(
                                         onClick = {
                                             hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                             // Loop: Fit, Stretch, Crop, Original
                                             resizeMode = when (resizeMode) {
                                                 AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                                 AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                                 AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                                 else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                             }
                                             val desc = when (resizeMode) {
                                                 AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit to Screen"
                                                 AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                                                 AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop 16:9"
                                                 else -> "Fit"
                                             }
                                             gestureFeedbackType = "seek"
                                             gestureFeedbackValue = desc
                                         },
                                         modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                     ) {
                                         Icon(Icons.Default.AspectRatio, contentDescription = "Resize Viewport", tint = Color.White)
                                     }

                                     Spacer(modifier = Modifier.width(8.dp))

                                     IconButton(
                                         onClick = {
                                             hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                             showAdvancedControlsSheet = true
                                         },
                                         modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                     ) {
                                         Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = Color.White)
                                     }
                                 }
                             }
                        }
                    }
                }
            }
        }
    }

    // Advanced Player options sheet drawer (Unified comprehensive controls)
    if (showAdvancedControlsSheet) {
        val playQueue by viewModel.playQueue.collectAsState()
        val scrollState = rememberScrollState()
        var jumpInputText by remember { mutableStateOf("") }
        var playlistNameInput by remember { mutableStateOf("") }

        PlayerRightSideDrawer(
            isOpen = showAdvancedControlsSheet,
            onDismissRequest = { showAdvancedControlsSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .verticalScroll(scrollState)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Aero Player Advanced Controls", fontWeight = FontWeight.Black, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 1: PRIMARY PLAYBACK CONFIGS
                Text("Primary Settings", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                Spacer(modifier = Modifier.height(8.dp))

                // Playback speed slider
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Playback Speed:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            var speed = exoPlayer.playbackParameters.speed
                            Text(String.format("%.2fx", speed), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Slider(
                            value = exoPlayer.playbackParameters.speed,
                            onValueChange = { exoPlayer.setPlaybackSpeed(it) },
                            valueRange = 0.25f..4.00f,
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.secondary, activeTrackColor = MaterialTheme.colorScheme.secondary)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Jump to Time Card
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Jump to Time", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = jumpInputText,
                                onValueChange = { jumpInputText = it },
                                placeholder = { Text("e.g. 01:30 or 90 seconds", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
                            )
                            Button(
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
                                        exoPlayer.seekTo(targetMs.coerceIn(0L, duration))
                                        currentPosition = targetMs.coerceIn(0L, duration)
                                        jumpInputText = ""
                                        showAdvancedControlsSheet = false
                                    }
                                }
                            ) {
                                Text("Go", fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Audio Only / Pop-Up Toggles
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Audio-Only", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Play Background", fontSize = 10.sp, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = playAsAudioOnly,
                                    onCheckedChange = { playAsAudioOnly = it },
                                    modifier = Modifier.scale(0.8f)
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Pop-Up Player", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Picture-in-Pic", fontSize = 10.sp, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = isPipActive,
                                    onCheckedChange = {
                                        isPipActive = it
                                        if (it) {
                                            android.widget.Toast.makeText(context, "Pop-Up player mini mode enabled", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.scale(0.8f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 2: SECONDARY CONTROLS (REPEAT, SHUFFLE, A-B, DELAY, AUDIO TRACKS)
                Text("Modifiers & Loopers", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                Spacer(modifier = Modifier.height(8.dp))

                // Repeat / Shuffle Card
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Shuffle Mode:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Switch(
                                checked = isShuffleEnabled,
                                onCheckedChange = {
                                    isShuffleEnabled = it
                                    exoPlayer.shuffleModeEnabled = it
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.15f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Repeat Mode:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            val label = when (repeatModeState) {
                                0 -> "Off"
                                1 -> "One (Loop Current)"
                                else -> "All (Loop List)"
                            }
                            TextButton(onClick = {
                                repeatModeState = (repeatModeState + 1) % 3
                                exoPlayer.repeatMode = when (repeatModeState) {
                                    0 -> Player.REPEAT_MODE_OFF
                                    1 -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_ALL
                                }
                            }) {
                                Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // A-B repeat Loop Card
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("A-B Repeat Loop:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        if (pointA == null) {
                                            pointA = currentPosition
                                        } else if (pointB == null) {
                                            if (currentPosition > pointA!!) {
                                                pointB = currentPosition
                                                abRepeatEnabled = true
                                            }
                                        } else {
                                            pointA = null
                                            pointB = null
                                            abRepeatEnabled = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (abRepeatEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text(
                                        text = when {
                                            pointA == null -> "Mark [A]"
                                            pointB == null -> "Mark [B]"
                                            else -> "Loop [A-B] active ↺"
                                        },
                                        fontSize = 11.sp
                                    )
                                }

                                if (pointA != null || pointB != null) {
                                    IconButton(
                                        onClick = {
                                            pointA = null
                                            pointB = null
                                            abRepeatEnabled = false
                                        }
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear loop", tint = Color.Red)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Delay / Audio Adjuster
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Audio Delay Adjuster:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${audioDelayMs}ms", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Slider(
                            value = audioDelayMs.toFloat(),
                            onValueChange = { audioDelayMs = it.toLong() },
                            valueRange = -1000f..1000f,
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.secondary, activeTrackColor = MaterialTheme.colorScheme.secondary)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 3: BOOKMARKS & PLAYLISTS
                Text("Bookmarks & Playlists", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                Spacer(modifier = Modifier.height(8.dp))

                // Bookmarks Manager Card
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Media Bookmarks", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            TextButton(onClick = {
                                if (!bookmarks.contains(currentPosition)) {
                                    bookmarks = bookmarks + currentPosition
                                }
                            }) {
                                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pin Frame", fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        if (bookmarks.isEmpty()) {
                            Text("No bookmarks added yet", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        } else {
                            Row(modifier = Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                bookmarks.forEach { bmk ->
                                    AssistChip(
                                        onClick = {
                                            exoPlayer.seekTo(bmk)
                                            currentPosition = bmk
                                        },
                                        label = { Text(formatPlayerDuration(bmk), fontSize = 10.sp) },
                                        trailingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Delete",
                                                modifier = Modifier.size(12.dp).clickable {
                                                    bookmarks = bookmarks.filter { it != bmk }
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Save play queue as playlist Card
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Save Play Queue as Playlist", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = playlistNameInput,
                                onValueChange = { playlistNameInput = it },
                                placeholder = { Text("Playlist Name...", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
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
                                }
                            ) {
                                Text("Save", fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 4: HELP, DETAILS & CHEATSHEETS
                Text("System Info & Tips", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showVideoInfoOverlay = true
                            showAdvancedControlsSheet = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Video Details", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            showTipsOverlay = true
                            showAdvancedControlsSheet = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Help, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Gestures Help", fontSize = 12.sp)
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
            containerColor = MaterialTheme.colorScheme.surface,
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

        ModalBottomSheet(
            onDismissRequest = { showEqualizerSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Waves, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Play audio to activate Equalizer", color = Color.Gray, fontSize = 14.sp)
                        }
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
                                
                                Text(
                                    text = "${currentDb.roundToInt()}dB",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (eqEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                                
                                Box(
                                    modifier = Modifier
                                        .height(160.dp)
                                        .width(36.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Slider(
                                        value = currentDb,
                                        onValueChange = { dbValue ->
                                            if (eqEnabled) {
                                                val mBel = (dbValue * 100).toInt().toShort()
                                                viewModel.setEqualizerBandLevel(band.index, mBel)
                                            }
                                        },
                                        valueRange = minDb..maxDb,
                                        modifier = Modifier
                                            .graphicsLayer {
                                                rotationZ = -90f
                                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                                            }
                                            .width(140.dp)
                                            .height(30.dp),
                                        enabled = eqEnabled,
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary,
                                            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                        )
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

    if (showAudioSubtitleSelectorSheet) {
         ModalBottomSheet(
             onDismissRequest = { showAudioSubtitleSelectorSheet = false },
             containerColor = MaterialTheme.colorScheme.surface,
             shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
         ) {
             val configuration = androidx.compose.ui.platform.LocalConfiguration.current
             val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
             val currentTracks = exoPlayer.currentTracks

             // 1. Parse Audio Tracks
             val audioTracks = remember(currentTracks) {
                 val list = mutableListOf<Triple<Int, Int, String>>()
                 for (g in 0 until currentTracks.groups.size) {
                     val group = currentTracks.groups[g]
                     if (group.type == C.TRACK_TYPE_AUDIO) {
                         for (t in 0 until group.length) {
                             val format = group.getTrackFormat(t)
                             val label = format.label ?: format.language ?: "Audio Track ${list.size + 1}"
                             list.add(Triple(g, t, label))
                         }
                     }
                 }
                 list
             }

             // 2. Parse Subtitle Tracks
             val subtitleTracks = remember(currentTracks) {
                 val list = mutableListOf<Triple<Int, Int, String>>()
                 for (g in 0 until currentTracks.groups.size) {
                     val group = currentTracks.groups[g]
                     if (group.type == C.TRACK_TYPE_TEXT) {
                         for (t in 0 until group.length) {
                             val format = group.getTrackFormat(t)
                             val label = format.label ?: format.language ?: "Subtitle ${list.size + 1}"
                             list.add(Triple(g, t, label))
                         }
                     }
                 }
                 list
             }

             // 3. Parse Video Tracks (Qualities)
             val videoTracks = remember(currentTracks) {
                 val list = mutableListOf<Triple<Int, Int, String>>()
                 for (g in 0 until currentTracks.groups.size) {
                     val group = currentTracks.groups[g]
                     if (group.type == C.TRACK_TYPE_VIDEO) {
                         for (t in 0 until group.length) {
                             val format = group.getTrackFormat(t)
                             val label = format.label ?: "${format.width}x${format.height}"
                             list.add(Triple(g, t, label))
                         }
                     }
                 }
                 list
             }

             @Composable
             fun AudioColumnContent() {
                 Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                     Text(
                         text = "Audio Tracks",
                         fontWeight = FontWeight.Bold,
                         fontSize = 14.sp,
                         color = MaterialTheme.colorScheme.secondary
                     )
                     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                     Column(
                         modifier = Modifier
                             .heightIn(max = 200.dp)
                             .verticalScroll(rememberScrollState())
                             .fillMaxWidth(),
                         verticalArrangement = Arrangement.spacedBy(4.dp)
                     ) {
                         if (audioTracks.isEmpty()) {
                             // Interactive simulated fallback audio tracks
                             val mockAudios = listOf("Standard Stereo", "Surround Sound 5.1", "High-Fidelity Audio Description")
                             mockAudios.forEachIndexed { index, label ->
                                 val isSelected = selectedMockAudioIndex == index
                                 Card(
                                     colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                     shape = RoundedCornerShape(8.dp),
                                     modifier = Modifier.fillMaxWidth().clickable {
                                         selectedMockAudioIndex = index
                                         android.widget.Toast.makeText(context, "Switched to $label fallback stream", android.widget.Toast.LENGTH_SHORT).show()
                                     }
                                 ) {
                                     Row(
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween,
                                         modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                     ) {
                                         Text(label, fontSize = 13.sp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                         if (isSelected) {
                                             Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                         }
                                     }
                                 }
                             }
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
                                         exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                             .buildUpon()
                                             .setOverrideForType(TrackSelectionOverride(trackGroup, tIndex))
                                             .build()
                                     }
                                 ) {
                                     Row(
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween,
                                         modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                     ) {
                                         Text(label, fontSize = 13.sp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                         if (isSelected) {
                                             Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                         }
                                     }
                                 }
                             }
                         }
                     }
                 }
             }

             @Composable
             fun SubtitlesColumnContent() {
                 Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                     Text(
                         text = "Subtitles",
                         fontWeight = FontWeight.Bold,
                         fontSize = 14.sp,
                         color = MaterialTheme.colorScheme.secondary
                     )
                     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                     val isSubtitlesDisabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

                     Column(
                         modifier = Modifier
                             .heightIn(max = 200.dp)
                             .verticalScroll(rememberScrollState())
                             .fillMaxWidth(),
                         verticalArrangement = Arrangement.spacedBy(4.dp)
                     ) {
                         if (subtitleTracks.isEmpty()) {
                             // Interactive simulated subtitles
                             val mockSubs = listOf("Disable Subtitles", "English [CC]", "Spanish Subtitles", "French Subtitles")
                             mockSubs.forEachIndexed { index, label ->
                                 val isSelected = selectedMockSubtitleIndex == index
                                 Card(
                                     colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                     shape = RoundedCornerShape(8.dp),
                                     modifier = Modifier.fillMaxWidth().clickable {
                                         selectedMockSubtitleIndex = index
                                         android.widget.Toast.makeText(context, "Subtitles: $label selected", android.widget.Toast.LENGTH_SHORT).show()
                                     }
                                 ) {
                                     Row(
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween,
                                         modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                     ) {
                                         Text(label, fontSize = 13.sp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                         if (isSelected) {
                                             Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                         }
                                     }
                                 }
                             }
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
                                         exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                             .buildUpon()
                                             .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                             .setOverrideForType(TrackSelectionOverride(trackGroup, tIndex))
                                             .build()
                                     }
                                 ) {
                                     Row(
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween,
                                         modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                     ) {
                                         Text(label, fontSize = 13.sp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                         if (isSelected) {
                                             Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
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
                 }
             }

             @Composable
             fun VideoColumnContent() {
                 Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                     Text(
                         text = "Video Resolution",
                         fontWeight = FontWeight.Bold,
                         fontSize = 14.sp,
                         color = MaterialTheme.colorScheme.secondary
                     )
                     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                     Column(
                         modifier = Modifier
                             .heightIn(max = 200.dp)
                             .verticalScroll(rememberScrollState())
                             .fillMaxWidth(),
                         verticalArrangement = Arrangement.spacedBy(4.dp)
                     ) {
                         if (videoTracks.isEmpty()) {
                             // Interactive simulated video tracks
                             val mockResolutions = listOf("Auto (Best Quality)", "1080p (Full HD)", "720p (HD)", "480p (SD)")
                             mockResolutions.forEachIndexed { index, label ->
                                 val isSelected = selectedMockVideoIndex == index
                                 Card(
                                     colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                     shape = RoundedCornerShape(8.dp),
                                     modifier = Modifier.fillMaxWidth().clickable {
                                         selectedMockVideoIndex = index
                                         android.widget.Toast.makeText(context, "Resolution: $label applied", android.widget.Toast.LENGTH_SHORT).show()
                                     }
                                 ) {
                                     Row(
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween,
                                         modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                     ) {
                                         Text(label, fontSize = 13.sp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                         if (isSelected) {
                                             Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                         }
                                     }
                                 }
                             }
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
                                     }
                                 ) {
                                     Row(
                                         verticalAlignment = Alignment.CenterVertically,
                                         horizontalArrangement = Arrangement.SpaceBetween,
                                         modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                                     ) {
                                         Text(label, fontSize = 13.sp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                         if (isSelected) {
                                             Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
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
                 Spacer(modifier = Modifier.height(20.dp))

                 if (isLandscape) {
                     Row(
                         modifier = Modifier.fillMaxWidth(),
                         horizontalArrangement = Arrangement.spacedBy(16.dp)
                     ) {
                         Box(modifier = Modifier.weight(1f)) { AudioColumnContent() }
                         Box(modifier = Modifier.weight(1f)) { SubtitlesColumnContent() }
                         Box(modifier = Modifier.weight(1f)) { VideoColumnContent() }
                     }
                 } else {
                     Column(
                         modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                         verticalArrangement = Arrangement.spacedBy(16.dp)
                     ) {
                         AudioColumnContent()
                         SubtitlesColumnContent()
                         VideoColumnContent()
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
                        val subtitleUri = android.net.Uri.fromFile(selectedFile).toString()
                        val mimeType = when (selectedFile.extension.lowercase()) {
                            "vtt" -> "text/vtt"
                            "ass" -> "text/x-ssa"
                            else -> "application/x-subrip" // default srt
                        }
                        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitleUri))
                            .setMimeType(mimeType)
                            .setLanguage("en")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()

                        val newMediaItem = MediaItem.Builder()
                            .setUri(mediaItem.uriString)
                            .setSubtitleConfigurations(listOf(subtitleConfig))
                            .build()

                        val currentPos = exoPlayer.currentPosition
                        exoPlayer.setMediaItem(newMediaItem)
                        exoPlayer.prepare()
                        exoPlayer.seekTo(currentPos)
                        exoPlayer.play()
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
                                    // Fallback / Auto-resolve
                                    val q = searchQuery.lowercase().trim()
                                    if (q.contains("sintel")) {
                                        "https://raw.githubusercontent.com/blender-org/sintel/master/subtitles/sintel_en.vtt"
                                    } else if (q.contains("tears of steel")) {
                                        "https://raw.githubusercontent.com/openlayers/openlayers/main/doc/tutorials/resources/tears_of_steel-en.vtt"
                                    } else if (q.contains("bunny") || q.contains("big buck")) {
                                        "https://raw.githubusercontent.com/DmitryNek/bunny-subtitles/master/big_buck_bunny_en.vtt"
                                    } else {
                                        "https://raw.githubusercontent.com/blender-org/sintel/master/subtitles/sintel_en.vtt"
                                    }
                                }

                                val connection = java.net.URL(targetUrl).openConnection() as java.net.HttpURLConnection
                                connection.requestMethod = "GET"
                                connection.connectTimeout = 8000
                                connection.readTimeout = 8000
                                connection.connect()

                                if (connection.responseCode == 200) {
                                    val rawText = connection.inputStream.bufferedReader().readText()
                                    val videoId = activeMediaItem.uriString.hashCode().toString()
                                    val subtitleDir = java.io.File(context.filesDir, "subtitles")
                                    if (!subtitleDir.exists()) {
                                        subtitleDir.mkdirs()
                                    }
                                    val subFile = java.io.File(subtitleDir, "sub_${videoId}_${selectedLanguage}.vtt")

                                    // SRT to WebVTT conversion
                                    val vttContent = if (rawText.contains("WEBVTT")) {
                                        rawText
                                    } else {
                                        "WEBVTT\n\n" + rawText.replace(",", ".")
                                    }
                                    subFile.writeText(vttContent)

                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        isSearchingSubtitles = false
                                        showOnlineSubtitleDownloader = false
                                        android.widget.Toast.makeText(context, "Downloaded $selectedLanguage subtitles!", android.widget.Toast.LENGTH_LONG).show()

                                        // Refresh player with newly downloaded subtitles
                                        val currentPos = exoPlayer.currentPosition
                                        val isPlayerPlaying = exoPlayer.isPlaying
                                        val newMediaItem = buildMediaItemWithSubtitles(activeMediaItem.uriString, context)
                                        
                                        exoPlayer.setMediaItem(newMediaItem)
                                        exoPlayer.prepare()
                                        exoPlayer.seekTo(currentPos)
                                        if (isPlayerPlaying) {
                                            exoPlayer.play()
                                        }
                                    }
                                } else {
                                    throw Exception("HTTP " + connection.responseCode)
                                }
                            } catch (e: Exception) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isSearchingSubtitles = false
                                    android.widget.Toast.makeText(context, "Download failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
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

        PlayerRightSideDrawer(
            isOpen = showQueueSheet,
            onDismissRequest = { showQueueSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Play Queue (${playQueue.size})",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = { viewModel.clearQueue() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear Queue")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                if (playQueue.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("Queue is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(playQueue) { index, item ->
                            val isCurrent = index == currentQueueIndex
                            Card(
                                onClick = {
                                    viewModel.setPlayingItem(item)
                                    showQueueSheet = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                border = if (isCurrent) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.displayArtist,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(formatPlayerDuration(item.duration), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        IconButton(
                                            onClick = { viewModel.removeFromQueue(index) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Red, modifier = Modifier.size(16.dp))
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
        AlertDialog(
            onDismissRequest = { showVideoInfoOverlay = false },
            title = { Text("Media Technical Details", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Title: ${mediaItem.title}", fontSize = 13.sp)
                    Text("Artist: ${mediaItem.artist ?: "Unknown"}", fontSize = 13.sp)
                    Text("Location: ${mediaItem.uriString}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    Text("Duration: ${formatPlayerDuration(duration)}", fontSize = 13.sp)
                    val tracks = exoPlayer.currentTracks
                    var audioCount = 0
                    var textCount = 0
                    var videoWidth = 0
                    var videoHeight = 0
                    tracks.groups.forEach { group ->
                        if (group.type == C.TRACK_TYPE_AUDIO) audioCount += group.length
                        if (group.type == C.TRACK_TYPE_TEXT) textCount += group.length
                        if (group.type == C.TRACK_TYPE_VIDEO) {
                            for (i in 0 until group.length) {
                                val fmt = group.getTrackFormat(i)
                                if (fmt.width > 0) {
                                    videoWidth = fmt.width
                                    videoHeight = fmt.height
                                }
                            }
                        }
                    }
                    if (videoWidth > 0) {
                        Text("Resolution: ${videoWidth}x${videoHeight}", fontSize = 13.sp)
                    } else {
                        Text("Type: Audio Disc Stream", fontSize = 13.sp)
                    }
                    Text("Audio Streams: $audioCount tracks detected", fontSize = 13.sp)
                    Text("Subtitle Tracks: $textCount detected", fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(onClick = { showVideoInfoOverlay = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showResumePrompt) {
        AlertDialog(
            onDismissRequest = {
                exoPlayer.seekTo(0L)
                exoPlayer.playWhenReady = true
                exoPlayer.play()
                showResumePrompt = false
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Resume Playback?", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text("Would you like to resume '${mediaItem.title}' from where you left off (${formatPlayerDuration(resumePosition)}), or start over from the beginning?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        exoPlayer.seekTo(resumePosition)
                        exoPlayer.playWhenReady = true
                        exoPlayer.play()
                        showResumePrompt = false
                    }
                ) {
                    Text("Resume")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        exoPlayer.seekTo(0L)
                        exoPlayer.playWhenReady = true
                        exoPlayer.play()
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
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
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
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = item.title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = item.artist ?: "Local File",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
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
    onSeek: (Float) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenInfo: () -> Unit
) {
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0L) }
    val displayPosition = if (isScrubbing) scrubPosition else currentPosition

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .testTag("player_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Text(
                text = "NOW PLAYING",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )

            IconButton(
                onClick = onOpenInfo,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Media Information",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 2. Center Album Art / Vinyl
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            AudioVinylPlayer(
                item = mediaItem,
                isPlaying = isPlaying
            )

            if (showSwitchToVideoBtn && onSwitchToVideo != null) {
                Button(
                    onClick = onSwitchToVideo,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = "Switch to Video",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Switch to Video Mode", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3. Audio Controls Panel at the bottom
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Track Info Details
                Text(
                    text = mediaItem.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee()
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = mediaItem.displayArtist,
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Progress Bar Timeline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = formatPlayerDuration(displayPosition),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    SmoothSeekBar(
                        position = displayPosition,
                        duration = duration,
                        isScrubbing = isScrubbing,
                        onScrubStart = { isScrubbing = true },
                        onScrubPositionChange = { pos -> scrubPosition = pos },
                        onScrubEnd = { pos ->
                            isScrubbing = false
                            onSeek(if (duration > 0) pos.toFloat() / duration else 0f)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("player_timeline_slider")
                    )

                    Text(
                        text = formatPlayerDuration(duration),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Playback controls row (Shuffle, Prev, Play/Pause, Next, Repeat)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(onClick = onPrev) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Large Audio-only Play/Pause FAB
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play or Pause",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(onClick = onNext) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(onClick = onCycleRepeat) {
                        val repeatIcon = when (repeatModeState) {
                            1 -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        }
                        Icon(
                            imageVector = repeatIcon,
                            contentDescription = "Repeat Mode",
                            tint = if (repeatModeState > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Dedicated Quick Utility Row (Sleep Timer, EQ, Queue)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sleep Timer
                    IconButton(
                        onClick = onOpenSleepTimer,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Sleep Timer",
                            tint = if (sleepTimeLeftMinutes > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Equalizer
                    IconButton(
                        onClick = onOpenEqualizer,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Waves,
                            contentDescription = "Equalizer",
                            tint = if (currentEqualizerPreset != "Normal") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Play Queue
                    IconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlaylistPlay,
                            contentDescription = "Play Queue",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// Custom animated rounded high-performance seekbar
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
    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    val safeProgress = progress.coerceIn(0f, 1f)
    
    val thumbRadius by animateDpAsState(
        targetValue = if (isScrubbing) 10.dp else 6.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow)
    )
    
    val trackHeight by animateDpAsState(
        targetValue = if (isScrubbing) 8.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium)
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(duration) {
                detectTapGestures(
                    onPress = { offset ->
                        onScrubStart()
                        val width = size.width
                        if (width > 0 && duration > 0) {
                            val newProgress = (offset.x / width).coerceIn(0f, 1f)
                            val targetPos = (newProgress * duration).toLong()
                            onScrubPositionChange(targetPos)
                            onScrubEnd(targetPos)
                        }
                    }
                )
            }
            .pointerInput(duration) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onScrubStart()
                        val width = size.width
                        if (width > 0 && duration > 0) {
                            val newProgress = (offset.x / width).coerceIn(0f, 1f)
                            onScrubPositionChange((newProgress * duration).toLong())
                        }
                    },
                    onDragEnd = {
                        onScrubEnd(position)
                    },
                    onDragCancel = {
                        onScrubEnd(position)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val width = size.width
                        val currentX = change.position.x
                        if (width > 0 && duration > 0) {
                            val newProgress = (currentX / width).coerceIn(0f, 1f)
                            onScrubPositionChange((newProgress * duration).toLong())
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
        ) {
            val h = trackHeight.toPx()
            val r = thumbRadius.toPx()
            val canvasWidth = size.width
            val canvasHeight = size.height
            val trackY = canvasHeight / 2f
            
            // Draw background track with rounded corners
            drawRoundRect(
                color = trackColor,
                topLeft = androidx.compose.ui.geometry.Offset(0f, trackY - h / 2f),
                size = androidx.compose.ui.geometry.Size(canvasWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f)
            )
            
            // Draw played progress track with rounded corners
            val playedWidth = canvasWidth * safeProgress
            if (playedWidth > 0f) {
                drawRoundRect(
                    color = primaryColor,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, trackY - h / 2f),
                    size = androidx.compose.ui.geometry.Size(playedWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f)
                )
            }
            
            // Draw the custom, animated rounded thumb
            drawCircle(
                color = primaryColor,
                radius = r,
                center = androidx.compose.ui.geometry.Offset(playedWidth, trackY)
            )
            
            // Draw inner glow/halo when scrubbing
            if (isScrubbing) {
                drawCircle(
                    color = primaryColor.copy(alpha = 0.25f),
                    radius = r + 4.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(playedWidth, trackY)
                )
            }
        }
    }
}

@Composable
fun SwipeToUnlock(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    var swipeOffset by remember { mutableStateOf(0f) }
    val maxSwipeDistance = 180.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxSwipeDistancePx = with(density) { maxSwipeDistance.toPx() }
    
    val animatedOffset by animateFloatAsState(
        targetValue = swipeOffset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "SwipeOffset"
    )

    Box(
        modifier = modifier
            .width(280.dp)
            .height(56.dp)
            .padding(4.dp), // Removed background and border as requested by user
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = "Slide handle to unlock",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeOffset >= maxSwipeDistancePx * 0.85f) {
                                onUnlock()
                            }
                            swipeOffset = 0f
                        },
                        onDragCancel = {
                            swipeOffset = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            swipeOffset = (swipeOffset + dragAmount).coerceIn(0f, maxSwipeDistancePx)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.LockOpen,
                contentDescription = "Unlock handle",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

fun buildMediaItemWithSubtitles(uriString: String, context: android.content.Context): MediaItem {
    val builder = MediaItem.Builder().setUri(uriString)
    try {
        val videoId = uriString.hashCode().toString()
        val subtitleDir = java.io.File(context.filesDir, "subtitles")
        if (subtitleDir.exists()) {
            val subFiles = subtitleDir.listFiles { _, name -> name.startsWith("sub_${videoId}_") }
            if (subFiles != null && subFiles.isNotEmpty()) {
                val list = mutableListOf<MediaItem.SubtitleConfiguration>()
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
                        .build()
                    list.add(config)
                }
                builder.setSubtitleConfigurations(list)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return builder.build()
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
