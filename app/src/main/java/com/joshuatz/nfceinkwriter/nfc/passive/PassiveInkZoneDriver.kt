package com.joshuatz.nfceinkwriter.nfc.passive

import android.graphics.Bitmap
import android.nfc.Tag
import android.nfc.TagLostException
import android.util.Log
import com.joshuatz.nfceinkwriter.ScreenSizesInPixels
import com.joshuatz.nfceinkwriter.nfc.EInkFlashResult
import com.joshuatz.nfceinkwriter.nfc.EInkNfcDriver
import com.joshuatz.nfceinkwriter.nfc.NfcFlashSession
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.PassiveD0D1TagProbe
import com.joshuatz.nfceinkwriter.nfc.isWaveshareTag
import com.joshuatz.nfceinkwriter.nfc.tagHasIsoDep
import com.joshuatz.nfceinkwriter.nfc.tagHasNfcA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * INKZONE-style passive phone cases (NfcA + Ndef, 240×416 4-color).
 * Uses 0xD0/0xD1 transceive commands — not the Waveshare or badge-APDU protocols.
 */
class PassiveInkZoneDriver : EInkNfcDriver {
    override val id: String = PassiveD0D1TagProbe.DRIVER_ID
    override val name: String = "INKZONE passive"

    override fun canHandle(tag: Tag, ndefPayloadHint: Boolean): Boolean {
        if (!tagHasNfcA(tag)) return false
        if (isWaveshareTag(tag, ndefPayloadHint)) return false
        // Phone cases expose NfcA + Ndef only; badge modules also expose IsoDep.
        return !tagHasIsoDep(tag)
    }

    override suspend fun sendBitmap(
        tag: Tag,
        bitmap: Bitmap,
        session: NfcFlashSession,
        progress: (Int) -> Unit,
    ): EInkFlashResult = withContext(Dispatchers.IO) {
        try {
            progress(2)
            val fallback = ScreenSizesInPixels["3.7\" INKZONE"] ?: (240 to 416)
            val profile = session.profile
            val width = profile?.width ?: fallback.first
            val height = profile?.height ?: fallback.second
            val colorMode = profile?.colorMode ?: session.colorMode
            val payload = PassiveInkZoneImageEncoder.encode(
                bitmap,
                width,
                height,
                colorMode,
            )
            PassiveInkZoneProtocol.transfer(
                tag,
                payload.blackWhite,
                payload.redYellow,
            ) { p ->
                progress(p.coerceIn(0, 100))
            }
            EInkFlashResult(true, "OK", name)
        } catch (e: TagLostException) {
            Log.w(TAG, "Tag lost during INKZONE transfer", e)
            EInkFlashResult(
                false,
                "Lost contact with case — keep the upper-back of the phone flat on the coil and hold still.",
                name,
            )
        } catch (e: IOException) {
            Log.w(TAG, "I/O error during INKZONE transfer", e)
            EInkFlashResult(
                false,
                "Case did not respond — check placement, or the display module may not be responding.",
                name,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Passive INKZONE flash failed", e)
            EInkFlashResult(false, e.message ?: "INKZONE transfer failed", name)
        }
    }

    companion object {
        private const val TAG = "PassiveInkZoneDriver"
    }
}
