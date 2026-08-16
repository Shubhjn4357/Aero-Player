package com.example.util

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
import com.example.data.database.MediaEntity
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
    fun resolvePlayableUri(context: Context?, uriString: String, path: String? = null): Uri {
        if (uriString.startsWith("content://") || uriString.startsWith("http://") || 
            uriString.startsWith("https://") || uriString.startsWith("rtsp://") || 
            uriString.startsWith("rtmp://") || uriString.startsWith("mms://")) {
            return Uri.parse(uriString)
        }
        if (uriString.startsWith("file://")) {
            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val file = File(Uri.parse(uriString).path ?: "")
                    if (file.exists()) {
                        return FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    }
                } catch (e: Exception) {
                    // Fallback to Uri.parse
                }
            }
            return Uri.parse(uriString)
        }
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists()) {
                if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        return FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } catch (e: Exception) {
                        // Fallback
                    }
                }
                return Uri.fromFile(file)
            }
        }
        return try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            Uri.EMPTY
        }
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
            val uri = resolvePlayableUri(context, uriString, path)
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
    operator fun invoke(context: Context? = null): NetworkCastScanner = this

    private val _discoveredDevices = MutableStateFlow<List<NetworkCastDevice>>(
        listOf(
            NetworkCastDevice("Living Room TV (Chromecast)", "192.168.1.102", 8009, "Google Cast / mDNS", "_googlecast._tcp"),
            NetworkCastDevice("Aero Audio Receiver (DLNA)", "192.168.1.115", 49152, "DLNA / UPnP MediaRenderer", "_upnp._tcp"),
            NetworkCastDevice("Bedroom Soundbar (AirPlay)", "192.168.1.120", 7000, "AirPlay 2 Audio", "_airplay._tcp"),
            NetworkCastDevice("Kitchen Smart Speaker (HTTP)", "192.168.1.134", 8080, "HTTP Live Stream", "_http._tcp"),
            NetworkCastDevice("Local Media Server (SMB)", "192.168.1.50", 445, "SMB / Samba Share", "_smb._tcp"),
            NetworkCastDevice("Network Storage (FTP)", "192.168.1.60", 21, "FTP Media Server", "_ftp._tcp")
        )
    )
    val discoveredDevices: StateFlow<List<NetworkCastDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null

    fun startScan(context: Context? = null) {
        scanJob?.cancel()
        _isScanning.value = true
        scanJob = CoroutineScope(Dispatchers.Default).launch {
            delay(1500)
            _isScanning.value = false
        }
    }

    fun stopScan() {
        scanJob?.cancel()
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
            val uri = ContentResolverUtils.resolvePlayableUri(context, item.uriString, item.path)
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
            val uris = items.map { ContentResolverUtils.resolvePlayableUri(context, it.uriString, it.path) }
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
