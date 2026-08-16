package com.example.data.repository

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

    override suspend fun scanMedia(context: Context): Unit = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaEntity>()

        // Retain existing custom streams and non-MediaStore items from DB
        val existingItems = try {
            mediaDao.getAllMedia().firstOrNull() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val customItems = existingItems.filter { item ->
            item.artist == "Custom Stream" ||
            item.uriString.startsWith("http://") ||
            item.uriString.startsWith("https://") ||
            item.uriString.startsWith("rtsp://") ||
            item.uriString.startsWith("mms://")
        }

        // 1. Scan Videos
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

                    if (contentUri.isNotBlank()) {
                        mediaList.add(
                            MediaEntity(
                                uriString = contentUri,
                                title = title,
                                artist = artist ?: "Unknown",
                                album = album ?: "Unknown",
                                duration = duration,
                                size = size,
                                dateAdded = dateAdded,
                                isVideo = true,
                                path = data,
                                mimeType = mime,
                                genre = "Video"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaRepository", "Error querying video MediaStore", e)
        }

        // 2. Scan Audio
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

                    if (contentUri.isNotBlank()) {
                        mediaList.add(
                            MediaEntity(
                                uriString = contentUri,
                                title = title,
                                artist = artist ?: "Unknown",
                                album = album ?: "Unknown",
                                duration = duration,
                                size = size,
                                dateAdded = dateAdded,
                                isVideo = false,
                                path = data,
                                mimeType = mime,
                                genre = "Audio"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaRepository", "Error querying audio MediaStore", e)
        }

        // Add custom items (network streams added by user)
        mediaList.addAll(customItems)

        // Deduplicate items
        val verifiedList = mediaList.distinctBy { it.uriString }

        // Only update database if media list actually changed to prevent unnecessary UI invalidation
        val existingUris = existingItems.map { it.uriString }.toSet()
        val newUris = verifiedList.map { it.uriString }.toSet()
        if (existingUris != newUris || existingItems.size != verifiedList.size) {
            mediaDao.replaceLocalMedia(verifiedList)
        }

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
