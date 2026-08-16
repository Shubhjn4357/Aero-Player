package com.example.data.database

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

    @Query("DELETE FROM media_items WHERE artist != 'Custom Stream'")
    suspend fun clearLocalMedia()

    @Transaction
    suspend fun replaceLocalMedia(media: List<MediaEntity>) {
        clearLocalMedia()
        insertMedia(media)
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
