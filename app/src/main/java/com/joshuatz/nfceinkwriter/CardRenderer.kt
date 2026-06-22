package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Renders panel-sized cards (QR, Wi-Fi, contact, links) for e-ink.
 * All sizes are proportional so any Waveshare panel resolution works.
 */
object CardRenderer {

    private const val ACCENT_RED = 0xFFCE1126.toInt()

    fun renderQrCard(width: Int, height: Int, content: String, label: String): Bitmap {
        val bmp = blankCard(width, height)
        val canvas = Canvas(bmp)
        val pad = pad(width, height)

        val hasLabel = label.isNotBlank()
        val labelHeight = if (hasLabel) height * 0.14f else 0f
        val qrSize = (min(width.toFloat(), height - labelHeight) - 2 * pad).toInt()
        val qr = QrCodeGenerator.generate(content, qrSize)
        if (qr == null) {
            drawPlaceholder(canvas, width, height)
            return bmp
        }

        val qrLeft = (width - qrSize) / 2f
        val qrTop = ((height - labelHeight) - qrSize) / 2f
        canvas.drawBitmap(qr, qrLeft, qrTop, null)
        qr.recycle()

        if (hasLabel) {
            val paint = textPaint(height * 0.085f, bold = true)
            val text = ellipsize(paint, label, width - 2 * pad)
            canvas.drawText(
                text,
                (width - paint.measureText(text)) / 2f,
                height - pad,
                paint,
            )
        }
        return bmp
    }

    fun renderWifiCard(width: Int, height: Int, ssid: String, password: String, security: String): Bitmap {
        val bmp = blankCard(width, height)
        val canvas = Canvas(bmp)
        val pad = pad(width, height)
        drawAccentBar(canvas, width, height)

        val payload = QrCodeGenerator.wifiPayload(ssid, password, security)
        val qrSize = (height * 0.8f).toInt().coerceAtMost((width * 0.5f).toInt())
        val qr = if (ssid.isBlank()) null else QrCodeGenerator.generate(payload, qrSize)
        if (qr == null) {
            drawPlaceholder(canvas, width, height)
            return bmp
        }
        val qrLeft = width - qrSize - pad
        canvas.drawBitmap(qr, qrLeft, (height - qrSize) / 2f, null)
        qr.recycle()

        val colWidth = qrLeft - 2 * pad
        var y = height * 0.3f
        val header = textPaint(height * 0.07f, bold = true)
        canvas.drawText(ellipsize(header, "WI-FI NETWORK", colWidth), pad, y, header)

        y += height * 0.16f
        val ssidPaint = textPaint(height * 0.115f, bold = true)
        fitText(ssidPaint, ssid, colWidth, height * 0.07f)
        canvas.drawText(ellipsize(ssidPaint, ssid, colWidth), pad, y, ssidPaint)

        y += height * 0.18f
        val hint = textPaint(height * 0.065f, color = 0xFF444444.toInt())
        canvas.drawText(ellipsize(hint, "Scan to join", colWidth), pad, y, hint)
        return bmp
    }

    fun renderContactCard(
        width: Int,
        height: Int,
        name: String,
        title: String,
        phone: String,
        email: String,
        url: String,
    ): Bitmap {
        val bmp = blankCard(width, height)
        val canvas = Canvas(bmp)
        val pad = pad(width, height)
        drawAccentBar(canvas, width, height)

        val payload = QrCodeGenerator.contactPayload(name, phone, email, url, title)
        val qrSize = (height * 0.72f).toInt().coerceAtMost((width * 0.45f).toInt())
        val qr = if (name.isBlank()) null else QrCodeGenerator.generate(payload, qrSize)
        if (qr == null) {
            drawPlaceholder(canvas, width, height)
            return bmp
        }
        val qrLeft = width - qrSize - pad
        canvas.drawBitmap(qr, qrLeft, (height - qrSize) / 2f, null)
        qr.recycle()

        val colWidth = qrLeft - 2 * pad
        var y = height * 0.22f

        val namePaint = textPaint(height * 0.115f, bold = true)
        fitText(namePaint, name, colWidth, height * 0.07f)
        canvas.drawText(ellipsize(namePaint, name, colWidth), pad, y, namePaint)

        if (title.isNotBlank()) {
            y += height * 0.13f
            val titlePaint = textPaint(height * 0.072f, color = 0xFF333333.toInt())
            canvas.drawText(ellipsize(titlePaint, title, colWidth), pad, y, titlePaint)
        }

        y += height * 0.1f
        canvas.drawRect(pad, y, pad + colWidth * 0.55f, y + max(1f, height * 0.008f), Paint().apply { color = ACCENT_RED })
        y += height * 0.05f

        val linePaint = textPaint(height * 0.07f)
        val lineGap = height * 0.115f
        for (line in listOf(phone, email, url)) {
            if (line.isBlank()) continue
            y += lineGap
            canvas.drawText(ellipsize(linePaint, line, colWidth), pad, y, linePaint)
        }
        return bmp
    }

    fun renderLinksCard(width: Int, height: Int, handle: String, links: List<String>): Bitmap {
        val bmp = blankCard(width, height)
        val canvas = Canvas(bmp)
        val pad = pad(width, height)
        drawAccentBar(canvas, width, height)

        val cleanLinks = links.map { it.trim() }.filter { it.isNotBlank() }
        val primary = cleanLinks.firstOrNull()
        val qrSize = (height * 0.62f).toInt().coerceAtMost((width * 0.42f).toInt())
        val qr = primary?.let { QrCodeGenerator.generate(QrCodeGenerator.normalizeUrl(it), qrSize) }
        if (qr == null) {
            drawPlaceholder(canvas, width, height)
            return bmp
        }

        var y = height * 0.18f
        val handlePaint = textPaint(height * 0.105f, bold = true)
        val handleText = if (handle.isBlank()) "My links" else handle
        fitText(handlePaint, handleText, width - 2 * pad, height * 0.07f)
        canvas.drawText(ellipsize(handlePaint, handleText, width - 2 * pad), pad, y, handlePaint)

        val qrLeft = width - qrSize - pad
        val qrTop = height - qrSize - pad
        canvas.drawBitmap(qr, qrLeft.toFloat(), qrTop.toFloat(), null)
        qr.recycle()

        val colWidth = qrLeft - 2 * pad
        val linePaint = textPaint(height * 0.07f)
        val bulletPaint = textPaint(height * 0.07f, bold = true, color = ACCENT_RED)
        val lineGap = height * 0.13f
        y += height * 0.08f
        for (link in cleanLinks.take(4)) {
            y += lineGap
            if (y > height - pad) break
            canvas.drawText("›", pad, y, bulletPaint)
            val textX = pad + bulletPaint.measureText("› ")
            canvas.drawText(
                ellipsize(linePaint, stripScheme(link), colWidth - (textX - pad)),
                textX,
                y,
                linePaint,
            )
        }
        return bmp
    }

    private fun blankCard(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        return bmp
    }

    private fun pad(width: Int, height: Int): Float = max(4f, min(width, height) * 0.055f)

    private fun drawAccentBar(canvas: Canvas, width: Int, height: Int) {
        canvas.drawRect(
            0f, 0f, width.toFloat(), max(2f, height * 0.018f),
            Paint().apply { color = ACCENT_RED },
        )
    }

    private fun drawPlaceholder(canvas: Canvas, width: Int, height: Int) {
        val paint = textPaint(height * 0.085f, color = 0xFF666666.toInt())
        val text = "Fill in the fields below"
        canvas.drawText(text, (width - paint.measureText(text)) / 2f, height / 2f, paint)
    }

    private fun stripScheme(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    private fun textPaint(size: Float, bold: Boolean = false, color: Int = Color.BLACK): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        }

    private fun fitText(paint: Paint, text: String, maxWidth: Float, minSize: Float) {
        while (paint.textSize > minSize && paint.measureText(text) > maxWidth) {
            paint.textSize -= 1f
        }
    }

    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }
}
