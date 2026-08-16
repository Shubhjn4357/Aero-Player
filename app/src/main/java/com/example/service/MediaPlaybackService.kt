package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.example.MainActivity
import com.example.R
import com.example.data.database.MediaEntity
import com.example.receiver.PlayerActionReceiver
import com.example.ui.viewmodel.PlayerControlBridge
import kotlinx.coroutines.*

class MediaPlaybackService : Service() {

    private var mediaSession: MediaSessionCompat? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        initMediaSession()
    }

    private fun initMediaSession() {
        val mbrIntent = Intent(Intent.ACTION_MEDIA_BUTTON, null, this, MediaButtonReceiver::class.java)
        val mbrPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            mbrIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(
            this,
            "AeroPlayerSession",
            ComponentName(this, MediaButtonReceiver::class.java),
            mbrPendingIntent
        ).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setMediaButtonReceiver(mbrPendingIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    PlayerControlBridge.play()
                }

                override fun onPause() {
                    PlayerControlBridge.pause()
                }

                override fun onSkipToNext() {
                    PlayerControlBridge.next()
                }

                override fun onSkipToPrevious() {
                    PlayerControlBridge.prev()
                }

                override fun onSeekTo(pos: Long) {
                    PlayerControlBridge.seekTo(pos)
                }

                override fun onFastForward() {
                    PlayerControlBridge.seekBy(10000L)
                }

                override fun onRewind() {
                    PlayerControlBridge.seekBy(-10000L)
                }

                override fun onStop() {
                    PlayerControlBridge.pause()
                    stopSelf()
                }

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= 33) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                    }

                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_HEADSETHOOK -> {
                                PlayerControlBridge.onHeadsetHookClick()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                PlayerControlBridge.playPause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                PlayerControlBridge.play()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                PlayerControlBridge.pause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                                PlayerControlBridge.next()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                                PlayerControlBridge.prev()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_STOP -> {
                                PlayerControlBridge.pause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                PlayerControlBridge.seekBy(10000L)
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                PlayerControlBridge.seekBy(-10000L)
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Delegate media button intents from Bluetooth/earbuds to MediaSessionCompat
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        val action = intent?.action
        if (action == ACTION_STOP_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val item = currentMediaItem
        val isPlaying = isPlaybackActive

        if (item != null) {
            updateMediaSessionState(isPlaying)
            val notification = buildLiveNotification(this, item, isPlaying, showSeekButtons, mediaSession)
            activeNotification = notification

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            val fallback = activeNotification ?: createFallbackNotification(this)
            try {
                startForeground(NOTIFICATION_ID, fallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return START_STICKY
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_STOP

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )

        currentMediaItem?.let { item ->
            val metaBuilder = android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, item.artist ?: "Aero Player")
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM, item.album ?: "")
                .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, item.duration)

            cachedArtwork?.let { art ->
                metaBuilder.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                metaBuilder.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART, art)
            }
            mediaSession?.setMetadata(metaBuilder.build())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        activeNotification = null
    }

    companion object {
        const val NOTIFICATION_ID = 201
        const val CHANNEL_ID = "aero_player_playback_channel"
        const val ACTION_STOP_SERVICE = "com.example.service.STOP_MEDIA_SERVICE"

        var activeNotification: Notification? = null
        var currentMediaItem: MediaEntity? = null
        var isPlaybackActive: Boolean = false
        var showSeekButtons: Boolean = true
        var cachedArtwork: Bitmap? = null
        private var lastArtworkUri: String? = null

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Live Media Controls",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Native live playback controls and progress for Aero Player"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        fun updateNotification(
            context: Context,
            item: MediaEntity?,
            isPlaying: Boolean,
            seekButtonsEnabled: Boolean = true
        ) {
            currentMediaItem = item
            isPlaybackActive = isPlaying
            showSeekButtons = seekButtonsEnabled

            if (item == null) {
                stopPlaybackService(context)
                return
            }

            ensureNotificationChannel(context)

            // Extract thumbnail asynchronously if changed
            if (lastArtworkUri != item.uriString) {
                lastArtworkUri = item.uriString
                cachedArtwork = extractArtworkBitmap(context, item)
            }

            val intent = Intent(context, MediaPlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // If direct start fails, post directly via notification manager
                val notification = buildLiveNotification(context, item, isPlaying, seekButtonsEnabled, null)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
            }
        }

        fun stopPlaybackService(context: Context) {
            try {
                val intent = Intent(context, MediaPlaybackService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            } catch (e: Exception) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
            }
            activeNotification = null
            currentMediaItem = null
            isPlaybackActive = false
        }

        fun buildLiveNotification(
            context: Context,
            item: MediaEntity,
            isPlaying: Boolean,
            seekButtons: Boolean,
            mediaSession: MediaSessionCompat?
        ): Notification {
            ensureNotificationChannel(context)

            // Tap notification -> open MainActivity with PlayerScreen
            val contentIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("NAVIGATE_TO_PLAYER", true)
                putExtra("MEDIA_URI", item.uriString)
            }
            val pendingContentIntent = PendingIntent.getActivity(
                context,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Action Pending Intents
            fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
                val intent = Intent(context, PlayerActionReceiver::class.java).apply {
                    this.action = action
                }
                return PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val prevIntent = createActionPendingIntent(PlayerActionReceiver.ACTION_PREV, 101)
            val rewindIntent = createActionPendingIntent(PlayerActionReceiver.ACTION_SEEK_BACKWARD, 102)
            val playPauseIntent = createActionPendingIntent(PlayerActionReceiver.ACTION_PLAY_PAUSE, 103)
            val forwardIntent = createActionPendingIntent(PlayerActionReceiver.ACTION_SEEK_FORWARD, 104)
            val nextIntent = createActionPendingIntent(PlayerActionReceiver.ACTION_NEXT, 105)
            val stopIntent = createActionPendingIntent(PlayerActionReceiver.ACTION_STOP, 106)

            val playPauseIcon = if (isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
            val playPauseTitle = if (isPlaying) "Pause" else "Play"

            val subtitleText = buildString {
                if (!item.artist.isNullOrBlank() && item.artist != "<unknown>") {
                    append(item.artist)
                }
                if (!item.album.isNullOrBlank() && item.album != "<unknown>") {
                    if (isNotEmpty()) append(" · ")
                    append(item.album)
                }
                if (isEmpty()) {
                    append(if (item.isVideo) "Video Playback" else "Audio Track")
                }
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(if (item.isVideo) android.R.drawable.ic_media_play else android.R.drawable.ic_media_play)
                .setContentTitle(item.title)
                .setContentText(subtitleText)
                .setSubText(if (item.isVideo) "Aero Video" else "Aero Audio")
                .setContentIntent(pendingContentIntent)
                .setDeleteIntent(stopIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(isPlaying)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)

            if (cachedArtwork != null) {
                builder.setLargeIcon(cachedArtwork)
            }

            // Always add Prev
            builder.addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)

            // Conditionally add -10s rewind
            if (seekButtons) {
                builder.addAction(android.R.drawable.ic_media_rew, "-10s", rewindIntent)
            }

            // Always add Play/Pause
            builder.addAction(playPauseIcon, playPauseTitle, playPauseIntent)

            // Conditionally add +10s forward
            if (seekButtons) {
                builder.addAction(android.R.drawable.ic_media_ff, "+10s", forwardIntent)
            }

            // Always add Next
            builder.addAction(android.R.drawable.ic_media_next, "Next", nextIntent)

            // MediaStyle styling
            val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(if (seekButtons) 2 else 1) // Centers Play/Pause in compact view

            if (mediaSession != null) {
                mediaStyle.setMediaSession(mediaSession.sessionToken)
            }
            mediaStyle.setShowCancelButton(true)
            mediaStyle.setCancelButtonIntent(stopIntent)

            builder.setStyle(mediaStyle)

            return builder.build()
        }

        private fun extractArtworkBitmap(context: Context, item: MediaEntity): Bitmap? {
            return try {
                if (item.isVideo) {
                    val retriever = MediaMetadataRetriever()
                    val path = item.path ?: item.uriString
                    if (path.startsWith("http") || path.startsWith("content://")) {
                        retriever.setDataSource(context, Uri.parse(item.uriString))
                    } else {
                        retriever.setDataSource(path)
                    }
                    val frame = retriever.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    frame
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        val path = item.path ?: item.uriString
                        if (path.startsWith("http") || path.startsWith("content://")) {
                            retriever.setDataSource(context, Uri.parse(item.uriString))
                        } else {
                            retriever.setDataSource(path)
                        }
                        val art = retriever.embeddedPicture
                        retriever.release()
                        if (art != null) {
                            BitmapFactory.decodeByteArray(art, 0, art.size)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        retriever.release()
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        fun createFallbackNotification(context: Context): Notification {
            ensureNotificationChannel(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Aero Player")
                .setContentText("Media playback active")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
