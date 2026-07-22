package com.example.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.MediaEntity
import com.example.ui.viewmodel.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FolderTabContent(
    viewModel: MainViewModel,
    onPlayItem: (MediaEntity) -> Unit,
    onFolderLongClick: (String, List<MediaEntity>) -> Unit
) {
    val mediaList by viewModel.filteredMediaList.collectAsState()
    val prefs by viewModel.preferencesState.collectAsState()
    val selectionState by viewModel.selectionState.collectAsState()
    val historyList by viewModel.historyState.collectAsState()
    val historyProgressMap = remember(historyList) {
        historyList.associate { it.uriString to (it.progressMs.toFloat() / it.duration.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f) }
    }
    var currentPathSegments by remember { mutableStateOf<List<String>>(emptyList()) }
    val accentOrange = MaterialTheme.colorScheme.primary
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    BackHandler(enabled = currentPathSegments.isNotEmpty()) {
        currentPathSegments = currentPathSegments.dropLast(1)
    }

    val directoryContents = remember(mediaList, currentPathSegments) {
        val nonStream = mediaList.filter { it.genre != "Live Stream" }
        
        val subDirs = mutableSetOf<String>()
        val directFiles = mutableListOf<MediaEntity>()
        
        nonStream.forEach { item ->
            val path = item.path
            val cleanPath = when {
                path.startsWith("/storage/emulated/0/") -> path.substringAfter("/storage/emulated/0/")
                path.startsWith("storage/emulated/0/") -> path.substringAfter("storage/emulated/0/")
                else -> path.trimStart('/')
            }
            
            val segments = cleanPath.split('/').filter { it.isNotEmpty() }
            
            val match = currentPathSegments.size <= segments.size - 1 &&
                currentPathSegments.indices.all { i -> segments[i] == currentPathSegments[i] }
                
            if (match) {
                if (segments.size - 1 == currentPathSegments.size) {
                    directFiles.add(item)
                } else if (segments.size - 1 > currentPathSegments.size) {
                    subDirs.add(segments[currentPathSegments.size])
                }
            }
        }
        
        Pair(subDirs.sorted(), directFiles.sortedBy { it.title })
    }
    
    val (subDirectories, files) = directoryContents

    val allFilesInFolderAndSubfolders = remember(mediaList, currentPathSegments) {
        val nonStream = mediaList.filter { it.genre != "Live Stream" }
        nonStream.filter { item ->
            val path = item.path
            val cleanPath = when {
                path.startsWith("/storage/emulated/0/") -> path.substringAfter("/storage/emulated/0/")
                path.startsWith("storage/emulated/0/") -> path.substringAfter("storage/emulated/0/")
                else -> path.trimStart('/')
            }
            val segments = cleanPath.split('/').filter { it.isNotEmpty() }
            currentPathSegments.size <= segments.size - 1 &&
                currentPathSegments.indices.all { i -> segments[i] == currentPathSegments[i] }
        }.sortedBy { it.title }
    }

    val getDirectoryFiles = remember(mediaList, currentPathSegments) {
        { dirName: String ->
            val nonStream = mediaList.filter { it.genre != "Live Stream" }
            val dirSegments = currentPathSegments + dirName
            nonStream.filter { item ->
                val path = item.path
                val cleanPath = when {
                    path.startsWith("/storage/emulated/0/") -> path.substringAfter("/storage/emulated/0/")
                    path.startsWith("storage/emulated/0/") -> path.substringAfter("storage/emulated/0/")
                    else -> path.trimStart('/')
                }
                val segments = cleanPath.split('/').filter { it.isNotEmpty() }
                dirSegments.size <= segments.size - 1 &&
                    dirSegments.indices.all { i -> segments[i] == dirSegments[i] }
            }
        }
    }

    val formatDuration = { durationMs: Long ->
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)
        if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentPathSegments.isNotEmpty()) {
                IconButton(
                    onClick = { currentPathSegments = currentPathSegments.dropLast(1) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = accentOrange,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = accentOrange,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            
            Text(
                text = if (currentPathSegments.isEmpty()) "Root" else currentPathSegments.last(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = accentOrange
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.playAll(allFilesInFolderAndSubfolders) },
                    enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = accentOrange),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play All", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }

                OutlinedButton(
                    onClick = { viewModel.addToQueue(allFilesInFolderAndSubfolders) },
                    enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accentOrange),
                    border = BorderStroke(1.dp, accentOrange),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Queue All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            IconButton(
                onClick = { viewModel.updateUseGroupWiseFolderStyle(!prefs.useGroupWiseFolderStyle) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (prefs.useGroupWiseFolderStyle) Icons.Default.List else Icons.Default.GridView,
                    contentDescription = "Toggle Grid/List View",
                    tint = accentOrange,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (subDirectories.isEmpty() && files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No files or folders at this level",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }
        } else if (currentPathSegments.isEmpty()) {
            // Root Level: Respect folder grid/list preference
            if (prefs.useGroupWiseFolderStyle) {
                // Show as Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(subDirectories, key = { "dir_$it" }) { dir ->
                        val folderVideos = getDirectoryFiles(dir)
                        val isSelected = selectionState.selectedFolderPaths.contains(dir)
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.82f)
                                .combinedClickable(
                                    onClick = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        if (selectionState.isInSelectionMode) {
                                            viewModel.toggleFolderSelection(dir)
                                        } else {
                                            currentPathSegments = currentPathSegments + dir
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        viewModel.toggleFolderSelection(dir)
                                    }
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) accentOrange.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) accentOrange else Color.Transparent
                            )
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    FolderThumbnail(
                                        folderFiles = folderVideos,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1.2f)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = dir,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "${folderVideos.size} files",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                
                                if (selectionState.isInSelectionMode) {
                                    Box(
                                        modifier = Modifier
                                            .padding(4.dp)
                                            .size(18.dp)
                                            .align(Alignment.TopEnd)
                                            .background(
                                                color = if (isSelected) accentOrange else Color.DarkGray.copy(alpha = 0.6f),
                                                shape = RoundedCornerShape(4.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.Black,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Show as List
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(subDirectories, key = { "dir_root_$it" }) { dir ->
                        val folderVideos = getDirectoryFiles(dir)
                        val isSelected = selectionState.selectedFolderPaths.contains(dir)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        if (selectionState.isInSelectionMode) {
                                            viewModel.toggleFolderSelection(dir)
                                        } else {
                                            currentPathSegments = currentPathSegments + dir
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        viewModel.toggleFolderSelection(dir)
                                    }
                                ),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) accentOrange.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) accentOrange else Color.Transparent
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FolderThumbnail(
                                    folderFiles = folderVideos,
                                    modifier = Modifier.size(52.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dir,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${folderVideos.size} media files",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Open Folder level: Unified dynamic layout for folders and files
            val isFolderGrid = prefs.useGroupWiseFolderStyle
            val isFileGrid = prefs.listStyle == "Grid"

            if (!isFolderGrid && !isFileGrid) {
                // BOTH are List layout -> Use a clean, simple LazyColumn
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Subdirectories (folders) inside this folder as list rows
                    items(subDirectories, key = { "sub_dir_list_$it" }) { dir ->
                        val folderVideos = getDirectoryFiles(dir)
                        val isSelected = selectionState.selectedFolderPaths.contains(dir)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        if (selectionState.isInSelectionMode) {
                                            viewModel.toggleFolderSelection(dir)
                                        } else {
                                            currentPathSegments = currentPathSegments + dir
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        viewModel.toggleFolderSelection(dir)
                                    }
                                ),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) accentOrange.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) accentOrange else Color.Transparent
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FolderThumbnail(
                                    folderFiles = folderVideos,
                                    modifier = Modifier.size(52.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dir,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${folderVideos.size} media files",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Files inside this folder as list rows
                    items(files, key = { "file_list_${it.uriString}" }) { file ->
                        val isSelected = selectionState.selectedVideoIds.contains(file.uriString)
                        val badgeLabel = when {
                            file.isVideo -> {
                                if (file.title.contains("1080p")) "1080p • H.264"
                                else if (file.title.contains("720p")) "720p • H.264"
                                else "1080p • MP4"
                            }
                            else -> {
                                if (file.title.contains("FLAC")) "FLAC • Lossless"
                                else "MP3 • 320kbps"
                            }
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        if (selectionState.isInSelectionMode) {
                                            viewModel.toggleVideoSelection(file.uriString)
                                        } else {
                                            viewModel.setPlayingItemWithQueue(file, files)
                                            onPlayItem(file)
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        viewModel.toggleVideoSelection(file.uriString)
                                    }
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) accentOrange.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) accentOrange else Color.Transparent
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(75.dp)
                                        .height(50.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                ) {
                                    VideoThumbnailItem(file)
                                    val progress = historyProgressMap[file.uriString]
                                    if (progress != null && progress > 0f && progress <= 1f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(3.dp)
                                                .align(Alignment.BottomStart)
                                                .background(Color.White.copy(alpha = 0.3f))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(progress)
                                                    .fillMaxHeight()
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatDuration(file.duration),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = badgeLabel,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                                
                                if (selectionState.isInSelectionMode) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(
                                                color = if (isSelected) accentOrange else Color.DarkGray,
                                                shape = RoundedCornerShape(4.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.Black,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                } else {
                                    IconButton(
                                        onClick = { onFolderLongClick(currentPathSegments.lastOrNull() ?: "Folder", listOf(file)) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Options",
                                            tint = Color.LightGray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // At least one is Grid layout -> Use a beautiful LazyVerticalGrid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Subdirectories (folders)
                    if (subDirectories.isNotEmpty()) {
                        if (isFolderGrid) {
                            items(subDirectories, key = { "sub_dir_grid_$it" }) { dir ->
                                val folderVideos = getDirectoryFiles(dir)
                                val isSelected = selectionState.selectedFolderPaths.contains(dir)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.82f)
                                        .combinedClickable(
                                            onClick = {
                                                if (selectionState.isInSelectionMode) {
                                                    viewModel.toggleFolderSelection(dir)
                                                } else {
                                                    currentPathSegments = currentPathSegments + dir
                                                }
                                            },
                                            onLongClick = {
                                                viewModel.toggleFolderSelection(dir)
                                            }
                                        ),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) accentOrange.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) accentOrange else Color.Transparent
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.SpaceBetween,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        FolderThumbnail(
                                            folderFiles = folderVideos,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1.2f)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = dir,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                        Text(
                                            text = "${folderVideos.size} files",
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            // Folder is List, so span full width!
                            items(subDirectories, key = { "sub_dir_grid_span_$it" }, span = { GridItemSpan(maxLineSpan) }) { dir ->
                                val folderVideos = getDirectoryFiles(dir)
                                val isSelected = selectionState.selectedFolderPaths.contains(dir)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (selectionState.isInSelectionMode) {
                                                    viewModel.toggleFolderSelection(dir)
                                                } else {
                                                    currentPathSegments = currentPathSegments + dir
                                                }
                                            },
                                            onLongClick = {
                                                viewModel.toggleFolderSelection(dir)
                                            }
                                        ),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) accentOrange.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) accentOrange else Color.Transparent
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        FolderThumbnail(
                                            folderFiles = folderVideos,
                                            modifier = Modifier.size(52.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = dir,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "${folderVideos.size} media files",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Files inside this folder
                    if (files.isNotEmpty()) {
                        if (isFileGrid) {
                            items(files, key = { "file_grid_${it.uriString}" }) { file ->
                                val isSelected = selectionState.selectedVideoIds.contains(file.uriString)
                                val badgeLabel = if (file.isVideo) "Video" else "Audio"
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.85f)
                                        .combinedClickable(
                                            onClick = {
                                                if (selectionState.isInSelectionMode) {
                                                    viewModel.toggleVideoSelection(file.uriString)
                                                } else {
                                                    viewModel.setPlayingItemWithQueue(file, files)
                                                    onPlayItem(file)
                                                }
                                            },
                                            onLongClick = {
                                                viewModel.toggleVideoSelection(file.uriString)
                                            }
                                        ),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) accentOrange.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) accentOrange else Color.Transparent
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(6.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                        ) {
                                            VideoThumbnailItem(file)
                                            val progress = historyProgressMap[file.uriString]
                                            if (progress != null && progress > 0f && progress <= 1f) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(3.dp)
                                                        .align(Alignment.BottomStart)
                                                        .background(Color.White.copy(alpha = 0.3f))
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth(progress)
                                                            .fillMaxHeight()
                                                            .background(MaterialTheme.colorScheme.primary)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = file.title,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = formatDuration(file.duration),
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text(badgeLabel, fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // File is List, so span full width!
                            items(files, key = { "file_grid_span_${it.uriString}" }, span = { GridItemSpan(maxLineSpan) }) { file ->
                                val isSelected = selectionState.selectedVideoIds.contains(file.uriString)
                                val badgeLabel = when {
                                    file.isVideo -> {
                                        if (file.title.contains("1080p")) "1080p • H.264"
                                        else if (file.title.contains("720p")) "720p • H.264"
                                        else "1080p • MP4"
                                    }
                                    else -> {
                                        if (file.title.contains("FLAC")) "FLAC • Lossless"
                                        else "MP3 • 320kbps"
                                    }
                                }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (selectionState.isInSelectionMode) {
                                                    viewModel.toggleVideoSelection(file.uriString)
                                                } else {
                                                    viewModel.setPlayingItemWithQueue(file, files)
                                                    onPlayItem(file)
                                                }
                                            },
                                            onLongClick = {
                                                viewModel.toggleVideoSelection(file.uriString)
                                            }
                                        ),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) accentOrange.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) accentOrange else Color.Transparent
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(75.dp)
                                                .height(50.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                        ) {
                                            VideoThumbnailItem(file)
                                             val progress = historyProgressMap[file.uriString]
                                             if (progress != null && progress > 0f && progress <= 1f) {
                                                 Box(
                                                     modifier = Modifier
                                                         .fillMaxWidth()
                                                         .height(3.dp)
                                                         .align(Alignment.BottomStart)
                                                         .background(Color.White.copy(alpha = 0.3f))
                                                 ) {
                                                     Box(
                                                         modifier = Modifier
                                                             .fillMaxWidth(progress)
                                                             .fillMaxHeight()
                                                             .background(MaterialTheme.colorScheme.primary)
                                                     )
                                                 }
                                             }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = file.title,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = formatDuration(file.duration),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = badgeLabel,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                        
                                        if (selectionState.isInSelectionMode) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .background(
                                                        color = if (isSelected) accentOrange else Color.DarkGray,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = Color.Black,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        } else {
                                            IconButton(
                                                onClick = { onFolderLongClick(currentPathSegments.lastOrNull() ?: "Folder", listOf(file)) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = "Options",
                                                    tint = Color.LightGray
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        } // Close Column
        
        // Floating Action Button
        if (allFilesInFolderAndSubfolders.isNotEmpty()) {
            FloatingActionButton(
                onClick = { viewModel.playAll(allFilesInFolderAndSubfolders) },
                containerColor = accentOrange,
                contentColor = Color.Black,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 24.dp, end = 24.dp)
                    .testTag("folder_play_all_fab"),
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play All Files",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun FolderThumbnail(
    folderFiles: List<MediaEntity>,
    modifier: Modifier = Modifier
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.15f),
                        containerColor
                    )
                )
            )
            .border(
                1.dp,
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.25f),
                        accentColor.copy(alpha = 0.05f)
                    )
                ),
                RoundedCornerShape(14.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        // Glowing halo effect behind the icon
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(accentColor.copy(alpha = 0.18f), CircleShape)
                .align(Alignment.Center)
        )
        
        Icon(
            imageVector = Icons.Default.FolderCopy,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(28.dp)
        )
        
        // Count Badge removed
    }
}

@Composable
fun VideoThumbnailItem(media: MediaEntity) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(media.path) {
        withContext(Dispatchers.IO) {
            try {
                if (media.isVideo) {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(media.path)
                    bitmap = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                }
            } catch (e: Exception) {
                // Ignore failure
            }
        }
    }
    
    val accentColor = if (media.isVideo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.2f),
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (media.isVideo) Icons.Default.PlayCircleOutline else Icons.Default.MusicNote,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
