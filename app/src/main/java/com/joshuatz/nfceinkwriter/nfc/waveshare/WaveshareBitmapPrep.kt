package com.joshuatz.nfceinkwriter.nfc.waveshare

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.joshuatz.nfceinkwriter.BitmapUtils
import com.joshuatz.nfceinkwriter.PanelTestPattern

/**
 * Waveshare SDK expects exact panel dimensions and strictly black/white pixels only.
 * @see https://www.waveshare.com/wiki/Android_SDK_for_NFC-Powered_e-Paper
 */
object WaveshareBitmapPrep {

    private const val TAG = "WaveshareBitmapPrep"

    /** Full white frame — use to force a clean panel before a real image after partial transfers. */
    fun blankPanel(width: Int, height: Int): Bitmap = solidPanel(width, height, Color.WHITE)

    fun testPattern(width: Int, height: Int, pattern: PanelTestPattern): Bitmap = when (pattern) {
        PanelTestPattern.WHITE -> solidPanel(width, height, Color.WHITE)
        PanelTestPattern.BLACK -> solidPanel(width, height, Color.BLACK)
        PanelTestPattern.CHECKERBOARD -> checkerboardPanel(width, height)
        PanelTestPattern.HORIZONTAL_BARS -> horizontalBarsPanel(width, height)
    }

    private fun solidPanel(width: Int, height: Int, color: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
    }

    /** 8×8 px tiles — partial refresh shows a sharp seam between updated and stale regions. */
    private fun checkerboardPanel(width: Int, height: Int): Bitmap {
        val tile = 8
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val dark = ((x / tile) + (y / tile)) % 2 == 0
                    setPixel(x, y, if (dark) Color.BLACK else Color.WHITE)
                }
            }
        }
    }

    /** Alternating horizontal bands — reveals row-wise refresh dropouts. */
    private fun horizontalBarsPanel(width: Int, height: Int): Bitmap {
        val band = (height / 8).coerceAtLeast(4)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                val color = if ((y / band) % 2 == 0) Color.BLACK else Color.WHITE
                for (x in 0 until width) {
                    setPixel(x, y, color)
                }
            }
        }
    }

    fun prepare(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val sized = sizeToPanel(bitmap, width, height)
        return snapToStrictMonochrome(sized)
    }

    /**
     * The official native dither (RenderScript) returns blank under DexClassLoader, which clears
     * the panel. Dither here in Kotlin so the engine's r() encoder gets a proper B/W image.
     */
    fun prepareForOfficial(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val sized = sizeToPanel(bitmap, width, height)
        if (sized.width == width && sized.height == height) {
            // Editor / Card Studio already outputs panel pixels. Re-running Floyd–Steinberg on a
            // halftone (or on 3/4-color palette pixels) smears the pattern and can invert tones.
            return if (looksStrictMonochrome(sized)) {
                Log.d(TAG, "Panel-sized strict B/W — preserving editor halftone")
                copyStrictMonochrome(sized)
            } else {
                Log.d(TAG, "Panel-sized palette/gray — snapping to B/W without re-dither")
                snapToStrictMonochrome(sized)
            }
        }
        Log.d(TAG, "Non-panel source ${sized.width}x${sized.height} → Floyd–Steinberg")
        return floydSteinbergMonochrome(sized)
    }

    /** True when sampled pixels are already strict black/white (typical B/W editor output). */
    private fun looksStrictMonochrome(source: Bitmap): Boolean {
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

    /** Per-pixel luminance threshold — no error diffusion (safe for already-dithered input). */
    private fun snapToStrictMonochrome(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            pixels[i] = monochromeFromArgb(pixels[i])
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /** Preserve halftone bit pattern from editor output; only normalize to #000 / #FFF. */
    private fun copyStrictMonochrome(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            pixels[i] = if (Color.red(pixels[i]) >= 128) Color.WHITE else Color.BLACK
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    private fun monochromeFromArgb(color: Int): Int {
        val lum = Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f
        return if (lum >= 128f) Color.WHITE else Color.BLACK
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

    /**
     * The bundled official dex engine ([activity.a.v] / [activity.a.r]) encodes with opposite
     * B/W polarity from our editor preview — swap tones so the panel matches the phone.
     */
    fun invertForOfficialEngine(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            pixels[i] = if (Color.red(pixels[i]) >= 128) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
