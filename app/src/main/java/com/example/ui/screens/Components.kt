package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import com.example.data.database.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MediaThumbnail(item: MediaEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(item.uriString) { mutableStateOf<Bitmap?>(null) }
    var triedToLoad by remember(item.uriString) { mutableStateOf(false) }

    LaunchedEffect(item.uriString) {
        if (!triedToLoad) {
            triedToLoad = true
            withContext(Dispatchers.IO) {
                try {
                    val uri = Uri.parse(item.uriString)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.uriString.startsWith("content://")) {
                        try {
                            bitmap = context.contentResolver.loadThumbnail(uri, android.util.Size(300, 300), null)
                        } catch (e: Exception) {
                            bitmap = getThumbnailFallback(context, uri, item.path)
                        }
                    } else {
                        bitmap = getThumbnailFallback(context, uri, item.path)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MediaThumbnail", "Error loading thumbnail for ${item.title}", e)
                }
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.04f)
                    )
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (item.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun getThumbnailFallback(context: android.content.Context, uri: Uri, path: String?): Bitmap? {
    val retriever = MediaMetadataRetriever()
    try {
        if (uri.scheme == "content") {
            retriever.setDataSource(context, uri)
        } else if (path != null && path.isNotEmpty()) {
            retriever.setDataSource(path)
        } else {
            return null
        }
        val artBytes = retriever.embeddedPicture
        if (artBytes != null) {
            return BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
        }
        // Fallback: extract a frame at 1s if video
        return retriever.getFrameAtTime(1000000)
    } catch (e: Exception) {
        return null
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    onClick: () -> Unit,
    tooltip: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit
) {
    if (tooltip.isEmpty()) {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
            content = content
        )
    } else {
        val state = rememberTooltipState()
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip {
                    Text(tooltip)
                }
            },
            state = state,
            modifier = Modifier
        ) {
            IconButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                colors = colors,
                content = content
            )
        }
    }
}




