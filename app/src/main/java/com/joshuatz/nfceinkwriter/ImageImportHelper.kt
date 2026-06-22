package com.joshuatz.nfceinkwriter

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saves a shared or picked image URI into the private edit pipeline. */
object ImageImportHelper {

    private const val TAG = "ImageImportHelper"

    suspend fun saveUriToPickSource(context: Context, uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val decoded = decodeUri(context.contentResolver, uri) ?: return@withContext false
                val software = BitmapUtils.toSoftwareBitmap(decoded) ?: decoded
                val bounded = BitmapUtils.downscaleToMax(software, 2048)
                context.openFileOutput(PickedSourceFilename, Context.MODE_PRIVATE).use { out ->
                    bounded.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import image from $uri", e)
                false
            }
        }
    }

    fun extractShareUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") != true) return null
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    ?: @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") != true) return null
                val list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    ?: @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                list?.firstOrNull()
            }
            else -> null
        }
    }

    fun isShareIntent(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE

    private fun decodeUri(resolver: ContentResolver, uri: Uri): Bitmap? {
        resolver.openInputStream(uri)?.use { stream ->
            return BitmapFactory.decodeStream(stream)
        }
        return null
    }
}
