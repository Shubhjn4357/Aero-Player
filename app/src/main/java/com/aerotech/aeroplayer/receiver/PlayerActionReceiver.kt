package com.aerotech.aeroplayer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import com.aerotech.aeroplayer.ui.viewmodel.PlayerControlBridge

class PlayerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        
        if (action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            // Earbuds or headphones unplugged / Bluetooth disconnected
            PlayerControlBridge.pause()
            return
        }

        if (action == Intent.ACTION_HEADSET_PLUG) {
            val state = intent.getIntExtra("state", -1)
            if (state == 0) {
                // Headset unplugged
                PlayerControlBridge.pause()
            } else if (state == 1) {
                // Headset plugged in
                PlayerControlBridge.onHeadsetPluggedIn()
            }
            return
        }

        when (action) {
            ACTION_PLAY_PAUSE -> PlayerControlBridge.playPause()
            ACTION_PLAY -> PlayerControlBridge.play()
            ACTION_PAUSE -> PlayerControlBridge.pause()
            ACTION_PREV -> PlayerControlBridge.prev()
            ACTION_NEXT -> PlayerControlBridge.next()
            ACTION_SEEK_FORWARD -> PlayerControlBridge.seekBy(10000L)
            ACTION_SEEK_BACKWARD -> PlayerControlBridge.seekBy(-10000L)
            ACTION_STOP -> PlayerControlBridge.pause()
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.aerotech.aeroplayer.ACTION_PLAY_PAUSE"
        const val ACTION_PLAY = "com.aerotech.aeroplayer.ACTION_PLAY"
        const val ACTION_PAUSE = "com.aerotech.aeroplayer.ACTION_PAUSE"
        const val ACTION_PREV = "com.aerotech.aeroplayer.ACTION_PREV"
        const val ACTION_NEXT = "com.aerotech.aeroplayer.ACTION_NEXT"
        const val ACTION_SEEK_FORWARD = "com.aerotech.aeroplayer.ACTION_SEEK_FORWARD"
        const val ACTION_SEEK_BACKWARD = "com.aerotech.aeroplayer.ACTION_SEEK_BACKWARD"
        const val ACTION_STOP = "com.aerotech.aeroplayer.ACTION_STOP"
    }
}

