package com.aerotech.aeroplayer.ui.viewmodel

import androidx.media3.exoplayer.ExoPlayer
import com.aerotech.aeroplayer.player.VlcPlayerWrapper
import java.lang.ref.WeakReference
import kotlinx.coroutines.*

object PlayerControlBridge {
    var vlcPlayerRef: WeakReference<VlcPlayerWrapper>? = null
    var exoPlayerRef: WeakReference<ExoPlayer>? = null
    var viewModelRef: WeakReference<MainViewModel>? = null
    var activeEngineName: String = "Auto (Smart Format Detection)"
    var activeEngineType: String = "ExoPlayer" // "ExoPlayer" or "VLC"
    
    var isPlayingListener: ((Boolean) -> Unit)? = null
    var onPlayPauseListener: (() -> Unit)? = null
    var onPlayListener: (() -> Unit)? = null
    var onPauseListener: (() -> Unit)? = null
    var onNextListener: (() -> Unit)? = null
    var onPrevListener: (() -> Unit)? = null
    var onSeekByListener: ((Long) -> Unit)? = null
    var onSeekToListener: ((Long) -> Unit)? = null
    var onVolumeKeyPressedListener: ((Boolean) -> Unit)? = null

    // Multi-click detection for earbud headset hook button
    private var hookClickCount = 0
    private var hookJob: Job? = null
    private var lastHookTimestamp: Long = 0L
    private var lastPlayPauseTimestamp: Long = 0L
    private val bridgeScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun onPlayerStateChanged(playing: Boolean) {
        isPlayingListener?.invoke(playing)
    }

    fun play() {
        val now = System.currentTimeMillis()
        if (now - lastPlayPauseTimestamp < 250L) return
        lastPlayPauseTimestamp = now

        // Cancel any pending headset hook job to avoid double toggle
        hookJob?.cancel()
        hookClickCount = 0

        if (onPlayListener != null) {
            onPlayListener?.invoke()
            return
        }
        if (activeEngineType == "VLC") {
            vlcPlayerRef?.get()?.let { player ->
                if (player.isEndedState()) {
                    player.seekTo(0)
                }
                if (!player.isPlaying) player.play()
            }
        } else {
            exoPlayerRef?.get()?.let { player ->
                if (player.playerError != null || player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    player.prepare()
                }
                if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    player.seekTo(0)
                } else {
                    val curPos = player.currentPosition
                    if (curPos > 0L) {
                        player.seekTo(curPos)
                    }
                }
                if (!player.isPlaying) player.play()
            }
        }
    }

    fun pause() {
        val now = System.currentTimeMillis()
        if (now - lastPlayPauseTimestamp < 250L) return
        lastPlayPauseTimestamp = now

        // Cancel any pending headset hook job to avoid double toggle
        hookJob?.cancel()
        hookClickCount = 0

        if (onPauseListener != null) {
            onPauseListener?.invoke()
            return
        }
        if (activeEngineType == "VLC") {
            vlcPlayerRef?.get()?.let { if (it.isPlaying) it.pause() }
        } else {
            exoPlayerRef?.get()?.let { if (it.isPlaying) it.pause() }
        }
    }

    fun playPause() {
        val now = System.currentTimeMillis()
        if (now - lastPlayPauseTimestamp < 250L) return
        lastPlayPauseTimestamp = now

        // Cancel any pending headset hook job to avoid double toggle
        hookJob?.cancel()
        hookClickCount = 0

        if (onPlayPauseListener != null) {
            onPlayPauseListener?.invoke()
            return
        }
        if (activeEngineType == "VLC") {
            vlcPlayerRef?.get()?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.isEndedState()) {
                        player.seekTo(0)
                    }
                    player.play()
                }
            }
        } else {
            exoPlayerRef?.get()?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.playerError != null || player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                        player.prepare()
                    }
                    if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        player.seekTo(0)
                    } else {
                        val curPos = player.currentPosition
                        if (curPos > 0L) {
                            player.seekTo(curPos)
                        }
                    }
                    player.play()
                }
            }
        }
    }

    fun next() {
        if (onNextListener != null) {
            onNextListener?.invoke()
            return
        }
        viewModelRef?.get()?.playNext()
    }

    fun prev() {
        if (onPrevListener != null) {
            onPrevListener?.invoke()
            return
        }
        viewModelRef?.get()?.playPrevious()
    }

    fun seekBy(offsetMs: Long) {
        if (onSeekByListener != null) {
            onSeekByListener?.invoke(offsetMs)
            return
        }
        if (activeEngineType == "VLC") {
            vlcPlayerRef?.get()?.let { player ->
                val target = (player.currentPositionMs + offsetMs).coerceAtLeast(0L)
                player.seekTo(target)
            }
        } else {
            exoPlayerRef?.get()?.let { player ->
                val target = (player.currentPosition + offsetMs).coerceAtLeast(0L)
                player.seekTo(target)
            }
        }
    }

    fun seekTo(targetMs: Long) {
        if (onSeekToListener != null) {
            onSeekToListener?.invoke(targetMs)
            return
        }
        if (activeEngineType == "VLC") {
            vlcPlayerRef?.get()?.seekTo(targetMs.coerceAtLeast(0L))
        } else {
            exoPlayerRef?.get()?.seekTo(targetMs.coerceAtLeast(0L))
        }
    }

    fun stop() {
        if (activeEngineType == "VLC") {
            vlcPlayerRef?.get()?.stop()
        } else {
            exoPlayerRef?.get()?.stop()
        }
    }

    fun toggleSubtitles() {
        val vm = viewModelRef?.get()
        if (vm != null) {
            val isEnabled = vm.subtitleEngine.state.value.isEnabled
            vm.subtitleEngine.setEnabled(!isEnabled)
        }
        val vlc = vlcPlayerRef?.get()
        if (vlc != null) {
            val currentSpu = vlc.getSelectedSubtitleTrack()
            if (currentSpu == -1) {
                val tracks = vlc.getSubtitleTracks()
                val firstValid = tracks.firstOrNull { it.id != -1 }
                if (firstValid != null) vlc.selectSubtitleTrack(firstValid.id)
            } else {
                vlc.selectSubtitleTrack(-1)
            }
        }
    }

    fun adjustSubtitleDelay(offsetMs: Long) {
        val vm = viewModelRef?.get()
        if (vm != null) {
            val current = vm.subtitleEngine.state.value.subDelayMs
            vm.subtitleEngine.setSubtitleDelay(current + offsetMs)
        }
        val vlc = vlcPlayerRef?.get()
        if (vlc != null) {
            vlc.setSubtitleDelay(vlc.getSubtitleDelay() + (offsetMs * 1000L))
        }
    }

    fun setSubtitleEncoding(encoding: String) {
        val vm = viewModelRef?.get()
        if (vm != null) {
            vm.updateSubtitleEncoding(encoding)
        }
        val vlc = vlcPlayerRef?.get()
        if (vlc != null) {
            vlc.setSubtitleEncoding(encoding)
        }
    }

    fun loadUri(uriString: String) {
        val vm = viewModelRef?.get() ?: return
        bridgeScope.launch(Dispatchers.Main) {
            try {
                val item = com.aerotech.aeroplayer.data.database.MediaEntity(
                    uriString = uriString,
                    title = uriString.substringAfterLast("/"),
                    artist = null,
                    album = null,
                    duration = 0L,
                    size = 0L,
                    dateAdded = System.currentTimeMillis(),
                    isVideo = true,
                    path = uriString,
                    mimeType = null
                )
                vm.setPlayingItemWithQueue(item, listOf(item), 0)
            } catch (e: Exception) {
                android.util.Log.e("PlayerControlBridge", "Failed to load URI via CLI: $uriString", e)
            }
        }
    }

    fun onHeadsetHookClick() {
        val now = System.currentTimeMillis()
        if (now - lastHookTimestamp < 75L) {
            // Deduplicate rapid dual dispatches of the same physical button press
            return
        }
        lastHookTimestamp = now

        val prefs = viewModelRef?.get()?.preferencesState?.value
        if (prefs != null && prefs.ignoreHeadsetButtons) {
            return
        }
        hookClickCount++
        hookJob?.cancel()
        hookJob = bridgeScope.launch {
            delay(300L) // Wait to differentiate single, double, or triple click on earbuds
            val clicks = hookClickCount
            hookClickCount = 0
            when (clicks) {
                1 -> playPause()
                2 -> next()
                3 -> prev()
                else -> playPause()
            }
        }
    }

    fun onHeadsetPluggedIn() {
        val prefs = viewModelRef?.get()?.preferencesState?.value
        if (prefs == null || prefs.resumeOnHeadsetInsertion) {
            play()
        }
    }

    fun onVolumeKeyPressed(isUp: Boolean) {
        onVolumeKeyPressedListener?.invoke(isUp)
    }
}

