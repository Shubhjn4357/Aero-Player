package com.emptysol.aeroplayer.ui.screens

import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emptysol.aeroplayer.player.subtitle.SubtitleParser
import com.emptysol.aeroplayer.ui.components.AeroExplorerEmptyState
import com.emptysol.aeroplayer.ui.components.AeroExplorerFolderListRow
import com.emptysol.aeroplayer.ui.components.AeroExplorerHeaderCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFileBrowser(
    title: String = "Select File",
    allowedExtensions: List<String>? = null, // null means allow all files
    onFileSelected: (File) -> Unit,
    onDismiss: () -> Unit
) {
    var currentDir by remember {
        mutableStateOf(
            File("/storage/emulated/0").let {
                if (it.exists() && it.canRead()) it else Environment.getExternalStorageDirectory() ?: File("/")
            }
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var directoryFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isScanningDir by remember { mutableStateOf(true) }

    LaunchedEffect(currentDir, allowedExtensions) {
        isScanningDir = true
        val loaded = withContext(Dispatchers.IO) {
            try {
                val list = currentDir.listFiles()?.toList() ?: emptyList()
                list.filter { file ->
                    file.isDirectory || allowedExtensions == null ||
                        allowedExtensions.any { it.equals(file.extension, ignoreCase = true) }
                }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            } catch (_: Exception) {
                emptyList()
            }
        }
        directoryFiles = loaded
        isScanningDir = false
    }

    val filesList = remember(directoryFiles, searchQuery) {
        if (searchQuery.isBlank()) directoryFiles
        else directoryFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    val pathSegments = remember(currentDir) {
        val segments = mutableListOf<File>()
        var curr: File? = currentDir
        while (curr != null) {
            segments.add(0, curr)
            curr = curr.parentFile
            if (curr?.path == "/storage/emulated") break
        }
        segments
    }

    FrostedGlassBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            AeroExplorerHeaderCard(
                title = title,
                subtitle = currentDir.absolutePath,
                icon = Icons.Default.FolderOpen,
                showBack = true,
                onBack = onDismiss,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (currentDir.parentFile != null && currentDir.path != "/storage/emulated/0" && currentDir.path != "/") {
                    IconButton(
                        onClick = {
                            currentDir = currentDir.parentFile ?: currentDir
                            searchQuery = ""
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Go Up One Folder",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search files in this folder...", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Home, contentDescription = "Home", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = pathSegments.joinToString(" / ") { it.name.ifEmpty { "Root" } },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            if (isScanningDir) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filesList.isEmpty()) {
                AeroExplorerEmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = "No accessible files",
                    subtitle = "This folder is empty or has no matching file types",
                    modifier = Modifier.weight(1f)
                )
            } else {
                val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filesList, key = { it.absolutePath }) { file ->
                        val isFolder = file.isDirectory
                        val dateStr = dateFormat.format(Date(file.lastModified()))
                        val sizeStr = if (isFolder) "" else formatFileSize(file.length())
                        val ext = file.extension.lowercase()
                        val subtitle = if (sizeStr.isNotEmpty()) "$dateStr • $sizeStr" else dateStr

                        val itemIcon = when {
                            isFolder -> Icons.Default.Folder
                            ext in listOf("mp4", "mkv", "webm", "avi", "flv", "wmv", "mov", "ts", "m2ts", "vob", "ogv", "3gp", "3g2") -> Icons.Default.Movie
                            ext in listOf("mp3", "wav", "m4a", "ogg", "flac", "aac", "opus", "wma", "ac3", "eac3") -> Icons.Default.MusicNote
                            ext in SubtitleParser.SUPPORTED_SUBTITLE_EXTENSIONS -> Icons.Default.Subtitles
                            else -> Icons.AutoMirrored.Filled.InsertDriveFile
                        }
                        val itemTint = when {
                            isFolder -> MaterialTheme.colorScheme.primary
                            ext in listOf("mp4", "mkv", "webm", "avi", "flv", "wmv", "mov", "ts", "m2ts", "vob", "ogv", "3gp", "3g2") -> MaterialTheme.colorScheme.secondary
                            ext in listOf("mp3", "wav", "m4a", "ogg", "flac", "aac", "opus", "wma", "ac3", "eac3") -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        AeroExplorerFolderListRow(
                            title = file.name,
                            subtitle = subtitle,
                            icon = itemIcon,
                            iconTint = itemTint,
                            showChevron = isFolder,
                            onClick = {
                                if (isFolder) {
                                    currentDir = file
                                    searchQuery = ""
                                } else {
                                    onFileSelected(file)
                                }
                            },
                            trailingContent = if (!isFolder) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.RadioButtonUnchecked,
                                        contentDescription = "Select file",
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else null
                        )
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
