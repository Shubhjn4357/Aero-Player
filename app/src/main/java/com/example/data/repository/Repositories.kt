package com.example.data.repository

import java.io.File
import android.net.Uri
import android.os.Environment
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.data.database.*
import com.example.domain.model.MediaItemDomain
import com.example.domain.model.PlaybackHistoryDomain
import com.example.domain.repository.IHistoryRepository
import com.example.domain.repository.IMediaRepository
import com.example.domain.repository.IPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun MediaEntity.toDomain() = MediaItemDomain(
    uriString = uriString,
    title = title,
    artist = artist,
    album = album,
    durationMs = duration,
    sizeBytes = size,
    dateAddedSec = dateAdded,
    isVideo = isVideo,
    path = path,
    mimeType = mimeType,
    subtitleUri = subtitleUri,
    genre = genre
)

fun MediaItemDomain.toEntity() = MediaEntity(
    uriString = uriString,
    title = title,
    artist = artist,
    album = album,
    duration = durationMs,
    size = sizeBytes,
    dateAdded = dateAddedSec,
    isVideo = isVideo,
    path = path,
    mimeType = mimeType,
    subtitleUri = subtitleUri,
    genre = genre
)

fun HistoryEntity.toDomain() = PlaybackHistoryDomain(
    uriString = uriString,
    title = title,
    isVideo = isVideo,
    durationMs = duration,
    lastPlayedTimeMs = lastPlayedTime,
    progressMs = progressMs
)

class MediaRepository(private val mediaDao: MediaDao) : IMediaRepository {

    fun getMediaFlow(): Flow<List<MediaEntity>> = mediaDao.getAllMedia()

    override fun getMediaFlowDomain(): Flow<List<MediaItemDomain>> = mediaDao.getAllMedia().map { list ->
        list.map { it.toDomain() }
    }

    override suspend fun addMediaItem(item: MediaItemDomain) {
        mediaDao.insertMedia(listOf(item.toEntity()))
    }

    suspend fun addMediaItem(item: MediaEntity) {
        mediaDao.insertMedia(listOf(item))
    }

    override suspend fun deleteMedia(uriString: String) {
        mediaDao.deleteMediaByUri(uriString)
    }

    override suspend fun clearMedia() {
        mediaDao.clearAllMedia()
    }

    private fun normalizePath(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return try {
            File(path).canonicalPath
        } catch (e: Exception) {
            path.replace("/sdcard/", "/storage/emulated/0/")
                .replace("/mnt/sdcard/", "/storage/emulated/0/")
        }
    }

    override suspend fun scanMedia(context: Context): Unit = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaEntity>()
        val seenPaths = mutableSetOf<String>()
        val seenUris = mutableSetOf<String>()

        // Retain existing custom streams and non-MediaStore items from DB
        val existingItems = try {
            mediaDao.getAllMedia().firstOrNull() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val customItems = existingItems.filter { item ->
            item.artist == "Custom Stream" ||
            item.genre == "Live Stream" ||
            item.genre == "Playlist Stream Channel" ||
            item.uriString.startsWith("http://") ||
            item.uriString.startsWith("https://") ||
            item.uriString.startsWith("rtsp://") ||
            item.uriString.startsWith("rtmp://") ||
            item.uriString.startsWith("mms://")
        }

        val videoExtensions = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp",
            "ts", "m2ts", "mts", "vob", "ogv", "divx", "rmvb", "rm", "asf",
            "f4v", "mpg", "mpeg", "m1v", "m2v", "iso", "dat", "264", "h264", "hevc"
        )
        val audioExtensions = setOf(
            "mp3", "wav", "aac", "ogg", "flac", "m4a", "wma", "opus", "aiff",
            "alac", "amr", "ape", "dts", "ac3", "eac3", "mid", "midi", "mka", "ra", "wv", "oga"
        )

        // 1. Scan Videos from MediaStore
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.ARTIST,
            MediaStore.Video.Media.ALBUM
        )

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val artistCol = cursor.getColumnIndex(MediaStore.Video.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Video.Media.ALBUM)

                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "Video_$id" else "Video_$id"
                    val title = if (titleCol >= 0) cursor.getString(titleCol) ?: name else name
                    val duration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else System.currentTimeMillis() / 1000
                    val data = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    val artist = if (artistCol >= 0) cursor.getString(artistCol) else null
                    val album = if (albumCol >= 0) cursor.getString(albumCol) else null

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    val normPath = normalizePath(data)
                    if (normPath.isNotBlank()) {
                        if (seenPaths.contains(normPath)) continue
                        seenPaths.add(normPath)
                    }

                    if (contentUri.isNotBlank() && !seenUris.contains(contentUri)) {
                        seenUris.add(contentUri)
                        val entity = MediaEntity(
                            uriString = contentUri,
                            title = title,
                            artist = artist ?: "Unknown",
                            album = album ?: "Unknown",
                            duration = duration,
                            size = size,
                            dateAdded = dateAdded,
                            isVideo = true,
                            path = if (normPath.isNotBlank()) normPath else data,
                            mimeType = mime,
                            genre = "Video"
                        )
                        mediaList.add(entity)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaRepository", "Error querying video MediaStore", e)
        }

        // 2. Scan Audio from MediaStore
        val audioProjection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                audioProjection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)

                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "Audio_$id" else "Audio_$id"
                    val title = if (titleCol >= 0) cursor.getString(titleCol) ?: name else name
                    val duration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else System.currentTimeMillis() / 1000
                    val data = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    val artist = if (artistCol >= 0) cursor.getString(artistCol) else null
                    val album = if (albumCol >= 0) cursor.getString(albumCol) else null

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    val normPath = normalizePath(data)
                    if (normPath.isNotBlank()) {
                        if (seenPaths.contains(normPath)) continue
                        seenPaths.add(normPath)
                    }

                    if (contentUri.isNotBlank() && !seenUris.contains(contentUri)) {
                        seenUris.add(contentUri)
                        val entity = MediaEntity(
                            uriString = contentUri,
                            title = title,
                            artist = artist ?: "Unknown",
                            album = album ?: "Unknown",
                            duration = duration,
                            size = size,
                            dateAdded = dateAdded,
                            isVideo = false,
                            path = if (normPath.isNotBlank()) normPath else data,
                            mimeType = mime,
                            genre = "Audio"
                        )
                        mediaList.add(entity)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaRepository", "Error querying audio MediaStore", e)
        }

        // 3. Scan Generic MediaStore Files table for any missed audio/video formats
        try {
            val filesUri = MediaStore.Files.getContentUri("external")
            val filesProjection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.TITLE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )
            val filesSelection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'video/%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'audio/%'"
            val filesSelectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString()
            )

            context.contentResolver.query(
                filesUri,
                filesProjection,
                filesSelection,
                filesSelectionArgs,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.TITLE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val mediaTypeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)

                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "File_$id" else "File_$id"
                    val title = if (titleCol >= 0) cursor.getString(titleCol) ?: name else name
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else System.currentTimeMillis() / 1000
                    val data = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    val mediaType = if (mediaTypeCol >= 0) cursor.getInt(mediaTypeCol) else 0

                    val normPath = normalizePath(data)
                    if (normPath.isNotBlank() && seenPaths.contains(normPath)) {
                        continue
                    }

                    val ext = (data.substringAfterLast('.', "")).lowercase()
                    val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ||
                                  mime?.startsWith("video/") == true ||
                                  videoExtensions.contains(ext)
                    val isAudio = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO ||
                                  mime?.startsWith("audio/") == true ||
                                  audioExtensions.contains(ext)

                    if (isVideo || isAudio) {
                        val contentUri = ContentUris.withAppendedId(filesUri, id).toString()
                        if (!seenUris.contains(contentUri)) {
                            if (normPath.isNotBlank()) seenPaths.add(normPath)
                            seenUris.add(contentUri)
                            mediaList.add(
                                MediaEntity(
                                    uriString = contentUri,
                                    title = title,
                                    artist = "Unknown",
                                    album = "Unknown",
                                    duration = 0L,
                                    size = size,
                                    dateAdded = dateAdded,
                                    isVideo = isVideo,
                                    path = if (normPath.isNotBlank()) normPath else data,
                                    mimeType = mime,
                                    genre = if (isVideo) "Video" else "Audio"
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaRepository", "Error querying generic MediaStore Files", e)
        }

        // 4. Direct Filesystem Deep Scan: Search storage directories for media files not yet indexed by MediaStore
        val rootsToScan = mutableListOf<File>()
        val seenRoots = mutableSetOf<String>()
        try {
            val externalStorage = Environment.getExternalStorageDirectory()
            if (externalStorage != null && externalStorage.exists() && externalStorage.canRead()) {
                val canon = normalizePath(externalStorage.absolutePath)
                if (seenRoots.add(canon)) rootsToScan.add(File(canon))
            }
            context.getExternalFilesDirs(null)?.forEach { f ->
                if (f != null && f.exists() && f.canRead()) {
                    val canon = normalizePath(f.absolutePath)
                    if (seenRoots.add(canon)) rootsToScan.add(File(canon))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaRepository", "Error discovering storage root directories", e)
        }

        fun scanDirectoryRecursive(directory: File, depth: Int = 0) {
            if (depth > 12 || !directory.exists() || !directory.canRead() || !directory.isDirectory) return
            val name = directory.name
            if (name.startsWith(".") && name != ".nomedia") return
            if (name.equals("Android", ignoreCase = true) && directory.parentFile?.name == "0") return
            if (name.equals("data", ignoreCase = true) && directory.parentFile?.name == "Android") return
            if (name.equals("obb", ignoreCase = true) && directory.parentFile?.name == "Android") return

            val files = try {
                directory.listFiles()
            } catch (e: Exception) {
                null
            } ?: return

            val unindexedFilesToScan = mutableListOf<String>()

            for (file in files) {
                if (file.isDirectory) {
                    scanDirectoryRecursive(file, depth + 1)
                } else if (file.isFile && file.length() > 0) {
                    val filePath = file.absolutePath
                    val normPath = normalizePath(filePath)
                    if (normPath.isNotBlank() && seenPaths.contains(normPath)) continue

                    val ext = file.extension.lowercase()
                    val isVideo = videoExtensions.contains(ext)
                    val isAudio = audioExtensions.contains(ext)

                    if (isVideo || isAudio) {
                        if (normPath.isNotBlank()) seenPaths.add(normPath)
                        val fileUri = Uri.fromFile(file).toString()
                        if (seenUris.contains(fileUri)) continue
                        seenUris.add(fileUri)

                        mediaList.add(
                            MediaEntity(
                                uriString = fileUri,
                                title = file.nameWithoutExtension.ifBlank { file.name },
                                artist = "Local File",
                                album = directory.name,
                                duration = 0L,
                                size = file.length(),
                                dateAdded = file.lastModified() / 1000,
                                isVideo = isVideo,
                                path = if (normPath.isNotBlank()) normPath else filePath,
                                mimeType = com.example.util.ContentResolverUtils.inferMimeType(fileUri, filePath, context),
                                genre = if (isVideo) "Video" else "Audio"
                            )
                        )
                        unindexedFilesToScan.add(filePath)
                    }
                }
            }

            if (unindexedFilesToScan.isNotEmpty()) {
                try {
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        unindexedFilesToScan.toTypedArray(),
                        null,
                        null
                    )
                } catch (e: Exception) {}
            }
        }

        for (root in rootsToScan) {
            try {
                scanDirectoryRecursive(root)
            } catch (e: Exception) {
                android.util.Log.e("MediaRepository", "Error scanning root directory: ${root.absolutePath}", e)
            }
        }

        // Add custom items (network streams added by user)
        val allItems = mediaList + customItems

        // Robust deduplication by normalized path (or uri if path is empty)
        val verifiedList = allItems
            .groupBy { item ->
                val norm = normalizePath(item.path)
                if (norm.isNotBlank()) norm.lowercase() else item.uriString.lowercase()
            }
            .map { (_, duplicates) ->
                // Prefer content:// MediaStore URI over raw file:// URI, and prefer item with non-zero duration
                duplicates.maxByOrNull { item ->
                    var score = 0
                    if (item.uriString.startsWith("content://")) score += 10
                    if (item.duration > 0) score += 5
                    if (!item.artist.isNullOrBlank() && item.artist != "Unknown" && item.artist != "Local File") score += 2
                    score
                } ?: duplicates.first()
            }

        // Always update database cleanly
        mediaDao.replaceLocalMedia(verifiedList)

        // Preload thumbnails in background asynchronously so scan completes instantly
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.example.ui.screens.ThumbnailManager.preloadThumbnails(context, verifiedList)
            } catch (e: Exception) {}
        }
    }
}

class HistoryRepository(private val historyDao: HistoryDao) : IHistoryRepository {

    fun getHistoryFlow(): Flow<List<HistoryEntity>> = historyDao.getHistoryFlow()

    override fun getHistoryFlowDomain(): Flow<List<PlaybackHistoryDomain>> = historyDao.getHistoryFlow().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getHistoryByUri(uriString: String): HistoryEntity? = historyDao.getHistoryByUri(uriString)

    override suspend fun getHistoryByUriDomain(uriString: String): PlaybackHistoryDomain? = historyDao.getHistoryByUri(uriString)?.toDomain()

    override suspend fun addHistory(
        uriString: String,
        title: String,
        isVideo: Boolean,
        duration: Long,
        progressMs: Long
    ) {
        val existing = historyDao.getHistoryByUri(uriString)
        val finalProgress = if (progressMs <= 0L && existing != null && existing.progressMs > 0L) existing.progressMs else progressMs
        val finalDuration = if (duration <= 0L && existing != null && existing.duration > 0L) existing.duration else duration
        val entry = HistoryEntity(
            uriString = uriString,
            title = title,
            isVideo = isVideo,
            duration = finalDuration,
            lastPlayedTime = System.currentTimeMillis(),
            progressMs = finalProgress
        )
        historyDao.insertHistory(entry)
    }

    override suspend fun deleteHistory(uriString: String) {
        historyDao.deleteHistoryByUri(uriString)
    }

    override suspend fun clearHistory() {
        historyDao.clearHistory()
    }
}

class PreferenceRepository(private val preferenceDao: PreferenceDao) : IPreferencesRepository {

    override fun getPreferencesFlow(): Flow<PreferenceEntity> = preferenceDao.getPreferencesFlow().map {
        it ?: PreferenceEntity() // Return default if not initialized
    }

    override suspend fun updatePreferences(preferences: PreferenceEntity) {
        preferenceDao.insertOrUpdatePreferences(preferences)
    }

    override suspend fun getPreferencesDirect(): PreferenceEntity {
        return getPreferencesFlow().firstOrNull() ?: PreferenceEntity()
    }
}
