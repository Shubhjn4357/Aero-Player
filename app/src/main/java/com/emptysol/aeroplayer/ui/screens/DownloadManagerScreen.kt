package com.emptysol.aeroplayer.ui.screens

import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emptysol.aeroplayer.data.database.MediaEntity
import com.emptysol.aeroplayer.ui.components.AeroExplorerEmptyState
import com.emptysol.aeroplayer.ui.components.AeroStatusBadge
import com.emptysol.aeroplayer.ui.components.AeroTonalCard
import com.emptysol.aeroplayer.ui.components.AppScreenScaffold
import com.emptysol.aeroplayer.ui.theme.AeroTheme
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class DirectDownloadItem(
    val id: Long,
    val title: String,
    val sourceUri: String,
    val localUri: String,
    val mimeType: String,
    val status: Int,
    val reason: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val lastModifiedMs: Long
) {
    val statusLabel: String
        get() = when (status) {
            DownloadManager.STATUS_RUNNING -> "DOWNLOADING"
            DownloadManager.STATUS_PENDING -> "PENDING"
            DownloadManager.STATUS_PAUSED -> "PAUSED"
            DownloadManager.STATUS_SUCCESSFUL -> "COMPLETED"
            DownloadManager.STATUS_FAILED -> "FAILED"
            else -> "QUEUED"
        }

    val isActive: Boolean
        get() = status == DownloadManager.STATUS_RUNNING ||
            status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_PAUSED

    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Dedicated Direct / HTTP Download Manager backed by Android's native DownloadManager.
 * Supports direct media/file URLs, smart magnet/.torrent redirection to Torrent Manager,
 * live progress tracking, folder browsing, and 1-tap playback in Aero Player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPlayMedia: (MediaEntity) -> Unit,
    onOpenFolderInApp: (String) -> Unit = {},
    onOpenTorrentManager: () -> Unit = {},
    onOpenVpnManager: () -> Unit = {}
) {
    val context = LocalContext.current
    var urlInput by rememberSaveable { mutableStateOf("") }
    var filterChip by rememberSaveable { mutableStateOf("All") }
    var downloads by remember { mutableStateOf<List<DirectDownloadItem>>(emptyList()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    val trimmedInput = urlInput.trim()
    val isMagnetOrTorrent = remember(trimmedInput) {
        trimmedInput.startsWith("magnet:", ignoreCase = true) ||
            trimmedInput.lowercase(Locale.US).endsWith(".torrent")
    }

    // Live poll Android DownloadManager on IO dispatcher
    LaunchedEffect(refreshTrigger) {
        while (isActive) {
            val loaded = withContext(Dispatchers.IO) {
                querySystemDownloads(context)
            }
            downloads = loaded
            val hasActive = loaded.any { it.isActive }
            delay(if (hasActive) 1200L else 4000L)
        }
    }

    val filteredDownloads = remember(downloads, filterChip) {
        when (filterChip) {
            "Active" -> downloads.filter { it.isActive }
            "Completed" -> downloads.filter { it.status == DownloadManager.STATUS_SUCCESSFUL }
            "Failed" -> downloads.filter { it.status == DownloadManager.STATUS_FAILED }
            else -> downloads
        }
    }

    val activeCount = remember(downloads) { downloads.count { it.isActive } }
    val completedCount = remember(downloads) { downloads.count { it.status == DownloadManager.STATUS_SUCCESSFUL } }

    fun enqueueDirectUrl(rawUrl: String) {
        val cleanUrl = rawUrl.trim()
        if (cleanUrl.isBlank()) return
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            Toast.makeText(context, "Enter a valid http:// or https:// download URL", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: throw IllegalStateException("System DownloadManager unavailable")
            val parsedUri = Uri.parse(cleanUrl)
            val derivedName = parsedUri.lastPathSegment
                ?.substringBefore('?')
                ?.takeIf { it.isNotBlank() && it.length <= 120 }
                ?: "aero_download_${System.currentTimeMillis()}"

            val request = DownloadManager.Request(parsedUri)
                .setTitle(derivedName)
                .setDescription("Downloading with Aero Player")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            try {
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "AeroDownloads/$derivedName"
                )
            } catch (_: Throwable) {
                request.setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    derivedName
                )
            }

            dm.enqueue(request)
            urlInput = ""
            refreshTrigger++
            Toast.makeText(context, "Started download: $derivedName", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(context, "Unable to start download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    AppScreenScaffold(
        title = "Download Manager",
        subtitle = "$activeCount active • $completedCount completed • Direct HTTP/HTTPS",
        onNavigateBack = onBack,
        backButtonTestTag = "download_manager_back_btn",
        actions = {
            IconButton(
                onClick = onOpenTorrentManager,
                modifier = Modifier.testTag("download_open_torrents_btn")
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = "Open Torrent Manager", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                onClick = onOpenVpnManager,
                modifier = Modifier.testTag("download_open_vpn_btn")
            ) {
                Icon(Icons.Default.VpnKey, contentDescription = "Open VPN Manager", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    ) {
        // URL Input Card (Tonal surface without heavy borders)
        AeroTonalCard(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("download_url_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "New Direct Download",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = { refreshTrigger++ },
                        modifier = Modifier.size(32.dp).testTag("download_refresh_btn")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh Downloads",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("https://example.com/video.mp4", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (urlInput.isNotEmpty()) {
                            IconButton(onClick = { urlInput = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear URL", modifier = Modifier.size(18.dp))
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                    if (!clipText.isNullOrBlank()) {
                                        urlInput = clipText.trim()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste URL", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!isMagnetOrTorrent) enqueueDirectUrl(urlInput) }),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("download_url_input")
                )

                    if (isMagnetOrTorrent) {
                        FilledTonalButton(
                            onClick = {
                                viewModel.addTorrentFromInput(trimmedInput) { res ->
                                    res.onSuccess {
                                        Toast.makeText(context, "Sent to Torrent Manager: ${it.title}", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(context, it.message ?: "Invalid torrent link", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                urlInput = ""
                                onOpenTorrentManager()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_send_to_torrent_manager")
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Detected Magnet / .torrent — Open in Torrent Manager", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = { enqueueDirectUrl(urlInput) },
                            enabled = trimmedInput.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_start_http_download")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Start Download", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Active", "Completed", "Failed").forEach { label ->
                    FilterChip(
                        selected = filterChip == label,
                        onClick = { filterChip = label },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.testTag("download_filter_${label.lowercase(Locale.US)}")
                    )
                }
            }

            if (filteredDownloads.isEmpty()) {
                AeroExplorerEmptyState(
                    icon = Icons.Default.DownloadForOffline,
                    title = "No downloads in \"$filterChip\"",
                    subtitle = "Paste any direct HTTP/HTTPS video, audio, or file URL above to download directly into AeroDownloads.",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredDownloads, key = { it.id }) { item ->
                        DirectDownloadCard(
                            item = item,
                            onPlay = {
                                val media = buildMediaEntityFromDownload(item)
                                if (media != null) {
                                    onPlayMedia(media)
                                } else {
                                    Toast.makeText(context, "File is not ready for playback yet", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onOpenFolder = {
                                val dirPath = resolveDownloadFolder(item)
                                onOpenFolderInApp(dirPath)
                            },
                            onRemove = {
                                try {
                                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                                    dm?.remove(item.id)
                                    refreshTrigger++
                                    Toast.makeText(context, "Removed ${item.title}", Toast.LENGTH_SHORT).show()
                                } catch (_: Throwable) {
                                }
                            }
                        )
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
    }
}

@Composable
private fun DirectDownloadCard(
    item: DirectDownloadItem,
    onPlay: () -> Unit,
    onOpenFolder: () -> Unit,
    onRemove: () -> Unit
) {
    val isVideo = item.mimeType.startsWith("video/", ignoreCase = true) ||
        item.title.substringAfterLast('.', "").lowercase(Locale.US) in setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts")
    val isAudio = item.mimeType.startsWith("audio/", ignoreCase = true) ||
        item.title.substringAfterLast('.', "").lowercase(Locale.US) in setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus")
    val isCompleted = item.status == DownloadManager.STATUS_SUCCESSFUL
    val pct = (item.progressFraction * 100f).toInt().coerceIn(0, 100)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_item_${item.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isVideo -> Icons.Default.Movie
                            isAudio -> Icons.Default.MusicNote
                            else -> Icons.AutoMirrored.Filled.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${formatDownloadBytes(item.bytesDownloaded)} / ${if (item.totalBytes > 0L) formatDownloadBytes(item.totalBytes) else "Unknown"} • $pct%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val ext = AeroTheme.extendedColors
                AeroStatusBadge(
                    text = item.statusLabel,
                    containerColor = when (item.status) {
                        DownloadManager.STATUS_SUCCESSFUL -> ext.successContainer
                        DownloadManager.STATUS_FAILED -> MaterialTheme.colorScheme.errorContainer
                        else -> ext.infoContainer
                    },
                    contentColor = when (item.status) {
                        DownloadManager.STATUS_SUCCESSFUL -> ext.onSuccessContainer
                        DownloadManager.STATUS_FAILED -> MaterialTheme.colorScheme.onErrorContainer
                        else -> ext.onInfoContainer
                    }
                )
            }

            if (item.isActive || item.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { item.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCompleted && (isVideo || isAudio || item.localUri.isNotBlank())) {
                    FilledTonalButton(
                        onClick = onPlay,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("btn_play_download_${item.id}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onOpenFolder, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Open download folder", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onRemove, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Remove download", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun querySystemDownloads(context: Context): List<DirectDownloadItem> {
    return try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return emptyList()
        val results = mutableListOf<DirectDownloadItem>()
        val cursor: Cursor = dm.query(DownloadManager.Query()) ?: return emptyList()
        cursor.use { c ->
            val colId = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val colTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val colUri = c.getColumnIndex(DownloadManager.COLUMN_URI)
            val colLocalUri = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val colMime = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            val colStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val colReason = c.getColumnIndex(DownloadManager.COLUMN_REASON)
            val colDl = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val colTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val colModified = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)

            while (c.moveToNext()) {
                val id = if (colId >= 0) c.getLong(colId) else continue
                val title = if (colTitle >= 0) c.getString(colTitle).orEmpty() else ""
                val srcUri = if (colUri >= 0) c.getString(colUri).orEmpty() else ""
                val localUri = if (colLocalUri >= 0) c.getString(colLocalUri).orEmpty() else ""
                val mime = if (colMime >= 0) c.getString(colMime).orEmpty() else ""
                val status = if (colStatus >= 0) c.getInt(colStatus) else DownloadManager.STATUS_PENDING
                val reason = if (colReason >= 0) c.getInt(colReason) else 0
                val dl = if (colDl >= 0) c.getLong(colDl).coerceAtLeast(0L) else 0L
                val total = if (colTotal >= 0) c.getLong(colTotal).coerceAtLeast(0L) else 0L
                val modified = if (colModified >= 0) c.getLong(colModified) else 0L

                results.add(
                    DirectDownloadItem(
                        id = id,
                        title = title.ifBlank { Uri.parse(srcUri).lastPathSegment ?: "Download #$id" },
                        sourceUri = srcUri,
                        localUri = localUri,
                        mimeType = mime,
                        status = status,
                        reason = reason,
                        bytesDownloaded = dl,
                        totalBytes = total,
                        lastModifiedMs = modified
                    )
                )
            }
        }
        results.sortedByDescending { it.lastModifiedMs }
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun buildMediaEntityFromDownload(item: DirectDownloadItem): MediaEntity? {
    val uriStr = item.localUri.ifBlank { item.sourceUri }
    if (uriStr.isBlank()) return null
    val parsed = Uri.parse(uriStr)
    val filePath = if (parsed.scheme == "file") parsed.path.orEmpty() else uriStr
    val ext = item.title.substringAfterLast('.', "").lowercase(Locale.US)
    val isAudio = item.mimeType.startsWith("audio/", ignoreCase = true) ||
        ext in setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus")
    val isVideo = !isAudio
    return MediaEntity(
        uriString = uriStr,
        title = item.title.substringBeforeLast('.').ifBlank { item.title },
        artist = "Aero Downloads",
        album = "Downloads",
        duration = 0L,
        size = item.totalBytes.coerceAtLeast(item.bytesDownloaded),
        dateAdded = item.lastModifiedMs / 1000L,
        isVideo = isVideo,
        path = filePath,
        mimeType = item.mimeType.ifBlank { if (isVideo) "video/*" else "audio/*" },
        genre = if (isVideo) "Video" else "Audio"
    )
}

private fun resolveDownloadFolder(item: DirectDownloadItem): String {
    val parsed = Uri.parse(item.localUri)
    if (parsed.scheme == "file" && !parsed.path.isNullOrBlank()) {
        val parent = File(parsed.path!!).parentFile
        if (parent != null && parent.exists()) return parent.absolutePath
    }
    return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
}

private fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
    else String.format(Locale.US, "%.1f MB", mb)
}
