package com.joshuatz.nfceinkwriter.trailtag

import android.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Offline-first QR targets — embeds a self-contained HTML page in a data: URI
 * so scanners open the profile with no network. Falls back to HTTPS when too large.
 */
object TrailTagOfflineQr {

    /** Max QR URL length for reliable e-ink scan (version ~25, EC-L). */
    const val MAX_QR_URL_CHARS = 2_400

    fun dataUri(profile: TrailTagProfile, session: TrailTagSession?): String {
        val html = TrailTagHtmlGenerator.buildCompactOfflineHtml(profile, session)
        val encoded = URLEncoder.encode(html, StandardCharsets.UTF_8.name())
        val charsetUri = "data:text/html;charset=utf-8,$encoded"
        if (charsetUri.length <= MAX_QR_URL_CHARS) return charsetUri
        val b64 = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "data:text/html;base64,$b64"
    }

    /** Prefer offline data URI; fall back to online universal URL when payload exceeds QR capacity. */
    fun bestQrTarget(profile: TrailTagProfile, session: TrailTagSession?): String {
        if (profile.isHosted() && !profile.hostedToken.isNullOrBlank()) {
            return TrailTagQr.hostedUrl(profile.hostedToken!!)
        }
        val offline = dataUri(profile, session)
        if (offline.length <= MAX_QR_URL_CHARS) return offline
        return TrailTagQr.universalUrl(profile, session)
    }

    fun qrTargetLength(profile: TrailTagProfile, session: TrailTagSession?): Int =
        bestQrTarget(profile, session).length
}
