package com.emptysol.aeroplayer.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emptysol.aeroplayer.ui.screens.CustomButton
import com.emptysol.aeroplayer.ui.screens.CustomSlider
import com.emptysol.aeroplayer.util.VideoPoint
import com.emptysol.aeroplayer.util.VideoPointDetector
import com.emptysol.aeroplayer.util.VideoPointType
import com.emptysol.aeroplayer.util.parseJumpTimeMs
import java.util.Locale

@Composable
fun PlayerRightDrawerContent(
    isAudio: Boolean,
    effectiveEngine: String,
    onSelectEngine: (String) -> Unit,
    videoPoints: List<VideoPoint>,
    currentPosition: Long,
    duration: Long,
    onJumpToPoint: (VideoPoint) -> Unit,
    onOpenAllPoints: () -> Unit,
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    eqEnabled: Boolean,
    currentEqPreset: String,
    onToggleEq: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    playAsAudioOnly: Boolean,
    onToggleAudioOnly: () -> Unit,
    isPipActive: Boolean,
    onTogglePip: () -> Unit,
    sleepTimeLeftMinutes: Int,
    onSetSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onJumpToTimeMs: (Long) -> Unit,
    onSeekDeltaSeconds: (Int) -> Unit,
    pointA: Long?,
    pointB: Long?,
    abRepeatEnabled: Boolean,
    onCycleAbPoint: () -> Unit,
    onResetAbLoop: () -> Unit,
    audioDelayMs: Long,
    onAudioDelayChange: (Long) -> Unit,
    bookmarks: List<Long>,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    playQueueSize: Int,
    onSaveQueueAsPlaylist: (String) -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenMediaDetails: () -> Unit,
    onOpenUsageTips: () -> Unit,
    onCloseDrawer: () -> Unit,
    formatDuration: (Long) -> String
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()
    var localSpeed by remember(playbackSpeed) { mutableStateOf(playbackSpeed) }
    var jumpInputText by remember { mutableStateOf("") }
    var playlistNameInput by remember { mutableStateOf("") }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val isCompact = maxWidth < 210.dp
        val horizPad = if (isCompact) 8.dp else 14.dp
        val cardPad = if (isCompact) 8.dp else 12.dp
        val cardRadius = if (isCompact) 14.dp else 18.dp
        val sectionGap = if (isCompact) 10.dp else 14.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizPad)
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(if (isCompact) 10.dp else 14.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (!isCompact) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isAudio) Icons.Default.MusicNote else Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    Column {
                        Text(
                            text = "Playback Controls",
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isCompact) 12.sp else 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!isCompact) {
                            Text(
                                text = if (isAudio) "Audio options & timing" else "Speed, engine & extras",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onCloseDrawer,
                    modifier = Modifier
                        .size(if (isCompact) 34.dp else 44.dp)
                        .testTag("close_right_drawer_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close controls",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(if (isCompact) 18.dp else 22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(sectionGap))

            // 1. Playback Engine (Video only)
            if (!isAudio) {
                DrawerSectionLabel(if (isCompact) "Engine" else "Playback Engine")
                Surface(
                    shape = RoundedCornerShape(cardRadius),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("engine_switch_card")
                ) {
                    Column(
                        modifier = Modifier.padding(cardPad),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isCompact) "Active" else "Player Engine",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = if (isCompact) 11.sp else 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = if (effectiveEngine == "VLC") "VLC" else "Exo",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }

                        val isExo = effectiveEngine != "VLC"
                        val isVlc = effectiveEngine == "VLC"

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCompact) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(3.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    EngineTogglePill(
                                        label = "ExoPlayer",
                                        selected = isExo,
                                        compact = true,
                                        modifier = Modifier.fillMaxWidth().testTag("engine_pill_exoplayer"),
                                        onClick = {
                                            if (!isExo) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onSelectEngine("ExoPlayer")
                                            }
                                        }
                                    )
                                    EngineTogglePill(
                                        label = "VLC Engine",
                                        selected = isVlc,
                                        compact = true,
                                        modifier = Modifier.fillMaxWidth().testTag("engine_pill_vlc"),
                                        onClick = {
                                            if (!isVlc) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onSelectEngine("VLC")
                                            }
                                        }
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    EngineTogglePill(
                                        label = "ExoPlayer",
                                        selected = isExo,
                                        compact = false,
                                        modifier = Modifier.weight(1f).testTag("engine_pill_exoplayer"),
                                        onClick = {
                                            if (!isExo) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onSelectEngine("ExoPlayer")
                                            }
                                        }
                                    )
                                    EngineTogglePill(
                                        label = "VLC Engine",
                                        selected = isVlc,
                                        compact = false,
                                        modifier = Modifier.weight(1f).testTag("engine_pill_vlc"),
                                        onClick = {
                                            if (!isVlc) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onSelectEngine("VLC")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(sectionGap))
            }

            // 2. Chapters / Key Points (only when genuine embedded chapters exist)
            if (videoPoints.isNotEmpty() && !isAudio) {
                DrawerSectionLabel("Chapters")
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(cardRadius),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("drawer_video_points_card")
                ) {
                    Column(
                        modifier = Modifier.padding(cardPad),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bookmarks,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isCompact) "${videoPoints.size} ch" else "${videoPoints.size} chapters",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = if (isCompact) 11.sp else 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            val currentPoint = VideoPointDetector.getActivePoint(videoPoints, currentPosition)
                            if (currentPoint != null && !isCompact) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = currentPoint.title,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        val introPoint = videoPoints.firstOrNull { it.type == VideoPointType.PROLOGUE }
                        val bonusPoint = videoPoints.firstOrNull { it.type == VideoPointType.POST_CREDIT }

                        if (introPoint != null || bonusPoint != null) {
                            if (isCompact) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (introPoint != null) {
                                        FilledTonalButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onJumpToPoint(introPoint.copy(startMs = (introPoint.skipTargetMs + 100L).coerceAtMost(duration)))
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Skip Intro", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        }
                                    }
                                    if (bonusPoint != null) {
                                        FilledTonalButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onJumpToPoint(bonusPoint)
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Bonus", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (introPoint != null) {
                                        FilledTonalButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onJumpToPoint(introPoint.copy(startMs = (introPoint.skipTargetMs + 100L).coerceAtMost(duration)))
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Skip Intro", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        }
                                    }
                                    if (bonusPoint != null) {
                                        FilledTonalButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onJumpToPoint(bonusPoint)
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Bonus Scene", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = onOpenAllPoints,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("drawer_open_video_points_sheet_button"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (isCompact) "Chapters (${videoPoints.size})" else "View All Chapters (${videoPoints.size})",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = if (isCompact) 11.sp else 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(sectionGap))
            }

            // 3. Quick Modes (Video only: Background Audio & Pop-Up Player)
            if (!isAudio) {
                DrawerSectionLabel(if (isCompact) "Modes" else "View Modes")
                if (isCompact) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ModeTileCard(
                            title = "Audio Only",
                            subtitle = "Background",
                            icon = Icons.Default.Headphones,
                            selected = playAsAudioOnly,
                            compact = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleAudioOnly()
                            }
                        )
                        ModeTileCard(
                            title = "Pop-Up",
                            subtitle = "Floating",
                            icon = Icons.Default.PictureInPicture,
                            selected = isPipActive,
                            compact = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTogglePip()
                            }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ModeTileCard(
                            title = "Audio Only",
                            subtitle = "Listen in background",
                            icon = Icons.Default.Headphones,
                            selected = playAsAudioOnly,
                            compact = false,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleAudioOnly()
                            }
                        )
                        ModeTileCard(
                            title = "Pop-Up",
                            subtitle = "Floating window",
                            icon = Icons.Default.PictureInPicture,
                            selected = isPipActive,
                            compact = false,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTogglePip()
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(sectionGap))
            }

            // 4. Playback Speed
            DrawerSectionLabel(if (isCompact) "Speed" else "Playback Speed")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(cardRadius),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(cardPad)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Speed",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (isCompact) 11.sp else 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.2fx", localSpeed),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    CustomSlider(
                        value = localSpeed,
                        onValueChange = {
                            localSpeed = it
                            onSpeedChange(it)
                        },
                        onValueChangeFinished = {
                            onSpeedChange(localSpeed)
                        },
                        valueRange = 0.25f..4.00f
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val speedPresets = listOf(0.5f, 1.0f, 1.5f, 2.0f)
                    if (isCompact) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            speedPresets.chunked(2).forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    pair.forEach { preset ->
                                        val isSelected = kotlin.math.abs(localSpeed - preset) < 0.02f
                                        SpeedChipButton(
                                            label = if (preset == 1.0f) "1x" else "${preset}x",
                                            selected = isSelected,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                localSpeed = preset
                                                onSpeedChange(preset)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            speedPresets.forEach { preset ->
                                val isSelected = kotlin.math.abs(localSpeed - preset) < 0.02f
                                SpeedChipButton(
                                    label = if (preset == 1.0f) "1x" else "${preset}x",
                                    selected = isSelected,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        localSpeed = preset
                                        onSpeedChange(preset)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 5. Sound Equalizer
            Spacer(modifier = Modifier.height(sectionGap))
            DrawerSectionLabel(if (isCompact) "EQ" else "Sound Equalizer")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(cardRadius),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isCompact) "EQ" else "Equalizer",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = if (isCompact) 12.sp else 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = currentEqPreset,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = eqEnabled,
                            onCheckedChange = onToggleEq
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenEqualizer()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (isCompact) "EQ Bands" else "Adjust Sound Bands",
                            fontSize = if (isCompact) 11.sp else 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 6. Sleep Timer (Audio mode)
            if (isAudio) {
                Spacer(modifier = Modifier.height(sectionGap))
                DrawerSectionLabel("Sleep Timer")
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(cardRadius),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(cardPad)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isCompact) "Timer" else "Stop Playback After",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = if (isCompact) 11.sp else 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (sleepTimeLeftMinutes > 0) "${sleepTimeLeftMinutes}m" else "Off",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (sleepTimeLeftMinutes > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (sleepTimeLeftMinutes > 0) {
                            Button(
                                onClick = onCancelSleepTimer,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Turn Off", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onError)
                            }
                        } else if (isCompact) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(listOf(10, 20, 30), listOf(45, 60)).forEach { chunk ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        chunk.forEach { mins ->
                                            SpeedChipButton(
                                                label = "${mins}m",
                                                selected = false,
                                                modifier = Modifier.weight(1f),
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onSetSleepTimer(mins)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(10, 20, 30, 45, 60).forEach { mins ->
                                    SpeedChipButton(
                                        label = "${mins}m",
                                        selected = false,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSetSleepTimer(mins)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7. Jump to Time
            Spacer(modifier = Modifier.height(sectionGap))
            DrawerSectionLabel(if (isCompact) "Jump" else "Jump to Time")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(cardRadius),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isCompact) {
                        OutlinedTextField(
                            value = jumpInputText,
                            onValueChange = { jumpInputText = it },
                            placeholder = { Text("1:30", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        CustomButton(
                            onClick = {
                                val parsedMs = parseJumpTimeMs(jumpInputText)
                                if (parsedMs != null) {
                                    onJumpToTimeMs(parsedMs.coerceIn(0L, duration.coerceAtLeast(0L)))
                                    jumpInputText = ""
                                    onCloseDrawer()
                                } else {
                                    Toast.makeText(context, "Enter time like 1:30 or 90s", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Go", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = jumpInputText,
                                onValueChange = { jumpInputText = it },
                                placeholder = { Text("1:30 or 90s", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            CustomButton(
                                onClick = {
                                    val parsedMs = parseJumpTimeMs(jumpInputText)
                                    if (parsedMs != null) {
                                        onJumpToTimeMs(parsedMs.coerceIn(0L, duration.coerceAtLeast(0L)))
                                        jumpInputText = ""
                                        onCloseDrawer()
                                    } else {
                                        Toast.makeText(context, "Enter time like 1:30 or 90s", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Go", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }

                    val deltas = listOf(-30, -10, 10, 30)
                    if (isCompact) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            deltas.chunked(2).forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    pair.forEach { seconds ->
                                        val label = if (seconds > 0) "+${seconds}s" else "${seconds}s"
                                        SpeedChipButton(
                                            label = label,
                                            selected = false,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onSeekDeltaSeconds(seconds)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            deltas.forEach { seconds ->
                                val label = if (seconds > 0) "+${seconds}s" else "${seconds}s"
                                SpeedChipButton(
                                    label = label,
                                    selected = false,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSeekDeltaSeconds(seconds)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 8. A-B Loop
            Spacer(modifier = Modifier.height(sectionGap))
            DrawerSectionLabel("Loop A–B")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(cardRadius),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(cardPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 8.dp)
                    ) {
                        TimePointChip(
                            label = if (isCompact) "A" else "Start (A)",
                            timeText = if (pointA == null) "--:--" else formatDuration(pointA),
                            compact = isCompact,
                            modifier = Modifier.weight(1f)
                        )
                        TimePointChip(
                            label = if (isCompact) "B" else "End (B)",
                            timeText = if (pointB == null) "--:--" else formatDuration(pointB),
                            compact = isCompact,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CustomButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCycleAbPoint()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (abRepeatEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                contentColor = if (abRepeatEnabled) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                text = when {
                                    pointA == null -> if (isCompact) "Set A" else "Set Start (A)"
                                    pointB == null -> if (isCompact) "Set B" else "Set End (B)"
                                    else -> "Clear"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = if (isCompact) 11.sp else 12.sp,
                                maxLines = 1
                            )
                        }

                        if (pointA != null || pointB != null) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onResetAbLoop()
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear loop",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 9. Video-only: Audio Sync, Bookmarks, Subtitles
            if (!isAudio) {
                Spacer(modifier = Modifier.height(sectionGap))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DrawerSectionLabel(if (isCompact) "Sync" else "Audio Sync", modifier = Modifier.padding(bottom = 0.dp))
                    if (audioDelayMs != 0L) {
                        Text(
                            text = "Reset",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onAudioDelayChange(0L)
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(cardRadius),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(cardPad)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Delay",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = if (isCompact) 11.sp else 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${audioDelayMs}ms",
                                color = if (audioDelayMs == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (isCompact) 11.sp else 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        CustomSlider(
                            value = audioDelayMs.toFloat(),
                            onValueChange = { onAudioDelayChange(it.toLong()) },
                            valueRange = -1000f..1000f
                        )
                    }
                }

                Spacer(modifier = Modifier.height(sectionGap))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DrawerSectionLabel("Bookmarks", modifier = Modifier.padding(bottom = 0.dp))
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAddBookmark()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                        if (!isCompact) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Time", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(cardRadius),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(cardPad)) {
                        if (bookmarks.isEmpty()) {
                            Text(
                                text = if (isCompact) "Tap + to save scene" else "Tap \"Save Time\" to bookmark the current scene.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(bookmarks.size) { index ->
                                    val bmk = bookmarks[index]
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        modifier = Modifier.clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onJumpToTimeMs(bmk)
                                        }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = formatDuration(bmk),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Bold
                                            )
                                            IconButton(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onRemoveBookmark(bmk)
                                                },
                                                modifier = Modifier.size(18.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Remove bookmark",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (effectiveEngine != "VLC") {
                    Spacer(modifier = Modifier.height(sectionGap))
                    DrawerSectionLabel("Subtitles")
                    FilledTonalButton(
                        onClick = onOpenSubtitles,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ClosedCaption, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isCompact) "Subs Style" else "Subtitle Appearance",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (isCompact) 11.sp else 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 10. Save Queue as Playlist
            Spacer(modifier = Modifier.height(sectionGap))
            DrawerSectionLabel("Save Queue")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(cardRadius),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isCompact) {
                        OutlinedTextField(
                            value = playlistNameInput,
                            onValueChange = { playlistNameInput = it },
                            placeholder = { Text("List name", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Button(
                            onClick = {
                                if (playlistNameInput.isNotBlank() && playQueueSize > 0) {
                                    onSaveQueueAsPlaylist(playlistNameInput.trim())
                                    playlistNameInput = ""
                                    onCloseDrawer()
                                } else {
                                    Toast.makeText(context, "Enter a playlist name", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = playlistNameInput,
                                onValueChange = { playlistNameInput = it },
                                placeholder = { Text("Playlist name...", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Button(
                                onClick = {
                                    if (playlistNameInput.isNotBlank() && playQueueSize > 0) {
                                        onSaveQueueAsPlaylist(playlistNameInput.trim())
                                        playlistNameInput = ""
                                        onCloseDrawer()
                                    } else {
                                        Toast.makeText(context, "Enter a playlist name", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 11. Details & Tips
            Spacer(modifier = Modifier.height(sectionGap))
            if (isCompact) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = onOpenMediaDetails,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isAudio) "Track Info" else "Info", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Button(
                        onClick = onOpenUsageTips,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tips", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onOpenMediaDetails,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isAudio) "Track Info" else "Video Info", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onOpenUsageTips,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tips", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DrawerSectionLabel(
    text: String,
    modifier: Modifier = Modifier.padding(bottom = 6.dp)
) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun SpeedChipButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun EngineTogglePill(
    label: String,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = modifier
            .heightIn(min = if (compact) 34.dp else 40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(vertical = if (compact) 5.dp else 8.dp, horizontal = if (compact) 6.dp else 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp).padding(end = 3.dp)
                )
            }
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (compact) 10.sp else 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModeTileCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 12.dp else 16.dp))
            .clickable(onClick = onClick)
    ) {
        if (compact) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TimePointChip(
    label: String,
    timeText: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 6.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = if (compact) 9.sp else 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(modifier = Modifier.height(2.dp))
            Text(timeText, fontSize = if (compact) 11.sp else 13.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
