package com.example.domain.usecase

import android.content.Context
import com.example.domain.model.MediaItemDomain
import com.example.domain.model.PlaybackHistoryDomain
import com.example.domain.repository.IHistoryRepository
import com.example.domain.repository.IMediaRepository
import com.example.domain.repository.IPreferencesRepository
import com.example.data.database.PreferenceEntity
import kotlinx.coroutines.flow.Flow

class GetMediaItemsUseCase(private val repository: IMediaRepository) {
    operator fun invoke(): Flow<List<MediaItemDomain>> = repository.getMediaFlowDomain()
}

class ScanMediaUseCase(private val repository: IMediaRepository) {
    suspend operator fun invoke(context: Context) = repository.scanMedia(context)
}

class ManageHistoryUseCase(private val repository: IHistoryRepository) {
    fun getHistory(): Flow<List<PlaybackHistoryDomain>> = repository.getHistoryFlowDomain()

    suspend fun saveProgress(
        uriString: String,
        title: String,
        isVideo: Boolean,
        duration: Long,
        progressMs: Long
    ) {
        repository.addHistory(uriString, title, isVideo, duration, progressMs)
    }

    suspend fun clearAll() = repository.clearHistory()
}

class ManagePreferencesUseCase(private val repository: IPreferencesRepository) {
    fun getPreferences(): Flow<PreferenceEntity> = repository.getPreferencesFlow()
    suspend fun update(preferences: PreferenceEntity) = repository.updatePreferences(preferences)
}
