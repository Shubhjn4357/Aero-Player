package com.example.ui.screens

/*
 * ======================================================================================
 * ARCHITECTURAL SPECIFICATION: SCALABLE NESTED PREFERENCES (Group > Folder > Setting)
 * ======================================================================================
 *
 * This implementation refactors flat preference controls into a multi-tier nested 
 * navigation system using a local State Stack in Compose. All state definitions, 
 * schemas, file directories, and variables are mapped strictly via camelCase conventions.
 *
 * SCHEMA MATRIX & DIRECTORY MAPPING:
 * 
 * Group A: User Interface (File Path: ui/)
 * └── Folder: Theme Engine (ui/theme/)
 *     ├── appThemeEngine             -> prefs.themeMode ("System", "Light", "Dark")
 *     └── material3DynamicColors     -> prefs.useDynamicColor (Boolean)
 * └── Folder: Layout Options (ui/layout/)
 *     ├── mediaListDisplayStyle      -> prefs.listStyle ("Grid", "List")
 *     └── groupWiseFolderStyle       -> prefs.useGroupWiseFolderStyle (Boolean)
 *
 * Group B: Playback Pipeline (File Path: playback/)
 * └── Folder: Player Controls (playback/controls/)
 *     ├── doubleTapSeekDuration      -> prefs.doubleTapSeekSeconds (5, 10, 15, 20, 30)
 *     ├── videoScreenOrientation     -> prefs.defaultOrientation ("SYSTEM_DEFAULT", "LANDSCAPE", "SENSOR_BASED")
 *     └── playerRotationLock         -> prefs.rotationLock (Boolean)
 * └── Folder: Core Processing (playback/core/)
 *     ├── hardwareAcceleration       -> prefs.hwAcceleration ("DISABLED_SOFTWARE", "DECODING_ONLY", "FULL_HARDWARE")
 *     ├── backgroundPlaybackPip      -> prefs.backgroundMode ("STOP_PLAYBACK", "PLAY_BACKGROUND_AUDIO", "LAUNCH_PIP_MODE")
 *     ├── resumePlaybackBehavior     -> prefs.resumePlaybackBehavior ("Ask Every Time", "Always Resume", "Always Start from Beginning")
 *     ├── perVideoPlaybackSettings   -> prefs.usePerVideoSettings (Boolean)
 *     └── saveVolumeBrightnessLevel  -> prefs.saveVolumeBrightnessBehavior ("None", "Global", "Individual")
 *
 * Group C: Video Assets (File Path: assets/)
 * └── Folder: Subtitle System (assets/subtitles/)
 *     ├── defaultSubtitleLanguage    -> prefs.defaultSubtitleLanguage ("English", "Spanish", etc.)
 *     ├── subtitleTextColor          -> prefs.subtitleTextColor (Hex Code)
 *     ├── subtitleBackground         -> prefs.subtitleBackground (Hex Code with Alpha)
 *     ├── subtitleTextSize           -> prefs.subtitleSize (Float in sp)
 *     └── subtitleFontStyle          -> prefs.subtitleFontStyle ("Normal", "Bold", "Italic")
 *
 * Group D: Data & Privacy (File Path: dataPrivacy/)
 * └── Folder: Network Restrictions (dataPrivacy/network/)
 *     └── meteredNetworkPolicy       -> prefs.meteredNetworkAction ("WARN_BEFORE_STREAMING", "BLOCK_STREAMING", "ALLOW_STREAMING")
 * └── Folder: Logging Engine (dataPrivacy/logging/)
 *     ├── playbackHistoryTracker     -> prefs.playHistoryEnabled (Boolean)
 *     └── queueHistoryCache          -> prefs.saveVideoQueueHistory & prefs.saveAudioQueueHistory (Boolean)
 * └── Folder: Storage Management (dataPrivacy/storage/)
 *     ├── mediaLibraryFolders        -> Favorite Folder List Custom Settings
 *     ├── autoRescanOnLaunch         -> prefs.autoScanEnabled (Boolean)
 *     ├── resetMediaDatabaseCache    -> Trigger Scanner Rebuild Action
 *     └── wipePlaybackProgressCache  -> Clear Playback Progress History Action
 *
 * Group E: Application Info (File Path: appInfo/)
 * └── Folder: Legal Legalities (appInfo/legal/)
 *     ├── privacyPolicy              -> Launch dynamic EULA terms dialog
 *     ├── termsAndConditions         -> Launch standard service usage dialog
 *     └── changelog                  -> Launch capabilities increment log dialog
 *
 * ======================================================================================
 */

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.MainViewModel
import androidx.activity.compose.BackHandler

// --- SEAMLESS SCHEMAS AND DATA CLASSES ---

enum class SettingGroup(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector
) {
    userInterface("userInterface", "User Interface", "Themes, accent colors, and custom library displays", Icons.Default.Palette),
    playbackPipeline("playbackPipeline", "Playback Pipeline", "Hardware decoding, seek times, and background audio", Icons.Default.PlayArrow),
    videoAssets("videoAssets", "Video Assets", "Subtitles language, text color, sizes, and fonts", Icons.Default.Subtitles),
    dataPrivacy("dataPrivacy", "Data & Privacy", "Network warning thresholds, storage directories, and database cache", Icons.Default.Security),
    appInfo("appInfo", "Application Info", "Software EULA agreements, terms of use, and dynamic capability log", Icons.Default.Info)
}

enum class SettingFolder(
    val id: String,
    val group: SettingGroup,
    val title: String,
    val description: String,
    val icon: ImageVector
) {
    // Group A: User Interface (File Path: ui/)
    themeEngine("themeEngine", SettingGroup.userInterface, "Theme Engine", "Manage dark/light transitions and dynamic colors", Icons.Default.Brush),
    layoutOptions("layoutOptions", SettingGroup.userInterface, "Layout Options", "Adjust visual listing metrics and folder grouping", Icons.Default.Dashboard),

    // Group B: Playback Pipeline (File Path: playback/)
    playerControls("playerControls", SettingGroup.playbackPipeline, "Player Controls", "Configure jump intervals, rotation locks, and tilt orientations", Icons.Default.Settings),
    coreProcessing("coreProcessing", SettingGroup.playbackPipeline, "Core Processing", "Decoders, background media delivery, and resume behaviors", Icons.Default.Tune),

    // Group C: Video Assets (File Path: assets/)
    subtitleSystem("subtitleSystem", SettingGroup.videoAssets, "Subtitle System", "Adjust default language tracks, colors, and sizing scales", Icons.Default.Subtitles),

    // Group D: Data & Privacy (File Path: dataPrivacy/)
    networkRestrictions("networkRestrictions", SettingGroup.dataPrivacy, "Network Restrictions", "Handle metered network warnings and connection rules", Icons.Default.Wifi),
    loggingEngine("loggingEngine", SettingGroup.dataPrivacy, "Logging Engine", "Toggle playback history and audio/video queue cache tracking", Icons.Default.History),
    storageManagement("storageManagement", SettingGroup.dataPrivacy, "Storage Management", "Erase indices, customize folder scopes, and scan files", Icons.Default.Storage),

    // Group E: Application Info (File Path: appInfo/)
    legalLegalities("legalLegalities", SettingGroup.appInfo, "Legal Legalities", "Review system privacy terms, EULA, and release logs", Icons.Default.Description)
}

sealed class SettingsView {
    object Root : SettingsView()
    data class Group(val group: SettingGroup) : SettingsView()
    data class Folder(val folder: SettingFolder) : SettingsView()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val prefs by viewModel.preferencesState.collectAsState()
    val context = LocalContext.current

    // Dialog state controllers
    var showFolderManagerDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }

    // Dropdown open controllers for atomic states
    var showThemeMenu by remember { mutableStateOf(false) }
    var showStyleMenu by remember { mutableStateOf(false) }
    var showOrientationMenu by remember { mutableStateOf(false) }
    var showSeekSecondsMenu by remember { mutableStateOf(false) }
    var showHwMenu by remember { mutableStateOf(false) }
    var showBgModeMenu by remember { mutableStateOf(false) }
    var showResumeMenu by remember { mutableStateOf(false) }
    var showSaveVolBrightMenu by remember { mutableStateOf(false) }
    var showSubtitleLangMenu by remember { mutableStateOf(false) }
    var showSubtitleColorMenu by remember { mutableStateOf(false) }
    var showSubtitleBgMenu by remember { mutableStateOf(false) }
    var showSubtitleSizeMenu by remember { mutableStateOf(false) }
    var showSubtitleFontMenu by remember { mutableStateOf(false) }
    var showNetworkMenu by remember { mutableStateOf(false) }

    // Backstack navigation manager for nested folder routing
    val screenStack = remember { mutableStateListOf<SettingsView>(SettingsView.Root) }

    BackHandler {
        when {
            showFolderManagerDialog -> showFolderManagerDialog = false
            showPrivacyPolicyDialog -> showPrivacyPolicyDialog = false
            showTermsDialog -> showTermsDialog = false
            showChangelogDialog -> showChangelogDialog = false
            screenStack.size > 1 -> {
                screenStack.removeAt(screenStack.lastIndex)
            }
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val titleText = when (val last = screenStack.last()) {
                        is SettingsView.Root -> "Aero-Player Preferences"
                        is SettingsView.Group -> last.group.title
                        is SettingsView.Folder -> last.folder.title
                    }
                    Text(titleText, fontWeight = FontWeight.ExtraBold)
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (screenStack.size > 1) {
                                screenStack.removeAt(screenStack.lastIndex)
                            } else {
                                onBack()
                            }
                        },
                        modifier = Modifier.testTag("settings_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // --- Elegant Breadcrumb Trail Navigation ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Home",
                    fontSize = 13.sp,
                    fontWeight = if (screenStack.last() is SettingsView.Root) FontWeight.Bold else FontWeight.Normal,
                    color = if (screenStack.last() is SettingsView.Root) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            while (screenStack.size > 1) {
                                screenStack.removeAt(screenStack.lastIndex)
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                if (screenStack.size > 1) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    val activeGroup = when (val last = screenStack.last()) {
                        is SettingsView.Group -> last.group
                        is SettingsView.Folder -> last.folder.group
                        else -> null
                    }
                    if (activeGroup != null) {
                        Text(
                            text = activeGroup.title,
                            fontSize = 13.sp,
                            fontWeight = if (screenStack.last() is SettingsView.Group) FontWeight.Bold else FontWeight.Normal,
                            color = if (screenStack.last() is SettingsView.Group) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    while (screenStack.size > 2) {
                                        screenStack.removeAt(screenStack.lastIndex)
                                    }
                                    if (screenStack.last() !is SettingsView.Group) {
                                        screenStack.add(SettingsView.Group(activeGroup))
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (screenStack.size > 2) {
                    val last = screenStack.last()
                    if (last is SettingsView.Folder) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = last.folder.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Scrollable Content Pane based on Backstack Routing
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (val currentScreen = screenStack.last()) {
                    is SettingsView.Root -> {
                        RootSettingsScreen(
                            onSelectGroup = { screenStack.add(SettingsView.Group(it)) }
                        )
                    }
                    is SettingsView.Group -> {
                        GroupSettingsScreen(
                            group = currentScreen.group,
                            onSelectFolder = { screenStack.add(SettingsView.Folder(it)) }
                        )
                    }
                    is SettingsView.Folder -> {
                        FolderSettingsScreen(
                            folder = currentScreen.folder,
                            viewModel = viewModel,
                            prefs = prefs,
                            showFolderManager = { showFolderManagerDialog = true },
                            showPrivacy = { showPrivacyPolicyDialog = true },
                            showTerms = { showTermsDialog = true },
                            showChangelog = { showChangelogDialog = true },
                            showThemeMenu = showThemeMenu,
                            onChangeThemeMenu = { showThemeMenu = it },
                            showStyleMenu = showStyleMenu,
                            onChangeStyleMenu = { showStyleMenu = it },
                            showOrientationMenu = showOrientationMenu,
                            onChangeOrientationMenu = { showOrientationMenu = it },
                            showSeekSecondsMenu = showSeekSecondsMenu,
                            onChangeSeekSecondsMenu = { showSeekSecondsMenu = it },
                            showHwMenu = showHwMenu,
                            onChangeHwMenu = { showHwMenu = it },
                            showBgModeMenu = showBgModeMenu,
                            onChangeBgModeMenu = { showBgModeMenu = it },
                            showResumeMenu = showResumeMenu,
                            onChangeResumeMenu = { showResumeMenu = it },
                            showSaveVolBrightMenu = showSaveVolBrightMenu,
                            onChangeSaveVolBrightMenu = { showSaveVolBrightMenu = it },
                            showSubtitleLangMenu = showSubtitleLangMenu,
                            onChangeSubtitleLangMenu = { showSubtitleLangMenu = it },
                            showSubtitleColorMenu = showSubtitleColorMenu,
                            onChangeSubtitleColorMenu = { showSubtitleColorMenu = it },
                            showSubtitleBgMenu = showSubtitleBgMenu,
                            onChangeSubtitleBgMenu = { showSubtitleBgMenu = it },
                            showSubtitleSizeMenu = showSubtitleSizeMenu,
                            onChangeSubtitleSizeMenu = { showSubtitleSizeMenu = it },
                            showSubtitleFontMenu = showSubtitleFontMenu,
                            onChangeSubtitleFontMenu = { showSubtitleFontMenu = it },
                            showNetworkMenu = showNetworkMenu,
                            onChangeNetworkMenu = { showNetworkMenu = it }
                        )
                    }
                }
            }
        }
    }

    // --- Dynamic Support Dialogs Integration with High Style Fidelity ---

    // 1. Library Storage Folders Dialog
    if (showFolderManagerDialog) {
        val favoriteFolders = remember(prefs.favoriteFoldersJson) {
            try {
                val array = org.json.JSONArray(prefs.favoriteFoldersJson)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    list.add(array.getString(i))
                }
                list
            } catch (e: Exception) {
                listOf("Movies", "Music", "WhatsApp")
            }
        }
        var customPathField by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showFolderManagerDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Library Storage Paths", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Verify or add explicit storage folders scanned by the Aero-Player engine. Active background threads monitor these channels for new media elements:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text("Active Scanner Paths:", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        favoriteFolders.forEach { folder ->
                            val fullPath = "/storage/emulated/0/$folder"
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Column {
                                            Text(folder, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text(fullPath, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.toggleFavoriteFolder(folder)
                                            android.widget.Toast.makeText(context, "Removed $folder", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    OutlinedTextField(
                        value = customPathField,
                        onValueChange = { customPathField = it },
                        label = { Text("Add Custom Subfolder Path") },
                        placeholder = { Text("e.g. Downloads") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            if (customPathField.isNotBlank()) {
                                viewModel.toggleFavoriteFolder(customPathField.trim())
                                android.widget.Toast.makeText(context, "Added scanner target: ${customPathField.trim()}", android.widget.Toast.LENGTH_SHORT).show()
                                customPathField = ""
                            }
                        },
                        enabled = customPathField.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Add Path", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showFolderManagerDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // 2. Privacy Policy Dialog
    if (showPrivacyPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyPolicyDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Privacy & Permission EULA", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Last updated: July 2026", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "Welcome to Aero-Player, a local-first offline media application. This policy details how our system processes files, credentials, and logs:",
                        fontSize = 12.sp
                    )
                    Text(
                        "1. Local Storage Access:\nAero-Player requests READ_EXTERNAL_STORAGE or media-specific storage permissions exclusively to locate and build indices of local video/audio files. File scan profiles never leave your physical device and are completely contained inside Room database files.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "2. Media Metrics Tracking:\nIf 'Playback History Tracker' is active, playback elapsed seconds are logged locally to help with resuming media and dashboard widgets. You can toggle this off in settings at any point.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "3. Zero Network Interception:\nAero-Player runs in sandboxed spaces and does not transmit tracking coordinates, logs, metadata, or play history to cloud storage networks. All analytics remain strictly localized.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showPrivacyPolicyDialog = false }) {
                    Text("I Understand")
                }
            }
        )
    }

    // 3. Terms & Conditions Dialog
    if (showTermsDialog) {
        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Terms of Use", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("End-User License Agreement (EULA)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "By running this software package, you explicitly accept the following licensing clauses and system boundaries:",
                        fontSize = 12.sp
                    )
                    Text(
                        "1. License Scope:\nWe grant you a personal, non-transferable, non-exclusive license to use the Aero-Player binary to play standard multimedia file formats for personal convenience.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "2. Absolute Codec Limitation:\nDecoding capabilities are powered by Android MediaCodec drivers and standard ExoPlayer configurations. We make no warranty that all arbitrary video bitstream profiles (including obscure formats or corrupted headers) will render perfectly.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "3. Liability Disclaimer:\nAero-Player processes physical disk files. We accept zero liability for storage failures, media file corruption, or permission conflicts that result in operating system index errors.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showTermsDialog = false }) {
                    Text("Accept Terms")
                }
            }
        )
    }

    // 4. Changelog Dialog
    if (showChangelogDialog) {
        AlertDialog(
            onDismissRequest = { showChangelogDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Aero-Player Changelog", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Version 1.2.0 - Stabilized Scan Pipelines", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "- Added absolute storage folder configuration tree.\n" +
                        "- Optimized ExoPlayer background audio handover.\n" +
                        "- Implemented on-demand .srt subtitle web lookup scraper.\n" +
                        "- Stabilized multi-thread Room DB transactions.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Version 1.1.0 - Advanced Player HUD", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "- Deployed side control drawers with full DSP Equalizer control.\n" +
                        "- Integrated A-B Loop markers and custom playlist serialization.\n" +
                        "- Added double-tap to seek with dynamic bubble feedback.\n" +
                        "- Enabled fluid swipe-based HUD brightness/volume adjusters.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Version 1.0.0 - Core Launch", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "- Core ExoPlayer pipeline integration.\n" +
                        "- Dynamic Material 3 theme and view switching engines.\n" +
                        "- Local media catalog scanners.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showChangelogDialog = false }) {
                    Text("Awesome")
                }
            }
        )
    }
}

// --- SUB-SCREEN RENDERING PANELS ---

@Composable
fun RootSettingsScreen(
    onSelectGroup: (SettingGroup) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "SYSTEM PREFERENCES",
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        SettingGroup.values().forEach { group ->
            Card(
                onClick = { onSelectGroup(group) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("group_card_${group.id}")
            ) {
                ListItem(
                    headlineContent = { Text(group.title, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                    supportingContent = { Text(group.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)) },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(group.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open group",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Made By Shubh jain",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun GroupSettingsScreen(
    group: SettingGroup,
    onSelectFolder: (SettingFolder) -> Unit
) {
    val folders = remember(group) {
        SettingFolder.values().filter { it.group == group }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Explanatory Group Hero Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    group.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Column {
                    Text(group.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                    Text(group.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text(
            text = "FOLDERS",
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            letterSpacing = 1.sp
        )

        folders.forEach { folder ->
            Card(
                onClick = { onSelectFolder(folder) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("folder_card_${folder.id}")
            ) {
                ListItem(
                    headlineContent = { Text(folder.title, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                    supportingContent = { Text(folder.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)) },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(folder.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open folder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
fun FolderSettingsScreen(
    folder: SettingFolder,
    viewModel: MainViewModel,
    prefs: com.example.data.database.PreferenceEntity,
    showFolderManager: () -> Unit,
    showPrivacy: () -> Unit,
    showTerms: () -> Unit,
    showChangelog: () -> Unit,
    // Unified Dropdown State Providers
    showThemeMenu: Boolean,
    onChangeThemeMenu: (Boolean) -> Unit,
    showStyleMenu: Boolean,
    onChangeStyleMenu: (Boolean) -> Unit,
    showOrientationMenu: Boolean,
    onChangeOrientationMenu: (Boolean) -> Unit,
    showSeekSecondsMenu: Boolean,
    onChangeSeekSecondsMenu: (Boolean) -> Unit,
    showHwMenu: Boolean,
    onChangeHwMenu: (Boolean) -> Unit,
    showBgModeMenu: Boolean,
    onChangeBgModeMenu: (Boolean) -> Unit,
    showResumeMenu: Boolean,
    onChangeResumeMenu: (Boolean) -> Unit,
    showSaveVolBrightMenu: Boolean,
    onChangeSaveVolBrightMenu: (Boolean) -> Unit,
    showSubtitleLangMenu: Boolean,
    onChangeSubtitleLangMenu: (Boolean) -> Unit,
    showSubtitleColorMenu: Boolean,
    onChangeSubtitleColorMenu: (Boolean) -> Unit,
    showSubtitleBgMenu: Boolean,
    onChangeSubtitleBgMenu: (Boolean) -> Unit,
    showSubtitleSizeMenu: Boolean,
    onChangeSubtitleSizeMenu: (Boolean) -> Unit,
    showSubtitleFontMenu: Boolean,
    onChangeSubtitleFontMenu: (Boolean) -> Unit,
    showNetworkMenu: Boolean,
    onChangeNetworkMenu: (Boolean) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "${folder.title.uppercase()} PREFERENCES",
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                when (folder) {
                    SettingFolder.themeEngine -> {
                        // appThemeEngine setting row
                        ListItem(
                            headlineContent = { Text("App Theme Engine", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Selected Theme: ${prefs.themeMode}") },
                            leadingContent = { Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeThemeMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showThemeMenu, onDismissRequest = { onChangeThemeMenu(false) }) {
                                        listOf("System", "Light", "Dark").forEach { theme ->
                                            DropdownMenuItem(
                                                text = { Text(theme) },
                                                onClick = {
                                                    viewModel.updateTheme(theme)
                                                    onChangeThemeMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeThemeMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // material3DynamicColors setting row
                        ListItem(
                            headlineContent = { Text("Material 3 Style (Dynamic Colors)", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Use system dynamic accent colors on Android 12+") },
                            leadingContent = { Icon(Icons.Default.ColorLens, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(
                                    checked = prefs.useDynamicColor,
                                    onCheckedChange = { viewModel.updateDynamicColor(it) }
                                )
                            }
                        )
                    }

                    SettingFolder.layoutOptions -> {
                        // mediaListDisplayStyle setting row
                        ListItem(
                            headlineContent = { Text("Media List Display Style", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Selected Layout: ${prefs.listStyle}") },
                            leadingContent = { Icon(Icons.Default.GridView, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeStyleMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showStyleMenu, onDismissRequest = { onChangeStyleMenu(false) }) {
                                        listOf("List", "Grid").forEach { style ->
                                            DropdownMenuItem(
                                                text = { Text(style) },
                                                onClick = {
                                                    viewModel.updateListStyle(style)
                                                    onChangeStyleMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeStyleMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // groupWiseFolderStyle setting row
                        ListItem(
                            headlineContent = { Text("Group-wise Folder Style", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Organize media folders in structural groups") },
                            leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(
                                    checked = prefs.useGroupWiseFolderStyle,
                                    onCheckedChange = { viewModel.updateGroupWiseFolderStyle(it) }
                                )
                            }
                        )
                    }

                    SettingFolder.playerControls -> {
                        // doubleTapSeekDuration setting row
                        ListItem(
                            headlineContent = { Text("Double-Tap Seek Duration", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Jump distance: ${prefs.doubleTapSeekSeconds} seconds") },
                            leadingContent = { Icon(Icons.Default.Gesture, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeSeekSecondsMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showSeekSecondsMenu, onDismissRequest = { onChangeSeekSecondsMenu(false) }) {
                                        listOf(5, 10, 15, 20, 30).forEach { seconds ->
                                            DropdownMenuItem(
                                                text = { Text("$seconds seconds") },
                                                onClick = {
                                                    viewModel.updateDoubleTapSeekSeconds(seconds)
                                                    onChangeSeekSecondsMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeSeekSecondsMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // videoScreenOrientation setting row
                        ListItem(
                            headlineContent = { Text("Video Screen Orientation", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Constraint: ${prefs.defaultOrientation}") },
                            leadingContent = { Icon(Icons.Default.ScreenRotation, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeOrientationMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showOrientationMenu, onDismissRequest = { onChangeOrientationMenu(false) }) {
                                        listOf("System Auto", "Portrait", "Landscape", "Reverse Portrait", "Reverse Landscape").forEach { orient ->
                                            DropdownMenuItem(
                                                text = { Text(orient) },
                                                onClick = {
                                                    viewModel.updateDefaultOrientation(orient)
                                                    onChangeOrientationMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeOrientationMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // playerRotationLock setting row
                        ListItem(
                            headlineContent = { Text("Player Rotation Lock", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Lock video player rotation on screen entry") },
                            leadingContent = { Icon(Icons.Default.ScreenLockRotation, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(
                                    checked = prefs.rotationLock,
                                    onCheckedChange = { viewModel.toggleRotationLock(it) }
                                )
                            }
                        )
                    }

                    SettingFolder.coreProcessing -> {
                        // hardwareAcceleration setting row
                        ListItem(
                            headlineContent = { Text("Hardware Acceleration", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Current Mode: ${prefs.hwAcceleration}") },
                            leadingContent = { Icon(Icons.Default.Hardware, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeHwMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showHwMenu, onDismissRequest = { onChangeHwMenu(false) }) {
                                        listOf("DISABLED_SOFTWARE", "DECODING_ONLY", "FULL_HARDWARE").forEach { mode ->
                                            DropdownMenuItem(
                                                text = { Text(mode) },
                                                onClick = {
                                                    viewModel.updateHwAcceleration(mode)
                                                    onChangeHwMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeHwMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // backgroundPlaybackPip setting row
                        ListItem(
                            headlineContent = { Text("Background Playback / PiP", fontWeight = FontWeight.SemiBold) },
                            supportingContent = {
                                val label = when (prefs.backgroundMode) {
                                    "STOP_PLAYBACK" -> "Stop Playback"
                                    "PLAY_BACKGROUND_AUDIO" -> "Play Background Audio"
                                    "LAUNCH_PIP_MODE" -> "Launch PiP Mode"
                                    else -> prefs.backgroundMode
                                }
                                Text("Action: $label")
                            },
                            leadingContent = { Icon(Icons.Default.PictureInPicture, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeBgModeMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showBgModeMenu, onDismissRequest = { onChangeBgModeMenu(false) }) {
                                        listOf(
                                            "STOP_PLAYBACK" to "Stop Playback",
                                            "PLAY_BACKGROUND_AUDIO" to "Play Background Audio",
                                            "LAUNCH_PIP_MODE" to "Launch PiP Mode"
                                        ).forEach { (mode, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    viewModel.updateBackgroundMode(mode)
                                                    onChangeBgModeMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeBgModeMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // resumePlaybackBehavior setting row
                        ListItem(
                            headlineContent = { Text("Resume Playback Behavior", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("When opening partially played files: ${prefs.resumePlaybackBehavior}") },
                            leadingContent = { Icon(Icons.Default.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeResumeMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showResumeMenu, onDismissRequest = { onChangeResumeMenu(false) }) {
                                        listOf("Ask Every Time", "Always Resume", "Always Start from Beginning").forEach { behavior ->
                                            DropdownMenuItem(
                                                text = { Text(behavior) },
                                                onClick = {
                                                    viewModel.updateResumePlaybackBehavior(behavior)
                                                    onChangeResumeMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeResumeMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // perVideoPlaybackSettings setting row
                        ListItem(
                            headlineContent = { Text("Per-Video Playback Settings", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Track individual file speeds, zooms, and audio volumes") },
                            leadingContent = { Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(
                                    checked = prefs.usePerVideoSettings,
                                    onCheckedChange = { viewModel.toggleUsePerVideoSettings(it) }
                                )
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // saveVolumeBrightnessLevel setting row
                        ListItem(
                            headlineContent = { Text("Save Volume & Brightness Level", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Behavior: ${when (prefs.saveVolumeBrightnessBehavior) {
                                "Global" -> "Global levels"
                                "Individual" -> "Individual levels per video"
                                else -> "Do not save levels"
                            }}") },
                            leadingContent = { Icon(Icons.Default.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeSaveVolBrightMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(
                                        expanded = showSaveVolBrightMenu,
                                        onDismissRequest = { onChangeSaveVolBrightMenu(false) }
                                    ) {
                                        listOf(
                                            "None" to "Do not save levels",
                                            "Global" to "Global levels",
                                            "Individual" to "Individual levels per video"
                                        ).forEach { (valStr, labelStr) ->
                                            DropdownMenuItem(
                                                text = { Text(labelStr) },
                                                onClick = {
                                                    viewModel.updateSaveVolumeBrightnessBehavior(valStr)
                                                    onChangeSaveVolBrightMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeSaveVolBrightMenu(true) }
                        )
                    }

                    SettingFolder.subtitleSystem -> {
                        // defaultSubtitleLanguage setting row
                        ListItem(
                            headlineContent = { Text("Default Subtitle Language", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Selected language: ${prefs.defaultSubtitleLanguage}") },
                            leadingContent = { Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeSubtitleLangMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showSubtitleLangMenu, onDismissRequest = { onChangeSubtitleLangMenu(false) }) {
                                        listOf("English", "Hindi", "Spanish", "French", "German", "Japanese", "Chinese", "Russian", "Arabic", "Portuguese", "Bengali").forEach { lang ->
                                            DropdownMenuItem(
                                                text = { Text(lang) },
                                                onClick = {
                                                    viewModel.updateSubtitleLanguage(lang)
                                                    onChangeSubtitleLangMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeSubtitleLangMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // subtitleTextColor setting row
                        ListItem(
                            headlineContent = { Text("Subtitle Text Color", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Hex value: ${prefs.subtitleTextColor}") },
                            leadingContent = { Icon(Icons.Default.ColorLens, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeSubtitleColorMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showSubtitleColorMenu, onDismissRequest = { onChangeSubtitleColorMenu(false) }) {
                                        listOf(
                                            "#FFFFFF" to "White",
                                            "#FFFF00" to "Yellow",
                                            "#00FF00" to "Green",
                                            "#00FFFF" to "Cyan",
                                            "#FF00FF" to "Magenta",
                                            "#FF3333" to "Soft Red",
                                            "#000000" to "Black"
                                        ).forEach { (hex, name) ->
                                            DropdownMenuItem(
                                                text = { Text(name) },
                                                onClick = {
                                                    viewModel.updateSubtitleCustomization(
                                                        background = prefs.subtitleBackground,
                                                        textColor = hex,
                                                        size = prefs.subtitleSize,
                                                        fontStyle = prefs.subtitleFontStyle
                                                    )
                                                    onChangeSubtitleColorMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeSubtitleColorMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // subtitleBackground setting row
                        ListItem(
                            headlineContent = { Text("Subtitle Background", fontWeight = FontWeight.SemiBold) },
                            supportingContent = {
                                val bgLabel = when (prefs.subtitleBackground) {
                                    "#00000000" -> "Transparent (Default)"
                                    "#80000000" -> "Semi-Transparent Black"
                                    "#FF000000" -> "Solid Black"
                                    "#80333333" -> "Semi-Transparent Dark Gray"
                                    else -> prefs.subtitleBackground
                                }
                                Text(bgLabel)
                            },
                            leadingContent = { Icon(Icons.Default.Texture, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeSubtitleBgMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showSubtitleBgMenu, onDismissRequest = { onChangeSubtitleBgMenu(false) }) {
                                        listOf(
                                            "#00000000" to "Transparent",
                                            "#80000000" to "Semi-Transparent Black",
                                            "#FF000000" to "Solid Black",
                                            "#80333333" to "Semi-Transparent Dark Gray"
                                        ).forEach { (hex, name) ->
                                            DropdownMenuItem(
                                                text = { Text(name) },
                                                onClick = {
                                                    viewModel.updateSubtitleCustomization(
                                                        background = hex,
                                                        textColor = prefs.subtitleTextColor,
                                                        size = prefs.subtitleSize,
                                                        fontStyle = prefs.subtitleFontStyle
                                                    )
                                                    onChangeSubtitleBgMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeSubtitleBgMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // subtitleTextSize setting row
                        ListItem(
                            headlineContent = { Text("Subtitle Text Size", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("${prefs.subtitleSize.toInt()} sp") },
                            leadingContent = { Icon(Icons.Default.FormatSize, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeSubtitleSizeMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showSubtitleSizeMenu, onDismissRequest = { onChangeSubtitleSizeMenu(false) }) {
                                        listOf(12f, 14f, 16f, 18f, 20f, 24f).forEach { sz ->
                                            DropdownMenuItem(
                                                text = { Text("${sz.toInt()} sp") },
                                                onClick = {
                                                    viewModel.updateSubtitleCustomization(
                                                        background = prefs.subtitleBackground,
                                                        textColor = prefs.subtitleTextColor,
                                                        size = sz,
                                                        fontStyle = prefs.subtitleFontStyle
                                                    )
                                                    onChangeSubtitleSizeMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeSubtitleSizeMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // subtitleFontStyle setting row
                        ListItem(
                            headlineContent = { Text("Subtitle Font Style", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text(prefs.subtitleFontStyle) },
                            leadingContent = { Icon(Icons.Default.FormatItalic, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeSubtitleFontMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showSubtitleFontMenu, onDismissRequest = { onChangeSubtitleFontMenu(false) }) {
                                        listOf("Normal", "Bold", "Italic").forEach { style ->
                                            DropdownMenuItem(
                                                text = { Text(style) },
                                                onClick = {
                                                    viewModel.updateSubtitleCustomization(
                                                        background = prefs.subtitleBackground,
                                                        textColor = prefs.subtitleTextColor,
                                                        size = prefs.subtitleSize,
                                                        fontStyle = style
                                                    )
                                                    onChangeSubtitleFontMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeSubtitleFontMenu(true) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // Subtitle Live Preview block
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "LIVE SUBTITLE PREVIEW",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF151515)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "[Mock Video Scene Preview]",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray.copy(alpha = 0.4f)
                                )

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            try {
                                                Color(android.graphics.Color.parseColor(prefs.subtitleBackground))
                                            } catch (e: Exception) {
                                                Color.Transparent
                                            }
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Aero-Player renders beautiful subtitles in sync.",
                                        color = try {
                                            Color(android.graphics.Color.parseColor(prefs.subtitleTextColor))
                                        } catch (e: Exception) {
                                            Color.White
                                        },
                                        fontSize = prefs.subtitleSize.sp,
                                        fontWeight = if (prefs.subtitleFontStyle == "Bold") FontWeight.Bold else FontWeight.Normal,
                                        style = if (prefs.subtitleFontStyle == "Italic") MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) else MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    SettingFolder.networkRestrictions -> {
                        // meteredNetworkPolicy setting row
                        ListItem(
                            headlineContent = { Text("Metered Network Policy", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Data stream action: ${prefs.meteredNetworkAction}") },
                            leadingContent = { Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { onChangeNetworkMenu(true) }) {
                                        Text("Change")
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(expanded = showNetworkMenu, onDismissRequest = { onChangeNetworkMenu(false) }) {
                                        listOf("WARN_BEFORE_STREAMING", "BLOCK_STREAMING", "ALLOW_STREAMING").forEach { action ->
                                            DropdownMenuItem(
                                                text = { Text(action) },
                                                onClick = {
                                                    viewModel.updateMeteredNetworkAction(action)
                                                    onChangeNetworkMenu(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onChangeNetworkMenu(true) }
                        )
                    }

                    SettingFolder.loggingEngine -> {
                        // playbackHistoryTracker setting row
                        ListItem(
                            headlineContent = { Text("Playback History Tracker", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Log file progression events to database cache") },
                            leadingContent = { Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(
                                    checked = prefs.playHistoryEnabled,
                                    onCheckedChange = { viewModel.togglePlayHistoryEnabled(it) }
                                )
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // queueHistoryCache setting row (Video and Audio queue persistence tracking)
                        ListItem(
                            headlineContent = { Text("Save Video Queue History", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Remember active video list track positions") },
                            leadingContent = { Icon(Icons.Default.Queue, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(
                                    checked = prefs.saveVideoQueueHistory,
                                    onCheckedChange = { viewModel.toggleSaveVideoQueueHistory(it) }
                                )
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        ListItem(
                            headlineContent = { Text("Save Audio Queue History", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Remember active audio play queue across reboots") },
                            leadingContent = { Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(
                                    checked = prefs.saveAudioQueueHistory,
                                    onCheckedChange = { viewModel.toggleSaveAudioQueueHistory(it) }
                                )
                            }
                        )
                    }

                    SettingFolder.storageManagement -> {
                        // mediaLibraryFolders dialog launcher setting row
                        ListItem(
                            headlineContent = { Text("Media Library Folders", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Configure and verify scanned directory system paths") },
                            leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Manage", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            modifier = Modifier.clickable { showFolderManager() }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // autoRescanOnLaunch setting row
                        ListItem(
                            headlineContent = { Text("Auto-Rescan on Launch", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Rescan file system structures during initialization stage") },
                            leadingContent = { Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(
                                    checked = prefs.autoScanEnabled,
                                    onCheckedChange = { viewModel.toggleAutoScan(it) }
                                )
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // resetMediaDatabaseCache action setting row
                        ListItem(
                            headlineContent = { Text("Reset Media Database Cache", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Clear catalog data and query device storage again") },
                            leadingContent = { Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                TextButton(onClick = {
                                    viewModel.scanLocalMedia()
                                    android.widget.Toast.makeText(context, "Rescanning local device storage...", android.widget.Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("Rebuild", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // wipePlaybackProgressCache action setting row
                        ListItem(
                            headlineContent = { Text("Wipe Playback Progress Cache", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Erase resume times, landmarks, and playlist indices") },
                            leadingContent = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                TextButton(onClick = {
                                    viewModel.clearHistory()
                                    android.widget.Toast.makeText(context, "History wiped permanently", android.widget.Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("Wipe", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        )
                    }

                    SettingFolder.legalLegalities -> {
                        // privacyPolicy setting row
                        ListItem(
                            headlineContent = { Text("Privacy Policy", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Review system data processing and file permissions EULA") },
                            leadingContent = { Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.clickable { showPrivacy() }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // termsAndConditions setting row
                        ListItem(
                            headlineContent = { Text("Terms and Conditions", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Aero-Player usage constraints and software licensing model") },
                            leadingContent = { Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.clickable { showTerms() }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // changelog setting row
                        ListItem(
                            headlineContent = { Text("Changelog", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Explore version v1.2.0 capability increments & bugfixes") },
                            leadingContent = { Icon(Icons.Default.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.clickable { showChangelog() }
                        )
                    }
                }
            }
        }
    }
}
