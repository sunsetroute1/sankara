package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Crisp black/white QR bitmaps + standard payload builders. No network needed. */
object QrCodeGenerator {

    /** Pick lowest EC that fits — larger payloads need L for e-ink QR capacity. */
    fun generateBestEffort(content: String, size: Int): Bitmap? {
        for (ec in listOf(ErrorCorrectionLevel.M, ErrorCorrectionLevel.L)) {
            generate(content, size, ec)?.let { return it }
        }
        return null
    }

    fun generate(content: String, size: Int): Bitmap? =
        generate(content, size, ErrorCorrectionLevel.M)

    fun generate(content: String, size: Int, errorCorrection: ErrorCorrectionLevel): Bitmap? {
        if (content.isBlank() || size <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to errorCorrection,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val row = y * size
                for (x in 0 until size) {
                    pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** WIFI:T:WPA;S:ssid;P:pass;; — scanned by both Android and iOS cameras. */
    fun wifiPayload(ssid: String, password: String, security: String): String {
        val sec = if (password.isBlank()) "nopass" else security
        val pwd = if (password.isBlank()) "" else "P:${escape(password)};"
        return "WIFI:T:$sec;S:${escape(ssid)};$pwd;"
    }

    /** MeCard — much more compact than vCard, so QR modules stay big enough for e-ink. */
    fun contactPayload(
        name: String,
        phone: String,
        email: String,
        url: String,
        title: String,
    ): String {
        val sb = StringBuilder("MECARD:")
        sb.append("N:${escape(name)};")
        if (phone.isNotBlank()) sb.append("TEL:${escape(phone)};")
        if (email.isNotBlank()) sb.append("EMAIL:${escape(email)};")
        if (url.isNotBlank()) sb.append("URL:${escape(normalizeUrl(url))};")
        if (title.isNotBlank()) sb.append("NOTE:${escape(title)};")
        sb.append(";")
        return sb.toString()
    }

    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains("://")) return trimmed
        return if (trimmed.contains('.')) "https://$trimmed" else trimmed
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace(":", "\\:")
        .replace("\"", "\\\"")
}
