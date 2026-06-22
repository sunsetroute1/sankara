package com.joshuatz.nfceinkwriter.nfc.waveshare

import android.graphics.Bitmap
import android.nfc.Tag
import android.nfc.TagLostException
import android.util.Log
import com.joshuatz.nfceinkwriter.nfc.EInkFlashResult
import com.joshuatz.nfceinkwriter.nfc.NfcFlashSession
import com.joshuatz.nfceinkwriter.nfc.isRev22WaveshareModule
import java.io.IOException

/**
 * Rev2.2 transfer over raw NfcA transceive (matches official app Frida capture).
 * Run on the reader-mode callback thread while the Tag is still valid.
 */
object Rev22WaveshareDriver {

    private const val TAG = "Rev22WaveshareDriver"
    /** Official app uses 1700 ms (0x6A4) on IsoDep during transfer. */
    private const val NFC_TIMEOUT_MS = 1_700

    fun transfer(
        tag: Tag,
        payloadBitmap: Bitmap,
        expected: Pair<Int, Int>,
        session: NfcFlashSession,
        progress: (Int) -> Unit,
        preEncodedImage: Rev22EncodedImage? = null,
    ): EInkFlashResult {
        Log.i(TAG, "Rev2.2 transfer ${expected.first}x${expected.second}")
        val image = preEncodedImage ?: Rev22BitmapEncoder.encode(payloadBitmap)
        Log.i(
            TAG,
            "Image payload ${image.blackWhite.size} bytes/plane (preEncoded=${preEncodedImage != null})",
        )

        return transferCustomProtocol(tag, session.devicePassword, image, progress)
    }

    private fun transferCustomProtocol(
        tag: Tag,
        password: String,
        image: Rev22EncodedImage,
        progress: (Int) -> Unit,
    ): EInkFlashResult {
        var link: Rev22Link? = null
        return try {
            // Successful NFCTag uploads on Samsung use IsoDep for 0x74 frames.
            link = openLink(tag)
            Rev22IsoDepProtocol.transfer(
                link = link,
                password = password,
                image = image,
                progress = { p -> progress(p.coerceIn(1, 99)) },
            )
            progress(100)
            Log.i(TAG, "Rev2.2 custom transfer completed via ${link.name}")
            EInkFlashResult(true, "OK", DRIVER_NAME)
        } catch (e: TagLostException) {
            Log.w(TAG, "Tag lost during Rev2.2 transfer", e)
            EInkFlashResult(
                false,
                "Lost NFC contact — keep holding the phone on the module until refresh finishes.",
                DRIVER_NAME,
            )
        } catch (e: Rev22IsoDepProtocol.Rev22TransferException) {
            Log.w(TAG, "Rev2.2 protocol error: ${e.message}", e)
            EInkFlashResult(false, e.message ?: "Rev2.2 transfer failed", DRIVER_NAME)
        } catch (e: IOException) {
            Log.w(TAG, "Rev2.2 IO error", e)
            EInkFlashResult(
                false,
                "Could not connect to module — keep the phone on the coil and try again.",
                DRIVER_NAME,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Rev2.2 failed", e)
            EInkFlashResult(false, e.message ?: "Rev2.2 transfer failed", DRIVER_NAME)
        } finally {
            try {
                link?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Prefer IsoDep — matches the official NFCTag app transfer path on Samsung. */
    private fun openLink(tag: Tag): Rev22Link =
        Rev22Link.open(tag, NFC_TIMEOUT_MS, Rev22Transport.ISO_DEP)

    fun isRev22Module(tag: Tag): Boolean = isRev22WaveshareModule(tag)

    private const val DRIVER_NAME = "Waveshare Rev2.2"
}
