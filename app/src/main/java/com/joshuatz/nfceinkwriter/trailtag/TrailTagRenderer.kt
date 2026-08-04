package com.joshuatz.nfceinkwriter.trailtag

import android.graphics.Bitmap
import android.graphics.Canvas
import com.joshuatz.nfceinkwriter.EInkColorMode
import com.joshuatz.nfceinkwriter.EInkImageProcessor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TrailTagRenderer {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("MMM d h:mm a", Locale.getDefault())

    /** Preview / legacy — full card in one pass. */
    fun render(request: TrailTagRenderRequest, width: Int, height: Int): Bitmap =
        renderForEink(request, width, height, EInkColorMode.DEFAULT)

    /**
     * E-ink pipeline: dither QR + accent base, then paint crisp text on top so halftone
     * never softens letterforms (matches Now Playing overlay technique).
     */
    fun renderForEink(
        request: TrailTagRenderRequest,
        width: Int,
        height: Int,
        colorMode: EInkColorMode,
    ): Bitmap {
        val qrUrl = TrailTagQr.qrTarget(request.profile, request.session)
        val base = TrailTagCardRenderer.renderBase(width, height, qrUrl)
        val dithered = EInkImageProcessor.toEInkBitmap(base, width, height, colorMode)
        if (dithered !== base) base.recycle()
        TrailTagCardRenderer.paintContent(
            canvas = Canvas(dithered),
            width = width,
            height = height,
            request = request,
            formatTime = { timeFormat.format(Date(it)) },
            formatDateTime = { dateTimeFormat.format(Date(it)) },
        )
        return dithered
    }
}
