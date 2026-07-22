package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.*

enum class NativeSettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
) {
    DISPLAY("Display & Theme", "App theme, dynamic colors, and media list layout", Icons.Default.Palette),
    PLAYBACK("Playback Engine", "Hardware acceleration, seek intervals, orientation, and PiP mode", Icons.Default.PlayCircle),
    SUBTITLES("Subtitles & Captions", "Language, font size, text colors, encodings, and shadow effects", Icons.Default.Subtitles),
    STORAGE("Storage & Scanner", "Library folders, all files permission, and database index rebuild", Icons.Default.Storage),
    DATA_PRIVACY("Data & Privacy", "Metered network alerts and playback history tracking", Icons.Default.Security),
    ABOUT("About & App Info", "Version diagnostic tools, system info, EULA, and release notes", Icons.Default.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val prefs by viewModel.preferencesState.collectAsState()
    val context = LocalContext.current

    // Navigation & View state controllers
    var selectedCategory by remember { mutableStateOf<NativeSettingsCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showAboutScreen by remember { mutableStateOf(false) }

    // Dialog state controllers
    var showFolderManagerDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }

    // Dropdown menu state controllers
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
    var showSubtitlePresetMenu by remember { mutableStateOf(false) }
    var showSubtitleEncodingMenu by remember { mutableStateOf(false) }
    var showShadowColorMenu by remember { mutableStateOf(false) }
    var showOutlineColorMenu by remember { mutableStateOf(false) }
    var showNetworkMenu by remember { mutableStateOf(false) }

    BackHandler {
        when {
            showAboutScreen -> showAboutScreen = false
            showFolderManagerDialog -> showFolderManagerDialog = false
            showPrivacyPolicyDialog -> showPrivacyPolicyDialog = false
            showTermsDialog -> showTermsDialog = false
            showChangelogDialog -> showChangelogDialog = false
            searchQuery.isNotEmpty() -> searchQuery = ""
            selectedCategory != null -> selectedCategory = null
            else -> onBack()
        }
    }

    if (showAboutScreen) {
        AboutScreen(
            onBack = { showAboutScreen = false },
            showPrivacy = { showPrivacyPolicyDialog = true },
            showTerms = { showTermsDialog = true },
            showChangelog = { showChangelogDialog = true }
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedCategory?.title ?: "Settings",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (searchQuery.isNotEmpty()) {
                                searchQuery = ""
                            } else if (selectedCategory != null) {
                                selectedCategory = null
                            } else {
                                onBack()
                            }
                        },
                        modifier = Modifier.testTag("settings_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedCategory != null || searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            selectedCategory = null
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.Home, contentDescription = "Home Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Search Bar at Top of Settings
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                placeholder = { Text("Search settings...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (searchQuery.isNotBlank()) {
                    // Search Mode
                    SearchResultsView(
                        query = searchQuery,
                        viewModel = viewModel,
                        prefs = prefs,
                        onOpenFolderManager = { showFolderManagerDialog = true },
                        onOpenAbout = { showAboutScreen = true },
                        onOpenPrivacy = { showPrivacyPolicyDialog = true },
                        onOpenTerms = { showTermsDialog = true },
                        onOpenChangelog = { showChangelogDialog = true }
                    )
                } else if (selectedCategory == null) {
                    // Top-Level Categories View (Native Android Preference Tree)
                    NativeSettingsCategoryOverview(
                        onSelectCategory = { category ->
                            if (category == NativeSettingsCategory.ABOUT) {
                                showAboutScreen = true
                            } else {
                                selectedCategory = category
                            }
                        }
                    )
                } else {
                    // Category Detail View
                    when (selectedCategory) {
                        NativeSettingsCategory.DISPLAY -> {
                            DisplaySettingsGroup(
                                viewModel = viewModel,
                                prefs = prefs,
                                showThemeMenu = showThemeMenu,
                                onChangeThemeMenu = { showThemeMenu = it },
                                showStyleMenu = showStyleMenu,
                                onChangeStyleMenu = { showStyleMenu = it }
                            )
                        }
                        NativeSettingsCategory.PLAYBACK -> {
                            PlaybackSettingsGroup(
                                viewModel = viewModel,
                                prefs = prefs,
                                showSeekSecondsMenu = showSeekSecondsMenu,
                                onChangeSeekSecondsMenu = { showSeekSecondsMenu = it },
                                showOrientationMenu = showOrientationMenu,
                                onChangeOrientationMenu = { showOrientationMenu = it },
                                showHwMenu = showHwMenu,
                                onChangeHwMenu = { showHwMenu = it },
                                showBgModeMenu = showBgModeMenu,
                                onChangeBgModeMenu = { showBgModeMenu = it },
                                showResumeMenu = showResumeMenu,
                                onChangeResumeMenu = { showResumeMenu = it },
                                showSaveVolBrightMenu = showSaveVolBrightMenu,
                                onChangeSaveVolBrightMenu = { showSaveVolBrightMenu = it }
                            )
                        }
                        NativeSettingsCategory.SUBTITLES -> {
                            SubtitleSettingsGroup(
                                viewModel = viewModel,
                                prefs = prefs,
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
                                showSubtitlePresetMenu = showSubtitlePresetMenu,
                                onChangeSubtitlePresetMenu = { showSubtitlePresetMenu = it },
                                showSubtitleEncodingMenu = showSubtitleEncodingMenu,
                                onChangeSubtitleEncodingMenu = { showSubtitleEncodingMenu = it },
                                showShadowColorMenu = showShadowColorMenu,
                                onChangeShadowColorMenu = { showShadowColorMenu = it },
                                showOutlineColorMenu = showOutlineColorMenu,
                                onChangeOutlineColorMenu = { showOutlineColorMenu = it }
                            )
                        }
                        NativeSettingsCategory.STORAGE -> {
                            StorageSettingsGroup(
                                viewModel = viewModel,
                                prefs = prefs,
                                showFolderManager = { showFolderManagerDialog = true }
                            )
                        }
                        NativeSettingsCategory.DATA_PRIVACY -> {
                            DataPrivacySettingsGroup(
                                viewModel = viewModel,
                                prefs = prefs,
                                showNetworkMenu = showNetworkMenu,
                                onChangeNetworkMenu = { showNetworkMenu = it }
                            )
                        }
                        NativeSettingsCategory.ABOUT -> {
                            AboutSettingsGroup(
                                showAbout = { showAboutScreen = true },
                                showPrivacy = { showPrivacyPolicyDialog = true },
                                showTerms = { showTermsDialog = true },
                                showChangelog = { showChangelogDialog = true }
                            )
                        }
                        else -> {}
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // --- SUPPORT DIALOGS ---

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
                        "Verify or add explicit storage folders scanned by the Aero Player engine. Active background threads monitor these channels for new media elements:",
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
                        "Welcome to Aero Player, a local-first offline media application. This policy details how our system processes files, credentials, and logs:",
                        fontSize = 12.sp
                    )
                    Text(
                        "1. Local Storage Access:\nAero Player requests READ_EXTERNAL_STORAGE or media-specific storage permissions exclusively to locate and build indices of local video/audio files. File scan profiles never leave your physical device.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "2. Media Metrics Tracking:\nIf 'Playback History Tracker' is active, playback elapsed seconds are logged locally to help with resuming media. You can toggle this off in settings at any point.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "3. Zero Network Interception:\nAero Player runs in sandboxed spaces and does not transmit tracking coordinates or play history to cloud networks. All analytics remain strictly localized.",
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
                        "1. License Scope:\nWe grant you a personal, non-transferable, non-exclusive license to use the Aero Player binary to play standard multimedia file formats for personal convenience.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "2. Codec Capabilities:\nDecoding capabilities are powered by Android MediaCodec drivers and standard Media3/ExoPlayer configurations with bundled FFmpeg extensions.",
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
                    Text("Aero Player Changelog", fontWeight = FontWeight.Bold)
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
                    Text("Version 1.5.0 - Native UI & Decoders", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "- Native Android Settings Tree Structure with instant search.\n" +
                        "- Redesigned About page with system diagnostics & hardware info.\n" +
                        "- Integrated Jellyfin Media3 FFmpeg software decoder extension.\n" +
                        "- Support for Dolby AC3 / EAC-3 multi-channel audio tracks.\n" +
                        "- Advanced subtitle encodings, custom shadows, and live preview.",
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showChangelogDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

// --- CATEGORIES OVERVIEW (Native Android Settings Style) ---
@Composable
private fun NativeSettingsCategoryOverview(
    onSelectCategory: (NativeSettingsCategory) -> Unit
) {
    Text(
        text = "PREFERENCE CATEGORIES",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp)
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            NativeSettingsCategory.values().forEachIndexed { index, category ->
                ListItem(
                    headlineContent = {
                        Text(category.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    },
                    supportingContent = {
                        Text(category.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    leadingContent = {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    category.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Navigate",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable { onSelectCategory(category) }
                )
                if (index < NativeSettingsCategory.values().size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

// --- CATEGORY DISPLAY SETTINGS ---
@Composable
private fun DisplaySettingsGroup(
    viewModel: MainViewModel,
    prefs: com.example.data.database.PreferenceEntity,
    showThemeMenu: Boolean,
    onChangeThemeMenu: (Boolean) -> Unit,
    showStyleMenu: Boolean,
    onChangeStyleMenu: (Boolean) -> Unit
) {
    Text("DISPLAY & INTERFACE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Theme Engine
            ListItem(
                headlineContent = { Text("App Theme Engine", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Selected Theme: ${prefs.themeMode}") },
                leadingContent = { SettingsIconBadge(Icons.Default.Palette) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { onChangeThemeMenu(true) }) {
                            Text(prefs.themeMode)
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Dynamic Colors
            ListItem(
                headlineContent = { Text("Material 3 Style (Dynamic Colors)", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Use system dynamic accent colors on Android 12+") },
                leadingContent = { SettingsIconBadge(Icons.Default.ColorLens) },
                trailingContent = {
                    Switch(
                        checked = prefs.useDynamicColor,
                        onCheckedChange = { viewModel.updateDynamicColor(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // List Style
            ListItem(
                headlineContent = { Text("Media List Display Style", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Selected Layout: ${prefs.listStyle}") },
                leadingContent = { SettingsIconBadge(Icons.Default.GridView) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { onChangeStyleMenu(true) }) {
                            Text(prefs.listStyle)
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Folder Grouping
            ListItem(
                headlineContent = { Text("Group-wise Folder Style", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Organize media folders in structural groups") },
                leadingContent = { SettingsIconBadge(Icons.Default.Folder) },
                trailingContent = {
                    Switch(
                        checked = prefs.useGroupWiseFolderStyle,
                        onCheckedChange = { viewModel.updateGroupWiseFolderStyle(it) }
                    )
                }
            )
        }
    }
}

// --- CATEGORY PLAYBACK SETTINGS ---
@Composable
private fun PlaybackSettingsGroup(
    viewModel: MainViewModel,
    prefs: com.example.data.database.PreferenceEntity,
    showSeekSecondsMenu: Boolean,
    onChangeSeekSecondsMenu: (Boolean) -> Unit,
    showOrientationMenu: Boolean,
    onChangeOrientationMenu: (Boolean) -> Unit,
    showHwMenu: Boolean,
    onChangeHwMenu: (Boolean) -> Unit,
    showBgModeMenu: Boolean,
    onChangeBgModeMenu: (Boolean) -> Unit,
    showResumeMenu: Boolean,
    onChangeResumeMenu: (Boolean) -> Unit,
    showSaveVolBrightMenu: Boolean,
    onChangeSaveVolBrightMenu: (Boolean) -> Unit
) {
    Text("PLAYBACK ENGINE & CONTROLS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Hardware Acceleration
            ListItem(
                headlineContent = { Text("Hardware Acceleration", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Decoder Mode: ${prefs.hwAcceleration}") },
                leadingContent = { SettingsIconBadge(Icons.Default.Hardware) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { onChangeHwMenu(true) }) {
                            Text(prefs.hwAcceleration)
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Double Tap Seek
            ListItem(
                headlineContent = { Text("Double-Tap Seek Duration", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("${prefs.doubleTapSeekSeconds} seconds jump") },
                leadingContent = { SettingsIconBadge(Icons.Default.Gesture) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { onChangeSeekSecondsMenu(true) }) {
                            Text("${prefs.doubleTapSeekSeconds}s")
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Screen Orientation
            ListItem(
                headlineContent = { Text("Video Screen Orientation", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Constraint: ${prefs.defaultOrientation}") },
                leadingContent = { SettingsIconBadge(Icons.Default.ScreenRotation) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { onChangeOrientationMenu(true) }) {
                            Text(prefs.defaultOrientation)
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Rotation Lock
            ListItem(
                headlineContent = { Text("Player Rotation Lock", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Lock player orientation on entry") },
                leadingContent = { SettingsIconBadge(Icons.Default.ScreenLockRotation) },
                trailingContent = {
                    Switch(
                        checked = prefs.rotationLock,
                        onCheckedChange = { viewModel.toggleRotationLock(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Background Playback / PiP
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
                leadingContent = { SettingsIconBadge(Icons.Default.PictureInPicture) },
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Resume Playback Behavior
            ListItem(
                headlineContent = { Text("Resume Playback Behavior", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("When re-opening media: ${prefs.resumePlaybackBehavior}") },
                leadingContent = { SettingsIconBadge(Icons.Default.Restore) },
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Per-Video Playback Memory
            ListItem(
                headlineContent = { Text("Per-Video Playback Settings", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Track individual speeds, zooms, and volumes") },
                leadingContent = { SettingsIconBadge(Icons.Default.Tune) },
                trailingContent = {
                    Switch(
                        checked = prefs.usePerVideoSettings,
                        onCheckedChange = { viewModel.toggleUsePerVideoSettings(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Save Volume & Brightness Level
            ListItem(
                headlineContent = { Text("Save Volume & Brightness Level", fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Text(when (prefs.saveVolumeBrightnessBehavior) {
                        "Global" -> "Global levels"
                        "Individual" -> "Individual levels per video"
                        else -> "Do not save levels"
                    })
                },
                leadingContent = { SettingsIconBadge(Icons.Default.VolumeUp) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { onChangeSaveVolBrightMenu(true) }) {
                            Text("Change")
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = showSaveVolBrightMenu, onDismissRequest = { onChangeSaveVolBrightMenu(false) }) {
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
    }
}

// --- CATEGORY SUBTITLES SETTINGS ---
@Composable
private fun SubtitleSettingsGroup(
    viewModel: MainViewModel,
    prefs: com.example.data.database.PreferenceEntity,
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
    showSubtitlePresetMenu: Boolean,
    onChangeSubtitlePresetMenu: (Boolean) -> Unit,
    showSubtitleEncodingMenu: Boolean,
    onChangeSubtitleEncodingMenu: (Boolean) -> Unit,
    showShadowColorMenu: Boolean,
    onChangeShadowColorMenu: (Boolean) -> Unit,
    showOutlineColorMenu: Boolean,
    onChangeOutlineColorMenu: (Boolean) -> Unit
) {
    Text("SUBTITLES & CAPTIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Language
            ListItem(
                headlineContent = { Text("Default Subtitle Language", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Selected language: ${prefs.defaultSubtitleLanguage}") },
                leadingContent = { SettingsIconBadge(Icons.Default.Language) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { onChangeSubtitleLangMenu(true) }) {
                            Text(prefs.defaultSubtitleLanguage)
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Preset
            ListItem(
                headlineContent = { Text("Subtitle Preset", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Preset: ${prefs.subtitlePreset}") },
                leadingContent = { SettingsIconBadge(Icons.Default.Style) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { onChangeSubtitlePresetMenu(true) }) {
                            Text(prefs.subtitlePreset)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = showSubtitlePresetMenu, onDismissRequest = { onChangeSubtitlePresetMenu(false) }) {
                            listOf("Custom", "White on Black", "Yellow on Black", "White Outline", "Yellow Outline", "Soft Shadow").forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset) },
                                    onClick = {
                                        viewModel.applySubtitlePreset(preset)
                                        onChangeSubtitlePresetMenu(false)
                                    }
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.clickable { onChangeSubtitlePresetMenu(true) }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Subtitle Text Color
            ListItem(
                headlineContent = { Text("Subtitle Text Color", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Hex: ${prefs.subtitleTextColor}") },
                leadingContent = { SettingsIconBadge(Icons.Default.ColorLens) },
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Subtitle Text Size
            ListItem(
                headlineContent = { Text("Subtitle Text Size", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("${prefs.subtitleSize.toInt()} sp") },
                leadingContent = { SettingsIconBadge(Icons.Default.FormatSize) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { onChangeSubtitleSizeMenu(true) }) {
                            Text("${prefs.subtitleSize.toInt()} sp")
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Subtitle Encoding
            ListItem(
                headlineContent = { Text("Subtitle File Encoding", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Encoding format: ${prefs.subtitleEncoding}") },
                leadingContent = { SettingsIconBadge(Icons.Default.Code) },
                trailingContent = {
                    Box {
                        TextButton(onClick = { onChangeSubtitleEncodingMenu(true) }) {
                            Text(prefs.subtitleEncoding)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = showSubtitleEncodingMenu, onDismissRequest = { onChangeSubtitleEncodingMenu(false) }) {
                            listOf("UTF-8", "ISO-8859-1", "Windows-1252", "UTF-16", "US-ASCII", "Big5", "GBK", "Shift_JIS", "EUC-KR").forEach { enc ->
                                DropdownMenuItem(
                                    text = { Text(enc) },
                                    onClick = {
                                        viewModel.updateAdvancedSubtitleSettings(encoding = enc)
                                        onChangeSubtitleEncodingMenu(false)
                                    }
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.clickable { onChangeSubtitleEncodingMenu(true) }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Subtitle Live Preview Box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "LIVE SUBTITLE PREVIEW",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
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
                        text = "[ Video Frame Background Preview ]",
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
                            text = "Aero Player renders crisp, beautiful subtitles.",
                            color = try {
                                val baseColor = android.graphics.Color.parseColor(prefs.subtitleTextColor)
                                val alpha = (prefs.subtitleOpacity * 255).toInt().coerceIn(0, 255)
                                Color((baseColor and 0x00FFFFFF) or (alpha shl 24))
                            } catch (e: Exception) {
                                Color.White
                            },
                            fontSize = prefs.subtitleSize.sp,
                            fontWeight = if (prefs.subtitleFontStyle == "Bold") FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

// --- CATEGORY STORAGE SETTINGS ---
@Composable
private fun StorageSettingsGroup(
    viewModel: MainViewModel,
    prefs: com.example.data.database.PreferenceEntity,
    showFolderManager: () -> Unit
) {
    val context = LocalContext.current
    Text("STORAGE & MEDIA SCANNER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Media Folders
            ListItem(
                headlineContent = { Text("Media Library Folders", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Manage scanned directory system paths") },
                leadingContent = { SettingsIconBadge(Icons.Default.FolderOpen) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Manage", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier.clickable { showFolderManager() }
            )



            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Auto Rescan
            ListItem(
                headlineContent = { Text("Auto-Rescan on Launch", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Rescan device file system during app startup") },
                leadingContent = { SettingsIconBadge(Icons.Default.Sync) },
                trailingContent = {
                    Switch(
                        checked = prefs.autoScanEnabled,
                        onCheckedChange = { viewModel.toggleAutoScan(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Rebuild Database Cache
            ListItem(
                headlineContent = { Text("Reset Media Database Cache", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Clear catalog data and rebuild local indexer") },
                leadingContent = { SettingsIconBadge(Icons.Default.Storage) },
                trailingContent = {
                    TextButton(onClick = {
                        viewModel.scanLocalMedia()
                        android.widget.Toast.makeText(context, "Rescanning local device storage...", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Rebuild", color = MaterialTheme.colorScheme.error)
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Clear History Progress
            ListItem(
                headlineContent = { Text("Wipe Playback Progress Cache", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Erase resume timestamps and play counts") },
                leadingContent = { SettingsIconBadge(Icons.Default.DeleteForever) },
                trailingContent = {
                    TextButton(onClick = {
                        viewModel.clearHistory()
                        android.widget.Toast.makeText(context, "Playback progress wiped", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Wipe", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    }
}

// --- CATEGORY DATA PRIVACY SETTINGS ---
@Composable
private fun DataPrivacySettingsGroup(
    viewModel: MainViewModel,
    prefs: com.example.data.database.PreferenceEntity,
    showNetworkMenu: Boolean,
    onChangeNetworkMenu: (Boolean) -> Unit
) {
    Text("DATA & PRIVACY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Metered Network
            ListItem(
                headlineContent = { Text("Metered Network Policy", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Action: ${prefs.meteredNetworkAction}") },
                leadingContent = { SettingsIconBadge(Icons.Default.Wifi) },
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

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Playback History Tracker
            ListItem(
                headlineContent = { Text("Playback History Tracker", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Log file progress events to database cache") },
                leadingContent = { SettingsIconBadge(Icons.Default.History) },
                trailingContent = {
                    Switch(
                        checked = prefs.playHistoryEnabled,
                        onCheckedChange = { viewModel.togglePlayHistoryEnabled(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Queue History Tracker
            ListItem(
                headlineContent = { Text("Save Video Queue History", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Remember active video queue list") },
                leadingContent = { SettingsIconBadge(Icons.Default.Queue) },
                trailingContent = {
                    Switch(
                        checked = prefs.saveVideoQueueHistory,
                        onCheckedChange = { viewModel.toggleSaveVideoQueueHistory(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            ListItem(
                headlineContent = { Text("Save Audio Queue History", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Remember active audio queue list") },
                leadingContent = { SettingsIconBadge(Icons.Default.QueueMusic) },
                trailingContent = {
                    Switch(
                        checked = prefs.saveAudioQueueHistory,
                        onCheckedChange = { viewModel.toggleSaveAudioQueueHistory(it) }
                    )
                }
            )
        }
    }
}

// --- CATEGORY ABOUT SETTINGS ---
@Composable
private fun AboutSettingsGroup(
    showAbout: () -> Unit,
    showPrivacy: () -> Unit,
    showTerms: () -> Unit,
    showChangelog: () -> Unit
) {
    Text("ABOUT & APP INFO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            ListItem(
                headlineContent = { Text("About Aero Player", fontWeight = FontWeight.Bold) },
                supportingContent = { Text("System diagnostics, media decoders & build info") },
                leadingContent = { SettingsIconBadge(Icons.Default.Info) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable { showAbout() }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            ListItem(
                headlineContent = { Text("What's New (Changelog)", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("View v1.5.0 updates & FFmpeg software decoders") },
                leadingContent = { SettingsIconBadge(Icons.Default.NewReleases) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable { showChangelog() }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            ListItem(
                headlineContent = { Text("Privacy Policy", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Data permissions and local storage policy") },
                leadingContent = { SettingsIconBadge(Icons.Default.PrivacyTip) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable { showPrivacy() }
            )

            HorizontalDivider(modifier = Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            ListItem(
                headlineContent = { Text("Terms of Service", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("End-User License Agreement (EULA)") },
                leadingContent = { SettingsIconBadge(Icons.Default.Gavel) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickable { showTerms() }
            )
        }
    }
}

// --- SEARCH RESULTS VIEW ---
@Composable
private fun SearchResultsView(
    query: String,
    viewModel: MainViewModel,
    prefs: com.example.data.database.PreferenceEntity,
    onOpenFolderManager: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenChangelog: () -> Unit
) {
    val context = LocalContext.current
    val q = query.lowercase().trim()

    Text(
        text = "SEARCH RESULTS FOR \"$query\"",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.sp
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            var matchCount = 0

            // Theme
            if ("theme".contains(q) || "mode".contains(q) || "dark".contains(q) || "light".contains(q)) {
                matchCount++
                ListItem(
                    headlineContent = { Text("App Theme Engine", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Selected Theme: ${prefs.themeMode}") },
                    leadingContent = { SettingsIconBadge(Icons.Default.Palette) }
                )
            }

            // Hardware Acceleration
            if ("hardware".contains(q) || "acceleration".contains(q) || "decoder".contains(q) || "ffmpeg".contains(q) || "codec".contains(q)) {
                if (matchCount > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                matchCount++
                ListItem(
                    headlineContent = { Text("Hardware Acceleration", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Mode: ${prefs.hwAcceleration}") },
                    leadingContent = { SettingsIconBadge(Icons.Default.Hardware) }
                )
            }

            // Seek
            if ("seek".contains(q) || "jump".contains(q) || "double tap".contains(q)) {
                if (matchCount > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                matchCount++
                ListItem(
                    headlineContent = { Text("Double-Tap Seek Duration", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("${prefs.doubleTapSeekSeconds} seconds") },
                    leadingContent = { SettingsIconBadge(Icons.Default.Gesture) }
                )
            }

            // Orientation
            if ("orientation".contains(q) || "rotation".contains(q) || "portrait".contains(q) || "landscape".contains(q)) {
                if (matchCount > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                matchCount++
                ListItem(
                    headlineContent = { Text("Video Screen Orientation", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Constraint: ${prefs.defaultOrientation}") },
                    leadingContent = { SettingsIconBadge(Icons.Default.ScreenRotation) }
                )
            }

            // Subtitles
            if ("subtitle".contains(q) || "caption".contains(q) || "language".contains(q) || "font".contains(q) || "color".contains(q) || "encoding".contains(q)) {
                if (matchCount > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                matchCount++
                ListItem(
                    headlineContent = { Text("Default Subtitle Language", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Language: ${prefs.defaultSubtitleLanguage}, Encoding: ${prefs.subtitleEncoding}") },
                    leadingContent = { SettingsIconBadge(Icons.Default.Subtitles) }
                )
            }

            // Folder & Storage
            if ("folder".contains(q) || "storage".contains(q) || "directory".contains(q) || "scan".contains(q) || "path".contains(q)) {
                if (matchCount > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                matchCount++
                ListItem(
                    headlineContent = { Text("Media Library Folders", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Manage scanned directory paths") },
                    leadingContent = { SettingsIconBadge(Icons.Default.FolderOpen) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { onOpenFolderManager() }
                )
            }

            // History
            if ("history".contains(q) || "log".contains(q) || "tracker".contains(q) || "resume".contains(q)) {
                if (matchCount > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                matchCount++
                ListItem(
                    headlineContent = { Text("Playback History Tracker", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Log file progress events") },
                    leadingContent = { SettingsIconBadge(Icons.Default.History) }
                )
            }

            // About
            if ("about".contains(q) || "version".contains(q) || "info".contains(q) || "update".contains(q) || "license".contains(q)) {
                if (matchCount > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                matchCount++
                ListItem(
                    headlineContent = { Text("About Aero Player", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("System diagnostics, FFmpeg audio decoder & build info") },
                    leadingContent = { SettingsIconBadge(Icons.Default.Info) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { onOpenAbout() }
                )
            }

            if (matchCount == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No matching settings found for \"$query\"",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsIconBadge(icon: ImageVector) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
