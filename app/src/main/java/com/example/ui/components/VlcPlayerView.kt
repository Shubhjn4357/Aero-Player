package com.example.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    modifier: Modifier = Modifier,
    aspectRatio: String? = null,
    scale: Float = 0f
) {
    var boundLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

    AndroidView(
        factory = { context ->
            VLCVideoLayout(context).apply {
                keepScreenOn = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                boundLayout = this
                vlcPlayer.attachLayout(this)
                vlcPlayer.setAspectRatio(aspectRatio)
                vlcPlayer.setScale(scale)
            }
        },
        update = { layout ->
            boundLayout = layout
            vlcPlayer.attachLayout(layout)
            vlcPlayer.setAspectRatio(aspectRatio)
            vlcPlayer.setScale(scale)
        },
        onRelease = { layout ->
            // Retain layout during transient recomposition to prevent blank frames
        },
        modifier = modifier
    )

    DisposableEffect(vlcPlayer) {
        onDispose {
            boundLayout?.let { vlcPlayer.detachLayout(it) }
        }
    }
}
