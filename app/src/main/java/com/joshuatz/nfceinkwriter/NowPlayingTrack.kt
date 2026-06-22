package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.net.Uri

data class NowPlayingTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val albumArtBitmap: Bitmap? = null,
    val albumArtUri: Uri? = null,
    val packageName: String = "",
)

enum class AlbumArtSource {
    SESSION_BITMAP,
    SESSION_URI,
    DISK_CACHE,
    MEDIA_STORE,
    NOTIFICATION,
    REMOTE_ITUNES,
    REMOTE_DEEZER,
    ARTIST_PLACEHOLDER,
}

data class ResolvedAlbumArt(
    val bitmap: Bitmap,
    val source: AlbumArtSource,
)
