package com.emptysol.aeroplayer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emptysol.aeroplayer.data.database.MediaEntity
import com.emptysol.aeroplayer.data.database.TorrentTaskEntity
import com.emptysol.aeroplayer.ui.components.AeroStatusBadge
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel
import java.io.File
import java.util.Locale

private enum class TorrentTab(val label: String) {
    DOWNLOADS("Downloads"),
    RSS("RSS Auto"),
    SCHEDULER("Limits & Schedule"),
    REMOTE_VPN("Remote & VPN"),
    SECURITY("Security & Folder")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TorrentManagerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPlayMedia: (MediaEntity) -> Unit,
    onOpenFolderInApp: (String) -> Unit = {},
    initialTabKey: String = "DOWNLOADS"
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs by viewModel.preferencesState.collectAsState()
    val torrents by viewModel.torrentsFlow.collectAsState()
    val rssFeeds by viewModel.rssFeedsFlow.collectAsState()
    val engineStatus by viewModel.torrentEngineStatus.collectAsState()
    val inbuiltVpnState by viewModel.inbuiltVpnState.collectAsState()
    val rssPreviewMap by viewModel.rssPreviewItemsFlow.collectAsState()

    var selectedTab by rememberSaveable(initialTabKey) {
        mutableStateOf(
            when (initialTabKey.uppercase()) {
                "VPN", "REMOTE_VPN" -> TorrentTab.REMOTE_VPN
                "RSS" -> TorrentTab.RSS
                "SCHEDULER" -> TorrentTab.SCHEDULER
                "SECURITY" -> TorrentTab.SECURITY
                else -> TorrentTab.DOWNLOADS
            }
        )
    }
    var filterChip by rememberSaveable { mutableStateOf("All") }
    var showAddMagnetDialog by remember { mutableStateOf(false) }
    var showAddRssDialog by remember { mutableStateOf(false) }
    var selectedTorrentForSheet by remember { mutableStateOf<TorrentTaskEntity?>(null) }
    var longPressedTorrent by remember { mutableStateOf<TorrentTaskEntity?>(null) }
    var folderViewerTask by remember { mutableStateOf<TorrentTaskEntity?>(null) }
    var torrentToRemove by remember { mutableStateOf<TorrentTaskEntity?>(null) }

    val openTorrentFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    viewModel.addTorrentBytes(bytes, uri.toString()) { res ->
                        res.onFailure {
                            Toast.makeText(context, it.message ?: "Invalid .torrent file", Toast.LENGTH_LONG).show()
                        }.onSuccess {
                            Toast.makeText(context, "Added ${it.title}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to read .torrent file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val openDownloadTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            } catch (_: Exception) {
            }
            val label = treeUri.lastPathSegment?.substringAfterLast(':') ?: "Selected SAF Folder"
            viewModel.updateTorrentDownloadFolder(treeUri.toString(), "Folder: $label")
            Toast.makeText(context, "Download folder updated: $label", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler {
        when {
            longPressedTorrent != null -> longPressedTorrent = null
            folderViewerTask != null -> folderViewerTask = null
            selectedTorrentForSheet != null -> selectedTorrentForSheet = null
            else -> onBack()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "P2P Torrent Manager",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "↓ ${engineStatus.totalDownloadBps / 1024} KB/s • ↑ ${engineStatus.totalUploadBps / 1024} KB/s • ${engineStatus.vpnStatus.transportSummary}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("torrent_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val isVpnOn = engineStatus.vpnStatus.isVpnActive || inbuiltVpnState.isRunning
                    FilterChip(
                        selected = selectedTab == TorrentTab.REMOTE_VPN || isVpnOn,
                        onClick = {
                            selectedTab = if (selectedTab == TorrentTab.REMOTE_VPN) {
                                TorrentTab.DOWNLOADS
                            } else {
                                TorrentTab.REMOTE_VPN
                            }
                        },
                        label = { Text(if (isVpnOn) "VPN ON" else "VPN", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isVpnOn) Icons.Default.VpnLock else Icons.Default.VpnKey,
                                contentDescription = "VPN Hub",
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .testTag("torrent_topbar_vpn_pill")
                    )
                    IconButton(
                        onClick = { openTorrentFileLauncher.launch(arrayOf("application/x-bittorrent", "*/*")) },
                        modifier = Modifier.testTag("torrent_open_file_btn")
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Open .torrent file", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = { showAddMagnetDialog = true },
                        modifier = Modifier.testTag("torrent_add_magnet_btn")
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = "Add Magnet Link", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (selectedTab == TorrentTab.DOWNLOADS) {
                FloatingActionButton(
                    onClick = { showAddMagnetDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("fab_add_torrent")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Magnet or Torrent")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                edgePadding = 12.dp
            ) {
                TorrentTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label, fontSize = 13.sp, maxLines = 1) },
                        modifier = Modifier.testTag("torrent_tab_${tab.name.lowercase()}")
                    )
                }
            }

            when (selectedTab) {
                TorrentTab.DOWNLOADS -> {
                    DownloadsDashboardTab(
                        torrents = torrents,
                        filterChip = filterChip,
                        onSelectFilter = { filterChip = it },
                        onOpenAddDialog = { showAddMagnetDialog = true },
                        onOpenTorrentFile = { openTorrentFileLauncher.launch(arrayOf("application/x-bittorrent", "*/*")) },
                        viewModel = viewModel,
                        onInspectTorrent = { selectedTorrentForSheet = it },
                        onLongPressTorrent = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            longPressedTorrent = it
                        },
                        onViewFolder = { folderViewerTask = it },
                        onRequestRemove = { torrentToRemove = it },
                        onStreamPlay = { task ->
                            val media = viewModel.torrentEngineManager.getPlayableMediaEntity(task)
                            if (media != null) {
                                onPlayMedia(media)
                            } else {
                                Toast.makeText(context, "Buffering initial pieces — try again shortly", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                TorrentTab.RSS -> {
                    RssAutoDownloaderTab(
                        viewModel = viewModel,
                        feeds = rssFeeds,
                        previewMap = rssPreviewMap,
                        onOpenAddFeedDialog = { showAddRssDialog = true }
                    )
                }
                TorrentTab.SCHEDULER -> {
                    SchedulerAndBandwidthTab(
                        viewModel = viewModel,
                        prefs = prefs,
                        engineStatus = engineStatus
                    )
                }
                TorrentTab.REMOTE_VPN -> {
                    RemoteAndVpnTab(
                        viewModel = viewModel,
                        prefs = prefs,
                        engineStatus = engineStatus
                    )
                }
                TorrentTab.SECURITY -> {
                    SecurityAndStorageTab(
                        viewModel = viewModel,
                        prefs = prefs,
                        torrents = torrents,
                        onPickDownloadFolder = { openDownloadTreeLauncher.launch(null) }
                    )
                }
            }
        }
    }

    selectedTorrentForSheet?.let { activeTask ->
        val freshTask = torrents.find { it.infoHash == activeTask.infoHash } ?: activeTask
        TorrentDetailsBottomSheet(
            task = freshTask,
            viewModel = viewModel,
            onDismiss = { selectedTorrentForSheet = null },
            onOpenFolder = { folderTask ->
                selectedTorrentForSheet = null
                folderViewerTask = folderTask
            },
            onStreamPlay = { task ->
                val media = viewModel.torrentEngineManager.getPlayableMediaEntity(task)
                if (media != null) {
                    selectedTorrentForSheet = null
                    onPlayMedia(media)
                } else {
                    Toast.makeText(context, "Waiting for first media pieces...", Toast.LENGTH_SHORT).show()
                }
            },
            onPlayFileMedia = { media ->
                selectedTorrentForSheet = null
                onPlayMedia(media)
            }
        )
    }

    longPressedTorrent?.let { pressedTask ->
        val freshTask = torrents.find { it.infoHash == pressedTask.infoHash } ?: pressedTask
        val index = torrents.indexOfFirst { it.infoHash == freshTask.infoHash }
        TorrentLongPressBottomSheet(
            task = freshTask,
            viewModel = viewModel,
            canMoveUp = index > 0,
            canMoveDown = index in 0 until torrents.lastIndex,
            onDismiss = { longPressedTorrent = null },
            onInspectFiles = {
                longPressedTorrent = null
                selectedTorrentForSheet = freshTask
            },
            onViewFolder = {
                longPressedTorrent = null
                folderViewerTask = freshTask
            },
            onStreamPlay = {
                val media = viewModel.torrentEngineManager.getPlayableMediaEntity(freshTask)
                if (media != null) {
                    longPressedTorrent = null
                    onPlayMedia(media)
                } else {
                    Toast.makeText(context, "Buffering initial pieces — try again shortly", Toast.LENGTH_SHORT).show()
                }
            },
            onDeleteNameOnly = {
                viewModel.removeTorrent(freshTask.infoHash, deleteFiles = false)
                longPressedTorrent = null
                Toast.makeText(context, "Removed from list (files kept in folder)", Toast.LENGTH_SHORT).show()
            },
            onDeleteWithData = {
                viewModel.removeTorrent(freshTask.infoHash, deleteFiles = true)
                longPressedTorrent = null
                Toast.makeText(context, "Deleted torrent and stored data", Toast.LENGTH_SHORT).show()
            }
        )
    }

    folderViewerTask?.let { folderTask ->
        val freshTask = torrents.find { it.infoHash == folderTask.infoHash } ?: folderTask
        TorrentFolderViewerDialog(
            task = freshTask,
            viewModel = viewModel,
            onDismiss = { folderViewerTask = null },
            onOpenInFileBrowser = { dirPath ->
                folderViewerTask = null
                onOpenFolderInApp(dirPath)
            },
            onPlayDiskFile = { file ->
                val media = viewModel.torrentEngineManager.getPlayableMediaForDiskFile(file, freshTask.title)
                if (media != null) {
                    folderViewerTask = null
                    onPlayMedia(media)
                } else {
                    Toast.makeText(context, "File is still empty or unsupported", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showAddMagnetDialog) {
        var inputLink by remember { mutableStateOf("") }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { showAddMagnetDialog = false },
            title = { Text("Add Magnet or .torrent URL", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = inputLink,
                        onValueChange = { inputLink = it },
                        label = { Text("Magnet URI or https://...torrent") },
                        placeholder = { Text("magnet:?xt=urn:btih:...") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("magnet_input_field")
                    )
                    OutlinedButton(
                        onClick = {
                            showAddMagnetDialog = false
                            openTorrentFileLauncher.launch(arrayOf("application/x-bittorrent", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pick Local .torrent File Instead")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addTorrentFromInput(inputLink) { res ->
                            res.onSuccess {
                                Toast.makeText(context, "Queued: ${it.title}", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, it.message ?: "Invalid torrent URI", Toast.LENGTH_LONG).show()
                            }
                        }
                        showAddMagnetDialog = false
                    },
                    enabled = inputLink.isNotBlank(),
                    modifier = Modifier.testTag("confirm_add_magnet_btn")
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMagnetDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddRssDialog) {
        var title by remember { mutableStateOf("") }
        var feedUrl by remember { mutableStateOf("") }
        var includeFilter by remember { mutableStateOf("") }
        var excludeFilter by remember { mutableStateOf("") }
        var autoDl by remember { mutableStateOf(true) }

        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { showAddRssDialog = false },
            title = { Text("Subscribe RSS Feed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Feed Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = feedUrl, onValueChange = { feedUrl = it }, label = { Text("RSS / Atom URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = includeFilter, onValueChange = { includeFilter = it }, label = { Text("Include Filters (comma or /regex/)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = excludeFilter, onValueChange = { excludeFilter = it }, label = { Text("Exclude Filters") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoDl, onCheckedChange = { autoDl = it })
                        Text("Auto-download matching releases", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addRssFeed(title, feedUrl, includeFilter, excludeFilter, autoDl)
                        showAddRssDialog = false
                    },
                    enabled = feedUrl.isNotBlank()
                ) {
                    Text("Subscribe")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRssDialog = false }) { Text("Cancel") }
            }
        )
    }

    torrentToRemove?.let { task ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { torrentToRemove = null },
            title = { Text("Delete Torrent", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(task.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "Choose whether to remove only the torrent entry or also delete downloaded files from storage.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            viewModel.removeTorrent(task.infoHash, deleteFiles = false)
                            torrentToRemove = null
                            Toast.makeText(context, "Deleted torrent entry (files kept)", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("btn_delete_torrent_name_only")
                    ) {
                        Icon(Icons.Default.PlaylistRemove, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete File Name Only", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            viewModel.removeTorrent(task.infoHash, deleteFiles = true)
                            torrentToRemove = null
                            Toast.makeText(context, "Deleted torrent and files from storage", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().testTag("btn_delete_torrent_with_data")
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete File + Data", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { torrentToRemove = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorrentLongPressBottomSheet(
    task: TorrentTaskEntity,
    viewModel: MainViewModel,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onInspectFiles: () -> Unit,
    onViewFolder: () -> Unit,
    onStreamPlay: () -> Unit,
    onDeleteNameOnly: () -> Unit,
    onDeleteWithData: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val isActive = task.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED")
    val folderLabel = remember(task.saveLocation, task.title) {
        viewModel.torrentEngineManager.getDisplayFolderLabel(task)
    }
    val pct = remember(task.downloadedBytes, task.totalBytes, task.completedPieces, task.totalPieces) {
        when {
            task.totalBytes > 0L -> ((task.downloadedBytes * 100L) / task.totalBytes).toInt().coerceIn(0, 100)
            task.totalPieces > 0 -> ((task.completedPieces * 100) / task.totalPieces).coerceIn(0, 100)
            else -> 0
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                BottomSheetDefaults.DragHandle(modifier = Modifier.padding(vertical = 0.dp))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("torrent_long_press_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = task.title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AeroStatusBadge(task.status, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = "$pct% • ${formatHumanBytes(task.downloadedBytes)} / ${formatHumanBytes(task.totalBytes)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Folder: $folderLabel",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (isActive) viewModel.pauseTorrent(task.infoHash) else viewModel.resumeTorrent(task.infoHash)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).testTag("sheet_toggle_pause_btn")
                ) {
                    Icon(if (isActive) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isActive) "Pause" else "Resume", fontWeight = FontWeight.SemiBold)
                }

                FilledTonalButton(
                    onClick = onViewFolder,
                    modifier = Modifier.weight(1f).testTag("sheet_view_folder_btn")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View Folder", fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (task.primaryMediaPath != null) {
                    ListItem(
                        headlineContent = { Text("Stream / Play Video", fontWeight = FontWeight.SemiBold) },
                        leadingContent = { Icon(Icons.Default.OndemandVideo, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onStreamPlay)
                    )
                }

                ListItem(
                    headlineContent = { Text("Files & Priorities", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Pause, resume, or remove individual files") },
                    leadingContent = { Icon(Icons.Default.ListAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onInspectFiles)
                        .testTag("sheet_files_priorities_item")
                )

                ListItem(
                    headlineContent = { Text(if (task.isSequential) "Sequential Mode: ON" else "Sequential Mode: OFF", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Download pieces in order for streaming") },
                    leadingContent = { Icon(Icons.Default.LowPriority, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.setTorrentSequential(task.infoHash, !task.isSequential)
                            onDismiss()
                        }
                )

                ListItem(
                    headlineContent = { Text("Safety Scan Files", fontWeight = FontWeight.SemiBold) },
                    leadingContent = { Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.scanTorrentSafety(task.infoHash) { report ->
                                Toast.makeText(context, report.summary, Toast.LENGTH_SHORT).show()
                            }
                            onDismiss()
                        }
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.moveTorrentQueueOrder(task.infoHash, -1)
                            onDismiss()
                        },
                        enabled = canMoveUp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Move Up", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.moveTorrentQueueOrder(task.infoHash, 1)
                            onDismiss()
                        },
                        enabled = canMoveDown,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Move Down", fontSize = 12.sp)
                    }
                }

                ListItem(
                    headlineContent = { Text("Copy Magnet Link", fontWeight = FontWeight.SemiBold) },
                    leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Magnet Link", task.sourceUri))
                            Toast.makeText(context, "Magnet link copied", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                )

                HorizontalDivider()
                Text("Delete Options", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)

                OutlinedButton(
                    onClick = onDeleteNameOnly,
                    modifier = Modifier.fillMaxWidth().testTag("sheet_delete_name_only_btn")
                ) {
                    Icon(Icons.Default.PlaylistRemove, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete File Name Only (Keep Data)", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onDeleteWithData,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().testTag("sheet_delete_with_data_btn")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete File + Data", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun TorrentFolderViewerDialog(
    task: TorrentTaskEntity,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onOpenInFileBrowser: (String) -> Unit,
    onPlayDiskFile: (File) -> Unit
) {
    val context = LocalContext.current
    val stagingDir = remember(task.saveLocation, task.title) {
        viewModel.torrentEngineManager.resolveStagingDirForTask(task)
    }
    val folderLabel = remember(task.saveLocation, task.title) {
        viewModel.torrentEngineManager.getDisplayFolderLabel(task)
    }
    var diskFiles by remember(task.infoHash, task.downloadedBytes) {
        mutableStateOf(viewModel.torrentEngineManager.listDownloadedFilesOnDisk(task))
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Download Folder", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                Text(task.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Save Location", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(folderLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            stagingDir.absolutePath,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (diskFiles.isEmpty()) {
                    Text(
                        "No downloaded files found in this folder yet. Files appear as pieces finish downloading.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Files in Folder (${diskFiles.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        diskFiles.forEach { f ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(f.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatHumanBytes(f.length()), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { onPlayDiskFile(f) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play file", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            f.delete()
                                            diskFiles = viewModel.torrentEngineManager.listDownloadedFilesOnDisk(task)
                                            Toast.makeText(context, "Deleted ${f.name}", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete disk file", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val browseDir = if (stagingDir.exists()) stagingDir.absolutePath else (stagingDir.parentFile?.absolutePath ?: stagingDir.absolutePath)
                    onOpenInFileBrowser(browseDir)
                }
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Browse Folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun DownloadsDashboardTab(
    torrents: List<TorrentTaskEntity>,
    filterChip: String,
    onSelectFilter: (String) -> Unit,
    onOpenAddDialog: () -> Unit,
    onOpenTorrentFile: () -> Unit,
    viewModel: MainViewModel,
    onInspectTorrent: (TorrentTaskEntity) -> Unit,
    onLongPressTorrent: (TorrentTaskEntity) -> Unit,
    onViewFolder: (TorrentTaskEntity) -> Unit,
    onRequestRemove: (TorrentTaskEntity) -> Unit,
    onStreamPlay: (TorrentTaskEntity) -> Unit
) {
    val filters = listOf("All", "Active", "Paused", "Completed")
    val filtered = remember(torrents, filterChip) {
        when (filterChip) {
            "Active" -> torrents.filter { it.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED") }
            "Paused" -> torrents.filter { it.status in setOf("PAUSED", "VPN_PAUSED", "SCHEDULE_PAUSED") }
            "Completed" -> torrents.filter { it.status == "COMPLETED" || it.status == "SEEDING" }
            else -> torrents
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Bulk controls + filter row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            filters.forEach { label ->
                FilterChip(
                    selected = filterChip == label,
                    onClick = { onSelectFilter(label) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(
                onClick = { viewModel.resumeAllTorrents() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Resume All", fontSize = 11.sp)
            }
            FilledTonalButton(
                onClick = { viewModel.pauseAllTorrents() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Pause All", fontSize = 11.sp)
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.DownloadForOffline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                        Text("No torrents in \"$filterChip\"", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Paste a magnet link or open a .torrent file to download or stream media directly into Aero Player.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onOpenAddDialog) { Text("Paste Magnet") }
                            OutlinedButton(onClick = onOpenTorrentFile) { Text("Open .torrent") }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(filtered, key = { _, item -> item.infoHash }) { index, task ->
                    val folderLabel = remember(task.saveLocation, task.title) {
                        viewModel.torrentEngineManager.getDisplayFolderLabel(task)
                    }
                    TorrentItemCard(
                        task = task,
                        folderLabel = folderLabel,
                        canMoveUp = index > 0,
                        canMoveDown = index < filtered.lastIndex,
                        onMoveUp = { viewModel.moveTorrentQueueOrder(task.infoHash, -1) },
                        onMoveDown = { viewModel.moveTorrentQueueOrder(task.infoHash, 1) },
                        onTogglePause = {
                            if (task.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED")) {
                                viewModel.pauseTorrent(task.infoHash)
                            } else {
                                viewModel.resumeTorrent(task.infoHash)
                            }
                        },
                        onInspect = { onInspectTorrent(task) },
                        onLongPress = { onLongPressTorrent(task) },
                        onViewFolder = { onViewFolder(task) },
                        onRemove = { onRequestRemove(task) },
                        onStream = { onStreamPlay(task) }
                    )
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TorrentItemCard(
    task: TorrentTaskEntity,
    folderLabel: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onTogglePause: () -> Unit,
    onInspect: () -> Unit,
    onLongPress: () -> Unit,
    onViewFolder: () -> Unit,
    onRemove: () -> Unit,
    onStream: () -> Unit
) {
    val progress = if (task.totalBytes > 0L) {
        (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else if (task.totalPieces > 0) {
        (task.completedPieces.toFloat() / task.totalPieces.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val pct = (progress * 100f).toInt().coerceIn(0, 100)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onInspect,
                onLongClick = onLongPress
            )
            .testTag("torrent_card_${task.infoHash}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(task.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$pct% • ${formatHumanBytes(task.downloadedBytes)} / ${formatHumanBytes(task.totalBytes)} • Peers ${task.peerCount} (${task.seedCount} seeds)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AeroStatusBadge(
                    text = task.status,
                    containerColor = when (task.status) {
                        "COMPLETED" -> MaterialTheme.colorScheme.secondaryContainer
                        "ERROR", "VPN_PAUSED" -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onViewFolder,
                        onLongClick = onLongPress
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Folder, contentDescription = "View folder", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = folderLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "↓ ${task.downloadRateBps / 1024} KB/s  ↑ ${task.uploadRateBps / 1024} KB/s",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.primaryMediaPath != null) {
                        FilledTonalIconButton(onClick = onStream, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.OndemandVideo, contentDescription = "Stream video", modifier = Modifier.size(17.dp))
                        }
                    }
                    IconButton(onClick = onViewFolder, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "View folder", modifier = Modifier.size(17.dp))
                    }
                    IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Queue up", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Queue down", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onTogglePause, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = if (task.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED")) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Pause or resume"
                        )
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Remove torrent", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun formatHumanBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
    else String.format(Locale.US, "%.1f MB", mb)
}
