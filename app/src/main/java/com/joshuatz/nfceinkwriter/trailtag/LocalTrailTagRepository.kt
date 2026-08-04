package com.joshuatz.nfceinkwriter.trailtag

/** Local-only persistence for TrailTag profile, session, and sync metadata. */
class LocalTrailTagRepository(context: android.content.Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    fun getProfile(): TrailTagProfile {
        val json = prefs.getString(KEY_PROFILE, null) ?: return TrailTagProfile.empty()
        return try {
            val profile = TrailTagProfile.fromJson(org.json.JSONObject(json))
            profile.copy(trackingLinks = TrackingProviderRegistry.normalizeLinks(profile.trackingLinks))
        } catch (_: Exception) {
            TrailTagProfile.empty()
        }
    }

    fun saveProfile(profile: TrailTagProfile) {
        prefs.edit().putString(KEY_PROFILE, profile.toJson().toString()).apply()
    }

    fun getSession(): TrailTagSession? {
        val json = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            TrailTagSession.fromJson(org.json.JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }

    fun saveSession(session: TrailTagSession) {
        prefs.edit().putString(KEY_SESSION, session.toJson().toString()).apply()
    }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    fun getSelectedTemplate(): TrailTagTemplate {
        return TrailTagTemplate.fromKey(prefs.getString(KEY_TEMPLATE, null))
    }

    fun setSelectedTemplate(template: TrailTagTemplate) {
        prefs.edit().putString(KEY_TEMPLATE, template.storageKey).apply()
    }

    fun getLastDisplaySyncMs(): Long = prefs.getLong(KEY_LAST_SYNC_MS, 0L)

    fun markDisplaySynced(atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC_MS, atMs).apply()
    }

    companion object {
        private const val PREFS_NAME = "trail_tag"
        private const val KEY_PROFILE = "profile"
        private const val KEY_SESSION = "session"
        private const val KEY_TEMPLATE = "template"
        private const val KEY_LAST_SYNC_MS = "last_display_sync_ms"
    }
}
