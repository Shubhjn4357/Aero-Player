package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.example.ui.viewmodel.PlayerControlBridge

class PlayerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_MEDIA_BUTTON) {
            val keyEvent = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
            }
            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        PlayerControlBridge.playPause()
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        PlayerControlBridge.next()
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        PlayerControlBridge.prev()
                    }
                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        PlayerControlBridge.playPause()
                    }
                }
            }
        } else {
            when (action) {
                ACTION_PLAY_PAUSE -> PlayerControlBridge.playPause()
                ACTION_PREV -> PlayerControlBridge.prev()
                ACTION_NEXT -> PlayerControlBridge.next()
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.example.ACTION_PREV"
        const val ACTION_NEXT = "com.example.ACTION_NEXT"
    }
}
