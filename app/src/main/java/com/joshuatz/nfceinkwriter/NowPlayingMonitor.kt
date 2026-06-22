package com.joshuatz.nfceinkwriter

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

object NowPlayingMonitor {

    @Volatile
    var latest: NowPlayingTrack? = null
        private set

    fun update(track: NowPlayingTrack?) {
        latest = track
    }

    fun readActiveTrack(context: Context): NowPlayingTrack? {
        val controller = readActiveController(context) ?: return latest
        return controllerToTrack(controller)
    }

    fun readActiveController(context: Context): MediaController? {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val component = ComponentName(context, MediaNotificationListener::class.java)
        return try {
            val sessions = manager.getActiveSessions(component)
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }

    fun controllerToTrack(controller: MediaController): NowPlayingTrack {
        val meta = controller.metadata
        return NowPlayingTrack(
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Track",
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "Unknown Artist",
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            albumArtBitmap = extractBitmap(meta),
            albumArtUri = extractArtUri(meta),
            packageName = controller.packageName ?: "",
        )
    }

    @Suppress("DEPRECATION")
    private fun extractBitmap(meta: MediaMetadata?): Bitmap? {
        if (meta == null) return null
        val raw = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        return BitmapUtils.toSoftwareBitmap(raw)
    }

    private fun extractArtUri(meta: MediaMetadata?): Uri? {
        if (meta == null) return null
        val uriString = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        return uriString?.let { Uri.parse(it) }
    }
}

class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        NowPlayingMonitor.update(NowPlayingMonitor.readActiveTrack(this))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) {
            NotificationArtStore.captureFromNotification(this, sbn)
        }
        NowPlayingMonitor.update(NowPlayingMonitor.readActiveTrack(this))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        NowPlayingMonitor.update(NowPlayingMonitor.readActiveTrack(this))
    }
}
