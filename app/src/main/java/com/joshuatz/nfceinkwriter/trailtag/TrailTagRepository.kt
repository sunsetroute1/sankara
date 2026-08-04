package com.joshuatz.nfceinkwriter.trailtag

import android.content.Context

/**
 * Unified TrailTag store — UI uses this facade only.
 * Delegates local CRUD to [LocalTrailTagRepository] and hosted publish/revoke
 * to [HostedTrailTagRepository].
 */
class TrailTagRepository(context: Context) {

    private val local = LocalTrailTagRepository(context)
    private val hosted = HostedTrailTagRepository(context, local)
    private val appContext = context.applicationContext

    fun getProfile(): TrailTagProfile = local.getProfile()

    fun saveProfile(profile: TrailTagProfile) {
        local.saveProfile(profile)
        regenerateLocalHtml()
    }

    fun getSession(): TrailTagSession? = local.getSession()

    fun saveSession(session: TrailTagSession) {
        local.saveSession(session)
        regenerateLocalHtml()
    }

    fun clearSession() {
        local.clearSession()
        regenerateLocalHtml()
    }

    fun getSelectedTemplate(): TrailTagTemplate = local.getSelectedTemplate()

    fun setSelectedTemplate(template: TrailTagTemplate) {
        local.setSelectedTemplate(template)
    }

    fun getLastDisplaySyncMs(): Long = local.getLastDisplaySyncMs()

    fun markDisplaySynced(atMs: Long = System.currentTimeMillis()) {
        local.markDisplaySynced(atMs)
    }

    fun publishEmergencyPage(): HostedPublishResult {
        val profile = getProfile()
        if (!profile.hasMinimumContent()) {
            return HostedPublishResult(success = false, message = "Profile name required")
        }
        return hosted.publish(profile, getSession())
    }

    fun revokeHostedPage(): TrailTagProfile {
        return hosted.revoke(getProfile())
    }

    fun getHostedUrl(): String? = hosted.getHostedUrl(getProfile())

    fun hostedPublishedAtMs(): Long = hosted.publishedAtMs()

    fun regenerateLocalHtml() {
        TrailTagHtmlGenerator.generate(appContext, getProfile(), getSession())
    }

    fun localHtmlIndexFile() = TrailTagHtmlGenerator.indexFile(appContext)
}
