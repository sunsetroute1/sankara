package com.joshuatz.nfceinkwriter.nfc.passive

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.util.Log

/**
 * Passive INKZONE / 3.7" phone-case protocol over NfcA (or IsoDep transceive).
 * Reverse-engineered from the Flutter & Friends friends_badge package.
 */
object PassiveInkZoneProtocol {
    private const val TAG = "PassiveInkZone"
    private const val CHUNK_SIZE = 248

    data class PanelSpec(val width: Int, val height: Int, val hardwareCode: Int, val rawResponseHex: String = "")

    private interface Transceiver {
        fun transceive(data: ByteArray): ByteArray
        fun setTimeout(ms: Int)
    }

    /** Read-only probe — does not transfer image data. */
    fun probeSpec(tag: Tag): PanelSpec? {
        return try {
            withTransceiver(tag, specTimeoutMs = 5_000, transferTimeoutMs = 5_000) { tx ->
                val response = tx.transceive(byteArrayOf(0xD0.toByte(), 0xD1.toByte(), 0x03, 0x00, 0x01))
                parseSpecResponse(response)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Passive probe failed", e)
            null
        }
    }

    /** Read panel spec then send image in a single NFC session. */
    fun transfer(
        tag: Tag,
        blackWhite: ByteArray,
        redYellow: ByteArray,
        progress: (Int) -> Unit,
    ): PanelSpec {
        return withTransceiver(tag, specTimeoutMs = 5_000, transferTimeoutMs = 30_000) { tx ->
            transferWithConnectedTransceiver(tx, blackWhite, redYellow, progress)
        }
    }

    /** Same D0/D1 protocol, but force raw NfcA even when Android also exposes IsoDep. */
    fun transferNfcA(
        tag: Tag,
        blackWhite: ByteArray,
        redYellow: ByteArray,
        progress: (Int) -> Unit,
    ): PanelSpec {
        return withNfcATransceiver(tag, specTimeoutMs = 5_000, transferTimeoutMs = 30_000) { tx ->
            transferWithConnectedTransceiver(tx, blackWhite, redYellow, progress)
        }
    }

    private fun transferWithConnectedTransceiver(
        tx: Transceiver,
        blackWhite: ByteArray,
        redYellow: ByteArray,
        progress: (Int) -> Unit,
    ): PanelSpec {
            val response = tx.transceive(byteArrayOf(0xD0.toByte(), 0xD1.toByte(), 0x03, 0x00, 0x01))
            val panel = parseSpecResponse(response)
            Log.i(TAG, "Panel ${panel.width}x${panel.height} hw=0x${panel.hardwareCode.toString(16)}")
            progress(8)

            val totalBytes = (blackWhite.size + redYellow.size).coerceAtLeast(1)
            var sent = 0
            sendChunks(tx, blackWhite, continueFlag = 0x01, lastFlag = 0x02) { chunkLen ->
                sent += chunkLen
                progress(8 + (sent * 90 / totalBytes))
            }
            sendChunks(tx, redYellow, continueFlag = 0x04, lastFlag = 0x05) { chunkLen ->
                sent += chunkLen
                progress(8 + (sent * 90 / totalBytes))
            }
            tx.transceive(byteArrayOf(0xD0.toByte(), 0xD1.toByte(), 0x03, 0x00, 0x00))
            progress(100)
            return panel
    }

    private fun parseSpecResponse(response: ByteArray): PanelSpec {
        if (response.isEmpty()) {
            throw IllegalStateException("Empty spec response")
        }
        if (response.size == 2 && response[0] == 0x6D.toByte() && response[1] == 0x00.toByte()) {
            throw IllegalStateException("D0/D1 command was routed through IsoDep/APDU (6D00)")
        }
        val code = response[0].toInt() and 0xFF
        Log.i(
            TAG,
            "Hardware spec code=0x${code.toString(16)} " +
                "response=${response.joinToString(" ") { "%02X".format(it) }}",
        )
        return PanelSpec(
            width = 240,
            height = 416,
            hardwareCode = code,
            rawResponseHex = response.joinToString(" ") { "%02X".format(it) },
        )
    }

    private fun sendChunks(
        tx: Transceiver,
        data: ByteArray,
        continueFlag: Int,
        lastFlag: Int,
        onChunk: (Int) -> Unit,
    ) {
        if (data.isEmpty()) return
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + CHUNK_SIZE, data.size)
            val chunk = data.copyOfRange(offset, end)
            val isLast = end >= data.size
            val flag = if (isLast) lastFlag else continueFlag
            val command = ByteArray(5 + chunk.size)
            command[0] = 0xD0.toByte()
            command[1] = 0xD1.toByte()
            command[2] = flag.toByte()
            command[3] = 0x00
            command[4] = chunk.size.toByte()
            System.arraycopy(chunk, 0, command, 5, chunk.size)
            tx.transceive(command)
            onChunk(chunk.size)
            offset = end
        }
    }

    private inline fun <T> withTransceiver(
        tag: Tag,
        specTimeoutMs: Int = 5_000,
        transferTimeoutMs: Int = 30_000,
        crossinline block: (Transceiver) -> T,
    ): T {
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            try {
                isoDep.connect()
                val tx = IsoDepTransceiver(isoDep)
                tx.setTimeout(specTimeoutMs)
                val result = block(object : Transceiver {
                    private var calls = 0
                    override fun transceive(data: ByteArray): ByteArray {
                        val response = tx.transceive(data)
                        calls++
                        if (calls == 1) {
                            tx.setTimeout(transferTimeoutMs)
                        }
                        return response
                    }
                    override fun setTimeout(ms: Int) = tx.setTimeout(ms)
                })
                return result
            } finally {
                try {
                    isoDep.close()
                } catch (_: Exception) {
                }
            }
        }
        val nfcA = NfcA.get(tag) ?: throw IllegalStateException("Tag has no NfcA or IsoDep")
        return withConnectedNfcA(nfcA, specTimeoutMs, transferTimeoutMs, block)
    }

    private inline fun <T> withNfcATransceiver(
        tag: Tag,
        specTimeoutMs: Int = 5_000,
        transferTimeoutMs: Int = 30_000,
        crossinline block: (Transceiver) -> T,
    ): T {
        val nfcA = NfcA.get(tag) ?: throw IllegalStateException("Tag has no NfcA")
        return withConnectedNfcA(nfcA, specTimeoutMs, transferTimeoutMs, block)
    }

    private inline fun <T> withConnectedNfcA(
        nfcA: NfcA,
        specTimeoutMs: Int,
        transferTimeoutMs: Int,
        crossinline block: (Transceiver) -> T,
    ): T {
        try {
            nfcA.connect()
            val tx = NfcATransceiver(nfcA)
            tx.setTimeout(specTimeoutMs)
            val result = block(object : Transceiver {
                private var calls = 0
                override fun transceive(data: ByteArray): ByteArray {
                    val response = tx.transceive(data)
                    calls++
                    if (calls == 1) {
                        tx.setTimeout(transferTimeoutMs)
                    }
                    return response
                }
                override fun setTimeout(ms: Int) = tx.setTimeout(ms)
            })
            return result
        } finally {
            try {
                nfcA.close()
            } catch (_: Exception) {
            }
        }
    }

    private class NfcATransceiver(private val nfcA: NfcA) : Transceiver {
        override fun transceive(data: ByteArray): ByteArray = nfcA.transceive(data)
        override fun setTimeout(ms: Int) {
            nfcA.timeout = ms
        }
    }

    private class IsoDepTransceiver(private val isoDep: IsoDep) : Transceiver {
        override fun transceive(data: ByteArray): ByteArray = isoDep.transceive(data)
        override fun setTimeout(ms: Int) {
            isoDep.timeout = ms
        }
    }
}
