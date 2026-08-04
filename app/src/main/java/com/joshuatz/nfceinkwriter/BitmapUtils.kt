package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import kotlin.math.min

object BitmapUtils {

    /** Media sessions often return HARDWARE bitmaps which crash CPU draw/dither ops. */
    fun toSoftwareBitmap(source: Bitmap?): Bitmap? {
        if (source == null || source.isRecycled) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        ) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (source.config == null || source.config == Bitmap.Config.HARDWARE) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        return source
    }

    fun safeScale(source: Bitmap?, width: Int, height: Int): Bitmap? {
        val bmp = toSoftwareBitmap(source) ?: return null
        if (width <= 0 || height <= 0) return bmp
        if (bmp.width == width && bmp.height == height) return bmp
        return Bitmap.createScaledBitmap(bmp, width, height, true)
    }

    /** Center-crop to target aspect ratio, then scale to exact panel pixels. */
    fun centerCropAndScale(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val software = toSoftwareBitmap(source) ?: source
        if (targetW <= 0 || targetH <= 0) return software
        val targetAspect = targetW.toFloat() / targetH
        val srcAspect = software.width.toFloat() / software.height
        val cropW: Int
        val cropH: Int
        if (srcAspect > targetAspect) {
            cropH = software.height
            cropW = (cropH * targetAspect).toInt().coerceAtLeast(1)
        } else {
            cropW = software.width
            cropH = (cropW / targetAspect).toInt().coerceAtLeast(1)
        }
        val x = ((software.width - cropW) / 2).coerceAtLeast(0)
        val y = ((software.height - cropH) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(software, x, y, cropW.coerceAtMost(software.width - x), cropH.coerceAtMost(software.height - y))
        return Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
    }

    /** Downscale so the longest side is at most [maxSide] pixels (no upscale). */
    fun downscaleToMax(source: Bitmap, maxSide: Int): Bitmap {
        val software = toSoftwareBitmap(source) ?: source
        if (maxSide <= 0) return software
        val longest = maxOf(software.width, software.height)
        if (longest <= maxSide) return software
        val scale = maxSide.toFloat() / longest
        val w = (software.width * scale).toInt().coerceAtLeast(1)
        val h = (software.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(software, w, h, true)
    }

    /** Center-crop to square, then scale. */
    fun centerCropSquare(source: Bitmap, size: Int): Bitmap {
        val software = toSoftwareBitmap(source) ?: source
        if (size <= 0) return software
        val side = minOf(software.width, software.height).coerceAtLeast(1)
        val x = (software.width - side) / 2
        val y = (software.height - side) / 2
        val cropped = Bitmap.createBitmap(software, x, y, side, side)
        return if (size == side) cropped else Bitmap.createScaledBitmap(cropped, size, size, true)
    }

    /**
     * Scale [source] to fit entirely inside [targetW]×[targetH] (aspect preserved), centered on
     * [background]. Output is exactly panel pixel dimensions — no cropping.
     */
    fun fitInsidePanel(
        source: Bitmap,
        targetW: Int,
        targetH: Int,
        background: Int = Color.WHITE,
    ): Bitmap {
        val software = toSoftwareBitmap(source) ?: source
        if (targetW <= 0 || targetH <= 0) return software
        if (software.width == targetW && software.height == targetH) return software

        val scale = min(
            targetW.toFloat() / software.width,
            targetH.toFloat() / software.height,
        )
        val scaledW = (software.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (software.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(software, scaledW, scaledH, true)

        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        canvas.drawBitmap(
            scaled,
            (targetW - scaledW) / 2f,
            (targetH - scaledH) / 2f,
            null,
        )
        if (scaled !== software) scaled.recycle()
        return out
    }
}
