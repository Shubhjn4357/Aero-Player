package com.example.ui.screens

import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFileBrowser(
    title: String = "Select File",
    allowedExtensions: List<String>? = null, // null means allow all files
    onFileSelected: (File) -> Unit,
    onDismiss: () -> Unit
) {
    var currentDir by remember {
        mutableStateOf(
            File("/storage/emulated/0").let {
                if (it.exists() && it.canRead()) it else Environment.getExternalStorageDirectory() ?: File("/")
            }
        )
    }

    var searchQuery by remember { mutableStateOf("") }

    // List and filter files
    val filesList = remember(currentDir, searchQuery) {
        try {
            val list = currentDir.listFiles()?.toList() ?: emptyList()
            list.filter { file ->
                val matchesQuery = file.name.contains(searchQuery, ignoreCase = true)
                val isFolder = file.isDirectory
                val matchesExtension = allowedExtensions == null || isFolder || 
                        allowedExtensions.contains(file.extension.lowercase())
                matchesQuery && matchesExtension
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Breadcrumb path components
    val pathSegments = remember(currentDir) {
        val segments = mutableListOf<File>()
        var curr: File? = currentDir
        while (curr != null) {
            segments.add(0, curr)
            curr = curr.parentFile
            // Prevent going above root storage or system root to keep it user friendly
            if (curr?.path == "/storage/emulated") break
        }
        segments
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = currentDir.absolutePath,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search files in this folder...", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                focusedBorderColor = MaterialTheme.colorScheme.secondary,
                unfocusedBorderColor = Color.Transparent
            )
        )

        // Breadcrumbs Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Home, contentDescription = "Home", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.width(4.dp))
            
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                reverseLayout = true // Show latest path at end
            ) {
                // Just display path elegantly as breadcrumbs
            }
            
            // Simple display of directory path clickables
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = pathSegments.joinToString(" > ") { it.name.ifEmpty { "Root" } },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (currentDir.parentFile != null && currentDir.path != "/storage/emulated/0" && currentDir.path != "/") {
                IconButton(
                    onClick = {
                        currentDir = currentDir.parentFile ?: currentDir
                        searchQuery = ""
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Go Up", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Files List
        if (filesList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f))
                    Text("No accessible files or empty folder", color = MaterialTheme.colorScheme.secondary, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filesList) { file ->
                    val isFolder = file.isDirectory
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val dateStr = dateFormat.format(Date(file.lastModified()))
                    val sizeStr = if (isFolder) "" else formatFileSize(file.length())

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isFolder) {
                                    currentDir = file
                                    searchQuery = ""
                                } else {
                                    onFileSelected(file)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // File/Folder icon
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isFolder) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    else MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    isFolder -> Icons.Default.Folder
                                    file.extension.lowercase() in listOf("mp4", "mkv", "webm", "avi") -> Icons.Default.Movie
                                    file.extension.lowercase() in listOf("mp3", "wav", "m4a", "ogg", "flac") -> Icons.Default.MusicNote
                                    file.extension.lowercase() in listOf("srt", "vtt", "ass") -> Icons.Default.Subtitles
                                    else -> Icons.Default.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = if (isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Label details
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(dateStr, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                if (sizeStr.isNotEmpty()) {
                                    Text(sizeStr, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Icon(
                            imageVector = if (isFolder) Icons.Default.ChevronRight else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
