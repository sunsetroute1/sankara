package com.joshuatz.nfceinkwriter.trailtag

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.joshuatz.nfceinkwriter.QrCodeGenerator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** 264×176-optimized TrailTag layouts — crisp B/W text for e-ink fidelity. */
object TrailTagCardRenderer {

    private const val ACCENT = 0xFFCE1126.toInt()

    /** Accent bar + QR only — text is painted after dither for maximum sharpness. */
    fun renderBase(width: Int, height: Int, qrUrl: String): Bitmap {
        val bmp = blank(width, height)
        val canvas = Canvas(bmp)
        val pad = pad(width, height)
        val qrSz = qrSize(width, height, pad)
        drawAccentBar(canvas, width, height)
        drawQr(canvas, width, height, qrUrl, qrSz)
        return bmp
    }

    fun paintContent(
        canvas: Canvas,
        width: Int,
        height: Int,
        request: TrailTagRenderRequest,
        formatTime: (Long) -> String,
        formatDateTime: (Long) -> String,
    ) {
        when (request.template) {
            TrailTagTemplate.ACTIVE_ADVENTURE -> paintActiveAdventure(
                canvas, width, height, request.profile, request.session, request.status, formatTime,
            )
            TrailTagTemplate.VEHICLE_TRAILHEAD -> paintVehicleTrailhead(
                canvas, width, height, request.profile, request.session, formatDateTime,
            )
            TrailTagTemplate.EMERGENCY_PROFILE -> paintEmergencyProfile(
                canvas, width, height, request.profile,
            )
        }
    }

    private fun paintActiveAdventure(
        canvas: Canvas,
        width: Int,
        height: Int,
        profile: TrailTagProfile,
        session: TrailTagSession?,
        status: AdventureStatus,
        formatTime: (Long) -> String,
    ) {
        val pad = pad(width, height)
        val qrSz = qrSize(width, height, pad)
        val textW = textColumnWidth(width, pad, qrSz)

        val name = profile.personLabel().uppercase()
        val headerLine = "$name IS OUTSIDE"
        val header = crispTextPaint(height * 0.102f, bold = true)
        fitText(header, headerLine, textW, height * 0.072f)
        drawCrispText(canvas, ellipsize(header, headerLine, textW), pad, height * 0.155f, header)

        val activity = session?.activityType?.label?.uppercase() ?: "OUTDOOR"
        val activityPaint = crispTextPaint(height * 0.128f, bold = true)
        fitText(activityPaint, activity, textW, height * 0.088f)
        drawCrispText(canvas, ellipsize(activityPaint, activity, textW), pad, height * 0.30f, activityPaint)

        val location = session?.location?.trim().orEmpty().ifBlank { "Location not set" }
        val locPaint = crispTextPaint(height * 0.092f, bold = true)
        fitText(locPaint, location, textW, height * 0.068f)
        drawCrispText(canvas, ellipsize(locPaint, location, textW), pad, height * 0.40f, locPaint)

        val body = crispTextPaint(height * 0.078f)
        var y = height * 0.52f
        session?.let {
            drawCrispText(canvas, "Start ${formatTime(it.startTimeMs)}", pad, y, body)
            y += height * 0.105f
            drawCrispText(canvas, "Return ${formatTime(it.expectedReturnMs)}", pad, y, body)
        }

        val statusLine = when (status) {
            AdventureStatus.EMERGENCY -> "CHECK OVERDUE"
            AdventureStatus.PAST_RETURN -> "PAST RETURN"
            AdventureStatus.NEEDS_UPDATE -> "NEEDS UPDATE"
            else -> "SCAN FOR INFO"
        }
        val footer = crispTextPaint(height * 0.072f, bold = true)
        drawCrispText(canvas, statusLine, pad, height * 0.86f, footer)
    }

    private fun paintVehicleTrailhead(
        canvas: Canvas,
        width: Int,
        height: Int,
        profile: TrailTagProfile,
        session: TrailTagSession?,
        formatDateTime: (Long) -> String,
    ) {
        val pad = pad(width, height)
        val qrSz = qrSize(width, height, pad)
        val textW = textColumnWidth(width, pad, qrSz)

        val activity = session?.activityType?.label?.uppercase() ?: "OUTDOOR"
        val headerLine = "OUT $activity"
        val header = crispTextPaint(height * 0.098f, bold = true)
        fitText(header, headerLine, textW, height * 0.068f)
        drawCrispText(canvas, ellipsize(header, headerLine, textW), pad, height * 0.155f, header)

        val name = profile.personLabel()
        val namePaint = crispTextPaint(height * 0.122f, bold = true)
        fitText(namePaint, name, textW, height * 0.082f)
        drawCrispText(canvas, ellipsize(namePaint, name, textW), pad, height * 0.28f, namePaint)

        val labelPaint = crispTextPaint(height * 0.072f, bold = true)
        val body = crispTextPaint(height * 0.078f)
        var y = height * 0.40f
        drawCrispText(canvas, "Trail:", pad, y, labelPaint)
        y += height * 0.095f
        val trail = session?.location?.trim().orEmpty().ifBlank { "—" }
        fitText(body, trail, textW, height * 0.062f)
        drawCrispText(canvas, ellipsize(body, trail, textW), pad, y, body)

        y += height * 0.13f
        drawCrispText(canvas, "Started:", pad, y, labelPaint)
        y += height * 0.095f
        val started = session?.startTimeMs?.let(formatDateTime) ?: "—"
        fitText(body, started, textW, height * 0.062f)
        drawCrispText(canvas, ellipsize(body, started, textW), pad, y, body)

        val hint = crispTextPaint(height * 0.068f, bold = true)
        drawCrispText(canvas, "Scan if concerned", pad, height * 0.88f, hint)
    }

    private fun paintEmergencyProfile(
        canvas: Canvas,
        width: Int,
        height: Int,
        profile: TrailTagProfile,
    ) {
        val pad = pad(width, height)
        val qrSz = qrSize(width, height, pad, heightFraction = 0.50f)
        val textW = textColumnWidth(width, pad, qrSz)

        val title = crispTextPaint(height * 0.095f, bold = true)
        drawCrispText(canvas, "OUTDOOR PROFILE", pad, height * 0.155f, title)

        val name = profile.personLabel()
        val namePaint = crispTextPaint(height * 0.122f, bold = true)
        fitText(namePaint, name, textW, height * 0.082f)
        drawCrispText(canvas, ellipsize(namePaint, name, textW), pad, height * 0.28f, namePaint)

        val labelPaint = crispTextPaint(height * 0.072f, bold = true)
        val body = crispTextPaint(height * 0.078f)
        var y = height * 0.40f
        drawCrispText(canvas, "Emergency Info", pad, y, labelPaint)
        y += height * 0.10f
        val contact = profile.contacts.firstOrNull()
        val contactLine = contact?.primaryPhone?.trim().orEmpty().ifBlank { "See QR profile" }
        fitText(body, contactLine, textW, height * 0.062f)
        drawCrispText(canvas, ellipsize(body, contactLine, textW), pad, y, body)

        y += height * 0.125f
        drawCrispText(canvas, "Tracking Links", pad, y, labelPaint)
        y += height * 0.10f
        val hasTracking = profile.trackingLinks.any { it.url.isNotBlank() }
        val trackingLine = if (hasTracking) "On profile" else "Add in app"
        drawCrispText(canvas, trackingLine, pad, y, body)

        val hint = crispTextPaint(height * 0.068f, bold = true)
        drawCrispText(canvas, "Scan QR", pad, height * 0.88f, hint)
    }

    /** Large scannable QR — ~48% panel width, ~46% height (Card Studio proportions). */
    private fun qrSize(
        width: Int,
        height: Int,
        pad: Float,
        widthFraction: Float = 0.48f,
        heightFraction: Float = 0.46f,
    ): Int = min(
        (height * heightFraction).toInt(),
        (width * widthFraction).toInt(),
    ).coerceAtLeast(56)

    private fun textColumnWidth(width: Int, pad: Float, qrSize: Int): Float =
        (width - qrSize - 3f * pad).coerceAtLeast(width * 0.42f)

    private fun drawQr(
        canvas: Canvas,
        width: Int,
        height: Int,
        url: String,
        qrSize: Int,
    ) {
        val pad = pad(width, height)
        val qr = QrCodeGenerator.generateBestEffort(url, qrSize) ?: return
        val left = round(width - qrSize - pad)
        val top = round(height - qrSize - pad)
        canvas.drawBitmap(qr, left, top, null)
        qr.recycle()
    }

    private fun blank(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.WHITE)
        }

    private fun pad(width: Int, height: Int): Float = max(6f, min(width, height) * 0.045f)

    private fun drawAccentBar(canvas: Canvas, width: Int, height: Int) {
        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            max(3f, height * 0.022f),
            Paint().apply { color = ACCENT },
        )
    }

    /** Pixel-snapped text — no anti-aliasing so e-ink dither stays sharp. */
    private fun crispTextPaint(size: Float, bold: Boolean = false): Paint =
        Paint().apply {
            isAntiAlias = false
            isSubpixelText = false
            isFilterBitmap = false
            color = Color.BLACK
            textSize = size
            isFakeBoldText = bold
            typeface = if (bold) {
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            } else {
                Typeface.SANS_SERIF
            }
        }

    private fun drawCrispText(canvas: Canvas, text: String, x: Float, baseline: Float, paint: Paint) {
        canvas.drawText(text, round(x), round(baseline), paint)
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
