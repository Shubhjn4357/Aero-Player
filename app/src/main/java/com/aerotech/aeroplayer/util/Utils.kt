package com.aerotech.aeroplayer.util

import android.app.DownloadManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.aerotech.aeroplayer.data.database.MediaEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object ContentResolverUtils {
    /**
     * Resolves a URI for internal media player engines (ExoPlayer & LibVLC).
     * Strictly avoids FileProvider wrapping for internal playback to ensure direct,
     * zero-overhead POSIX file I/O (FileDataSource / RandomAccessFile) on large 1GB+ files.
     */
    fun resolvePlayableUri(context: Context?, uriString: String, path: String? = null): Uri {
        // 1. Direct file path check (highest performance, zero IPC)
        if (!path.isNullOrBlank()) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    return Uri.fromFile(file)
                }
            } catch (e: Exception) {}
        }
        // 2. Direct file:// URI check
        if (uriString.startsWith("file://")) {
            try {
                val pathFromUri = Uri.parse(uriString).path
                if (!pathFromUri.isNullOrBlank()) {
                    val file = File(pathFromUri)
                    if (file.exists() && file.canRead()) {
                        return Uri.fromFile(file)
                    }
                }
            } catch (e: Exception) {}
            return Uri.parse(uriString)
        }
        // 3. Network or Content URIs
        if (uriString.startsWith("content://") || uriString.startsWith("http://") || 
            uriString.startsWith("https://") || uriString.startsWith("rtsp://") || 
            uriString.startsWith("rtmp://") || uriString.startsWith("mms://")) {
            return Uri.parse(uriString)
        }
        // 4. Fallback path string
        return try {
            val file = File(uriString)
            if (file.exists() && file.canRead()) {
                Uri.fromFile(file)
            } else {
                Uri.parse(uriString)
            }
        } catch (e: Exception) {
            Uri.parse(uriString)
        }
    }

    /**
     * Resolves a shareable URI with FileProvider when exposing media to external apps.
     */
    fun getShareableUri(context: Context, uriString: String, path: String? = null): Uri {
        if (uriString.startsWith("content://")) {
            return Uri.parse(uriString)
        }
        val targetPath = path ?: (if (uriString.startsWith("file://")) Uri.parse(uriString).path else uriString)
        if (!targetPath.isNullOrBlank()) {
            val file = File(targetPath)
            if (file.exists()) {
                try {
                    return FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {}
            }
        }
        return Uri.parse(uriString)
    }

    fun inferMimeType(uriString: String, path: String?, context: Context?): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uriString).ifBlank {
            path?.substringAfterLast('.', "") ?: ""
        }
        if (extension.isNotBlank()) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            if (mime != null) return mime
        }
        if (uriString.endsWith(".m3u8", ignoreCase = true) || uriString.contains("m3u8", ignoreCase = true)) {
            return "application/x-mpegURL"
        }
        if (uriString.endsWith(".mpd", ignoreCase = true)) {
            return "application/dash+xml"
        }
        if (uriString.endsWith(".mkv", ignoreCase = true)) {
            return "video/x-matroska"
        }
        if (uriString.endsWith(".mp4", ignoreCase = true)) {
            return "video/mp4"
        }
        if (uriString.endsWith(".mp3", ignoreCase = true)) {
            return "audio/mpeg"
        }
        if (uriString.endsWith(".flac", ignoreCase = true)) {
            return "audio/flac"
        }
        return if (context != null && uriString.startsWith("content://")) {
            try {
                context.contentResolver.getType(Uri.parse(uriString))
            } catch (e: Exception) {
                null
            }
        } else null
    }

    fun openInExternalPlayer(context: Context, uriString: String, path: String? = null) {
        try {
            val uri = getShareableUri(context, uriString, path)
            val mime = inferMimeType(uriString, path, context) ?: "video/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "No external player found: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openInExternalPlayer(context: Context, uri: Uri, mimeType: String? = null) {
        try {
            val mime = mimeType ?: "video/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "No external player found: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}

data class StreamPlaylistItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val group: String = "General",
    val isVideo: Boolean = true
)

object StreamPlaylistParser {
    fun isPlaylistUrl(url: String, mimeType: String? = null): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".pls") || 
               lower.endsWith(".xspf") || lower.contains(".m3u8?") || lower.contains(".m3u?") ||
               mimeType?.contains("mpegurl", ignoreCase = true) == true ||
               mimeType?.contains("x-mpegurl", ignoreCase = true) == true
    }

    suspend fun parsePlaylist(
        parentTitle: String,
        parentUrl: String,
        defaultIsVideo: Boolean = true
    ): List<StreamPlaylistItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<StreamPlaylistItem>()
        try {
            val url = URL(parentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "AeroPlayer/1.0")
            }
            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                var line: String?
                var currentTitle = ""
                var currentGroup = "General"
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: continue
                    if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                        val groupMatch = Regex("""group-title="([^"]+)"""", RegexOption.IGNORE_CASE).find(trimmed)
                        currentGroup = groupMatch?.groupValues?.get(1) ?: "General"
                        val commaIndex = trimmed.lastIndexOf(',')
                        currentTitle = if (commaIndex >= 0 && commaIndex < trimmed.length - 1) {
                            trimmed.substring(commaIndex + 1).trim()
                        } else {
                            "Channel ${result.size + 1}"
                        }
                    } else if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                        val itemUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("rtsp://")) {
                            trimmed
                        } else {
                            // Resolve relative URL
                            try {
                                URL(url, trimmed).toString()
                            } catch (e: Exception) {
                                trimmed
                            }
                        }
                        val titleToUse = if (currentTitle.isNotBlank()) currentTitle else "Stream ${result.size + 1}"
                        val isVideoItem = !itemUrl.endsWith(".mp3", ignoreCase = true) && 
                                          !itemUrl.endsWith(".aac", ignoreCase = true) && 
                                          !itemUrl.endsWith(".flac", ignoreCase = true) && 
                                          defaultIsVideo
                        result.add(
                            StreamPlaylistItem(
                                title = titleToUse,
                                url = itemUrl,
                                group = currentGroup,
                                isVideo = isVideoItem
                            )
                        )
                        currentTitle = ""
                        currentGroup = "General"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (result.isEmpty()) {
            result.add(
                StreamPlaylistItem(
                    title = parentTitle,
                    url = parentUrl,
                    group = "Direct Stream",
                    isVideo = defaultIsVideo
                )
            )
        }
        result
    }

    fun toMediaEntity(item: StreamPlaylistItem, parentTitle: String): MediaEntity {
        return MediaEntity(
            uriString = item.url,
            title = item.title,
            artist = "Stream • $parentTitle",
            album = item.group,
            duration = 0L,
            size = 0L,
            dateAdded = System.currentTimeMillis() / 1000,
            isVideo = item.isVideo,
            path = item.url,
            mimeType = if (item.isVideo) "video/x-stream" else "audio/x-stream",
            genre = item.group
        )
    }
}

data class NetworkCastDevice(
    val name: String,
    val ipAddress: String,
    val port: Int,
    val protocol: String,
    val serviceType: String = ""
)

object NetworkCastScanner {
    private var castManager: com.aerotech.aeroplayer.cast.CastManager? = null

    operator fun invoke(context: Context? = null): NetworkCastScanner {
        if (context != null && castManager == null) {
            castManager = com.aerotech.aeroplayer.cast.CastManager.getInstance(context)
        }
        return this
    }

    private val _discoveredDevices = MutableStateFlow<List<NetworkCastDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<NetworkCastDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun startScan(context: Context? = null) {
        if (context != null && castManager == null) {
            castManager = com.aerotech.aeroplayer.cast.CastManager.getInstance(context)
        }
        val mgr = castManager
        if (mgr != null) {
            mgr.startScan()
            CoroutineScope(Dispatchers.Main).launch {
                mgr.discoveredDevices.collect { list ->
                    if (list.isNotEmpty()) {
                        _discoveredDevices.value = list.map {
                            NetworkCastDevice(it.name, it.ipAddress, it.port, it.protocol, it.serviceType)
                        }
                    }
                }
            }
            CoroutineScope(Dispatchers.Main).launch {
                mgr.isScanning.collect { _isScanning.value = it }
            }
        } else {
            _isScanning.value = true
            CoroutineScope(Dispatchers.Default).launch {
                delay(1500)
                _isScanning.value = false
            }
        }
    }

    fun stopScan() {
        castManager?.stopScan()
        _isScanning.value = false
    }
}

fun downloadFileFromWeb(context: Context, url: String) {
    try {
        val uri = Uri.parse(url)
        val fileName = uri.lastPathSegment ?: "download_${System.currentTimeMillis()}"
        val request = DownloadManager.Request(uri).apply {
            setTitle(fileName)
            setDescription("Downloading media file")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        manager?.enqueue(request)
        Toast.makeText(context, "Download started for $fileName", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Download failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

fun shareMediaItems(context: Context, items: List<MediaEntity>) {
    if (items.isEmpty()) return
    try {
        if (items.size == 1) {
            val item = items.first()
            val uri = ContentResolverUtils.getShareableUri(context, item.uriString, item.path)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType ?: if (item.isVideo) "video/*" else "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, item.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share media").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } else {
            val uris = items.map { ContentResolverUtils.getShareableUri(context, it.uriString, it.path) }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${items.size} media items").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share media: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

fun setAsRingtone(context: Context, item: MediaEntity) {
    try {
        val uri = ContentResolverUtils.resolvePlayableUri(context, item.uriString, item.path)
        RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, uri)
        Toast.makeText(context, "Set '${item.title}' as ringtone", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Permission required or cannot set ringtone: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}
