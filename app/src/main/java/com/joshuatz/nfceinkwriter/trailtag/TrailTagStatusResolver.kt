package com.joshuatz.nfceinkwriter.trailtag

/** Derives display status from session timestamps — no network required. */
object TrailTagStatusResolver {

    fun resolve(session: TrailTagSession?, nowMs: Long = System.currentTimeMillis()): AdventureStatus {
        if (session == null) return AdventureStatus.NONE
        if (!session.active) return AdventureStatus.NEEDS_UPDATE
        return when {
            nowMs > session.emergencyThresholdMs -> AdventureStatus.EMERGENCY
            nowMs > session.expectedReturnMs -> AdventureStatus.PAST_RETURN
            else -> AdventureStatus.ACTIVE
        }
    }
}
