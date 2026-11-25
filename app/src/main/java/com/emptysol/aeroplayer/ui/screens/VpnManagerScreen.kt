package com.emptysol.aeroplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel

/**
 * Dedicated Privacy VPN & Remote LAN Manager Screen.
 * Keeps VPN profiles, split-tunneling, tracker blocking, external VPN add-ons,
 * Kill-Switch guard, and Remote Web UI cleanly separated from P2P Torrents & Direct Downloads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnManagerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenTorrentManager: () -> Unit = {},
    onOpenDownloadManager: () -> Unit = {}
) {
    val prefs by viewModel.preferencesState.collectAsState()
    val engineStatus by viewModel.torrentEngineStatus.collectAsState()
    val inbuiltVpnState by viewModel.inbuiltVpnState.collectAsState()
    val isVpnActive = engineStatus.vpnStatus.isVpnActive || inbuiltVpnState.isRunning

    BackHandler { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "VPN Manager",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (inbuiltVpnState.isRunning) {
                                "Aero Privacy VPN Active • ${inbuiltVpnState.activeProfileName}"
                            } else if (isVpnActive) {
                                "External VPN Tunnel Active • ${engineStatus.vpnStatus.transportSummary}"
                            } else {
                                "VPN Disconnected • ${engineStatus.vpnStatus.transportSummary}"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("vpn_manager_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshVpnAndNetworkStatus() },
                        modifier = Modifier.testTag("vpn_manager_refresh_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh VPN Status",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onOpenTorrentManager,
                        modifier = Modifier.testTag("vpn_manager_open_torrents_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Open Torrent Manager",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onOpenDownloadManager,
                        modifier = Modifier.testTag("vpn_manager_open_downloads_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Open Download Manager",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            RemoteAndVpnTab(
                viewModel = viewModel,
                prefs = prefs,
                engineStatus = engineStatus
            )
        }
    }
}
