package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.receiver.PlayerActionReceiver

class PlayerWidget4x1 : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        PlayerWidgetProvider.update4x1Widgets(context, appWidgetManager, appWidgetIds)
    }
}

class PlayerWidget2x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        PlayerWidgetProvider.update2x2Widgets(context, appWidgetManager, appWidgetIds)
    }
}

object PlayerWidgetProvider {
    private var lastTitle: String = "Aero Player"
    private var lastArtist: String = "Select track to play"
    private var lastIsPlaying: Boolean = false
    private var lastArt: Bitmap? = null
    private var lastProgressMs: Long = 0L
    private var lastDurationMs: Long = 0L

    fun updateAll(
        context: Context,
        title: String,
        artist: String,
        isPlaying: Boolean,
        art: Bitmap? = null,
        progressMs: Long = 0L,
        durationMs: Long = 0L
    ) {
        lastTitle = title.ifEmpty { "Aero Player" }
        lastArtist = artist.ifEmpty { "Select track to play" }
        lastIsPlaying = isPlaying
        lastArt = art
        lastProgressMs = progressMs
        lastDurationMs = durationMs

        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return

        try {
            val ids4x1 = appWidgetManager.getAppWidgetIds(ComponentName(context, PlayerWidget4x1::class.java))
            if (ids4x1 != null && ids4x1.isNotEmpty()) {
                update4x1Widgets(context, appWidgetManager, ids4x1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val ids2x2 = appWidgetManager.getAppWidgetIds(ComponentName(context, PlayerWidget2x2::class.java))
            if (ids2x2 != null && ids2x2.isNotEmpty()) {
                update2x2Widgets(context, appWidgetManager, ids2x2)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    fun update4x1Widgets(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, PlayerActionReceiver::class.java).apply { action = PlayerActionReceiver.ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, PlayerActionReceiver::class.java).apply { action = PlayerActionReceiver.ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, PlayerActionReceiver::class.java).apply { action = PlayerActionReceiver.ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progressVal = if (lastDurationMs > 0) {
            ((lastProgressMs * 1000) / lastDurationMs).toInt().coerceIn(0, 1000)
        } else 0

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_player_4x1)
            views.setTextViewText(R.id.widget_title, lastTitle)
            views.setTextViewText(R.id.widget_artist, lastArtist)
            views.setImageViewResource(R.id.widget_btn_play_pause, if (lastIsPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            views.setProgressBar(R.id.widget_progress, 1000, progressVal, false)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    val accentColor = context.getColor(android.R.color.system_accent1_100)
                    views.setTextColor(R.id.widget_title, accentColor)
                } catch (e: Exception) { e.printStackTrace() }
            }

            if (lastArt != null) {
                views.setImageViewBitmap(R.id.widget_art, lastArt)
            } else {
                views.setImageViewResource(R.id.widget_art, R.drawable.ic_widget_music)
            }

            views.setOnClickPendingIntent(R.id.widget_container, openAppIntent)
            views.setOnClickPendingIntent(R.id.widget_btn_prev, prevIntent)
            views.setOnClickPendingIntent(R.id.widget_btn_play_pause, playPauseIntent)
            views.setOnClickPendingIntent(R.id.widget_btn_next, nextIntent)

            appWidgetManager.updateAppWidget(id, views)
        }
    }

    fun update2x2Widgets(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getBroadcast(
            context, 10,
            Intent(context, PlayerActionReceiver::class.java).apply { action = PlayerActionReceiver.ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getBroadcast(
            context, 11,
            Intent(context, PlayerActionReceiver::class.java).apply { action = PlayerActionReceiver.ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getBroadcast(
            context, 12,
            Intent(context, PlayerActionReceiver::class.java).apply { action = PlayerActionReceiver.ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progressVal = if (lastDurationMs > 0) {
            ((lastProgressMs * 1000) / lastDurationMs).toInt().coerceIn(0, 1000)
        } else 0
        val timeText = "${formatMs(lastProgressMs)} / ${formatMs(lastDurationMs)}"

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_player_2x2)
            views.setTextViewText(R.id.widget_title_2x2, lastTitle)
            views.setTextViewText(R.id.widget_artist_2x2, lastArtist)
            views.setImageViewResource(R.id.widget_btn_play_pause_2x2, if (lastIsPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            views.setProgressBar(R.id.widget_progress_2x2, 1000, progressVal, false)
            views.setTextViewText(R.id.widget_time_2x2, timeText)

            if (lastArt != null) {
                views.setImageViewBitmap(R.id.widget_art_2x2, lastArt)
            } else {
                views.setImageViewResource(R.id.widget_art_2x2, R.drawable.ic_widget_music)
            }

            views.setOnClickPendingIntent(R.id.widget_container_2x2, openAppIntent)
            views.setOnClickPendingIntent(R.id.widget_btn_prev_2x2, prevIntent)
            views.setOnClickPendingIntent(R.id.widget_btn_play_pause_2x2, playPauseIntent)
            views.setOnClickPendingIntent(R.id.widget_btn_next_2x2, nextIntent)

            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
