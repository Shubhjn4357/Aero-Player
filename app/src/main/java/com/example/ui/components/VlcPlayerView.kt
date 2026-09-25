package com.example.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
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

/**
 * Jetpack Compose host for LibVLC video rendering surface.
 * Directly connects a SurfaceView's SurfaceHolder to LibVLC's IVLCVout,
 * ensuring robust native video rendering in Jetpack Compose.
 */
@Composable
fun VlcPlayerView(
    vlcPlayer: VlcPlayerWrapper,
    modifier: Modifier = Modifier,
    aspectRatio: String? = null,
    scale: Float = 0f
) {
    var boundSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                val surfaceView = SurfaceView(context).apply {
                    keepScreenOn = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            vlcPlayer.attachSurface(this@apply, holder)
                            vlcPlayer.setAspectRatio(aspectRatio)
                            vlcPlayer.setScale(scale)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            vlcPlayer.onSurfaceSizeChanged(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            vlcPlayer.detachSurface(this@apply)
                        }
                    })
                }
                addView(surfaceView)
                boundSurfaceView = surfaceView
            }
        },
        update = { frameLayout ->
            val surfaceView = boundSurfaceView ?: (frameLayout.getChildAt(0) as? SurfaceView)
            if (surfaceView != null) {
                boundSurfaceView = surfaceView
                if (surfaceView.holder.surface.isValid) {
                    vlcPlayer.attachSurface(surfaceView, surfaceView.holder)
                    if (vlcPlayer.isPlaying || vlcPlayer.currentPositionMs > 0L) {
                        vlcPlayer.play()
                    }
                }
                vlcPlayer.setAspectRatio(aspectRatio)
                vlcPlayer.setScale(scale)
            }
        },
        onRelease = {
            // Surface lifecycle is properly managed via holder callback and DisposableEffect
        },
        modifier = modifier
    )

    DisposableEffect(vlcPlayer) {
        onDispose {
            boundSurfaceView?.let { vlcPlayer.detachSurface(it) }
        }
    }
}
