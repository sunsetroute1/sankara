package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint

/** User-adjustable image transforms applied before e-ink dither. */
data class ImageEditParams(
    var flipHorizontal: Boolean = false,
    var flipVertical: Boolean = false,
    var invert: Boolean = false,
    /** Clockwise steps of 90° (0–3). */
    var rotationQuarterTurns: Int = 0,
    /** 0.5 = low contrast, 1 = normal, 2 = high. */
    var contrast: Float = 1f,
    /** -100..100 brightness offset. */
    var brightness: Int = 0,
) {
    fun normalizedRotation(): Int = ((rotationQuarterTurns % 4) + 4) % 4

    fun isDefault(): Boolean =
        !flipHorizontal && !flipVertical && !invert &&
            normalizedRotation() == 0 &&
            contrast == 1f && brightness == 0
}

object BitmapEditor {

    fun apply(source: Bitmap, params: ImageEditParams): Bitmap {
        val software = BitmapUtils.toSoftwareBitmap(source) ?: source
        var bmp = software
        val turns = params.normalizedRotation()
        if (turns != 0) {
            bmp = rotateQuarterTurns(bmp, turns)
        }
        if (params.flipHorizontal || params.flipVertical) {
            bmp = flip(bmp, params.flipHorizontal, params.flipVertical)
        }
        if (params.contrast != 1f || params.brightness != 0) {
            bmp = adjustContrastBrightness(bmp, params.contrast, params.brightness)
        }
        if (params.invert) {
            bmp = invertColors(bmp)
        }
        return bmp
    }

    private fun rotateQuarterTurns(source: Bitmap, quarterTurns: Int): Bitmap {
        if (quarterTurns == 0) return source
        val degrees = quarterTurns * 90f
        val matrix = Matrix().apply { postRotate(degrees) }
        val out = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        return out
    }

    private fun flip(source: Bitmap, horizontal: Boolean, vertical: Boolean): Bitmap {
        val matrix = Matrix().apply {
            postScale(
                if (horizontal) -1f else 1f,
                if (vertical) -1f else 1f,
                source.width / 2f,
                source.height / 2f,
            )
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun adjustContrastBrightness(source: Bitmap, contrast: Float, brightness: Int): Bitmap {
        val c = contrast.coerceIn(0.25f, 3f)
        val b = brightness.coerceIn(-100, 100).toFloat()
        val translate = (-0.5f * c + 0.5f) * 255f + b
        val matrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun invertColors(source: Bitmap): Bitmap {
        val matrix = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }
}
