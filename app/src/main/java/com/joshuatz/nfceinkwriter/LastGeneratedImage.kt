package com.joshuatz.nfceinkwriter

import android.content.Context
import java.io.File
import java.text.DateFormat
import java.util.Date

object LastGeneratedImage {
    private const val PREFS = "last_generated_image"
    private const val KEY_SAVED_MS = "saved_at_ms"

    fun markSaved(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SAVED_MS, System.currentTimeMillis())
            .apply()
    }

    fun savedAtMs(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(KEY_SAVED_MS, 0L)
        if (stored > 0L) return stored
        val file = context.getFileStreamPath(GeneratedImageFilename)
        return if (file.exists()) file.lastModified() else 0L
    }

    fun file(context: Context): File? {
        val file = context.getFileStreamPath(GeneratedImageFilename)
        return file.takeIf { it.exists() }
    }

    fun formattedSavedAt(context: Context): String? {
        val ms = savedAtMs(context)
        if (ms <= 0L) return null
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(ms))
    }
}
