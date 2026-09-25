package com.aerotech.aeroplayer.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aerotech.aeroplayer.ui.viewmodel.PlayerControlBridge
import com.aerotech.aeroplayer.ui.viewmodel.updateDefaultPlayerEngine

/**
 * BroadcastReceiver enabling CLI / ADB control of the player:
 * Usage via ADB:
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "play"
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "pause"
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "toggle"
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "stop"
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "seek" --el position_ms 30000
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "seek_by" --el offset_ms 10000
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "engine" --es engine "VLC"
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "sub_toggle"
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "sub_delay" --el offset_ms 500
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "sub_encoding" --es encoding "UTF-8"
 * adb shell am broadcast -a com.aerotech.aeroplayer.player.VLC_CLI --es action "load" --es uri "/sdcard/Movies/sample.mkv"
 */
class VlcCliReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_VLC_CLI = "com.aerotech.aeroplayer.player.VLC_CLI"
        const val ACTION_MEDIA_CLI = "com.aerotech.aeroplayer.player.MEDIA_CLI"
        private const val TAG = "VlcCliReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_VLC_CLI && action != ACTION_MEDIA_CLI) return

        val cmd = intent.getStringExtra("action") ?: intent.getStringExtra("command") ?: return
        Log.i(TAG, "CLI Broadcast received: cmd=$cmd")

        when (cmd.lowercase()) {
            "play" -> PlayerControlBridge.play()
            "pause" -> PlayerControlBridge.pause()
            "toggle", "playpause" -> PlayerControlBridge.playPause()
            "stop" -> PlayerControlBridge.stop()
            "next" -> PlayerControlBridge.next()
            "prev", "previous" -> PlayerControlBridge.prev()
            "seek" -> {
                val pos = intent.getLongExtra("position_ms", -1L)
                if (pos >= 0L) {
                    PlayerControlBridge.seekTo(pos)
                }
            }
            "seek_by" -> {
                val offset = intent.getLongExtra("offset_ms", 0L)
                if (offset != 0L) {
                    PlayerControlBridge.seekBy(offset)
                }
            }
            "engine" -> {
                val engine = intent.getStringExtra("engine") ?: "Auto"
                val mapped = when {
                    engine.contains("vlc", ignoreCase = true) -> "VLC Engine (vlcjni)"
                    engine.contains("exo", ignoreCase = true) || engine.contains("media3", ignoreCase = true) -> "Media3 ExoPlayer"
                    else -> "Auto (Smart Format Detection)"
                }
                PlayerControlBridge.viewModelRef?.get()?.updateDefaultPlayerEngine(mapped)
                Log.i(TAG, "Set default engine via CLI: $mapped")
            }
            "sub_toggle" -> {
                PlayerControlBridge.toggleSubtitles()
            }
            "sub_delay" -> {
                val offset = intent.getLongExtra("offset_ms", 0L)
                PlayerControlBridge.adjustSubtitleDelay(offset)
            }
            "sub_encoding" -> {
                val enc = intent.getStringExtra("encoding") ?: "UTF-8"
                PlayerControlBridge.setSubtitleEncoding(enc)
            }
            "sub_style" -> {
                val color = intent.getStringExtra("color")
                val size = intent.getFloatExtra("size", -1f)
                val vlc = PlayerControlBridge.vlcPlayerRef?.get()
                if (vlc != null) {
                    if (color != null) vlc.setSubtitleTextColor(color)
                    if (size > 0f) vlc.setSubtitleSizeSp(size)
                }
            }
            "load" -> {
                val uriStr = intent.getStringExtra("uri") ?: intent.getStringExtra("url")
                if (!uriStr.isNullOrBlank()) {
                    PlayerControlBridge.loadUri(uriStr)
                }
            }
        }
    }
}
