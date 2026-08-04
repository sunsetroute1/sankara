package com.joshuatz.nfceinkwriter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.joshuatz.nfceinkwriter.trailtag.AdventureStatus
import com.joshuatz.nfceinkwriter.trailtag.TrailTagQr
import com.joshuatz.nfceinkwriter.trailtag.TrailTagRepository
import com.joshuatz.nfceinkwriter.trailtag.TrailTagStatusResolver
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Preview of hosted emergency page — reads local publish payload until cloud backend exists.
 */
class TrailTagPublicProfileActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trail_tag_public_profile)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.trailTagPublicAppBar))
        SystemBarUtils.applyNavigationBarInset(findViewById(R.id.trailTagPublicScroll))

        val repository = TrailTagRepository(this)
        val profile = repository.getProfile()
        val session = repository.getSession()
        val status = TrailTagStatusResolver.resolve(session)
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

        findViewById<MaterialToolbar>(R.id.trail_tag_public_toolbar).setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.publicStatusBadge).text = when (status) {
            AdventureStatus.ACTIVE -> getString(R.string.trail_tag_public_status_active)
            AdventureStatus.PAST_RETURN -> getString(R.string.trail_tag_public_status_past_return)
            AdventureStatus.EMERGENCY -> getString(R.string.trail_tag_public_status_emergency)
            AdventureStatus.NEEDS_UPDATE -> getString(R.string.trail_tag_public_status_needs_update)
            AdventureStatus.NONE -> getString(R.string.trail_tag_public_status_idle)
        }

        if (status == AdventureStatus.PAST_RETURN || status == AdventureStatus.EMERGENCY) {
            val overdueMs = System.currentTimeMillis() - (session?.expectedReturnMs ?: 0L)
            val hours = TimeUnit.MILLISECONDS.toHours(overdueMs).coerceAtLeast(1)
            findViewById<TextView>(R.id.publicOverdueWarning)?.apply {
                visibility = android.view.View.VISIBLE
                text = getString(R.string.trail_tag_public_overdue_warning, hours.toInt())
            }
        }

        findViewById<TextView>(R.id.publicPersonName).text = profile.personLabel()

        if (session != null) {
            findViewById<TextView>(R.id.publicAdventureDetail).text = buildString {
                append(session.activityType.label)
                if (session.location.isNotBlank()) append(" · ").append(session.location)
            }
            findViewById<TextView>(R.id.publicAdventureTimes).text = getString(
                R.string.trail_tag_status_times,
                timeFmt.format(session.startTimeMs),
                timeFmt.format(session.expectedReturnMs),
            )
        } else {
            findViewById<TextView>(R.id.publicAdventureDetail).text = getString(R.string.trail_tag_public_no_adventure)
            findViewById<TextView>(R.id.publicAdventureTimes).text = ""
        }

        val contact = profile.contacts.firstOrNull { it.isConfigured() }
        findViewById<TextView>(R.id.publicEmergencyInfo).text = when {
            contact == null ->
                getString(R.string.trail_tag_public_no_emergency)
            contact.name.isNotBlank() ->
                getString(R.string.trail_tag_public_emergency_line, contact.name, contact.primaryPhone)
            else -> contact.primaryPhone
        }

        val tracking = profile.trackingLinks.filter { it.url.isNotBlank() }
        findViewById<TextView>(R.id.publicTrackingLinks).text = if (tracking.isEmpty()) {
            getString(R.string.trail_tag_public_no_tracking)
        } else {
            tracking.joinToString("\n") { link ->
                val icon = com.joshuatz.nfceinkwriter.trailtag.TrackingProviderRegistry.find(link.providerId)?.icon ?: "🔗"
                "$icon ${link.label}: ${link.url}"
            }
        }

        val qrUrl = TrailTagQr.qrTarget(profile, session)
        findViewById<TextView>(R.id.publicQrUrl).text = qrUrl
        findViewById<TextView>(R.id.publicQrUrl).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(qrUrl)))
        }
    }
}
