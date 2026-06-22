package com.joshuatz.nfceinkwriter.nfc.passive

import android.graphics.Bitmap
import android.graphics.Color
import com.joshuatz.nfceinkwriter.BitmapUtils
import com.joshuatz.nfceinkwriter.EInkColorMode

object PassiveInkZoneImageEncoder {
    data class LayeredPayload(val blackWhite: ByteArray, val redYellow: ByteArray)

    fun encode(bitmap: Bitmap, width: Int, height: Int, mode: EInkColorMode): LayeredPayload {
        val software = BitmapUtils.toSoftwareBitmap(bitmap) ?: bitmap
        val scaled = Bitmap.createScaledBitmap(software, width, height, true)
        return when (mode) {
            EInkColorMode.FOUR_COLOR -> LayeredPayload(encodeBwyr(scaled), ByteArray(0))
            EInkColorMode.THREE_COLOR -> encodeBwr(scaled)
            EInkColorMode.BLACK_WHITE -> LayeredPayload(encodeBw(scaled), ByteArray(0))
        }
    }

    /** 4-color: single 2bpp column-major plane (0=black, 1=white, 2=yellow, 3=red). */
    private fun encodeBwyr(image: Bitmap): ByteArray {
        val width = image.width
        val height = image.height
        val outputSize = (width * height + 3) / 4
        val output = ByteArray(outputSize)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val colorValue = colorValueFromPixel(image.getPixel(x, y), fourColor = true)
                val index = (x / 4) * height + y
                output[index] = ((output[index].toInt() and 0xFF) shl 2 or colorValue).toByte()
            }
        }
        return output
    }

    /** 3-color: separate B/W and red overlay planes. */
    private fun encodeBwr(image: Bitmap): LayeredPayload {
        val width = image.width
        val height = image.height
        val outputSize = (width * height + 7) / 8
        val blackWhite = ByteArray(outputSize)
        val redOverlay = ByteArray(outputSize)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = image.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val luminance = r * 0.3 + g * 0.59 + b * 0.11
                val bwBit = if (luminance <= 95) 1 else 0
                val redBit = if (r > 95 && g < 95 && b < 95) 1 else 0
                val index = (x / 8) * height + (height - 1 - y)
                blackWhite[index] = ((blackWhite[index].toInt() and 0xFF) shl 1 or bwBit).toByte()
                redOverlay[index] = ((redOverlay[index].toInt() and 0xFF) shl 1 or redBit).toByte()
            }
        }
        return LayeredPayload(blackWhite, redOverlay)
    }

    private fun encodeBw(image: Bitmap): ByteArray {
        val width = image.width
        val height = image.height
        val outputSize = (width * height + 7) / 8
        val output = ByteArray(outputSize)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = image.getPixel(x, y)
                val luminance = Color.red(px) * 0.3 + Color.green(px) * 0.59 + Color.blue(px) * 0.11
                val bit = if (luminance <= 95) 1 else 0
                val index = (x / 8) * height + (height - 1 - y)
                output[index] = ((output[index].toInt() and 0xFF) shl 1 or bit).toByte()
            }
        }
        return output
    }

    private fun colorValueFromPixel(px: Int, fourColor: Boolean): Int {
        val r = Color.red(px)
        val g = Color.green(px)
        val b = Color.blue(px)
        if (fourColor) {
            if (r > 95 && g > 95 && b < 95) return 2 // yellow
            if (r > 95 && g < 95 && b < 95) return 3 // red
        } else if (r > 95 && g < 95 && b < 95) {
            return 3
        }
        val luminance = r * 0.3 + g * 0.59 + b * 0.11
        return if (luminance <= 95) 0 else 1
    }
}
