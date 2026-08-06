package com.example.ui.viewmodel

import android.util.Log

data class EqualizerBand(
    val index: Int,
    val centerFrequencyHz: Int,
    val minLevelMilliBel: Short,
    val maxLevelMilliBel: Short,
    val currentLevelMilliBel: Short
)

fun MainViewModel.initEqualizer(audioSessionId: Int) {
    ensureDefaultBands()
    if (audioSessionId == 0) return
    try {
        androidEqualizer?.release()
        val eq = android.media.audiofx.Equalizer(0, audioSessionId).apply {
            enabled = _equalizerEnabled.value
        }
        androidEqualizer = eq
        
        val bands = mutableListOf<EqualizerBand>()
        val numBands = eq.numberOfBands
        val range = eq.bandLevelRange
        val minLevel = range[0]
        val maxLevel = range[1]
        
        for (i in 0 until numBands.toInt()) {
            val centerFreq = eq.getCenterFreq(i.toShort()) / 1000 // mHz to Hz
            val currentLevel = eq.getBandLevel(i.toShort())
            bands.add(EqualizerBand(i, centerFreq, minLevel, maxLevel, currentLevel))
        }
        if (bands.isNotEmpty()) {
            _equalizerBands.value = bands
        } else {
            ensureDefaultBands()
        }
        applySavedEqualizerSettings()
    } catch (e: Throwable) {
        Log.e("MainViewModel", "Failed to initialize Equalizer with session $audioSessionId, using interactive fallback bands", e)
        ensureDefaultBands()
        applySavedEqualizerSettings()
    }
}

fun MainViewModel.ensureDefaultBands() {
    if (_equalizerBands.value.isEmpty()) {
        _equalizerBands.value = listOf(
            EqualizerBand(0, 60, -1500, 1500, 0),
            EqualizerBand(1, 230, -1500, 1500, 0),
            EqualizerBand(2, 910, -1500, 1500, 0),
            EqualizerBand(3, 4000, -1500, 1500, 0),
            EqualizerBand(4, 14000, -1500, 1500, 0)
        )
    }
}

fun MainViewModel.applySavedEqualizerSettings() {
    val preset = _currentEqualizerPreset.value
    if (preset != "Custom") {
        applyPreset(preset)
    } else {
        val eq = androidEqualizer ?: return
        _equalizerBands.value.forEach { band ->
            try {
                eq.setBandLevel(band.index.toShort(), band.currentLevelMilliBel)
            } catch (e: Exception) {}
        }
    }
}

fun MainViewModel.setEqualizerEnabled(enabled: Boolean) {
    _equalizerEnabled.value = enabled
    try {
        androidEqualizer?.enabled = enabled
    } catch (e: Exception) {
        Log.e("MainViewModel", "Failed to set equalizer enabled state", e)
    }
}

fun MainViewModel.setEqualizerBandLevel(bandIndex: Int, levelMilliBel: Short) {
    try {
        androidEqualizer?.setBandLevel(bandIndex.toShort(), levelMilliBel)
        _equalizerBands.value = _equalizerBands.value.map { band ->
            if (band.index == bandIndex) band.copy(currentLevelMilliBel = levelMilliBel) else band
        }
        _currentEqualizerPreset.value = "Custom"
    } catch (e: Exception) {
        Log.e("MainViewModel", "Failed to set band level", e)
    }
}

fun MainViewModel.applyPreset(presetName: String) {
    _currentEqualizerPreset.value = presetName
    val eq = androidEqualizer
    try {
        val bands = _equalizerBands.value
        if (bands.isEmpty()) return
        
        val newBands = bands.map { band ->
            val factor = when (presetName) {
                "Bass Booster" -> {
                    when (band.index) {
                        0 -> 0.7f
                        1 -> 0.5f
                        else -> 0f
                    }
                }
                "Vocal Enhancer" -> {
                    when (band.index) {
                        2 -> 0.6f
                        3 -> 0.4f
                        else -> -0.1f
                    }
                }
                "Jazz Stage" -> {
                    when (band.index) {
                        0 -> 0.4f
                        1 -> 0.1f
                        2 -> -0.2f
                        3 -> 0.2f
                        4 -> 0.5f
                        else -> 0f
                    }
                }
                "Classic Room" -> {
                    when (band.index) {
                        1 -> 0.5f
                        2 -> 0.3f
                        3 -> -0.2f
                        4 -> -0.5f
                        else -> 0f
                    }
                }
                "Studio Flat" -> {
                    0f
                }
                else -> 0f // "Normal"
            }
            
            val maxL = band.maxLevelMilliBel.toFloat()
            val minL = band.minLevelMilliBel.toFloat()
            val targetLevel = if (factor >= 0) {
                (factor * maxL).toInt().toShort()
            } else {
                (-factor * minL).toInt().toShort()
            }
            
            try {
                eq?.setBandLevel(band.index.toShort(), targetLevel)
            } catch (e: Exception) {}
            band.copy(currentLevelMilliBel = targetLevel)
        }
        _equalizerBands.value = newBands
    } catch (e: Exception) {
        Log.e("MainViewModel", "Failed to apply preset", e)
    }
}
