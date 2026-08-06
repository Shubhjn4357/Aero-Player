package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.player.VlcPlayerWrapper
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Jetpack Compose AndroidView container for LibVLC (vlcjni) rendering via VLCVideoLayout.
 */
@Composable
fun VlcPlayerView(
    vlcPlayer: VlcPlayerWrapper,
    modifier: Modifier = Modifier
) {
    DisposableEffect(vlcPlayer) {
        onDispose {
            vlcPlayer.detachLayout()
        }
    }

    AndroidView(
        factory = { context ->
            VLCVideoLayout(context).apply {
                vlcPlayer.attachLayout(this)
            }
        },
        update = { layout ->
            vlcPlayer.attachLayout(layout)
        },
        modifier = modifier
    )
}
