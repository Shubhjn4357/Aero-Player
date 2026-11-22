package com.emptysol.aeroplayer.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items")
    fun getAllMedia(): Flow<List<MediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(media: List<MediaEntity>)

    @Query("DELETE FROM media_items")
    suspend fun clearAllMedia()

    @Query("SELECT * FROM media_items")
    suspend fun getAllMediaDirect(): List<MediaEntity>

    @Query("DELETE FROM media_items WHERE uriString IN (:uris)")
    suspend fun deleteMediaList(uris: List<String>)

    @Query("DELETE FROM media_items WHERE artist != 'Custom Stream' AND genre != 'Live Stream' AND genre != 'Playlist Stream Channel'")
    suspend fun clearLocalMedia()

    @Transaction
    suspend fun replaceLocalMedia(media: List<MediaEntity>) {
        syncLocalMedia(media)
    }

    @Transaction
    suspend fun syncLocalMedia(media: List<MediaEntity>) {
        if (media.isNotEmpty()) {
            insertMedia(media)
        }
        val scannedUris = media.map { it.uriString }.toSet()
        val allCurrent = getAllMediaDirect()
        val toDelete = allCurrent.filter {
            it.artist != "Custom Stream" &&
            it.genre != "Live Stream" &&
            it.genre != "Playlist Stream Channel" &&
            !it.uriString.startsWith("http://") &&
            !it.uriString.startsWith("https://") &&
            !it.uriString.startsWith("rtsp://") &&
            !it.uriString.startsWith("rtmp://") &&
            !scannedUris.contains(it.uriString)
        }
        if (toDelete.isNotEmpty()) {
            toDelete.map { it.uriString }.chunked(500).forEach { chunk ->
                deleteMediaList(chunk)
            }
        }
    }

    @Query("DELETE FROM media_items WHERE uriString = :uriString")
    suspend fun deleteMediaByUri(uriString: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY lastPlayedTime DESC")
    fun getHistoryFlow(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE uriString = :uriString LIMIT 1")
    suspend fun getHistoryByUri(uriString: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity)

    @Query("DELETE FROM playback_history WHERE uriString = :uriString")
    suspend fun deleteHistoryByUri(uriString: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()
}

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM app_preferences WHERE id = 1")
    fun getPreferencesFlow(): Flow<PreferenceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePreferences(preferences: PreferenceEntity)
}

@Dao
interface TorrentDao {
    @Query("SELECT * FROM torrent_tasks ORDER BY queueOrder ASC, addedAtMs DESC")
    fun getAllTorrentsFlow(): Flow<List<TorrentTaskEntity>>

    @Query("SELECT * FROM torrent_tasks ORDER BY queueOrder ASC, addedAtMs DESC")
    suspend fun getAllTorrentsDirect(): List<TorrentTaskEntity>

    @Query("SELECT * FROM torrent_tasks WHERE infoHash = :infoHash LIMIT 1")
    suspend fun getTorrentByHash(infoHash: String): TorrentTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTorrent(task: TorrentTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTorrents(tasks: List<TorrentTaskEntity>)

    @Query("DELETE FROM torrent_tasks WHERE infoHash = :infoHash")
    suspend fun deleteTorrent(infoHash: String)

    @Query("DELETE FROM torrent_tasks")
    suspend fun deleteAllTorrents()
}

@Dao
interface RssFeedDao {
    @Query("SELECT * FROM rss_feeds ORDER BY id DESC")
    fun getAllFeedsFlow(): Flow<List<RssFeedEntity>>

    @Query("SELECT * FROM rss_feeds ORDER BY id DESC")
    suspend fun getAllFeedsDirect(): List<RssFeedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFeed(feed: RssFeedEntity): Long

    @Query("DELETE FROM rss_feeds WHERE id = :id")
    suspend fun deleteFeed(id: Long)
}

