package com.joshuatz.nfceinkwriter.trailtag

/** Predefined tracking service slot — app stores URLs only; no API integration yet. */
interface TrackingProvider {
    val id: String
    val name: String
    val icon: String
}

data class ConfiguredTrackingLink(
    val provider: TrackingProvider,
    val url: String,
)

object TrackingProviderRegistry {

    val garminInReach = object : TrackingProvider {
        override val id = "garmin_inreach"
        override val name = "Garmin inReach"
        override val icon = "📡"
    }

    val garminLiveTrack = object : TrackingProvider {
        override val id = "garmin_livetrack"
        override val name = "Garmin LiveTrack"
        override val icon = "📍"
    }

    val stravaBeacon = object : TrackingProvider {
        override val id = "strava_beacon"
        override val name = "Strava Beacon"
        override val icon = "🚴"
    }

    val googleMaps = object : TrackingProvider {
        override val id = "google_maps"
        override val name = "Google Maps"
        override val icon = "🗺"
    }

    val customUrl = object : TrackingProvider {
        override val id = "custom_url"
        override val name = "Custom URL"
        override val icon = "🔗"
    }

    val defaultSlots: List<TrackingProvider> = listOf(
        garminInReach,
        garminLiveTrack,
        stravaBeacon,
        googleMaps,
        customUrl,
    )

    fun find(id: String): TrackingProvider? = defaultSlots.firstOrNull { it.id == id }

    fun findByLabel(label: String): TrackingProvider? =
        defaultSlots.firstOrNull { it.name.equals(label, ignoreCase = true) }

    /** Migrate legacy label-only links to provider-id keyed links. */
    fun normalizeLinks(links: List<TrackingLink>): List<TrackingLink> {
        if (links.isEmpty()) return TrailTagProfile.defaultTrackingSlots()
        return defaultSlots.map { slot ->
            val existing = links.firstOrNull { it.providerId == slot.id }
                ?: links.firstOrNull { it.label.equals(slot.name, ignoreCase = true) }
            TrackingLink(slot.id, slot.name, existing?.url.orEmpty())
        }
    }
}
