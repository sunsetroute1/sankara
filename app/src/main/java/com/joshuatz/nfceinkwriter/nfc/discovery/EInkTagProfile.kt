package com.joshuatz.nfceinkwriter.nfc.discovery

import com.joshuatz.nfceinkwriter.EInkColorMode
import org.json.JSONObject

/** How the tag is powered — Sankara focuses on passive NFC harvest for now. */
enum class PowerModel {
    PASSIVE,
    POWERED,
}

enum class ProtocolFamily(val displayName: String, val powerModel: PowerModel) {
    WAVESHARE_NFCA("Waveshare NFC-A", PowerModel.PASSIVE),
    PASSIVE_D0D1("Passive 0xD0/D1", PowerModel.PASSIVE),
    ISODEP_BADGE("IsoDep badge APDU", PowerModel.POWERED),
    UNKNOWN("Unknown", PowerModel.PASSIVE),
}

enum class ProfileSource {
    PROBED,
    CACHED,
    USER_PREF,
}

enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * Unified description of an e-paper module, from discovery or user settings.
 * New passive protocols register probe handlers that populate this model.
 */
data class EInkTagProfile(
    val driverId: String,
    val protocolFamily: ProtocolFamily,
    val width: Int,
    val height: Int,
    val colorMode: EInkColorMode?,
    val displayName: String,
    val hardwareCode: Int? = null,
    val serial: String? = null,
    val waveshareSdkType: Int? = null,
    val screenSizeKey: String? = null,
    val source: ProfileSource = ProfileSource.PROBED,
    val confidence: Confidence = Confidence.MEDIUM,
    val notes: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("driverId", driverId)
        put("protocolFamily", protocolFamily.name)
        put("width", width)
        put("height", height)
        put("colorMode", colorMode?.prefValue)
        put("displayName", displayName)
        hardwareCode?.let { put("hardwareCode", it) }
        serial?.let { put("serial", it) }
        waveshareSdkType?.let { put("waveshareSdkType", it) }
        screenSizeKey?.let { put("screenSizeKey", it) }
        put("source", source.name)
        put("confidence", confidence.name)
        notes?.let { put("notes", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): EInkTagProfile = EInkTagProfile(
            driverId = json.getString("driverId"),
            protocolFamily = ProtocolFamily.valueOf(json.getString("protocolFamily")),
            width = json.getInt("width"),
            height = json.getInt("height"),
            colorMode = json.optString("colorMode").takeIf { it.isNotEmpty() }
                ?.let { EInkColorMode.fromPref(it) },
            displayName = json.getString("displayName"),
            hardwareCode = json.optInt("hardwareCode").takeIf { json.has("hardwareCode") },
            serial = json.optString("serial").takeIf { it.isNotEmpty() },
            waveshareSdkType = json.optInt("waveshareSdkType").takeIf { json.has("waveshareSdkType") },
            screenSizeKey = json.optString("screenSizeKey").takeIf { it.isNotEmpty() },
            source = ProfileSource.valueOf(json.optString("source", ProfileSource.CACHED.name)),
            confidence = Confidence.valueOf(json.optString("confidence", Confidence.MEDIUM.name)),
            notes = json.optString("notes").takeIf { it.isNotEmpty() },
        )
    }
}

data class TagHints(
    val waveshareNdef: Boolean = false,
    val suggestedScreenSizeKey: String? = null,
    val suggestedWaveshareSdkType: Int? = null,
)

data class ProbeAttempt(
    val probeId: String,
    val probeName: String,
    val matched: Boolean,
    val confidence: Confidence? = null,
    val detail: String,
    val rawHex: String? = null,
)

data class ProbeResult(
    val profile: EInkTagProfile,
    val rawHex: String? = null,
)

data class DiscoveryResult(
    val best: ProbeResult?,
    val attempts: List<ProbeAttempt>,
    val passiveOnly: Boolean,
) {
    val driverId: String? get() = best?.profile?.driverId
}
