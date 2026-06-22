package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs

object ArtistPlaceholderGenerator {

    private val palette = intArrayOf(
        0xFFCE1126.toInt(), // Sankara red
        0xFF009739.toInt(), // green
        0xFFFCD116.toInt(), // gold
        0xFF1B4D3E.toInt(), // deep green
        0xFF8B1A1A.toInt(), // dark red
        0xFF2E4057.toInt(), // slate
    )

    fun create(size: Int, artist: String, title: String = ""): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val primaryArtist = primaryArtistName(artist)
        val bg = palette[abs(primaryArtist.hashCode()) % palette.size]
        canvas.drawColor(bg)

        // Subtle diagonal stripe — revolutionary poster feel
        val stripe = Paint().apply {
            color = Color.argb(40, 255, 255, 255)
            strokeWidth = size * 0.08f
        }
        canvas.drawLine(0f, size * 0.85f, size.toFloat(), size * 0.15f, stripe)

        val initials = initialsFor(primaryArtist)
        val initPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(initials, size / 2f, size * 0.58f, initPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            textSize = size * 0.11f
            textAlign = Paint.Align.CENTER
        }
        val label = if (primaryArtist.length <= 18) primaryArtist.uppercase() else primaryArtist.take(16).uppercase() + "…"
        canvas.drawText(label, size / 2f, size * 0.82f, labelPaint)

        if (title.isNotBlank()) {
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(160, 255, 255, 255)
                textSize = size * 0.08f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("♫", size / 2f, size * 0.22f, subPaint)
        }

        return bmp
    }

    private fun primaryArtistName(artist: String): String {
        val cleaned = artist
            .substringBefore(" feat.", "")
            .substringBefore(" ft.", "")
            .substringBefore(" featuring", "")
            .substringBefore(",")
            .trim()
        return cleaned.ifBlank { artist.ifBlank { "Unknown" } }
    }

    private fun initialsFor(artist: String): String {
        val words = artist.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "?"
        if (words.size == 1) return words[0].take(2).uppercase()
        return "${words[0].first()}${words[1].first()}".uppercase()
    }
}
