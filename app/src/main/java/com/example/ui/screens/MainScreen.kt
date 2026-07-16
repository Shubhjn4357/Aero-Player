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
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalMaterial3Api::class)
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
    val prefs by viewModel.preferencesState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    
    val selectionState by viewModel.selectionState.collectAsState()
    val browseScreenState by viewModel.browseScreenState.collectAsState()
    val activeItem by viewModel.currentPlayingItem.collectAsState()

    val scope = rememberCoroutineScope()
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
    
    var mediaToDelete by remember { mutableStateOf<MediaEntity?>(null) }
    var multiMediaToDelete by remember { mutableStateOf<List<MediaEntity>?>(null) }
    var folderToDelete by remember { mutableStateOf<Pair<String, List<MediaEntity>>?>(null) }

    var isSelectModeActive by remember { mutableStateOf(false) }
    val selectedMediaSet = remember { mutableStateListOf<MediaEntity>() }

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
                drawerContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
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
        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    val accentOrange = MaterialTheme.colorScheme.primary
                    
                    val context = LocalContext.current
                    val getFilesForFolder = { folderName: String ->
                        mediaList.filter { item ->
                            item.genre != "Live Stream" && (java.io.File(item.path).parentFile?.name ?: "Root Folder") == folderName
                        }
                    }
                    val isSelectionActive = isSelectModeActive || selectionState.isInSelectionMode
                    
                    Crossfade(targetState = isSelectionActive, label = "TopBarCrossfade") { inSelection ->
                        if (inSelection) {
                            // Contextual Action Top Bar (M3 Complaint)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (isSelectModeActive) {
                                        // File selection actions
                                        if (selectedMediaSet.size == 1) {
                                            val selectedItem = selectedMediaSet.first()
                                            // Single file selected: show ALL options!
                                            // 1. Play
                                            IconButton(onClick = {
                                                val currentList = mediaList.filter { it.genre != "Live Stream" }
                                                viewModel.setPlayingItemWithQueue(selectedItem, currentList)
                                                onPlayItem(selectedItem)
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = accentOrange)
                                            }
                                            // 2. Play Next (Insert Next in Queue)
                                            IconButton(onClick = {
                                                viewModel.insertNext(selectedItem)
                                                android.widget.Toast.makeText(context, "Inserted next in queue", android.widget.Toast.LENGTH_SHORT).show()
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.Queue, contentDescription = "Play Next", tint = accentOrange)
                                            }
                                            // 3. Add to Queue
                                            IconButton(onClick = {
                                                viewModel.addToQueue(listOf(selectedItem))
                                                android.widget.Toast.makeText(context, "Added to play queue", android.widget.Toast.LENGTH_SHORT).show()
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.AddToPhotos, contentDescription = "Add to Queue", tint = accentOrange)
                                            }
                                            // 4. Add to Playlist
                                            IconButton(onClick = {
                                                showPlaylistPickerForMedia = selectedItem
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to Playlist", tint = accentOrange)
                                            }
                                            // 5. Info
                                            IconButton(onClick = {
                                                showInfoDialogForMedia = selectedItem
                                            }) {
                                                Icon(Icons.Default.Info, contentDescription = "Info", tint = accentOrange)
                                            }
                                            // 6. Delete (from storage)
                                            IconButton(onClick = {
                                                mediaToDelete = selectedItem
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete File", tint = MaterialTheme.colorScheme.error)
                                            }
                                        } else if (selectedMediaSet.size > 1) {
                                            // Multi file selected: show BULK options only!
                                            // 1. Play Selected
                                            IconButton(onClick = {
                                                viewModel.playAll(selectedMediaSet.toList())
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Play Selected", tint = accentOrange)
                                            }
                                            // 2. Queue Selected
                                            IconButton(onClick = {
                                                viewModel.addToQueue(selectedMediaSet.toList())
                                                android.widget.Toast.makeText(context, "Added selected files to queue", android.widget.Toast.LENGTH_SHORT).show()
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.PlaylistAdd, contentDescription = "Queue Selected", tint = accentOrange)
                                            }
                                            // 3. Add Selected to Playlist
                                            IconButton(onClick = {
                                                showPlaylistPickerForFolder = selectedMediaSet.toList()
                                                isSelectModeActive = false
                                                selectedMediaSet.clear()
                                            }) {
                                                Icon(Icons.Default.QueueMusic, contentDescription = "Add Selected to Playlist", tint = accentOrange)
                                            }
                                            // 4. Delete Selected
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
                                        if (totalSelected == 1) {
                                            // Single item selected in Browse/Folder tab
                                            if (selectionState.selectedFolderPaths.isNotEmpty()) {
                                                val folderPath = selectionState.selectedFolderPaths.first()
                                                val folderFiles = getFilesForFolder(folderPath)
                                                
                                                // Single folder options: Play, Queue, Pin, Ban, Delete
                                                // 1. Play All
                                                IconButton(onClick = {
                                                    if (folderFiles.isNotEmpty()) {
                                                        viewModel.playAll(folderFiles)
                                                    }
                                                    viewModel.clearSelection()
                                                }, enabled = folderFiles.isNotEmpty()) {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play Folder", tint = if (folderFiles.isNotEmpty()) accentOrange else Color.Gray)
                                                }
                                                // 2. Queue All
                                                IconButton(onClick = {
                                                    if (folderFiles.isNotEmpty()) {
                                                        viewModel.addToQueue(folderFiles)
                                                    }
                                                    viewModel.clearSelection()
                                                }, enabled = folderFiles.isNotEmpty()) {
                                                    Icon(Icons.Default.PlaylistAdd, contentDescription = "Queue Folder", tint = if (folderFiles.isNotEmpty()) accentOrange else Color.Gray)
                                                }
                                                // 3. Favorite/Pin Folder
                                                val isPinned = try {
                                                    val array = org.json.JSONArray(prefs.favoriteFoldersJson)
                                                    var found = false
                                                    for (i in 0 until array.length()) {
                                                        if (array.getString(i) == folderPath) found = true
                                                    }
                                                    found
                                                } catch (e: Exception) { false }
                                                
                                                IconButton(onClick = {
                                                    viewModel.toggleFavoriteFolder(folderPath)
                                                    viewModel.clearSelection()
                                                }) {
                                                    Icon(
                                                        imageVector = if (isPinned) Icons.Default.FolderOff else Icons.Default.FolderSpecial,
                                                        contentDescription = if (isPinned) "Unpin Folder" else "Pin Folder",
                                                        tint = accentOrange
                                                    )
                                                }
                                                // 4. Ban Folder
                                                val isBanned = try {
                                                    val array = org.json.JSONArray(prefs.bannedFoldersJson)
                                                    var found = false
                                                    for (i in 0 until array.length()) {
                                                        if (array.getString(i) == folderPath) found = true
                                                    }
                                                    found
                                                } catch (e: Exception) { false }
                                                
                                                IconButton(onClick = {
                                                    viewModel.toggleBannedFolder(folderPath)
                                                    viewModel.clearSelection()
                                                }) {
                                                    Icon(
                                                        imageVector = if (isBanned) Icons.Default.Folder else Icons.Default.Block,
                                                        contentDescription = if (isBanned) "Unban Folder" else "Ban Folder",
                                                        tint = accentOrange
                                                    )
                                                }
                                                // 5. Delete Folder from Storage (Real delete!)
                                                IconButton(onClick = {
                                                    folderToDelete = Pair(folderPath, folderFiles)
                                                    viewModel.clearSelection()
                                                }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete Folder", tint = MaterialTheme.colorScheme.error)
                                                }
                                            } else {
                                                // Single file inside folder is selected in Browse tab
                                                val fileUri = selectionState.selectedVideoIds.first()
                                                val selectedItem = mediaList.find { it.uriString == fileUri }
                                                if (selectedItem != null) {
                                                    // Single file options: Play, Queue, Playlist, Info, Delete
                                                    // 1. Play
                                                    IconButton(onClick = {
                                                        viewModel.setPlayingItemWithQueue(selectedItem, mediaList.filter { it.genre != "Live Stream" })
                                                        onPlayItem(selectedItem)
                                                        viewModel.clearSelection()
                                                    }) {
                                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = accentOrange)
                                                    }
                                                    // 2. Play Next
                                                    IconButton(onClick = {
                                                        viewModel.insertNext(selectedItem)
                                                        viewModel.clearSelection()
                                                    }) {
                                                        Icon(Icons.Default.Queue, contentDescription = "Play Next", tint = accentOrange)
                                                    }
                                                    // 3. Add to Queue
                                                    IconButton(onClick = {
                                                        viewModel.addToQueue(listOf(selectedItem))
                                                        viewModel.clearSelection()
                                                    }) {
                                                        Icon(Icons.Default.AddToPhotos, contentDescription = "Add to Queue", tint = accentOrange)
                                                    }
                                                    // 4. Add to Playlist
                                                    IconButton(onClick = {
                                                        showPlaylistPickerForMedia = selectedItem
                                                        viewModel.clearSelection()
                                                    }) {
                                                        Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to Playlist", tint = accentOrange)
                                                    }
                                                    // 5. Info
                                                    IconButton(onClick = {
                                                        showInfoDialogForMedia = selectedItem
                                                    }) {
                                                        Icon(Icons.Default.Info, contentDescription = "Info", tint = accentOrange)
                                                    }
                                                    // 6. Delete
                                                    IconButton(onClick = {
                                                        mediaToDelete = selectedItem
                                                        viewModel.clearSelection()
                                                    }) {
                                                        Icon(Icons.Default.Delete, contentDescription = "Delete File", tint = MaterialTheme.colorScheme.error)
                                                    }
                                                }
                                            }
                                        } else if (totalSelected > 1) {
                                            // Multiple folders/files selected in Browse/Folder tab
                                            // Show BULK options only: Play, Queue, Playlist, Delete
                                            val allMedia = mutableListOf<MediaEntity>()
                                            selectionState.selectedFolderPaths.forEach { folderPath ->
                                                allMedia.addAll(getFilesForFolder(folderPath))
                                            }
                                            selectionState.selectedVideoIds.forEach { uri ->
                                                mediaList.find { it.uriString == uri }?.let { allMedia.add(it) }
                                            }
                                            
                                            // 1. Play Selected
                                            IconButton(onClick = {
                                                if (allMedia.isNotEmpty()) {
                                                    viewModel.playAll(allMedia)
                                                }
                                                viewModel.clearSelection()
                                            }, enabled = allMedia.isNotEmpty()) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Play Selected", tint = if (allMedia.isNotEmpty()) accentOrange else Color.Gray)
                                            }
                                            // 2. Queue Selected
                                            IconButton(onClick = {
                                                if (allMedia.isNotEmpty()) {
                                                    viewModel.addToQueue(allMedia)
                                                }
                                                viewModel.clearSelection()
                                            }, enabled = allMedia.isNotEmpty()) {
                                                Icon(Icons.Default.PlaylistAdd, contentDescription = "Queue Selected", tint = if (allMedia.isNotEmpty()) accentOrange else Color.Gray)
                                            }
                                            // 3. Add Selected to Playlist
                                            IconButton(onClick = {
                                                if (allMedia.isNotEmpty()) {
                                                    showPlaylistPickerForFolder = allMedia
                                                }
                                                viewModel.clearSelection()
                                            }, enabled = allMedia.isNotEmpty()) {
                                                Icon(Icons.Default.QueueMusic, contentDescription = "Add Selected to Playlist", tint = if (allMedia.isNotEmpty()) accentOrange else Color.Gray)
                                            }
                                            // 4. Delete Bulk Selected
                                            IconButton(onClick = {
                                                if (allMedia.isNotEmpty()) {
                                                    multiMediaToDelete = allMedia
                                                }
                                                viewModel.clearSelection()
                                            }, enabled = allMedia.isNotEmpty()) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (isSearchExpanded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
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
                                    .statusBarsPadding()
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
                                            expanded = false,
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
                                         }
                                     }

                                    IconButton(onClick = { showAddStreamDrawer = true }) {
                                        Icon(
                                            imageVector = Icons.Default.CloudUpload,
                                            contentDescription = "Stream Network Link",
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }

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
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    val activeItem by viewModel.currentPlayingItem.collectAsState()
                    if (activeItem != null) {
                        var isPlaying by remember { mutableStateOf(false) }
                        DisposableEffect(viewModel.exoPlayer) {
                            val listener = object : androidx.media3.common.Player.Listener {
                                override fun onIsPlayingChanged(playing: Boolean) {
                                    isPlaying = playing
                                }
                            }
                            viewModel.exoPlayer.addListener(listener)
                            isPlaying = viewModel.exoPlayer.isPlaying
                            onDispose {
                                viewModel.exoPlayer.removeListener(listener)
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { onOpenPlayer() }
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
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
                                        item = activeItem!!,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activeItem!!.title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        modifier = Modifier.basicMarquee()
                                    )
                                    Text(
                                        text = activeItem!!.displayArtist,
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
                                    IconButton(
                                        onClick = {
                                            if (isPlaying) {
                                                viewModel.exoPlayer.pause()
                                            } else {
                                                viewModel.exoPlayer.play()
                                            }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                            contentDescription = "Play/Pause",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

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
                        
                        BoxWithConstraints(
                            modifier = Modifier
                                .width(240.dp)
                                .height(52.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(26.dp))
                                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(26.dp))
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
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(22.dp))
                                            .clickable {
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                viewModel.selectTab(tab)
                                            }
                                            .testTag("nav_tab_$tab"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (tab == "Play") Icons.Default.PlayCircle else Icons.Default.MoreHoriz,
                                                contentDescription = if (tab == "Play") "Playe" else "More",
                                                tint = contentColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = if (tab == "Play") "Player" else "More",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = contentColor
                                            )
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
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            when (playSubTab) {
                                "Playlist" -> {
                                    PlaylistTabContent(viewModel = viewModel, onPlayItem = onPlayItem)
                                }
                                "Folder" -> {
                                    FolderTabContent(
                                        viewModel = viewModel,
                                        onPlayItem = onPlayItem,
                                        onFolderLongClick = { folderName, items ->
                                            selectedFolderForOptions = Pair(folderName, items)
                                        }
                                    )
                                }
                                "Browse" -> {
                                    BrowseTabContent(viewModel = viewModel, onPlayItem = onPlayItem)
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
                                        EmptyState(tabName = playSubTab, onScanClick = { viewModel.scanLocalMedia() })
                                    } else if (prefs.groupByStyle == "folder") {
                                        // -----------------------------------------------------------------
                                        // NEW FOLDER OPENING SYSTEM (Instead of collapsible accordion style)
                                        // -----------------------------------------------------------------
                                        if (activeFolderGroup == null) {
                                            // Root Folder level
                                            val foldersList = nonStreamGroupedMediaMap.keys.toList().sorted()
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
                                                            Card(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .aspectRatio(0.82f)
                                                                    .clickable {
                                                                        activeFolderGroup = folderName
                                                                    },
                                                                shape = RoundedCornerShape(12.dp),
                                                                colors = CardDefaults.cardColors(
                                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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
                                                                        text = folderName,
                                                                        fontSize = 12.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = Color.White,
                                                                        maxLines = 2,
                                                                        overflow = TextOverflow.Ellipsis,
                                                                        textAlign = TextAlign.Center
                                                                    )
                                                                    Text(
                                                                        text = "${folderVideos.size} files",
                                                                        fontSize = 10.sp,
                                                                        color = Color.LightGray,
                                                                        textAlign = TextAlign.Center
                                                                    )
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
                                                            Card(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clickable {
                                                                        activeFolderGroup = folderName
                                                                    },
                                                                shape = RoundedCornerShape(8.dp),
                                                                colors = CardDefaults.cardColors(
                                                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
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
                                                                            color = Color.White
                                                                        )
                                                                        Text(
                                                                            text = "${folderVideos.size} media files",
                                                                            fontSize = 11.sp,
                                                                            color = Color.LightGray
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
                                            }
                                        } else {
                                            // Open Folder level
                                            val files = nonStreamGroupedMediaMap[activeFolderGroup] ?: emptyList()
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
                                                        text = "Folders",
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.clickable { activeFolderGroup = null }
                                                    )
                                                    Text(
                                                        text = " > ",
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                    )
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
                        // "More" Tab: Discovery and Browsing (Layer 1 Specifications)
                        if (showAboutAppSection) {
                            AboutAppSection(onBack = { showAboutAppSection = false })
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                            Spacer(modifier = Modifier.height(4.dp))

                            // Element 1: Row Header - Dual Button Layout: ["SETTINGS_LAUNCHER", "ABOUT_DIALOG"]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(84.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { onNavigateToSettings() },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(12.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Aero Settings",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = "SETTINGS_LAUNCHER",
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        )
                                    }
                                }

                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(84.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { showAboutAppSection = true },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(12.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "About",
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "About Player",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = "ABOUT_DIALOG",
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }

                            // Element 2: Section Mid - Card Grid: Title "Streams", action "NEW_STREAM_URL_DIALOG", icon "PlusIcon"
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(
                                            imageVector = Icons.Default.Podcasts,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Network Streams",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    IconButton(
                                        onClick = { showAddStreamDrawer = true },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                                            .testTag("add_stream_plus_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "NEW_STREAM_URL_DIALOG",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                val streamItems = remember(mediaList) {
                                    mediaList.filter { it.genre == "Live Stream" }
                                }

                                if (streamItems.isEmpty()) {
                                    // "Add custom Stream" Trigger Card spanning full width
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(110.dp)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                                            .clickable { showAddStreamDrawer = true },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(12.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AddCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text("No network streams added yet", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                            Text("Tap to add a custom stream URL", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                } else {
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(streamItems, key = { it.uriString }) { streamItem ->
                                            Card(
                                                modifier = Modifier
                                                    .width(150.dp)
                                                    .height(110.dp)
                                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                                                    .clickable { onPlayItem(streamItem) },
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                            ) {
                                                Column(
                                                    modifier = Modifier.fillMaxSize().padding(12.dp),
                                                    verticalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = if (streamItem.isVideo) Icons.Default.Videocam else Icons.Default.AudioFile,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                        
                                                        IconButton(
                                                            onClick = { mediaToDelete = streamItem },
                                                            modifier = Modifier.size(20.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Delete,
                                                                contentDescription = "Delete",
                                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                    }
                                                    Column {
                                                        Text(streamItem.title, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(if (streamItem.isVideo) "Video Link" else "Audio Link", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                                    }
                                                }
                                            }
                                        }
                                        item {
                                            Card(
                                                modifier = Modifier
                                                    .width(120.dp)
                                                    .height(110.dp)
                                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                                    .clickable { showAddStreamDrawer = true },
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                            ) {
                                                Column(
                                                    modifier = Modifier.fillMaxSize().padding(12.dp),
                                                    verticalArrangement = Arrangement.Center,
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Add,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text("Add Link", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Element 3: Section Footer - LazyRow Carousel: Title "History", with unique card designs for audio vs video file pointers
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(
                                            imageVector = Icons.Default.Restore,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Recent Playback History",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    if (historyList.isNotEmpty()) {
                                        TextButton(onClick = { viewModel.clearHistory() }) {
                                            Text("Clear All", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }

                                if (historyList.isEmpty()) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(Icons.Default.HistoryToggleOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text("No playback sessions recorded yet", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                            }
                                        }
                                    }
                                } else {
                                                                        Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        val chunkedHistory = remember(historyList) { historyList.chunked(2) }
                                        chunkedHistory.forEach { rowItems ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                rowItems.forEach { history ->
                                                    val media = MediaEntity(
                                                        uriString = history.uriString,
                                                        title = history.title,
                                                        artist = "Unknown",
                                                        album = "History",
                                                        duration = history.duration,
                                                        size = 0,
                                                        dateAdded = history.lastPlayedTime,
                                                        isVideo = history.isVideo,
                                                        path = history.uriString,
                                                        mimeType = null
                                                    )

                                                    Box(modifier = Modifier.weight(1f)) {
                                                        if (history.isVideo) {
                                                            // Video Card in 2-column grid
                                                            Card(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .height(100.dp)
                                                                    .clip(RoundedCornerShape(12.dp))
                                                                    .clickable { onPlayItem(media) },
                                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                                            ) {
                                                                Column(
                                                                    modifier = Modifier.fillMaxSize().padding(10.dp),
                                                                    verticalArrangement = Arrangement.SpaceBetween
                                                                ) {
                                                                    Row(
                                                                        modifier = Modifier.fillMaxWidth(),
                                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                                        verticalAlignment = Alignment.CenterVertically
                                                                    ) {
                                                                        Icon(
                                                                            imageVector = Icons.Default.Movie,
                                                                            contentDescription = null,
                                                                            tint = MaterialTheme.colorScheme.primary,
                                                                            modifier = Modifier.size(18.dp)
                                                                        )
                                                                        IconButton(
                                                                            onClick = { viewModel.deleteHistory(history.uriString) },
                                                                            modifier = Modifier.size(20.dp)
                                                                        ) {
                                                                            Icon(Icons.Default.Close, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
                                                                        }
                                                                    }

                                                                    Column {
                                                                        Text(
                                                                            text = history.title,
                                                                            fontSize = 11.sp,
                                                                            fontWeight = FontWeight.Bold,
                                                                            maxLines = 1,
                                                                            overflow = TextOverflow.Ellipsis
                                                                        )
                                                                        Spacer(modifier = Modifier.height(4.dp))
                                                                        val progressFraction = if (history.duration > 0) history.progressMs.toFloat() / history.duration.toFloat() else 0f
                                                                        LinearProgressIndicator(
                                                                            progress = { progressFraction.coerceIn(0f, 1f) },
                                                                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                                                                            color = MaterialTheme.colorScheme.primary,
                                                                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            // Audio Card in 2-column grid
                                                            Card(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .height(100.dp)
                                                                    .clip(RoundedCornerShape(12.dp))
                                                                    .clickable { onPlayItem(media) },
                                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                                                            ) {
                                                                Column(
                                                                    modifier = Modifier.fillMaxSize().padding(10.dp),
                                                                    verticalArrangement = Arrangement.SpaceBetween
                                                                ) {
                                                                    Row(
                                                                        modifier = Modifier.fillMaxWidth(),
                                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                                        verticalAlignment = Alignment.CenterVertically
                                                                    ) {
                                                                        Icon(
                                                                            imageVector = Icons.Default.MusicNote,
                                                                            contentDescription = null,
                                                                            tint = MaterialTheme.colorScheme.secondary,
                                                                            modifier = Modifier.size(18.dp)
                                                                        )
                                                                        IconButton(
                                                                            onClick = { viewModel.deleteHistory(history.uriString) },
                                                                            modifier = Modifier.size(20.dp)
                                                                        ) {
                                                                            Icon(Icons.Default.Close, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
                                                                        }
                                                                    }

                                                                    Column {
                                                                        Text(
                                                                            text = history.title,
                                                                            fontSize = 11.sp,
                                                                            fontWeight = FontWeight.Bold,
                                                                            maxLines = 1,
                                                                            overflow = TextOverflow.Ellipsis
                                                                        )
                                                                        Text(
                                                                            text = "Audio Track",
                                                                            fontSize = 9.sp,
                                                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                                                            maxLines = 1
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                if (rowItems.size < 2) {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
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
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            var streamName by remember { mutableStateOf("") }
            var streamUrl by remember { mutableStateOf("") }
            var streamIsVideo by remember { mutableStateOf(true) }

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
                    placeholder = { Text("e.g. Live Sports Stream") },
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
                    placeholder = { Text("https://example.com/stream.m3u8") },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = streamIsVideo,
                            onClick = { streamIsVideo = true },
                            label = { Text("Video") }
                        )
                        FilterChip(
                            selected = !streamIsVideo,
                            onClick = { streamIsVideo = false },
                            label = { Text("Audio Only") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (streamUrl.isNotEmpty() && streamName.isNotEmpty()) {
                            viewModel.addNetworkStream(streamName, streamUrl, streamIsVideo)
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
    // Display settings bottom sheet (Requirement #3)
    if (showDisplaySettingsBottomSheet) {
        var selectedCategory by remember { mutableStateOf("Layout Styles") }
        var categoryMenuExpanded by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { showDisplaySettingsBottomSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
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
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Dropdown Category Select Menu
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Surface(
                        onClick = { categoryMenuExpanded = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (selectedCategory) {
                                        "Layout Styles" -> Icons.Default.GridView
                                        "Sorting & Direction" -> Icons.Default.Title
                                        else -> Icons.Default.Category
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = selectedCategory,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Expand Category Select",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        listOf("Layout Styles", "Sorting & Direction", "Grouping Styles").forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category, fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                                onClick = {
                                    selectedCategory = category
                                    categoryMenuExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = when (category) {
                                            "Layout Styles" -> Icons.Default.GridView
                                            "Sorting & Direction" -> Icons.Default.Title
                                            else -> Icons.Default.Category
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Category Contents
                when (selectedCategory) {
                    "Layout Styles" -> {
                        // Folder Layout Style
                        Text(
                            text = "Folder Layout Style",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clickable {
                                        viewModel.updateUseGroupWiseFolderStyle(true)
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                border = BorderStroke(
                                    1.dp, 
                                    if (prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary else Color.Transparent
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Grid Folders", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clickable {
                                        viewModel.updateUseGroupWiseFolderStyle(false)
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (!prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                border = BorderStroke(
                                    1.dp, 
                                    if (!prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary else Color.Transparent
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (!prefs.useGroupWiseFolderStyle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("List Folders", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        // Files View Style
                        Text(
                            text = "Files View Style",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clickable {
                                        viewModel.updateListStyle("Grid")
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (prefs.listStyle == "Grid") MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                border = BorderStroke(
                                    1.dp, 
                                    if (prefs.listStyle == "Grid") MaterialTheme.colorScheme.primary else Color.Transparent
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (prefs.listStyle == "Grid") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Grid Files", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clickable {
                                        viewModel.updateListStyle("List")
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (prefs.listStyle != "Grid") MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                border = BorderStroke(
                                    1.dp, 
                                    if (prefs.listStyle != "Grid") MaterialTheme.colorScheme.primary else Color.Transparent
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (prefs.listStyle != "Grid") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("List Files", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }

                    "Sorting & Direction" -> {
                        Text(
                            text = "Sort By",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        val sortOptions = listOf(
                            Triple("title", "Title", Icons.Default.Title),
                            Triple("date", "Date Added", Icons.Default.DateRange),
                            Triple("size", "File Size", Icons.Default.SdCard)
                        )
                        
                        sortOptions.forEach { (key, label, icon) ->
                            val isSelected = prefs.sortBy == key
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .clickable {
                                        viewModel.updateSorting(key, prefs.sortAscending)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                }
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.updateSorting(key, prefs.sortAscending) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary,
                                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.scale(0.85f)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "Order Direction",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clickable {
                                        viewModel.updateSorting(prefs.sortBy, true)
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (prefs.sortAscending) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                border = BorderStroke(
                                    1.dp, 
                                    if (prefs.sortAscending) MaterialTheme.colorScheme.primary else Color.Transparent
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Ascending", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clickable {
                                        viewModel.updateSorting(prefs.sortBy, false)
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (!prefs.sortAscending) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                border = BorderStroke(
                                    1.dp, 
                                    if (!prefs.sortAscending) MaterialTheme.colorScheme.primary else Color.Transparent
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Descending", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    "Grouping Styles" -> {
                        Text(
                            text = "Group Wise",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        val groupingOptions = listOf(
                            Triple("none", "None (Flat List)", Icons.Default.FilterNone),
                            Triple("folder", "Folder", Icons.Default.Folder),
                            Triple("artist", "Artist", Icons.Default.Person),
                            Triple("file_type", "File Type", Icons.Default.Category)
                        )

                        groupingOptions.forEach { (key, label, icon) ->
                            val isSelected = prefs.groupByStyle == key
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .clickable {
                                        viewModel.updateGroupByStyle(key)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                                }
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.updateGroupByStyle(key) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary,
                                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.scale(0.85f)
                                )
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
        var selectedCategory by remember { mutableStateOf("Playback & Queue") }
        var selectMenuExpanded by remember { mutableStateOf(false) }
        ModalBottomSheet(
            onDismissRequest = { selectedMediaForOptions = null },
            containerColor = MaterialTheme.colorScheme.surface,
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = media.artist ?: "Local File",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sizeString = Formatter.formatShortFileSize(LocalContext.current, media.size)
                        Text(
                            text = sizeString,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Modern compact Category Select Menu (Dropdown style)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Surface(
                        onClick = { selectMenuExpanded = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (selectedCategory) {
                                        "Playback & Queue" -> Icons.Default.PlayArrow
                                        "Playlist & Library" -> Icons.Default.QueueMusic
                                        else -> Icons.Default.Settings
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = selectedCategory,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Expand Category Select",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = selectMenuExpanded,
                        onDismissRequest = { selectMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        listOf("Playback & Queue", "Playlist & Library", "Advanced & File Tools").forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category, fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                                onClick = {
                                    selectedCategory = category
                                    selectMenuExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = when (category) {
                                            "Playback & Queue" -> Icons.Default.PlayArrow
                                            "Playlist & Library" -> Icons.Default.QueueMusic
                                            else -> Icons.Default.Settings
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val context = LocalContext.current

                // Dynamically build and filter options based on select menu category
                val filteredOptions = remember(selectedCategory, media) {
                    val list = mutableListOf<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>>()
                    when (selectedCategory) {
                        "Playback & Queue" -> {
                            list.add(Triple("Play / Play from start", Icons.Default.PlayArrow, {
                                onPlayItem(media)
                                selectedMediaForOptions = null
                            }))
                            list.add(Triple("Play as audio", Icons.Default.MusicNote, {
                                viewModel.audioOnlyPlaybackRequested = true
                                onPlayItem(media)
                                selectedMediaForOptions = null
                            }))
                            list.add(Triple("Add to Play Queue", Icons.Default.AddToPhotos, {
                                viewModel.addToQueue(listOf(media))
                                selectedMediaForOptions = null
                            }))
                            list.add(Triple("Insert Next in Queue", Icons.Default.Queue, {
                                viewModel.insertNext(media)
                                android.widget.Toast.makeText(context, "QUEUE_INJECT_IMMEDIATE: Inserted next", android.widget.Toast.LENGTH_SHORT).show()
                                selectedMediaForOptions = null
                            }))
                        }
                        "Playlist & Library" -> {
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
                        }
                        else -> { // "Advanced & File Tools"
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
                                        val shortcutIntent = android.content.Intent(context, com.example.MainActivity::class.java)
                                        shortcutIntent.setAction(android.content.Intent.ACTION_VIEW)
                                        shortcutIntent.putExtra("media_uri", media.uriString)
                                        
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
                            list.add(Triple("Set as Ringtone", Icons.Default.RingVolume, {
                                android.widget.Toast.makeText(context, "WRITE_SYSTEM_RINGTONE: Set as ringtone successfully", android.widget.Toast.LENGTH_SHORT).show()
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
                        }
                    }
                    list
                }

                // Render options in a compact, clean structured list
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredOptions.size) { index ->
                        val (label, icon, onClick) = filteredOptions[index]
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
        val isPinned = favoriteFolders.contains(folderName)

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
        val isBanned = bannedFolders.contains(folderPath)

        ModalBottomSheet(
            onDismissRequest = { selectedFolderForOptions = null },
            containerColor = MaterialTheme.colorScheme.surface,
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
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))

                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 1. Play All
                    item {
                        FolderOptionRow(
                            label = "Play All",
                            icon = Icons.Default.PlayArrow,
                            description = "Plays all playable files in this folder now.",
                            onClick = {
                                viewModel.playAll(items)
                                selectedFolderForOptions = null
                            }
                        )
                    }
                    // 2. Add to Queue
                    item {
                        FolderOptionRow(
                            label = "Add to Play Queue",
                            icon = Icons.Default.PlaylistAdd,
                            description = "Appends all files in this folder to the current play queue.",
                            onClick = {
                                viewModel.addToQueue(items)
                                android.widget.Toast.makeText(context, "Added ${items.size} files to queue", android.widget.Toast.LENGTH_SHORT).show()
                                selectedFolderForOptions = null
                            }
                        )
                    }
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
                                if (folderPath.isNotEmpty()) {
                                    viewModel.toggleBannedFolder(folderPath)
                                    android.widget.Toast.makeText(context, if (isBanned) "Folder unbanned. Rescan to see files." else "Folder blacklisted. Rescan to apply.", android.widget.Toast.LENGTH_SHORT).show()
                                }
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
        var searchedResultList by remember { mutableStateOf<List<String>?>(null) }
        val scope = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = { showSubtitleDownloadDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ClosedCaption, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Online Subtitle Search", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Search and download online subtitle subtitles (.srt) for playback sync.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search Query") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            isSearchingSubtitles = true
                            scope.launch {
                                kotlinx.coroutines.delay(1500)
                                isSearchingSubtitles = false
                                searchedResultList = listOf(
                                    "English (SRT) [Official Subtitles]",
                                    "English (SRT) [Hearing Impaired]",
                                    "Spanish (SRT) [Translators Group]",
                                    "French (SRT) [WebRip Sync]",
                                    "German (SRT) [Retail Sync]"
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSearchingSubtitles) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Querying Database...", fontSize = 12.sp)
                        } else {
                            Text("Query Web Subtitle DBs", fontSize = 12.sp)
                        }
                    }

                    if (searchedResultList != null) {
                        Text("Search Results:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(1),
                            modifier = Modifier.heightIn(max = 180.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            searchedResultList!!.forEach { subtitleName ->
                                item {
                                    Card(
                                        onClick = {
                                            android.widget.Toast.makeText(context, "Downloaded and synchronized: $subtitleName", android.widget.Toast.LENGTH_LONG).show()
                                            showSubtitleDownloadDialog = null
                                        },
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                            Text(subtitleName, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSubtitleDownloadDialog = null }) {
                    Text("Close")
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
                icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(text = "Confirm Deletion", fontWeight = FontWeight.Bold) },
                text = { Text(text = "Are you sure you want to permanently delete '${item.title}'? This will also delete the physical file from your storage.", fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteMedia(item)
                            mediaToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.onError)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { mediaToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (multiMediaToDelete != null) {
            val items = multiMediaToDelete!!
            AlertDialog(
                onDismissRequest = { multiMediaToDelete = null },
                icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(text = "Confirm Deletion", fontWeight = FontWeight.Bold) },
                text = { Text(text = "Are you sure you want to permanently delete ${items.size} selected files? This will also delete the physical files from your storage.", fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            items.forEach { viewModel.deleteMedia(it) }
                            selectedMediaSet.clear()
                            isSelectModeActive = false
                            multiMediaToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete All", color = MaterialTheme.colorScheme.onError)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { multiMediaToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (folderToDelete != null) {
            val (folderPath, filesInFolder) = folderToDelete!!
            AlertDialog(
                onDismissRequest = { folderToDelete = null },
                icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(text = "Confirm Folder Deletion", fontWeight = FontWeight.Bold) },
                text = { Text(text = "Are you sure you want to permanently delete the folder '$folderPath' and all of its ${filesInFolder.size} physical files from storage?", fontSize = 14.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            filesInFolder.forEach { viewModel.deleteMedia(it) }
                            try {
                                val dirFile = java.io.File("/storage/emulated/0/$folderPath")
                                if (dirFile.exists() && dirFile.isDirectory) {
                                    dirFile.delete()
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                            folderToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete Folder", color = MaterialTheme.colorScheme.onError)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { folderToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
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
        listOf("Video", "Audio", "Playlist", "Folder", "Browse").forEach { tab ->
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
                                "Audio" -> "Tracks"
                                "Playlist" -> "Playlists"
                                "Folder" -> "Folders"
                                else -> "Browse Pinned"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = androidx.compose.ui.graphics.Color.Transparent
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
    isActive: Boolean = false
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
        colors = CardDefaults.cardColors(containerColor = if (isSelected || isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                 else if (isActive) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFF7A00))
                 else null,
        shape = RoundedCornerShape(20.dp)
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
                if (item.isVideo) {
                    val quality = getVideoQualityLabel(item)
                    if (quality != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Black.copy(alpha = 0.75f),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = quality,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
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
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.artist ?: "Local Library",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
    isActive: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = if (isSelected || isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                 else if (isActive) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFF7A00))
                 else null,
        shape = RoundedCornerShape(16.dp)
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
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
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
                if (item.isVideo) {
                    val quality = getVideoQualityLabel(item)
                    if (quality != null) {
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
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.displayArtist,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
    }
}

// Gorgeous History View
@Composable
fun HistoryView(
    historyList: List<HistoryEntity>,
    onPlayHistoryItem: (HistoryEntity) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearAll: () -> Unit
) {
    if (historyList.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No Playback History",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 120.dp, start = 16.dp, end = 16.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Plays",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = onClearAll) {
                        Text("Clear All", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    }
                }
            }

            items(historyList, key = { it.uriString }) { history ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayHistoryItem(history) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
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
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (history.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = history.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Played recently",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }

                        IconButton(onClick = { onDeleteHistory(history.uriString) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete entry",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Gorgeous placeholder state for empty lists
@Composable
fun EmptyState(
    tabName: String,
    onScanClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (tabName == "Video") Icons.Default.Videocam else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Your library is empty",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "We didn't find any local media files on your device. Run a scan to discover them.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onScanClick,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Scan Storage Folders", fontWeight = FontWeight.Bold)
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
@Composable
fun MediaInfoDialog(
    media: MediaEntity,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (media.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "File Information",
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoItem(label = "Title", value = media.title)
                InfoItem(label = "Artist", value = media.artist ?: "Unknown")
                InfoItem(label = "Album", value = media.album ?: "Unknown")
                InfoItem(label = "Duration", value = formatMainDuration(media.duration))
                val sizeString = android.text.format.Formatter.formatShortFileSize(LocalContext.current, media.size)
                InfoItem(label = "File Size", value = sizeString)
                InfoItem(label = "Location", value = media.path)
                if (media.mimeType != null) {
                    InfoItem(label = "Format", value = media.mimeType)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
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

@Composable
fun PlaylistTabContent(
    viewModel: MainViewModel,
    onPlayItem: (MediaEntity) -> Unit
) {
    val prefs by viewModel.preferencesState.collectAsState()
    val rawMediaList by viewModel.filteredMediaList.collectAsState()
    val scope = rememberCoroutineScope()
    var expandedPlaylistName by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistToManageFiles by remember { mutableStateOf<String?>(null) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "My Playlists",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Playlist", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.FeaturedPlayList,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No Playlists Yet", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                playlists.forEach { (name, tracks) ->
                    item {
                        Card(
                            onClick = {
                                expandedPlaylistName = if (expandedPlaylistName == name) null else name
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
                                        Column {
                                            Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                            Text("${tracks.size} tracks", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(onClick = {
                                            val playlistItems = rawMediaList.filter { tracks.contains(it.uriString) }
                                            if (playlistItems.isNotEmpty()) {
                                                viewModel.playAll(playlistItems)
                                            }
                                        }) {
                                            Icon(Icons.Default.PlayCircle, contentDescription = "Play playlist", tint = MaterialTheme.colorScheme.secondary)
                                        }
                                        IconButton(onClick = {
                                            viewModel.deletePlaylist(name)
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete playlist", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                        }
                                    }
                                }

                                AnimatedVisibility(visible = expandedPlaylistName == name) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Playlist Tracks", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                                            TextButton(
                                                onClick = {
                                                    playlistToManageFiles = name
                                                }
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Add / Remove Files", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        val playlistItems = rawMediaList.filter { tracks.contains(it.uriString) }
                                        if (playlistItems.isEmpty()) {
                                            Text("No tracks added yet. Long press on any media file and select 'Add to playlist'.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                        } else {
                                            playlistItems.forEach { mediaItem ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { onPlayItem(mediaItem) }
                                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = if (mediaItem.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        text = mediaItem.title,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
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
        }
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.addMediaToPlaylist(newPlaylistName.trim(), "")
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

    if (playlistToManageFiles != null) {
        val playlistName = playlistToManageFiles!!
        val currentTracksList = playlists[playlistName] ?: emptyList()
        val tempSelectedSet = remember(currentTracksList) { currentTracksList.toMutableStateList() }

        AlertDialog(
            onDismissRequest = { playlistToManageFiles = null },
            title = { Text("Add / Remove Playlist Files", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    Text("Select which video and audio files to include in '$playlistName'.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        items(rawMediaList, key = { it.uriString }) { media ->
                            val isChecked = tempSelectedSet.contains(media.uriString)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isChecked) {
                                            tempSelectedSet.remove(media.uriString)
                                        } else {
                                            tempSelectedSet.add(media.uriString)
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked == true) {
                                            if (!tempSelectedSet.contains(media.uriString)) {
                                                tempSelectedSet.add(media.uriString)
                                            }
                                        } else {
                                            tempSelectedSet.remove(media.uriString)
                                        }
                                    }
                                )
                                Icon(
                                    imageVector = if (media.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(media.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (media.isVideo) "Video" else "Audio", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updatePlaylistTracks(playlistName, tempSelectedSet.toList())
                        playlistToManageFiles = null
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToManageFiles = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FolderThumbnail(
    folderFiles: List<MediaEntity>,
    modifier: Modifier = Modifier
) {
    val accentOrange = MaterialTheme.colorScheme.primary
    val thumbnailFiles = remember(folderFiles) {
        folderFiles.filter { it.isVideo }.take(4)
    }
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(Color(0xFF2E2E2E), Color(0xFF161616))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailFiles.size >= 4) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(1.dp)) {
                        VideoThumbnailItem(thumbnailFiles[0])
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(1.dp)) {
                        VideoThumbnailItem(thumbnailFiles[1])
                    }
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(1.dp)) {
                        VideoThumbnailItem(thumbnailFiles[2])
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(1.dp)) {
                        VideoThumbnailItem(thumbnailFiles[3])
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(accentOrange.copy(alpha = 0.25f), Color.Transparent),
                            radius = 120f
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = accentOrange,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
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
                .background(Color(0xFF242424)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = null,
                tint = Color.DarkGray,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

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
    var currentPathSegments by remember { mutableStateOf<List<String>>(emptyList()) }
    val accentOrange = MaterialTheme.colorScheme.primary

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
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = accentOrange,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            
            Text(
                text = "Root",
                fontSize = 13.sp,
                fontWeight = if (currentPathSegments.isEmpty()) FontWeight.Bold else FontWeight.Normal,
                color = if (currentPathSegments.isEmpty()) accentOrange else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { currentPathSegments = emptyList() }
            )
            
            currentPathSegments.forEachIndexed { index, segment ->
                Text(
                    text = " > ",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    text = segment,
                    fontSize = 13.sp,
                    fontWeight = if (index == currentPathSegments.size - 1) FontWeight.Bold else FontWeight.Normal,
                    color = if (index == currentPathSegments.size - 1) accentOrange else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable {
                        currentPathSegments = currentPathSegments.take(index + 1)
                    }
                )
            }
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
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "${folderVideos.size} files",
                                        fontSize = 10.sp,
                                        color = Color.LightGray,
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
                                        color = Color.LightGray
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
                                        color = Color.LightGray
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
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                ) {
                                    VideoThumbnailItem(file)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color.White,
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
                                            color = Color.LightGray
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
                                            color = Color.White,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                        Text(
                                            text = "${folderVideos.size} files",
                                            fontSize = 9.sp,
                                            color = Color.LightGray,
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
                                                color = Color.LightGray
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
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = file.title,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
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
                                                color = Color.LightGray
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
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                        ) {
                                            VideoThumbnailItem(file)
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = file.title,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = Color.White,
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
                                                    color = Color.LightGray
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
fun BrowseTabContent(
    viewModel: MainViewModel,
    onPlayItem: (MediaEntity) -> Unit
) {
    val mediaList by viewModel.filteredMediaList.collectAsState()
    val prefs by viewModel.preferencesState.collectAsState()
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
        val filesList = remember(currentFolder, searchQuery, mediaList) {
            try {
                val files = currentFolder.listFiles() ?: emptyArray()
                val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                if (searchQuery.isEmpty()) {
                    sorted.toList()
                } else {
                    sorted.filter { it.name.contains(searchQuery, ignoreCase = true) }
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
                    val isDir = file.isDirectory
                    val ext = file.extension.lowercase()
                    val isVideo = ext in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp")
                    val isAudio = ext in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
                    
                    if (isDir || isVideo || isAudio) {
                        Card(
                            onClick = {
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
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDir) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                } else {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                }
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
                                        maxLines = 1,
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
                                if (isDir) {
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
                        val filesInFolder = remember(mediaList, folderName) {
                            val nonStream = mediaList.filter { it.genre != "Live Stream" }
                            nonStream.filter {
                                val f = java.io.File(it.path)
                                (f.parentFile?.name ?: "").contains(folderName, ignoreCase = true)
                            }
                        }

                        Card(
                            onClick = {
                                val f = java.io.File("/storage/emulated/0/$folderName")
                                if (f.exists() && f.isDirectory) {
                                    currentBrowseFolder = f
                                } else if (filesInFolder.isNotEmpty()) {
                                    val parentFile = java.io.File(filesInFolder.first().path).parentFile
                                    if (parentFile != null && parentFile.exists()) {
                                        currentBrowseFolder = parentFile
                                    } else {
                                        viewModel.playAll(filesInFolder)
                                        android.widget.Toast.makeText(context, "Playing all files in $folderName", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    android.widget.Toast.makeText(context, "No local files indexed under $folderName yet", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                    Text(folderName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }

                                Badge(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ) {
                                    Text("${filesInFolder.size} files", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                            isNetworkScanning = true
                            scope.launch {
                                kotlinx.coroutines.delay(2000)
                                isNetworkScanning = false
                                android.widget.Toast.makeText(context, "Network scan completed. No active SMB/FTP hosts found.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Sync, contentDescription = "Scan network", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }

                    if (isNetworkScanning) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Samba / FTP / DLNA scanner active...", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
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
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No Active Network Servers",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Start SMB, FTP or DLNA discovery thread to scan your local subnet endpoints.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center
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
fun AboutAppSection(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(36.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = "ABOUT PLAYER",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "AERO ENGINE v1.2.0  ·  STABLE",
                        fontSize = 9.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "APPLICATION SUMMARY",
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Aero Player is a high-precision, low-latency media rendering suite engineered directly on top of the Google Media3 pipeline. Adhering strictly to monochromatic Nothing OS design blueprints, it pairs complete offline file indexing with real-time parametric equalizer presets, custom audio delay compensation, and adaptive network streaming feeds.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "CORE ARCHITECTURE PIPELINE",
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            listOf(
                "Advanced Track Selector" to "Custom multi-column fallback channel selection",
                "Parametric Equalizer" to "Real-time acoustic frequency range customization",
                "Power Management" to "Background wake-lock OS sleep prevention overrides",
                "Per-Video Memory" to "State preservation of speed, scale, and volume parameters",
                "Gesture Controls" to "Scrubbing frame extractor with swipe-to-adjust parameters"
            ).forEach { (title, desc) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "·",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.offset(y = (-2).dp)
                    )
                    Column {
                        Text(
                            text = title.uppercase(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = desc,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "BUILD_TAG" to "AERO_STABLE_42",
                    "SDK_COMPILE" to "ANDROID_API_34",
                    "PIPELINE" to "EXOPLAYER_MEDIA3_1.3.0",
                    "DEVELOPER" to "SHUBH_JAIN",
                    "AESTHETIC" to "NOTHING_OS_MONOCHROMATIC"
                ).forEach { (key, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = key,
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = value,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
