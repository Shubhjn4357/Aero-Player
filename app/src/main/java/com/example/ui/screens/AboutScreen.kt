package com.example.ui.screens

import android.content.Context
import android.os.Build
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    showPrivacy: (() -> Unit)? = null,
    showTerms: (() -> Unit)? = null,
    showChangelog: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showLicensesDialog by remember { mutableStateOf(false) }
    var internalShowPrivacy by remember { mutableStateOf(false) }
    var internalShowTerms by remember { mutableStateOf(false) }
    var internalShowChangelog by remember { mutableStateOf(false) }

    val onPrivacyClick = { internalShowPrivacy = true }
    val onTermsClick = { internalShowTerms = true }
    val onChangelogClick = { internalShowChangelog = true }

    FrostedGlassBackground {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Aero Player Logo",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "About Aero Player",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "v1.5.0 Stable  ·  Build 42",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Overview Description Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)
                )
            ) {
                Text(
                    text = "High-precision, low-latency Android media suite powered by Google Media3 pipeline & Jellyfin FFmpeg Dolby audio decoding extension.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // Core Architectural Capabilities
            Text(
                text = "FEATURE CAPABILITIES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeatureRow(
                        icon = Icons.Default.Equalizer,
                        title = "Parametric Equalizer",
                        desc = "5-band frequency equalizer with bass boost, virtualizer & custom presets"
                    )
                    FeatureRow(
                        icon = Icons.Default.Subtitles,
                        title = "Subtitles Engine",
                        desc = "Full styling with custom fonts, colors, shadows, borders & UTF-8 encodings"
                    )
                    FeatureRow(
                        icon = Icons.Default.PictureInPicture,
                        title = "Background & PiP Mode",
                        desc = "Seamless floating window picture-in-picture and background audio playback"
                    )
                    FeatureRow(
                        icon = Icons.Default.Speed,
                        title = "Speed & Pitch Controls",
                        desc = "Smooth pitch-preserving audio stretch from 0.25x up to 4.0x playback speed"
                    )
                    FeatureRow(
                        icon = Icons.Default.TouchApp,
                        title = "Gesture Navigation",
                        desc = "Intuitive swipe gestures for volume, brightness, fast-seeking and zoom"
                    )
                }
            }

            // System Actions & Legal
            Text(
                text = "SUPPORT & LEGAL",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text("What's New in v1.5.0", fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("View recent changes and feature additions") },
                        leadingContent = { Icon(Icons.Default.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { onChangelogClick() }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    ListItem(
                        headlineContent = { Text("Privacy Policy", fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("Learn how local permissions and data are handled") },
                        leadingContent = { Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { onPrivacyClick() }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    ListItem(
                        headlineContent = { Text("Terms of Service (EULA)", fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("End-User License Agreement and system boundaries") },
                        leadingContent = { Icon(Icons.Default.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { onTermsClick() }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    ListItem(
                        headlineContent = { Text("Open Source Licenses", fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("ExoPlayer, Media3, Jellyfin FFmpeg, Jetpack Compose") },
                        leadingContent = { Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { showLicensesDialog = true }
                    )
                }
            }

            // Footer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Aero Player Engine",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
    }

    // Open Source Licenses Dialog
    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Open Source Licenses", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    LicenseItem("AndroidX Media3 (ExoPlayer)", "Apache License 2.0", "Google LLC")
                    LicenseItem("Jellyfin Media3 FFmpeg Extension", "LGPL v2.1 / GPL v2+", "Jellyfin Project & FFmpeg Team")
                    LicenseItem("Jetpack Compose & Material 3", "Apache License 2.0", "Google LLC")
                    LicenseItem("Kotlin Coroutines & Flow", "Apache License 2.0", "JetBrains s.r.o.")
                    LicenseItem("Room Persistence Library", "Apache License 2.0", "Google LLC")
                }
            },
            confirmButton = {
                Button(onClick = { showLicensesDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (internalShowPrivacy) {
        AlertDialog(
            onDismissRequest = { internalShowPrivacy = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Privacy & Permission Policy", fontWeight = FontWeight.Bold)
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
                        "Welcome to Aero Player, a local-first offline media application. This policy details how our system processes files and data:",
                        fontSize = 12.sp
                    )
                    Text(
                        "1. Local Storage Access:\nAero Player requests READ_EXTERNAL_STORAGE or media-specific storage permissions exclusively to locate and build indices of local video/audio files. File scan profiles never leave your physical device.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "2. Media Metrics Tracking:\nIf 'Playback History Tracker' is active, playback elapsed seconds are logged locally to help with resuming media.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "3. Zero Network Interception:\nAero Player runs in sandboxed spaces and does not transmit tracking coordinates or play history to external cloud networks.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = { internalShowPrivacy = false }) {
                    Text("I Understand")
                }
            }
        )
    }

    if (internalShowTerms) {
        AlertDialog(
            onDismissRequest = { internalShowTerms = false },
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
                        "By running this software package, you accept the following licensing clauses and system boundaries:",
                        fontSize = 12.sp
                    )
                    Text(
                        "1. License Scope:\nWe grant you a personal, non-transferable, non-exclusive license to use the Aero Player application to play standard multimedia file formats for personal convenience.",
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
                Button(onClick = { internalShowTerms = false }) {
                    Text("Accept Terms")
                }
            }
        )
    }

    if (internalShowChangelog) {
        AlertDialog(
            onDismissRequest = { internalShowChangelog = false },
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
                        "- Native Android Preference Tree structure with instant search.\n" +
                        "- Unified About page with system diagnostics & hardware info.\n" +
                        "- Integrated Jellyfin Media3 FFmpeg software decoder extension.\n" +
                        "- Support for Dolby AC3 / EAC-3 multi-channel audio tracks.\n" +
                        "- Advanced subtitle encodings, custom shadows, and live preview.",
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = { internalShowChangelog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun DiagnosticItem(
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) },
        supportingContent = {
            Column {
                Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
            }
        }
    )
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
            }
        }
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun LicenseItem(
    name: String,
    license: String,
    author: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("License: $license", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        Text("Copyright: $author", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
