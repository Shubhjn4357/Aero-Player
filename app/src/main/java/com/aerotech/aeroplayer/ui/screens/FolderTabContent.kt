package com.aerotech.aeroplayer.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.aerotech.aeroplayer.data.database.MediaEntity
import com.aerotech.aeroplayer.ui.viewmodel.*
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
    var folderMenuExpanded by remember { mutableStateOf(false) }
    val accentOrange = MaterialTheme.colorScheme.primary
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    BackHandler(enabled = currentPathSegments.isNotEmpty()) {
        currentPathSegments = currentPathSegments.dropLast(1)
    }

    val parsedMediaItems = remember(mediaList) {
        mediaList.mapNotNull { item ->
            if (item.genre == "Live Stream") return@mapNotNull null
            val path = item.path
            val cleanPath = when {
                path.startsWith("/storage/emulated/0/") -> path.substringAfter("/storage/emulated/0/")
                path.startsWith("storage/emulated/0/") -> path.substringAfter("storage/emulated/0/")
                else -> path.trimStart('/')
            }
            val segments = cleanPath.split('/').filter { it.isNotEmpty() }
            item to segments
        }
    }

    val directoryContentsData = remember(parsedMediaItems, currentPathSegments, prefs.sortBy, prefs.sortAscending) {
        val currentDepth = currentPathSegments.size
        val subDirs = mutableSetOf<String>()
        val directFiles = mutableListOf<MediaEntity>()
        val subDirFiles = mutableMapOf<String, MutableList<MediaEntity>>()
        val folderMetrics = mutableMapOf<String, Triple<Long, Long, Long>>()
        val allFolderFiles = mutableListOf<MediaEntity>()

        parsedMediaItems.forEach { (item, segments) ->
            val match = currentDepth <= segments.size - 1 &&
                currentPathSegments.indices.all { i -> segments[i] == currentPathSegments[i] }
            if (match) {
                allFolderFiles.add(item)
                if (segments.size - 1 == currentDepth) {
                    directFiles.add(item)
                } else if (segments.size - 1 > currentDepth) {
                    val subDirName = segments[currentDepth]
                    subDirs.add(subDirName)
                    subDirFiles.getOrPut(subDirName) { mutableListOf() }.add(item)

                    val existing = folderMetrics[subDirName] ?: Triple(0L, 0L, 0L)
                    val newMaxDate = if (item.dateAdded > existing.first) item.dateAdded else existing.first
                    val newSumSize = existing.second + item.size
                    val newSumDuration = existing.third + item.duration
                    folderMetrics[subDirName] = Triple(newMaxDate, newSumSize, newSumDuration)
                }
            }
        }

        val sortedFiles = when (prefs.sortBy) {
            "date" -> if (prefs.sortAscending) directFiles.sortedBy { it.dateAdded } else directFiles.sortedByDescending { it.dateAdded }
            "size" -> if (prefs.sortAscending) directFiles.sortedBy { it.size } else directFiles.sortedByDescending { it.size }
            "length", "duration" -> if (prefs.sortAscending) directFiles.sortedBy { it.duration } else directFiles.sortedByDescending { it.duration }
            "artist" -> if (prefs.sortAscending) directFiles.sortedBy { (it.artist ?: "").lowercase() } else directFiles.sortedByDescending { (it.artist ?: "").lowercase() }
            else -> if (prefs.sortAscending) directFiles.sortedBy { it.title.lowercase() } else directFiles.sortedByDescending { it.title.lowercase() }
        }

        val sortedSubDirs = subDirs.sortedWith(Comparator { dir1, dir2 ->
            val m1 = folderMetrics[dir1] ?: Triple(0L, 0L, 0L)
            val m2 = folderMetrics[dir2] ?: Triple(0L, 0L, 0L)
            val res = when (prefs.sortBy) {
                "date" -> m1.first.compareTo(m2.first)
                "size" -> m1.second.compareTo(m2.second)
                "length", "duration" -> m1.third.compareTo(m2.third)
                else -> dir1.lowercase().compareTo(dir2.lowercase())
            }
            if (prefs.sortAscending) res else -res
        })

        val sortedAllFolderFiles = allFolderFiles.sortedBy { it.title }
        val getFilesLambda = { dirName: String -> (subDirFiles[dirName] ?: emptyList<MediaEntity>()) as List<MediaEntity> }

        object {
            val subDirectories = sortedSubDirs
            val files = sortedFiles
            val allFilesInFolderAndSubfolders = sortedAllFolderFiles
            val getDirectoryFiles = getFilesLambda
        }
    }

    val subDirectories = directoryContentsData.subDirectories
    val files = directoryContentsData.files
    val allFilesInFolderAndSubfolders = directoryContentsData.allFilesInFolderAndSubfolders
    val getDirectoryFiles = directoryContentsData.getDirectoryFiles

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
            val isAllFolderItemsSelected = remember(selectionState, subDirectories, files) {
                val selDirs = selectionState.selectedFolderPaths
                val selFiles = selectionState.selectedVideoIds
                (subDirectories.isNotEmpty() || files.isNotEmpty()) &&
                    subDirectories.all { selDirs.contains(it) } &&
                    files.all { selFiles.contains(it.uriString) }
            }

            val isAllFoldersSelected = remember(selectionState, subDirectories) {
                subDirectories.isNotEmpty() && subDirectories.all { selectionState.selectedFolderPaths.contains(it) }
            }

            // Top Folder Header Row with Navigation & Dedicated Folder Options Menu Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (currentPathSegments.isNotEmpty()) {
                        IconButton(
                            onClick = { currentPathSegments = currentPathSegments.dropLast(1) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = accentOrange,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = accentOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = if (currentPathSegments.isEmpty()) "Root Folder" else currentPathSegments.last(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box {
                    IconButton(
                        onClick = { folderMenuExpanded = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Folder Options Menu",
                            tint = accentOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = folderMenuExpanded,
                        onDismissRequest = { folderMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isAllFolderItemsSelected) "Deselect All Items" else "Select All Folders & Files") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isAllFolderItemsSelected) Icons.Default.CheckCircle else Icons.Default.SelectAll,
                                    contentDescription = null,
                                    tint = accentOrange
                                )
                            },
                            onClick = {
                                folderMenuExpanded = false
                                if (isAllFolderItemsSelected) {
                                    viewModel.clearSelection()
                                } else {
                                    viewModel.selectAllFoldersAndFiles(subDirectories, files.map { it.uriString })
                                }
                            }
                        )

                        DropdownMenuItem(
                            text = { Text(if (isAllFoldersSelected) "Deselect All Folders" else "Select All Folders Only") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isAllFoldersSelected) Icons.Default.Folder else Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = accentOrange
                                )
                            },
                            enabled = subDirectories.isNotEmpty(),
                            onClick = {
                                folderMenuExpanded = false
                                if (isAllFoldersSelected) {
                                    subDirectories.forEach { dir ->
                                        if (selectionState.selectedFolderPaths.contains(dir)) {
                                            viewModel.toggleFolderSelection(dir)
                                        }
                                    }
                                } else {
                                    subDirectories.forEach { dir ->
                                        if (!selectionState.selectedFolderPaths.contains(dir)) {
                                            viewModel.toggleFolderSelection(dir)
                                        }
                                    }
                                }
                            }
                        )

                        val currentFolder = currentPathSegments.lastOrNull() ?: "Root Folder"
                        val isFav = prefs.favoriteFoldersJson.contains(currentFolder)

                        DropdownMenuItem(
                            text = { Text(if (isFav) "Remove Folder from Favorites" else "Favorite Current Folder") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null,
                                    tint = accentOrange
                                )
                            },
                            onClick = {
                                folderMenuExpanded = false
                                viewModel.toggleFavoriteFolder(currentFolder)
                                android.widget.Toast.makeText(context, if (isFav) "Removed $currentFolder from favorites" else "Added $currentFolder to favorites", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Play All Videos") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = accentOrange) },
                            enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                            onClick = {
                                folderMenuExpanded = false
                                viewModel.playAll(allFilesInFolderAndSubfolders)
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Shuffle Play Folder") },
                            leadingIcon = { Icon(Icons.Default.Shuffle, contentDescription = null, tint = accentOrange) },
                            enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                            onClick = {
                                folderMenuExpanded = false
                                viewModel.playAll(allFilesInFolderAndSubfolders.shuffled())
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Queue All Videos") },
                            leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = accentOrange) },
                            enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                            onClick = {
                                folderMenuExpanded = false
                                val count = allFilesInFolderAndSubfolders.size
                                viewModel.addToQueue(allFilesInFolderAndSubfolders)
                                android.widget.Toast.makeText(context, "Queued $count files", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = { Text(if (prefs.useGroupWiseFolderStyle) "Switch to List View" else "Switch to Grid View") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (prefs.useGroupWiseFolderStyle) Icons.Default.List else Icons.Default.GridView,
                                    contentDescription = null,
                                    tint = accentOrange
                                )
                            },
                            onClick = {
                                folderMenuExpanded = false
                                viewModel.updateUseGroupWiseFolderStyle(!prefs.useGroupWiseFolderStyle)
                            }
                        )

                        if (currentPathSegments.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Hide / Ban Folder") },
                                leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    folderMenuExpanded = false
                                    viewModel.toggleBannedFolder(currentFolder)
                                    currentPathSegments = currentPathSegments.dropLast(1)
                                    android.widget.Toast.makeText(context, "Folder $currentFolder hidden", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }

            // Folder Action & Selection Bar (All Folder Selector + Quick Options)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // All Folder Selector Button
                OutlinedButton(
                    onClick = {
                        if (isAllFoldersSelected) {
                            subDirectories.forEach { dir ->
                                if (selectionState.selectedFolderPaths.contains(dir)) {
                                    viewModel.toggleFolderSelection(dir)
                                }
                            }
                        } else {
                            subDirectories.forEach { dir ->
                                if (!selectionState.selectedFolderPaths.contains(dir)) {
                                    viewModel.toggleFolderSelection(dir)
                                }
                            }
                        }
                    },
                    enabled = subDirectories.isNotEmpty(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isAllFoldersSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = if (isAllFoldersSelected) Icons.Default.Folder else Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isAllFoldersSelected) "Deselect Folders (${subDirectories.size})" else "Select All Folders (${subDirectories.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Select All Items (Folders + Files) Button
                FilledTonalButton(
                    onClick = {
                        if (isAllFolderItemsSelected) {
                            viewModel.clearSelection()
                        } else {
                            viewModel.selectAllFoldersAndFiles(subDirectories, files.map { it.uriString })
                        }
                    },
                    enabled = subDirectories.isNotEmpty() || files.isNotEmpty(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isAllFolderItemsSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = if (isAllFolderItemsSelected) Icons.Default.CheckCircle else Icons.Default.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isAllFolderItemsSelected) "Deselect All" else "Select All Items", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Play All Button
                Button(
                    onClick = { viewModel.playAll(allFilesInFolderAndSubfolders) },
                    enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play All (${allFilesInFolderAndSubfolders.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Queue All Button
                FilledTonalButton(
                    onClick = {
                        val count = allFilesInFolderAndSubfolders.size
                        viewModel.addToQueue(allFilesInFolderAndSubfolders)
                        android.widget.Toast.makeText(context, "Queued $count files", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Queue All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Grid / List Toggle
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
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            border = null
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
                                                color = if (isSelected) accentOrange else MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(4.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
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
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            border = null
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
                                    val totalFolderSize = folderVideos.sumOf { it.size }
                                    val folderSizeStr = formatMediaFileSize(totalFolderSize)
                                    val folderSubtext = if (folderSizeStr.isNotEmpty()) "${folderVideos.size} files • $folderSizeStr" else "${folderVideos.size} files"
                                    Text(
                                        text = folderSubtext,
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
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            border = null
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
                                    val totalFolderSize = folderVideos.sumOf { it.size }
                                    val folderSizeStr = formatMediaFileSize(totalFolderSize)
                                    val folderSubtext = if (folderSizeStr.isNotEmpty()) "${folderVideos.size} files • $folderSizeStr" else "${folderVideos.size} files"
                                    Text(
                                        text = folderSubtext,
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
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            border = null
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
                                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
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
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = formatMediaLengthAndSize(file.duration, file.size),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                
                                if (selectionState.isInSelectionMode) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(
                                                color = if (isSelected) accentOrange else MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(4.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
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
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    border = null
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
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(6.dp)
                                                    .size(22.dp)
                                                    .clip(CircleShape)
                                                    .background(accentOrange),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
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
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    border = null
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
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .clip(CircleShape)
                                                    .background(accentOrange),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        } else {
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
                    }

                    // Files inside this folder
                    if (files.isNotEmpty()) {
                        if (isFileGrid) {
                            items(files, key = { "file_grid_${it.uriString}" }) { file ->
                                val isSelected = selectionState.selectedVideoIds.contains(file.uriString)
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
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    border = null
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
                                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
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
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = formatMediaLengthAndSize(file.duration, file.size),
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        } else {
                            // File is List, so span full width!
                            items(files, key = { "file_grid_span_${it.uriString}" }, span = { GridItemSpan(maxLineSpan) }) { file ->
                                val isSelected = selectionState.selectedVideoIds.contains(file.uriString)
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
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    border = null
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
                                                         .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
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
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = formatMediaLengthAndSize(file.duration, file.size),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                        
                                        if (selectionState.isInSelectionMode) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .background(
                                                        color = if (isSelected) accentOrange else MaterialTheme.colorScheme.surfaceVariant,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onPrimary,
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
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                contentColor = MaterialTheme.colorScheme.onPrimary,
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
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.fillMaxSize(0.68f)
        )
    }
}

@Composable
fun VideoThumbnailItem(media: MediaEntity) {
    MediaThumbnail(item = media, modifier = Modifier.fillMaxSize())
}
