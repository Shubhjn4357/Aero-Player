package com.emptysol.aeroplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emptysol.aeroplayer.data.database.MediaEntity
import com.emptysol.aeroplayer.ui.components.AeroExplorerEmptyState
import com.emptysol.aeroplayer.ui.components.AeroExplorerFolderGridCard
import com.emptysol.aeroplayer.ui.components.AeroExplorerFolderListRow
import com.emptysol.aeroplayer.ui.components.AeroExplorerHeaderCard
import com.emptysol.aeroplayer.ui.viewmodel.*

@OptIn(ExperimentalFoundationApi::class)
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
    val activeItem by viewModel.currentPlayingItem.collectAsState()
    val historyProgressMap = remember(historyList) {
        historyList.associate {
            it.uriString to (it.progressMs.toFloat() / it.duration.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
        }
    }
    val currentPathSegments by viewModel.folderPathSegments.collectAsState()
    var folderMenuExpanded by remember { mutableStateOf(false) }
    val accentOrange = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = currentPathSegments.isNotEmpty()) {
        viewModel.setFolderPathSegments(currentPathSegments.dropLast(1))
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
            else -> if (prefs.sortAscending) {
                directFiles.sortedWith { a, b -> com.emptysol.aeroplayer.ui.viewmodel.compareNaturalStrings(a.title, b.title) }
            } else {
                directFiles.sortedWith { a, b -> com.emptysol.aeroplayer.ui.viewmodel.compareNaturalStrings(b.title, a.title) }
            }
        }

        val sortedSubDirs = subDirs.sortedWith(Comparator { dir1, dir2 ->
            val m1 = folderMetrics[dir1] ?: Triple(0L, 0L, 0L)
            val m2 = folderMetrics[dir2] ?: Triple(0L, 0L, 0L)
            val res = when (prefs.sortBy) {
                "date" -> m1.first.compareTo(m2.first)
                "size" -> m1.second.compareTo(m2.second)
                "length", "duration" -> m1.third.compareTo(m2.third)
                else -> com.emptysol.aeroplayer.ui.viewmodel.compareNaturalStrings(dir1, dir2)
            }
            if (prefs.sortAscending) res else -res
        })

        val sortedAllFolderFiles = allFolderFiles.sortedWith { a, b ->
            com.emptysol.aeroplayer.ui.viewmodel.compareNaturalStrings(a.title, b.title)
        }
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

            val breadcrumbSubtitle = if (currentPathSegments.isEmpty()) {
                "${subDirectories.size} folders • ${allFilesInFolderAndSubfolders.size} media files"
            } else {
                currentPathSegments.joinToString(" / ")
            }

            AeroExplorerHeaderCard(
                title = if (currentPathSegments.isEmpty()) "All Folders" else currentPathSegments.last(),
                subtitle = breadcrumbSubtitle,
                icon = Icons.Default.FolderOpen,
                showBack = currentPathSegments.isNotEmpty(),
                onBack = { viewModel.setFolderPathSegments(currentPathSegments.dropLast(1)) },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                IconButton(
                    onClick = { viewModel.updateUseGroupWiseFolderStyle(!prefs.useGroupWiseFolderStyle) },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (prefs.useGroupWiseFolderStyle) Icons.AutoMirrored.Filled.List else Icons.Default.GridView,
                        contentDescription = "Toggle Grid or List View",
                        tint = accentOrange,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box {
                    IconButton(
                        onClick = { folderMenuExpanded = true },
                        modifier = Modifier.minimumInteractiveComponentSize()
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
                            text = { Text(if (isFav) "Remove from Favorites" else "Add Folder to Favorites") },
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
                                android.widget.Toast.makeText(
                                    context,
                                    if (isFav) "Removed $currentFolder from favorites" else "Added $currentFolder to favorites",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Play All") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = accentOrange) },
                            enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                            onClick = {
                                folderMenuExpanded = false
                                viewModel.playAll(allFilesInFolderAndSubfolders)
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Shuffle All") },
                            leadingIcon = { Icon(Icons.Default.Shuffle, contentDescription = null, tint = accentOrange) },
                            enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                            onClick = {
                                folderMenuExpanded = false
                                viewModel.playAll(allFilesInFolderAndSubfolders.shuffled())
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Add All to Queue") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, tint = accentOrange) },
                            enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                            onClick = {
                                folderMenuExpanded = false
                                val count = allFilesInFolderAndSubfolders.size
                                viewModel.addToQueue(allFilesInFolderAndSubfolders)
                                android.widget.Toast.makeText(context, "Added $count files to queue", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = { Text(if (prefs.useGroupWiseFolderStyle) "List Style" else "Grid Style") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (prefs.useGroupWiseFolderStyle) Icons.AutoMirrored.Filled.List else Icons.Default.GridView,
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
                                text = { Text("Hide Folder") },
                                leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    folderMenuExpanded = false
                                    viewModel.toggleBannedFolder(currentFolder)
                                    viewModel.setFolderPathSegments(currentPathSegments.dropLast(1))
                                    android.widget.Toast.makeText(context, "Hid $currentFolder", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }

            // Quick Actions Strip (consistent tonal chips across explorer)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.playAll(allFilesInFolderAndSubfolders) },
                    enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play All (${allFilesInFolderAndSubfolders.size})", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = {
                        val count = allFilesInFolderAndSubfolders.size
                        viewModel.addToQueue(allFilesInFolderAndSubfolders)
                        android.widget.Toast.makeText(context, "Queued $count files", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    enabled = allFilesInFolderAndSubfolders.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Queue", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }

                FilledTonalButton(
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
                        containerColor = if (isAllFoldersSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        imageVector = if (isAllFoldersSelected) Icons.Default.Folder else Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isAllFoldersSelected) "Deselect Folders" else "Folders (${subDirectories.size})",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

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
                        containerColor = if (isAllFolderItemsSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        imageVector = if (isAllFolderItemsSelected) Icons.Default.CheckCircle else Icons.Default.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isAllFolderItemsSelected) "Clear" else "Select All",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (subDirectories.isEmpty() && files.isEmpty()) {
                AeroExplorerEmptyState(
                    icon = Icons.Default.FolderOpen,
                    title = "Empty directory",
                    subtitle = "No indexed media files or subfolders at this level"
                )
            } else {
                val isFolderGrid = prefs.useGroupWiseFolderStyle
                val isFileGrid = prefs.listStyle == "Grid"

                if (!isFolderGrid && !isFileGrid) {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("dir_explorer_list")
                    ) {
                        items(subDirectories, key = { "sub_dir_list_$it" }) { dir ->
                            val folderVideos = getDirectoryFiles(dir)
                            val isSelected = selectionState.selectedFolderPaths.contains(dir)
                            val sizeStr = formatMediaFileSize(folderVideos.sumOf { it.size })
                            val subtext = if (sizeStr.isNotEmpty()) "${folderVideos.size} files • $sizeStr" else "${folderVideos.size} files"

                            AeroExplorerFolderListRow(
                                title = dir,
                                subtitle = subtext,
                                icon = Icons.Default.Folder,
                                iconTint = accentOrange,
                                isSelected = isSelected,
                                onClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    if (selectionState.isInSelectionMode) {
                                        viewModel.toggleFolderSelection(dir)
                                    } else {
                                        viewModel.setFolderPathSegments(currentPathSegments + dir)
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    viewModel.toggleFolderSelection(dir)
                                },
                                modifier = Modifier.testTag("dir_folder_row_$dir")
                            )
                        }

                        items(files, key = { "file_list_${it.uriString}" }) { file ->
                            val isSelected = selectionState.selectedVideoIds.contains(file.uriString)
                            MediaListRow(
                                item = file,
                                isSelected = isSelected,
                                isSelectModeActive = selectionState.isInSelectionMode,
                                onMenuClick = { onFolderLongClick(currentPathSegments.lastOrNull() ?: "Folder", listOf(file)) },
                                isActive = (activeItem?.uriString == file.uriString),
                                progress = historyProgressMap[file.uriString],
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
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (isFileGrid && subDirectories.isEmpty()) 140.dp else 112.dp),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("dir_explorer_grid")
                    ) {
                        if (subDirectories.isNotEmpty()) {
                            if (isFolderGrid) {
                                items(subDirectories, key = { "sub_dir_grid_$it" }) { dir ->
                                    val folderVideos = getDirectoryFiles(dir)
                                    val isSelected = selectionState.selectedFolderPaths.contains(dir)
                                    val sizeStr = formatMediaFileSize(folderVideos.sumOf { it.size })
                                    val subtext = if (sizeStr.isNotEmpty()) "${folderVideos.size} files • $sizeStr" else "${folderVideos.size} files"

                                    AeroExplorerFolderGridCard(
                                        title = dir,
                                        subtitle = subtext,
                                        icon = Icons.Default.Folder,
                                        badgeText = folderVideos.size.toString(),
                                        iconTint = accentOrange,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (selectionState.isInSelectionMode) {
                                                viewModel.toggleFolderSelection(dir)
                                            } else {
                                                viewModel.setFolderPathSegments(currentPathSegments + dir)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleFolderSelection(dir) },
                                        modifier = Modifier.testTag("dir_folder_card_$dir")
                                    )
                                }
                            } else {
                                items(subDirectories, key = { "sub_dir_grid_span_$it" }, span = { GridItemSpan(maxLineSpan) }) { dir ->
                                    val folderVideos = getDirectoryFiles(dir)
                                    val isSelected = selectionState.selectedFolderPaths.contains(dir)
                                    val sizeStr = formatMediaFileSize(folderVideos.sumOf { it.size })
                                    val subtext = if (sizeStr.isNotEmpty()) "${folderVideos.size} files • $sizeStr" else "${folderVideos.size} files"

                                    AeroExplorerFolderListRow(
                                        title = dir,
                                        subtitle = subtext,
                                        icon = Icons.Default.Folder,
                                        iconTint = accentOrange,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (selectionState.isInSelectionMode) {
                                                viewModel.toggleFolderSelection(dir)
                                            } else {
                                                viewModel.setFolderPathSegments(currentPathSegments + dir)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleFolderSelection(dir) },
                                        modifier = Modifier.testTag("dir_folder_row_$dir")
                                    )
                                }
                            }
                        }

                        if (files.isNotEmpty()) {
                            if (isFileGrid) {
                                items(files, key = { "file_grid_${it.uriString}" }) { file ->
                                    val isSelected = selectionState.selectedVideoIds.contains(file.uriString)
                                    MediaGridCard(
                                        item = file,
                                        isSelected = isSelected,
                                        isSelectModeActive = selectionState.isInSelectionMode,
                                        onMenuClick = { onFolderLongClick(currentPathSegments.lastOrNull() ?: "Folder", listOf(file)) },
                                        isActive = (activeItem?.uriString == file.uriString),
                                        progress = historyProgressMap[file.uriString],
                                        onClick = {
                                            if (selectionState.isInSelectionMode) {
                                                viewModel.toggleVideoSelection(file.uriString)
                                            } else {
                                                viewModel.setPlayingItemWithQueue(file, files)
                                                onPlayItem(file)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleVideoSelection(file.uriString) }
                                    )
                                }
                            } else {
                                items(files, key = { "file_grid_span_${it.uriString}" }, span = { GridItemSpan(maxLineSpan) }) { file ->
                                    val isSelected = selectionState.selectedVideoIds.contains(file.uriString)
                                    MediaListRow(
                                        item = file,
                                        isSelected = isSelected,
                                        isSelectModeActive = selectionState.isInSelectionMode,
                                        onMenuClick = { onFolderLongClick(currentPathSegments.lastOrNull() ?: "Folder", listOf(file)) },
                                        isActive = (activeItem?.uriString == file.uriString),
                                        progress = historyProgressMap[file.uriString],
                                        onClick = {
                                            if (selectionState.isInSelectionMode) {
                                                viewModel.toggleVideoSelection(file.uriString)
                                            } else {
                                                viewModel.setPlayingItemWithQueue(file, files)
                                                onPlayItem(file)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleVideoSelection(file.uriString) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

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
