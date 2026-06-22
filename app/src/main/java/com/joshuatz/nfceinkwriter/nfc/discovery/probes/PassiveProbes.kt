package com.joshuatz.nfceinkwriter.nfc.discovery.probes

import android.content.Context
import android.nfc.Tag
import com.joshuatz.nfceinkwriter.EInkColorMode
import com.joshuatz.nfceinkwriter.ScreenSizes
import com.joshuatz.nfceinkwriter.ScreenSizesInPixels
import com.joshuatz.nfceinkwriter.nfc.discovery.Confidence
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProfile
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.PassiveHardwareCatalog
import com.joshuatz.nfceinkwriter.nfc.discovery.ProbeResult
import com.joshuatz.nfceinkwriter.nfc.discovery.ProtocolFamily
import com.joshuatz.nfceinkwriter.nfc.discovery.TagHints
import com.joshuatz.nfceinkwriter.nfc.isWaveshareTag
import com.joshuatz.nfceinkwriter.nfc.passive.PassiveInkZoneProtocol
import com.joshuatz.nfceinkwriter.nfc.tagHasNfcA
import com.joshuatz.nfceinkwriter.nfc.waveshare.WavesharePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WaveshareTagProbe : EInkTagProbe {
    override val id = "waveshare_uid"
    override val displayName = "Waveshare UID / NDEF"
    override val priority = 10
    override val protocolFamily = ProtocolFamily.WAVESHARE_NFCA

    override suspend fun probe(tag: Tag, hints: TagHints): ProbeResult? {
        if (!isWaveshareTag(tag, hints.waveshareNdef)) return null

        val screenKey = hints.suggestedScreenSizeKey ?: ScreenSizes[5]
        val pixels = ScreenSizesInPixels[screenKey] ?: (264 to 176)
        val sdkType = hints.suggestedWaveshareSdkType
            ?: WavesharePanel.sdkTypeForScreenKey(screenKey)

        return ProbeResult(
            profile = EInkTagProfile(
                driverId = DRIVER_ID,
                protocolFamily = ProtocolFamily.WAVESHARE_NFCA,
                width = pixels.first,
                height = pixels.second,
                colorMode = EInkColorMode.BLACK_WHITE,
                displayName = "Waveshare $screenKey",
                waveshareSdkType = sdkType,
                screenSizeKey = screenKey,
                confidence = Confidence.HIGH,
                notes = "Panel size from app settings — confirm in Settings if unsure.",
            ),
        )
    }

    companion object {
        const val DRIVER_ID = "waveshare"
    }
}

class PassiveD0D1TagProbe(private val context: Context) : EInkTagProbe {
    override val id = "passive_d0d1"
    override val displayName = "Passive 0xD0/D1 handshake"
    override val priority = 20
    override val protocolFamily = ProtocolFamily.PASSIVE_D0D1

    override suspend fun probe(tag: Tag, hints: TagHints): ProbeResult? = withContext(Dispatchers.IO) {
        if (!tagHasNfcA(tag)) return@withContext null
        if (isWaveshareTag(tag, hints.waveshareNdef)) return@withContext null

        val spec = PassiveInkZoneProtocol.probeSpec(tag) ?: return@withContext null
        val entry = PassiveHardwareCatalog.resolve(context, spec.hardwareCode)
        ProbeResult(
            profile = EInkTagProfile(
                driverId = DRIVER_ID,
                protocolFamily = ProtocolFamily.PASSIVE_D0D1,
                width = entry.width,
                height = entry.height,
                colorMode = entry.colorMode,
                displayName = entry.displayName,
                hardwareCode = spec.hardwareCode,
                screenSizeKey = entry.screenSizeKey,
                confidence = Confidence.HIGH,
                notes = "Probed passive module (hw=0x${spec.hardwareCode.toString(16)})",
            ),
            rawHex = spec.rawResponseHex,
        )
    }

    companion object {
        const val DRIVER_ID = "passive_d0d1"
    }
}
