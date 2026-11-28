package com.emptysol.aeroplayer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emptysol.aeroplayer.data.database.MediaEntity
import com.emptysol.aeroplayer.data.database.TorrentTaskEntity
import com.emptysol.aeroplayer.torrent.TorrentEngineStatus
import com.emptysol.aeroplayer.ui.components.AeroStatusBadge
import com.emptysol.aeroplayer.ui.components.AeroTonalCard
import com.emptysol.aeroplayer.ui.components.AppScreenScaffold
import com.emptysol.aeroplayer.ui.theme.AeroTheme
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel
import java.io.File
import java.util.Locale

private enum class TorrentTab(val label: String) {
    DOWNLOADS("Torrents"),
    RSS("RSS Auto"),
    SCHEDULER("Speed & Schedule"),
    SECURITY("Storage & Safety")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TorrentManagerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPlayMedia: (MediaEntity) -> Unit,
    onOpenFolderInApp: (String) -> Unit = {},
    onOpenVpnManager: () -> Unit = {},
    onOpenDownloadManager: () -> Unit = {},
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
                "RSS" -> TorrentTab.RSS
                "SCHEDULER" -> TorrentTab.SCHEDULER
                "SECURITY" -> TorrentTab.SECURITY
                else -> TorrentTab.DOWNLOADS
            }
        )
    }
    var filterChip by rememberSaveable { mutableStateOf("All") }
    var showAddMagnetSheet by remember { mutableStateOf(false) }
    var showAddRssSheet by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
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
            } catch (_: Exception) {
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
            torrentToRemove != null -> torrentToRemove = null
            longPressedTorrent != null -> longPressedTorrent = null
            folderViewerTask != null -> folderViewerTask = null
            selectedTorrentForSheet != null -> selectedTorrentForSheet = null
            showAddMagnetSheet -> showAddMagnetSheet = false
            showAddRssSheet -> showAddRssSheet = false
            else -> onBack()
        }
    }

    val isVpnOn = engineStatus.vpnStatus.isVpnActive || inbuiltVpnState.isRunning
    val extendedColors = AeroTheme.extendedColors

    AppScreenScaffold(
        title = "P2P Torrent Manager",
        subtitle = "↓ ${engineStatus.totalDownloadBps / 1024} KB/s • ↑ ${engineStatus.totalUploadBps / 1024} KB/s • ${engineStatus.activePeerCount} peers",
        onNavigateBack = onBack,
        backButtonTestTag = "torrent_back_btn",
        actions = {
            // Calm VPN pill + single overflow menu (zero header clutter)
            Surface(
                onClick = onOpenVpnManager,
                shape = RoundedCornerShape(10.dp),
                color = if (isVpnOn) extendedColors.successContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.testTag("torrent_topbar_vpn_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = if (isVpnOn) Icons.Default.VpnLock else Icons.Default.VpnKey,
                        contentDescription = "Open VPN Manager",
                        tint = if (isVpnOn) extendedColors.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isVpnOn) "VPN" else "No VPN",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isVpnOn) extendedColors.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { showHeaderMenu = true },
                    modifier = Modifier.testTag("torrent_header_more_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More torrent options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showHeaderMenu,
                    onDismissRequest = { showHeaderMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Open .torrent File") },
                        leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                        onClick = {
                            showHeaderMenu = false
                            openTorrentFileLauncher.launch(arrayOf("application/x-bittorrent", "*/*"))
                        },
                        modifier = Modifier.testTag("torrent_open_file_btn")
                    )
                    DropdownMenuItem(
                        text = { Text("Resume All") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        onClick = {
                            showHeaderMenu = false
                            viewModel.resumeAllTorrents()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Pause All") },
                        leadingIcon = { Icon(Icons.Default.Pause, contentDescription = null) },
                        onClick = {
                            showHeaderMenu = false
                            viewModel.pauseAllTorrents()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Direct Download Manager") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = {
                            showHeaderMenu = false
                            onOpenDownloadManager()
                        },
                        modifier = Modifier.testTag("torrent_open_downloads_btn")
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == TorrentTab.DOWNLOADS) {
                FloatingActionButton(
                    onClick = { showAddMagnetSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("fab_add_torrent")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Magnet or Torrent")
                }
            }
        }
    ) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            edgePadding = 16.dp,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)) }
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
                    engineStatus = engineStatus,
                    filterChip = filterChip,
                    onSelectFilter = { filterChip = it },
                    onOpenAddDialog = { showAddMagnetSheet = true },
                    onOpenTorrentFile = { openTorrentFileLauncher.launch(arrayOf("application/x-bittorrent", "*/*")) },
                    viewModel = viewModel,
                    onInspectTorrent = { selectedTorrentForSheet = it },
                    onLongPressTorrent = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        longPressedTorrent = it
                    },
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
                    onOpenAddFeedDialog = { showAddRssSheet = true }
                )
            }
            TorrentTab.SCHEDULER -> {
                SchedulerAndBandwidthTab(
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
            onDeleteConfirmed = { deleteFiles ->
                viewModel.removeTorrent(freshTask.infoHash, deleteFiles = deleteFiles)
                longPressedTorrent = null
                Toast.makeText(
                    context,
                    if (deleteFiles) "Deleted torrent and downloaded files" else "Deleted torrent task (files kept)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    folderViewerTask?.let { folderTask ->
        val freshTask = torrents.find { it.infoHash == folderTask.infoHash } ?: folderTask
        TorrentFolderViewerSheet(
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

    if (showAddMagnetSheet) {
        AddTorrentBottomSheet(
            onDismiss = { showAddMagnetSheet = false },
            onPickLocalFile = {
                showAddMagnetSheet = false
                openTorrentFileLauncher.launch(arrayOf("application/x-bittorrent", "*/*"))
            },
            onSubmitLink = { inputLink ->
                viewModel.addTorrentFromInput(inputLink) { res ->
                    res.onSuccess {
                        Toast.makeText(context, "Queued: ${it.title}", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Invalid torrent URI", Toast.LENGTH_LONG).show()
                    }
                }
                showAddMagnetSheet = false
            }
        )
    }

    if (showAddRssSheet) {
        AddRssFeedBottomSheet(
            onDismiss = { showAddRssSheet = false },
            onSubmit = { title, url, includeFilter, excludeFilter, autoDl ->
                viewModel.addRssFeed(title, url, includeFilter, excludeFilter, autoDl)
                showAddRssSheet = false
            }
        )
    }

    torrentToRemove?.let { task ->
        DeleteTorrentBottomSheet(
            task = task,
            onDismiss = { torrentToRemove = null },
            onConfirmDelete = { deleteData ->
                viewModel.removeTorrent(task.infoHash, deleteFiles = deleteData)
                torrentToRemove = null
                Toast.makeText(
                    context,
                    if (deleteData) "Deleted torrent and files from storage" else "Deleted torrent task (files kept)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTorrentBottomSheet(
    onDismiss: () -> Unit,
    onPickLocalFile: () -> Unit,
    onSubmitLink: (String) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var inputLink by remember { mutableStateOf("") }
    var attemptedSubmit by remember { mutableStateOf(false) }
    val trimmed = inputLink.trim()
    val inputError = if (attemptedSubmit && trimmed.isBlank()) "Enter a magnet:? link or .torrent URL" else null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Add Torrent",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            OutlinedTextField(
                value = inputLink,
                onValueChange = {
                    inputLink = it
                    if (attemptedSubmit) attemptedSubmit = false
                },
                label = { Text("Magnet URI or .torrent URL") },
                placeholder = { Text("magnet:?xt=urn:btih:...") },
                isError = inputError != null,
                supportingText = inputError?.let { msg -> { Text(msg) } },
                trailingIcon = {
                    if (inputLink.isNotEmpty()) {
                        IconButton(onClick = { inputLink = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                                if (!clip.isNullOrBlank()) inputLink = clip
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste link")
                        }
                    }
                },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        attemptedSubmit = true
                        if (trimmed.isNotBlank()) onSubmitLink(trimmed)
                    }
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("magnet_input_field")
            )

            OutlinedButton(
                onClick = onPickLocalFile,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choose Local .torrent File")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        attemptedSubmit = true
                        if (trimmed.isNotBlank()) onSubmitLink(trimmed)
                    },
                    enabled = trimmed.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("confirm_add_magnet_btn")
                ) {
                    Text("Download", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRssFeedBottomSheet(
    onDismiss: () -> Unit,
    onSubmit: (title: String, url: String, includeFilter: String, excludeFilter: String, autoDl: Boolean) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
    var feedUrl by remember { mutableStateOf("") }
    var showAdvancedFilters by remember { mutableStateOf(false) }
    var includeFilter by remember { mutableStateOf("") }
    var excludeFilter by remember { mutableStateOf("") }
    var autoDl by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Subscribe RSS Feed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Feed Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = feedUrl,
                onValueChange = { feedUrl = it },
                label = { Text("RSS / Atom URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            AeroTonalCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { autoDl = !autoDl }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-download matches", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Automatically queue new items", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoDl, onCheckedChange = { autoDl = it })
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedFilters = !showAdvancedFilters }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Keyword & Regex Filters", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    imageVector = if (showAdvancedFilters) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle filters",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = showAdvancedFilters) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = includeFilter,
                        onValueChange = { includeFilter = it },
                        label = { Text("Include (comma or /regex/)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = excludeFilter,
                        onValueChange = { excludeFilter = it },
                        label = { Text("Exclude Keywords") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onSubmit(title, feedUrl, includeFilter, excludeFilter, autoDl) },
                    enabled = feedUrl.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text("Subscribe", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteTorrentBottomSheet(
    task: TorrentTaskEntity,
    onDismiss: () -> Unit,
    onConfirmDelete: (deleteFiles: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var deleteFilesToo by remember(task.infoHash) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("delete_torrent_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Delete Torrent",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            AeroTonalCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteFilesToo = !deleteFilesToo }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Also delete downloaded data",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (deleteFilesToo) "Removes task and permanently deletes files" else "Removes task only; files stay in storage",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = deleteFilesToo,
                        onCheckedChange = { deleteFilesToo = it },
                        modifier = Modifier.testTag("switch_delete_torrent_data")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onConfirmDelete(deleteFilesToo) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("btn_confirm_delete_torrent")
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (deleteFilesToo) "Delete + Data" else "Delete Task", fontWeight = FontWeight.SemiBold)
                }
            }
        }
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
    onDeleteConfirmed: (deleteFiles: Boolean) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
    var deleteFilesToo by remember(task.infoHash) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("torrent_long_press_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = task.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TorrentStatusPill(task.status)
                Text(
                    text = "$pct% • ${formatHumanBytes(task.downloadedBytes)} / ${formatHumanBytes(task.totalBytes)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = folderLabel,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (isActive) viewModel.pauseTorrent(task.infoHash) else viewModel.resumeTorrent(task.infoHash)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).testTag("sheet_toggle_pause_btn")
                ) {
                    Icon(if (isActive) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isActive) "Pause" else "Resume", fontWeight = FontWeight.SemiBold)
                }

                FilledTonalButton(
                    onClick = onViewFolder,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).testTag("sheet_view_folder_btn")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View Folder", fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            if (task.primaryMediaPath != null) {
                ListItem(
                    headlineContent = { Text("Stream / Play Media", fontWeight = FontWeight.Medium) },
                    leadingContent = { Icon(Icons.Default.OndemandVideo, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onStreamPlay)
                )
            }

            ListItem(
                headlineContent = { Text("Files & Priorities", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Inspect files, priorities, and transfer caps") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onInspectFiles)
                    .testTag("sheet_files_priorities_item")
            )

            ListItem(
                headlineContent = { Text(if (task.isSequential) "Sequential Mode: ON" else "Sequential Mode: OFF", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Download pieces in order for streaming") },
                leadingContent = { Icon(Icons.Default.LowPriority, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.setTorrentSequential(task.infoHash, !task.isSequential)
                        onDismiss()
                    }
            )

            ListItem(
                headlineContent = { Text("Safety Scan Files", fontWeight = FontWeight.Medium) },
                leadingContent = { Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.scanTorrentSafety(task.infoHash) { report ->
                            Toast.makeText(context, report.summary, Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    }
            )

            ListItem(
                headlineContent = { Text("Copy Magnet Link", fontWeight = FontWeight.Medium) },
                leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Magnet Link", task.sourceUri))
                        Toast.makeText(context, "Magnet link copied", Toast.LENGTH_SHORT).show()
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Queue Up", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        viewModel.moveTorrentQueueOrder(task.infoHash, 1)
                        onDismiss()
                    },
                    enabled = canMoveDown,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Queue Down", fontSize = 12.sp)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Clean single Delete Task flow with optional Delete Data switch
            AeroTonalCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteFilesToo = !deleteFilesToo },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text("Also delete downloaded data", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (deleteFilesToo) "Deletes task and removes files from storage" else "Removes task from list only",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = deleteFilesToo,
                            onCheckedChange = { deleteFilesToo = it },
                            modifier = Modifier.testTag("sheet_delete_data_switch")
                        )
                    }

                    Button(
                        onClick = { onDeleteConfirmed(deleteFilesToo) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sheet_confirm_delete_btn")
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (deleteFilesToo) "Delete Task & Data" else "Delete Task",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorrentFolderViewerSheet(
    task: TorrentTaskEntity,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onOpenInFileBrowser: (String) -> Unit,
    onPlayDiskFile: (File) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stagingDir = remember(task.saveLocation, task.title) {
        viewModel.torrentEngineManager.resolveStagingDirForTask(task)
    }
    val folderLabel = remember(task.saveLocation, task.title) {
        viewModel.torrentEngineManager.getDisplayFolderLabel(task)
    }
    var diskFiles by remember(task.infoHash, task.downloadedBytes) {
        mutableStateOf(viewModel.torrentEngineManager.listDownloadedFilesOnDisk(task))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Download Folder", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(task.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            AeroTonalCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Save Location", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
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
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Files on Disk (${diskFiles.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    diskFiles.forEach { f ->
                        AeroTonalCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatHumanBytes(f.length()), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onPlayDiskFile(f) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play file")
                                }
                                IconButton(
                                    onClick = {
                                        f.delete()
                                        diskFiles = viewModel.torrentEngineManager.listDownloadedFilesOnDisk(task)
                                        Toast.makeText(context, "Deleted ${f.name}", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete disk file", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
                Button(
                    onClick = {
                        val browseDir = if (stagingDir.exists()) stagingDir.absolutePath else (stagingDir.parentFile?.absolutePath ?: stagingDir.absolutePath)
                        onOpenInFileBrowser(browseDir)
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse Folder")
                }
            }
        }
    }
}

@Composable
private fun DownloadsDashboardTab(
    torrents: List<TorrentTaskEntity>,
    engineStatus: TorrentEngineStatus,
    filterChip: String,
    onSelectFilter: (String) -> Unit,
    onOpenAddDialog: () -> Unit,
    onOpenTorrentFile: () -> Unit,
    viewModel: MainViewModel,
    onInspectTorrent: (TorrentTaskEntity) -> Unit,
    onLongPressTorrent: (TorrentTaskEntity) -> Unit,
    onStreamPlay: (TorrentTaskEntity) -> Unit
) {
    val filters = listOf("All", "Active", "Paused", "Completed")
    val filtered = remember(torrents, filterChip) {
        when (filterChip) {
            "Active" -> torrents.filter { it.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED") }
            "Paused" -> torrents.filter { it.status in setOf("PAUSED", "VPN_PAUSED", "SCHEDULE_PAUSED", "ERROR") }
            "Completed" -> torrents.filter { it.status == "COMPLETED" || it.status == "SEEDING" }
            else -> torrents
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sleek Tonal Turbo Speed Boost Card (no heavy borders)
        AeroTonalCard(
            containerColor = if (engineStatus.speedBoostEnabled) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("torrent_speed_boost_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (engineStatus.speedBoostEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = if (engineStatus.speedBoostEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (engineStatus.speedBoostEnabled) "Turbo Speed Boost" else "Standard Speed",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (engineStatus.speedBoostEnabled) {
                            val natTag = if (engineStatus.natMapped) "UPnP" else if (engineStatus.listenPort > 0) "Port ${engineStatus.listenPort}" else "NAT"
                            "${engineStatus.pipelineDepth}-block • DHT ${engineStatus.dhtNodeCount} • $natTag • Peak ${engineStatus.peakDownloadBps / 1024} KB/s"
                        } else {
                            "Balanced 48-block pipeline • Tap to enable Turbo"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = engineStatus.speedBoostEnabled,
                    onCheckedChange = { enabled -> viewModel.setTorrentSpeedBoostEnabled(enabled) },
                    modifier = Modifier.testTag("switch_torrent_speed_boost")
                )
            }
        }

        // Single clean horizontal FilterChip row
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filters) { label ->
                FilterChip(
                    selected = filterChip == label,
                    onClick = { onSelectFilter(label) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                AeroTonalCard(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.DownloadForOffline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp)
                        )
                        Text("No torrents in \"$filterChip\"", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            "Add a magnet link or .torrent file to download or stream directly in Aero Player.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onOpenAddDialog, shape = RoundedCornerShape(12.dp)) { Text("Add Magnet") }
                            OutlinedButton(onClick = onOpenTorrentFile, shape = RoundedCornerShape(12.dp)) { Text("Open .torrent") }
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
                itemsIndexed(filtered, key = { _, item -> item.infoHash }) { _, task ->
                    TorrentItemCard(
                        task = task,
                        onTogglePause = {
                            if (task.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED")) {
                                viewModel.pauseTorrent(task.infoHash)
                            } else {
                                viewModel.resumeTorrent(task.infoHash)
                            }
                        },
                        onInspect = { onInspectTorrent(task) },
                        onLongPress = { onLongPressTorrent(task) },
                        onStream = { onStreamPlay(task) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

/**
 * Clean 3-data-point progressive-disclosure Torrent card:
 * 1) Title (1 line)
 * 2) Supporting line (progress % + speed/peers or concise error)
 * 3) Semantic status badge + slim progress bar + 1 primary quick action (Play/Pause)
 * Tap card for details & files; long-press for full action sheet & delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TorrentItemCard(
    task: TorrentTaskEntity,
    onTogglePause: () -> Unit,
    onInspect: () -> Unit,
    onLongPress: () -> Unit,
    onStream: () -> Unit
) {
    val isActive = task.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED")
    val progress = when {
        task.status == "COMPLETED" -> 1f
        task.totalBytes > 0L -> (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
        task.totalPieces > 0 -> (task.completedPieces.toFloat() / task.totalPieces.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    val pct = (progress * 100f).toInt().coerceIn(0, 100)

    val supportingLine = remember(task) {
        when {
            !task.errorMessage.isNullOrBlank() -> task.errorMessage
            task.status == "COMPLETED" -> "${formatHumanBytes(task.totalBytes)} • Ready to play"
            task.status == "METADATA" -> "Fetching metadata • ${task.peerCount} peers"
            isActive -> "$pct% • ↓ ${task.downloadRateBps / 1024} KB/s • ${task.peerCount} peers"
            else -> "$pct% • ${formatHumanBytes(task.downloadedBytes)} / ${formatHumanBytes(task.totalBytes)}"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onInspect,
                onLongClick = onLongPress
            )
            .testTag("torrent_card_${task.infoHash}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = task.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = supportingLine,
                        fontSize = 12.sp,
                        color = if (!task.errorMessage.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TorrentStatusPill(task.status)

                if (task.primaryMediaPath != null) {
                    FilledTonalIconButton(
                        onClick = onStream,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.OndemandVideo, contentDescription = "Stream media", modifier = Modifier.size(19.dp))
                    }
                }

                FilledTonalIconButton(
                    onClick = onTogglePause,
                    modifier = Modifier.size(40.dp).testTag("torrent_toggle_pause_${task.infoHash}")
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isActive) "Pause torrent" else "Resume torrent",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when (task.status) {
                    "COMPLETED" -> AeroTheme.extendedColors.success
                    "ERROR" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}

@Composable
private fun TorrentStatusPill(status: String) {
    val ext = AeroTheme.extendedColors
    val (bg, fg, label) = when (status) {
        "COMPLETED" -> Triple(ext.successContainer, ext.onSuccessContainer, "Done")
        "DOWNLOADING" -> Triple(ext.infoContainer, ext.onInfoContainer, "Active")
        "METADATA" -> Triple(ext.infoContainer, ext.onInfoContainer, "Meta")
        "SEEDING" -> Triple(ext.successContainer, ext.onSuccessContainer, "Seeding")
        "PAUSED" -> Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, "Paused")
        "VPN_PAUSED" -> Triple(ext.warningContainer, ext.onWarningContainer, "VPN Wait")
        "SCHEDULE_PAUSED" -> Triple(ext.warningContainer, ext.onWarningContainer, "Scheduled")
        "ERROR" -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Error")
        else -> Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, status)
    }
    AeroStatusBadge(text = label, containerColor = bg, contentColor = fg)
}

private fun formatHumanBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
    else String.format(Locale.US, "%.1f MB", mb)
}
