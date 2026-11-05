package com.example.porticho

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Servicio en primer plano: mantiene viva la app con la pantalla apagada o fuera de ella. */
class PlaybackService : Service() {
    private lateinit var c: RolaController
    private lateinit var session: MediaSession
    private var wake: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        c = (application as RolaApp).controller
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW))
        }
        session = MediaSession(this, "Rola").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!c.playing) c.toggle() }
                override fun onPause() { if (c.playing) c.toggle() }
                override fun onSkipToNext() { c.next() }
                override fun onSkipToPrevious() { c.prev() }
                override fun onSeekTo(pos: Long) { c.seek(pos / 1000f) }
            })
            isActive = true
        }
        c.listener = { refresh() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> c.toggle()
            ACTION_NEXT -> c.next()
            ACTION_PREV -> c.prev()
            ACTION_STOP -> {
                c.listener = null
                c.stopPlayback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun refresh() {
        val n = buildNotification()
        updateSession()
        if (!started) {
            started = true
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, n)
        } else {
            getSystemService(NotificationManager::class.java).notify(ID, n)
        }
        if (c.playing) {
            if (wake?.isHeld != true) {
                wake = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rola:playback").also { it.acquire() }
            }
        } else wake?.let { if (it.isHeld) it.release() }
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun act(icon: Int, label: String, action: String, code: Int) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi(action, code)).build()

    private fun current(): Pair<String, String> {
        val cur = c.queue.getOrNull(c.idx)
        val m = cur?.vid()?.let { c.meta[it] }
        return Pair(m?.title ?: if (cur?.isList == true) "Lista de reproducción" else "Rola", m?.author ?: "YouTube")
    }

    private fun buildNotification(): Notification {
        val (title, author) = current()
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
        )
        return b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(author)
            .setContentIntent(open).setOngoing(c.playing).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, "Anterior", ACTION_PREV, 1))
            .addAction(
                act(
                    if (c.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (c.playing) "Pausar" else "Reproducir", ACTION_TOGGLE, 2
                )
            )
            .addAction(act(android.R.drawable.ic_media_next, "Siguiente", ACTION_NEXT, 3))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar", ACTION_STOP, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun updateSession() {
        val (title, author) = current()
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, author)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (c.duration * 1000).toLong())
                .build()
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(if (c.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, (c.position * 1000).toLong(), 1f)
                .build()
        )
    }

    override fun onDestroy() {
        c.listener = null
        wake?.let { if (it.isHeld) it.release() }
        session.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "rola_playback"
        const val ID = 1
        const val ACTION_TOGGLE = "com.example.rola.TOGGLE"
        const val ACTION_NEXT = "com.example.rola.NEXT"
        const val ACTION_PREV = "com.example.rola.PREV"
        const val ACTION_STOP = "com.example.rola.STOP"
    }
}
