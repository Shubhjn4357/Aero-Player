package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.MediaEntity
import com.example.ui.viewmodel.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistTabContent(
    viewModel: MainViewModel,
    onPlayItem: (MediaEntity) -> Unit
) {
    val prefs by viewModel.preferencesState.collectAsState()
    val rawMediaList by viewModel.filteredMediaList.collectAsState()
    var expandedPlaylistName by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistToManageFiles by remember { mutableStateOf<String?>(null) }

    val selectionState by viewModel.selectionState.collectAsState()
    val accentOrange = Color(0xFFFF7A00)

    val playlists = remember(prefs.playlistsJson) {
        try {
            val json = org.json.JSONObject(prefs.playlistsJson)
            val map = mutableMapOf<String, List<String>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val array = json.getJSONArray(key)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    list.add(array.getString(i))
                }
                map[key] = list
            }
            map
        } catch (e: java.lang.Exception) {
            emptyMap<String, List<String>>()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "My Playlists",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Playlist", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.FeaturedPlayList,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No Playlists Yet", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                playlists.forEach { (name, tracks) ->
                    item {
                        val isSelected = selectionState.selectedFolderPaths.contains(name)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectionState.isInSelectionMode) {
                                            viewModel.toggleFolderSelection(name)
                                        } else {
                                            expandedPlaylistName = if (expandedPlaylistName == name) null else name
                                        }
                                    },
                                    onLongClick = {
                                        viewModel.toggleFolderSelection(name)
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) accentOrange.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(
                                if (isSelected) 2.dp else 0.dp,
                                if (isSelected) accentOrange else Color.Transparent
                            )
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
                                        Column {
                                            Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                            Text("${tracks.size} tracks", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(onClick = {
                                            val playlistItems = rawMediaList.filter { tracks.contains(it.uriString) }
                                            if (playlistItems.isNotEmpty()) {
                                                viewModel.playAll(playlistItems)
                                            }
                                        }) {
                                            Icon(Icons.Default.PlayCircle, contentDescription = "Play playlist", tint = MaterialTheme.colorScheme.secondary)
                                        }
                                        IconButton(onClick = {
                                            viewModel.deletePlaylist(name)
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete playlist", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                        }
                                    }
                                }

                                AnimatedVisibility(visible = expandedPlaylistName == name) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Playlist Tracks", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                                            TextButton(
                                                onClick = {
                                                    playlistToManageFiles = name
                                                }
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Add / Remove Files", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        val playlistItems = rawMediaList.filter { tracks.contains(it.uriString) }
                                        if (playlistItems.isEmpty()) {
                                            Text("No tracks added yet. Long press on any media file and select 'Add to playlist'.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                        } else {
                                            playlistItems.forEach { mediaItem ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { onPlayItem(mediaItem) }
                                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = if (mediaItem.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        text = mediaItem.title,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(10.dp)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(accentOrange),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.addMediaToPlaylist(newPlaylistName.trim(), "")
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (playlistToManageFiles != null) {
        val playlistName = playlistToManageFiles!!
        val currentTracksList = playlists[playlistName] ?: emptyList()
        val tempSelectedSet = remember(currentTracksList) { currentTracksList.toMutableStateList() }

        AlertDialog(
            onDismissRequest = { playlistToManageFiles = null },
            title = { Text("Add / Remove Playlist Files", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    Text("Select which video and audio files to include in '$playlistName'.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        items(rawMediaList, key = { it.uriString }) { media ->
                            val isChecked = tempSelectedSet.contains(media.uriString)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isChecked) {
                                            tempSelectedSet.remove(media.uriString)
                                        } else {
                                            tempSelectedSet.add(media.uriString)
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked == true) {
                                            if (!tempSelectedSet.contains(media.uriString)) {
                                                tempSelectedSet.add(media.uriString)
                                            }
                                        } else {
                                            tempSelectedSet.remove(media.uriString)
                                        }
                                    }
                                )
                                Icon(
                                    imageVector = if (media.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(media.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (media.isVideo) "Video" else "Audio", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updatePlaylistTracks(playlistName, tempSelectedSet.toList())
                        playlistToManageFiles = null
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToManageFiles = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
}
