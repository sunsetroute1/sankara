package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object RemoteAlbumArtFetcher {

    private const val TAG = "RemoteAlbumArt"

    fun fetchItunes(track: NowPlayingTrack): Bitmap? {
        val term = URLEncoder.encode("${track.artist} ${track.title}", "UTF-8")
        val url = URL("https://itunes.apple.com/search?term=$term&entity=song&limit=1")
        return fetchFromUrl(url)?.let { json ->
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val artUrl = results.getJSONObject(0)
                .optString("artworkUrl100", "")
                .replace("100x100", "600x600")
            if (artUrl.isBlank()) return null
            downloadBitmap(artUrl)
        }
    }

    fun fetchDeezer(track: NowPlayingTrack): Bitmap? {
        val q = URLEncoder.encode("artist:\"${track.artist}\" track:\"${track.title}\"", "UTF-8")
        val url = URL("https://api.deezer.com/search?q=$q&limit=1")
        return fetchFromUrl(url)?.let { json ->
            val data = json.optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val cover = data.getJSONObject(0)
                .optJSONObject("album")
                ?.optString("cover_xl", "")
                ?: data.getJSONObject(0).optJSONObject("album")?.optString("cover_big", "")
            if (cover.isNullOrBlank()) return null
            downloadBitmap(cover)
        }
    }

    private fun fetchFromUrl(url: URL): JSONObject? {
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "Sankara-EInk/1.0")
            }
            conn.inputStream.bufferedReader().use { reader ->
                JSONObject(reader.readText())
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON fetch failed: ${e.message}")
            null
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            conn.inputStream.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bitmap download failed: ${e.message}")
            null
        }
    }
}
