package com.emptysol.aeroplayer.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emptysol.aeroplayer.data.database.MediaEntity
import com.emptysol.aeroplayer.data.database.PreferenceEntity
import com.emptysol.aeroplayer.data.database.displayArtist
import com.emptysol.aeroplayer.ui.screens.FolderThumbnail
import com.emptysol.aeroplayer.ui.screens.MediaGridCard
import com.emptysol.aeroplayer.ui.screens.MediaListRow
import com.emptysol.aeroplayer.ui.screens.formatMediaFileSize
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel

/**
 * Reusable Folder Component used across the entire application (Main page filter, Folder sub-tab, Browse tab).
 * Preserves folder navigation state, hierarchical breadcrumbs, scoped selection, and consistent UI/UX design.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FolderListComponent(
    mediaList: List<MediaEntity>,
    viewModel: MainViewModel,
    prefs: PreferenceEntity,
    activeFolder: String?,
    onFolderSelect: (String?) -> Unit,
    onPlayItem: (MediaEntity) -> Unit,
    onMediaMenuClick: (MediaEntity) -> Unit,
    activeItem: MediaEntity? = null,
    historyProgressMap: Map<String, Float> = emptyMap(),
    selectedMediaSet: MutableList<MediaEntity>,
    isSelectModeActive: Boolean,
    onToggleSelectMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    // Group media by folder name / parent directory
    val nonStreamMedia = remember(mediaList) {
        mediaList.filter { it.genre != "Live Stream" }
    }

    val isArtistMode = prefs.groupByStyle == "artist"
    val isTypeMode = prefs.groupByStyle == "file_type"
    val isGenreMode = prefs.groupByStyle == "genre"
    val groupIcon = when {
        isArtistMode -> Icons.Default.Person
        isTypeMode -> Icons.Default.Category
        isGenreMode -> Icons.Default.MusicNote
        else -> Icons.Default.Folder
    }

    val groupedByFolder = remember(nonStreamMedia, prefs.groupByStyle) {
        val map = linkedMapOf<String, MutableList<MediaEntity>>()
        nonStreamMedia.forEach { item ->
            val key = when (prefs.groupByStyle) {
                "artist" -> {
                    val art = item.displayArtist.trim()
                    if (art.isEmpty() || art.equals("<unknown>", ignoreCase = true) || art.equals("unknown", ignoreCase = true)) {
                        "Unknown Artist"
                    } else art
                }
                "file_type" -> {
                    val ext = item.path.substringAfterLast('.', "").uppercase().trim()
                    if (ext.isEmpty()) "OTHER" else ext
                }
                "genre" -> {
                    val gen = item.genre?.trim() ?: ""
                    if (gen.isEmpty() || gen.equals("<unknown>", ignoreCase = true) || gen.equals("unknown", ignoreCase = true)) {
                        "Unknown Genre"
                    } else gen
                }
                else -> {
                    try {
                        val f = java.io.File(item.path)
                        f.parentFile?.name ?: "Root Folder"
                    } catch (e: Exception) {
                        "Root Folder"
                    }
                }
            }
            map.getOrPut(key) { mutableListOf() }.add(item)
        }
        map
    }

    val foldersList = remember(groupedByFolder, prefs.sortBy, prefs.sortAscending) {
        val keys = groupedByFolder.keys.toList()
        keys.sortedWith { f1, f2 ->
            val files1 = groupedByFolder[f1] ?: emptyList()
            val files2 = groupedByFolder[f2] ?: emptyList()
            val cmp = when (prefs.sortBy) {
                "date" -> {
                    val d1 = files1.maxOfOrNull { it.dateAdded } ?: 0L
                    val d2 = files2.maxOfOrNull { it.dateAdded } ?: 0L
                    d1.compareTo(d2)
                }
                "size" -> {
                    val s1 = files1.sumOf { it.size }
                    val s2 = files2.sumOf { it.size }
                    s1.compareTo(s2)
                }
                "length", "duration" -> {
                    val dur1 = files1.sumOf { it.duration }
                    val dur2 = files2.sumOf { it.duration }
                    dur1.compareTo(dur2)
                }
                "artist" -> {
                    val a1 = files1.firstOrNull()?.displayArtist?.lowercase() ?: ""
                    val a2 = files2.firstOrNull()?.displayArtist?.lowercase() ?: ""
                    a1.compareTo(a2)
                }
                else -> f1.lowercase().compareTo(f2.lowercase())
            }
            if (prefs.sortAscending) cmp else -cmp
        }
    }

    // Intercept system back button when inside an opened folder
    BackHandler(enabled = activeFolder != null) {
        onFolderSelect(null)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = activeFolder,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally(animationSpec = tween(140)) { it } + fadeIn(animationSpec = tween(120))).togetherWith(
                        slideOutHorizontally(animationSpec = tween(140)) { -it } + fadeOut(animationSpec = tween(120))
                    )
                } else {
                    (slideInHorizontally(animationSpec = tween(140)) { -it } + fadeIn(animationSpec = tween(120))).togetherWith(
                        slideOutHorizontally(animationSpec = tween(140)) { it } + fadeOut(animationSpec = tween(120))
                    )
                }
            },
            label = "folder_view_transition"
        ) { currentFolder ->
            if (currentFolder == null) {
                // -------------------------------------------------------------
                // LEVEL 1: ROOT FOLDERS OVERVIEW
                // -------------------------------------------------------------
                if (foldersList.isEmpty()) {
                    val emptyLabel = when {
                        isArtistMode -> "No artists found"
                        isTypeMode -> "No media types found"
                        isGenreMode -> "No genres found"
                        else -> "No media folders found"
                    }
                    AeroExplorerEmptyState(
                        icon = groupIcon,
                        title = emptyLabel,
                        subtitle = "Pull to refresh or scan storage to index media"
                    )
                } else {
                    if (prefs.useGroupWiseFolderStyle) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 112.dp),
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("folder_grid_view")
                        ) {
                            items(foldersList, key = { it }) { folderName ->
                                val folderVideos = groupedByFolder[folderName] ?: emptyList()
                                val isFolderSelected = folderVideos.isNotEmpty() && folderVideos.all { file ->
                                    selectedMediaSet.any { it.uriString == file.uriString }
                                }
                                val totalSize = folderVideos.sumOf { it.size }
                                val sizeStr = formatMediaFileSize(totalSize)
                                val subtext = if (sizeStr.isNotEmpty()) "${folderVideos.size} files • $sizeStr" else "${folderVideos.size} files"

                                AeroExplorerFolderGridCard(
                                    title = folderName,
                                    subtitle = subtext,
                                    icon = groupIcon,
                                    badgeText = folderVideos.size.toString(),
                                    iconTint = accentColor,
                                    isSelected = isFolderSelected,
                                    onClick = {
                                        if (isSelectModeActive) {
                                            if (isFolderSelected) {
                                                selectedMediaSet.removeAll(folderVideos.toSet())
                                                if (selectedMediaSet.isEmpty()) onToggleSelectMode(false)
                                            } else {
                                                selectedMediaSet.addAll(folderVideos)
                                            }
                                        } else {
                                            onFolderSelect(folderName)
                                        }
                                    },
                                    onLongClick = {
                                        onToggleSelectMode(true)
                                        if (isFolderSelected) {
                                            selectedMediaSet.removeAll(folderVideos.toSet())
                                        } else {
                                            selectedMediaSet.addAll(folderVideos)
                                        }
                                    },
                                    modifier = Modifier.testTag("folder_card_$folderName")
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("folder_list_view")
                        ) {
                            items(foldersList, key = { it }) { folderName ->
                                val folderVideos = groupedByFolder[folderName] ?: emptyList()
                                val isFolderSelected = folderVideos.isNotEmpty() && folderVideos.all { file ->
                                    selectedMediaSet.any { it.uriString == file.uriString }
                                }
                                val totalSize = folderVideos.sumOf { it.size }
                                val sizeStr = formatMediaFileSize(totalSize)
                                val subtext = if (sizeStr.isNotEmpty()) "${folderVideos.size} items • $sizeStr" else "${folderVideos.size} items"

                                AeroExplorerFolderListRow(
                                    title = folderName,
                                    subtitle = subtext,
                                    icon = groupIcon,
                                    iconTint = accentColor,
                                    isSelected = isFolderSelected,
                                    onClick = {
                                        if (isSelectModeActive) {
                                            if (isFolderSelected) {
                                                selectedMediaSet.removeAll(folderVideos.toSet())
                                                if (selectedMediaSet.isEmpty()) onToggleSelectMode(false)
                                            } else {
                                                selectedMediaSet.addAll(folderVideos)
                                            }
                                        } else {
                                            onFolderSelect(folderName)
                                        }
                                    },
                                    onLongClick = {
                                        onToggleSelectMode(true)
                                        if (isFolderSelected) {
                                            selectedMediaSet.removeAll(folderVideos.toSet())
                                        } else {
                                            selectedMediaSet.addAll(folderVideos)
                                        }
                                    },
                                    modifier = Modifier.testTag("folder_row_$folderName")
                                )
                            }
                        }
                    }
                }
            } else {
                // -------------------------------------------------------------
                // LEVEL 2: OPENED FOLDER DETAIL FILE VIEW
                // -------------------------------------------------------------
                val rawFolderFiles = groupedByFolder[currentFolder] ?: emptyList()
                val files = remember(rawFolderFiles, prefs.sortBy, prefs.sortAscending) {
                    when (prefs.sortBy) {
                        "date" -> if (prefs.sortAscending) rawFolderFiles.sortedBy { it.dateAdded } else rawFolderFiles.sortedByDescending { it.dateAdded }
                        "size" -> if (prefs.sortAscending) rawFolderFiles.sortedBy { it.size } else rawFolderFiles.sortedByDescending { it.size }
                        "length", "duration" -> if (prefs.sortAscending) rawFolderFiles.sortedBy { it.duration } else rawFolderFiles.sortedByDescending { it.duration }
                        "artist" -> if (prefs.sortAscending) rawFolderFiles.sortedBy { (it.artist ?: "").lowercase() } else rawFolderFiles.sortedByDescending { (it.artist ?: "").lowercase() }
                        else -> if (prefs.sortAscending) rawFolderFiles.sortedWith { a, b -> com.emptysol.aeroplayer.ui.viewmodel.compareNaturalStrings(a.title, b.title) } else rawFolderFiles.sortedWith { a, b -> com.emptysol.aeroplayer.ui.viewmodel.compareNaturalStrings(b.title, a.title) }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (files.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No media files found",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 14.sp
                            )
                        }
                    } else if (prefs.listStyle == "Grid") {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 140.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("folder_files_grid")
                        ) {
                            items(files, key = { it.uriString }) { item ->
                                val isSelected = selectedMediaSet.any { it.uriString == item.uriString }
                                MediaGridCard(
                                    item = item,
                                    isSelected = isSelected,
                                    isSelectModeActive = isSelectModeActive,
                                    onMenuClick = { onMediaMenuClick(item) },
                                    isActive = (activeItem?.uriString == item.uriString),
                                    progress = historyProgressMap[item.uriString],
                                    onClick = {
                                        if (isSelectModeActive) {
                                            if (isSelected) {
                                                selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                if (selectedMediaSet.isEmpty()) onToggleSelectMode(false)
                                            } else {
                                                selectedMediaSet.add(item)
                                            }
                                        } else {
                                            viewModel.setPlayingItemWithQueue(item, files)
                                            onPlayItem(item)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectModeActive) {
                                            onToggleSelectMode(true)
                                            selectedMediaSet.clear()
                                            selectedMediaSet.add(item)
                                        } else {
                                            if (isSelected) {
                                                selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                if (selectedMediaSet.isEmpty()) onToggleSelectMode(false)
                                            } else {
                                                selectedMediaSet.add(item)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("folder_files_list")
                        ) {
                            items(files, key = { it.uriString }) { item ->
                                val isSelected = selectedMediaSet.any { it.uriString == item.uriString }
                                MediaListRow(
                                    item = item,
                                    isSelected = isSelected,
                                    isSelectModeActive = isSelectModeActive,
                                    onMenuClick = { onMediaMenuClick(item) },
                                    isActive = (activeItem?.uriString == item.uriString),
                                    progress = historyProgressMap[item.uriString],
                                    onClick = {
                                        if (isSelectModeActive) {
                                            if (isSelected) {
                                                selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                if (selectedMediaSet.isEmpty()) onToggleSelectMode(false)
                                            } else {
                                                selectedMediaSet.add(item)
                                            }
                                        } else {
                                            viewModel.setPlayingItemWithQueue(item, files)
                                            onPlayItem(item)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectModeActive) {
                                            onToggleSelectMode(true)
                                            selectedMediaSet.clear()
                                            selectedMediaSet.add(item)
                                        } else {
                                            if (isSelected) {
                                                selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                if (selectedMediaSet.isEmpty()) onToggleSelectMode(false)
                                            } else {
                                                selectedMediaSet.add(item)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
