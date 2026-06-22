package com.joshuatz.nfceinkwriter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

object EInkImageProcessor {

    fun processForDisplay(
        source: Bitmap,
        width: Int,
        height: Int,
        mode: EInkColorMode = EInkColorMode.DEFAULT,
    ): Bitmap {
        val fitted = BitmapUtils.centerCropAndScale(source, width, height)
        return paletteDither(fitted, mode.palette())
    }

    fun toEInkBitmap(
        source: Bitmap,
        width: Int,
        height: Int,
        mode: EInkColorMode = EInkColorMode.DEFAULT,
    ): Bitmap = processForDisplay(source, width, height, mode)

    fun renderNowPlayingCard(
        width: Int,
        height: Int,
        title: String,
        artist: String,
        albumArt: Bitmap? = null,
        mode: EInkColorMode = EInkColorMode.DEFAULT,
    ): Bitmap {
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.WHITE)

        val pad = max(3f, min(width, height) * 0.012f)
        val accentHeight = max(2f, height * 0.006f)

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCE1126.toInt() }
        canvas.drawRect(0f, 0f, width.toFloat(), accentHeight, accent)

        val displayTitle = title.ifBlank { "Unknown Track" }
        val displayArtist = artist.ifBlank { "Unknown Artist" }

        val plan = computeNowPlayingLayout(
            width, height, pad, accentHeight, displayTitle, displayArtist,
        )

        if (plan.useHeroArt) {
            drawAlbumArtHero(canvas, albumArt, plan.artRect)
        } else {
            drawAlbumArt(
                canvas,
                albumArt,
                plan.artRect.left,
                plan.artRect.top,
                plan.artRect.width().toInt().coerceAtLeast(1),
            )
        }

        if (plan.showDivider) {
            canvas.drawLine(
                plan.dividerX0,
                plan.dividerY,
                plan.dividerX1,
                plan.dividerY,
                Paint().apply {
                    color = Color.BLACK
                    strokeWidth = max(1f, width * 0.004f)
                },
            )
        }

        canvas.drawRect(plan.textRect, Paint().apply { color = Color.WHITE })

        val overlayLines = buildTextOverlayLines(plan)
        val dithered = paletteDither(canvasBitmap, mode.palette())
        paintCrispTextOverlay(dithered, TextOverlay(plan.textRect, overlayLines))
        return dithered
    }

    private data class NowPlayingLayout(
        val artRect: RectF,
        val textRect: RectF,
        val useHeroArt: Boolean,
        val showDivider: Boolean,
        val dividerX0: Float,
        val dividerX1: Float,
        val dividerY: Float,
        val showLabel: Boolean,
        val labelSize: Float,
        val titleSize: Float,
        val artistSize: Float,
        val titleLines: List<String>,
        val artistLines: List<String>,
        val textBlockHeight: Float,
    )

    /** Picks stacked vs side-by-side and sizes art/text to consume the full panel. */
    private fun computeNowPlayingLayout(
        width: Int,
        height: Int,
        pad: Float,
        accentHeight: Float,
        title: String,
        artist: String,
    ): NowPlayingLayout {
        val contentLeft = pad
        val contentTop = accentHeight + pad * 0.35f
        val contentW = width - 2 * pad
        val contentH = height - contentTop - pad
        val minDim = min(width, height).toFloat()
        val aspect = contentW / contentH

        // Side-by-side only when the panel is very wide and text still gets a usable column.
        val splitH = aspect >= 2.15f && (contentW * 0.34f) >= 80f

        val artRect: RectF
        val textRect: RectF
        val useHeroArt: Boolean

        if (splitH) {
            val artSide = min(contentH, contentW * 0.62f)
            artRect = RectF(contentLeft, contentTop, contentLeft + artSide, contentTop + artSide)
            val textLeft = artRect.right + pad * 0.2f
            textRect = RectF(textLeft, contentTop, contentLeft + contentW, contentTop + contentH)
            useHeroArt = false
        } else {
            val textFraction = when {
                contentH > contentW * 1.55f -> 0.24f
                contentH > contentW * 1.1f -> 0.27f
                else -> 0.32f
            }
            val gap = pad * 0.15f
            val textH = (contentH * textFraction).coerceIn(44f, contentH * 0.42f)
            val artH = contentH - textH - gap
            useHeroArt = artH < contentW * 0.92f
            if (useHeroArt) {
                artRect = RectF(contentLeft, contentTop, contentLeft + contentW, contentTop + artH)
            } else {
                val side = min(contentW, artH)
                artRect = RectF(
                    contentLeft + (contentW - side) / 2f,
                    contentTop,
                    contentLeft + (contentW + side) / 2f,
                    contentTop + side,
                )
            }
            textRect = RectF(
                contentLeft,
                contentTop + contentH - textH,
                contentLeft + contentW,
                contentTop + contentH,
            )
        }

        val textW = textRect.width()
        val textH = textRect.height()
        val maxTitleLines = when {
            textH > minDim * 0.38f -> 4
            textH > minDim * 0.22f -> 3
            else -> 2
        }
        val maxArtistLines = when {
            textH > minDim * 0.30f -> 3
            else -> 2
        }
        val showLabel = textH >= 28f

        var bestTitle = 10f
        var bestArtist = 8f
        var bestLabel = 6f
        var bestTitleLines = listOf(title)
        var bestArtistLines = listOf(artist)
        var bestBlockH = 0f

        var lo = 8f
        var hi = min(min(textW * 0.24f, textH * 0.52f), minDim * 0.16f).coerceAtLeast(12f)
        while (hi - lo > 0.5f) {
            val titleSize = (lo + hi) / 2f
            val artistSize = (titleSize * 0.72f).coerceAtLeast(8f)
            val labelSize = (titleSize * 0.48f).coerceAtLeast(6f)
            val titlePaint = titlePaint(titleSize)
            val artistPaint = bodyPaint(artistSize)
            val labelPaint = labelPaint(labelSize)
            val titleLines = layoutLines(title, titlePaint, textW, maxTitleLines)
            val artistLines = layoutLines(artist, artistPaint, textW, maxArtistLines)
            val gap1 = pad * 0.18f
            val gap2 = pad * 0.12f
            val blockH = (if (showLabel) measureBlockHeight(listOf("NOW PLAYING"), labelPaint, gap1) else 0f) +
                measureBlockHeight(titleLines, titlePaint, gap2) +
                measureBlockHeight(artistLines, artistPaint, 0f)
            if (blockH <= textH) {
                bestTitle = titleSize
                bestArtist = artistSize
                bestLabel = labelSize
                bestTitleLines = titleLines
                bestArtistLines = artistLines
                bestBlockH = blockH
                lo = titleSize
            } else {
                hi = titleSize
            }
        }

        if (bestBlockH <= 0f) {
            bestTitle = max(9f, textW * 0.11f)
            bestArtist = max(8f, bestTitle * 0.7f)
            bestLabel = max(6f, bestTitle * 0.45f)
            bestTitleLines = layoutLines(title, titlePaint(bestTitle), textW, maxTitleLines)
            bestArtistLines = layoutLines(artist, bodyPaint(bestArtist), textW, maxArtistLines)
            bestBlockH = textH
        }

        val showDivider = !splitH && artRect.bottom < textRect.top - 1f
        val dividerY = (artRect.bottom + textRect.top) / 2f

        return NowPlayingLayout(
            artRect = artRect,
            textRect = textRect,
            useHeroArt = useHeroArt,
            showDivider = showDivider,
            dividerX0 = contentLeft,
            dividerX1 = contentLeft + contentW,
            dividerY = dividerY,
            showLabel = showLabel,
            labelSize = bestLabel,
            titleSize = bestTitle,
            artistSize = bestArtist,
            titleLines = bestTitleLines,
            artistLines = bestArtistLines,
            textBlockHeight = bestBlockH,
        )
    }

    private fun buildTextOverlayLines(plan: NowPlayingLayout): List<TextLine> {
        val lines = mutableListOf<TextLine>()
        val x = plan.textRect.left
        val textH = plan.textRect.height()
        val yStart = plan.textRect.top + max(0f, (textH - plan.textBlockHeight) / 2f)

        val labelStyle = TextStyle(plan.labelSize, bold = true, label = true)
        val titleStyle = TextStyle(plan.titleSize, bold = true, label = false)
        val artistStyle = TextStyle(plan.artistSize, bold = false, label = false)

        val labelPaint = labelPaint(plan.labelSize)
        val titlePaint = titlePaint(plan.titleSize)
        val artistPaint = bodyPaint(plan.artistSize)

        var y = yStart + textBaselineOffset(if (plan.showLabel) labelPaint else titlePaint)
        if (plan.showLabel) {
            y = collectTextLines(lines, listOf("NOW PLAYING"), x, y, labelStyle, labelPaint, plan.labelSize * 0.35f)
        }
        y = collectTextLines(lines, plan.titleLines, x, y, titleStyle, titlePaint, plan.titleSize * 0.28f)
        collectTextLines(lines, plan.artistLines, x, y, artistStyle, artistPaint, 0f)
        return lines
    }

    private data class TextLine(val text: String, val x: Float, val baseline: Float, val style: TextStyle)
    private data class TextStyle(val size: Float, val bold: Boolean, val label: Boolean)
    private data class TextOverlay(val background: RectF, val lines: List<TextLine>)

    private fun paintCrispTextOverlay(bitmap: Bitmap, overlay: TextOverlay) {
        val canvas = Canvas(bitmap)
        canvas.drawRect(overlay.background, Paint().apply { color = Color.WHITE })
        for (line in overlay.lines) {
            canvas.drawText(line.text, line.x, line.baseline, crispTextPaint(line.style))
        }
    }

    private fun crispTextPaint(style: TextStyle): Paint = Paint().apply {
        isAntiAlias = false
        isSubpixelText = false
        color = Color.BLACK
        textSize = style.size
        isFakeBoldText = style.bold
        typeface = if (style.bold) {
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        } else {
            Typeface.DEFAULT
        }
        if (style.label) {
            letterSpacing = 0.1f
        }
    }

    private fun measureBlockHeight(lines: List<String>, paint: Paint, trailingGap: Float): Float {
        if (lines.isEmpty()) return 0f
        return lines.size * lineHeight(paint) * 1.12f + trailingGap
    }

    private fun collectTextLines(
        out: MutableList<TextLine>,
        lines: List<String>,
        x: Float,
        startBaseline: Float,
        style: TextStyle,
        paint: Paint,
        trailingGap: Float,
    ): Float {
        var baseline = startBaseline
        val step = lineHeight(paint) * 1.12f
        for (line in lines) {
            out.add(TextLine(line, x, baseline, style))
            baseline += step
        }
        return baseline + trailingGap
    }

    private fun drawAlbumArt(canvas: Canvas, albumArt: Bitmap?, left: Float, top: Float, size: Int) {
        if (size <= 0) return
        val frame = RectF(left - 1f, top - 1f, left + size + 1f, top + size + 1f)
        canvas.drawRect(frame, Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = max(1f, size * 0.012f)
        })
        if (albumArt != null) {
            val art = BitmapUtils.centerCropSquare(albumArt, size)
            canvas.drawBitmap(art, left, top, null)
        } else {
            canvas.drawRect(left, top, left + size, top + size, Paint().apply {
                color = 0xFFF0F0F0.toInt()
            })
            val noteSize = size * 0.35f
            canvas.drawText(
                "♪",
                left + (size - noteSize) / 2f,
                top + size * 0.62f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = noteSize
                },
            )
        }
    }

    /** Full-width banner crop for wide-but-short panels (e.g. Waveshare 264×176). */
    private fun drawAlbumArtHero(canvas: Canvas, albumArt: Bitmap?, rect: RectF) {
        val w = rect.width().toInt().coerceAtLeast(1)
        val h = rect.height().toInt().coerceAtLeast(1)
        val frame = RectF(rect.left - 1f, rect.top - 1f, rect.right + 1f, rect.bottom + 1f)
        canvas.drawRect(frame, Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = max(1f, min(w, h) * 0.012f)
        })
        if (albumArt != null) {
            val art = BitmapUtils.centerCropAndScale(albumArt, w, h)
            canvas.drawBitmap(art, rect.left, rect.top, null)
        } else {
            canvas.drawRect(rect, Paint().apply { color = 0xFFF0F0F0.toInt() })
            val noteSize = min(w, h) * 0.35f
            canvas.drawText(
                "♪",
                rect.left + (w - noteSize) / 2f,
                rect.top + h * 0.62f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = noteSize
                },
            )
        }
    }

    private fun labelPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666666.toInt()
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.08f
    }

    private fun titlePaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun bodyPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
    }

    private fun lineHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return fm.descent - fm.ascent
    }

    private fun textBaselineOffset(paint: Paint): Float = -paint.fontMetrics.ascent

    private fun drawTextLines(
        canvas: Canvas,
        lines: List<String>,
        x: Float,
        y: Float,
        paint: Paint,
    ): Float {
        var baseline = y
        val step = lineHeight(paint)
        for (line in lines) {
            canvas.drawText(line, x, baseline, paint)
            baseline += step
        }
        return baseline
    }

    private fun layoutLines(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (maxWidth <= 0 || maxLines <= 0) return emptyList()
        if (paint.measureText(text) <= maxWidth) return listOf(text)

        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(ellipsize(text, paint, maxWidth))

        val lines = mutableListOf<String>()
        var current = StringBuilder()

        for (word in words) {
            val trial = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(trial) <= maxWidth) {
                current = StringBuilder(trial)
            } else {
                if (current.isNotEmpty()) {
                    lines.add(current.toString())
                    current = StringBuilder()
                }
                if (lines.size >= maxLines) break
                if (paint.measureText(word) <= maxWidth) {
                    current = StringBuilder(word)
                } else {
                    lines.add(ellipsize(word, paint, maxWidth))
                    return lines.take(maxLines)
                }
            }
            if (lines.size >= maxLines) break
        }

        if (current.isNotEmpty() && lines.size < maxLines) {
            lines.add(current.toString())
        }

        if (lines.size > maxLines) {
            return lines.take(maxLines - 1) + ellipsize(lines[maxLines - 1], paint, maxWidth)
        }

        val joined = lines.joinToString(" ")
        if (joined.length < text.length && lines.isNotEmpty()) {
            lines[lines.lastIndex] = ellipsize(lines.last(), paint, maxWidth)
        }
        return lines
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }

    private fun paletteDither(source: Bitmap, palette: IntArray): Bitmap {
        if (palette.size == 2) {
            return floydSteinbergMonochrome(source)
        }
        return floydSteinbergPalette(source, palette)
    }

    private fun floydSteinbergMonochrome(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            gray[i] = luminance(pixels[i])
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = gray[i]
                val new = if (old >= 128f) 255f else 0f
                gray[i] = new
                diffuseError(gray, w, h, x, y, old - new)
            }
        }

        for (i in pixels.indices) {
            val v = if (gray[i] >= 128f) 255 else 0
            pixels[i] = Color.rgb(v, v, v)
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    private fun floydSteinbergPalette(source: Bitmap, palette: IntArray): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        for (i in pixels.indices) {
            r[i] = Color.red(pixels[i]).toFloat()
            g[i] = Color.green(pixels[i]).toFloat()
            b[i] = Color.blue(pixels[i]).toFloat()
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val oldR = r[i]
                val oldG = g[i]
                val oldB = b[i]
                val nearest = nearestPaletteColor(oldR, oldG, oldB, palette)
                val newR = Color.red(nearest).toFloat()
                val newG = Color.green(nearest).toFloat()
                val newB = Color.blue(nearest).toFloat()
                r[i] = newR
                g[i] = newG
                b[i] = newB
                pixels[i] = nearest
                diffuseError(r, w, h, x, y, oldR - newR)
                diffuseError(g, w, h, x, y, oldG - newG)
                diffuseError(b, w, h, x, y, oldB - newB)
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    private fun diffuseError(channel: FloatArray, w: Int, h: Int, x: Int, y: Int, err: Float) {
        val i = y * w + x
        if (x + 1 < w) channel[i + 1] += err * 7f / 16f
        if (y + 1 < h) {
            if (x > 0) channel[i + w - 1] += err * 3f / 16f
            channel[i + w] += err * 5f / 16f
            if (x + 1 < w) channel[i + w + 1] += err * 1f / 16f
        }
    }

    private fun nearestPaletteColor(r: Float, g: Float, b: Float, palette: IntArray): Int {
        var best = palette[0]
        var bestDist = Float.MAX_VALUE
        for (color in palette) {
            val dr = r - Color.red(color)
            val dg = g - Color.green(color)
            val db = b - Color.blue(color)
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                best = color
            }
        }
        return best
    }

    private fun luminance(color: Int): Float =
        Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f
}
