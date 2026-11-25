package com.emptysol.aeroplayer.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.emptysol.aeroplayer.player.VlcPlayerWrapper

/**
 * Jetpack Compose host for LibVLC video rendering surface.
 * Directly connects a SurfaceView's SurfaceHolder to LibVLC's IVLCVout
 * without triggering redundant re-attachments during HUD/scrubbing recompositions.
 */
@Composable
fun VlcPlayerView(
    vlcPlayer: VlcPlayerWrapper,
    modifier: Modifier = Modifier,
    aspectRatio: String? = null,
    scale: Float = 0f
) {
    var boundSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    val latestAspectRatio by rememberUpdatedState(aspectRatio)
    val latestScale by rememberUpdatedState(scale)

    LaunchedEffect(vlcPlayer, aspectRatio, scale) {
        vlcPlayer.setAspectRatio(aspectRatio)
        vlcPlayer.setScale(scale)
    }

    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                val surfaceView = SurfaceView(context)
                surfaceView.keepScreenOn = true
                surfaceView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        vlcPlayer.attachSurface(surfaceView, holder)
                        vlcPlayer.setAspectRatio(latestAspectRatio)
                        vlcPlayer.setScale(latestScale)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        vlcPlayer.onSurfaceSizeChanged(width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        vlcPlayer.detachSurface(surfaceView)
                    }
                })
                addView(surfaceView)
                boundSurfaceView = surfaceView
            }
        },
        update = { frameLayout ->
            val surfaceView = boundSurfaceView ?: (frameLayout.getChildAt(0) as? SurfaceView)
            if (surfaceView != null && boundSurfaceView !== surfaceView) {
                boundSurfaceView = surfaceView
                if (surfaceView.holder.surface.isValid) {
                    vlcPlayer.attachSurface(surfaceView, surfaceView.holder)
                }
            }
        },
        onRelease = {
            // Surface lifecycle is managed via holder callback and DisposableEffect
        },
        modifier = modifier
    )

    DisposableEffect(vlcPlayer) {
        onDispose {
            boundSurfaceView?.let { vlcPlayer.detachSurface(it) }
        }
    }
}
