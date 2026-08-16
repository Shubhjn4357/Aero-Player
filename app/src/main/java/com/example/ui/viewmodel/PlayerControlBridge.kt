package com.example.ui.viewmodel

import androidx.media3.exoplayer.ExoPlayer
import com.example.player.VlcPlayerWrapper
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
    private val bridgeScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun onPlayerStateChanged(playing: Boolean) {
        isPlayingListener?.invoke(playing)
    }

    fun play() {
        if (onPlayListener != null) {
            onPlayListener?.invoke()
            return
        }
        if (onPlayPauseListener != null) {
            onPlayPauseListener?.invoke()
            return
        }
        if (activeEngineType == "VLC") {
            vlcPlayerRef?.get()?.let { if (!it.isPlaying) it.play() }
        } else {
            exoPlayerRef?.get()?.let { if (!it.isPlaying) it.play() }
        }
    }

    fun pause() {
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
        if (onPlayPauseListener != null) {
            onPlayPauseListener?.invoke()
            return
        }
        if (activeEngineType == "VLC") {
            vlcPlayerRef?.get()?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        } else {
            exoPlayerRef?.get()?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
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

    fun onHeadsetHookClick() {
        hookClickCount++
        hookJob?.cancel()
        hookJob = bridgeScope.launch {
            delay(350L) // Wait to differentiate single, double, or triple click on earbuds
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

    fun onVolumeKeyPressed(isUp: Boolean) {
        onVolumeKeyPressedListener?.invoke(isUp)
    }
}

