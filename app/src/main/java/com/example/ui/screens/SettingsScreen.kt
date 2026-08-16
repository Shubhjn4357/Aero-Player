package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.PreferenceEntity
import com.example.ui.viewmodel.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val prefs by viewModel.preferencesState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAboutDialog by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    FrostedGlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Settings",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. APPEARANCE & THEME
                SettingsSection(title = "Appearance & Interface", icon = Icons.Default.Palette) {
                    SettingsDropdownItem(
                        title = "Theme Mode",
                        subtitle = "Select light, dark or follow system",
                        currentValue = prefs.themeMode,
                        options = listOf("System", "Light", "Dark"),
                        onOptionSelected = { viewModel.updateTheme(it) }
                    )

                    SettingsSwitchItem(
                        title = "Dynamic Material You Colors",
                        subtitle = "Harmonize player interface with Android wallpaper colors",
                        checked = prefs.useDynamicColor,
                        onCheckedChange = { viewModel.updateDynamicColor(it) }
                    )

                    SettingsSwitchItem(
                        title = "Group-wise Folder Navigation",
                        subtitle = "Group media directories into tree hierarchies",
                        checked = prefs.useGroupWiseFolderStyle,
                        onCheckedChange = { viewModel.updateGroupWiseFolderStyle(it) }
                    )

                    SettingsDropdownItem(
                        title = "Library View Style",
                        subtitle = "Media card display formatting",
                        currentValue = prefs.listStyle,
                        options = listOf("Grid", "List"),
                        onOptionSelected = { viewModel.updateListStyle(it) }
                    )
                }

                // 2. PLAYBACK & CONTROLS
                SettingsSection(title = "Playback & Controls", icon = Icons.Default.PlayCircleFilled) {
                    SettingsDropdownItem(
                        title = "Default Playback Speed",
                        subtitle = "Default video & audio tempo",
                        currentValue = "${prefs.playbackSpeed}x",
                        options = listOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"),
                        onOptionSelected = {
                            val speed = it.removeSuffix("x").toFloatOrNull() ?: 1.0f
                            viewModel.updatePlaybackSettings(speed, prefs.resizeMode)
                        }
                    )

                    SettingsDropdownItem(
                        title = "Default Video Aspect / Scaling",
                        subtitle = "Scaling mode for widescreen and ultra-wide videos",
                        currentValue = when (prefs.resizeMode) {
                            1 -> "Fill Screen"
                            2 -> "Zoom / Crop"
                            3 -> "Stretch to Width"
                            else -> "Fit (Best Aspect)"
                        },
                        options = listOf("Fit (Best Aspect)", "Fill Screen", "Zoom / Crop", "Stretch to Width"),
                        onOptionSelected = {
                            val mode = when (it) {
                                "Fill Screen" -> 1
                                "Zoom / Crop" -> 2
                                "Stretch to Width" -> 3
                                else -> 0
                            }
                            viewModel.updatePlaybackSettings(prefs.playbackSpeed, mode)
                        }
                    )

                    SettingsDropdownItem(
                        title = "Double Tap Seek Duration",
                        subtitle = "Seconds skipped on double tap gestures",
                        currentValue = "${prefs.doubleTapSeekSeconds}s",
                        options = listOf("5s", "10s", "15s", "30s"),
                        onOptionSelected = {
                            val sec = it.removeSuffix("s").toIntOrNull() ?: 10
                            viewModel.updateDoubleTapSeekSeconds(sec)
                        }
                    )

                    SettingsDropdownItem(
                        title = "Background Playback Mode",
                        subtitle = "Action when leaving the app during playback",
                        currentValue = when (prefs.backgroundMode) {
                            "PLAY_BACKGROUND_AUDIO" -> "Play Background Audio"
                            "LAUNCH_PIP_MODE" -> "Picture-in-Picture (PiP)"
                            else -> "Stop Playback"
                        },
                        options = listOf("Stop Playback", "Play Background Audio", "Picture-in-Picture (PiP)"),
                        onOptionSelected = {
                            val mode = when (it) {
                                "Play Background Audio" -> "PLAY_BACKGROUND_AUDIO"
                                "Picture-in-Picture (PiP)" -> "LAUNCH_PIP_MODE"
                                else -> "STOP_PLAYBACK"
                            }
                            viewModel.updateBackgroundMode(mode)
                        }
                    )

                    SettingsDropdownItem(
                        title = "Hardware Acceleration",
                        subtitle = "Hardware video decoding pipeline",
                        currentValue = prefs.hwAcceleration,
                        options = listOf("Full", "Decoding", "Disabled"),
                        onOptionSelected = { viewModel.updateHwAcceleration(it) }
                    )

                    SettingsDropdownItem(
                        title = "Default Playback Engine",
                        subtitle = "Select universal LibVLC or Media3 ExoPlayer engine",
                        currentValue = prefs.defaultPlayerEngine,
                        options = listOf("Auto (Smart Format Detection)", "VLC Engine (Universal Codecs)", "Media3 ExoPlayer"),
                        onOptionSelected = { viewModel.updateDefaultPlayerEngine(it) }
                    )
                }

                // 3. SUBTITLES & CAPTIONS
                SettingsSection(title = "Subtitles & Captions", icon = Icons.Default.Subtitles) {
                    SettingsDropdownItem(
                        title = "Subtitle Preset",
                        subtitle = "Preconfigured high-contrast styling presets",
                        currentValue = prefs.subtitlePreset,
                        options = listOf("White on Black", "Yellow on Black", "White Outline", "Yellow Outline", "Soft Shadow", "Custom"),
                        onOptionSelected = { viewModel.applySubtitlePreset(it) }
                    )

                    SettingsDropdownItem(
                        title = "Subtitle Font Size",
                        subtitle = "Text dimension in scaled pixels",
                        currentValue = "${prefs.subtitleSize.toInt()} sp",
                        options = listOf("12 sp", "14 sp", "16 sp", "18 sp", "22 sp", "26 sp"),
                        onOptionSelected = {
                            val size = it.removeSuffix(" sp").toFloatOrNull() ?: 16f
                            viewModel.updateSubtitleSettings(size, prefs.subtitleTextColor)
                        }
                    )

                    SettingsDropdownItem(
                        title = "Default Subtitle Language",
                        subtitle = "Preferred language track for embedded subtitles",
                        currentValue = prefs.defaultSubtitleLanguage,
                        options = listOf("English", "Spanish", "French", "German", "Japanese", "Chinese", "Hindi", "Arabic"),
                        onOptionSelected = { viewModel.updateSubtitleLanguage(it) }
                    )
                }

                // 4. CASTING & NETWORK
                SettingsSection(title = "Network & Casting", icon = Icons.Default.Cast) {
                    SettingsSwitchItem(
                        title = "Enable Cast & DLNA Discovery",
                        subtitle = "Scan local network for Chromecast, DLNA, and UPnP renderers",
                        checked = prefs.isCastEnabled,
                        onCheckedChange = { viewModel.updateCastSettings(isCastEnabled = it) }
                    )

                    SettingsDropdownItem(
                        title = "Preferred Cast Protocol",
                        subtitle = "Media casting transmission protocol",
                        currentValue = prefs.castProtocol,
                        options = listOf("Chromecast / DLNA", "DLNA / UPnP", "AirPlay / Miracast", "HTTP Stream"),
                        onOptionSelected = { viewModel.updateCastSettings(castProtocol = it) }
                    )

                    SettingsDropdownItem(
                        title = "Cast Streaming Quality",
                        subtitle = "Transcoding bitrate and resolution target",
                        currentValue = prefs.castQuality,
                        options = listOf("High (320kbps / 1080p)", "Balanced (192kbps / 720p)", "Low Bandwidth (128kbps / 480p)"),
                        onOptionSelected = { viewModel.updateCastSettings(castQuality = it) }
                    )
                }

                // 5. MAINTENANCE & SYSTEM
                SettingsSection(title = "Library & Maintenance", icon = Icons.Default.Build) {
                    SettingsActionItem(
                        title = "Rescan Media Library",
                        subtitle = "Scan device storage for newly added videos and songs",
                        icon = Icons.Default.Refresh,
                        onClick = {
                            viewModel.scanLocalMedia()
                            Toast.makeText(context, "Scanning media library...", Toast.LENGTH_SHORT).show()
                        }
                    )

                    SettingsActionItem(
                        title = "Clear Playback History",
                        subtitle = "Remove all entries from the recently played history",
                        icon = Icons.Default.DeleteOutline,
                        onClick = {
                            viewModel.clearPlaybackHistory()
                            Toast.makeText(context, "Playback history cleared", Toast.LENGTH_SHORT).show()
                        }
                    )

                    SettingsActionItem(
                        title = "Reset All Settings to Defaults",
                        subtitle = "Restore all configurations and preferences to original defaults",
                        icon = Icons.Default.RestartAlt,
                        isDestructive = true,
                        onClick = {
                            showResetConfirmDialog = true
                        }
                    )

                    SettingsActionItem(
                        title = "About Aero Player",
                        subtitle = "App version, license, and architecture information",
                        icon = Icons.Default.Info,
                        onClick = {
                            showAboutDialog = true
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Reset All Settings?") },
            text = { Text("This will reset all playback, theme, subtitle, and network settings to default.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.preferenceRepository.updatePreferences(PreferenceEntity())
                            Toast.makeText(context, "Settings reset to default", Toast.LENGTH_SHORT).show()
                            showResetConfirmDialog = false
                        }
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAboutDialog) {
        AboutScreen(onBack = { showAboutDialog = false })
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            content()
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsDropdownItem(
    title: String,
    subtitle: String,
    currentValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Box {
            FilledTonalButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(text = currentValue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                fontWeight = if (option == currentValue) FontWeight.Bold else FontWeight.Normal,
                                color = if (option == currentValue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsActionItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}
