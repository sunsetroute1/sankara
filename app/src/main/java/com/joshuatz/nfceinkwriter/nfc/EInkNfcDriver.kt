package com.joshuatz.nfceinkwriter.nfc

import android.content.Context
import android.graphics.Bitmap
import android.nfc.Tag
import com.joshuatz.nfceinkwriter.EInkColorMode

data class EInkFlashResult(
    val success: Boolean,
    val message: String,
    val driverName: String = "",
    /** Failure is likely transient — re-detecting the tag and retrying may succeed. */
    val retryable: Boolean = false,
    /** Official engine refresh poll stuck at 99% — Rev22 fallback may work better. */
    val refreshStalled: Boolean = false,
    /** Do not auto re-arm reader mode (module needs cooldown after long/stalled sessions). */
    val suppressAutoRearm: Boolean = false,
)

data class NfcFlashSession(
    val context: Context,
    val screenSizeEnum: Int,
    val colorMode: EInkColorMode,
    val profile: com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProfile? = null,
    val devicePassword: String = com.joshuatz.nfceinkwriter.DefaultDevicePassword,
)

interface EInkNfcDriver {
    val id: String
    val name: String
    fun canHandle(tag: Tag, ndefPayloadHint: Boolean): Boolean
    suspend fun sendBitmap(
        tag: Tag,
        bitmap: Bitmap,
        session: NfcFlashSession,
        progress: (Int) -> Unit,
    ): EInkFlashResult
}
