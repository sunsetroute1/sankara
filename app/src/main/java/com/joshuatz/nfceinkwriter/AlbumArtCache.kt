package com.joshuatz.nfceinkwriter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object AlbumArtCache {

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "album_art").also { it.mkdirs() }

    fun keyFor(track: NowPlayingTrack): String {
        val raw = "${track.artist}|${track.title}|${track.album}".lowercase().trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(context: Context, track: NowPlayingTrack): Bitmap? {
        val file = File(cacheDir(context), keyFor(track) + ".jpg")
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun put(context: Context, track: NowPlayingTrack, bitmap: Bitmap) {
        val file = File(cacheDir(context), keyFor(track) + ".jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
    }
}
