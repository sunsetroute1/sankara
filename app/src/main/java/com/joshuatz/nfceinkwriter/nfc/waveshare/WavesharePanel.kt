package com.joshuatz.nfceinkwriter.nfc.waveshare

import com.joshuatz.nfceinkwriter.DefaultScreenSize
import com.joshuatz.nfceinkwriter.EInkColorMode
import com.joshuatz.nfceinkwriter.Preferences
import com.joshuatz.nfceinkwriter.ScreenSizes
import com.joshuatz.nfceinkwriter.ScreenSizesInPixels
import com.joshuatz.nfceinkwriter.nfc.discovery.Confidence
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProfile
import com.joshuatz.nfceinkwriter.nfc.discovery.ProtocolFamily
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.WaveshareTagProbe

object WavesharePanel {
    /** Waveshare SDK type for the 2.7" module (264×176). */
    const val SDK_TYPE_2_7 = 6

    /** Last entry in [ScreenSizes] is INKZONE-only — not a Waveshare SDK type. */
    private const val MAX_WAVESHARE_SDK_INDEX = 6

    /** SDK enum is 1-based index into [ScreenSizes] (Waveshare panels only). */
    fun sdkTypeForScreenKey(screenKey: String): Int {
        val idx = ScreenSizes.indexOf(screenKey)
        return if (idx in 0..MAX_WAVESHARE_SDK_INDEX) idx + 1 else SDK_TYPE_2_7
    }

    fun sdkTypeForPreferences(preferences: Preferences): Int =
        sdkTypeForScreenKey(preferences.getScreenSize())

    fun sdkTypeFromLegacyEnum(screenSizeEnum: Int): Int {
        val idx = screenSizeEnum - 1
        return if (idx in 0..MAX_WAVESHARE_SDK_INDEX) screenSizeEnum else SDK_TYPE_2_7
    }

    fun transferProfile(preferences: Preferences): EInkTagProfile {
        val screenKey = preferences.getScreenSize()
        val sdkType = sdkTypeForScreenKey(screenKey)
        val pixels = expectedPixels(sdkType)
        val resolvedKey = if (ScreenSizes.indexOf(screenKey) <= MAX_WAVESHARE_SDK_INDEX) {
            screenKey
        } else {
            DefaultScreenSize
        }
        return EInkTagProfile(
            driverId = WaveshareTagProbe.DRIVER_ID,
            protocolFamily = ProtocolFamily.WAVESHARE_NFCA,
            width = pixels.first,
            height = pixels.second,
            colorMode = EInkColorMode.BLACK_WHITE,
            displayName = "Waveshare $resolvedKey",
            waveshareSdkType = sdkType,
            screenSizeKey = resolvedKey,
            confidence = Confidence.HIGH,
        )
    }

    fun expectedPixels(sdkType: Int): Pair<Int, Int> {
        val idx = sdkType - 1
        if (idx in 0..MAX_WAVESHARE_SDK_INDEX) {
            return ScreenSizesInPixels[ScreenSizes[idx]] ?: fallback()
        }
        return fallback()
    }

    private fun fallback(): Pair<Int, Int> =
        ScreenSizesInPixels[DefaultScreenSize] ?: (264 to 176)
}
