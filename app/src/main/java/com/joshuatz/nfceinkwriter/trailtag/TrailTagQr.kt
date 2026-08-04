package com.joshuatz.nfceinkwriter.trailtag

/**
 * Universal HTTPS QR targets — payload embedded in URL, decoded client-side by static viewer.
 * Deploy docs/trailtag/ to VIEWER_BASE_URL (GitHub Pages or sankara.app).
 */
object TrailTagQr {

    /**
     * Public static viewer. Default: GitHub Pages for sunsetroute1/sankara.
     * Mirror to https://sankara.app/trailtag/ when DNS is ready (same index.html).
     */
    const val VIEWER_BASE_URL = "https://sunsetroute1.github.io/sankara/trailtag/"

    /** Legacy hosted token URL (Phase 2 cloud — optional). */
    const val HOSTED_BASE_URL = "https://trailtag.sankara.app/t"

    /** In-app deep link — same device only. */
    fun localAppUrl(profileId: String): String = "sankara://trailtag/$profileId"

    fun hostedUrl(token: String): String = "$HOSTED_BASE_URL/$token"

    /**
     * E-ink QR — any phone with a browser can scan and view the safety profile.
     * Data lives in the ?d= parameter; no account or Sankara install required.
     */
    fun universalUrl(profile: TrailTagProfile, session: TrailTagSession?): String {
        val status = TrailTagStatusResolver.resolve(session)
        val token = TrailTagQrPayload.encode(
            TrailTagQrPayload.Snapshot(profile, session, status),
        )
        return "${VIEWER_BASE_URL}?d=$token"
    }

    /** Prefer hosted token URL when published; otherwise universal embedded payload. */
    fun qrTarget(profile: TrailTagProfile, session: TrailTagSession?): String = when {
        profile.isHosted() && !profile.hostedToken.isNullOrBlank() ->
            hostedUrl(profile.hostedToken!!)
        else -> universalUrl(profile, session)
    }
}
