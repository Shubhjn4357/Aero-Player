package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ui.viewmodel.PlayerControlBridge

class PlayerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> PlayerControlBridge.playPause()
            ACTION_PREV -> PlayerControlBridge.prev()
            ACTION_NEXT -> PlayerControlBridge.next()
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.example.ACTION_PREV"
        const val ACTION_NEXT = "com.example.ACTION_NEXT"
    }
}
