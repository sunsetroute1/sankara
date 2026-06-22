package com.joshuatz.nfceinkwriter.nfc.discovery.probes

import android.nfc.Tag
import com.joshuatz.nfceinkwriter.nfc.apdu.IsoDepEInkProtocol
import com.joshuatz.nfceinkwriter.nfc.discovery.Confidence
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProfile
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.ProbeResult
import com.joshuatz.nfceinkwriter.nfc.discovery.ProtocolFamily
import com.joshuatz.nfceinkwriter.nfc.discovery.TagHints
import com.joshuatz.nfceinkwriter.nfc.isWaveshareTag
import com.joshuatz.nfceinkwriter.nfc.tagHasIsoDep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Powered badge modules (IsoDep + APDU). Included for completeness but filtered
 * out when [EInkDiscoveryEngine.discover] runs in passive-only mode.
 */
class IsoDepBadgeTagProbe : EInkTagProbe {
    override val id = "isodep_badge"
    override val displayName = "IsoDep badge APDU"
    override val priority = 30
    override val protocolFamily = ProtocolFamily.ISODEP_BADGE

    override suspend fun probe(tag: Tag, hints: TagHints): ProbeResult? = withContext(Dispatchers.IO) {
        if (!tagHasIsoDep(tag)) return@withContext null
        if (isWaveshareTag(tag, hints.waveshareNdef)) return@withContext null

        val info = IsoDepEInkProtocol.probeDeviceInfo(tag) ?: return@withContext null
        val colorMode = when (info.bitsPerPixel) {
            1 -> com.joshuatz.nfceinkwriter.EInkColorMode.BLACK_WHITE
            2 -> com.joshuatz.nfceinkwriter.EInkColorMode.FOUR_COLOR
            else -> null
        }
        ProbeResult(
            profile = EInkTagProfile(
                driverId = DRIVER_ID,
                protocolFamily = ProtocolFamily.ISODEP_BADGE,
                width = info.width,
                height = info.height,
                colorMode = colorMode,
                displayName = "Badge ${info.width}×${info.height}",
                serial = info.serialNumber,
                confidence = Confidence.HIGH,
                notes = "Powered IsoDep module — not passive harvest.",
            ),
        )
    }

    companion object {
        const val DRIVER_ID = "isodep_badge"
    }
}
