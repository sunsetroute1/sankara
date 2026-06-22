package com.joshuatz.nfceinkwriter

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AlbumArtResolver {

    private const val TAG = "AlbumArtResolver"
    private const val MAX_ART_PX = 512

    suspend fun resolve(context: Context, track: NowPlayingTrack, artSize: Int): ResolvedAlbumArt =
        withContext(Dispatchers.IO) {
            resolveBlocking(context, track, artSize)
        }

    private fun resolveBlocking(context: Context, track: NowPlayingTrack, artSize: Int): ResolvedAlbumArt {
        // 1. Bitmap already attached to media session (zero network)
        track.albumArtBitmap?.let { bmp ->
            val scaled = scaleDown(bmp)
            cacheIfNotPlaceholder(context, track, scaled)
            return ResolvedAlbumArt(scaled, AlbumArtSource.SESSION_BITMAP)
        }
        track.albumArtUri?.let { uri ->
            loadFromUri(context, uri)?.let { bmp ->
                val scaled = scaleDown(bmp)
                AlbumArtCache.put(context, track, scaled)
                return ResolvedAlbumArt(scaled, AlbumArtSource.SESSION_URI)
            }
        }

        // 3. On-disk cache from a previous resolve
        AlbumArtCache.get(context, track)?.let { bmp ->
            return ResolvedAlbumArt(scaleDown(bmp), AlbumArtSource.DISK_CACHE)
        }

        // 4. Local MediaStore album art
        queryMediaStore(context, track)?.let { bmp ->
            val scaled = scaleDown(bmp)
            AlbumArtCache.put(context, track, scaled)
            return ResolvedAlbumArt(scaled, AlbumArtSource.MEDIA_STORE)
        }

        // 5. Large icon captured from the media notification
        if (track.packageName.isNotBlank()) {
            NotificationArtStore.get(track.packageName)?.let { bmp ->
                val scaled = scaleDown(bmp)
                AlbumArtCache.put(context, track, scaled)
                return ResolvedAlbumArt(scaled, AlbumArtSource.NOTIFICATION)
            }
        }

        // 6. Remote metadata services (iTunes, then Deezer)
        RemoteAlbumArtFetcher.fetchItunes(track)?.let { bmp ->
            val scaled = scaleDown(bmp)
            AlbumArtCache.put(context, track, scaled)
            return ResolvedAlbumArt(scaled, AlbumArtSource.REMOTE_ITUNES)
        }

        RemoteAlbumArtFetcher.fetchDeezer(track)?.let { bmp ->
            val scaled = scaleDown(bmp)
            AlbumArtCache.put(context, track, scaled)
            return ResolvedAlbumArt(scaled, AlbumArtSource.REMOTE_DEEZER)
        }

        // 7. Artist-themed generated placeholder
        val placeholder = ArtistPlaceholderGenerator.create(artSize.coerceAtLeast(128), track.artist, track.title)
        Log.i(TAG, "Using artist placeholder for ${track.artist} — ${track.title}")
        return ResolvedAlbumArt(placeholder, AlbumArtSource.ARTIST_PLACEHOLDER)
    }

    private fun cacheIfNotPlaceholder(context: Context, track: NowPlayingTrack, bitmap: Bitmap) {
        AlbumArtCache.put(context, track, bitmap)
    }

    private fun loadFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.w(TAG, "URI load failed: ${e.message}")
            null
        }
    }

    private fun queryMediaStore(context: Context, track: NowPlayingTrack): Bitmap? {
        return try {
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(MediaStore.Audio.Media.ALBUM_ID)
            val selection = "${MediaStore.Audio.Media.TITLE} LIKE ? AND ${MediaStore.Audio.Media.ARTIST} LIKE ?"
            val args = arrayOf("%${track.title}%", "%${track.artist}%")

            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val albumId = cursor.getLong(albumIdCol)
                val artUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId,
                )
                context.contentResolver.openInputStream(artUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore lookup failed: ${e.message}")
            null
        }
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val software = BitmapUtils.toSoftwareBitmap(source) ?: source
        val maxDim = maxOf(software.width, software.height)
        if (maxDim <= MAX_ART_PX) return software
        val scale = MAX_ART_PX.toFloat() / maxDim
        val w = (software.width * scale).toInt().coerceAtLeast(1)
        val h = (software.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(software, w, h, true)
    }
}
