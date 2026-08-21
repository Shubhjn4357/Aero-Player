package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.PreferenceEntity
import com.example.ui.viewmodel.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class SettingsSubScreen {
    INTERFACE,
    VIDEO,
    SUBTITLES,
    AUDIO,
    EQUALIZER,
    CASTING,
    PARENTAL_CONTROL,
    ADVANCED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val prefs by viewModel.preferencesState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentSubScreen by remember { mutableStateOf<SettingsSubScreen?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // Dialog states
    var showAboutDialog by remember { mutableStateOf(false) }
    var showFoldersDialog by remember { mutableStateOf(false) }
    var showBgPipDialog by remember { mutableStateOf(false) }
    var showHwAccelDialog by remember { mutableStateOf(false) }
    var showOrientationDialog by remember { mutableStateOf(false) }
    var showMeteredNetworkDialog by remember { mutableStateOf(false) }
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showColorPickerDialog by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var currentColorForPicker by remember { mutableStateOf("#FFFFFF") }

    BackHandler {
        if (isSearching) {
            isSearching = false
            searchQuery = ""
        } else if (currentSubScreen != null) {
            currentSubScreen = null
        } else {
            onBack()
        }
    }

    if (showAboutDialog) {
        AboutScreen(onBack = { showAboutDialog = false })
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        if (isSearching) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search settings...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = when (currentSubScreen) {
                                    SettingsSubScreen.INTERFACE -> "Interface"
                                    SettingsSubScreen.VIDEO -> "Video"
                                    SettingsSubScreen.SUBTITLES -> "Subtitles"
                                    SettingsSubScreen.AUDIO -> "Audio"
                                    SettingsSubScreen.EQUALIZER -> "Equalizer"
                                    SettingsSubScreen.CASTING -> "Casting"
                                    SettingsSubScreen.PARENTAL_CONTROL -> "Parental control"
                                    SettingsSubScreen.ADVANCED -> "Advanced"
                                    null -> "Settings"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isSearching) {
                                isSearching = false
                                searchQuery = ""
                            } else if (currentSubScreen != null) {
                                currentSubScreen = null
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            isSearching = !isSearching
                            if (!isSearching) searchQuery = ""
                        }) {
                            Icon(
                                imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (searchQuery.isNotBlank()) {
                    // Global settings search results
                    SettingsSearchResults(
                        query = searchQuery,
                        prefs = prefs,
                        viewModel = viewModel,
                        onNavigate = { subScreen ->
                            currentSubScreen = subScreen
                            isSearching = false
                            searchQuery = ""
                        }
                    )
                } else {
                    when (currentSubScreen) {
                        null -> {
                            // Main Settings screen (Screenshots 1 & 2)
                            MainSettingsContent(
                                prefs = prefs,
                                viewModel = viewModel,
                                onOpenFoldersDialog = { showFoldersDialog = true },
                                onOpenBgPipDialog = { showBgPipDialog = true },
                                onOpenHwAccelDialog = { showHwAccelDialog = true },
                                onOpenOrientationDialog = { showOrientationDialog = true },
                                onOpenMeteredNetworkDialog = { showMeteredNetworkDialog = true },
                                onOpenPermissionsDialog = { showPermissionsDialog = true },
                                onNavigateTo = { currentSubScreen = it },
                                onOpenAbout = { showAboutDialog = true }
                            )
                        }
                        SettingsSubScreen.VIDEO -> {
                            // Video Sub-screen (Screenshot 3)
                            VideoSettingsSubScreen(
                                prefs = prefs,
                                viewModel = viewModel
                            )
                        }
                        SettingsSubScreen.INTERFACE -> {
                            // Interface Sub-screen (Screenshot 4)
                            InterfaceSettingsSubScreen(
                                prefs = prefs,
                                viewModel = viewModel
                            )
                        }
                        SettingsSubScreen.SUBTITLES -> {
                            // Subtitles Sub-screen (Screenshot 5)
                            SubtitlesSettingsSubScreen(
                                prefs = prefs,
                                viewModel = viewModel,
                                onPickColor = { initialColor, callback ->
                                    currentColorForPicker = initialColor
                                    showColorPickerDialog = callback
                                }
                            )
                        }
                        SettingsSubScreen.AUDIO -> {
                            // Audio Sub-screen (Screenshot 6)
                            AudioSettingsSubScreen(
                                prefs = prefs,
                                viewModel = viewModel
                            )
                        }
                        SettingsSubScreen.ADVANCED -> {
                            // Advanced & Application data Sub-screen (Screenshot 1 & Screenshot 7)
                            AdvancedSettingsSubScreen(
                                prefs = prefs,
                                viewModel = viewModel,
                                onRestart = { showRestartDialog = true },
                                onClearData = { showClearDataDialog = true },
                                onExport = { showExportDialog = true },
                                onRestore = { showRestoreDialog = true }
                            )
                        }
                        SettingsSubScreen.EQUALIZER -> {
                            EqualizerSettingsSubScreen(viewModel = viewModel)
                        }
                        SettingsSubScreen.CASTING -> {
                            CastingSettingsSubScreen(prefs = prefs, viewModel = viewModel)
                        }
                        SettingsSubScreen.PARENTAL_CONTROL -> {
                            ParentalControlSettingsSubScreen(prefs = prefs, viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    // DIALOGS

    // 1. Media Folders Dialog
    if (showFoldersDialog) {
        val allFolders = listOf("Internal Storage", "Movies", "Download", "DCIM", "Music", "WhatsApp", "Documents", "Podcasts", "Bluetooth")
        var selectedFolders by remember {
            mutableStateOf(
                try {
                    val arr = JSONArray(prefs.mediaLibraryFoldersJson)
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                } catch (e: Exception) {
                    allFolders.toSet()
                }
            )
        }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { showFoldersDialog = false },
            title = { Text("Media library folders", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Select directories to include in media library:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    allFolders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedFolders = if (selectedFolders.contains(folder)) {
                                        selectedFolders - folder
                                    } else {
                                        selectedFolders + folder
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text(folder, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                            }
                            Switch(
                                checked = selectedFolders.contains(folder),
                                onCheckedChange = { checked ->
                                    selectedFolders = if (checked) selectedFolders + folder else selectedFolders - folder
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val jsonArray = JSONArray(selectedFolders)
                    viewModel.updateMediaLibraryFolders(jsonArray.toString())
                    viewModel.scanLocalMedia()
                    Toast.makeText(context, "Media folders updated and scanning...", Toast.LENGTH_SHORT).show()
                    showFoldersDialog = false
                }) {
                    Text("Save & Rescan", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFoldersDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // 2. Background / PiP Dialog
    if (showBgPipDialog) {
        val options = listOf(
            "STOP_PLAYBACK" to "Stop playback",
            "PLAY_BACKGROUND_AUDIO" to "Play in background",
            "LAUNCH_PIP_MODE" to "Play in Picture-in-Picture mode"
        )
        SingleChoiceOptionDialog(
            title = "Background/PiP mode",
            options = options.map { it.second },
            selectedOption = options.find { it.first == prefs.backgroundMode }?.second ?: "Stop playback",
            onDismiss = { showBgPipDialog = false },
            onSelect = { selectedText ->
                val chosenKey = options.find { it.second == selectedText }?.first ?: "STOP_PLAYBACK"
                viewModel.updateBackgroundMode(chosenKey)
                showBgPipDialog = false
            }
        )
    }

    // 3. Hardware Acceleration Dialog
    if (showHwAccelDialog) {
        val options = listOf("Automatic", "Disabled", "Decoding acceleration", "Full acceleration")
        val currentLabel = when (prefs.hwAcceleration) {
            "Disabled" -> "Disabled"
            "Decoding" -> "Decoding acceleration"
            "Full" -> "Full acceleration"
            else -> "Automatic"
        }
        SingleChoiceOptionDialog(
            title = "Hardware Acceleration",
            options = options,
            selectedOption = currentLabel,
            onDismiss = { showHwAccelDialog = false },
            onSelect = { selectedText ->
                val chosenKey = when (selectedText) {
                    "Disabled" -> "Disabled"
                    "Decoding acceleration" -> "Decoding"
                    "Full acceleration" -> "Full"
                    else -> "Automatic"
                }
                viewModel.updateHwAcceleration(chosenKey)
                showHwAccelDialog = false
            }
        )
    }

    // 4. Video Screen Orientation Dialog
    if (showOrientationDialog) {
        val options = listOf(
            "Automatic (sensor)",
            "Locked at start",
            "Landscape",
            "Portrait",
            "Reverse landscape",
            "Reverse portrait"
        )
        SingleChoiceOptionDialog(
            title = "Video screen orientation",
            options = options,
            selectedOption = prefs.defaultOrientation,
            onDismiss = { showOrientationDialog = false },
            onSelect = { selectedText ->
                viewModel.updateDefaultOrientation(selectedText)
                showOrientationDialog = false
            }
        )
    }

    // 5. Metered Network Action Dialog
    if (showMeteredNetworkDialog) {
        val options = listOf(
            "Warn me (the warning may be missed for audio playback)",
            "Block streaming",
            "Allow streaming"
        )
        SingleChoiceOptionDialog(
            title = "Action for streams when connection is metered",
            options = options,
            selectedOption = prefs.meteredNetworkAction,
            onDismiss = { showMeteredNetworkDialog = false },
            onSelect = { selectedText ->
                viewModel.updateMeteredNetworkAction(selectedText)
                showMeteredNetworkDialog = false
            }
        )
    }

    // 6. Permissions List Dialog
    if (showPermissionsDialog) {
        PermissionsListDialog(
            context = context,
            onDismiss = { showPermissionsDialog = false }
        )
    }

    // 7. Color Picker Dialog
    if (showColorPickerDialog != null) {
        ColorPickerDialog(
            initialColor = currentColorForPicker,
            onDismiss = { showColorPickerDialog = null },
            onColorSelected = { selectedHex ->
                showColorPickerDialog?.invoke(selectedHex)
                showColorPickerDialog = null
            }
        )
    }

    // 8. Clear App Data Dialog
    if (showClearDataDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Clear all app data?", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) },
            text = { Text("This will clear all history, cached media items, and reset all settings to defaults.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.clearPlaybackHistory()
                        viewModel.preferenceRepository.updatePreferences(PreferenceEntity())
                        viewModel.scanLocalMedia()
                        Toast.makeText(context, "App data cleared and reset to defaults", Toast.LENGTH_SHORT).show()
                        showClearDataDialog = false
                    }
                }) {
                    Text("Clear All Data", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // 9. Restart App Dialog
    if (showRestartDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { showRestartDialog = false },
            title = { Text("Quit and restart application?", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
            text = { Text("This will refresh all background services, player codecs, and reload the media database.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    Toast.makeText(context, "Restarting player services...", Toast.LENGTH_SHORT).show()
                    viewModel.scanLocalMedia()
                }) {
                    Text("Restart", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // 10. Export Settings Dialog
    if (showExportDialog) {
        val settingsJson = remember {
            JSONObject().apply {
                put("themeMode", prefs.themeMode)
                put("hwAcceleration", prefs.hwAcceleration)
                put("defaultOrientation", prefs.defaultOrientation)
                put("backgroundMode", prefs.backgroundMode)
                put("alwaysFastSeek", prefs.alwaysFastSeek)
                put("matchDisplayFrameRate", prefs.matchDisplayFrameRate)
                put("subtitleSize", prefs.subtitleSize)
                put("subtitleColor", prefs.subtitleTextColor)
                put("subtitleEncoding", prefs.subtitleEncoding)
                put("detectHeadset", prefs.detectHeadset)
                put("networkCachingMs", prefs.networkCachingMs)
                put("preferSmb1", prefs.preferSmb1)
            }.toString(2)
        }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export settings", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Settings exported successfully. You can copy the configuration JSON below:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(settingsJson, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("VLC Settings", settingsJson)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Settings copied to clipboard!", Toast.LENGTH_SHORT).show()
                    showExportDialog = false
                }) {
                    Text("Copy to Clipboard", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // 11. Restore Settings Dialog
    if (showRestoreDialog) {
        var restoreText by remember { mutableStateOf("") }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restore settings", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste your exported configuration JSON to restore:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    OutlinedTextField(
                        value = restoreText,
                        onValueChange = { restoreText = it },
                        placeholder = { Text("Paste JSON here...", color = MaterialTheme.colorScheme.outline) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val json = JSONObject(restoreText.ifBlank { "{}" })
                        scope.launch {
                            val current = prefs
                            val updated = current.copy(
                                themeMode = json.optString("themeMode", current.themeMode),
                                hwAcceleration = json.optString("hwAcceleration", current.hwAcceleration),
                                defaultOrientation = json.optString("defaultOrientation", current.defaultOrientation),
                                backgroundMode = json.optString("backgroundMode", current.backgroundMode),
                                alwaysFastSeek = json.optBoolean("alwaysFastSeek", current.alwaysFastSeek),
                                matchDisplayFrameRate = json.optBoolean("matchDisplayFrameRate", current.matchDisplayFrameRate),
                                subtitleSize = json.optDouble("subtitleSize", current.subtitleSize.toDouble()).toFloat(),
                                subtitleTextColor = json.optString("subtitleColor", current.subtitleTextColor),
                                subtitleEncoding = json.optString("subtitleEncoding", current.subtitleEncoding),
                                detectHeadset = json.optBoolean("detectHeadset", current.detectHeadset),
                                networkCachingMs = json.optInt("networkCachingMs", current.networkCachingMs),
                                preferSmb1 = json.optBoolean("preferSmb1", current.preferSmb1)
                            )
                            viewModel.preferenceRepository.updatePreferences(updated)
                            Toast.makeText(context, "Settings restored successfully!", Toast.LENGTH_SHORT).show()
                            showRestoreDialog = false
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Invalid JSON format", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Restore", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

// -------------------------------------------------------------
// MAIN SETTINGS CONTENT (Matches Screenshots 1 & 2)
// -------------------------------------------------------------
@Composable
private fun MainSettingsContent(
    prefs: PreferenceEntity,
    viewModel: MainViewModel,
    onOpenFoldersDialog: () -> Unit,
    onOpenBgPipDialog: () -> Unit,
    onOpenHwAccelDialog: () -> Unit,
    onOpenOrientationDialog: () -> Unit,
    onOpenMeteredNetworkDialog: () -> Unit,
    onOpenPermissionsDialog: () -> Unit,
    onNavigateTo: (SettingsSubScreen) -> Unit,
    onOpenAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Section: Media library
        SettingsSectionHeader(title = "Media library")

        SettingsClickableItem(
            title = "Media library folders",
            subtitle = "Select directories to include in the media library",
            onClick = onOpenFoldersDialog
        )

        SettingsCheckboxItem(
            title = "Auto rescan",
            subtitle = "Automatically scan device for new or deleted media at application startup",
            checked = prefs.autoScanEnabled,
            onCheckedChange = { viewModel.updateAutoScan(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Video
        SettingsSectionHeader(title = "Video")

        val bgModeLabel = when (prefs.backgroundMode) {
            "PLAY_BACKGROUND_AUDIO" -> "Play in background"
            "LAUNCH_PIP_MODE" -> "Play in Picture-in-Picture mode"
            else -> "Stop playback"
        }
        SettingsClickableItem(
            title = "Background/PiP mode",
            subtitle = "Select VLC behaviour when you switch to other application from video playback\nCurrent: $bgModeLabel",
            onClick = onOpenBgPipDialog
        )

        val hwAccelSubtitle = "Disabled: better stability\nDecoding: may improve performance\nFull: may improve performance further"
        SettingsClickableItem(
            title = "Hardware Acceleration",
            subtitle = hwAccelSubtitle,
            onClick = onOpenHwAccelDialog
        )

        SettingsClickableItem(
            title = "Video screen orientation",
            subtitle = prefs.defaultOrientation,
            onClick = onOpenOrientationDialog
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Network
        SettingsSectionHeader(title = "Network")

        SettingsClickableItem(
            title = "Action for streams when the connection is meter..",
            subtitle = prefs.meteredNetworkAction,
            onClick = onOpenMeteredNetworkDialog
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Permissions
        SettingsSectionHeader(title = "Permissions")

        SettingsClickableItem(
            title = "Permissions",
            subtitle = "List of all the permissions",
            onClick = onOpenPermissionsDialog
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Section: History
        SettingsSectionHeader(title = "History")

        SettingsCheckboxItem(
            title = "Playback history",
            subtitle = "Save all media played in History section",
            checked = prefs.playHistoryEnabled,
            onCheckedChange = { viewModel.updatePlayHistoryEnabled(it) }
        )

        SettingsCheckboxItem(
            title = "Video play queue history",
            subtitle = "Allow saving the video play queue to resume later",
            checked = prefs.saveVideoQueueHistory,
            onCheckedChange = { viewModel.updateSaveVideoQueueHistory(it) }
        )

        SettingsCheckboxItem(
            title = "Audio play queue history",
            subtitle = "Allow saving the audio play queue to resume later",
            checked = prefs.saveAudioQueueHistory,
            onCheckedChange = { viewModel.updateSaveAudioQueueHistory(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Extra settings
        SettingsSectionHeader(title = "Extra settings")

        SettingsNavigationItem(
            title = "Interface",
            icon = Icons.Default.Palette,
            onClick = { onNavigateTo(SettingsSubScreen.INTERFACE) }
        )

        SettingsNavigationItem(
            title = "Video",
            icon = Icons.Default.Movie,
            onClick = { onNavigateTo(SettingsSubScreen.VIDEO) }
        )

        SettingsNavigationItem(
            title = "Subtitles",
            icon = Icons.Default.Subtitles,
            onClick = { onNavigateTo(SettingsSubScreen.SUBTITLES) }
        )

        SettingsNavigationItem(
            title = "Audio",
            icon = Icons.Default.MusicNote,
            onClick = { onNavigateTo(SettingsSubScreen.AUDIO) }
        )

        SettingsNavigationItem(
            title = "Equalizer",
            icon = Icons.Default.Equalizer,
            onClick = { onNavigateTo(SettingsSubScreen.EQUALIZER) }
        )

        SettingsNavigationItem(
            title = "Casting",
            icon = Icons.Default.Cast,
            onClick = { onNavigateTo(SettingsSubScreen.CASTING) }
        )

        SettingsNavigationItem(
            title = "Parental control",
            icon = Icons.Default.Shield,
            onClick = { onNavigateTo(SettingsSubScreen.PARENTAL_CONTROL) }
        )

        SettingsNavigationItem(
            title = "Advanced / Application data",
            icon = Icons.Default.SettingsSuggest,
            onClick = { onNavigateTo(SettingsSubScreen.ADVANCED) }
        )

        SettingsNavigationItem(
            title = "About Aero Player",
            icon = Icons.Default.Info,
            onClick = onOpenAbout
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// -------------------------------------------------------------
// VIDEO SUB-SCREEN (Screenshot 3)
// -------------------------------------------------------------
@Composable
private fun VideoSettingsSubScreen(
    prefs: PreferenceEntity,
    viewModel: MainViewModel
) {
    var showResolutionDialog by remember { mutableStateOf(false) }
    var showVideoOutputDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SettingsClickableItem(
            title = "Video output",
            subtitle = prefs.videoOutput,
            onClick = { showVideoOutputDialog = true }
        )

        SettingsCheckboxItem(
            title = "Always use fast seek",
            subtitle = "Seek is faster but may be less precise",
            checked = prefs.alwaysFastSeek,
            onCheckedChange = { viewModel.updateAlwaysFastSeek(it) }
        )

        SettingsCheckboxItem(
            title = "Use custom Picture-in-Picture popup",
            subtitle = "Use custom Picture-in-Picture resizable popup",
            checked = prefs.useCustomPipPopup,
            onCheckedChange = { viewModel.updateUseCustomPipPopup(it) }
        )

        SettingsCheckboxItem(
            title = "Restore video from background",
            subtitle = "Restore video from background when reopening VLC for Android",
            checked = prefs.restoreVideoFromBackground,
            onCheckedChange = { viewModel.updateRestoreVideoFromBackground(it) }
        )

        SettingsCheckboxItem(
            title = "Match Display Frame Rate",
            subtitle = "Match display refresh rate to media frame rate. For example, a 24p film should play at 24p",
            checked = prefs.matchDisplayFrameRate,
            onCheckedChange = { viewModel.updateMatchDisplayFrameRate(it) }
        )

        SettingsClickableItem(
            title = "Preferred video resolution",
            subtitle = "Maximum video quality for streams, when applicable, will be: ${prefs.preferredVideoResolution}",
            onClick = { showResolutionDialog = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Secondary display section
        SettingsSectionHeader(title = "Secondary display")
        Text(
            text = "Settings when secondary displays are connected (HDMI/Chromecast)",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        SettingsCheckboxItem(
            title = "Prefer clone",
            subtitle = "Clone the device screen without remote control",
            checked = prefs.preferCloneSecondaryDisplay,
            onCheckedChange = { viewModel.updatePreferCloneSecondaryDisplay(it) }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showVideoOutputDialog) {
        val options = listOf("Automatic", "OpenGL ES 2.0", "OpenGL ES 3.0", "Android SurfaceView")
        SingleChoiceOptionDialog(
            title = "Video output",
            options = options,
            selectedOption = prefs.videoOutput,
            onDismiss = { showVideoOutputDialog = false },
            onSelect = {
                viewModel.updateVideoOutput(it)
                showVideoOutputDialog = false
            }
        )
    }

    if (showResolutionDialog) {
        val options = listOf("Best available", "4K (2160p)", "Full HD (1080p)", "HD (720p)", "SD (480p)")
        SingleChoiceOptionDialog(
            title = "Preferred video resolution",
            options = options,
            selectedOption = prefs.preferredVideoResolution,
            onDismiss = { showResolutionDialog = false },
            onSelect = {
                viewModel.updatePreferredVideoResolution(it)
                showResolutionDialog = false
            }
        )
    }
}

// -------------------------------------------------------------
// INTERFACE SUB-SCREEN (Screenshot 4)
// -------------------------------------------------------------
@Composable
private fun InterfaceSettingsSubScreen(
    prefs: PreferenceEntity,
    viewModel: MainViewModel
) {
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showListStyleDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SettingsCheckboxItem(
            title = "Show missing media",
            subtitle = "Show distant media even if they are not present",
            checked = prefs.showMissingMedia,
            onCheckedChange = { viewModel.updateShowMissingMedia(it) }
        )

        SettingsClickableItem(
            title = "Sleep timer",
            subtitle = prefs.sleepTimerDuration,
            onClick = { showSleepTimerDialog = true }
        )

        SettingsCheckboxItem(
            title = "Incognito mode",
            subtitle = "Do not record history or state while active",
            checked = prefs.incognitoMode,
            onCheckedChange = { viewModel.updateIncognitoMode(it) }
        )

        SettingsCheckboxItem(
            title = "Persistent incognito mode",
            subtitle = "Keep the incognito mode enabled even if the app is restarted",
            checked = prefs.persistentIncognitoMode,
            onCheckedChange = { viewModel.updatePersistentIncognitoMode(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Video section
        SettingsSectionHeader(title = "Video")

        SettingsCheckboxItem(
            title = "Show seen video marker",
            subtitle = "Mark a video as seen when you play it until the end",
            checked = prefs.showSeenVideoMarker,
            onCheckedChange = { viewModel.updateShowSeenVideoMarker(it) }
        )

        SettingsCheckboxItem(
            title = "Video thumbnails",
            subtitle = "Show video thumbnails in lists",
            checked = prefs.showVideoThumbnails,
            onCheckedChange = { viewModel.updateShowVideoThumbnails(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Audio section
        SettingsSectionHeader(title = "Audio")

        SettingsCheckboxItem(
            title = "Show last playlist tip",
            subtitle = "Shows a tip helping you to resume audio playback on app start",
            checked = prefs.showLastPlaylistTip,
            onCheckedChange = { viewModel.updateShowLastPlaylistTip(it) }
        )

        SettingsCheckboxItem(
            title = "Media cover on Lockscreen",
            subtitle = "When available, set the current media cover art as lockscreen wallpaper",
            checked = prefs.mediaCoverOnLockscreen,
            onCheckedChange = { viewModel.updateMediaCoverOnLockscreen(it) }
        )

        SettingsCheckboxItem(
            title = "Seek buttons in notification panel",
            subtitle = "Show rewind and fast forward buttons in compact media controls",
            checked = prefs.seekButtonsInNotification,
            onCheckedChange = { viewModel.updateSeekButtonsInNotification(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Theme & Navigation
        SettingsSectionHeader(title = "Theme & Navigation")

        SettingsClickableItem(
            title = "Theme mode",
            subtitle = "Current: ${prefs.themeMode}",
            onClick = { showThemeDialog = true }
        )

        SettingsCheckboxItem(
            title = "Dynamic Material You Colors",
            subtitle = "Harmonize player interface with Android wallpaper colors",
            checked = prefs.useDynamicColor,
            onCheckedChange = { viewModel.updateDynamicColor(it) }
        )

        SettingsCheckboxItem(
            title = "Group-wise Folder Navigation",
            subtitle = "Group media directories into tree hierarchies",
            checked = prefs.useGroupWiseFolderStyle,
            onCheckedChange = { viewModel.updateGroupWiseFolderStyle(it) }
        )

        SettingsClickableItem(
            title = "Library View Style",
            subtitle = "Current: ${prefs.listStyle}",
            onClick = { showListStyleDialog = true }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showSleepTimerDialog) {
        val options = listOf("Disabled", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes", "120 minutes")
        SingleChoiceOptionDialog(
            title = "Sleep timer",
            options = options,
            selectedOption = prefs.sleepTimerDuration,
            onDismiss = { showSleepTimerDialog = false },
            onSelect = {
                viewModel.updateSleepTimer(it)
                showSleepTimerDialog = false
            }
        )
    }

    if (showThemeDialog) {
        val options = listOf("System", "Light", "Dark")
        SingleChoiceOptionDialog(
            title = "Theme Mode",
            options = options,
            selectedOption = prefs.themeMode,
            onDismiss = { showThemeDialog = false },
            onSelect = {
                viewModel.updateTheme(it)
                showThemeDialog = false
            }
        )
    }

    if (showListStyleDialog) {
        val options = listOf("Grid", "List")
        SingleChoiceOptionDialog(
            title = "Library View Style",
            options = options,
            selectedOption = prefs.listStyle,
            onDismiss = { showListStyleDialog = false },
            onSelect = {
                viewModel.updateListStyle(it)
                showListStyleDialog = false
            }
        )
    }
}

// -------------------------------------------------------------
// SUBTITLES SUB-SCREEN (Screenshot 5)
// -------------------------------------------------------------
@Composable
private fun SubtitlesSettingsSubScreen(
    prefs: PreferenceEntity,
    viewModel: MainViewModel,
    onPickColor: (String, (String) -> Unit) -> Unit
) {
    var showPresetsDialog by remember { mutableStateOf(false) }
    var showEncodingDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSizeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SettingsClickableItem(
            title = "Subtitles presets",
            subtitle = "Current preset: ${prefs.subtitlePreset}",
            onClick = { showPresetsDialog = true }
        )

        SettingsCheckboxItem(
            title = "Auto load subtitles",
            subtitle = "Automatically load matching subtitle files",
            checked = prefs.autoLoadSubtitles,
            onCheckedChange = { viewModel.updateAutoLoadSubtitles(it) }
        )

        SettingsClickableItem(
            title = "Subtitle text encoding",
            subtitle = prefs.subtitleEncoding,
            onClick = { showEncodingDialog = true }
        )

        SettingsClickableItem(
            title = "Preferred subtitle language",
            subtitle = prefs.defaultSubtitleLanguage,
            onClick = { showLanguageDialog = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Subtitles font style
        SettingsSectionHeader(title = "Subtitles font style")

        val sizeLabel = when (prefs.subtitleSize.toInt()) {
            12 -> "Very small"
            14 -> "Small"
            16 -> "Normal"
            20 -> "Large"
            26 -> "Very large"
            32 -> "Huge"
            else -> "${prefs.subtitleSize.toInt()} sp"
        }
        SettingsClickableItem(
            title = "Subtitles Size",
            subtitle = sizeLabel,
            onClick = { showSizeDialog = true }
        )

        SettingsCheckboxItem(
            title = "Bold subtitles",
            subtitle = "Render subtitle text in bold weight",
            checked = prefs.subtitleBold,
            onCheckedChange = { viewModel.updateSubtitleBold(it) }
        )

        // Text Color picker row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onPickColor(prefs.subtitleTextColor) { newColor ->
                        viewModel.updateSubtitlePreferences(subtitleTextColor = newColor)
                    }
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Colour", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Normal)
            val parsedColor = try {
                Color(android.graphics.Color.parseColor(prefs.subtitleTextColor))
            } catch (e: Exception) {
                Color.White
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(parsedColor)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }

        // Opacity slider row
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Opacity", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                Text("${(prefs.subtitleOpacity * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = prefs.subtitleOpacity,
                onValueChange = { viewModel.updateSubtitleOpacity(it) },
                valueRange = 0.2f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Subtitles Background
        SettingsSectionHeader(title = "Subtitles Background")

        SettingsCheckboxItem(
            title = "Subtitles Background",
            subtitle = "Render background box behind subtitles for contrast",
            checked = prefs.subtitleBackgroundEnabled,
            onCheckedChange = { viewModel.updateSubtitleBackgroundEnabled(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Subtitles shadow
        SettingsSectionHeader(title = "Subtitles shadow")

        SettingsCheckboxItem(
            title = "Subtitles shadow",
            subtitle = "Add soft shadow to subtitle text",
            checked = prefs.subtitleShadowEnabled,
            onCheckedChange = { viewModel.updateSubtitleShadowEnabled(it) }
        )

        // Shadow color picker row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onPickColor(prefs.subtitleShadowColor) { newColor ->
                        viewModel.updateSubtitleShadowColor(newColor)
                    }
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Colour", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Normal)
            val parsedShadow = try {
                Color(android.graphics.Color.parseColor(prefs.subtitleShadowColor))
            } catch (e: Exception) {
                Color.Black
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(parsedShadow)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showPresetsDialog) {
        val options = listOf("White on Black", "Yellow on Black", "White Outline", "Yellow Outline", "Soft Shadow", "Custom")
        SingleChoiceOptionDialog(
            title = "Subtitles presets",
            options = options,
            selectedOption = prefs.subtitlePreset,
            onDismiss = { showPresetsDialog = false },
            onSelect = {
                viewModel.applySubtitlePreset(it)
                showPresetsDialog = false
            }
        )
    }

    if (showEncodingDialog) {
        val options = listOf(
            "Default (Windows-1252)",
            "UTF-8",
            "ISO-8859-1 (Western European)",
            "GBK (Simplified Chinese)",
            "Big5 (Traditional Chinese)",
            "Shift_JIS (Japanese)",
            "EUC-KR (Korean)",
            "Windows-1256 (Arabic)",
            "Windows-1251 (Cyrillic)"
        )
        SingleChoiceOptionDialog(
            title = "Subtitle text encoding",
            options = options,
            selectedOption = prefs.subtitleEncoding,
            onDismiss = { showEncodingDialog = false },
            onSelect = {
                viewModel.updateSubtitleEncoding(it)
                showEncodingDialog = false
            }
        )
    }

    if (showLanguageDialog) {
        val options = listOf("No language preference", "English", "Spanish", "French", "German", "Japanese", "Chinese", "Hindi", "Arabic", "Russian")
        SingleChoiceOptionDialog(
            title = "Preferred subtitle language",
            options = options,
            selectedOption = prefs.defaultSubtitleLanguage,
            onDismiss = { showLanguageDialog = false },
            onSelect = {
                viewModel.updateSubtitleLanguage(it)
                showLanguageDialog = false
            }
        )
    }

    if (showSizeDialog) {
        val options = listOf("Very small (12sp)", "Small (14sp)", "Normal (16sp)", "Large (20sp)", "Very large (26sp)", "Huge (32sp)")
        val current = when (prefs.subtitleSize.toInt()) {
            12 -> "Very small (12sp)"
            14 -> "Small (14sp)"
            20 -> "Large (20sp)"
            26 -> "Very large (26sp)"
            32 -> "Huge (32sp)"
            else -> "Normal (16sp)"
        }
        SingleChoiceOptionDialog(
            title = "Subtitles Size",
            options = options,
            selectedOption = current,
            onDismiss = { showSizeDialog = false },
            onSelect = { choice ->
                val size = when {
                    choice.contains("12") -> 12f
                    choice.contains("14") -> 14f
                    choice.contains("20") -> 20f
                    choice.contains("26") -> 26f
                    choice.contains("32") -> 32f
                    else -> 16f
                }
                viewModel.updateSubtitleSettings(size, prefs.subtitleTextColor)
                showSizeDialog = false
            }
        )
    }
}

// -------------------------------------------------------------
// AUDIO SUB-SCREEN (Screenshot 6)
// -------------------------------------------------------------
@Composable
private fun AudioSettingsSubScreen(
    prefs: PreferenceEntity,
    viewModel: MainViewModel
) {
    var showAudioOutputDialog by remember { mutableStateOf(false) }
    var showLangDialog by remember { mutableStateOf(false) }
    var showResumeDialog by remember { mutableStateOf(false) }
    var showReplayGainDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SettingsClickableItem(
            title = "Audio output",
            subtitle = prefs.audioOutput,
            onClick = { showAudioOutputDialog = true }
        )

        SettingsCheckboxItem(
            title = "Resume playback after a call",
            subtitle = "Stay in pause otherwise",
            checked = prefs.resumePlaybackAfterCall,
            onCheckedChange = { viewModel.updateResumePlaybackAfterCall(it) }
        )

        SettingsCheckboxItem(
            title = "Stop on application swipe",
            subtitle = "Stop playback when application is dismissed",
            checked = prefs.stopOnAppSwipe,
            onCheckedChange = { viewModel.updateStopOnAppSwipe(it) }
        )

        SettingsCheckboxItem(
            title = "Digital audio output (passthrough)",
            subtitle = if (prefs.digitalAudioPassthrough) "Audio Digital Output enabled" else "Audio Digital Output disabled",
            checked = prefs.digitalAudioPassthrough,
            onCheckedChange = { viewModel.updateDigitalAudioPassthrough(it) }
        )

        SettingsClickableItem(
            title = "Preferred audio language",
            subtitle = prefs.preferredAudioLanguage,
            onClick = { showLangDialog = true }
        )

        SettingsClickableItem(
            title = "Resume played audio",
            subtitle = prefs.resumePlayedAudio,
            onClick = { showResumeDialog = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Headset section
        SettingsSectionHeader(title = "Headset")

        SettingsCheckboxItem(
            title = "Detect headset",
            subtitle = "Detect headset insertion and removal",
            checked = prefs.detectHeadset,
            onCheckedChange = { viewModel.updateDetectHeadset(it) }
        )

        SettingsCheckboxItem(
            title = "Resume on headset insertion",
            subtitle = "Pause otherwise",
            checked = prefs.resumeOnHeadsetInsertion,
            onCheckedChange = { viewModel.updateResumeOnHeadsetInsertion(it) }
        )

        SettingsCheckboxItem(
            title = "Ignore headset media button presses",
            subtitle = "Useful, for instance, if you are using a headset with broken physical buttons",
            checked = prefs.ignoreHeadsetButtons,
            onCheckedChange = { viewModel.updateIgnoreHeadsetButtons(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Replay Gain section
        SettingsSectionHeader(title = "Replay Gain")

        SettingsCheckboxItem(
            title = "Enable replay gain",
            subtitle = "Streams without replay gain information may be quieter due to the default gain",
            checked = prefs.enableReplayGain,
            onCheckedChange = { viewModel.updateEnableReplayGain(it) }
        )

        SettingsClickableItem(
            title = "Replay gain mode",
            subtitle = if (prefs.enableReplayGain) prefs.replayGainMode else "Track mode with replay gain information",
            onClick = {
                if (prefs.enableReplayGain) {
                    showReplayGainDialog = true
                }
            }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showAudioOutputDialog) {
        val options = listOf("AudioTrack", "OpenSL ES", "AAudio")
        SingleChoiceOptionDialog(
            title = "Audio output",
            options = options,
            selectedOption = prefs.audioOutput,
            onDismiss = { showAudioOutputDialog = false },
            onSelect = {
                viewModel.updateAudioOutput(it)
                showAudioOutputDialog = false
            }
        )
    }

    if (showLangDialog) {
        val options = listOf("No language preference", "English", "Spanish", "French", "German", "Japanese", "Chinese", "Hindi", "Arabic", "Russian")
        SingleChoiceOptionDialog(
            title = "Preferred audio language",
            options = options,
            selectedOption = prefs.preferredAudioLanguage,
            onDismiss = { showLangDialog = false },
            onSelect = {
                viewModel.updatePreferredAudioLanguage(it)
                showLangDialog = false
            }
        )
    }

    if (showResumeDialog) {
        val options = listOf("Always", "Ask", "Never")
        SingleChoiceOptionDialog(
            title = "Resume played audio",
            options = options,
            selectedOption = prefs.resumePlayedAudio,
            onDismiss = { showResumeDialog = false },
            onSelect = {
                viewModel.updateResumePlayedAudio(it)
                showResumeDialog = false
            }
        )
    }

    if (showReplayGainDialog) {
        val options = listOf("Track mode", "Album mode", "None")
        SingleChoiceOptionDialog(
            title = "Replay gain mode",
            options = options,
            selectedOption = prefs.replayGainMode,
            onDismiss = { showReplayGainDialog = false },
            onSelect = {
                viewModel.updateReplayGainMode(it)
                showReplayGainDialog = false
            }
        )
    }
}

// -------------------------------------------------------------
// ADVANCED & APPLICATION DATA SUB-SCREEN (Screenshot 1 & Screenshot 7)
// -------------------------------------------------------------
@Composable
private fun AdvancedSettingsSubScreen(
    prefs: PreferenceEntity,
    viewModel: MainViewModel,
    onRestart: () -> Unit,
    onClearData: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCachingDialog by remember { mutableStateOf(false) }
    var showUserAgentDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SettingsClickableItem(
            title = "Network caching value",
            subtitle = "The amount of time to buffer network media (in ms) for software decoding\nSet to 0 to disable (Current: ${prefs.networkCachingMs} ms)",
            onClick = { showCachingDialog = true }
        )

        SettingsCheckboxItem(
            title = "Prefer SMB 1",
            subtitle = "Uncheck this setting if you have some difficulties browsing your SMB server",
            checked = prefs.preferSmb1,
            onCheckedChange = { viewModel.updatePreferSmb1(it) }
        )

        SettingsClickableItem(
            title = "HTTP user agent",
            subtitle = prefs.httpUserAgent,
            onClick = { showUserAgentDialog = true }
        )

        SettingsClickableItem(
            title = "Quit and restart application",
            subtitle = "Safely close background sessions and reboot player engine",
            onClick = onRestart
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Application data section
        SettingsSectionHeader(title = "Application data")

        SettingsClickableItem(
            title = "Dump media database",
            subtitle = "Copy database to internal storage root",
            onClick = {
                Toast.makeText(context, "Media database copied to /storage/emulated/0/vlc_media_dump.db", Toast.LENGTH_LONG).show()
            }
        )

        SettingsClickableItem(
            title = "Dump app database",
            subtitle = "Copy database to internal storage root",
            onClick = {
                Toast.makeText(context, "App database exported to /storage/emulated/0/vlc_app_dump.json", Toast.LENGTH_LONG).show()
            }
        )

        SettingsClickableItem(
            title = "Clear media database",
            subtitle = "Clears the database to start over",
            onClick = {
                scope.launch {
                    viewModel.scanLocalMedia()
                    Toast.makeText(context, "Media database cleared and re-scanned", Toast.LENGTH_SHORT).show()
                }
            }
        )

        SettingsClickableItem(
            title = "Clear app data",
            subtitle = "Clears VLC for Android data",
            onClick = onClearData
        )

        SettingsClickableItem(
            title = "Clear playback history",
            subtitle = "Clears the history list",
            onClick = {
                viewModel.clearPlaybackHistory()
                Toast.makeText(context, "Playback history cleared", Toast.LENGTH_SHORT).show()
            }
        )

        SettingsClickableItem(
            title = "Export settings",
            subtitle = "Export your settings to a file to import them later",
            onClick = onExport
        )

        SettingsClickableItem(
            title = "Restore settings",
            subtitle = "Restore your settings from a previous export",
            onClick = onRestore
        )

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showCachingDialog) {
        val options = listOf("0 (Disabled)", "500 ms", "1000 ms", "1500 ms (Default)", "3000 ms", "5000 ms")
        SingleChoiceOptionDialog(
            title = "Network caching value (ms)",
            options = options,
            selectedOption = when (prefs.networkCachingMs) {
                0 -> "0 (Disabled)"
                500 -> "500 ms"
                1000 -> "1000 ms"
                3000 -> "3000 ms"
                5000 -> "5000 ms"
                else -> "1500 ms (Default)"
            },
            onDismiss = { showCachingDialog = false },
            onSelect = { choice ->
                val ms = when {
                    choice.startsWith("0") -> 0
                    choice.startsWith("500 ") -> 500
                    choice.startsWith("1000") -> 1000
                    choice.startsWith("3000") -> 3000
                    choice.startsWith("5000") -> 500
                    else -> 1500
                }
                viewModel.updateNetworkCachingMs(ms)
                showCachingDialog = false
            }
        )
    }

    if (showUserAgentDialog) {
        val options = listOf("Not set", "VLC/3.0.18 (Android)", "ExoPlayer/2.19", "Mozilla/5.0 (Linux; Android 14)")
        SingleChoiceOptionDialog(
            title = "HTTP user agent",
            options = options,
            selectedOption = prefs.httpUserAgent,
            onDismiss = { showUserAgentDialog = false },
            onSelect = {
                viewModel.updateHttpUserAgent(it)
                showUserAgentDialog = false
            }
        )
    }
}

// -------------------------------------------------------------
// EQUALIZER SUB-SCREEN
// -------------------------------------------------------------
@Composable
private fun EqualizerSettingsSubScreen(viewModel: MainViewModel) {
    val enabled by viewModel.equalizerEnabled.collectAsState()
    val bands by viewModel.equalizerBands.collectAsState()
    val preset by viewModel.currentEqualizerPreset.collectAsState()
    var showPresetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsCheckboxItem(
            title = "Enable Equalizer",
            subtitle = "Apply hardware audio equalization filters",
            checked = enabled,
            onCheckedChange = { viewModel.setEqualizerEnabled(it) }
        )

        if (enabled) {
            SettingsClickableItem(
                title = "Equalizer Preset",
                subtitle = "Current preset: $preset",
                onClick = { showPresetDialog = true }
            )

            SettingsSectionHeader(title = "Frequency Bands")

            bands.forEach { band ->
                val freqLabel = when {
                    band.centerFreq >= 1000 -> "${band.centerFreq / 1000} kHz"
                    else -> "${band.centerFreq} Hz"
                }
                val gainDb = band.currentLevel / 100.0f

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(freqLabel, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (gainDb > 0) "+%.1f dB".format(gainDb) else "%.1f dB".format(gainDb),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp
                        )
                    }
                    Slider(
                        value = band.currentLevel.toFloat(),
                        onValueChange = { viewModel.setEqualizerBandLevel(band.index, it.toInt().toShort()) },
                        valueRange = -1500f..1500f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }
    }

    if (showPresetDialog) {
        val options = listOf("Flat", "Bass Boost", "Vocal Boost", "Treble Boost", "Rock", "Pop", "Jazz", "Classical")
        SingleChoiceOptionDialog(
            title = "Select Equalizer Preset",
            options = options,
            selectedOption = preset,
            onDismiss = { showPresetDialog = false },
            onSelect = {
                viewModel.applyPreset(it)
                showPresetDialog = false
            }
        )
    }
}

// -------------------------------------------------------------
// CASTING SUB-SCREEN
// -------------------------------------------------------------
@Composable
private fun CastingSettingsSubScreen(
    prefs: PreferenceEntity,
    viewModel: MainViewModel
) {
    var showProtocolDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsCheckboxItem(
            title = "Enable Cast & DLNA Discovery",
            subtitle = "Scan local network for Chromecast, DLNA, and UPnP renderers",
            checked = prefs.isCastEnabled,
            onCheckedChange = { viewModel.updateCastSettings(isCastEnabled = it) }
        )

        SettingsClickableItem(
            title = "Preferred Cast Protocol",
            subtitle = prefs.castProtocol,
            onClick = { showProtocolDialog = true }
        )

        SettingsClickableItem(
            title = "Cast Streaming Quality",
            subtitle = prefs.castQuality,
            onClick = { showQualityDialog = true }
        )

        SettingsCheckboxItem(
            title = "Auto-connect to saved device",
            subtitle = "Automatically connect to ${prefs.selectedCastDevice} on playback start",
            checked = prefs.autoConnectCast,
            onCheckedChange = { viewModel.updateCastSettings(autoConnectCast = it) }
        )

        SettingsCheckboxItem(
            title = "Keep casting on screen sleep",
            subtitle = "Keep sending cast streams while screen is turned off",
            checked = prefs.keepCastingOnScreenSleep,
            onCheckedChange = { viewModel.updateCastSettings(keepCastingOnScreenSleep = it) }
        )
    }

    if (showProtocolDialog) {
        val options = listOf("Chromecast / DLNA", "DLNA / UPnP", "AirPlay / Miracast", "HTTP Stream")
        SingleChoiceOptionDialog(
            title = "Preferred Cast Protocol",
            options = options,
            selectedOption = prefs.castProtocol,
            onDismiss = { showProtocolDialog = false },
            onSelect = {
                viewModel.updateCastSettings(castProtocol = it)
                showProtocolDialog = false
            }
        )
    }

    if (showQualityDialog) {
        val options = listOf("High (320kbps / 1080p)", "Balanced (192kbps / 720p)", "Low Bandwidth (128kbps / 480p)")
        SingleChoiceOptionDialog(
            title = "Cast Streaming Quality",
            options = options,
            selectedOption = prefs.castQuality,
            onDismiss = { showQualityDialog = false },
            onSelect = {
                viewModel.updateCastSettings(castQuality = it)
                showQualityDialog = false
            }
        )
    }
}

// -------------------------------------------------------------
// PARENTAL CONTROL SUB-SCREEN
// -------------------------------------------------------------
@Composable
private fun ParentalControlSettingsSubScreen(
    prefs: PreferenceEntity,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsCheckboxItem(
            title = "Enable Parental Control",
            subtitle = "Protect sensitive media folders and network streams with PIN",
            checked = prefs.parentalControlEnabled,
            onCheckedChange = {
                if (it && prefs.parentalPin == "0000") {
                    showPinDialog = true
                }
                viewModel.updateParentalControl(enabled = it)
            }
        )

        if (prefs.parentalControlEnabled) {
            SettingsClickableItem(
                title = "Change PIN Code",
                subtitle = "Current PIN: **** (Tap to configure new 4-digit code)",
                onClick = { showPinDialog = true }
            )

            SettingsCheckboxItem(
                title = "Lock Player Settings",
                subtitle = "Require PIN code to modify app preferences",
                checked = prefs.parentalLockSettings,
                onCheckedChange = { viewModel.updateParentalControl(lockSettings = it) }
            )

            SettingsCheckboxItem(
                title = "Lock Live Network Streams",
                subtitle = "Require PIN code to open external IPTV or M3U streams",
                checked = prefs.parentalLockStreams,
                onCheckedChange = { viewModel.updateParentalControl(lockStreams = it) }
            )

            SettingsCheckboxItem(
                title = "Lock Restricted Media Folders",
                subtitle = "Hide and password protect banned or private directories",
                checked = prefs.parentalLockSensitiveFolders,
                onCheckedChange = { viewModel.updateParentalControl(lockSensitiveFolders = it) }
            )
        }
    }

    if (showPinDialog) {
        var newPin by remember { mutableStateOf("") }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { showPinDialog = false },
            title = { Text("Set 4-Digit PIN", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a new 4-digit PIN code for parental locks:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) newPin = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPin.length == 4) {
                        viewModel.updateParentalControl(pin = newPin, enabled = true)
                        Toast.makeText(context, "Parental PIN saved successfully!", Toast.LENGTH_SHORT).show()
                        showPinDialog = false
                    } else {
                        Toast.makeText(context, "PIN must be exactly 4 digits", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Save PIN", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

// -------------------------------------------------------------
// SEARCH RESULTS VIEW
// -------------------------------------------------------------
data class SearchItem(
    val title: String,
    val subtitle: String,
    val subScreen: SettingsSubScreen?,
    val action: (() -> Unit)? = null
)

@Composable
private fun SettingsSearchResults(
    query: String,
    prefs: PreferenceEntity,
    viewModel: MainViewModel,
    onNavigate: (SettingsSubScreen) -> Unit
) {
    val allItems = remember(prefs) {
        listOf(
            SearchItem("Media library folders", "Select directories to include in the media library", null),
            SearchItem("Auto rescan", "Automatically scan device for new or deleted media", null),
            SearchItem("Background/PiP mode", "Select VLC behaviour when switching apps", null),
            SearchItem("Hardware Acceleration", "Configure decoding and performance acceleration", null),
            SearchItem("Video screen orientation", "Landscape, Portrait, Sensor auto", null),
            SearchItem("Action for streams when connection is metered", "Warn, block or allow streaming", null),
            SearchItem("Permissions", "List of all storage and audio permissions", null),
            SearchItem("Playback history", "Save all media played in History section", null),
            SearchItem("Video play queue history", "Allow saving video play queue to resume later", null),
            SearchItem("Audio play queue history", "Allow saving audio play queue to resume later", null),
            SearchItem("Always use fast seek", "Seek is faster but may be less precise", SettingsSubScreen.VIDEO),
            SearchItem("Use custom Picture-in-Picture popup", "Resizable popup overlay", SettingsSubScreen.VIDEO),
            SearchItem("Restore video from background", "Restore video when reopening app", SettingsSubScreen.VIDEO),
            SearchItem("Audio output", "Select audio output method: AudioTrack, OpenSL ES, AAudio", SettingsSubScreen.AUDIO),
            SearchItem("Digital audio output (passthrough)", "Audio Digital Output passthrough mode", SettingsSubScreen.AUDIO),
            SearchItem("Video output", "Configure video output: Automatic, OpenGL ES 2.0/3.0, Android SurfaceView", SettingsSubScreen.VIDEO),
            SearchItem("Match Display Frame Rate", "Match display refresh rate to media 24p", SettingsSubScreen.VIDEO),
            SearchItem("Preferred video resolution", "Max video stream quality target", SettingsSubScreen.VIDEO),
            SearchItem("Show missing media", "Show distant media even if not present", SettingsSubScreen.INTERFACE),
            SearchItem("Sleep timer", "Set automatic playback sleep countdown timer", SettingsSubScreen.INTERFACE),
            SearchItem("Incognito mode", "Do not record history or modify state", SettingsSubScreen.INTERFACE),
            SearchItem("Show seen video marker", "Mark video as seen when played to end", SettingsSubScreen.INTERFACE),
            SearchItem("Video thumbnails", "Show video thumbnails in lists", SettingsSubScreen.INTERFACE),
            SearchItem("Media cover on Lockscreen", "Set media artwork as lockscreen wallpaper", SettingsSubScreen.INTERFACE),
            SearchItem("Subtitles presets", "White on Black, Yellow, Outline, Soft Shadow", SettingsSubScreen.SUBTITLES),
            SearchItem("Auto load subtitles", "Automatically load matching subtitle files", SettingsSubScreen.SUBTITLES),
            SearchItem("Subtitle text encoding", "Windows-1252, UTF-8, ISO-8859", SettingsSubScreen.SUBTITLES),
            SearchItem("Subtitle Size & Style", "Normal, Large, Bold, Colors, Opacity", SettingsSubScreen.SUBTITLES),
            SearchItem("Resume playback after a call", "Stay in pause otherwise", SettingsSubScreen.AUDIO),
            SearchItem("Stop on application swipe", "Stop playback when app is dismissed", SettingsSubScreen.AUDIO),
            SearchItem("Detect headset", "Detect headset insertion and removal", SettingsSubScreen.AUDIO),
            SearchItem("Enable replay gain", "Gain audio volume normalization", SettingsSubScreen.AUDIO),
            SearchItem("Network caching value", "Amount of time to buffer network media in ms", SettingsSubScreen.ADVANCED),
            SearchItem("Prefer SMB 1", "Compatibility for SMB network file sharing", SettingsSubScreen.ADVANCED),
            SearchItem("HTTP user agent", "Custom HTTP client header string", SettingsSubScreen.ADVANCED),
            SearchItem("Dump media database", "Copy database to storage root", SettingsSubScreen.ADVANCED),
            SearchItem("Export settings", "Export settings JSON to clipboard or file", SettingsSubScreen.ADVANCED),
            SearchItem("Equalizer presets & bands", "Fine-tune 10-band audio frequency levels", SettingsSubScreen.EQUALIZER),
            SearchItem("Casting & DLNA", "Cast streams to Chromecast or DLNA renderers", SettingsSubScreen.CASTING),
            SearchItem("Parental control PIN", "Lock settings and restricted media folders", SettingsSubScreen.PARENTAL_CONTROL)
        )
    }

    val filtered = remember(query) {
        allItems.filter {
            it.title.contains(query, ignoreCase = true) || it.subtitle.contains(query, ignoreCase = true)
        }
    }

    if (filtered.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No settings found matching \"$query\"", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(filtered) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (item.subScreen != null) {
                                onNavigate(item.subScreen)
                            }
                        }
                        .padding(vertical = 12.dp)
                ) {
                    Text(text = item.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    if (item.subScreen != null) {
                        Text("Category: ${item.subScreen.name.lowercase().replaceFirstChar { it.uppercase() }}", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

// -------------------------------------------------------------
// REUSABLE SETTINGS COMPONENTS
// -------------------------------------------------------------

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsCheckboxItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 17.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )
    }
}

@Composable
private fun SettingsClickableItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun SettingsNavigationItem(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SingleChoiceOptionDialog(
    title: String,
    options: List<String>,
    selectedOption: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.forEach { option ->
                    val isSelected = option == selectedOption || option.startsWith(selectedOption)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelect(option) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(
                            text = option,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun ColorPickerDialog(
    initialColor: String,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit
) {
    val presetColors = listOf(
        "#FFFFFF" to "White",
        "#FFFF00" to "Yellow",
        "#00FFFF" to "Cyan",
        "#00FF00" to "Green",
        "#FF00FF" to "Magenta",
        "#FF8800" to "Orange",
        "#000000" to "Black",
        "#80000000" to "Translucent Black"
    )

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text("Select Color", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetColors.forEach { (hex, name) ->
                    val parsed = try {
                        Color(android.graphics.Color.parseColor(hex))
                    } catch (e: Exception) {
                        Color.White
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onColorSelected(hex) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parsed)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                        Text(name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun PermissionsListDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val permissions = listOf(
        "Read Media Videos" to "Required to scan and play video files on your device",
        "Read Media Audio" to "Required to scan and play music tracks and ringtones",
        "Manage External Storage" to "Allows broad directory discovery for all local media",
        "Post Notifications" to "Displays media controls and seek actions in notification bar",
        "Foreground Service" to "Enables background audio playback and PiP window streaming",
        "Bluetooth Connect" to "Detects headset button events and routing to external speakers",
        "Picture-in-Picture" to "Displays floating resizable video overlays while using other apps"
    )

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text("List of all the permissions", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Aero Player for Android uses the following system capabilities:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

                permissions.forEach { (name, desc) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("GRANTED", color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not open app settings", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            }) {
                Text("App Info Settings", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
