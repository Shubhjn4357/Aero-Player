package com.example.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File

object ContentResolverUtils {

    private val uriCache = androidx.collection.LruCache<String, Uri>(100)

    /**
     * Infers appropriate container/media MIME type for ExoPlayer media sources.
     */
    fun inferMimeType(uriString: String, path: String?, context: Context?): String? {
        val lower = (path ?: uriString).lowercase()
        when {
            lower.contains(".m3u8") -> return "application/x-mpegURL"
            lower.contains(".mpd") -> return "application/dash+xml"
            lower.contains(".ism") || lower.contains("/manifest") -> return "application/vnd.ms-sstr+xml"
            lower.endsWith(".mkv") -> return "video/x-matroska"
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> return "video/mp4"
            lower.endsWith(".webm") -> return "video/webm"
            lower.endsWith(".avi") -> return "video/avi"
            lower.endsWith(".mov") -> return "video/quicktime"
            lower.endsWith(".flv") -> return "video/x-flv"
            lower.endsWith(".ts") -> return "video/mp2t"
            lower.endsWith(".3gp") -> return "video/3gpp"
            lower.endsWith(".mp3") -> return "audio/mpeg"
            lower.endsWith(".aac") -> return "audio/aac"
            lower.endsWith(".flac") -> return "audio/flac"
            lower.endsWith(".wav") -> return "audio/wav"
            lower.endsWith(".ogg") || lower.endsWith(".opus") -> return "audio/ogg"
            lower.endsWith(".m4a") -> return "audio/mp4"
        }
        if (context != null && uriString.startsWith("content://")) {
            try {
                val type = context.contentResolver.getType(Uri.parse(uriString))
                if (!type.isNullOrBlank() && type != "application/octet-stream") {
                    return type
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return null
    }

    /**
     * Attempts to resolve the underlying physical file path from a Uri (content:// or file://).
     */
    fun getRealFilePath(context: Context, uri: Uri): String? {
        val scheme = uri.scheme ?: return null

        if (scheme == "file") {
            return uri.path
        }

        if (scheme == "content") {
            // 1. Storage Access Framework (SAF) DocumentsContract
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val authority = uri.authority ?: ""

                if ("com.android.externalstorage.documents" == authority) {
                    val split = docId.split(":")
                    val type = split.getOrNull(0)
                    val relativePath = split.getOrNull(1) ?: ""
                    if ("primary".equals(type, ignoreCase = true)) {
                        val path = "/storage/emulated/0/$relativePath"
                        if (File(path).exists()) return path
                    }
                } else if ("com.android.providers.downloads.documents" == authority) {
                    if (docId.startsWith("raw:")) {
                        val path = docId.substring(4)
                        if (File(path).exists()) return path
                    }
                    val contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"),
                        docId.toLongOrNull() ?: 0L
                    )
                    return queryDataColumn(context, contentUri, null, null)
                } else if ("com.android.providers.media.documents" == authority) {
                    val split = docId.split(":")
                    val type = split.getOrNull(0)
                    val id = split.getOrNull(1)
                    val contentUri = when (type) {
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> null
                    }
                    if (contentUri != null && id != null) {
                        return queryDataColumn(context, ContentUris.withAppendedId(contentUri, id.toLongOrNull() ?: 0L), null, null)
                    }
                }
            }

            // 2. Standard ContentResolver query for _data column
            val path = queryDataColumn(context, uri, null, null)
            if (!path.isNullOrBlank() && File(path).exists()) {
                return path
            }
        }

        return null
    }

    private fun queryDataColumn(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        var cursor: android.database.Cursor? = null
        val column = MediaStore.MediaColumns.DATA
        val projection = arrayOf(column)
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(column)
                if (index >= 0) {
                    return cursor.getString(index)
                }
            }
        } catch (e: Exception) {
            Log.e("ContentResolverUtils", "Error querying data column for $uri", e)
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * Resolves a uriString and optional file path into a playable Uri.
     * If content:// is broken/stale or cannot be opened by ContentResolver, it falls back
     * to physical file path or queries MediaStore by name/path to recover valid Uri.
     */
    fun resolvePlayableUri(
        context: Context?,
        uriString: String,
        path: String? = null
    ): Uri {
        val cacheKey = "$uriString|$path"
        uriCache.get(cacheKey)?.let { return it }

        val trimmed = uriString.trim()

        // 1. Network & Resource URIs
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("rtsp://") || trimmed.startsWith("mms://") ||
            trimmed.startsWith("android.resource://")) {
            val uri = Uri.parse(trimmed)
            uriCache.put(cacheKey, uri)
            return uri
        }

        // 2. Direct local file path fast path
        if (!path.isNullOrBlank() && path.startsWith("/")) {
            val uri = Uri.fromFile(File(path))
            uriCache.put(cacheKey, uri)
            return uri
        }

        // 3. Content URIs (Standard MediaStore or SAF)
        if (trimmed.startsWith("content://")) {
            val parsedUri = Uri.parse(trimmed)
            // MediaStore content URIs can be read directly by Android media players without SAF overhead
            if (trimmed.contains("media/external")) {
                uriCache.put(cacheKey, parsedUri)
                return parsedUri
            }

            if (context != null) {
                val realPath = getRealFilePath(context, parsedUri)
                if (!realPath.isNullOrBlank()) {
                    val fileUri = Uri.fromFile(File(realPath))
                    uriCache.put(cacheKey, fileUri)
                    return fileUri
                }
            }
            uriCache.put(cacheKey, parsedUri)
            return parsedUri
        }

        if (trimmed.startsWith("file://")) {
            val rawPath = Uri.parse(trimmed).path
            if (!rawPath.isNullOrBlank()) {
                val uri = Uri.fromFile(File(rawPath))
                uriCache.put(cacheKey, uri)
                return uri
            }
        }

        val file = File(trimmed)
        val finalUri = if (file.exists()) Uri.fromFile(file) else Uri.parse(trimmed)
        uriCache.put(cacheKey, finalUri)
        return finalUri
    }

    /**
     * Launches external media player (VLC, Just Player, MPV, MX Player, or System Default).
     */
    fun openInExternalPlayer(context: Context, uriString: String, path: String? = null) {
        try {
            val playableUri = resolvePlayableUri(context, uriString, path)
            val mimeType = inferMimeType(uriString, path, context) ?: "video/*"

            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(playableUri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = android.content.Intent.createChooser(intent, "Play with External Player (VLC, Just Player, etc.)").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e("ContentResolverUtils", "Failed to launch external player", e)
            android.widget.Toast.makeText(context, "Could not launch external player", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
