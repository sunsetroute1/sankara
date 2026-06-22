package com.joshuatz.nfceinkwriter.nfc

import android.graphics.Bitmap
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.IsoDepBadgeTagProbe
import com.joshuatz.nfceinkwriter.nfc.apdu.IsoDepEInkProtocol
import com.joshuatz.nfceinkwriter.nfc.apdu.IsoDepImageEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IsoDepEInkDriver : EInkNfcDriver {
    override val id: String = IsoDepBadgeTagProbe.DRIVER_ID
    override val name: String = "INKZONE / IsoDep"

    override fun canHandle(tag: Tag, ndefPayloadHint: Boolean): Boolean {
        if (!tagHasIsoDep(tag)) return false
        return !isWaveshareTag(tag, ndefPayloadHint)
    }

    override suspend fun sendBitmap(
        tag: Tag,
        bitmap: Bitmap,
        session: NfcFlashSession,
        progress: (Int) -> Unit,
    ): EInkFlashResult = withContext(Dispatchers.IO) {
        val isoDep = IsoDep.get(tag)
            ?: return@withContext EInkFlashResult(false, "IsoDep unavailable", name)
        try {
            isoDep.connect()
            isoDep.timeout = 30_000
            progress(5)
            IsoDepEInkProtocol.authenticate(isoDep)
            progress(10)
            val device = IsoDepEInkProtocol.readDeviceInfo(isoDep)
            Log.i(TAG, "Device ${device.width}x${device.height} ${device.numColors}-color serial=${device.serialNumber}")
            val indices = IsoDepImageEncoder.bitmapToPixelIndices(bitmap, device, session.colorMode)
            progress(15)
            IsoDepEInkProtocol.sendImage(isoDep, indices, device) { p ->
                progress(15 + (p * 70 / 100))
            }
            IsoDepEInkProtocol.refreshAndWait(isoDep, progress = { p ->
                progress(85 + (p * 15 / 100))
            })
            EInkFlashResult(true, "OK", name)
        } catch (e: Exception) {
            Log.e(TAG, "IsoDep flash failed", e)
            EInkFlashResult(false, e.message ?: "IsoDep transfer failed", name)
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "IsoDepEInkDriver"
    }
}
