package com.joshuatz.nfceinkwriter.trailtag

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SharingMode(val storageKey: String) {
    LOCAL_ONLY("local"),
    HOSTED("hosted"),
    ;

    companion object {
        fun fromKey(key: String?): SharingMode =
            entries.firstOrNull { it.storageKey == key } ?: LOCAL_ONLY
    }
}

enum class TrailActivityType(val storageKey: String, val label: String, val shortLabel: String) {
    TRAIL_RUN("trail_run", "Trail Run", "RUN"),
    MOUNTAIN_BIKE("mtb", "Mountain Bike", "MTB"),
    HIKE("hike", "Hike", "HIKE"),
    CLIMB("climb", "Climb", "CLIMB"),
    SKI("ski", "Ski", "SKI"),
    OTHER("other", "Other", "OUT"),
    ;

    companion object {
        fun fromKey(key: String?): TrailActivityType =
            entries.firstOrNull { it.storageKey == key } ?: OTHER
    }
}

enum class TrailTagTemplate(val storageKey: String, val label: String) {
    ACTIVE_ADVENTURE("active", "Active Adventure"),
    VEHICLE_TRAILHEAD("vehicle", "Vehicle Trailhead"),
    EMERGENCY_PROFILE("emergency", "Emergency Profile"),
    ;

    companion object {
        fun fromKey(key: String?): TrailTagTemplate =
            entries.firstOrNull { it.storageKey == key } ?: ACTIVE_ADVENTURE
    }
}

/** Return status for adventure sessions — derived from timestamps, no network required. */
enum class AdventureStatus {
    NONE,
    ACTIVE,
    PAST_RETURN,
    EMERGENCY,
    NEEDS_UPDATE,
}

/** @deprecated Use [AdventureStatus] — kept for renderer compatibility. */
typealias TrailTagStatus = AdventureStatus

data class EmergencyContact(
    val name: String = "",
    val primaryPhone: String = "",
    val secondaryPhone: String = "",
) {
    fun isConfigured(): Boolean = primaryPhone.isNotBlank() || secondaryPhone.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("primaryPhone", primaryPhone)
        put("secondaryPhone", secondaryPhone)
    }

    companion object {
        fun fromJson(json: JSONObject): EmergencyContact = EmergencyContact(
            name = json.optString("name", ""),
            primaryPhone = json.optString("primaryPhone", ""),
            secondaryPhone = json.optString("secondaryPhone", ""),
        )
    }
}

data class MedicalInfo(
    val notes: String = "",
    val allergies: String = "",
    val bloodType: String = "",
) {
    fun hasContent(): Boolean = notes.isNotBlank() || allergies.isNotBlank() || bloodType.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("notes", notes)
        put("allergies", allergies)
        put("bloodType", bloodType)
    }

    companion object {
        fun fromJson(json: JSONObject): MedicalInfo = MedicalInfo(
            notes = json.optString("notes", ""),
            allergies = json.optString("allergies", ""),
            bloodType = json.optString("bloodType", ""),
        )
    }
}

data class TrackingLink(
    val providerId: String,
    val label: String,
    val url: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("providerId", providerId)
        put("label", label)
        put("url", url)
    }

    companion object {
        fun fromJson(json: JSONObject): TrackingLink = TrackingLink(
            providerId = json.optString("providerId", ""),
            label = json.optString("label", ""),
            url = json.optString("url", ""),
        )
    }
}

data class VehicleInfo(
    val makeModel: String = "",
    val color: String = "",
    val licensePlate: String = "",
) {
    fun hasContent(): Boolean = makeModel.isNotBlank() || color.isNotBlank() || licensePlate.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("makeModel", makeModel)
        put("color", color)
        put("licensePlate", licensePlate)
    }

    companion object {
        fun fromJson(json: JSONObject): VehicleInfo = VehicleInfo(
            makeModel = json.optString("makeModel", ""),
            color = json.optString("color", ""),
            licensePlate = json.optString("licensePlate", ""),
        )
    }
}

/** Local safety profile — default LOCAL_ONLY; hosted mode requires explicit publish. */
data class TrailTagProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val photoUri: String? = null,
    val contacts: List<EmergencyContact> = emptyList(),
    val medical: MedicalInfo = MedicalInfo(),
    val trackingLinks: List<TrackingLink> = defaultTrackingSlots(),
    val vehicle: VehicleInfo = VehicleInfo(),
    val sharingMode: SharingMode = SharingMode.LOCAL_ONLY,
    val hostedToken: String? = null,
) {
    val displayName: String get() = name

    fun personLabel(): String = name.trim().ifBlank { "Adventurer" }

    fun hasMinimumContent(): Boolean = name.trim().isNotBlank()

    fun configuredContactCount(): Int = contacts.count { it.isConfigured() }

    fun configuredTrackingCount(): Int = trackingLinks.count { it.url.isNotBlank() }

    fun isHosted(): Boolean = sharingMode == SharingMode.HOSTED && !hostedToken.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("photoUri", photoUri ?: JSONObject.NULL)
        put("photoPath", photoUri ?: JSONObject.NULL) // legacy
        put("contacts", JSONArray().apply { contacts.forEach { put(it.toJson()) } })
        put("medical", medical.toJson())
        put("trackingLinks", JSONArray().apply { trackingLinks.forEach { put(it.toJson()) } })
        put("vehicle", vehicle.toJson())
        put("sharingMode", sharingMode.storageKey)
        put("hostedToken", hostedToken ?: JSONObject.NULL)
        put("hostedSharingEnabled", sharingMode == SharingMode.HOSTED) // legacy
    }

    companion object {
        fun defaultTrackingSlots(): List<TrackingLink> =
            TrackingProviderRegistry.defaultSlots.map { slot ->
                TrackingLink(slot.id, slot.name, "")
            }

        fun fromJson(json: JSONObject): TrailTagProfile {
            val contacts = mutableListOf<EmergencyContact>()
            json.optJSONArray("contacts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    contacts.add(EmergencyContact.fromJson(arr.getJSONObject(i)))
                }
            }
            val links = mutableListOf<TrackingLink>()
            json.optJSONArray("trackingLinks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    links.add(TrackingLink.fromJson(arr.getJSONObject(i)))
                }
            }
            val sharingMode = when {
                json.has("sharingMode") -> SharingMode.fromKey(json.optString("sharingMode"))
                json.optBoolean("hostedSharingEnabled", false) -> SharingMode.HOSTED
                else -> SharingMode.LOCAL_ONLY
            }
            val photo = json.optString("photoUri").takeIf { it.isNotBlank() }
                ?: json.optString("photoPath").takeIf { it.isNotBlank() }

            return TrailTagProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                photoUri = photo,
                contacts = contacts.ifEmpty { listOf(EmergencyContact()) },
                medical = MedicalInfo.fromJson(json.optJSONObject("medical") ?: JSONObject()),
                trackingLinks = links.ifEmpty { defaultTrackingSlots() },
                vehicle = VehicleInfo.fromJson(json.optJSONObject("vehicle") ?: JSONObject()),
                sharingMode = sharingMode,
                hostedToken = json.optString("hostedToken").takeIf { it.isNotBlank() },
            )
        }

        fun empty(): TrailTagProfile = TrailTagProfile(contacts = listOf(EmergencyContact()))
    }
}

/** Active or last-known adventure session shown on the e-ink tag. */
data class TrailTagSession(
    val activityType: TrailActivityType = TrailActivityType.HIKE,
    val location: String = "",
    val route: String = "",
    val startTimeMs: Long = System.currentTimeMillis(),
    val expectedReturnMs: Long = System.currentTimeMillis(),
    val emergencyThresholdMs: Long = System.currentTimeMillis(),
    val notes: String = "",
    val active: Boolean = true,
) {
    fun resolvedStatus(nowMs: Long = System.currentTimeMillis()): AdventureStatus =
        TrailTagStatusResolver.resolve(this, nowMs)

    fun toJson(): JSONObject = JSONObject().apply {
        put("activityType", activityType.storageKey)
        put("location", location)
        put("route", route)
        put("startTimeMs", startTimeMs)
        put("expectedReturnMs", expectedReturnMs)
        put("emergencyThresholdMs", emergencyThresholdMs)
        put("notes", notes)
        put("active", active)
    }

    companion object {
        fun fromJson(json: JSONObject): TrailTagSession = TrailTagSession(
            activityType = TrailActivityType.fromKey(json.optString("activityType")),
            location = json.optString("location", ""),
            route = json.optString("route", ""),
            startTimeMs = json.optLong("startTimeMs", System.currentTimeMillis()),
            expectedReturnMs = json.optLong("expectedReturnMs", System.currentTimeMillis()),
            emergencyThresholdMs = json.optLong("emergencyThresholdMs", System.currentTimeMillis()),
            notes = json.optString("notes", ""),
            active = json.optBoolean("active", true),
        )
    }
}

/** Inputs for rendering a TrailTag e-ink card. */
data class TrailTagRenderRequest(
    val profile: TrailTagProfile,
    val session: TrailTagSession?,
    val template: TrailTagTemplate,
    val status: AdventureStatus = AdventureStatus.NONE,
)

data class HostedPublishResult(
    val success: Boolean,
    val url: String? = null,
    val token: String? = null,
    val message: String? = null,
)
