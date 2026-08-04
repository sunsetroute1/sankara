package com.joshuatz.nfceinkwriter.trailtag

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Hosted emergency page storage — no cloud backend in MVP.
 * Generates unguessable tokens and stores the publish payload locally.
 * A future server upload would plug in here without UI changes.
 */
class HostedTrailTagRepository(
    context: Context,
    private val local: LocalTrailTagRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    fun getHostedUrl(profile: TrailTagProfile): String? {
        val token = profile.hostedToken ?: return null
        return TrailTagQr.hostedUrl(token)
    }

    fun getPublishedPayload(): JSONObject? {
        val json = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return try {
            JSONObject(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Publish emergency page — stores payload locally and marks profile HOSTED.
     * Does not upload to a server (local-first POC).
     */
    fun publish(profile: TrailTagProfile, session: TrailTagSession?): HostedPublishResult {
        val token = profile.hostedToken?.takeIf { it.isNotBlank() } ?: generateToken()
        val payload = buildPayload(profile, session, token)
        prefs.edit()
            .putString(KEY_PAYLOAD, payload.toString())
            .putLong(KEY_PUBLISHED_MS, System.currentTimeMillis())
            .apply()

        val updated = profile.copy(
            sharingMode = SharingMode.HOSTED,
            hostedToken = token,
        )
        local.saveProfile(updated)

        // Regenerate local HTML bundle so offline preview stays in sync
        TrailTagHtmlGenerator.generate(appContext, updated, session)

        val url = TrailTagQr.hostedUrl(token)
        return HostedPublishResult(success = true, url = url, token = token)
    }

    fun revoke(profile: TrailTagProfile): TrailTagProfile {
        prefs.edit()
            .remove(KEY_PAYLOAD)
            .remove(KEY_PUBLISHED_MS)
            .apply()
        val updated = profile.copy(
            sharingMode = SharingMode.LOCAL_ONLY,
            hostedToken = null,
        )
        local.saveProfile(updated)
        return updated
    }

    fun publishedAtMs(): Long = prefs.getLong(KEY_PUBLISHED_MS, 0L)

    private fun buildPayload(
        profile: TrailTagProfile,
        session: TrailTagSession?,
        token: String,
    ): JSONObject = JSONObject().apply {
        put("token", token)
        put("profileId", profile.id)
        put("publishedAtMs", System.currentTimeMillis())
        put("profile", profile.toJson())
        put("session", session?.toJson() ?: JSONObject.NULL)
        put("noindex", true)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "trail_tag_hosted"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_PUBLISHED_MS = "published_at_ms"
    }
}
