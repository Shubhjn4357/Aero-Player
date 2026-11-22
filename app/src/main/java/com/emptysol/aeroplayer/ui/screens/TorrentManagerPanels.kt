package com.emptysol.aeroplayer.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emptysol.aeroplayer.data.database.PreferenceEntity
import com.emptysol.aeroplayer.data.database.RssFeedEntity
import com.emptysol.aeroplayer.data.database.TorrentTaskEntity
import com.emptysol.aeroplayer.torrent.*
import com.emptysol.aeroplayer.ui.components.AeroSectionHeader
import com.emptysol.aeroplayer.ui.components.AeroStatusBadge
import com.emptysol.aeroplayer.ui.components.AeroSwitchItem
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RssAutoDownloaderTab(
    viewModel: MainViewModel,
    feeds: List<RssFeedEntity>,
    previewMap: Map<Long, List<RssFeedItem>>,
    onOpenAddFeedDialog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("RSS Auto-Downloader", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "Subscribe to release feeds and auto-enqueue matching torrents",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(
                    onClick = { viewModel.refreshAllRssFeeds() },
                    modifier = Modifier.testTag("rss_refresh_all_btn")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh all feeds")
                }
                Button(
                    onClick = onOpenAddFeedDialog,
                    modifier = Modifier.testTag("rss_add_feed_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Feed")
                }
            }
        }

        if (feeds.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.RssFeed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    Text("No RSS feeds subscribed", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "Add an RSS or Atom feed URL with include/exclude keyword or regex filters to automatically start downloads.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            feeds.forEach { feed ->
                val items = previewMap[feed.id].orEmpty()
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(feed.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(feed.feedUrl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row {
                                IconButton(onClick = { viewModel.refreshRssFeed(feed) }) {
                                    Icon(Icons.Default.Sync, contentDescription = "Refresh feed", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { viewModel.deleteRssFeed(feed.id) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Remove feed", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AeroStatusBadge(
                                text = if (feed.autoDownload) "Auto-Download ON" else "Manual",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (feed.includeFilter.isNotBlank()) {
                                AeroStatusBadge(
                                    text = "+ ${feed.includeFilter}",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        if (feed.lastCheckedMs > 0L) {
                            val checkedStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(feed.lastCheckedMs))
                            Text(
                                "Last checked $checkedStr • ${feed.lastItemCount} entries",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        items.take(4).forEach { entry ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(entry.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (entry.matchesFilter) "Matched filter" else "Filtered out",
                                        fontSize = 10.sp,
                                        color = if (entry.matchesFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.addTorrentFromInput(entry.downloadUri) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Get", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SchedulerAndBandwidthTab(
    viewModel: MainViewModel,
    prefs: PreferenceEntity,
    engineStatus: TorrentEngineStatus
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var dlCapText by remember(prefs.torrentGlobalDownloadCapKbps) {
        mutableStateOf(prefs.torrentGlobalDownloadCapKbps.toString())
    }
    var ulCapText by remember(prefs.torrentGlobalUploadCapKbps) {
        mutableStateOf(prefs.torrentGlobalUploadCapKbps.toString())
    }

    val activeDays = remember(prefs.torrentScheduleDaysJson) {
        TorrentScheduler.parseDaysJson(prefs.torrentScheduleDaysJson)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AeroSectionHeader("Bandwidth Optimization & Speed Caps")

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Live Link: ${engineStatus.networkCondition.transportLabel}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
                Text(
                    "Link capacity: ↓ ${engineStatus.networkCondition.linkDownstreamKbps / 1000} Mbps / ↑ ${engineStatus.networkCondition.linkUpstreamKbps / 1000} Mbps",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AeroSwitchItem(
                    title = "Auto-Bandwidth Optimizer",
                    subtitle = "Automatically scales download & upload throughput to your live network condition",
                    checked = prefs.torrentAutoBandwidth,
                    onCheckedChange = { enabled ->
                        viewModel.updateTorrentPreferences { it.copy(torrentAutoBandwidth = enabled) }
                    },
                    testTag = "switch_auto_bandwidth"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = dlCapText,
                        onValueChange = { dlCapText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("DL Cap (KB/s, 0=Max)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = ulCapText,
                        onValueChange = { ulCapText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("UL Cap (KB/s, 0=Max)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Button(
                    onClick = {
                        val dl = dlCapText.toIntOrNull() ?: 0
                        val ul = ulCapText.toIntOrNull() ?: 0
                        viewModel.updateTorrentPreferences {
                            it.copy(torrentGlobalDownloadCapKbps = dl, torrentGlobalUploadCapKbps = ul)
                        }
                        Toast.makeText(context, "Global speed limits saved", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply Speed Limits")
                }
            }
        }

        AeroSectionHeader("Download & Seeding Scheduler")

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AeroSwitchItem(
                    title = "Enable Transfer Scheduler",
                    subtitle = "Current directive: ${engineStatus.scheduleDirective.name}",
                    checked = prefs.torrentSchedulerEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.updateTorrentPreferences { it.copy(torrentSchedulerEnabled = enabled) }
                    },
                    testTag = "switch_scheduler_enabled"
                )

                val dayLabels = listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dayLabels.forEach { (dayNum, label) ->
                        val selected = dayNum in activeDays
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = if (selected) activeDays - dayNum else activeDays + dayNum
                                val arr = JSONArray(next.sorted())
                                viewModel.updateTorrentPreferences {
                                    it.copy(torrentScheduleDaysJson = arr.toString())
                                }
                            },
                            label = { Text(label, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                Text(
                    "Active Window: ${TorrentScheduler.formatMinute(prefs.torrentScheduleStartMinute)} → ${TorrentScheduler.formatMinute(prefs.torrentScheduleEndMinute)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text("Start Time", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = prefs.torrentScheduleStartMinute.toFloat(),
                    onValueChange = { m ->
                        viewModel.updateTorrentPreferences { it.copy(torrentScheduleStartMinute = (m.toInt() / 15) * 15) }
                    },
                    valueRange = 0f..1425f
                )
                Text("End Time", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = prefs.torrentScheduleEndMinute.toFloat(),
                    onValueChange = { m ->
                        viewModel.updateTorrentPreferences { it.copy(torrentScheduleEndMinute = (m.toInt() / 15) * 15) }
                    },
                    valueRange = 0f..1439f
                )

                Text("Mode inside active window:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                val modes = listOf("DOWNLOAD_AND_SEED" to "Download & Seed", "SEED_ONLY" to "Seed Only", "PAUSE_ALL" to "Pause All")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    modes.forEach { (key, label) ->
                        FilterChip(
                            selected = prefs.torrentScheduleAction == key,
                            onClick = {
                                viewModel.updateTorrentPreferences { it.copy(torrentScheduleAction = key) }
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        AeroSectionHeader("Auto-Completion Actions (When All Finish)")

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val actions = listOf(
                    "NONE" to "Do Nothing (Keep Seeding)",
                    "PAUSE_SEEDING" to "Pause All Seeding on Completion",
                    "STOP_SERVICE" to "Stop Background Sync Service",
                    "EXIT_APP" to "Close Aero Player Process on Completion"
                )
                actions.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clickable {
                                viewModel.updateTorrentPreferences { it.copy(torrentPostCompleteAction = code) }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = prefs.torrentPostCompleteAction == code,
                            onClick = {
                                viewModel.updateTorrentPreferences { it.copy(torrentPostCompleteAction = code) }
                            }
                        )
                        Text(label, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun RemoteAndVpnTab(
    viewModel: MainViewModel,
    prefs: PreferenceEntity,
    engineStatus: TorrentEngineStatus
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var portText by remember(prefs.torrentRemotePort) { mutableStateOf(prefs.torrentRemotePort.toString()) }
    var pinText by remember(prefs.torrentRemotePin) { mutableStateOf(prefs.torrentRemotePin) }
    var showAddCustomVpnDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AeroSectionHeader("Remote Web UI (LAN Control)")

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AeroSwitchItem(
                    title = "Enable Remote Web UI Server",
                    subtitle = "Add magnets and manage downloads from any PC or mobile browser on your Wi-Fi",
                    checked = prefs.torrentRemoteEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.updateTorrentPreferences { it.copy(torrentRemoteEnabled = enabled) }
                    },
                    testTag = "switch_remote_web_ui"
                )

                val remoteState = engineStatus.remoteServerState
                if (remoteState.isRunning) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Web UI Live (${remoteState.requestCount} requests)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(remoteState.url, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Aero Remote URL", remoteState.url))
                                Toast.makeText(context, "Copied Remote URL", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                } else if (!remoteState.error.isNullOrBlank()) {
                    Text(remoteState.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it },
                        label = { Text("Access PIN (Optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                }

                Button(
                    onClick = {
                        val port = (portText.toIntOrNull() ?: 8090).coerceIn(1024, 65535)
                        viewModel.updateTorrentPreferences {
                            it.copy(torrentRemotePort = port, torrentRemotePin = pinText.trim())
                        }
                        Toast.makeText(context, "Remote Web UI settings applied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Port & PIN")
                }
            }
        }

        val vpnConfig by viewModel.vpnConfigFlow.collectAsState()
        val inbuiltState by viewModel.inbuiltVpnState.collectAsState()
        val vpn = engineStatus.vpnStatus
        var customDns1Text by remember(vpnConfig.customPrimaryDns) { mutableStateOf(vpnConfig.customPrimaryDns) }
        var customDns2Text by remember(vpnConfig.customSecondaryDns) { mutableStateOf(vpnConfig.customSecondaryDns) }

        val vpnConsentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.startInbuiltVpn()
                Toast.makeText(context, "Inbuilt Aero Privacy VPN connected", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "VPN permission declined", Toast.LENGTH_SHORT).show()
            }
        }

        val toggleInbuiltVpnAction = {
            if (inbuiltState.isRunning) {
                viewModel.stopInbuiltVpn()
                Toast.makeText(context, "Inbuilt VPN disconnected", Toast.LENGTH_SHORT).show()
            } else {
                val prepareIntent = VpnAndCyberGhostHelper.prepareInbuiltVpnIntent(context)
                if (prepareIntent != null) {
                    vpnConsentLauncher.launch(prepareIntent)
                } else {
                    viewModel.startInbuiltVpn()
                    Toast.makeText(context, "Inbuilt Aero Privacy VPN started", Toast.LENGTH_SHORT).show()
                }
            }
        }

        AeroSectionHeader("Inbuilt VPN & Add-On VPN Selector")

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)),
            modifier = Modifier.testTag("inbuilt_vpn_card")
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Live Tunnel Status Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (vpn.isVpnActive) Icons.Default.VpnLock else Icons.Default.GppMaybe,
                            contentDescription = null,
                            tint = if (vpn.isVpnActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Column {
                            Text(
                                text = when {
                                    inbuiltState.isRunning -> "Inbuilt VPN Connected"
                                    vpn.isVpnActive -> "Add-On VPN Tunnel Active"
                                    else -> "No Active VPN Tunnel"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (inbuiltState.isRunning) {
                                    "${inbuiltState.activeProfileName} • DNS ${inbuiltState.primaryDns}"
                                } else {
                                    vpn.transportSummary
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refreshVpnAndNetworkStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh VPN status")
                    }
                }

                // Mode Switcher: Choose Inbuilt Aero VPN vs External Add-On VPN
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = vpnConfig.selectedMode == "INBUILT",
                        onClick = { viewModel.updateVpnConfig { it.copy(selectedMode = "INBUILT") } },
                        label = { Text("Inbuilt Aero VPN", fontSize = 12.sp) },
                        leadingIcon = if (vpnConfig.selectedMode == "INBUILT") {
                            { Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f).testTag("vpn_mode_inbuilt")
                    )
                    FilterChip(
                        selected = vpnConfig.selectedMode == "ADDON",
                        onClick = { viewModel.updateVpnConfig { it.copy(selectedMode = "ADDON") } },
                        label = { Text("Add-On VPN", fontSize = 12.sp) },
                        leadingIcon = if (vpnConfig.selectedMode == "ADDON") {
                            { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f).testTag("vpn_mode_addon")
                    )
                }

                if (vpnConfig.selectedMode == "INBUILT") {
                    // Connect / Disconnect Primary Button + Live Query Metrics
                    Button(
                        onClick = toggleInbuiltVpnAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (inbuiltState.isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_toggle_inbuilt_vpn")
                    ) {
                        Icon(
                            imageVector = if (inbuiltState.isRunning) Icons.Default.PowerSettingsNew else Icons.Default.VpnKey,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (inbuiltState.isRunning) "Disconnect Inbuilt VPN" else "Connect Inbuilt VPN",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (inbuiltState.isRunning) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Forwarded: ${inbuiltState.queriesForwarded} DNS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "Blocked Trackers: ${inbuiltState.queriesBlocked}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    } else if (!inbuiltState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = inbuiltState.errorMessage!!,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Text(
                        text = "Select Inbuilt Privacy Profile",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    com.emptysol.aeroplayer.service.AeroVpnService.builtInProfiles.forEach { profile ->
                        val isSelected = vpnConfig.inbuiltProfileId == profile.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateVpnConfig {
                                        it.copy(
                                            inbuiltProfileId = profile.id,
                                            blockTrackers = profile.blocksTrackers || it.blockTrackers
                                        )
                                    }
                                    if (inbuiltState.isRunning) {
                                        viewModel.startInbuiltVpn()
                                    }
                                }
                                .testTag("vpn_profile_${profile.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        viewModel.updateVpnConfig {
                                            it.copy(
                                                inbuiltProfileId = profile.id,
                                                blockTrackers = profile.blocksTrackers || it.blockTrackers
                                            )
                                        }
                                        if (inbuiltState.isRunning) {
                                            viewModel.startInbuiltVpn()
                                        }
                                    }
                                )
                                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                                    Text(profile.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(profile.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    val isCustomSelected = vpnConfig.inbuiltProfileId == "custom"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isCustomSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                        border = if (isCustomSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.updateVpnConfig { it.copy(inbuiltProfileId = "custom") }
                            }
                            .testTag("vpn_profile_custom")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = isCustomSelected,
                                    onClick = {
                                        viewModel.updateVpnConfig { it.copy(inbuiltProfileId = "custom") }
                                    }
                                )
                                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                                    Text("Custom DNS Tunnel", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("Specify your preferred upstream IPv4 resolver", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (isCustomSelected) {
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = customDns1Text,
                                        onValueChange = { customDns1Text = it },
                                        label = { Text("Primary DNS") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("custom_dns_primary_field")
                                    )
                                    OutlinedTextField(
                                        value = customDns2Text,
                                        onValueChange = { customDns2Text = it },
                                        label = { Text("Secondary DNS") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("custom_dns_secondary_field")
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                FilledTonalButton(
                                    onClick = {
                                        val validPrimary = com.emptysol.aeroplayer.service.AeroVpnService.isValidIpv4(customDns1Text)
                                        val validSecondary = customDns2Text.isBlank() ||
                                            com.emptysol.aeroplayer.service.AeroVpnService.isValidIpv4(customDns2Text)
                                        if (!validPrimary || !validSecondary) {
                                            Toast.makeText(context, "Enter valid IPv4 addresses (e.g. 1.1.1.1)", Toast.LENGTH_SHORT).show()
                                        } else {
                                            viewModel.updateVpnConfig {
                                                it.copy(
                                                    inbuiltProfileId = "custom",
                                                    customPrimaryDns = customDns1Text.trim(),
                                                    customSecondaryDns = customDns2Text.trim().ifBlank { customDns1Text.trim() }
                                                )
                                            }
                                            if (inbuiltState.isRunning) {
                                                viewModel.startInbuiltVpn()
                                            }
                                            Toast.makeText(context, "Custom DNS applied", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Apply Custom DNS")
                                }
                            }
                        }
                    }

                    AeroSwitchItem(
                        title = "Aero Player Only (Split-Tunnel)",
                        subtitle = "Route only Aero Player & torrent DNS through the inbuilt VPN; keep other apps direct",
                        checked = vpnConfig.splitTunnelAppOnly,
                        onCheckedChange = { split ->
                            viewModel.updateVpnConfig { it.copy(splitTunnelAppOnly = split) }
                            if (inbuiltState.isRunning) viewModel.startInbuiltVpn()
                        },
                        testTag = "switch_vpn_split_tunnel"
                    )

                    AeroSwitchItem(
                        title = "Block Ad & Tracker Domains",
                        subtitle = "Drop known analytics and tracker domains at the inbuilt DNS tunnel level",
                        checked = vpnConfig.blockTrackers,
                        onCheckedChange = { block ->
                            viewModel.updateVpnConfig { it.copy(blockTrackers = block) }
                            if (inbuiltState.isRunning) viewModel.startInbuiltVpn()
                        },
                        testTag = "switch_vpn_block_trackers"
                    )
                } else {
                    // Add-On External VPN Selector & Custom Package Registration
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Choose or Add External VPN", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "Connect an external add-on VPN app or open Android VPN Settings",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { showAddCustomVpnDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("btn_add_custom_vpn_addon")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add VPN", fontSize = 11.sp)
                        }
                    }

                    val addOnList = vpn.installedAddOnVpns
                    addOnList.forEach { app ->
                        val isPreferred = vpnConfig.selectedAddonPackage == app.packageName
                        val isCyberGhost = app.packageName.contains("cyberghost", ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isPreferred) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateVpnConfig { it.copy(selectedAddonPackage = app.packageName) }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(app.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        text = if (app.isInstalled) "Installed • Tap Open to start tunnel" else "Not installed • Tap Get to install",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (app.isCustomAdded) {
                                        IconButton(
                                            onClick = { viewModel.removeCustomVpnAddon(app.packageName) },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove custom VPN", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.updateVpnConfig { it.copy(selectedAddonPackage = app.packageName) }
                                            VpnAndCyberGhostHelper.launchVpnApp(context, app.packageName)
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = if (isCyberGhost) Modifier.testTag("btn_cyberghost_vpn") else Modifier
                                    ) {
                                        Text(
                                            if (app.isInstalled) "Open VPN" else if (isCyberGhost) "Get CyberGhost" else "Get App",
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { VpnAndCyberGhostHelper.openSystemVpnSettings(context) },
                        modifier = Modifier.fillMaxWidth().testTag("btn_system_vpn_settings")
                    ) {
                        Icon(Icons.Default.SettingsEthernet, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Android System VPN Settings")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // Quick CyberGhost shortcut always reachable for Kill-Switch workflows
                if (vpnConfig.selectedMode == "INBUILT") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("CyberGhost VPN", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                if (vpn.isCyberGhostInstalled) "Installed add-on • Tap to switch to full device VPN"
                                else "Optional add-on full-device VPN client",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { VpnAndCyberGhostHelper.launchCyberGhostOrStore(context) },
                            modifier = Modifier.testTag("btn_cyberghost_vpn")
                        ) {
                            Text(if (vpn.isCyberGhostInstalled) "Open VPN" else "Get CyberGhost", fontSize = 11.sp)
                        }
                    }
                }

                AeroSwitchItem(
                    title = "Require VPN (Kill-Switch Guard)",
                    subtitle = "Automatically pauses all P2P transfers whenever the device VPN interface disconnects",
                    checked = prefs.torrentRequireVpn,
                    onCheckedChange = { req ->
                        viewModel.updateTorrentPreferences { it.copy(torrentRequireVpn = req) }
                    },
                    testTag = "switch_require_vpn"
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAddCustomVpnDialog) {
        var pkgInput by remember { mutableStateOf("") }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            onDismissRequest = { showAddCustomVpnDialog = false },
            title = { Text("Add External VPN App", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the Android package name of your installed VPN client (for example: com.wireguard.android or net.mullvad.mullvadvpn).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pkgInput,
                        onValueChange = { pkgInput = it },
                        label = { Text("VPN Package ID") },
                        placeholder = { Text("com.wireguard.android") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("custom_vpn_pkg_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addCustomVpnAddon(pkgInput)
                        showAddCustomVpnDialog = false
                        Toast.makeText(context, "Added VPN provider to list", Toast.LENGTH_SHORT).show()
                    },
                    enabled = pkgInput.isNotBlank() && pkgInput.contains("."),
                    modifier = Modifier.testTag("confirm_add_custom_vpn_btn")
                ) {
                    Text("Add VPN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomVpnDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SecurityAndStorageTab(
    viewModel: MainViewModel,
    prefs: PreferenceEntity,
    torrents: List<TorrentTaskEntity>,
    onPickDownloadFolder: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var filterRulesText by remember(prefs.torrentIpFilterRules) { mutableStateOf(prefs.torrentIpFilterRules) }

    val blocklistPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isNotBlank()) {
                    filterRulesText = text.take(120_000)
                    viewModel.updateTorrentPreferences {
                        it.copy(torrentIpFilterEnabled = true, torrentIpFilterRules = filterRulesText)
                    }
                    Toast.makeText(context, "IP blocklist loaded", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Could not read filter file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AeroSectionHeader("Download Folder & Permissions")

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Download Save Folder", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(prefs.torrentDownloadFolderLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickDownloadFolder, modifier = Modifier.testTag("btn_pick_download_folder")) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Choose Folder")
                    }
                    if (prefs.torrentDownloadFolderUri.isNotBlank()) {
                        OutlinedButton(onClick = {
                            viewModel.updateTorrentDownloadFolder("", "Default (Downloads/AeroTorrents)")
                        }) {
                            Text("Reset Default")
                        }
                    }
                }
            }
        }

        AeroSectionHeader("Protocol Encryption, DHT & PEX")

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Protocol Encryption (MSE / PE)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Allow", "Prefer", "Require").forEach { mode ->
                        FilterChip(
                            selected = prefs.torrentEncryptionMode == mode,
                            onClick = {
                                viewModel.updateTorrentPreferences { it.copy(torrentEncryptionMode = mode) }
                            },
                            label = { Text(mode) }
                        )
                    }
                }

                AeroSwitchItem(
                    title = "Enable DHT (Distributed Hash Table)",
                    subtitle = "Trackerless decentralized peer lookup via BEP-5 UDP nodes",
                    checked = prefs.torrentDhtEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.updateTorrentPreferences { it.copy(torrentDhtEnabled = enabled) }
                    }
                )

                AeroSwitchItem(
                    title = "Enable PEX (Peer Exchange)",
                    subtitle = "Exchange peer endpoints directly with connected peers via BEP-11",
                    checked = prefs.torrentPexEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.updateTorrentPreferences { it.copy(torrentPexEnabled = enabled) }
                    }
                )

                AeroSwitchItem(
                    title = "Sequential Mode by Default",
                    subtitle = "Download head pieces first so videos stream immediately in Aero Player",
                    checked = prefs.torrentSequentialByDefault,
                    onCheckedChange = { seq ->
                        viewModel.updateTorrentPreferences { it.copy(torrentSequentialByDefault = seq) }
                    }
                )
            }
        }

        AeroSectionHeader("IP Filtering (.dat / CIDR Blocklist)")

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AeroSwitchItem(
                    title = "Enable IP Peer Filter",
                    subtitle = "Blocks incoming & outgoing connections matching CIDR or PeerGuardian ranges",
                    checked = prefs.torrentIpFilterEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.updateTorrentPreferences { it.copy(torrentIpFilterEnabled = enabled) }
                    },
                    testTag = "switch_ip_filter"
                )
                OutlinedTextField(
                    value = filterRulesText,
                    onValueChange = { filterRulesText = it },
                    label = { Text("CIDR / IP Ranges (one per line)") },
                    placeholder = { Text("192.168.100.0/24\nBlocked:1.2.3.0-1.2.3.255") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { blocklistPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import .dat/.p2p")
                    }
                    Button(
                        onClick = {
                            val count = IpFilterManager(filterRulesText).ruleCount()
                            viewModel.updateTorrentPreferences { it.copy(torrentIpFilterRules = filterRulesText) }
                            Toast.makeText(context, "Saved $count IP filter range(s)", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Rules")
                    }
                }
            }
        }

        AeroSectionHeader("Integrated File Safety & Malware Scanner")

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AeroSwitchItem(
                    title = "Auto-Scan Completed Downloads",
                    subtitle = "Verifies magic-byte headers against spoofed extensions, APKs, scripts, and SHA-256 checksums",
                    checked = prefs.torrentAutoScanCompleted,
                    onCheckedChange = { enabled ->
                        viewModel.updateTorrentPreferences { it.copy(torrentAutoScanCompleted = enabled) }
                    }
                )
                Button(
                    onClick = {
                        torrents.forEach { t -> viewModel.scanTorrentSafety(t.infoHash) }
                        Toast.makeText(context, "Scanning ${torrents.size} torrent folder(s)...", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan Downloaded Files Now")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TorrentDetailsBottomSheet(
    task: TorrentTaskEntity,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onOpenFolder: (TorrentTaskEntity) -> Unit = {},
    onStreamPlay: (TorrentTaskEntity) -> Unit,
    onPlayFileMedia: (com.emptysol.aeroplayer.data.database.MediaEntity) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val files = remember(task.filesJson) { TorrentEngineManager.parseFilesJson(task.filesJson) }
    val trackers = remember(task.trackersJson) { TorrentEngineManager.parseStringArray(task.trackersJson) }
    val folderLabel = remember(task.saveLocation, task.title) {
        viewModel.torrentEngineManager.getDisplayFolderLabel(task)
    }
    var dlCapInput by remember(task.infoHash) { mutableStateOf(task.downloadCapKbps.toString()) }
    var ulCapInput by remember(task.infoHash) { mutableStateOf(task.uploadCapKbps.toString()) }
    var encMode by remember(task.infoHash) { mutableStateOf(task.encryptionMode) }
    var scanReport by remember(task.infoHash) { mutableStateOf<TorrentScanReport?>(null) }
    var selectedFileForSheet by remember(task.infoHash) { mutableStateOf<TorrentFileEntry?>(null) }

    val isTorrentActive = task.status in setOf("DOWNLOADING", "METADATA", "SEEDING", "QUEUED")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("torrent_details_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(task.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
            Text("InfoHash: ${task.infoHash}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AeroStatusBadge(task.status, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                AeroStatusBadge(
                    "Scan: ${task.scanStatus}",
                    when (task.scanStatus) {
                        "CLEAN" -> MaterialTheme.colorScheme.secondaryContainer
                        "BLOCKED" -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    MaterialTheme.colorScheme.onSurface
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFolder(task) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Download Folder", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(folderLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onOpenFolder(task) }, modifier = Modifier.testTag("details_open_folder_btn")) {
                        Text("Open")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (isTorrentActive) viewModel.pauseTorrent(task.infoHash) else viewModel.resumeTorrent(task.infoHash)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(if (isTorrentActive) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isTorrentActive) "Pause" else "Resume")
                }
                if (task.primaryMediaPath != null) {
                    FilledTonalButton(onClick = { onStreamPlay(task) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.OndemandVideo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stream Now")
                    }
                }
                FilledTonalButton(
                    onClick = {
                        viewModel.scanTorrentSafety(task.infoHash) { report ->
                            scanReport = report
                            Toast.makeText(context, report.summary, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scan")
                }
            }

            AeroSwitchItem(
                title = "Sequential Streaming Mode",
                subtitle = "Prioritize first pieces so media plays smoothly while downloading",
                checked = task.isSequential,
                onCheckedChange = { viewModel.setTorrentSequential(task.infoHash, it) }
            )

            if (scanReport != null || task.scanSummary.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(scanReport?.summary ?: task.scanSummary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        scanReport?.findings?.take(5)?.forEach { f ->
                            Text("• ${f.fileName}: ${f.status} — ${f.reason}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            AeroSectionHeader("Per-Torrent Speed Limits & Encryption")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dlCapInput,
                    onValueChange = { dlCapInput = it.filter { c -> c.isDigit() } },
                    label = { Text("DL KB/s (0=Global)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = ulCapInput,
                    onValueChange = { ulCapInput = it.filter { c -> c.isDigit() } },
                    label = { Text("UL KB/s (0=Global)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Allow", "Prefer", "Require").forEach { m ->
                    FilterChip(selected = encMode == m, onClick = { encMode = m }, label = { Text(m, fontSize = 12.sp) })
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    viewModel.setTorrentCapsAndEncryption(
                        task.infoHash,
                        dlCapInput.toIntOrNull() ?: 0,
                        ulCapInput.toIntOrNull() ?: 0,
                        encMode
                    )
                    Toast.makeText(context, "Per-torrent limits updated", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Apply")
                }
            }

            AeroSectionHeader("Files & Priority (${files.size})")
            if (files.isEmpty()) {
                Text("Waiting for peers to send torrent metadata...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "Long press a file or tap More for Pause/Resume and Delete options",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                files.forEach { fileEntry ->
                    val isPaused = fileEntry.priority == "SKIP"
                    val playableFileMedia = remember(task.infoHash, fileEntry.path, task.downloadedBytes) {
                        viewModel.torrentEngineManager.getPlayableMediaForFile(task, fileEntry)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                            .combinedClickable(
                                onClick = {
                                    if (playableFileMedia != null) {
                                        onPlayFileMedia(playableFileMedia)
                                    } else {
                                        selectedFileForSheet = fileEntry
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedFileForSheet = fileEntry
                                }
                            )
                            .padding(10.dp)
                            .testTag("torrent_file_row_${fileEntry.index}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(fileEntry.path, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val sizeMb = String.format(Locale.US, "%.1f MB", fileEntry.length / (1024.0 * 1024.0))
                                val stateText = if (isPaused) "Paused (Skipped)" else "Priority: ${fileEntry.priority}"
                                Text("$sizeMb • $stateText", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(
                                onClick = {
                                    val nextPrio = if (isPaused) "NORMAL" else "SKIP"
                                    viewModel.updateTorrentFilePriority(task.infoHash, fileEntry.index, nextPrio)
                                },
                                modifier = Modifier.size(34.dp).testTag("file_toggle_pause_${fileEntry.index}")
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPaused) "Resume file" else "Pause file",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { selectedFileForSheet = fileEntry },
                                modifier = Modifier.size(34.dp).testTag("file_more_options_${fileEntry.index}")
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More file options")
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("HIGH", "NORMAL", "LOW", "SKIP").forEach { prio ->
                                FilterChip(
                                    selected = fileEntry.priority == prio,
                                    onClick = { viewModel.updateTorrentFilePriority(task.infoHash, fileEntry.index, prio) },
                                    label = { Text(if (prio == "SKIP") "PAUSED" else prio, fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                }
            }

            if (trackers.isNotEmpty()) {
                AeroSectionHeader("Trackers (${trackers.size})")
                trackers.take(6).forEach { tr ->
                    Text("• $tr", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    selectedFileForSheet?.let { activeFile ->
        val playableFileMedia = remember(task.infoHash, activeFile.path, task.downloadedBytes) {
            viewModel.torrentEngineManager.getPlayableMediaForFile(task, activeFile)
        }
        TorrentFileOptionsBottomSheet(
            task = task,
            fileEntry = activeFile,
            playableFileMedia = playableFileMedia,
            viewModel = viewModel,
            onDismiss = { selectedFileForSheet = null },
            onPlayFile = { media ->
                selectedFileForSheet = null
                onPlayFileMedia(media)
            },
            onOpenFolder = {
                selectedFileForSheet = null
                onOpenFolder(task)
            },
            onDeleteNameOnly = {
                viewModel.removeTorrentFile(task.infoHash, activeFile.index, deleteFromDisk = false)
                selectedFileForSheet = null
                Toast.makeText(context, "Removed file from download list (data kept)", Toast.LENGTH_SHORT).show()
            },
            onDeleteWithData = {
                viewModel.removeTorrentFile(task.infoHash, activeFile.index, deleteFromDisk = true)
                selectedFileForSheet = null
                Toast.makeText(context, "Deleted file entry and data from storage", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorrentFileOptionsBottomSheet(
    task: TorrentTaskEntity,
    fileEntry: TorrentFileEntry,
    playableFileMedia: com.emptysol.aeroplayer.data.database.MediaEntity?,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onPlayFile: (com.emptysol.aeroplayer.data.database.MediaEntity) -> Unit,
    onOpenFolder: () -> Unit,
    onDeleteNameOnly: () -> Unit,
    onDeleteWithData: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val isPaused = fileEntry.priority == "SKIP"
    val fileSizeMb = String.format(Locale.US, "%.1f MB", fileEntry.length / (1024.0 * 1024.0))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("torrent_file_options_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = fileEntry.path.substringAfterLast('/'),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$fileSizeMb • Path: ${fileEntry.path}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            HorizontalDivider()

            if (playableFileMedia != null) {
                Button(
                    onClick = { onPlayFile(playableFileMedia) },
                    modifier = Modifier.fillMaxWidth().testTag("file_sheet_play_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play File", fontWeight = FontWeight.SemiBold)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.updateTorrentFilePriority(task.infoHash, fileEntry.index, if (isPaused) "NORMAL" else "SKIP")
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).testTag("file_sheet_toggle_pause")
                ) {
                    Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPaused) "Resume File" else "Pause File", fontWeight = FontWeight.SemiBold)
                }

                FilledTonalButton(
                    onClick = onOpenFolder,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View Folder", fontWeight = FontWeight.SemiBold)
                }
            }

            AeroSectionHeader("Download Priority")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("HIGH", "NORMAL", "LOW", "SKIP").forEach { prio ->
                    FilterChip(
                        selected = fileEntry.priority == prio,
                        onClick = {
                            viewModel.updateTorrentFilePriority(task.infoHash, fileEntry.index, prio)
                            onDismiss()
                        },
                        label = { Text(if (prio == "SKIP") "PAUSED" else prio) }
                    )
                }
            }

            HorizontalDivider()
            Text("Delete File Options", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)

            OutlinedButton(
                onClick = onDeleteNameOnly,
                modifier = Modifier.fillMaxWidth().testTag("file_sheet_delete_name_only")
            ) {
                Icon(Icons.Default.PlaylistRemove, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete File Name Only (Keep Data)", fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = onDeleteWithData,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("file_sheet_delete_with_data")
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete File + Data", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
