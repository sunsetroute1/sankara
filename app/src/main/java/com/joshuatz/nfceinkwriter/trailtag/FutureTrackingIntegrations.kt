package com.joshuatz.nfceinkwriter.trailtag

/** Phase 3 — live API integrations. Interfaces only; not implemented. */
interface GarminConnectProvider {
    suspend fun fetchLiveTrackUrl(): String?
}

interface GarminInReachProvider {
    suspend fun fetchShareUrl(): String?
}

interface StravaLiveProvider {
    suspend fun fetchBeaconUrl(): String?
}

interface HealthConnectProvider {
    suspend fun fetchActivitySummary(): String?
}

/** No-op stubs for future wiring. */
object FutureTrackingIntegrations {
    val garminConnect: GarminConnectProvider = object : GarminConnectProvider {
        override suspend fun fetchLiveTrackUrl(): String? = null
    }
    val garminInReach: GarminInReachProvider = object : GarminInReachProvider {
        override suspend fun fetchShareUrl(): String? = null
    }
    val strava: StravaLiveProvider = object : StravaLiveProvider {
        override suspend fun fetchBeaconUrl(): String? = null
    }
    val healthConnect: HealthConnectProvider = object : HealthConnectProvider {
        override suspend fun fetchActivitySummary(): String? = null
    }
}
