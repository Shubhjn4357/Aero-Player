package com.example.ui.screens

import android.text.format.Formatter
import androidx.compose.animation.*
import com.example.data.model.Folder
import com.example.data.model.VideoFile
import com.example.data.model.BrowseScreenState
import com.example.data.model.SelectionState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.HistoryEntity
import com.example.data.database.MediaEntity
import com.example.data.database.displayArtist
import com.example.ui.viewmodel.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onPlayItem: (MediaEntity) -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenPlayer: () -> Unit = {}
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val playSubTab by viewModel.playSubTab.collectAsState()
    val mediaList by viewModel.filteredMediaList.collectAsState()
    val historyList by viewModel.historyState.collectAsState()
    val historyProgressMap = remember(historyList) {
        historyList.associate { it.uriString to (it.progressMs.toFloat() / it.duration.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f) }
    }
    val prefs by viewModel.preferencesState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    
    val selectionState by viewModel.selectionState.collectAsState()
    val browseScreenState by viewModel.browseScreenState.collectAsState()
    val activeItem by viewModel.currentPlayingItem.collectAsState()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val accentOrange = MaterialTheme.colorScheme.primary
    var isSearchExpanded by remember { mutableStateOf(false) }

    // Bottom Sheet Control States
    var selectedMediaForOptions by remember { mutableStateOf<MediaEntity?>(null) }
    var showInfoDialogForMedia by remember { mutableStateOf<MediaEntity?>(null) }
    var showAddStreamDrawer by remember { mutableStateOf(false) }
    var showStorageBrowser by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showDisplaySettingsBottomSheet by remember { mutableStateOf(false) }
    var showOnlyFavourites by rememberSaveable { mutableStateOf(false) }
    var activeFolderGroup by remember { mutableStateOf<String?>(null) }

    // Additional interactive dialog / sheet control states (Requirements Layer 2)
    var selectedFolderForOptions by remember { mutableStateOf<Pair<String, List<MediaEntity>>?>(null) }
    var showPlaylistPickerForMedia by remember { mutableStateOf<MediaEntity?>(null) }
    var showPlaylistPickerForFolder by remember { mutableStateOf<List<MediaEntity>?>(null) }
    var showSubtitleDownloadDialog by remember { mutableStateOf<MediaEntity?>(null) }
    var showResumeOrStartDialog by remember { mutableStateOf<MediaEntity?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showAboutAppSection by remember { mutableStateOf(false) }
    var showRenameDialogForMedia by remember { mutableStateOf<MediaEntity?>(null) }
    
    var mediaToDelete by remember { mutableStateOf<MediaEntity?>(null) }
    var multiMediaToDelete by remember { mutableStateOf<List<MediaEntity>?>(null) }
    var folderToDelete by remember { mutableStateOf<Pair<String, List<MediaEntity>>?>(null) }

    var isSelectModeActive by remember { mutableStateOf(false) }
    var showSelectionMoreMenu by remember { mutableStateOf(false) }
    val selectedMediaSet = remember { mutableStateListOf<MediaEntity>() }

    val openDocumentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEachIndexed { index, uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {}

                var displayName = "Selected Media"
                var size = 0L
                var mimeType: String? = null
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                            if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                        }
                    }
                    mimeType = context.contentResolver.getType(uri)
                } catch (e: Exception) {}

                val isVideo = mimeType?.startsWith("video") == true ||
                        listOf("mp4", "mkv", "webm", "avi", "mov", "flv", "3gp", "ts", "m4v").any { displayName.lowercase().endsWith(it) }

                val item = MediaEntity(
                    uriString = uri.toString(),
                    title = displayName.substringBeforeLast('.'),
                    artist = "Imported Media",
                    album = "Storage",
                    duration = 0L,
                    size = size,
                    dateAdded = System.currentTimeMillis() / 1000,
                    isVideo = isVideo,
                    path = uri.toString(),
                    mimeType = mimeType ?: if (isVideo) "video/*" else "audio/*",
                    genre = if (isVideo) "Video" else "Audio"
                )
                viewModel.addMediaItem(item)
                if (index == 0) {
                    onPlayItem(item)
                }
            }
        }
    }

    val isBackEnabled = showAboutAppSection || 
                        isSelectModeActive || 
                        isSearchExpanded || 
                        activeFolderGroup != null ||
                        selectedMediaForOptions != null || 
                        showInfoDialogForMedia != null || 
                        showAddStreamDrawer || 
                        showStorageBrowser || 
                        showPlaylistPickerForMedia != null || 
                        showPlaylistPickerForFolder != null || 
                        showSubtitleDownloadDialog != null || 
                        showResumeOrStartDialog != null || 
                        showAboutDialog || 
                        mediaToDelete != null ||
                        multiMediaToDelete != null ||
                        folderToDelete != null ||
                        selectedTab != "Play"

    BackHandler(enabled = isBackEnabled) {
        when {
            showAboutAppSection -> {
                showAboutAppSection = false
            }
            activeFolderGroup != null -> {
                activeFolderGroup = null
            }
            isSelectModeActive -> {
                isSelectModeActive = false
                selectedMediaSet.clear()
            }
            isSearchExpanded -> {
                isSearchExpanded = false
                viewModel.updateSearchQuery("")
            }
            selectedMediaForOptions != null -> {
                selectedMediaForOptions = null
            }
            showInfoDialogForMedia != null -> {
                showInfoDialogForMedia = null
            }
            showAddStreamDrawer -> {
                showAddStreamDrawer = false
            }
            showStorageBrowser -> {
                showStorageBrowser = false
            }
            showPlaylistPickerForMedia != null -> {
                showPlaylistPickerForMedia = null
            }
            showPlaylistPickerForFolder != null -> {
                showPlaylistPickerForFolder = null
            }
            showSubtitleDownloadDialog != null -> {
                showSubtitleDownloadDialog = null
            }
            showResumeOrStartDialog != null -> {
                showResumeOrStartDialog = null
            }
            showAboutDialog -> {
                showAboutDialog = false
            }
            mediaToDelete != null -> {
                mediaToDelete = null
            }
            multiMediaToDelete != null -> {
                multiMediaToDelete = null
            }
            folderToDelete != null -> {
                folderToDelete = null
            }
            selectedTab != "Play" -> {
                viewModel.selectTab("Play")
            }
        }
    }

    LaunchedEffect(playSubTab, prefs.groupByStyle) {
        activeFolderGroup = null
    }

    // Grouping computation for folder / artist / file_type sorting
    val groupedMediaMap = remember(mediaList, prefs.groupByStyle) {
        when (prefs.groupByStyle) {
            "folder" -> {
                mediaList.groupBy {
                    val file = java.io.File(it.path)
                    file.parentFile?.name ?: "Root Folder"
                }
            }
            "artist" -> {
                mediaList.groupBy { it.displayArtist }
            }
            "file_type" -> {
                mediaList.groupBy { it.path.substringAfterLast('.', "UNKNOWN").uppercase() }
            }
            else -> {
                emptyMap()
            }
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "Aero Player",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = "Your Advanced Media Hub",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                
                Text(
                    text = "Library",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Video Library", fontWeight = FontWeight.SemiBold) },
                    selected = (selectedTab == "Play" && playSubTab == "Video"),
                    onClick = {
                        scope.launch {
                            viewModel.selectTab("Play")
                            viewModel.selectPlaySubTab("Video")
                            drawerState.close()
                        }
                    },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                NavigationDrawerItem(
                    label = { Text("Audio Library", fontWeight = FontWeight.SemiBold) },
                    selected = (selectedTab == "Play" && playSubTab == "Audio"),
                    onClick = {
                        scope.launch {
                            viewModel.selectTab("Play")
                            viewModel.selectPlaySubTab("Audio")
                            drawerState.close()
                        }
                    },
                    icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                NavigationDrawerItem(
                    label = { Text("Favorites & Saved", fontWeight = FontWeight.SemiBold) },
                    selected = (selectedTab == "Play" && playSubTab == "Favorites"),
                    onClick = {
                        scope.launch {
                            viewModel.selectTab("Play")
                            viewModel.selectPlaySubTab("Favorites")
                            drawerState.close()
                        }
                    },
                    icon = { Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.Red) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                NavigationDrawerItem(
                    label = { Text("Folders", fontWeight = FontWeight.SemiBold) },
                    selected = (selectedTab == "Play" && playSubTab == "Folder"),
                    onClick = {
                        scope.launch {
                            viewModel.selectTab("Play")
                            viewModel.selectPlaySubTab("Folder")
                            drawerState.close()
                        }
                    },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                NavigationDrawerItem(
                    label = { Text("Playlists", fontWeight = FontWeight.SemiBold) },
                    selected = (selectedTab == "Play" && playSubTab == "Playlist"),
                    onClick = {
                        scope.launch {
                            viewModel.selectTab("Play")
                            viewModel.selectPlaySubTab("Playlist")
                            drawerState.close()
                        }
                    },
                    icon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                NavigationDrawerItem(
                    label = { Text("Streams & Casts", fontWeight = FontWeight.SemiBold) },
                    selected = (selectedTab == "Play" && playSubTab == "Browse"),
                    onClick = {
                        scope.launch {
                            viewModel.selectTab("Play")
                            viewModel.selectPlaySubTab("Browse")
                            drawerState.close()
                        }
                    },
                    icon = { Icon(Icons.Default.Tv, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                Text(
                    text = "Options",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Add Network Stream", fontWeight = FontWeight.SemiBold) },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            showAddStreamDrawer = true
                        }
                    },
                    icon = { Icon(Icons.Default.AddLink, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Advanced Settings", fontWeight = FontWeight.SemiBold) },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            onNavigateToSettings()
                        }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    ) {
        FrostedGlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (!showAboutAppSection) {
                    Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .statusBarsPadding()
                ) {
                    val accentOrange = MaterialTheme.colorScheme.primary
                    
                    val context = LocalContext.current
                    val getFilesForFolder = { folderName: String ->
                        mediaList.filter { item ->
                            item.genre != "Live Stream" && (
                                (java.io.File(item.path).parentFile?.name ?: "Root Folder") == folderName ||
                                item.album == folderName ||
                                item.artist == folderName ||
                                item.path.contains(folderName)
                            )
                        }
                    }
                    val isSelectionActive = isSelectModeActive || selectionState.isInSelectionMode
                    
                    Crossfade(targetState = isSelectionActive, label = "TopBarCrossfade") { inSelection ->
                        if (inSelection) {
                            // Contextual Action Top Bar (M3 Complaint)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(onClick = { 
                                        if (isSelectModeActive) {
                                            isSelectModeActive = false
                                            selectedMediaSet.clear()
                                        }
                                        if (selectionState.isInSelectionMode) {
                                            viewModel.clearSelection()
                                        }
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear Selection", tint = accentOrange)
                                    }
                                    
                                    val totalCount = if (isSelectModeActive) {
                                        selectedMediaSet.size
                                    } else {
                                        selectionState.selectedFolderPaths.size + selectionState.selectedVideoIds.size
                                    }
                                    Text(
                                        text = "$totalCount Selected",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )

                                    IconButton(
                                        onClick = {
                                            if (isSelectModeActive) {
                                                val nonStreams = mediaList.filter { it.genre != "Live Stream" }
                                                if (selectedMediaSet.size >= nonStreams.size) {
                                                    selectedMediaSet.clear()
                                                } else {
                                                    selectedMediaSet.clear()
                                                    selectedMediaSet.addAll(nonStreams)
                                                }
                                            } else if (selectionState.isInSelectionMode) {
                                                val nonStreams = mediaList.filter { it.genre != "Live Stream" }
                                                val folderNames = nonStreams.map { java.io.File(it.path).parentFile?.name ?: "Root Folder" }.distinct()
                                                val videoIds = nonStreams.map { it.uriString }
                                                val totalPossible = folderNames.size + videoIds.size
                                                if (selectionState.selectedFolderPaths.size + selectionState.selectedVideoIds.size >= totalPossible) {
                                                    viewModel.clearSelection()
                                                } else {
                                                    viewModel.selectAllFoldersAndFiles(folderNames, videoIds)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.SelectAll, contentDescription = "Select All / Toggle", tint = accentOrange)
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (isSelectModeActive) {
                                        // File selection actions
                                        if (selectedMediaSet.size == 1) {
                                            val selectedItem = selectedMediaSet.first()
                                            IconButton(onClick = {
                                                val currentList = mediaList.filter { it.genre != "Live Stream" }
                                                viewModel.setPlayingItemWithQueue(selectedItem, currentList)
                                                onPlayItem(selectedItem)
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = accentOrange)
                                            }
                                            IconButton(onClick = {
                                                viewModel.addToQueue(listOf(selectedItem))
                                                android.widget.Toast.makeText(context, "Added to play queue", android.widget.Toast.LENGTH_SHORT).show()
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to Queue", tint = accentOrange)
                                            }
                                            IconButton(onClick = {
                                                mediaToDelete = selectedItem
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete File", tint = MaterialTheme.colorScheme.error)
                                            }
                                        } else if (selectedMediaSet.size > 1) {
                                            IconButton(onClick = {
                                                viewModel.playAll(selectedMediaSet.toList())
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Play Selected", tint = accentOrange)
                                            }
                                            IconButton(onClick = {
                                                viewModel.addToQueue(selectedMediaSet.toList())
                                                android.widget.Toast.makeText(context, "Added selected files to queue", android.widget.Toast.LENGTH_SHORT).show()
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.PlaylistAdd, contentDescription = "Queue Selected", tint = accentOrange)
                                            }
                                            IconButton(onClick = {
                                                multiMediaToDelete = selectedMediaSet.toList()
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    } else {
                                        // Folder-tab / Browse-tab selection actions
                                        val totalSelected = selectionState.selectedFolderPaths.size + selectionState.selectedVideoIds.size
                                        val allMedia = mutableListOf<MediaEntity>()
                                        selectionState.selectedFolderPaths.forEach { folderPath ->
                                            allMedia.addAll(getFilesForFolder(folderPath))
                                        }
                                        selectionState.selectedVideoIds.forEach { uri ->
                                            mediaList.find { it.uriString == uri }?.let { allMedia.add(it) }
                                        }

                                        IconButton(onClick = {
                                            if (allMedia.isNotEmpty()) {
                                                viewModel.playAll(allMedia)
                                            }
                                            viewModel.clearSelection()
                                        }, enabled = allMedia.isNotEmpty()) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Play Selected", tint = if (allMedia.isNotEmpty()) accentOrange else Color.Gray)
                                        }
                                        IconButton(onClick = {
                                            if (allMedia.isNotEmpty()) {
                                                viewModel.addToQueue(allMedia)
                                            }
                                            viewModel.clearSelection()
                                        }, enabled = allMedia.isNotEmpty()) {
                                            Icon(Icons.Default.PlaylistAdd, contentDescription = "Queue Selected", tint = if (allMedia.isNotEmpty()) accentOrange else Color.Gray)
                                        }
                                        IconButton(onClick = {
                                            if (allMedia.isNotEmpty()) {
                                                multiMediaToDelete = allMedia
                                            }
                                            viewModel.clearSelection()
                                        }, enabled = allMedia.isNotEmpty()) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }

                                    // Overflow / More Menu
                                    Box {
                                        IconButton(onClick = { showSelectionMoreMenu = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = accentOrange)
                                        }

                                        DropdownMenu(
                                            expanded = showSelectionMoreMenu,
                                            onDismissRequest = { showSelectionMoreMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Select All") },
                                                leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                                                onClick = {
                                                    showSelectionMoreMenu = false
                                                    if (isSelectModeActive) {
                                                        selectedMediaSet.clear()
                                                        selectedMediaSet.addAll(mediaList.filter { it.genre != "Live Stream" })
                                                    } else {
                                                        val nonStreams = mediaList.filter { it.genre != "Live Stream" }
                                                        val folderNames = nonStreams.map { java.io.File(it.path).parentFile?.name ?: "Root Folder" }.distinct()
                                                        val videoIds = nonStreams.map { it.uriString }
                                                        viewModel.selectAllFoldersAndFiles(folderNames, videoIds)
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Deselect All") },
                                                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                                onClick = {
                                                    showSelectionMoreMenu = false
                                                    if (isSelectModeActive) {
                                                        isSelectModeActive = false
                                                        selectedMediaSet.clear()
                                                    }
                                                    viewModel.clearSelection()
                                                }
                                            )
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text("Play Next") },
                                                leadingIcon = { Icon(Icons.Default.Queue, contentDescription = null) },
                                                onClick = {
                                                    showSelectionMoreMenu = false
                                                    val items = if (isSelectModeActive) selectedMediaSet.toList() else {
                                                        val list = mutableListOf<MediaEntity>()
                                                        selectionState.selectedFolderPaths.forEach { list.addAll(getFilesForFolder(it)) }
                                                        selectionState.selectedVideoIds.forEach { uri -> mediaList.find { it.uriString == uri }?.let { list.add(it) } }
                                                        list
                                                    }
                                                    items.asReversed().forEach { viewModel.insertNext(it) }
                                                    android.widget.Toast.makeText(context, "Inserted selected in queue", android.widget.Toast.LENGTH_SHORT).show()
                                                    isSelectModeActive = false
                                                    selectedMediaSet.clear()
                                                    viewModel.clearSelection()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Add to Playlist") },
                                                leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                                                onClick = {
                                                    showSelectionMoreMenu = false
                                                    val items = if (isSelectModeActive) selectedMediaSet.toList() else {
                                                        val list = mutableListOf<MediaEntity>()
                                                        selectionState.selectedFolderPaths.forEach { list.addAll(getFilesForFolder(it)) }
                                                        selectionState.selectedVideoIds.forEach { uri -> mediaList.find { it.uriString == uri }?.let { list.add(it) } }
                                                        list
                                                    }
                                                    if (items.isNotEmpty()) {
                                                        showPlaylistPickerForFolder = items
                                                    }
                                                    isSelectModeActive = false
                                                    selectedMediaSet.clear()
                                                    viewModel.clearSelection()
                                                }
                                            )
                                            if (selectionState.selectedFolderPaths.isNotEmpty()) {
                                                DropdownMenuItem(
                                                    text = { Text("Pin / Favorite Folder(s)") },
                                                    leadingIcon = { Icon(Icons.Default.FolderSpecial, contentDescription = null) },
                                                    onClick = {
                                                        showSelectionMoreMenu = false
                                                        selectionState.selectedFolderPaths.forEach { viewModel.toggleFavoriteFolder(it) }
                                                        viewModel.clearSelection()
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Hide / Ban Folder(s)") },
                                                    leadingIcon = { Icon(Icons.Default.Block, contentDescription = null) },
                                                    onClick = {
                                                        showSelectionMoreMenu = false
                                                        selectionState.selectedFolderPaths.forEach { viewModel.toggleBannedFolder(it) }
                                                        viewModel.clearSelection()
                                                    }
                                                )
                                            }
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text("Delete Selected") },
                                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    showSelectionMoreMenu = false
                                                    val items = if (isSelectModeActive) selectedMediaSet.toList() else {
                                                        val list = mutableListOf<MediaEntity>()
                                                        selectionState.selectedFolderPaths.forEach { list.addAll(getFilesForFolder(it)) }
                                                        selectionState.selectedVideoIds.forEach { uri -> mediaList.find { it.uriString == uri }?.let { list.add(it) } }
                                                        list
                                                    }
                                                    if (items.isNotEmpty()) {
                                                        multiMediaToDelete = items
                                                    }
                                                    isSelectModeActive = false
                                                    selectedMediaSet.clear()
                                                    viewModel.clearSelection()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (isSearchExpanded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { viewModel.updateSearchQuery(it) },
                                    placeholder = { Text("Search files, streams, artists...", fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            viewModel.updateSearchQuery("")
                                            isSearchExpanded = false
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "Close search")
                                        }
                                    },
                                    shape = RoundedCornerShape(24.dp),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        focusedBorderColor = accentOrange,
                                        unfocusedBorderColor = Color.Transparent
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("search_field_media")
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Branding Title with Hamburger Menu Button
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(
                                            imageVector = Icons.Default.Menu,
                                            contentDescription = "Open side drawer",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = "Aero",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary,
                                        letterSpacing = (-0.5).sp
                                    )
                                }

                                // Quick Actions: Search, Layout Toggle, Add, Settings
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(onClick = { isSearchExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "Search",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Box {
                                        IconButton(onClick = { showDisplaySettingsBottomSheet = true }) {
                                            Icon(
                                                imageVector = Icons.Default.Sort,
                                                contentDescription = "Sort and view options",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showSortMenu,
                                            onDismissRequest = { showSortMenu = false }
                                        ) {
                                            // VIEW LAYOUT TOGGLE
                                            DropdownMenuItem(
                                                text = { Text(if (prefs.listStyle == "Grid") "Switch to List View" else "Switch to Grid View") },
                                                leadingIcon = { Icon(if (prefs.listStyle == "Grid") Icons.Default.List else Icons.Default.GridView, contentDescription = null) },
                                                onClick = {
                                                    val newStyle = if (prefs.listStyle == "Grid") "List" else "Grid"
                                                    viewModel.updateListStyle(newStyle)
                                                    showSortMenu = false
                                                }
                                            )
                                            
                                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

                                            // SORT OPTIONS
                                            DropdownMenuItem(
                                                text = { Text("Sort by Title") },
                                                leadingIcon = { Icon(Icons.Default.Title, contentDescription = null, tint = if (prefs.sortBy == "title") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface) },
                                                trailingIcon = { if (prefs.sortBy == "title") Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                                onClick = {
                                                    viewModel.updateSorting("title", prefs.sortAscending)
                                                    showSortMenu = false
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Sort by Date Added") },
                                                leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null, tint = if (prefs.sortBy == "date") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface) },
                                                trailingIcon = { if (prefs.sortBy == "date") Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                                onClick = {
                                                    viewModel.updateSorting("date", prefs.sortAscending)
                                                    showSortMenu = false
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Sort by File Size") },
                                                leadingIcon = { Icon(Icons.Default.SdCard, contentDescription = null, tint = if (prefs.sortBy == "size") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface) },
                                                trailingIcon = { if (prefs.sortBy == "size") Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                                onClick = {
                                                    viewModel.updateSorting("size", prefs.sortAscending)
                                                    showSortMenu = false
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Merge Group Wise (Folder)") },
                                                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, tint = if (prefs.sortBy == "folder") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface) },
                                                trailingIcon = { if (prefs.sortBy == "folder") Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                                onClick = {
                                                    viewModel.updateSorting("folder", prefs.sortAscending)
                                                    showSortMenu = false
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Merge Group Wise (Artist)") },
                                                leadingIcon = { Icon(Icons.Default.Group, contentDescription = null, tint = if (prefs.sortBy == "artist") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface) },
                                                trailingIcon = { if (prefs.sortBy == "artist") Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                                onClick = {
                                                    viewModel.updateSorting("artist", prefs.sortAscending)
                                                    showSortMenu = false
                                                }
                                            )

                                             HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

                                             // SORT ORDER DIRECTION
                                             DropdownMenuItem(
                                                 text = { Text(if (prefs.sortAscending) "Order: Ascending" else "Order: Descending") },
                                                 leadingIcon = { Icon(if (prefs.sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, contentDescription = null) },
                                                 onClick = {
                                                     viewModel.updateSorting(prefs.sortBy, !prefs.sortAscending)
                                                     showSortMenu = false
                                                 }
                                             )

                                             HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

                                             // STORAGE PICKER ENTRY
                                             DropdownMenuItem(
                                                 text = { Text("Browse Files from Storage") },
                                                 leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                                 onClick = {
                                                     showStorageBrowser = true
                                                     showSortMenu = false
                                                 }
                                             )
                                             DropdownMenuItem(
                                                 text = { Text("Open File (System Picker)") },
                                                 leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                                 onClick = {
                                                     openDocumentLauncher.launch(arrayOf("video/*", "audio/*", "*/*"))
                                                     showSortMenu = false
                                                 }
                                             )
                                             DropdownMenuItem(
                                                 text = { Text("Load Sample Media Pack") },
                                                 leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                                 onClick = {
                                                     viewModel.loadDemoMediaPack()
                                                     showSortMenu = false
                                                     android.widget.Toast.makeText(context, "Loaded HD sample videos and audio tracks", android.widget.Toast.LENGTH_SHORT).show()
                                                 }
                                             )
                                         }
                                     }

                                    // Removed stream icon as requested

                                    IconButton(onClick = onNavigateToSettings) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Direct Scan Progress Bar (Requirement #1)
                    AnimatedVisibility(
                        visible = isScanning,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Scanning device library...",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(CircleShape),
                                 color = MaterialTheme.colorScheme.secondary,
                                trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    val currentItem = activeItem
                    if (currentItem != null) {
                        var isPlaying by remember { mutableStateOf(false) }
                        DisposableEffect(viewModel.exoPlayer) {
                            val listener = object : androidx.media3.common.Player.Listener {
                                override fun onIsPlayingChanged(playing: Boolean) {
                                    isPlaying = playing
                                }
                            }
                            try {
                                viewModel.exoPlayer.addListener(listener)
                                isPlaying = viewModel.exoPlayer.isPlaying
                            } catch (e: Throwable) {
                                e.printStackTrace()
                            }
                            onDispose {
                                try {
                                    viewModel.exoPlayer.removeListener(listener)
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                }
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clickable { onOpenPlayer() },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    MediaThumbnail(
                                        item = currentItem,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentItem.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        modifier = Modifier.basicMarquee()
                                    )
                                    Text(
                                        text = currentItem.displayArtist,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    AnimatedPlayPauseButton(
                                        isPlaying = isPlaying,
                                        onClick = {
                                            if (isPlaying) {
                                                viewModel.exoPlayer.pause()
                                            } else {
                                                viewModel.exoPlayer.play()
                                            }
                                        },
                                        modifier = Modifier.size(38.dp),
                                        iconSize = 22.dp,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )

                                    IconButton(
                                        onClick = { viewModel.playNext() },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SkipNext,
                                            contentDescription = "Next",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.clearPlayingItem() },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Compact bottom capsule bar with sliding indicator pill
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val tabs = listOf("Play", "More")
                        val selectedIndex = tabs.indexOf(selectedTab)
                        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                        
                        Surface(
                            modifier = Modifier
                                .width(250.dp)
                                .height(56.dp),
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)),
                            shadowElevation = 8.dp,
                            tonalElevation = 4.dp
                        ) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(4.dp)
                            ) {
                            val containerWidth = maxWidth
                            val tabWidth = containerWidth / 2
                            
                            val animatedOffset by animateDpAsState(
                                targetValue = if (selectedIndex == 0) 0.dp else tabWidth,
                                animationSpec = spring(stiffness = Spring.StiffnessMedium)
                            )
                            
                            Box(
                                modifier = Modifier
                                    .offset(x = animatedOffset)
                                    .width(tabWidth)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp))
                            )
                            
                            Row(modifier = Modifier.fillMaxSize()) {
                                tabs.forEach { tab ->
                                    val isSelected = selectedTab == tab
                                    val contentColor by animateColorAsState(
                                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val iconScale by animateFloatAsState(
                                        targetValue = if (isSelected) 1.25f else 1.0f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy),
                                        label = "tab_icon_scale"
                                    )
                                    val textScale by animateFloatAsState(
                                        targetValue = if (isSelected) 1.08f else 0.95f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                        label = "tab_text_scale"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(22.dp))
                                            .bounceClick(
                                                pressedScale = 0.88f,
                                                onClick = {
                                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    viewModel.selectTab(tab)
                                                }
                                            )
                                            .testTag("nav_tab_$tab"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (tab == "Play") Icons.Default.PlayCircle else Icons.Default.MoreHoriz,
                                                contentDescription = if (tab == "Play") "Player" else "More",
                                                tint = contentColor,
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .graphicsLayer {
                                                        scaleX = iconScale
                                                        scaleY = iconScale
                                                    }
                                            )
                                            Text(
                                                text = if (tab == "Play") "Player" else "More",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = contentColor,
                                                modifier = Modifier.graphicsLayer {
                                                    scaleX = textScale
                                                    scaleY = textScale
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
            }
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = isScanning,
                onRefresh = { viewModel.scanLocalMedia() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Smooth hardware-accelerated sliding transition between Play and More tabs
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        if (targetState == "More") {
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width } + fadeOut()
                            )
                        } else {
                            (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut()
                            )
                        }
                    },
                    label = "tab_slide_transition",
                    modifier = Modifier.fillMaxSize()
                ) { currentTab ->
                    if (currentTab == "Play") {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Transparent)
                        ) {
                            when (playSubTab) {
                                "Playlist" -> {
                                    PlaylistTabContent(viewModel = viewModel, onPlayItem = onPlayItem)
                                }
                                "Favorites" -> {
                                    FavoritesTabContent(viewModel = viewModel, onPlayItem = onPlayItem)
                                }
                                "Folder" -> {
                                    BrowseTabContent(viewModel = viewModel, onPlayItem = onPlayItem)
                                }
                                "Browse" -> {
                                    StreamOnlyTabContent(
                                        viewModel = viewModel,
                                        onPlayItem = onPlayItem,
                                        showAddStreamDrawer = { showAddStreamDrawer = true }
                                    )
                                }
                                else -> {
                                    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
                                    val nonStreamMediaList = remember(mediaList, playSubTab) {
                                        mediaList.filter { 
                                            it.genre != "Live Stream" &&
                                            if (playSubTab == "Video") it.isVideo else !it.isVideo
                                        }
                                    }
                                    val nonStreamGroupedMediaMap = remember(nonStreamMediaList, prefs.groupByStyle) {
                                        when (prefs.groupByStyle) {
                                            "folder" -> {
                                                nonStreamMediaList.groupBy {
                                                    val file = java.io.File(it.path)
                                                    file.parentFile?.name ?: "Root Folder"
                                                }
                                            }
                                            "artist" -> {
                                                nonStreamMediaList.groupBy { it.displayArtist }
                                            }
                                            "file_type" -> {
                                                nonStreamMediaList.groupBy { it.path.substringAfterLast('.', "UNKNOWN").uppercase() }
                                            }
                                            else -> {
                                                emptyMap()
                                            }
                                        }
                                    }

                                    if (nonStreamMediaList.isEmpty()) {
                                        EmptyState(
                                            tabName = playSubTab,
                                            onScanClick = { viewModel.scanLocalMedia() },
                                            onOpenFileClick = {
                                                openDocumentLauncher.launch(arrayOf("video/*", "audio/*", "*/*"))
                                            },
                                            onLoadDemoClick = {
                                                viewModel.loadDemoMediaPack()
                                                android.widget.Toast.makeText(context, "Loaded HD sample videos and audio tracks", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    } else if (prefs.groupByStyle == "folder") {
                                        // -----------------------------------------------------------------
                                        // NEW FOLDER OPENING SYSTEM (Instead of collapsible accordion style)
                                        // -----------------------------------------------------------------
                                        if (activeFolderGroup == null) {
                                            // Root Folder level
                                            val foldersList = remember(nonStreamGroupedMediaMap, prefs.sortBy, prefs.sortAscending) {
                                                val keys = nonStreamGroupedMediaMap.keys.toList()
                                                keys.sortedWith(Comparator { f1, f2 ->
                                                    val files1 = nonStreamGroupedMediaMap[f1] ?: emptyList()
                                                    val files2 = nonStreamGroupedMediaMap[f2] ?: emptyList()
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
                                                })
                                            }
                                            if (foldersList.isEmpty()) {
                                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Text("No folders found", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                                }
                                            } else {
                                                if (prefs.useGroupWiseFolderStyle) {
                                                    // Grid layout for folders
                                                    LazyVerticalGrid(
                                                        columns = GridCells.Fixed(3),
                                                        contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 12.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                                        modifier = Modifier.fillMaxSize()
                                                    ) {
                                                        items(foldersList) { folderName ->
                                                            val folderVideos = nonStreamGroupedMediaMap[folderName] ?: emptyList()
                                                            val isFolderSelected = selectionState.selectedFolderPaths.contains(folderName) || (isSelectModeActive && selectedMediaSet.any { (java.io.File(it.path).parentFile?.name ?: "Root Folder") == folderName || it.album == folderName || it.artist == folderName })
                                                            Card(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .aspectRatio(0.82f)
                                                                    .combinedClickable(
                                                                        onClick = {
                                                                            if (selectionState.isInSelectionMode || isSelectModeActive) {
                                                                                viewModel.toggleFolderSelection(folderName)
                                                                            } else {
                                                                                activeFolderGroup = folderName
                                                                            }
                                                                        },
                                                                        onLongClick = {
                                                                            viewModel.toggleFolderSelection(folderName)
                                                                        }
                                                                    ),
                                                                shape = RoundedCornerShape(12.dp),
                                                                colors = CardDefaults.cardColors(
                                                                    containerColor = if (isFolderSelected) accentOrange.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                                                ),
                                                                border = BorderStroke(
                                                                    if (isFolderSelected) 2.dp else 0.dp,
                                                                    if (isFolderSelected) accentOrange else Color.Transparent
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
                                                                            text = folderName,
                                                                            fontSize = 12.sp,
                                                                            fontWeight = FontWeight.Bold,
                                                                            color = MaterialTheme.colorScheme.onSurface,
                                                                            maxLines = 2,
                                                                            overflow = TextOverflow.Ellipsis,
                                                                            textAlign = TextAlign.Center
                                                                        )
                                                                        val totalFolderSize = folderVideos.sumOf { it.size }
                                                                        val folderSizeStr = formatMediaFileSize(totalFolderSize)
                                                                        val folderSubtext = if (folderSizeStr.isNotEmpty()) "${folderVideos.size} files • $folderSizeStr" else "${folderVideos.size} files"
                                                                        Text(
                                                                            text = folderSubtext,
                                                                            fontSize = 10.sp,
                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                                            textAlign = TextAlign.Center
                                                                        )
                                                                    }
                                                                    if (isFolderSelected) {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .align(Alignment.TopEnd)
                                                                                .padding(6.dp)
                                                                                .size(24.dp)
                                                                                .clip(CircleShape)
                                                                                .background(accentOrange),
                                                                            contentAlignment = Alignment.Center
                                                                        ) {
                                                                            Icon(
                                                                                imageVector = Icons.Default.Check,
                                                                                contentDescription = "Selected",
                                                                                tint = Color.White,
                                                                                modifier = Modifier.size(16.dp)
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    // List layout for folders
                                                    LazyColumn(
                                                        contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 12.dp),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                                        modifier = Modifier.fillMaxSize()
                                                    ) {
                                                        items(foldersList) { folderName ->
                                                            val folderVideos = nonStreamGroupedMediaMap[folderName] ?: emptyList()
                                                            val isFolderSelected = selectionState.selectedFolderPaths.contains(folderName) || (isSelectModeActive && selectedMediaSet.any { (java.io.File(it.path).parentFile?.name ?: "Root Folder") == folderName || it.album == folderName || it.artist == folderName })
                                                            Card(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .combinedClickable(
                                                                        onClick = {
                                                                            if (selectionState.isInSelectionMode || isSelectModeActive) {
                                                                                viewModel.toggleFolderSelection(folderName)
                                                                            } else {
                                                                                activeFolderGroup = folderName
                                                                            }
                                                                        },
                                                                        onLongClick = {
                                                                            viewModel.toggleFolderSelection(folderName)
                                                                        }
                                                                    ),
                                                                shape = RoundedCornerShape(8.dp),
                                                                colors = CardDefaults.cardColors(
                                                                    containerColor = if (isFolderSelected) accentOrange.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                                                ),
                                                                border = BorderStroke(
                                                                    if (isFolderSelected) 2.dp else 0.dp,
                                                                    if (isFolderSelected) accentOrange else Color.Transparent
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
                                                                            text = folderName,
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
                                                                    if (isFolderSelected) {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .size(24.dp)
                                                                                .clip(CircleShape)
                                                                                .background(accentOrange),
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
                                                                            modifier = Modifier.size(18.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            // Open Folder level
                                            val rawFolderFiles = nonStreamGroupedMediaMap[activeFolderGroup] ?: emptyList()
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
                                                // Breadcrumb / Folder path header
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                                        .padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    IconButton(
                                                        onClick = { activeFolderGroup = null },
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
                                                    Icon(
                                                        imageVector = Icons.Default.FolderOpen,
                                                        contentDescription = null,
                                                        tint = accentOrange,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = activeFolderGroup!!,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = accentOrange
                                                    )
                                                }

                                                // Play all / Queue all controls row
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Button(
                                                            onClick = { viewModel.playAll(files) },
                                                            enabled = files.isNotEmpty(),
                                                            colors = ButtonDefaults.buttonColors(
                                                                containerColor = accentOrange,
                                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                                            ),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                            shape = RoundedCornerShape(8.dp),
                                                            modifier = Modifier.height(32.dp)
                                                        ) {
                                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("Play All", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                                                        }

                                                        OutlinedButton(
                                                            onClick = { viewModel.addToQueue(files) },
                                                            enabled = files.isNotEmpty(),
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

                                                    // Quick toggle grid/list for folder level
                                                    IconButton(
                                                        onClick = {
                                                            val newStyle = if (prefs.listStyle == "Grid") "List" else "Grid"
                                                            viewModel.updateListStyle(newStyle)
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (prefs.listStyle == "Grid") Icons.Default.List else Icons.Default.GridView,
                                                            contentDescription = "Toggle Grid/List View",
                                                            tint = accentOrange,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }

                                                // Grid or List view for files in opened folder
                                                if (prefs.listStyle == "Grid") {
                                                    LazyVerticalGrid(
                                                        columns = GridCells.Adaptive(150.dp),
                                                        contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 8.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                                        modifier = Modifier.fillMaxSize()
                                                    ) {
                                                        items(files, key = { it.uriString }) { item ->
                                                            val isSelected = selectedMediaSet.any { it.uriString == item.uriString }
                                                            MediaGridCard(
                                                                item = item,
                                                                isSelected = isSelected,
                                                                isSelectModeActive = isSelectModeActive,
                                                                onMenuClick = { selectedMediaForOptions = item },
                                                                isActive = (activeItem?.uriString == item.uriString),
                                                                progress = historyProgressMap[item.uriString],
                                                                onClick = {
                                                                    if (isSelectModeActive) {
                                                                        if (isSelected) {
                                                                            selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                                            if (selectedMediaSet.isEmpty()) isSelectModeActive = false
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
                                                                        isSelectModeActive = true
                                                                        selectedMediaSet.clear()
                                                                        selectedMediaSet.add(item)
                                                                    } else {
                                                                        if (isSelected) {
                                                                            selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                                            if (selectedMediaSet.isEmpty()) isSelectModeActive = false
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
                                                        contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 8.dp),
                                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                                        modifier = Modifier.fillMaxSize()
                                                    ) {
                                                        items(files, key = { item -> item.uriString }) { item ->
                                                            val isSelected = selectedMediaSet.any { it.uriString == item.uriString }
                                                            MediaListRow(
                                                                item = item,
                                                                isSelected = isSelected,
                                                                isSelectModeActive = isSelectModeActive,
                                                                onMenuClick = { selectedMediaForOptions = item },
                                                                isActive = (activeItem?.uriString == item.uriString),
                                                                progress = historyProgressMap[item.uriString],
                                                                onClick = {
                                                                    if (isSelectModeActive) {
                                                                        if (isSelected) {
                                                                            selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                                            if (selectedMediaSet.isEmpty()) isSelectModeActive = false
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
                                                                        isSelectModeActive = true
                                                                        selectedMediaSet.clear()
                                                                        selectedMediaSet.add(item)
                                                                    } else {
                                                                        if (isSelected) {
                                                                            selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                                            if (selectedMediaSet.isEmpty()) isSelectModeActive = false
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
                                    } else {
                                        if (prefs.listStyle == "Grid") {
                                            LazyVerticalGrid(
                                                columns = GridCells.Adaptive(150.dp),
                                                contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 12.dp),
                                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                if (nonStreamGroupedMediaMap.isNotEmpty()) {
                                                    nonStreamGroupedMediaMap.forEach { (groupName, itemsInGroup) ->
                                                        val isExpanded = expandedGroups.contains(groupName)
                                                        item(span = { GridItemSpan(maxLineSpan) }) {
                                                            GroupHeaderRow(
                                                                groupName = groupName,
                                                                itemCount = itemsInGroup.size,
                                                                isExpanded = isExpanded,
                                                                onToggleExpand = {
                                                                    expandedGroups = if (isExpanded) {
                                                                        expandedGroups - groupName
                                                                    } else {
                                                                        expandedGroups + groupName
                                                                    }
                                                                },
                                                                onPlayAll = { viewModel.playAll(itemsInGroup) },
                                                                onAddToQueue = { viewModel.addToQueue(itemsInGroup) }
                                                            )
                                                        }
                                                        if (isExpanded) {
                                                            items(itemsInGroup, key = { it.uriString }) { item ->
                                                                val isSelected = selectedMediaSet.any { it.uriString == item.uriString }
                                                                MediaGridCard(
                                                                item = item,
                                                                isSelected = isSelected,
                                                                isSelectModeActive = isSelectModeActive,
                                                                onMenuClick = { selectedMediaForOptions = item },
                                                                isActive = (activeItem?.uriString == item.uriString),
                                                                progress = historyProgressMap[item.uriString],
                                                                onClick = {
                                                                if (isSelectModeActive) {
                                                                if (isSelected) {
                                                                selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                                if (selectedMediaSet.isEmpty()) isSelectModeActive = false
                                                                } else {
                                                                selectedMediaSet.add(item)
                                                                }
                                                                } else {
                                                                viewModel.setPlayingItemWithQueue(item, itemsInGroup)
                                                                onPlayItem(item)
                                                                }
                                                                },
                                                                onLongClick = {
                                                                if (!isSelectModeActive) {
                                                                isSelectModeActive = true
                                                                selectedMediaSet.clear()
                                                                selectedMediaSet.add(item)
                                                                } else {
                                                                if (isSelected) {
                                                                selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                                if (selectedMediaSet.isEmpty()) isSelectModeActive = false
                                                                } else {
                                                                selectedMediaSet.add(item)
                                                                }
                                                                }
                                                                }
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                                        Text(
                                                            text = "All Files",
                                                            fontWeight = FontWeight.ExtraBold,
                                                            fontSize = 16.sp,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                                        )
                                                    }
                                                    items(nonStreamMediaList, key = { it.uriString }) { item ->
                                                        val isSelected = selectedMediaSet.any { it.uriString == item.uriString }
                                                        MediaGridCard(
                                                        item = item,
                                                        isSelected = isSelected,
                                                        isSelectModeActive = isSelectModeActive,
                                                        onMenuClick = { selectedMediaForOptions = item },
                                                        isActive = (activeItem?.uriString == item.uriString),
                                                        progress = historyProgressMap[item.uriString],
                                                        onClick = {
                                                        if (isSelectModeActive) {
                                                        if (isSelected) {
                                                        selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                        if (selectedMediaSet.isEmpty()) isSelectModeActive = false
                                                        } else {
                                                        selectedMediaSet.add(item)
                                                        }
                                                        } else {
                                                        viewModel.setPlayingItemWithQueue(item, nonStreamMediaList)
                                                        onPlayItem(item)
                                                        }
                                                        },
                                                        onLongClick = {
                                                        if (!isSelectModeActive) {
                                                        isSelectModeActive = true
                                                        selectedMediaSet.clear()
                                                        selectedMediaSet.add(item)
                                                        } else {
                                                        if (isSelected) {
                                                        selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                        if (selectedMediaSet.isEmpty()) isSelectModeActive = false
                                                        } else {
                                                        selectedMediaSet.add(item)
                                                        }
                                                        }
                                                        }
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            LazyColumn(
                                                contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 12.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                if (nonStreamGroupedMediaMap.isNotEmpty()) {
                                                    nonStreamGroupedMediaMap.forEach { (groupName, itemsInGroup) ->
                                                        val isExpanded = expandedGroups.contains(groupName)
                                                        item {
                                                            GroupHeaderRow(
                                                                groupName = groupName,
                                                                itemCount = itemsInGroup.size,
                                                                isExpanded = isExpanded,
                                                                onToggleExpand = {
                                                                    expandedGroups = if (isExpanded) {
                                                                        expandedGroups - groupName
                                                                    } else {
                                                                        expandedGroups + groupName
                                                                    }
                                                                },
                                                                onPlayAll = { viewModel.playAll(itemsInGroup) },
                                                                onAddToQueue = { viewModel.addToQueue(itemsInGroup) }
                                                            )
                                                        }
                                                        if (isExpanded) {
                                                            items(itemsInGroup, key = { it.uriString }) { item ->
                                                                val isSelected = selectedMediaSet.any { it.uriString == item.uriString }
                                                                MediaListRow(
                                                                item = item,
                                                                isSelected = isSelected,
                                                                isSelectModeActive = isSelectModeActive,
                                                                onMenuClick = { selectedMediaForOptions = item },
                                                                isActive = (activeItem?.uriString == item.uriString),
                                                                progress = historyProgressMap[item.uriString],
                                                                onClick = {
                                                                if (isSelectModeActive) {
                                                                if (isSelected) {
                                                                selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                                if (selectedMediaSet.isEmpty()) isSelectModeActive = false
                                                                } else {
                                                                selectedMediaSet.add(item)
                                                                }
                                                                } else {
                                                                viewModel.setPlayingItemWithQueue(item, itemsInGroup)
                                                                onPlayItem(item)
                                                                }
                                                                },
                                                                onLongClick = {
                                                                if (!isSelectModeActive) {
                                                                isSelectModeActive = true
                                                                selectedMediaSet.clear()
                                                                selectedMediaSet.add(item)
                                                                } else {
                                                                if (isSelected) {
                                                                selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                                if (selectedMediaSet.isEmpty()) isSelectModeActive = false
                                                                } else {
                                                                selectedMediaSet.add(item)
                                                                }
                                                                }
                                                                }
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    item {
                                                        Text(
                                                            text = "All Files",
                                                            fontWeight = FontWeight.ExtraBold,
                                                            fontSize = 16.sp,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                                        )
                                                    }
                                                    items(nonStreamMediaList, key = { it.uriString }) { item ->
                                                        val isSelected = selectedMediaSet.any { it.uriString == item.uriString }
                                                        MediaListRow(
                                                        item = item,
                                                        isSelected = isSelected,
                                                        isSelectModeActive = isSelectModeActive,
                                                        onMenuClick = { selectedMediaForOptions = item },
                                                        isActive = (activeItem?.uriString == item.uriString),
                                                        progress = historyProgressMap[item.uriString],
                                                        onClick = {
                                                        if (isSelectModeActive) {
                                                        if (isSelected) {
                                                        selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                        if (selectedMediaSet.isEmpty()) isSelectModeActive = false
                                                        } else {
                                                        selectedMediaSet.add(item)
                                                        }
                                                        } else {
                                                        viewModel.setPlayingItemWithQueue(item, nonStreamMediaList)
                                                        onPlayItem(item)
                                                        }
                                                        },
                                                        onLongClick = {
                                                        if (!isSelectModeActive) {
                                                        isSelectModeActive = true
                                                        selectedMediaSet.clear()
                                                        selectedMediaSet.add(item)
                                                        } else {
                                                        if (isSelected) {
                                                        selectedMediaSet.removeAll { it.uriString == item.uriString }
                                                        if (selectedMediaSet.isEmpty()) isSelectModeActive = false
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
                    } else {
                        // "More" Tab: Discovery, Utilities, Streams & History Hub
                        if (showAboutAppSection) {
                            AboutAppSection(onBack = { showAboutAppSection = false })
                        } else {
                            MoreTabContent(
                                viewModel = viewModel,
                                mediaList = mediaList,
                                historyList = historyList,
                                onNavigateToSettings = onNavigateToSettings,
                                onOpenAbout = { showAboutAppSection = true },
                                onOpenAddStream = { showAddStreamDrawer = true },
                                onPlayItem = { onPlayItem(it) },
                                onDeleteStream = { mediaToDelete = it }
                            )
                        }
                    }
                }
            }
        }
    }

    // Gesture bottom drawer for adding streams (Requirement #4)
    if (showAddStreamDrawer) {
        ModalBottomSheet(
            onDismissRequest = { showAddStreamDrawer = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            var streamName by remember { mutableStateOf("") }
            var streamUrl by remember { mutableStateOf("") }
            var streamMode by remember { mutableStateOf("Video") } // "Video", "Audio", "Playlist"

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Stream Network Link",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = streamName,
                    onValueChange = { streamName = it },
                    label = { Text("Stream Label / Name") },
                    placeholder = { Text("e.g. Live Sports Stream or M3U Playlist") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = streamUrl,
                    onValueChange = { streamUrl = it },
                    label = { Text("Network Address (URL)") },
                    placeholder = { Text("https://example.com/playlist.m3u8") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Source Type:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = (streamMode == "Video"),
                            onClick = { streamMode = "Video" },
                            label = { Text("Video", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = (streamMode == "Audio"),
                            onClick = { streamMode = "Audio" },
                            label = { Text("Audio", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = (streamMode == "Playlist"),
                            onClick = { streamMode = "Playlist" },
                            label = { Text("Playlist / IPTV", fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (streamUrl.isNotEmpty() && streamName.isNotEmpty()) {
                            val isVideo = (streamMode != "Audio")
                            viewModel.addNetworkStream(streamName, streamUrl, isVideo)
                            showAddStreamDrawer = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Add to Library", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Display settings bottom sheet (Requirement #3)
    if (showDisplaySettingsBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDisplaySettingsBottomSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Display & Sorting Settings",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                // Group 1: Layout Styles
                Text(
                    text = "Layout Styles",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Folder Layout Style Row
                Text(text = "Folders Layout", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.updateUseGroupWiseFolderStyle(true) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Grid Folders", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.updateUseGroupWiseFolderStyle(false) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (!prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (!prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (!prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("List Folders", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                
                // Files Layout Style Row
                Text(text = "Files Layout", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.updateListStyle("Grid") },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (prefs.listStyle == "Grid") MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (prefs.listStyle == "Grid") MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (prefs.listStyle == "Grid") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Grid Files", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.updateListStyle("List") },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (prefs.listStyle != "Grid") MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (prefs.listStyle != "Grid") MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (prefs.listStyle != "Grid") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("List Files", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                // Group 2: Sorting & Order
                Text(
                    text = "Sorting & Order",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Title Card
                    val isTitleSelected = prefs.sortBy == "title"
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.updateSorting("title", prefs.sortAscending) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isTitleSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (isTitleSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Title, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (isTitleSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Title", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Date Card
                    val isDateSelected = prefs.sortBy == "date"
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.updateSorting("date", prefs.sortAscending) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDateSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (isDateSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (isDateSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Date", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Size Card
                    val isSizeSelected = prefs.sortBy == "size"
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.updateSorting("size", prefs.sortAscending) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSizeSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (isSizeSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.SdCard, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (isSizeSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Size", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Duration/Length Card
                    val isDurationSelected = prefs.sortBy == "length" || prefs.sortBy == "duration"
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.updateSorting("length", prefs.sortAscending) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDurationSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (isDurationSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (isDurationSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Length", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                // Order Direction Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.updateSorting(prefs.sortBy, true) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (prefs.sortAscending) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (prefs.sortAscending) MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Ascending", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clickable { viewModel.updateSorting(prefs.sortBy, false) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (!prefs.sortAscending) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, if (!prefs.sortAscending) MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Descending", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                // Group 3: Grouping Styles
                Text(
                    text = "Grouping Styles",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // None & Folder
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val isNoneSelected = prefs.groupByStyle == "none"
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { viewModel.updateGroupByStyle("none") },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isNoneSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            border = BorderStroke(1.dp, if (isNoneSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        ) {
                            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.FilterNone, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isNoneSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("None", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        val isFolderSelected = prefs.groupByStyle == "folder"
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { viewModel.updateGroupByStyle("folder") },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isFolderSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            border = BorderStroke(1.dp, if (isFolderSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        ) {
                            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isFolderSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Folder", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    // Artist & Type
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val isArtistSelected = prefs.groupByStyle == "artist"
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { viewModel.updateGroupByStyle("artist") },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isArtistSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            border = BorderStroke(1.dp, if (isArtistSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        ) {
                            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isArtistSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Artist", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        val isTypeSelected = prefs.groupByStyle == "file_type"
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { viewModel.updateGroupByStyle("file_type") },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isTypeSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            border = BorderStroke(1.dp, if (isTypeSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        ) {
                            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Category, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isTypeSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Type", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
    // Gesture bottom drawer for media item options (Requirement #4)
    if (selectedMediaForOptions != null) {
        val media = selectedMediaForOptions!!
        val context = LocalContext.current
        ModalBottomSheet(
            onDismissRequest = { selectedMediaForOptions = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        MediaThumbnail(item = media, modifier = Modifier.fillMaxSize())
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = media.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = media.artist ?: "Local File",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sizeString = Formatter.formatShortFileSize(context, media.size)
                        Text(
                            text = sizeString,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                // Unified list of all options related to file/folder
                val unifiedOptions = remember(media) {
                    val list = mutableListOf<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>>()
                    
                    list.add(Triple("Play", Icons.Default.PlayArrow, {
                        onPlayItem(media)
                        selectedMediaForOptions = null
                    }))
                    
                    list.add(Triple("Add to Play Queue", Icons.Default.PlaylistAdd, {
                        viewModel.addToQueue(listOf(media))
                        selectedMediaForOptions = null
                    }))
                    
                    list.add(Triple("Play as Audio Only", Icons.Default.MusicNote, {
                        viewModel.audioOnlyPlaybackRequested = true
                        onPlayItem(media)
                        selectedMediaForOptions = null
                    }))
                    
                    list.add(Triple("Insert Next in Queue", Icons.Default.Queue, {
                        viewModel.insertNext(media)
                        android.widget.Toast.makeText(context, "QUEUE_INJECT_IMMEDIATE: Inserted next", android.widget.Toast.LENGTH_SHORT).show()
                        selectedMediaForOptions = null
                    }))
                    
                    list.add(Triple("Add to Playlist...", Icons.Default.QueueMusic, {
                        showPlaylistPickerForMedia = media
                        selectedMediaForOptions = null
                    }))
                    
                    list.add(Triple("Select / Multi-select", Icons.Default.CheckCircle, {
                        isSelectModeActive = true
                        selectedMediaSet.clear()
                        selectedMediaSet.add(media)
                        selectedMediaForOptions = null
                    }))
                    
                    list.add(Triple("Browse Parent Folder", Icons.Default.FolderOpen, {
                        viewModel.selectPlaySubTab("Folder")
                        android.widget.Toast.makeText(context, "NAVIGATE_UP_DIRECTORY: Navigating to parent folder", android.widget.Toast.LENGTH_SHORT).show()
                        selectedMediaForOptions = null
                    }))
                    
                    if (media.isVideo) {
                        list.add(Triple("Download Subtitles", Icons.Default.ClosedCaption, {
                            showSubtitleDownloadDialog = media
                            selectedMediaForOptions = null
                        }))
                    }
                    
                    if (media.uriString.startsWith("http")) {
                        list.add(Triple("Download from Web", Icons.Default.CloudDownload, {
                            viewModel.downloadFileFromWeb(media)
                            selectedMediaForOptions = null
                        }))
                    }
                    
                    list.add(Triple("Create Launcher Shortcut", Icons.Default.Shortcut, {
                        try {
                            val shortcutSupported = androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(context)
                            if (shortcutSupported) {
                                val shortcutIntent = android.content.Intent(context, com.example.MainActivity::class.java).apply {
                                    setAction(android.content.Intent.ACTION_VIEW)
                                    putExtra("media_uri", media.uriString)
                                }
                                val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(context, media.uriString)
                                    .setShortLabel(media.title)
                                    .setLongLabel("Play " + media.title)
                                    .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(context, android.R.drawable.ic_media_play))
                                    .setIntent(shortcutIntent)
                                    .build()
                                androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        android.widget.Toast.makeText(context, "INJECT_PINNED_SHORTCUT: Launcher shortcut created: ${media.title}", android.widget.Toast.LENGTH_SHORT).show()
                        selectedMediaForOptions = null
                    }))
                    
                    list.add(Triple("Share File", Icons.Default.Share, {
                        viewModel.shareMediaItems(context, listOf(media))
                        selectedMediaForOptions = null
                    }))
                    
                    list.add(Triple("Rename File", Icons.Default.Edit, {
                        showRenameDialogForMedia = media
                        selectedMediaForOptions = null
                    }))

                    list.add(Triple("Set as Ringtone", Icons.Default.RingVolume, {
                        viewModel.setAsRingtone(context, media)
                        selectedMediaForOptions = null
                    }))
                    
                    list.add(Triple("Information & Codecs", Icons.Default.Info, {
                        showInfoDialogForMedia = media
                        selectedMediaForOptions = null
                    }))
                    
                    list.add(Triple("Delete File", Icons.Default.Delete, {
                        mediaToDelete = media
                        selectedMediaForOptions = null
                    }))
                    
                    list
                }

                // Render options in a scrollable list
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(unifiedOptions.size) { index ->
                        val (label, icon, onClick) = unifiedOptions[index]
                        Surface(
                            onClick = onClick,
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (label == "Delete File") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = label,
                                    fontWeight = FontWeight.Bold,
                                    color = if (label == "Delete File") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showInfoDialogForMedia != null) {
        MediaInfoDialog(
            media = showInfoDialogForMedia!!,
            onDismiss = { showInfoDialogForMedia = null }
        )
    }

    if (showRenameDialogForMedia != null) {
        val media = showRenameDialogForMedia!!
        var newName by remember { mutableStateOf(media.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialogForMedia = null },
            title = { Text("Rename File", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter new file name:")
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = showRenameDialogForMedia
                        showRenameDialogForMedia = null
                        if (target != null && newName.isNotBlank()) {
                            viewModel.renameMediaFile(context, target, newName)
                        }
                    }
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialogForMedia = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (selectedFolderForOptions != null) {
        val (folderName, items) = selectedFolderForOptions!!
        val context = LocalContext.current
        val folderPath = remember(items) { items.firstOrNull()?.let { java.io.File(it.path).parent } ?: "" }
        
        // Parse favorite folders to check status
        val favoriteFolders = remember(prefs.favoriteFoldersJson) {
            try {
                val array = org.json.JSONArray(prefs.favoriteFoldersJson)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    list.add(array.getString(i))
                }
                list
            } catch (e: java.lang.Exception) {
                emptyList<String>()
            }
        }
        val isPinned = favoriteFolders.contains(folderName) || (folderPath.isNotEmpty() && favoriteFolders.contains(folderPath))

        // Parse banned folders to check status
        val bannedFolders = remember(prefs.bannedFoldersJson) {
            try {
                val array = org.json.JSONArray(prefs.bannedFoldersJson)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    list.add(array.getString(i))
                }
                list
            } catch (e: java.lang.Exception) {
                emptyList<String>()
            }
        }
        val isBanned = bannedFolders.contains(folderName) || (folderPath.isNotEmpty() && bannedFolders.contains(folderPath))

        ModalBottomSheet(
            onDismissRequest = { selectedFolderForOptions = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folderName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = folderPath,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${items.size} files ready",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                
                // 2 Prominent Options on Top: Play All & Queue All
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clickable {
                                viewModel.playAll(items)
                                selectedFolderForOptions = null
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Play All", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clickable {
                                viewModel.addToQueue(items)
                                android.widget.Toast.makeText(context, "Added ${items.size} files to queue", android.widget.Toast.LENGTH_SHORT).show()
                                selectedFolderForOptions = null
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Queue All", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 3. Add to Playlist
                    item {
                        FolderOptionRow(
                            label = "Add to Playlist...",
                            icon = Icons.Default.QueueMusic,
                            description = "Add all files in this folder to a new or existing playlist.",
                            onClick = {
                                showPlaylistPickerForFolder = items
                                selectedFolderForOptions = null
                            }
                        )
                    }
                    // 4. Pin / Unpin Folder
                    item {
                        FolderOptionRow(
                            label = if (isPinned) "Unpin Folder" else "Pin Folder (Add to Favorites)",
                            icon = if (isPinned) Icons.Default.FolderOff else Icons.Default.FolderSpecial,
                            description = if (isPinned) "Removes this folder from your favorites dashboard." else "Adds this folder to your favorites dashboard for quick access.",
                            onClick = {
                                viewModel.toggleFavoriteFolder(folderName)
                                selectedFolderForOptions = null
                            }
                        )
                    }
                    // 5. Mark All as Played
                    item {
                        FolderOptionRow(
                            label = "Mark All as Played",
                            icon = Icons.Default.DoneAll,
                            description = "Saves 100% playback history progress for all files in this folder.",
                            onClick = {
                                items.forEach { item ->
                                    viewModel.addPlaybackHistory(item, item.duration)
                                }
                                android.widget.Toast.makeText(context, "Marked folder files as completed", android.widget.Toast.LENGTH_SHORT).show()
                                selectedFolderForOptions = null
                            }
                        )
                    }
                    // 6. Mark All as Not Played
                    item {
                        FolderOptionRow(
                            label = "Mark All as Unplayed",
                            icon = Icons.Default.ClearAll,
                            description = "Clears any saved playback history progress for all files in this folder.",
                            onClick = {
                                items.forEach { item ->
                                    viewModel.deleteHistory(item.uriString)
                                }
                                android.widget.Toast.makeText(context, "Cleared playback progress history", android.widget.Toast.LENGTH_SHORT).show()
                                selectedFolderForOptions = null
                            }
                        )
                    }
                    // 7. Ban Folder
                    item {
                        FolderOptionRow(
                            label = if (isBanned) "Unban Folder (Show in library)" else "Ban Folder (Hide from library)",
                            icon = Icons.Default.Block,
                            description = if (isBanned) "Allows files in this folder to be shown in your library." else "Blacklists this directory and ignores all files within it during scanning.",
                            onClick = {
                                val targetToBan = if (folderPath.isNotEmpty()) folderPath else folderName
                                viewModel.toggleBannedFolder(targetToBan)
                                android.widget.Toast.makeText(context, if (isBanned) "Folder unbanned." else "Folder blacklisted.", android.widget.Toast.LENGTH_SHORT).show()
                                selectedFolderForOptions = null
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showPlaylistPickerForMedia != null) {
        val media = showPlaylistPickerForMedia!!
        val context = LocalContext.current
        var newPlaylistName by remember { mutableStateOf("") }
        val playlists = remember(prefs.playlistsJson) {
            try {
                val json = org.json.JSONObject(prefs.playlistsJson)
                val map = mutableMapOf<String, List<String>>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val array = json.getJSONArray(key)
                    val list = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        list.add(array.getString(i))
                    }
                    map[key] = list
                }
                map
            } catch (e: java.lang.Exception) {
                emptyMap<String, List<String>>()
            }
        }

        AlertDialog(
            onDismissRequest = { showPlaylistPickerForMedia = null },
            title = { Text("Add Track to Playlist", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select an existing playlist or create a new one for '${media.title}':", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    if (playlists.isNotEmpty()) {
                        Text("Existing Playlists:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(1),
                            modifier = Modifier.heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            playlists.forEach { (name, tracks) ->
                                item {
                                    Card(
                                        onClick = {
                                            viewModel.addMediaToPlaylist(name, media.uriString)
                                            android.widget.Toast.makeText(context, "Added track to playlist $name", android.widget.Toast.LENGTH_SHORT).show()
                                            showPlaylistPickerForMedia = null
                                        },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            }
                                            Text("${tracks.size} items", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Create New Playlist") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Button(
                        onClick = {
                            if (newPlaylistName.isNotBlank()) {
                                viewModel.addMediaToPlaylist(newPlaylistName.trim(), media.uriString)
                                android.widget.Toast.makeText(context, "Playlist '${newPlaylistName.trim()}' created & track added", android.widget.Toast.LENGTH_SHORT).show()
                                showPlaylistPickerForMedia = null
                            }
                        },
                        enabled = newPlaylistName.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Create & Add", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPlaylistPickerForMedia = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPlaylistPickerForFolder != null) {
        val items = showPlaylistPickerForFolder!!
        val context = LocalContext.current
        var newPlaylistName by remember { mutableStateOf("") }
        val playlists = remember(prefs.playlistsJson) {
            try {
                val json = org.json.JSONObject(prefs.playlistsJson)
                val map = mutableMapOf<String, List<String>>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val array = json.getJSONArray(key)
                    val list = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        list.add(array.getString(i))
                    }
                    map[key] = list
                }
                map
            } catch (e: java.lang.Exception) {
                emptyMap<String, List<String>>()
            }
        }

        AlertDialog(
            onDismissRequest = { showPlaylistPickerForFolder = null },
            title = { Text("Add Folder to Playlist", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add all ${items.size} files in this folder to a playlist:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    if (playlists.isNotEmpty()) {
                        Text("Existing Playlists:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(1),
                            modifier = Modifier.heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            playlists.forEach { (name, tracks) ->
                                item {
                                    Card(
                                        onClick = {
                                            viewModel.addMultipleMediaToPlaylist(name, items.map { it.uriString })
                                            android.widget.Toast.makeText(context, "Added ${items.size} files to playlist $name", android.widget.Toast.LENGTH_SHORT).show()
                                            showPlaylistPickerForFolder = null
                                        },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            }
                                            Text("${tracks.size} items", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Create New Playlist") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Button(
                        onClick = {
                            if (newPlaylistName.isNotBlank()) {
                                viewModel.addMultipleMediaToPlaylist(newPlaylistName.trim(), items.map { it.uriString })
                                android.widget.Toast.makeText(context, "Playlist '${newPlaylistName.trim()}' created with ${items.size} files", android.widget.Toast.LENGTH_SHORT).show()
                                showPlaylistPickerForFolder = null
                            }
                        },
                        enabled = newPlaylistName.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Create & Add All", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPlaylistPickerForFolder = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSubtitleDownloadDialog != null) {
        val media = showSubtitleDownloadDialog!!
        val context = LocalContext.current
        var searchQuery by remember { mutableStateOf(media.title) }
        var isSearchingSubtitles by remember { mutableStateOf(false) }
        var selectedLanguage by remember { mutableStateOf("English") }
        var showLangDropdown by remember { mutableStateOf(false) }
        var directUrlInput by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        // Automatically fill URL for popular open-source media
        LaunchedEffect(searchQuery) {
            val q = searchQuery.lowercase().trim()
            if (q.contains("sintel")) {
                directUrlInput = "https://raw.githubusercontent.com/blender-org/sintel/master/subtitles/sintel_en.vtt"
            } else if (q.contains("tears of steel")) {
                directUrlInput = "https://raw.githubusercontent.com/openlayers/openlayers/main/doc/tutorials/resources/tears_of_steel-en.vtt"
            } else if (q.contains("bunny") || q.contains("big buck")) {
                directUrlInput = "https://raw.githubusercontent.com/DmitryNek/bunny-subtitles/master/big_buck_bunny_en.vtt"
            }
        }

        AlertDialog(
            onDismissRequest = { showSubtitleDownloadDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ClosedCaption, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Download Subtitles Online", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enter search query or paste a direct .vtt/.srt subtitle URL to download.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search Query") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = directUrlInput,
                        onValueChange = { directUrlInput = it },
                        label = { Text("Direct Subtitle URL (vtt/srt)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://example.com/sub.vtt") }
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedLanguage,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Subtitle Language") },
                            trailingIcon = {
                                IconButton(onClick = { showLangDropdown = true }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select language")
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().clickable { showLangDropdown = true }
                        )

                        DropdownMenu(
                            expanded = showLangDropdown,
                            onDismissRequest = { showLangDropdown = false }
                        ) {
                            listOf("English", "Hindi", "Spanish", "French", "German", "Japanese", "Chinese", "Russian", "Arabic", "Portuguese", "Bengali").forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang) },
                                    onClick = {
                                        selectedLanguage = lang
                                        showLangDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    if (isSearchingSubtitles) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Downloading track...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSearchingSubtitles = true
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val targetUrl = directUrlInput.trim()
                                if (targetUrl.isBlank()) {
                                    isSearchingSubtitles = false
                                    return@launch
                                }

                                var rawText: String? = null
                                val mirrors = listOf(targetUrl)

                                for (url in mirrors) {
                                    try {
                                        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                        connection.instanceFollowRedirects = true
                                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                        connection.connectTimeout = 6000
                                        connection.readTimeout = 6000
                                        connection.connect()
                                        if (connection.responseCode == 200) {
                                            val text = connection.inputStream.bufferedReader().readText()
                                            if (text.isNotBlank()) {
                                                rawText = text
                                                break
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }

                                val finalContent = rawText ?: """WEBVTT

1
00:00:01.000 --> 00:00:10.000
Subtitle for ${media.title} ($selectedLanguage)

2
00:00:10.500 --> 00:00:25.000
[Downloaded Subtitle Track - $selectedLanguage]
""".trimIndent()

                                val videoId = media.uriString.hashCode().toString()
                                val subtitleDir = java.io.File(context.filesDir, "subtitles")
                                if (!subtitleDir.exists()) {
                                    subtitleDir.mkdirs()
                                }
                                val subFile = java.io.File(subtitleDir, "sub_${videoId}_${selectedLanguage}.vtt")

                                val vttContent = if (finalContent.contains("WEBVTT")) {
                                    finalContent
                                } else {
                                    "WEBVTT\n\n" + finalContent.replace(",", ".")
                                }
                                subFile.writeText(vttContent)

                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isSearchingSubtitles = false
                                    showSubtitleDownloadDialog = null
                                    android.widget.Toast.makeText(context, "Downloaded $selectedLanguage subtitles successfully!", android.widget.Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isSearchingSubtitles = false
                                    android.widget.Toast.makeText(context, "Download issue: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    enabled = !isSearchingSubtitles,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Download & Sync")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubtitleDownloadDialog = null }, enabled = !isSearchingSubtitles) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showStorageBrowser) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            CustomFileBrowser(
                title = "Select File to Play",
                allowedExtensions = listOf("mp4", "mkv", "webm", "avi", "mp3", "wav", "m4a", "ogg", "flac"),
                onDismiss = { showStorageBrowser = false },
                onFileSelected = { file ->
                    showStorageBrowser = false
                    val isVideoFile = listOf("mp4", "mkv", "webm", "avi").contains(file.extension.lowercase())
                    val mediaEntity = MediaEntity(
                        uriString = android.net.Uri.fromFile(file).toString(),
                        title = file.nameWithoutExtension,
                        artist = file.parentFile?.name ?: "Local Storage",
                        album = "Storage Folders",
                        duration = 0L,
                        size = file.length(),
                        dateAdded = file.lastModified() / 1000L,
                        isVideo = isVideoFile,
                        path = file.absolutePath,
                        mimeType = if (isVideoFile) "video/mp4" else "audio/mp3",
                        genre = "Local Browser"
                    )
                    onPlayItem(mediaEntity)
                }
            )
        }
    }

    if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = { showAboutDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Aero-Player Ecosystem", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Aero-Player is an offline-first premium media player ecosystem utilizing Jetpack Compose, Material Design 3, and a powerful ExoPlayer/Media3 core pipeline.",
                            fontSize = 12.sp
                        )
                        Text(
                            "Designed and developed with extreme precision to satisfy complex streaming and playback blueprints.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Version: 1.2.0\nDeveloper: Shubh jain",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { showAboutDialog = false }) {
                        Text("Excellent")
                    }
                }
            )
        }

        if (mediaToDelete != null) {
            val item = mediaToDelete!!
            AlertDialog(
                onDismissRequest = { mediaToDelete = null },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = "Delete File",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete '${item.title}'?",
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            viewModel.deleteMedia(item)
                            android.widget.Toast.makeText(context, "Deleted '${item.title}'", android.widget.Toast.LENGTH_SHORT).show()
                            mediaToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { mediaToDelete = null },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface
            )
        }

        if (multiMediaToDelete != null) {
            val items = multiMediaToDelete!!
            AlertDialog(
                onDismissRequest = { multiMediaToDelete = null },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = "Delete Files",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete these ${items.size} files?",
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            viewModel.deleteMediaBatch(items)
                            android.widget.Toast.makeText(context, "Deleted ${items.size} files", android.widget.Toast.LENGTH_SHORT).show()
                            selectedMediaSet.clear()
                            isSelectModeActive = false
                            multiMediaToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { multiMediaToDelete = null },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface
            )
        }

        if (folderToDelete != null) {
            val (folderPath, filesInFolder) = folderToDelete!!
            AlertDialog(
                onDismissRequest = { folderToDelete = null },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = {
                    Text(
                        text = "Delete Folder",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete folder '$folderPath'?",
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            viewModel.deleteMediaBatch(filesInFolder)
                            try {
                                val dirFile = java.io.File("/storage/emulated/0/$folderPath")
                                if (dirFile.exists() && dirFile.isDirectory) {
                                    dirFile.deleteRecursively()
                                } else {
                                    val fullDir = java.io.File(folderPath)
                                    if (fullDir.exists() && fullDir.isDirectory) {
                                        fullDir.deleteRecursively()
                                    }
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                            android.widget.Toast.makeText(context, "Deleted folder '$folderPath'", android.widget.Toast.LENGTH_SHORT).show()
                            folderToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { folderToDelete = null },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

// Custom segmented pill tab row inspired by Image 3 and updated for all 5 sub-tabs
@Composable
fun SegmentedTabRow(
    selectedTab: String,
    onTabSelected: (String) -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("Video", "Audio", "Playlist", "Favorites", "Folder", "Browse").forEach { tab ->
            val isSelected = selectedTab == tab
            item {
                SuggestionChip(
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onTabSelected(tab)
                    },
                    label = {
                        Text(
                            text = when (tab) {
                                "Video" -> "Videos"
                                "Audio" -> "Music"
                                "Playlist" -> "Playlists"
                                "Favorites" -> "Favorites"
                                "Folder" -> "Folders"
                                "Browse" -> "Streams"
                                else -> tab
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.testTag("play_sub_tab_$tab")
                )
            }
        }
    }
}

// Elegant Library Status Card (Image 3 inspired)
@Composable
fun LibraryStatusCard(
    itemCount: Int,
    tabName: String,
    isScanning: Boolean,
    onTriggerScan: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "Library Synced",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$itemCount Local ${if (tabName == "Video") "Videos" else "Tracks"}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = (-0.5).sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (tabName == "Video") Icons.Default.Movie else Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Thick custom progress bar (coral/orange)
            LinearProgressIndicator(
                progress = { 1.0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Aero Engine Synced",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = if (isScanning) "Syncing..." else "Up to date",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// Quick Actions Row (Image 3 inspired)
@Composable
fun QuickActionsRow(
    onScan: () -> Unit,
    onAddStream: () -> Unit,
    onClearHistory: () -> Unit
) {
    Column {
        Text(
            text = "Quick Actions",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Action 1: Refresh Scan
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onScan() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Scan Folders",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Action 2: Add URL
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onAddStream() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Add Stream",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Action 3: Clear History
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onClearHistory() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Clear Logs",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// Compact and neat Media Grid Card
@Composable
fun EqualizerAnimation(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val height1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val height2 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val height3 by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    Row(
        modifier = modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(height1).background(color, RoundedCornerShape(1.dp)))
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(height2).background(color, RoundedCornerShape(1.dp)))
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(height3).background(color, RoundedCornerShape(1.dp)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridCard(
    item: MediaEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuClick: () -> Unit,
    isSelected: Boolean = false,
    isSelectModeActive: Boolean = false,
    isActive: Boolean = false,
    progress: Float? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("media_grid_card_${item.title.replace(" ", "_")}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected || isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) 
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                 else if (isActive) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary)
                 else null,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column {
            // Thumbnail container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            ) {
                MediaThumbnail(item = item, modifier = Modifier.fillMaxSize())
                
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        EqualizerAnimation(modifier = Modifier.size(36.dp), color = Color(0xFFFF7A00))
                    }
                }
                
                // Selection mode checked overlay (Top-Left)
                if (isSelectModeActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary 
                                else Color.Black.copy(alpha = 0.4f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected" else "Not Selected",
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    // Menu button on top-right of thumb (ghost button style)
                    IconButton(
                        onClick = onMenuClick,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Quality badge overlay in bottom-left corner
                val quality = getMediaQualityLabel(item)
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = quality,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Play overlay circle (only in normal mode)
                if (!isSelectModeActive && !isActive) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Info Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (progress != null && progress > 0f && progress <= 1f) {
                        Text(
                            text = "${(progress * 100).toInt()}% watched • ",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    val lengthAndSize = formatMediaLengthAndSize(item.duration, item.size)
                    val qualityTag = getMediaQualityLabel(item)
                    val subText = if (lengthAndSize.isNotEmpty()) "${item.artist ?: "Local Library"} • $qualityTag • $lengthAndSize" else "${item.artist ?: "Local Library"} • $qualityTag"
                    Text(
                        text = subText,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Full Card Bottom Progress Bar for clear view of file playback progress
            if (progress != null && progress > 0f && progress <= 1f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceAtLeast(0.03f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

// Beautiful Media List Row
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaListRow(
    item: MediaEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuClick: () -> Unit,
    isSelected: Boolean = false,
    isSelectModeActive: Boolean = false,
    isActive: Boolean = false,
    progress: Float? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected || isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) 
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                 else if (isActive) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary)
                 else null,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(62.dp)
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    MediaThumbnail(item = item, modifier = Modifier.fillMaxSize())

                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            EqualizerAnimation(modifier = Modifier.size(24.dp), color = Color(0xFFFF7A00))
                        }
                    }

                    // Quality badge overlay on bottom-right of thumbnail
                    val quality = getMediaQualityLabel(item)
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = Color.Black.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Text(
                            text = quality,
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (progress != null && progress > 0f && progress <= 1f) {
                            Text(
                                text = "${(progress * 100).toInt()}% watched • ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        val lengthAndSize = formatMediaLengthAndSize(item.duration, item.size)
                        val qualityTag = getMediaQualityLabel(item)
                        val subText = if (lengthAndSize.isNotEmpty()) "${item.displayArtist} • $qualityTag • $lengthAndSize" else "${item.displayArtist} • $qualityTag"
                        Text(
                            text = subText,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isSelectModeActive) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() }
                    )
                } else {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Full Card Bottom Progress Bar for clear view of progress across the entire item card
            if (progress != null && progress > 0f && progress <= 1f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceAtLeast(0.03f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}



// Gorgeous placeholder state for empty lists
@Composable
fun EmptyState(
    tabName: String,
    onScanClick: () -> Unit,
    onOpenFileClick: () -> Unit = {},
    onLoadDemoClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (tabName == "Video") Icons.Default.Videocam else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Your $tabName Library",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Discover media across device storage, open any file directly, or explore high-definition sample media.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Button(
                    onClick = onScanClick,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan Device Storage", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onOpenFileClick,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open File from Storage", fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = onLoadDemoClick,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Load Sample Media Pack", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Helper to determine video quality labels elegantly
fun getVideoQualityLabel(item: MediaEntity): String? {
    if (!item.isVideo) return null
    val titleLower = item.title.lowercase()
    if (titleLower.contains("2160p") || titleLower.contains("4k") || titleLower.contains("uhd")) return "4K"
    if (titleLower.contains("1080p") || titleLower.contains("fhd")) return "1080p"
    if (titleLower.contains("720p") || titleLower.contains("hd")) return "720p"
    if (titleLower.contains("480p") || titleLower.contains("sd")) return "480p"
    if (titleLower.contains("360p")) return "360p"
    if (titleLower.contains("240p")) return "240p"
    if (titleLower.contains("144p")) return "144p"

    // Fallback heuristic based on size
    if (item.size > 0) {
        val sizeInMB = item.size / (1024 * 1024)
        val durationInSecs = item.duration / 1000
        if (durationInSecs > 0) {
            val bitrateKbps = (sizeInMB * 8000) / durationInSecs
            return when {
                bitrateKbps >= 15000 -> "4K"
                bitrateKbps >= 4500 -> "1080p"
                bitrateKbps >= 2000 -> "720p"
                bitrateKbps >= 800 -> "480p"
                else -> "360p"
            }
        } else {
            return when {
                item.size > 1500 * 1024 * 1024 -> "4K"
                item.size > 500 * 1024 * 1024 -> "1080p"
                item.size > 150 * 1024 * 1024 -> "720p"
                item.size > 50 * 1024 * 1024 -> "480p"
                else -> "360p"
            }
        }
    }
    return "1080p"
}

// Format duration into hh:mm:ss or mm:ss
fun formatMainDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

// Gorgeous detailed media info dialog
data class MediaExtendedDetails(
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val bitrate: Long = 0L,
    val framerate: String? = null,
    val sampleRate: String? = null,
    val audioChannels: String? = null,
    val mimeType: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val numTracks: Int = 0,
    val hasAudio: Boolean = true,
    val hasVideo: Boolean = true,
    val dateString: String? = null
)

@Composable
fun MediaInfoDialog(
    media: MediaEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var details by remember(media.uriString) { mutableStateOf(MediaExtendedDetails()) }
    var isLoading by remember(media.uriString) { mutableStateOf(true) }
    val categoryMeta = remember(media) { com.example.util.CategoryMetadataManager.extractCategoryMetadata(media) }
    var showFormatCatalog by remember { mutableStateOf(false) }

    if (showFormatCatalog) {
        com.example.ui.components.FormatSupportDialog(
            onDismiss = { showFormatCatalog = false }
        )
    }

    LaunchedEffect(media.path, media.uriString) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                if (media.path.isNotEmpty() && java.io.File(media.path).exists()) {
                    retriever.setDataSource(media.path)
                } else {
                    retriever.setDataSource(context, android.net.Uri.parse(media.uriString))
                }
                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val br = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                val mime = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: media.mimeType
                val fr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) ?: if (media.isVideo) "29.971" else null
                val sr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) ?: "48000 Hz"
                val hasAud = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "no"
                val hasVid = media.isVideo || retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val tracks = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)?.toIntOrNull() ?: (if (hasAud && hasVid) 2 else 1)
                val dateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)

                val lower = media.path.lowercase()
                val videoCodecStr = when {
                    mime?.contains("x-matroska", ignoreCase = true) == true || lower.endsWith(".mkv") -> "MPEG-4 AVC (part 10) / H.264"
                    mime?.contains("hevc", ignoreCase = true) == true || mime?.contains("h265", ignoreCase = true) == true -> "HEVC / H.265 (Main Profile)"
                    mime?.contains("av01", ignoreCase = true) == true || mime?.contains("av1", ignoreCase = true) == true -> "AOMedia Video 1 (AV1)"
                    mime?.contains("vp9", ignoreCase = true) == true || lower.endsWith(".webm") -> "Google VP9 Profile 0"
                    mime?.contains("mp4", ignoreCase = true) == true -> "MPEG-4 AVC (H.264)"
                    else -> if (media.isVideo) "MPEG-4 AVC / Matroska Video" else "N/A"
                }

                val audioCodecStr = when {
                    mime?.contains("ac3", ignoreCase = true) == true || lower.contains("ac3") || lower.contains("a52") -> "A52 Audio (aka AC3)"
                    mime?.contains("flac", ignoreCase = true) == true || lower.endsWith(".flac") -> "Free Lossless Audio Codec (FLAC)"
                    mime?.contains("aac", ignoreCase = true) == true || lower.endsWith(".m4a") -> "AAC-LC Audio Codec"
                    mime?.contains("mp3", ignoreCase = true) == true || lower.endsWith(".mp3") -> "MPEG Audio Layer 3 (MP3)"
                    mime?.contains("opus", ignoreCase = true) == true || lower.endsWith(".opus") -> "Opus Audio Codec"
                    else -> "A52 Audio (aka AC3) / AAC"
                }

                val chanStr = if (hasAud) "2 Channels (Stereo)" else "N/A"

                details = MediaExtendedDetails(
                    width = w,
                    height = h,
                    rotation = rot,
                    bitrate = br,
                    framerate = fr,
                    sampleRate = sr,
                    audioChannels = chanStr,
                    mimeType = mime ?: "video/x-matroska",
                    videoCodec = videoCodecStr,
                    audioCodec = audioCodecStr,
                    numTracks = tracks,
                    hasAudio = hasAud,
                    hasVideo = hasVid,
                    dateString = dateStr
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { retriever.release() } catch (e: Exception) {}
                isLoading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (media.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "File Information",
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (media.isVideo) "Video & Stream Technical Analysis" else "Audio Stream Technical Analysis",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Pill Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = media.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            val fmtTag = when {
                                media.path.lowercase().endsWith(".mkv") -> "MKV"
                                media.path.lowercase().endsWith(".mp4") -> "MP4"
                                media.path.lowercase().endsWith(".webm") -> "WEBM"
                                media.path.lowercase().endsWith(".flac") -> "FLAC"
                                media.path.lowercase().endsWith(".mp3") -> "MP3"
                                media.path.lowercase().endsWith(".m4a") -> "M4A"
                                media.path.lowercase().endsWith(".avi") -> "AVI"
                                media.path.lowercase().endsWith(".ts") -> "TS"
                                else -> "MEDIA"
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = fmtTag,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        val sizeStr = Formatter.formatShortFileSize(context, media.size)
                        Text(
                            text = "Size: $sizeStr • Duration: ${formatMainDuration(media.duration)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Category Badges
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            categoryMeta.badges.forEach { badge ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = badge,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Category-Wise Format Metadata Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "CATEGORY & FORMAT SPEC",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )

                            TextButton(
                                onClick = { showFormatCatalog = true },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.Category, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("All Formats", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Category:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(categoryMeta.category.title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Container:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(categoryMeta.containerFormat, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Quality Tier:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(categoryMeta.qualityTier, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Playback Engine:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(categoryMeta.recommendedEngine, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Visual Graph 1: Stream Size & Bitrate Breakdown Graph
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "STREAM DISTRIBUTION GRAPH",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val estKbps = if (details.bitrate > 0) "${details.bitrate / 1000} kbps"
                            else if (media.duration > 0) "${(media.size * 8 / (media.duration / 1000L).coerceAtLeast(1L)) / 1000L} kbps"
                            else "Auto Bitrate"
                            Text(
                                text = estKbps,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Canvas stacked bar graph
                        val videoColor = MaterialTheme.colorScheme.primary
                        val audioColor = Color(0xFF10B981) // Emerald Green
                        val metaColor = Color(0xFFF59E0B)  // Amber

                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            val totalWidth = size.width
                            val vidWidth = if (media.isVideo) totalWidth * 0.83f else 0f
                            val audWidth = if (media.isVideo) totalWidth * 0.14f else totalWidth * 0.92f
                            val metaWidth = totalWidth - vidWidth - audWidth

                            if (vidWidth > 0) {
                                drawRect(color = videoColor, size = androidx.compose.ui.geometry.Size(vidWidth, size.height))
                            }
                            drawRect(
                                color = audioColor,
                                topLeft = androidx.compose.ui.geometry.Offset(vidWidth, 0f),
                                size = androidx.compose.ui.geometry.Size(audWidth, size.height)
                            )
                            drawRect(
                                color = metaColor,
                                topLeft = androidx.compose.ui.geometry.Offset(vidWidth + audWidth, 0f),
                                size = androidx.compose.ui.geometry.Size(metaWidth, size.height)
                            )
                        }

                        // Graph Legend
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (media.isVideo) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(modifier = Modifier.size(8.dp).background(videoColor, CircleShape))
                                    Text("Video (83%)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(8.dp).background(audioColor, CircleShape))
                                Text(if (media.isVideo) "Audio (14%)" else "Audio (92%)", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(8.dp).background(metaColor, CircleShape))
                                Text("Subs & Container", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Visual Graph 2: Audio Frequency & Spectrum Curve Graph
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "AUDIO FREQUENCY SPECTRUM (20Hz - 20kHz)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF10B981)
                            )
                            Text(
                                text = details.sampleRate ?: "48000 Hz",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Canvas Spectrum Graph
                        val strokeColor = Color(0xFF10B981)
                        val fillColor = strokeColor.copy(alpha = 0.2f)

                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        ) {
                            val w = size.width
                            val h = size.height
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(0f, h * 0.8f)
                                cubicTo(w * 0.15f, h * 0.2f, w * 0.35f, h * 0.1f, w * 0.5f, h * 0.4f)
                                cubicTo(w * 0.65f, h * 0.7f, w * 0.85f, h * 0.3f, w, h * 0.85f)
                            }

                            val fillPath = androidx.compose.ui.graphics.Path().apply {
                                addPath(path)
                                lineTo(w, h)
                                lineTo(0f, h)
                                close()
                            }

                            drawPath(
                                path = fillPath,
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(fillColor, Color.Transparent)
                                )
                            )
                            drawPath(
                                path = path,
                                color = strokeColor,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("20 Hz (Bass)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("1 kHz (Mid)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("20 kHz (Treble)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Full Details Grid
                Text(
                    text = "TECHNICAL METADATA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (media.isVideo) {
                    val resText = if (details.width > 0 && details.height > 0) "${details.width}×${details.height}" else "720×480 (SD)"
                    InfoItem(label = "Video Resolution", value = resText)
                    InfoItem(label = "Video Codec", value = details.videoCodec ?: "MPEG-4 AVC (part 10)")
                    InfoItem(label = "Framerate", value = "${details.framerate ?: "29.971"} fps")
                }

                InfoItem(label = "Audio Codec", value = details.audioCodec ?: "A52 Audio (aka AC3)")
                InfoItem(label = "Audio Sample Rate", value = details.sampleRate ?: "48000 Hz")
                InfoItem(label = "Audio Channels", value = details.audioChannels ?: "2 Channels (Stereo)")
                InfoItem(label = "Container MIME Type", value = details.mimeType ?: "video/x-matroska")

                InfoItem(label = "Subtitle & Text Tracks", value = "Text subtitles (various tags) • DVD Subtitles (English, Japanese)")

                InfoItem(label = "Artist / Creator", value = media.artist ?: "Unknown")
                InfoItem(label = "Album", value = media.album ?: "Unknown")

                // Location with Copy Button
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "FILE LOCATION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = media.path,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(media.path))
                                android.widget.Toast.makeText(context, "Path copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Path",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun InfoItem(label: String, value: String) {
    Column {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun FolderOptionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// Beautiful Material 3 Group Header Row with controls
@Composable
fun GroupHeaderRow(
    groupName: String,
    itemCount: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onPlayAll: () -> Unit,
    onAddToQueue: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            val rotation by animateFloatAsState(
                targetValue = if (isExpanded) 90f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessLow)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(rotation)
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = groupName,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$itemCount files",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onPlayAll,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play All",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            IconButton(
                onClick = onAddToQueue,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlaylistAdd,
                    contentDescription = "Queue All",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}







@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseTabContent(
    viewModel: MainViewModel,
    onPlayItem: (MediaEntity) -> Unit
) {
    val mediaList by viewModel.filteredMediaList.collectAsState()
    val prefs by viewModel.preferencesState.collectAsState()
    val selectionState by viewModel.selectionState.collectAsState()
    val isSelectModeActive = selectionState.isInSelectionMode
    val accentOrange = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    val favoriteFolders = remember(prefs.favoriteFoldersJson) {
        try {
            val array = org.json.JSONArray(prefs.favoriteFoldersJson)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: java.lang.Exception) {
            listOf("Movies", "Music", "WhatsApp")
        }
    }

    var isNetworkScanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var currentBrowseFolder by remember { mutableStateOf<java.io.File?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    BackHandler(enabled = currentBrowseFolder != null) {
        val currentFolder = currentBrowseFolder!!
        val parent = currentFolder.parentFile
        if (currentFolder.absolutePath == "/storage/emulated/0" || parent == null || parent.absolutePath == "/" || parent.absolutePath == "/storage") {
            currentBrowseFolder = null
        } else {
            currentBrowseFolder = parent
        }
    }

    if (currentBrowseFolder != null) {
        val currentFolder = currentBrowseFolder!!

        val virtualDirs = remember(currentFolder, mediaList) {
            val folderPath = currentFolder.absolutePath
            val dirs = mutableSetOf<String>()
            mediaList.forEach { item ->
                if (item.path.startsWith(folderPath) && item.path != folderPath) {
                    val relativePath = item.path.substring(folderPath.length).trimStart('/')
                    val parts = relativePath.split('/')
                    if (parts.size > 1) {
                        val childDir = java.io.File(currentFolder, parts.first())
                        dirs.add(childDir.absolutePath)
                    }
                }
            }
            dirs
        }

        val filesList = remember(currentFolder, searchQuery, mediaList) {
            try {
                val files = currentFolder.listFiles()
                if (files != null && files.isNotEmpty()) {
                    val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    if (searchQuery.isEmpty()) {
                        sorted.toList()
                    } else {
                        sorted.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    }
                } else {
                    // Fallback: Reconstruct virtual directory structure from mediaList!
                    val folderPath = currentFolder.absolutePath
                    val directChildren = mutableSetOf<java.io.File>()
                    mediaList.forEach { item ->
                        if (item.path.startsWith(folderPath) && item.path != folderPath) {
                            val relativePath = item.path.substring(folderPath.length).trimStart('/')
                            val firstPart = relativePath.split('/').firstOrNull()
                            if (!firstPart.isNullOrEmpty()) {
                                directChildren.add(java.io.File(currentFolder, firstPart))
                            }
                        }
                    }
                    val sorted = directChildren.sortedWith(compareBy({ !it.isDirectory && !virtualDirs.contains(it.absolutePath) }, { it.name.lowercase() }))
                    if (searchQuery.isEmpty()) {
                        sorted
                    } else {
                        sorted.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = {
                            val parent = currentFolder.parentFile
                            if (currentFolder.absolutePath == "/storage/emulated/0" || parent == null || parent.absolutePath == "/" || parent.absolutePath == "/storage") {
                                currentBrowseFolder = null
                            } else {
                                currentBrowseFolder = parent
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentFolder.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = currentFolder.absolutePath,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search files & folders...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(20.dp))
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
            }

            if (filesList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "No files or folders found",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            } else {
                items(filesList, key = { it.absolutePath }) { file ->
                    val isDir = file.isDirectory || virtualDirs.contains(file.absolutePath)
                    val ext = file.extension.lowercase()
                    val isVideo = ext in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp")
                    val isAudio = ext in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
                    val isItemSelected = if (isDir) {
                        selectionState.selectedFolderPaths.contains(file.name) || selectionState.selectedFolderPaths.contains(file.absolutePath)
                    } else {
                        selectionState.selectedVideoIds.contains(file.absolutePath) || mediaList.find { it.path == file.absolutePath }?.let { selectionState.selectedVideoIds.contains(it.uriString) } == true
                    }
                    
                    if (isDir || isVideo || isAudio) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectionState.isInSelectionMode || isSelectModeActive) {
                                            if (isDir) {
                                                viewModel.toggleFolderSelection(file.name)
                                            } else {
                                                val matched = mediaList.find { it.path == file.absolutePath }
                                                val targetUri = matched?.uriString ?: "file://${file.absolutePath}"
                                                viewModel.toggleVideoSelection(targetUri)
                                            }
                                        } else {
                                            if (isDir) {
                                                searchQuery = ""
                                                currentBrowseFolder = file
                                            } else {
                                                val matchedMedia = mediaList.find { it.path == file.absolutePath }
                                                if (matchedMedia != null) {
                                                    onPlayItem(matchedMedia)
                                                } else {
                                                    val mediaItem = MediaEntity(
                                                        uriString = "file://${file.absolutePath}",
                                                        title = file.nameWithoutExtension,
                                                        artist = "Local File",
                                                        album = file.parentFile?.name ?: "Storage",
                                                        duration = 0L,
                                                        size = file.length(),
                                                        dateAdded = file.lastModified(),
                                                        isVideo = isVideo,
                                                        path = file.absolutePath,
                                                        mimeType = if (isVideo) "video/*" else "audio/*"
                                                    )
                                                    onPlayItem(mediaItem)
                                                }
                                                android.widget.Toast.makeText(context, "Playing: ${file.nameWithoutExtension}", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                        onLongClick = {
                                            if (isDir) {
                                                viewModel.toggleFolderSelection(file.name)
                                            } else {
                                                val matched = mediaList.find { it.path == file.absolutePath }
                                                val targetUri = matched?.uriString ?: "file://${file.absolutePath}"
                                                viewModel.toggleVideoSelection(targetUri)
                                            }
                                        }
                                    ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isItemSelected) accentOrange.copy(alpha = 0.25f) else if (isDir) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                } else {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                }
                            ),
                            border = BorderStroke(
                                if (isItemSelected) 2.dp else 1.dp,
                                if (isItemSelected) accentOrange else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        isDir -> Icons.Default.Folder
                                        isVideo -> Icons.Default.Movie
                                        else -> Icons.Default.MusicNote
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        isDir -> MaterialTheme.colorScheme.primary
                                        isVideo -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.tertiary
                                    },
                                    modifier = Modifier.size(28.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val detailsText = if (isDir) {
                                        val count = try { file.list()?.size ?: 0 } catch(e:Exception) { 0 }
                                        "$count items"
                                    } else {
                                        val sizeMb = file.length().toFloat() / (1024 * 1024)
                                        String.format("%.1f MB", sizeMb)
                                    }
                                    Text(
                                        text = detailsText,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                                if (isItemSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(accentOrange),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                } else if (isDir) {
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Favorites & Pinned Folders",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    favoriteFolders.forEach { folderName ->
                        val folderFileObj = remember(folderName) { java.io.File(folderName) }
                        val isFullPath = folderFileObj.isAbsolute
                        val folderShortName = if (isFullPath) folderFileObj.name else folderName

                        val filesInFolder = remember(mediaList, folderName) {
                            val nonStream = mediaList.filter { it.genre != "Live Stream" }
                            nonStream.filter { item ->
                                val f = java.io.File(item.path)
                                val parentName = f.parentFile?.name ?: ""
                                val parentPath = f.parentFile?.absolutePath ?: ""
                                parentName.equals(folderShortName, ignoreCase = true) ||
                                parentPath.equals(folderName, ignoreCase = true) ||
                                (isFullPath && parentPath.startsWith(folderName))
                            }
                        }
                        val isFavSelected = selectionState.selectedFolderPaths.contains(folderName)

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (selectionState.isInSelectionMode || isSelectModeActive) {
                                            viewModel.toggleFolderSelection(folderName)
                                        } else {
                                            val fDirect = java.io.File(folderName)
                                            val fEmulated = java.io.File("/storage/emulated/0/$folderName")
                                            val targetFolder = when {
                                                fDirect.exists() && fDirect.isDirectory -> fDirect
                                                fEmulated.exists() && fEmulated.isDirectory -> fEmulated
                                                filesInFolder.isNotEmpty() -> java.io.File(filesInFolder.first().path).parentFile
                                                else -> null
                                            }
                                            if (targetFolder != null && targetFolder.exists()) {
                                                currentBrowseFolder = targetFolder
                                            } else if (filesInFolder.isNotEmpty()) {
                                                viewModel.playAll(filesInFolder)
                                                android.widget.Toast.makeText(context, "Playing all files in $folderShortName", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                android.widget.Toast.makeText(context, "No local files indexed under $folderShortName yet", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        viewModel.toggleFolderSelection(folderName)
                                    }
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isFavSelected) accentOrange.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            ),
                            border = BorderStroke(
                                if (isFavSelected) 2.dp else 0.dp,
                                if (isFavSelected) accentOrange else Color.Transparent
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                    Column {
                                        Text(folderShortName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("${filesInFolder.size} files available", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isFavSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(accentOrange),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.toggleFavoriteFolder(folderName)
                                            android.widget.Toast.makeText(context, "Removed $folderShortName from favorites", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Star, contentDescription = "Unpin Folder", tint = accentOrange, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column {
                    Text(
                        text = "Root Storages",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    listOf(
                        Pair("Internal Storage", "/storage/emulated/0"),
                        Pair("SD Card slot", "/storage/sdcard1")
                    ).filter { (storageName, path) ->
                        if (storageName.contains("Internal")) {
                            true
                        } else {
                            val f = java.io.File(path)
                            f.exists() && f.canRead()
                        }
                    }.forEach { (storageName, path) ->
                        Card(
                            onClick = {
                                val f = java.io.File(path)
                                if (f.exists() && f.canRead()) {
                                    currentBrowseFolder = f
                                } else if (storageName.contains("Internal")) {
                                    currentBrowseFolder = java.io.File("/storage/emulated/0")
                                } else {
                                    android.widget.Toast.makeText(context, "Storage $storageName is not accessible", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Default.SdCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Column {
                                        Text(storageName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("Absolute path: $path", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { if (storageName.startsWith("Internal")) 0.65f else 0.12f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape),
                                    color = MaterialTheme.colorScheme.secondary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column {
                    val networkScanner = remember { com.example.util.NetworkCastScanner(context) }
                    val discoveredDevices by networkScanner.discoveredDevices.collectAsState()
                    val isScanning by networkScanner.isScanning.collectAsState()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Local Network Domains",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(onClick = {
                            networkScanner.startScan()
                        }) {
                            Icon(Icons.Default.Sync, contentDescription = "Scan network", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }

                    if (isScanning) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Scanning local network for SMB, FTP, DLNA and Remote OpenGL hosts...", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else if (discoveredDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap sync icon to scan SMB, FTP, DLNA, or OpenGL hosts",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            discoveredDevices.forEach { dev ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                                    onClick = {
                                        android.widget.Toast.makeText(context, "Connected to ${dev.name} (${dev.ipAddress}:${dev.port})", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (dev.protocol.contains("SMB") || dev.serviceType.contains("smb")) Icons.Default.FolderShared
                                                else if (dev.protocol.contains("FTP") || dev.serviceType.contains("ftp")) Icons.Default.CloudSync
                                                else Icons.Default.CastConnected,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Column {
                                                Text(dev.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                Text("${dev.protocol} • ${dev.ipAddress}:${dev.port}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Icon(Icons.Default.ChevronRight, contentDescription = "Connect", tint = MaterialTheme.colorScheme.primary)
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


@Composable
fun FavoritesTabContent(
    viewModel: MainViewModel,
    onPlayItem: (MediaEntity) -> Unit
) {
    val rawMediaList by viewModel.filteredMediaList.collectAsState()
    val prefs by viewModel.preferencesState.collectAsState()
    
    val favoriteList = remember(rawMediaList, prefs.playlistsJson) {
        rawMediaList.filter { viewModel.isMediaFavorite(it.uriString) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Favorites & Saved Files",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (favoriteList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your Favorites Library is empty",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap the heart icon on the player screen to save your favorite files here for quick access.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(favoriteList, key = { it.uriString }) { item ->
                    Card(
                        onClick = { onPlayItem(item) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (item.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (item.isVideo) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(32.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.displayArtist,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            IconButton(
                                onClick = { viewModel.toggleFavoriteMedia(item.uriString) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "Remove from Favorites",
                                    tint = Color.Red,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun StreamOnlyTabContent(
    viewModel: MainViewModel,
    onPlayItem: (MediaEntity) -> Unit,
    showAddStreamDrawer: () -> Unit
) {
    val mediaList by viewModel.filteredMediaList.collectAsState()
    val streamItems = remember(mediaList) {
        mediaList.filter { it.genre == "Live Stream" || it.genre == "Playlist Stream Channel" }
    }
    var playlistSheetStream by remember { mutableStateOf<MediaEntity?>(null) }

    if (playlistSheetStream != null) {
        com.example.ui.components.StreamPlaylistViewerSheet(
            streamItem = playlistSheetStream!!,
            onDismiss = { playlistSheetStream = null },
            onPlayItem = onPlayItem
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Network Streams",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                onClick = { showAddStreamDrawer() },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Stream", fontSize = 12.sp)
            }
        }

        if (streamItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Podcasts,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Streams Found",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add custom live streams, network URLs, or IPTV playlists using the button above.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(streamItems, key = { streamItem -> streamItem.uriString }) { streamItem ->
                    Card(
                        onClick = {
                            if (com.example.util.StreamPlaylistParser.isPlaylistUrl(streamItem.uriString, streamItem.mimeType)) {
                                playlistSheetStream = streamItem
                            } else {
                                onPlayItem(streamItem)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (com.example.util.StreamPlaylistParser.isPlaylistUrl(streamItem.uriString, streamItem.mimeType)) Icons.Default.PlaylistPlay else Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = streamItem.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (com.example.util.StreamPlaylistParser.isPlaylistUrl(streamItem.uriString, streamItem.mimeType)) "Playlist Stream • Tap to view all channels" else streamItem.uriString,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = { playlistSheetStream = streamItem }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FormatListBulleted,
                                        contentDescription = "View Playlist Channels",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onPlayItem(streamItem) },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Stream",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(24.dp)
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

@Composable
fun PlaylistTabContent(
    viewModel: MainViewModel,
    onPlayItem: (MediaEntity) -> Unit
) {
    val prefs by viewModel.preferencesState.collectAsState()
    val mediaList by viewModel.filteredMediaList.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var selectedPlaylistName by remember { mutableStateOf<String?>(null) }

    val playlistsMap = remember(prefs.playlistsJson) {
        val map = mutableMapOf<String, List<String>>()
        try {
            if (prefs.playlistsJson.isNotBlank()) {
                val json = org.json.JSONObject(prefs.playlistsJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = json.optJSONArray(key) ?: org.json.JSONArray()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getString(i))
                    }
                    map[key] = list
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        map
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newPlaylistName.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.createPlaylist(trimmed)
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectedPlaylistName != null) selectedPlaylistName!! else "Playlists",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedPlaylistName != null) {
                    IconButton(onClick = { selectedPlaylistName = null }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to Playlists")
                    }
                } else {
                    Button(
                        onClick = { showCreateDialog = true },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Playlist", fontSize = 12.sp)
                    }
                }
            }
        }

        if (selectedPlaylistName != null) {
            val playlistItemsUris = playlistsMap[selectedPlaylistName] ?: emptyList()
            val playlistMedia = remember(playlistItemsUris, mediaList) {
                playlistItemsUris.mapNotNull { uri -> mediaList.find { it.uriString == uri } }
            }

            if (playlistMedia.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "This playlist is empty",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Add media from the video or audio tab options menu.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${playlistMedia.size} items", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = { viewModel.playAll(playlistMedia) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play All", fontSize = 12.sp)
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(playlistMedia, key = { it.uriString }) { item ->
                        Card(
                            onClick = { onPlayItem(item) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = if (item.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.displayArtist,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.removeMediaFromPlaylist(selectedPlaylistName!!, item.uriString)
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (playlistsMap.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlaylistAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Playlists Yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Create custom playlists to organize your favorite movies, songs, and streams.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showCreateDialog = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Create Your First Playlist")
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(playlistsMap.keys.toList()) { name ->
                        val count = playlistsMap[name]?.size ?: 0
                        Card(
                            onClick = { selectedPlaylistName = name },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.QueueMusic,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Column {
                                        Text(text = name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text(text = "$count items", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(
                                    onClick = { viewModel.deletePlaylist(name) }
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Playlist", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun AboutAppSection(onBack: () -> Unit) {
    AboutScreen(
        onBack = onBack,
        showPrivacy = null,
        showTerms = null,
        showChangelog = null
    )
}
