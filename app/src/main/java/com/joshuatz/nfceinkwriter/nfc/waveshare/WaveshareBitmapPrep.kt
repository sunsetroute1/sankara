package com.joshuatz.nfceinkwriter.nfc.waveshare

import android.graphics.Bitmap
import android.graphics.Color
import com.joshuatz.nfceinkwriter.BitmapUtils

/**
 * Waveshare SDK expects exact panel dimensions and strictly black/white pixels only.
 * @see https://www.waveshare.com/wiki/Android_SDK_for_NFC-Powered_e-Paper
 */
object WaveshareBitmapPrep {

    /** Full white frame — use to force a clean panel before a real image after partial transfers. */
    fun blankPanel(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
    }

    fun prepare(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val sized = sizeToPanel(bitmap, width, height)
        return toStrictMonochrome(sized)
    }

    /**
     * The official native dither (RenderScript) returns blank under DexClassLoader, which clears
     * the panel. Dither here in Kotlin so the engine's r() encoder gets a proper B/W image.
     */
    fun prepareForOfficial(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val sized = sizeToPanel(bitmap, width, height)
        // Editor / preview path already dithers to panel pixels — re-running Floyd–Steinberg
        // smears the pattern and can drop user edits (e.g. invert) when jobs race.
        if (sized.width == width && sized.height == height && looksPreDithered(sized)) {
            return toStrictMonochrome(sized)
        }
        return floydSteinbergMonochrome(sized)
    }

    /** True when pixels are already thresholded (typical editor/NFC payload output). */
    private fun looksPreDithered(source: Bitmap): Boolean {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return false
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 16).coerceAtLeast(1)
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val c = source.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (r != g || g != b) return false
                if (r != 0 && r != 255) return false
            }
        }
        return true
    }

    private fun sizeToPanel(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        return if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            BitmapUtils.centerCropAndScale(bitmap, width, height)
        }
    }

    private fun floydSteinbergMonochrome(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val old = gray[idx]
                val newVal = if (old >= 128f) 255f else 0f
                val err = old - newVal
                pixels[idx] = if (newVal >= 128f) Color.WHITE else Color.BLACK
                if (x + 1 < w) gray[idx + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x > 0) gray[idx + w - 1] += err * 3f / 16f
                    gray[idx + w] += err * 5f / 16f
                    if (x + 1 < w) gray[idx + w + 1] += err * 1f / 16f
                }
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    private fun toStrictMonochrome(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
            pixels[i] = if (lum >= 128f) Color.WHITE else Color.BLACK
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
