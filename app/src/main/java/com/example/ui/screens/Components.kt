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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.unit.Dp
import com.example.data.database.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val globalThumbnailCache = androidx.collection.LruCache<String, Bitmap>(350)

object ThumbnailManager {
    private fun getDiskFile(context: android.content.Context, uriString: String): java.io.File {
        val dir = java.io.File(context.cacheDir, "media_thumbs")
        if (!dir.exists()) dir.mkdirs()
        val key = uriString.hashCode().toString()
        return java.io.File(dir, "$key.webp")
    }

    suspend fun getThumbnail(context: android.content.Context, item: MediaEntity): Bitmap? = withContext(Dispatchers.IO) {
        val memCached = globalThumbnailCache.get(item.uriString)
        if (memCached != null) return@withContext memCached

        val diskFile = getDiskFile(context, item.uriString)
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = BitmapFactory.decodeFile(diskFile.absolutePath, opts)
                if (bitmap != null) {
                    globalThumbnailCache.put(item.uriString, bitmap)
                    return@withContext bitmap
                }
            } catch (e: Exception) {}
        }

        val extracted = extractThumbnail(context, item)
        if (extracted != null) {
            val scaled = downscaleBitmap(extracted, 180, 180)
            globalThumbnailCache.put(item.uriString, scaled)
            saveToDisk(diskFile, scaled)
            return@withContext scaled
        }
        null
    }

    suspend fun preloadThumbnails(context: android.content.Context, items: List<MediaEntity>) = withContext(Dispatchers.IO) {
        items.take(30).forEach { item ->
            if (globalThumbnailCache.get(item.uriString) == null) {
                getThumbnail(context, item)
            }
        }
    }

    private fun extractThumbnail(context: android.content.Context, item: MediaEntity): Bitmap? {
        val uri = com.example.util.ContentResolverUtils.resolvePlayableUri(context, item.uriString, item.path)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            try {
                return context.contentResolver.loadThumbnail(uri, android.util.Size(180, 180), null)
            } catch (e: Exception) {}
        }
        return getThumbnailFallback(context, uri, item.path)
    }

    private fun saveToDisk(file: java.io.File, bitmap: Bitmap) {
        try {
            java.io.FileOutputStream(file).use { out ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
            }
        } catch (e: Exception) {}
    }

    private fun downscaleBitmap(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (src.width <= maxW && src.height <= maxH) return src
        val ratio = Math.min(maxW.toFloat() / src.width, maxH.toFloat() / src.height)
        val targetW = (src.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }
}

@Composable
fun MediaThumbnail(item: MediaEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(item.uriString) { mutableStateOf<Bitmap?>(globalThumbnailCache.get(item.uriString)) }

    if (bitmap == null) {
        LaunchedEffect(item.uriString) {
            bitmap = ThumbnailManager.getThumbnail(context, item)
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
                modifier = Modifier.size(20.dp)
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
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = 2
            }
            return BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, opts)
        }
        // Fallback: extract a frame at 1s if video
        return retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (e: Exception) {
        return null
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {}
    }
}

fun formatMediaDuration(durationMs: Long): String {
    if (durationMs <= 0) return "00:00"
    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatMediaFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    if (digitGroups == 0) return "$bytes B"
    return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatMediaLengthAndSize(durationMs: Long, sizeBytes: Long): String {
    val durationStr = formatMediaDuration(durationMs)
    val sizeStr = formatMediaFileSize(sizeBytes)
    return when {
        durationStr.isNotEmpty() && durationStr != "00:00" && sizeStr.isNotEmpty() -> "$durationStr • $sizeStr"
        durationStr.isNotEmpty() && durationStr != "00:00" -> durationStr
        sizeStr.isNotEmpty() -> sizeStr
        else -> durationStr
    }
}

fun getMediaQualityLabel(path: String?, title: String, isVideo: Boolean, durationMs: Long, sizeBytes: Long, mimeType: String? = null): String {
    val cleanPath = path ?: ""
    val titleLower = "$title $cleanPath ${mimeType ?: ""}".lowercase()
    if (isVideo) {
        if (titleLower.contains("2160p") || titleLower.contains("4k") || titleLower.contains("uhd")) return "4K UHD"
        if (titleLower.contains("1080p") || titleLower.contains("fhd")) return "1080p FHD"
        if (titleLower.contains("720p") || titleLower.contains("hd")) return "720p HD"
        if (titleLower.contains("480p") || titleLower.contains("sd")) return "480p"
        if (titleLower.contains("360p")) return "360p"
        if (titleLower.contains("240p")) return "240p"

        if (sizeBytes > 0 && durationMs > 0) {
            val durationSecs = durationMs / 1000.0
            if (durationSecs > 0) {
                val bitrateKbps = ((sizeBytes * 8.0) / durationSecs) / 1000.0
                return when {
                    bitrateKbps >= 12000 -> "4K UHD"
                    bitrateKbps >= 4000 -> "1080p"
                    bitrateKbps >= 1800 -> "720p"
                    bitrateKbps >= 700 -> "480p"
                    else -> "360p"
                }
            }
        }
        val ext = cleanPath.substringAfterLast('.', "").uppercase()
        return if (ext.isNotEmpty() && ext.length <= 4) "HD • $ext" else "HD"
    } else {
        val ext = cleanPath.substringAfterLast('.', "").lowercase()
        if (ext == "flac" || titleLower.contains("flac")) return "FLAC"
        if (ext == "wav" || titleLower.contains("wav")) return "WAV"
        if (ext == "alac" || titleLower.contains("alac")) return "ALAC"
        if (ext == "m4a" || ext == "aac") return "AAC"
        if (ext == "ogg" || ext == "opus") return "OGG"

        if (sizeBytes > 0 && durationMs > 0) {
            val durationSecs = durationMs / 1000.0
            if (durationSecs > 0) {
                val bitrateKbps = (((sizeBytes * 8.0) / durationSecs) / 1000.0).toInt()
                if (bitrateKbps >= 280) return "320k"
                if (bitrateKbps >= 220) return "256k"
                if (bitrateKbps >= 160) return "192k"
                if (bitrateKbps >= 100) return "128k"
            }
        }
        return if (ext.isNotEmpty() && ext.length <= 4) ext.uppercase() else "HQ Audio"
    }
}

fun getMediaQualityLabel(item: com.example.data.database.MediaEntity): String {
    return getMediaQualityLabel(item.path, item.title, item.isVideo, item.duration, item.size, item.mimeType)
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

@Composable
fun FrostedGlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val ambientGradient = Brush.radialGradient(
        colors = if (isDark) {
            listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                MaterialTheme.colorScheme.background
            )
        } else {
            listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f),
                MaterialTheme.colorScheme.background
            )
        },
        radius = 1600f
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(ambientGradient)
    ) {
        content()
    }
}

@Composable
fun FrostedGlassCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    border: androidx.compose.foundation.BorderStroke? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = shape,
        border = border,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        content()
    }
}

fun Modifier.bounceClick(
    onClick: (() -> Unit)? = null,
    pressedScale: Float = 0.90f
) = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "bounce_scale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    onClick = onClick
                )
            } else Modifier
        )
}

@Composable
fun BounceIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "bounce_icon_scale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun AnimatedPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 28.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.86f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "button_scale"
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.15f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioHighBouncy
        ),
        label = "icon_play_scale"
    )

    val rotation by animateFloatAsState(
        targetValue = if (isPlaying) 180f else 0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "icon_rotation"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = contentColor),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = isPlaying, label = "play_pause_crossfade") { playing ->
            Icon(
                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = contentColor,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        rotationZ = rotation
                    }
            )
        }
    }
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.82f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "icon_btn_scale"
    )

    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "filled_icon_btn_scale"
    )

    androidx.compose.material3.FilledIconButton(
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun OutlinedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.outlinedIconButtonColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "outlined_icon_btn_scale"
    )

    androidx.compose.material3.OutlinedIconButton(
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: androidx.compose.foundation.BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "btn_scale"
    )

    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: androidx.compose.foundation.BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "outlined_btn_scale"
    )

    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: androidx.compose.foundation.BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "text_btn_scale"
    )

    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun FloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = FloatingActionButtonDefaults.shape,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "fab_scale"
    )

    androidx.compose.material3.FloatingActionButton(
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = elevation,
        interactionSource = interactionSource,
        content = content
    )
}





