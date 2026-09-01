package com.example.ui.components

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
import com.example.data.database.MediaEntity
import com.example.data.database.PreferenceEntity
import com.example.data.database.displayArtist
import com.example.ui.screens.FolderThumbnail
import com.example.ui.screens.MediaGridCard
import com.example.ui.screens.MediaListRow
import com.example.ui.screens.formatMediaFileSize
import com.example.ui.viewmodel.MainViewModel

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

    val groupedByFolder = remember(nonStreamMedia) {
        val map = linkedMapOf<String, MutableList<MediaEntity>>()
        nonStreamMedia.forEach { item ->
            val parentName = try {
                val f = java.io.File(item.path)
                f.parentFile?.name ?: "Root Folder"
            } catch (e: Exception) {
                "Root Folder"
            }
            map.getOrPut(parentName) { mutableListOf() }.add(item)
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
                    (slideInHorizontally(animationSpec = tween(250)) { it } + fadeIn()).togetherWith(
                        slideOutHorizontally(animationSpec = tween(250)) { -it } + fadeOut()
                    )
                } else {
                    (slideInHorizontally(animationSpec = tween(250)) { -it } + fadeIn()).togetherWith(
                        slideOutHorizontally(animationSpec = tween(250)) { it } + fadeOut()
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
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(64.dp)
                            )
                            Text(
                                text = "No media folders found",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    if (prefs.useGroupWiseFolderStyle || prefs.listStyle == "Grid") {
                        // Grid Layout for Folders
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("folder_grid_view")
                        ) {
                            items(foldersList, key = { it }) { folderName ->
                                val folderVideos = groupedByFolder[folderName] ?: emptyList()
                                val isFolderSelected = folderVideos.isNotEmpty() && folderVideos.all { file ->
                                    selectedMediaSet.any { it.uriString == file.uriString }
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.85f)
                                        .combinedClickable(
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
                                            }
                                        )
                                        .testTag("folder_card_$folderName"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isFolderSelected) accentColor.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    ),
                                    border = BorderStroke(
                                        width = if (isFolderSelected) 2.dp else 1.dp,
                                        color = if (isFolderSelected) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.SpaceBetween,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            FolderThumbnail(
                                                folderFiles = folderVideos,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(1.25f)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = folderName,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                            val totalSize = folderVideos.sumOf { it.size }
                                            val sizeStr = formatMediaFileSize(totalSize)
                                            val subtext = if (sizeStr.isNotEmpty()) "${folderVideos.size} files • $sizeStr" else "${folderVideos.size} files"
                                            Text(
                                                text = subtext,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        if (isFolderSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(2.dp)
                                                    .size(22.dp)
                                                    .clip(CircleShape)
                                                    .background(accentColor),
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
                        }
                    } else {
                        // List Layout for Folders
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
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

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
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
                                            }
                                        )
                                        .testTag("folder_row_$folderName"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isFolderSelected) accentColor.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                    ),
                                    border = BorderStroke(
                                        width = if (isFolderSelected) 2.dp else 1.dp,
                                        color = if (isFolderSelected) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        FolderThumbnail(
                                            folderFiles = folderVideos,
                                            modifier = Modifier.size(52.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = folderName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val totalSize = folderVideos.sumOf { it.size }
                                            val sizeStr = formatMediaFileSize(totalSize)
                                            val subtext = if (sizeStr.isNotEmpty()) "${folderVideos.size} items • $sizeStr" else "${folderVideos.size} items"
                                            Text(
                                                text = subtext,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                        if (isFolderSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(accentColor),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
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
                        else -> if (prefs.sortAscending) rawFolderFiles.sortedBy { it.title.lowercase() } else rawFolderFiles.sortedByDescending { it.title.lowercase() }
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Header / Breadcrumbs Bar
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onFolderSelect(null) },
                                modifier = Modifier.size(36.dp).testTag("folder_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to Folders",
                                    tint = accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentFolder,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${files.size} items",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    // Action Controls Row (Play All, Queue All, View Mode Toggle, Select All)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { viewModel.playAll(files) },
                                enabled = files.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(34.dp).testTag("folder_play_all_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { viewModel.addToQueue(files) },
                                enabled = files.isNotEmpty(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor),
                                border = BorderStroke(1.dp, accentColor),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(34.dp).testTag("folder_queue_all_button")
                            ) {
                                Icon(Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Queue All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Scoped Select All for current folder
                            if (isSelectModeActive) {
                                val allFolderFilesSelected = files.isNotEmpty() && files.all { f -> selectedMediaSet.any { it.uriString == f.uriString } }
                                IconButton(
                                    onClick = {
                                        if (allFolderFilesSelected) {
                                            selectedMediaSet.removeAll(files.toSet())
                                            if (selectedMediaSet.isEmpty()) onToggleSelectMode(false)
                                        } else {
                                            selectedMediaSet.addAll(files)
                                        }
                                    },
                                    modifier = Modifier.size(36.dp).testTag("folder_select_all_button")
                                ) {
                                    Icon(
                                        imageVector = if (allFolderFilesSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                        contentDescription = "Select All in Folder",
                                        tint = accentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // View Mode Toggle
                            IconButton(
                                onClick = {
                                    val newStyle = if (prefs.listStyle == "Grid") "List" else "Grid"
                                    viewModel.updateListStyle(newStyle)
                                },
                                modifier = Modifier.size(36.dp).testTag("folder_toggle_view_button")
                            ) {
                                Icon(
                                    imageVector = if (prefs.listStyle == "Grid") Icons.Default.List else Icons.Default.GridView,
                                    contentDescription = "Toggle Grid/List View",
                                    tint = accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Files Content (Grid or List)
                    if (prefs.listStyle == "Grid") {
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
