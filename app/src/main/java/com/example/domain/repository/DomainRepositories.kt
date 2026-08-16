package com.example.domain.repository

import android.content.Context
import com.example.domain.model.MediaItemDomain
import com.example.domain.model.PlaybackHistoryDomain
import com.example.data.database.PreferenceEntity
import kotlinx.coroutines.flow.Flow

interface IMediaRepository {
    fun getMediaFlowDomain(): Flow<List<MediaItemDomain>>
    suspend fun addMediaItem(item: MediaItemDomain)
    suspend fun deleteMedia(uriString: String)
    suspend fun clearMedia()
    suspend fun scanMedia(context: Context)
}

interface IHistoryRepository {
    fun getHistoryFlowDomain(): Flow<List<PlaybackHistoryDomain>>
    suspend fun getHistoryByUriDomain(uriString: String): PlaybackHistoryDomain?
    suspend fun addHistory(
        uriString: String,
        title: String,
        isVideo: Boolean,
        duration: Long,
        progressMs: Long
    )
    suspend fun deleteHistory(uriString: String)
    suspend fun clearHistory()
}

interface IPreferencesRepository {
    fun getPreferencesFlow(): Flow<PreferenceEntity>
    suspend fun updatePreferences(preferences: PreferenceEntity)
    suspend fun getPreferencesDirect(): PreferenceEntity
}
