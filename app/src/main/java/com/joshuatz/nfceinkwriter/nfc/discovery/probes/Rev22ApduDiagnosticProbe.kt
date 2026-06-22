package com.joshuatz.nfceinkwriter.nfc.discovery.probes

import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.joshuatz.nfceinkwriter.EInkColorMode
import com.joshuatz.nfceinkwriter.nfc.discovery.Confidence
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProfile
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.ProbeResult
import com.joshuatz.nfceinkwriter.nfc.discovery.ProtocolFamily
import com.joshuatz.nfceinkwriter.nfc.discovery.TagHints
import com.joshuatz.nfceinkwriter.nfc.tagHasIsoDep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only probe for newer NFC e-paper tags that speak ISO-DEP/APDU.
 *
 * Based on the community protocol notes for AID D2760000850101. This only
 * selects the applet and reads device info; it does not authenticate or write.
 */
class Rev22ApduDiagnosticProbe : EInkTagProbe {
    override val id = "rev22_apdu_diag"
    override val displayName = "Rev2.2 IsoDep/APDU diagnostic"
    override val priority = 15
    override val protocolFamily = ProtocolFamily.UNKNOWN

    override suspend fun probe(tag: Tag, hints: TagHints): ProbeResult? = withContext(Dispatchers.IO) {
        if (!tagHasIsoDep(tag)) return@withContext null
        val isoDep = IsoDep.get(tag) ?: return@withContext null
        val log = mutableListOf<String>()
        try {
            isoDep.connect()
            isoDep.timeout = 5_000
            log += "hist=${isoDep.historicalBytes?.toHex() ?: "-"} hi=${isoDep.hiLayerResponse?.toHex() ?: "-"}"

            val select = isoDep.transceive(SELECT_EPAPER_AID)
            log += "SELECT D2760000850101 -> ${select.toHex()}"

            val info = isoDep.transceive(GET_DEVICE_INFO)
            log += "GET_INFO -> ${info.toHex()}"
            readType4Ndef(isoDep, log)
            val parsed = parseDeviceInfo(info)

            if (parsed != null) {
                val (width, height, bpp, name) = parsed
                return@withContext ProbeResult(
                    profile = EInkTagProfile(
                        driverId = DRIVER_ID,
                        protocolFamily = ProtocolFamily.UNKNOWN,
                        width = width,
                        height = height,
                        colorMode = if (bpp == 1) EInkColorMode.BLACK_WHITE else EInkColorMode.FOUR_COLOR,
                        displayName = name ?: "Rev2.2 APDU e-paper",
                        confidence = Confidence.LOW,
                        notes = "Diagnostic only; APDU applet responded to read-only device-info probe.",
                    ),
                    rawHex = log.joinToString("\n"),
                )
            }

            ProbeResult(
                profile = EInkTagProfile(
                    driverId = DRIVER_ID,
                    protocolFamily = ProtocolFamily.UNKNOWN,
                    width = 0,
                    height = 0,
                    colorMode = null,
                    displayName = "Unknown IsoDep/APDU e-paper",
                    confidence = Confidence.LOW,
                    notes = "Diagnostic only; applet/device-info response did not parse.",
                ),
                rawHex = log.joinToString("\n"),
            )
        } catch (e: Exception) {
            log += "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            ProbeResult(
                profile = EInkTagProfile(
                    driverId = DRIVER_ID,
                    protocolFamily = ProtocolFamily.UNKNOWN,
                    width = 0,
                    height = 0,
                    colorMode = null,
                    displayName = "Unknown IsoDep/APDU e-paper",
                    confidence = Confidence.LOW,
                    notes = "Diagnostic only; ${e.javaClass.simpleName}: ${e.message ?: "probe failed"}",
                ),
                rawHex = log.joinToString("\n"),
            )
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readType4Ndef(isoDep: IsoDep, log: MutableList<String>) {
        try {
            val selectCc = isoDep.transceive(SELECT_CC_FILE)
            log += "SELECT CC E103 -> ${selectCc.toHex()}"
            if (!selectCc.endsWith90()) return

            val cc = isoDep.transceive(READ_CC)
            log += "READ CC -> ${cc.toHex()}"
            if (!cc.endsWith90()) return

            val ccBody = cc.copyOf(cc.size - 2)
            val ndefFileId = parseNdefFileId(ccBody)
            log += "NDEF file id -> ${ndefFileId?.toUShortHex() ?: "-"}"
            if (ndefFileId == null) return

            val selectNdef = isoDep.transceive(selectFile(ndefFileId))
            log += "SELECT NDEF ${ndefFileId.toUShortHex()} -> ${selectNdef.toHex()}"
            if (!selectNdef.endsWith90()) return

            val lenResp = isoDep.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x02))
            log += "READ NDEF LEN -> ${lenResp.toHex()}"
            if (!lenResp.endsWith90() || lenResp.size < 4) return

            val ndefLen = ((lenResp[0].toInt() and 0xFF) shl 8) or (lenResp[1].toInt() and 0xFF)
            log += "NDEF len -> $ndefLen"
            if (ndefLen <= 0) return

            val readLen = minOf(ndefLen, 96)
            val ndef = isoDep.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x02, readLen.toByte()))
            log += "READ NDEF[0..${readLen - 1}] -> ${ndef.toHex()}"
            if (ndef.endsWith90()) {
                val body = ndef.copyOf(ndef.size - 2)
                val ascii = body.map { b ->
                    val c = b.toInt() and 0xFF
                    if (c in 32..126) c.toChar() else '.'
                }.joinToString("")
                log += "NDEF ascii -> $ascii"
            }
        } catch (e: Exception) {
            log += "NDEF read ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        }
    }

    private fun parseNdefFileId(cc: ByteArray): Int? {
        var i = 7 // skip CCLEN, mapping version, MLe, MLc
        while (i + 8 <= cc.size) {
            val tag = cc[i].toInt() and 0xFF
            val len = cc[i + 1].toInt() and 0xFF
            if (tag == 0x04 && len >= 6 && i + 4 < cc.size) {
                return ((cc[i + 2].toInt() and 0xFF) shl 8) or (cc[i + 3].toInt() and 0xFF)
            }
            i += 2 + len
        }
        return null
    }

    private fun selectFile(fileId: Int): ByteArray = byteArrayOf(
        0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02,
        ((fileId ushr 8) and 0xFF).toByte(),
        (fileId and 0xFF).toByte(),
    )

    private data class DeviceInfo(val width: Int, val height: Int, val bpp: Int, val name: String?)

    private fun parseDeviceInfo(response: ByteArray): DeviceInfo? {
        if (!response.endsWith90()) return null
        val body = response.copyOf(response.size - 2)
        var i = 0
        var width: Int? = null
        var height: Int? = null
        var bpp: Int? = null
        var name: String? = null
        while (i + 2 <= body.size) {
            val tag = body[i].toInt() and 0xFF
            val len = body[i + 1].toInt() and 0xFF
            val start = i + 2
            val end = start + len
            if (end > body.size) break
            val data = body.copyOfRange(start, end)
            when (tag) {
                0xA0 -> if (data.size >= 7) {
                    val colors = data[1].toInt() and 0xFF
                    bpp = if (colors == 0x07) 2 else 1
                    val heightBits = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
                    width = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
                    height = heightBits / (bpp ?: 1)
                }
                0xC0 -> name = data.toString(Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
            }
            i = end
        }
        return if (width != null && height != null && bpp != null) {
            DeviceInfo(width!!, height!!, bpp!!, name)
        } else {
            null
        }
    }

    private fun ByteArray.endsWith90(): Boolean =
        size >= 2 && this[size - 2] == 0x90.toByte() && this[size - 1] == 0x00.toByte()

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun Int.toUShortHex(): String = "%04X".format(this and 0xFFFF)

    companion object {
        const val DRIVER_ID = "rev22_apdu_diag"
        private val SELECT_EPAPER_AID = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01,
            0x00,
        )
        private val GET_DEVICE_INFO = byteArrayOf(0x00, 0xD1.toByte(), 0x00, 0x00, 0x00)
        private val SELECT_CC_FILE = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x03)
        private val READ_CC = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x0F)
    }
}
