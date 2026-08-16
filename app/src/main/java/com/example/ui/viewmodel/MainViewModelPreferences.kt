package com.example.ui.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

fun MainViewModel.updateTheme(themeName: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(themeMode = themeName))
    }
}

fun MainViewModel.updateDynamicColor(enabled: Boolean) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(useDynamicColor = enabled))
    }
}

fun MainViewModel.updateGroupWiseFolderStyle(enabled: Boolean) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(useGroupWiseFolderStyle = enabled))
    }
}

fun MainViewModel.updateListStyle(style: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(listStyle = style))
    }
}

fun MainViewModel.updateUseGroupWiseFolderStyle(enabled: Boolean) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(useGroupWiseFolderStyle = enabled))
    }
}

fun MainViewModel.updateSorting(sortBy: String, ascending: Boolean) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(sortBy = sortBy, sortAscending = ascending))
    }
}

fun MainViewModel.updateGroupByStyle(style: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(groupByStyle = style))
    }
}

fun MainViewModel.updatePlaybackSettings(speed: Float, resizeMode: Int) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(playbackSpeed = speed, resizeMode = resizeMode))
    }
}

fun MainViewModel.updateSubtitleSettings(size: Float, color: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(subtitleSize = size, subtitleColor = color, subtitleTextColor = color))
    }
}

fun MainViewModel.updateSubtitleLanguage(language: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(defaultSubtitleLanguage = language))
    }
}

fun MainViewModel.updateSubtitleCustomization(
    background: String,
    textColor: String,
    size: Float,
    fontStyle: String,
    verticalOffset: Float? = null
) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(
            subtitleBackground = background,
            subtitleTextColor = textColor,
            subtitleColor = textColor,
            subtitleSize = size,
            subtitleFontStyle = fontStyle,
            subtitleVerticalOffset = verticalOffset ?: current.subtitleVerticalOffset
        ))
    }
}

fun MainViewModel.updateAdvancedSubtitleSettings(
    shadowColor: String? = null,
    shadowRadius: Float? = null,
    shadowOpacity: Float? = null,
    outlineColor: String? = null,
    outlineWidth: Float? = null,
    outlineOpacity: Float? = null,
    opacity: Float? = null,
    preset: String? = null,
    encoding: String? = null,
    verticalOffset: Float? = null
) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(
            subtitleShadowColor = shadowColor ?: current.subtitleShadowColor,
            subtitleShadowRadius = shadowRadius ?: current.subtitleShadowRadius,
            subtitleShadowOpacity = shadowOpacity ?: current.subtitleShadowOpacity,
            subtitleOutlineColor = outlineColor ?: current.subtitleOutlineColor,
            subtitleOutlineWidth = outlineWidth ?: current.subtitleOutlineWidth,
            subtitleOutlineOpacity = outlineOpacity ?: current.subtitleOutlineOpacity,
            subtitleOpacity = opacity ?: current.subtitleOpacity,
            subtitlePreset = preset ?: current.subtitlePreset,
            subtitleEncoding = encoding ?: current.subtitleEncoding,
            subtitleVerticalOffset = verticalOffset ?: current.subtitleVerticalOffset
        ))
    }
}

fun MainViewModel.applySubtitlePreset(preset: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        val updated = when (preset) {
            "White on Black" -> {
                current.copy(
                    subtitlePreset = preset,
                    subtitleBackground = "#FF000000",
                    subtitleTextColor = "#FFFFFF",
                    subtitleShadowColor = "#00000000",
                    subtitleOutlineColor = "#00000000",
                    subtitleOpacity = 1.0f
                )
            }
            "Yellow on Black" -> {
                current.copy(
                    subtitlePreset = preset,
                    subtitleBackground = "#FF000000",
                    subtitleTextColor = "#FFFF00",
                    subtitleShadowColor = "#00000000",
                    subtitleOutlineColor = "#00000000",
                    subtitleOpacity = 1.0f
                )
            }
            "White Outline" -> {
                current.copy(
                    subtitlePreset = preset,
                    subtitleBackground = "#00000000",
                    subtitleTextColor = "#FFFFFF",
                    subtitleShadowColor = "#00000000",
                    subtitleOutlineColor = "#FF000000",
                    subtitleOutlineWidth = 2.5f,
                    subtitleOutlineOpacity = 1.0f,
                    subtitleOpacity = 1.0f
                )
            }
            "Yellow Outline" -> {
                current.copy(
                    subtitlePreset = preset,
                    subtitleBackground = "#00000000",
                    subtitleTextColor = "#FFFF00",
                    subtitleShadowColor = "#00000000",
                    subtitleOutlineColor = "#FF000000",
                    subtitleOutlineWidth = 2.5f,
                    subtitleOutlineOpacity = 1.0f,
                    subtitleOpacity = 1.0f
                )
            }
            "Soft Shadow" -> {
                current.copy(
                    subtitlePreset = preset,
                    subtitleBackground = "#00000000",
                    subtitleTextColor = "#FFFFFF",
                    subtitleShadowColor = "#80000000",
                    subtitleShadowRadius = 4f,
                    subtitleShadowOpacity = 0.8f,
                    subtitleOutlineColor = "#00000000",
                    subtitleOpacity = 1.0f
                )
            }
            else -> {
                current.copy(subtitlePreset = preset)
            }
        }
        preferenceRepository.updatePreferences(updated)
    }
}

fun MainViewModel.toggleAutoScan(enabled: Boolean) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(autoScanEnabled = enabled))
    }
}

fun MainViewModel.toggleUsePerVideoSettings(enabled: Boolean) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(usePerVideoSettings = enabled))
    }
}

fun MainViewModel.updateSaveVolumeBrightnessBehavior(behavior: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(saveVolumeBrightnessBehavior = behavior))
    }
}

fun MainViewModel.updateGlobalVolume(volume: Float) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(globalVolume = volume))
    }
}

fun MainViewModel.updateGlobalBrightness(brightness: Float) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(globalBrightness = brightness))
    }
}

fun MainViewModel.updatePerVideoVolumeBrightness(uriString: String, volume: Float, brightness: Float) {
    viewModelScope.launch {
        val current = preferencesState.value
        try {
            val json = if (current.perVideoSettingsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(current.perVideoSettingsJson)
            val videoObj = json.optJSONObject(uriString) ?: org.json.JSONObject()
            videoObj.put("volume", volume.toDouble())
            if (brightness >= 0f) {
                videoObj.put("brightness", brightness.toDouble())
            }
            json.put(uriString, videoObj)
            preferenceRepository.updatePreferences(current.copy(perVideoSettingsJson = json.toString()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun MainViewModel.updatePerVideoSettings(
    uriString: String,
    speed: Float,
    resizeMode: Int,
    volume: Float,
    eqPreset: String,
    brightness: Float = -1f
) {
    viewModelScope.launch {
        val current = preferencesState.value
        try {
            val json = if (current.perVideoSettingsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(current.perVideoSettingsJson)
            val videoObj = json.optJSONObject(uriString) ?: org.json.JSONObject()
            videoObj.put("speed", speed.toDouble())
            videoObj.put("resizeMode", resizeMode)
            videoObj.put("volume", volume.toDouble())
            videoObj.put("eqPreset", eqPreset)
            if (brightness >= 0f) {
                videoObj.put("brightness", brightness.toDouble())
            }
            json.put(uriString, videoObj)
            preferenceRepository.updatePreferences(current.copy(perVideoSettingsJson = json.toString()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun MainViewModel.updatePerVideoSubtitle(
    uriString: String,
    subtitleGroupIndex: Int,
    subtitleTrackIndex: Int,
    isDisabled: Boolean,
    externalSubtitleUri: String? = null,
    vlcSubTrackId: Int? = null,
    subtitleTrackName: String? = null,
    subtitleLanguage: String? = null
) {
    viewModelScope.launch {
        val current = preferencesState.value
        try {
            val json = if (current.perVideoSettingsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(current.perVideoSettingsJson)
            val videoObj = json.optJSONObject(uriString) ?: org.json.JSONObject()
            videoObj.put("subGroupIndex", subtitleGroupIndex)
            videoObj.put("subTrackIndex", subtitleTrackIndex)
            videoObj.put("subDisabled", isDisabled)
            if (externalSubtitleUri != null) {
                videoObj.put("externalSubUri", externalSubtitleUri)
            }
            if (vlcSubTrackId != null) {
                videoObj.put("vlcSubTrackId", vlcSubTrackId)
            }
            if (subtitleTrackName != null) {
                videoObj.put("subtitleTrackName", subtitleTrackName)
            }
            if (subtitleLanguage != null) {
                videoObj.put("subtitleLanguage", subtitleLanguage)
            }
            json.put(uriString, videoObj)
            preferenceRepository.updatePreferences(current.copy(perVideoSettingsJson = json.toString()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun MainViewModel.updatePerVideoVlcAudio(uriString: String, vlcAudioTrackId: Int, trackName: String? = null) {
    updatePerVideoAudio(uriString = uriString, audioGroupIndex = -1, audioTrackIndex = -1, vlcAudioTrackId = vlcAudioTrackId, audioTrackName = trackName)
}

fun MainViewModel.updatePerVideoVlcSubtitle(uriString: String, vlcSubTrackId: Int, trackName: String? = null) {
    updatePerVideoSubtitle(
        uriString = uriString,
        subtitleGroupIndex = -1,
        subtitleTrackIndex = -1,
        isDisabled = (vlcSubTrackId == -1),
        vlcSubTrackId = vlcSubTrackId,
        subtitleTrackName = trackName
    )
}

fun MainViewModel.updatePerVideoAudio(
    uriString: String,
    audioGroupIndex: Int,
    audioTrackIndex: Int,
    vlcAudioTrackId: Int? = null,
    audioTrackName: String? = null,
    audioLanguage: String? = null
) {
    viewModelScope.launch {
        val current = preferencesState.value
        try {
            val json = if (current.perVideoSettingsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(current.perVideoSettingsJson)
            val videoObj = json.optJSONObject(uriString) ?: org.json.JSONObject()
            videoObj.put("audioGroupIndex", audioGroupIndex)
            videoObj.put("audioTrackIndex", audioTrackIndex)
            if (vlcAudioTrackId != null) {
                videoObj.put("vlcAudioTrackId", vlcAudioTrackId)
            }
            if (audioTrackName != null) {
                videoObj.put("audioTrackName", audioTrackName)
            }
            if (audioLanguage != null) {
                videoObj.put("audioLanguage", audioLanguage)
            }
            json.put(uriString, videoObj)
            preferenceRepository.updatePreferences(current.copy(perVideoSettingsJson = json.toString()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun MainViewModel.updatePerVideoEngine(uriString: String, engine: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        try {
            val json = if (current.perVideoSettingsJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(current.perVideoSettingsJson)
            val videoObj = json.optJSONObject(uriString) ?: org.json.JSONObject()
            videoObj.put("playerEngine", engine)
            json.put(uriString, videoObj)
            preferenceRepository.updatePreferences(current.copy(perVideoSettingsJson = json.toString()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun MainViewModel.updateDefaultPlayerEngine(engine: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(defaultPlayerEngine = engine))
    }
}

fun MainViewModel.updateDefaultOrientation(orientation: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(defaultOrientation = orientation))
    }
}

fun MainViewModel.updateDoubleTapSeekSeconds(seconds: Int) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(doubleTapSeekSeconds = seconds))
    }
}

fun MainViewModel.toggleRotationLock(locked: Boolean) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(rotationLock = locked))
    }
}

fun MainViewModel.completeOnboarding() {
    try {
        getApplication<android.app.Application>().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", true).apply()
    } catch (e: Exception) {
        android.util.Log.e("MainViewModel", "Failed to write onboarding to SharedPreferences", e)
    }
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(onboardingCompleted = true))
    }
}

fun MainViewModel.toggleBannedFolder(folderPath: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        val array = try {
            org.json.JSONArray(current.bannedFoldersJson)
        } catch (e: Exception) {
            org.json.JSONArray()
        }
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        if (list.contains(folderPath)) {
            list.remove(folderPath)
        } else {
            list.add(folderPath)
        }
        val newArray = org.json.JSONArray(list)
        preferenceRepository.updatePreferences(current.copy(bannedFoldersJson = newArray.toString()))
    }
}

fun MainViewModel.toggleFavoriteFolder(folderPath: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        val array = try {
            org.json.JSONArray(current.favoriteFoldersJson)
        } catch (e: Exception) {
            org.json.JSONArray()
        }
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        if (list.contains(folderPath)) {
            list.remove(folderPath)
        } else {
            list.add(folderPath)
        }
        val newArray = org.json.JSONArray(list)
        preferenceRepository.updatePreferences(current.copy(favoriteFoldersJson = newArray.toString()))
    }
}

fun MainViewModel.toggleFavoriteMedia(mediaUri: String) {
    val playlistName = "Favorites"
    viewModelScope.launch {
        val current = preferencesState.value
        val jsonObj = try {
            org.json.JSONObject(current.playlistsJson)
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        val array = if (jsonObj.has(playlistName)) {
            jsonObj.getJSONArray(playlistName)
        } else {
            org.json.JSONArray()
        }
        var exists = false
        var indexToRemove = -1
        for (i in 0 until array.length()) {
            if (array.getString(i) == mediaUri) {
                exists = true
                indexToRemove = i
                break
            }
        }
        if (exists) {
            val newArray = org.json.JSONArray()
            for (i in 0 until array.length()) {
                if (i != indexToRemove) {
                    newArray.put(array.get(i))
                }
            }
            jsonObj.put(playlistName, newArray)
        } else {
            array.put(mediaUri)
            jsonObj.put(playlistName, array)
        }
        preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
    }
}

fun MainViewModel.isMediaFavorite(mediaUri: String): Boolean {
    val current = preferencesState.value
    val jsonObj = try {
        org.json.JSONObject(current.playlistsJson)
    } catch (e: Exception) {
        return false
    }
    val array = if (jsonObj.has("Favorites")) {
        jsonObj.getJSONArray("Favorites")
    } else {
        return false
    }
    for (i in 0 until array.length()) {
        if (array.getString(i) == mediaUri) {
            return true
        }
    }
    return false
}

fun MainViewModel.updateMeteredNetworkAction(action: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(meteredNetworkAction = action))
    }
}

fun MainViewModel.togglePlayHistoryEnabled(enabled: Boolean) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(playHistoryEnabled = enabled))
    }
}

fun MainViewModel.toggleSaveVideoQueueHistory(enabled: Boolean) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(saveVideoQueueHistory = enabled))
    }
}

fun MainViewModel.toggleSaveAudioQueueHistory(enabled: Boolean) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(saveAudioQueueHistory = enabled))
    }
}

fun MainViewModel.updateResumePlaybackBehavior(behavior: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(resumePlaybackBehavior = behavior))
    }
}

fun MainViewModel.updateHwAcceleration(mode: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(hwAcceleration = mode))
    }
}

fun MainViewModel.updateBackgroundMode(mode: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(current.copy(backgroundMode = mode))
    }
}

fun MainViewModel.addMediaToPlaylist(playlistName: String, mediaUri: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        val jsonObj = try {
            org.json.JSONObject(current.playlistsJson)
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        val array = if (jsonObj.has(playlistName)) {
            jsonObj.getJSONArray(playlistName)
        } else {
            org.json.JSONArray()
        }
        var exists = false
        for (i in 0 until array.length()) {
            if (array.getString(i) == mediaUri) {
                exists = true
                break
            }
        }
        if (!exists) {
            array.put(mediaUri)
        }
        jsonObj.put(playlistName, array)
        preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
    }
}

fun MainViewModel.addMultipleMediaToPlaylist(playlistName: String, mediaUris: List<String>) {
    viewModelScope.launch {
        val current = preferencesState.value
        val jsonObj = try {
            org.json.JSONObject(current.playlistsJson)
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        val array = if (jsonObj.has(playlistName)) {
            jsonObj.getJSONArray(playlistName)
        } else {
            org.json.JSONArray()
        }
        val existingSet = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            existingSet.add(array.getString(i))
        }
        for (uri in mediaUris) {
            if (uri.isNotBlank() && !existingSet.contains(uri)) {
                array.put(uri)
                existingSet.add(uri)
            }
        }
        jsonObj.put(playlistName, array)
        preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
    }
}

fun MainViewModel.createPlaylist(playlistName: String, initialUris: List<String> = emptyList()) {
    viewModelScope.launch {
        val current = preferencesState.value
        val jsonObj = try {
            org.json.JSONObject(current.playlistsJson)
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        val array = org.json.JSONArray()
        for (uri in initialUris) {
            if (uri.isNotBlank()) {
                array.put(uri)
            }
        }
        jsonObj.put(playlistName, array)
        preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
    }
}

fun MainViewModel.removeMediaFromPlaylist(playlistName: String, uriString: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        val jsonObj = try {
            org.json.JSONObject(current.playlistsJson)
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        if (jsonObj.has(playlistName)) {
            val arr = jsonObj.optJSONArray(playlistName) ?: org.json.JSONArray()
            val newArr = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getString(i)
                if (item != uriString) {
                    newArr.put(item)
                }
            }
            jsonObj.put(playlistName, newArr)
            preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
        }
    }
}

fun MainViewModel.updatePlaylistTracks(playlistName: String, mediaUris: List<String>) {
    viewModelScope.launch {
        val current = preferencesState.value
        val jsonObj = try {
            org.json.JSONObject(current.playlistsJson)
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        val array = org.json.JSONArray()
        for (uri in mediaUris) {
            if (uri.isNotBlank()) {
                array.put(uri)
            }
        }
        jsonObj.put(playlistName, array)
        preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
    }
}

fun MainViewModel.deletePlaylist(playlistName: String) {
    viewModelScope.launch {
        val current = preferencesState.value
        val jsonObj = try {
            org.json.JSONObject(current.playlistsJson)
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        if (jsonObj.has(playlistName)) {
            jsonObj.remove(playlistName)
        }
        preferenceRepository.updatePreferences(current.copy(playlistsJson = jsonObj.toString()))
    }
}

fun MainViewModel.updateCastSettings(
    isCastEnabled: Boolean? = null,
    selectedCastDevice: String? = null,
    castProtocol: String? = null,
    castQuality: String? = null,
    castBufferSize: String? = null,
    autoConnectCast: Boolean? = null,
    castAudioDelayMs: Int? = null,
    castVolume: Float? = null,
    useOpenGlNetworkRemote: Boolean? = null,
    openGlRemoteUrl: String? = null,
    openGlRenderMode: String? = null,
    pauseOnScreenSleep: Boolean? = null,
    keepCastingOnScreenSleep: Boolean? = null
) {
    viewModelScope.launch {
        val current = preferencesState.value
        preferenceRepository.updatePreferences(
            current.copy(
                isCastEnabled = isCastEnabled ?: current.isCastEnabled,
                selectedCastDevice = selectedCastDevice ?: current.selectedCastDevice,
                castProtocol = castProtocol ?: current.castProtocol,
                castQuality = castQuality ?: current.castQuality,
                castBufferSize = castBufferSize ?: current.castBufferSize,
                autoConnectCast = autoConnectCast ?: current.autoConnectCast,
                castAudioDelayMs = castAudioDelayMs ?: current.castAudioDelayMs,
                castVolume = castVolume ?: current.castVolume,
                useOpenGlNetworkRemote = useOpenGlNetworkRemote ?: current.useOpenGlNetworkRemote,
                openGlRemoteUrl = openGlRemoteUrl ?: current.openGlRemoteUrl,
                openGlRenderMode = openGlRenderMode ?: current.openGlRenderMode,
                pauseOnScreenSleep = pauseOnScreenSleep ?: current.pauseOnScreenSleep,
                keepCastingOnScreenSleep = keepCastingOnScreenSleep ?: current.keepCastingOnScreenSleep
            )
        )
    }
}

