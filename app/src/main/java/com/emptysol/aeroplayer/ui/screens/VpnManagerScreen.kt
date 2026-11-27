package com.emptysol.aeroplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.emptysol.aeroplayer.ui.components.AppScreenScaffold
import com.emptysol.aeroplayer.ui.viewmodel.MainViewModel

/**
 * Dedicated Privacy VPN & Remote LAN Manager Screen using AppScreenScaffold
 * with a clean, uncrowded top bar (Refresh + overflow navigation).
 */
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
    var showOverflowMenu by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    AppScreenScaffold(
        title = "VPN Manager",
        subtitle = when {
            inbuiltVpnState.isRunning -> "Aero Privacy VPN Active • ${inbuiltVpnState.activeProfileName}"
            isVpnActive -> "External VPN Active • ${engineStatus.vpnStatus.transportSummary}"
            else -> "Disconnected • ${engineStatus.vpnStatus.transportSummary}"
        },
        onNavigateBack = onBack,
        backButtonTestTag = "vpn_manager_back_btn",
        actions = {
            IconButton(
                onClick = { viewModel.refreshVpnAndNetworkStatus() },
                modifier = Modifier.testTag("vpn_manager_refresh_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh VPN Status",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More VPN options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Torrent Manager") },
                        leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                        onClick = {
                            showOverflowMenu = false
                            onOpenTorrentManager()
                        },
                        modifier = Modifier.testTag("vpn_manager_open_torrents_btn")
                    )
                    DropdownMenuItem(
                        text = { Text("Download Manager") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = {
                            showOverflowMenu = false
                            onOpenDownloadManager()
                        },
                        modifier = Modifier.testTag("vpn_manager_open_downloads_btn")
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            RemoteAndVpnTab(
                viewModel = viewModel,
                prefs = prefs,
                engineStatus = engineStatus
            )
        }
    }
}
