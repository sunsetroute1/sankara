package com.joshuatz.nfceinkwriter.nfc.waveshare

import android.graphics.Bitmap
import android.nfc.Tag
import android.util.Log
import com.joshuatz.nfceinkwriter.nfc.EInkFlashResult
import com.joshuatz.nfceinkwriter.nfc.EInkNfcDriver
import com.joshuatz.nfceinkwriter.nfc.NfcFlashSession
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.WaveshareTagProbe
import com.joshuatz.nfceinkwriter.nfc.isWaveshareTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WaveshareNfcDriver : EInkNfcDriver {
    override val id: String = WaveshareTagProbe.DRIVER_ID
    override val name: String = "Waveshare"

    override fun canHandle(tag: Tag, ndefPayloadHint: Boolean): Boolean =
        isWaveshareTag(tag, ndefPayloadHint)

    override suspend fun sendBitmap(
        tag: Tag,
        bitmap: Bitmap,
        session: NfcFlashSession,
        progress: (Int) -> Unit,
    ): EInkFlashResult = withContext(Dispatchers.IO) {
        val sdkType = session.profile?.waveshareSdkType
            ?: WavesharePanel.sdkTypeFromLegacyEnum(session.screenSizeEnum)
        val expected = WavesharePanel.expectedPixels(sdkType)
        val payload = WaveshareBitmapPrep.prepareForOfficial(bitmap, expected.first, expected.second)
        val officialBitmap = WaveshareBitmapPrep.invertForOfficialEngine(payload)
        Log.i(
            TAG,
            "Transfer sdkType=$sdkType expected=${expected.first}x${expected.second} " +
                "source=${bitmap.width}x${bitmap.height} payload=${payload.width}x${payload.height}",
        )

        OfficialWaveshareDriver.transferSync(
            context = session.context,
            tag = tag,
            bitmap = officialBitmap,
            panelType = sdkType,
            password = session.devicePassword,
            progress = progress,
        )
    }

    companion object {
        private const val TAG = "WaveshareNfcDriver"
    }
}
