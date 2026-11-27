package com.emptysol.aeroplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emptysol.aeroplayer.BuildConfig
import com.emptysol.aeroplayer.ui.components.AeroTonalCard
import com.emptysol.aeroplayer.ui.components.AppScreenScaffold

private enum class AboutSheetType {
    NONE,
    LICENSES,
    PRIVACY,
    TERMS,
    CHANGELOG
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    showPrivacy: (() -> Unit)? = null,
    showTerms: (() -> Unit)? = null,
    showChangelog: (() -> Unit)? = null
) {
    var activeSheet by remember { mutableStateOf(AboutSheetType.NONE) }

    AppScreenScaffold(
        title = "About Aero Player",
        subtitle = "v${BuildConfig.VERSION_NAME} • Build ${BuildConfig.VERSION_CODE}",
        onNavigateBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AeroTonalCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Android audio and video player built on Media3, Jellyfin FFmpeg audio decoders, LibVLC, and high-speed P2P streaming.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Text(
                text = "Features",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AeroTonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureRow(
                        icon = Icons.Default.Equalizer,
                        title = "Sound Equalizer",
                        desc = "5-band frequency equalizer with bass boost and sound presets"
                    )
                    FeatureRow(
                        icon = Icons.Default.Subtitles,
                        title = "Custom Subtitles",
                        desc = "Customize font size, colors, shadows, borders, and text encoding"
                    )
                    FeatureRow(
                        icon = Icons.Default.PictureInPicture,
                        title = "Pop-Up & Background Play",
                        desc = "Floating picture-in-picture window and background music playback"
                    )
                    FeatureRow(
                        icon = Icons.Default.Speed,
                        title = "Playback Speed",
                        desc = "Adjust playback speed smoothly from 0.25x to 4.0x"
                    )
                    FeatureRow(
                        icon = Icons.Default.TouchApp,
                        title = "Swipe Gestures",
                        desc = "Swipe on screen for volume, brightness, seeking, and video zoom"
                    )
                }
            }

            Text(
                text = "Support & Legal",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AeroTonalCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    AboutActionItem(
                        icon = Icons.Default.NewReleases,
                        title = "What's New in v${BuildConfig.VERSION_NAME}",
                        subtitle = "Recent fixes and performance improvements",
                        onClick = { activeSheet = AboutSheetType.CHANGELOG }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    AboutActionItem(
                        icon = Icons.Default.PrivacyTip,
                        title = "Privacy Policy",
                        subtitle = "How local permissions and data are handled",
                        onClick = { activeSheet = AboutSheetType.PRIVACY }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    AboutActionItem(
                        icon = Icons.Default.Gavel,
                        title = "Terms of Service (EULA)",
                        subtitle = "End-User License Agreement and system boundaries",
                        onClick = { activeSheet = AboutSheetType.TERMS }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    AboutActionItem(
                        icon = Icons.Default.Code,
                        title = "Open Source Licenses",
                        subtitle = "ExoPlayer, Media3, Jellyfin FFmpeg, Jetpack Compose",
                        onClick = { activeSheet = AboutSheetType.LICENSES }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Aero Player Engine",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = "Made with 🍁 By Shubh jain",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (activeSheet != AboutSheetType.NONE) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { activeSheet = AboutSheetType.NONE },
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
                when (activeSheet) {
                    AboutSheetType.LICENSES -> {
                        SheetTitleRow(Icons.Default.Code, "Open Source Licenses")
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            LicenseItem("AndroidX Media3 (ExoPlayer)", "Apache License 2.0", "Google LLC")
                            LicenseItem("Jellyfin Media3 FFmpeg Extension", "LGPL v2.1 / GPL v2+", "Jellyfin Project & FFmpeg Team")
                            LicenseItem("Jetpack Compose & Material 3", "Apache License 2.0", "Google LLC")
                            LicenseItem("Kotlin Coroutines & Flow", "Apache License 2.0", "JetBrains s.r.o.")
                            LicenseItem("Room Persistence Library", "Apache License 2.0", "Google LLC")
                        }
                    }
                    AboutSheetType.PRIVACY -> {
                        SheetTitleRow(Icons.Default.PrivacyTip, "Privacy & Permission Policy")
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text("Last updated: November 2026", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "Aero Player is a local-first media application. Your files, playlists, and playback history stay on your device.",
                                fontSize = 13.sp
                            )
                            Text(
                                "1. Local Storage Access:\nMedia permissions are used exclusively to locate and play local video and audio files.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "2. Playback Resume:\nElapsed positions are stored locally in Room so playback resumes where you left off.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "3. Zero Telemetry:\nAero Player does not transmit analytics or watch history to external cloud servers.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    AboutSheetType.TERMS -> {
                        SheetTitleRow(Icons.Default.Gavel, "Terms of Use")
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text("End-User License Agreement (EULA)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "1. License Scope:\nPersonal, non-transferable, non-exclusive license to play standard multimedia files for personal use.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "2. Codec Capabilities:\nDecoding is powered by Android MediaCodec drivers, Media3/ExoPlayer, LibVLC, and bundled FFmpeg extensions.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    AboutSheetType.CHANGELOG -> {
                        SheetTitleRow(Icons.Default.NewReleases, "What's New in v${BuildConfig.VERSION_NAME}")
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                "• Streaming disk-backed BitTorrent engine eliminating OutOfMemory crashes.\n" +
                                "• Reliable background torrent downloads with WakeLock, Wi-Fi lock, and seamless resume.\n" +
                                "• Material 3 semantic surfaces, uncrowded headers, and native bottom sheets.\n" +
                                "• Dedicated Torrent, VPN, and Direct Download Managers.",
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    AboutSheetType.NONE -> Unit
                }

                Button(
                    onClick = { activeSheet = AboutSheetType.NONE },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AboutActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun SheetTitleRow(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
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
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun LicenseItem(
    name: String,
    license: String,
    author: String
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
        Text("License: $license", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Text("Copyright: $author", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
