package com.example.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.player.VlcPlayerWrapper
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Jetpack Compose host for LibVLC video rendering surface.
 */
@Composable
fun VlcPlayerView(
    vlcPlayer: VlcPlayerWrapper,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            VLCVideoLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                vlcPlayer.attachLayout(this)
            }
        },
        update = { layout ->
            vlcPlayer.attachLayout(layout)
        },
        onRelease = { _ ->
            // Retain layout binding across transient recompositions
        },
        modifier = modifier
    )

    DisposableEffect(vlcPlayer) {
        onDispose {
            vlcPlayer.detachLayout()
        }
    }
}
