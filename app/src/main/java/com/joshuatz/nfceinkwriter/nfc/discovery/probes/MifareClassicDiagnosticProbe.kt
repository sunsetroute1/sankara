package com.joshuatz.nfceinkwriter.nfc.discovery.probes

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import com.joshuatz.nfceinkwriter.nfc.discovery.Confidence
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProfile
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.ProbeResult
import com.joshuatz.nfceinkwriter.nfc.discovery.ProtocolFamily
import com.joshuatz.nfceinkwriter.nfc.discovery.TagHints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only MIFARE Classic probe for modules that expose sector/block storage. */
class MifareClassicDiagnosticProbe : EInkTagProbe {
    override val id = DRIVER_ID
    override val displayName = "MifareClassic diagnostic"
    override val priority = 16
    override val protocolFamily = ProtocolFamily.UNKNOWN

    override suspend fun probe(tag: Tag, hints: TagHints): ProbeResult? = withContext(Dispatchers.IO) {
        val mifare = MifareClassic.get(tag) ?: return@withContext null
        val log = mutableListOf<String>()
        try {
            mifare.connect()
            mifare.timeout = 5_000
            log += "type=${mifare.type} size=${mifare.size} sectors=${mifare.sectorCount} blocks=${mifare.blockCount}"

            val maxSectors = minOf(mifare.sectorCount, 4)
            for (sector in 0 until maxSectors) {
                val auth = authenticate(mifare, sector)
                log += "sector $sector auth=${auth.name}"
                if (auth == AuthResult.NONE) continue
                val firstBlock = mifare.sectorToBlock(sector)
                val blocks = minOf(mifare.getBlockCountInSector(sector), 4)
                for (offset in 0 until blocks) {
                    val block = firstBlock + offset
                    val data = mifare.readBlock(block)
                    log += "block $block -> ${data.toHex()}  ${data.toAscii()}"
                }
            }

            ProbeResult(
                profile = EInkTagProfile(
                    driverId = DRIVER_ID,
                    protocolFamily = ProtocolFamily.UNKNOWN,
                    width = 0,
                    height = 0,
                    colorMode = null,
                    displayName = "MifareClassic e-paper diagnostic",
                    confidence = Confidence.LOW,
                    notes = "Diagnostic only; read-only sector probe.",
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
                    displayName = "MifareClassic e-paper diagnostic",
                    confidence = Confidence.LOW,
                    notes = "Diagnostic only; ${e.javaClass.simpleName}: ${e.message ?: "probe failed"}",
                ),
                rawHex = log.joinToString("\n"),
            )
        } finally {
            try {
                mifare.close()
            } catch (_: Exception) {
            }
        }
    }

    private enum class AuthResult { KEY_A_DEFAULT, KEY_B_DEFAULT, KEY_A_NFC, KEY_B_NFC, NONE }

    private fun authenticate(mifare: MifareClassic, sector: Int): AuthResult {
        if (mifare.authenticateSectorWithKeyA(sector, MifareClassic.KEY_DEFAULT)) return AuthResult.KEY_A_DEFAULT
        if (mifare.authenticateSectorWithKeyB(sector, MifareClassic.KEY_DEFAULT)) return AuthResult.KEY_B_DEFAULT
        if (mifare.authenticateSectorWithKeyA(sector, MifareClassic.KEY_NFC_FORUM)) return AuthResult.KEY_A_NFC
        if (mifare.authenticateSectorWithKeyB(sector, MifareClassic.KEY_NFC_FORUM)) return AuthResult.KEY_B_NFC
        return AuthResult.NONE
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun ByteArray.toAscii(): String = map { b ->
        val c = b.toInt() and 0xFF
        if (c in 32..126) c.toChar() else '.'
    }.joinToString("")

    companion object {
        const val DRIVER_ID = "mifare_classic_diag"
    }
}
