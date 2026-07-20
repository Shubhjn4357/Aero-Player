package com.example.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.data.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MediaRepository(private val mediaDao: MediaDao) {

    fun getMediaFlow(): Flow<List<MediaEntity>> = mediaDao.getAllMedia()

    suspend fun addMediaItem(item: MediaEntity) {
        mediaDao.insertMedia(listOf(item))
    }

    suspend fun deleteMedia(uriString: String) {
        mediaDao.deleteMediaByUri(uriString)
    }

    suspend fun clearMedia() {
        mediaDao.clearAllMedia()
    }

    suspend fun scanMedia(context: Context) = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaEntity>()

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
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ALBUM)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Video_$id"
                    val title = cursor.getString(titleCol) ?: name
                    val duration = cursor.getLong(durationCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateAddedCol)
                    val data = cursor.getString(dataCol) ?: ""
                    val mime = cursor.getString(mimeCol)
                    val artist = cursor.getString(artistCol)
                    val album = cursor.getString(albumCol)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

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
        } catch (e: Exception) {
            android.util.Log.e("MediaRepository", "Error querying video MediaStore, using fallback streams", e)
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
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Audio_$id"
                    val title = cursor.getString(titleCol) ?: name
                    val duration = cursor.getLong(durationCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateAddedCol)
                    val data = cursor.getString(dataCol) ?: ""
                    val mime = cursor.getString(mimeCol)
                    val artist = cursor.getString(artistCol)
                    val album = cursor.getString(albumCol)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

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
        } catch (e: Exception) {
            android.util.Log.e("MediaRepository", "Error querying audio MediaStore, using fallback streams", e)
        }

        // Seeding preloaded/online content so users can play and download from web
        val preloadedItems = listOf(
            MediaEntity(
                uriString = "android.resource://com.example/raw/sample_track",
                title = "Aero Premium Beats (Offline Demo)",
                artist = "Aero Collective",
                album = "Aero Showcase",
                duration = 372000,
                size = 8945228,
                dateAdded = System.currentTimeMillis() / 1000,
                isVideo = false,
                path = "android.resource://com.example/raw/sample_track",
                mimeType = "audio/mp3",
                genre = "Audio"
            ),
            MediaEntity(
                uriString = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                title = "Big Buck Bunny (H.264 / 1080p)",
                artist = "Blender Studio",
                album = "Preloaded Videos",
                duration = 596000,
                size = 276134947,
                dateAdded = System.currentTimeMillis() / 1000,
                isVideo = true,
                path = "/storage/emulated/0/Online/Videos/BigBuckBunny.mp4",
                mimeType = "video/mp4",
                genre = "Video"
            ),
            MediaEntity(
                uriString = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                title = "Elephants Dream (H.264 / 1080p)",
                artist = "Blender Studio",
                album = "Preloaded Videos",
                duration = 653000,
                size = 425123984,
                dateAdded = System.currentTimeMillis() / 1000,
                isVideo = true,
                path = "/storage/emulated/0/Online/Videos/ElephantsDream.mp4",
                mimeType = "video/mp4",
                genre = "Video"
            ),
            MediaEntity(
                uriString = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                title = "SoundHelix Song 1 (MP3)",
                artist = "SoundHelix",
                album = "Preloaded Tracks",
                duration = 372000,
                size = 8928000,
                dateAdded = System.currentTimeMillis() / 1000,
                isVideo = false,
                path = "/storage/emulated/0/Online/Music/SoundHelix-Song-1.mp3",
                mimeType = "audio/mp3",
                genre = "Audio"
            ),
            MediaEntity(
                uriString = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                title = "SoundHelix Song 2 (MP3)",
                artist = "SoundHelix",
                album = "Preloaded Tracks",
                duration = 423000,
                size = 10152000,
                dateAdded = System.currentTimeMillis() / 1000,
                isVideo = false,
                path = "/storage/emulated/0/Online/Music/SoundHelix-Song-2.mp3",
                mimeType = "audio/mp3",
                genre = "Audio"
            )
        )
        mediaList.addAll(preloadedItems)

        mediaDao.clearLocalMedia()
        mediaDao.insertMedia(mediaList)
    }
}

class HistoryRepository(private val historyDao: HistoryDao) {

    fun getHistoryFlow(): Flow<List<HistoryEntity>> = historyDao.getHistoryFlow()

    suspend fun getHistoryByUri(uriString: String): HistoryEntity? = historyDao.getHistoryByUri(uriString)

    suspend fun addHistory(
        uriString: String,
        title: String,
        isVideo: Boolean,
        duration: Long,
        progressMs: Long
    ) {
        val entry = HistoryEntity(
            uriString = uriString,
            title = title,
            isVideo = isVideo,
            duration = duration,
            lastPlayedTime = System.currentTimeMillis(),
            progressMs = progressMs
        )
        historyDao.insertHistory(entry)
    }

    suspend fun deleteHistory(uriString: String) {
        historyDao.deleteHistoryByUri(uriString)
    }

    suspend fun clearHistory() {
        historyDao.clearHistory()
    }
}

class PreferenceRepository(private val preferenceDao: PreferenceDao) {

    fun getPreferencesFlow(): Flow<PreferenceEntity> = preferenceDao.getPreferencesFlow().map {
        it ?: PreferenceEntity() // Return default if not initialized
    }

    suspend fun updatePreferences(preferences: PreferenceEntity) {
        preferenceDao.insertOrUpdatePreferences(preferences)
    }

    suspend fun getPreferencesDirect(): PreferenceEntity {
        return getPreferencesFlow().firstOrNull() ?: PreferenceEntity()
    }
}
