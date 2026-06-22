package com.joshuatz.nfceinkwriter.nfc.apdu

import android.graphics.Bitmap
import android.graphics.Color
import com.joshuatz.nfceinkwriter.BitmapUtils
import com.joshuatz.nfceinkwriter.EInkColorMode

object IsoDepImageEncoder {
    /** Protocol color order: 0=black, 1=white, 2=yellow, 3=red */
    fun bitmapToPixelIndices(bitmap: Bitmap, device: IsoDepDeviceInfo, mode: EInkColorMode): Array<IntArray> {
        val software = BitmapUtils.toSoftwareBitmap(bitmap) ?: bitmap
        val scaled = Bitmap.createScaledBitmap(software, device.width, device.height, true)
        val palette = mode.palette()

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        return Array(h) { y ->
            IntArray(w) { x ->
                val px = pixels[y * w + x]
                val nearestAppIndex = nearestPaletteIndex(px, palette)
                indexToProtocol(nearestAppIndex, mode)
            }
        }
    }

    private fun indexToProtocol(appIndex: Int, mode: EInkColorMode): Int {
        if (mode != EInkColorMode.FOUR_COLOR) {
            return appIndex.coerceIn(0, 1)
        }
        return when (appIndex) {
            0 -> 0 // black
            1 -> 1 // white
            2 -> 3 // red
            3 -> 2 // yellow
            else -> 1
        }
    }

    private fun nearestPaletteIndex(color: Int, palette: IntArray): Int {
        var best = 0
        var bestDist = Long.MAX_VALUE
        val a = Color.alpha(color)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        for (i in palette.indices) {
            val pr = Color.red(palette[i])
            val pg = Color.green(palette[i])
            val pb = Color.blue(palette[i])
            val dr = r - pr
            val dg = g - pg
            val db = b - pb
            val da = a - 255
            val dist = dr.toLong() * dr + dg.toLong() * dg + db.toLong() * db + da.toLong() * da
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }
}
