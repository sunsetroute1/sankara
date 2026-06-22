package com.joshuatz.nfceinkwriter.nfc.waveshare

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.util.Log
import java.io.IOException

enum class Rev22Transport {
    ISO_DEP,
    NFC_A,
}

/**
 * Raw transceive link for Waveshare Rev2.2 0x74 frames.
 */
interface Rev22Link {
    val name: String
    var timeout: Int
    fun transceive(data: ByteArray): ByteArray
    fun close()

    companion object {
        private const val TAG = "Rev22Link"

        fun open(tag: Tag, timeoutMs: Int, transport: Rev22Transport = Rev22Transport.ISO_DEP): Rev22Link {
            return when (transport) {
                Rev22Transport.ISO_DEP -> openIsoDep(tag, timeoutMs)
                    ?: throw IOException("IsoDep unavailable on tag")
                Rev22Transport.NFC_A -> openNfcA(tag, timeoutMs)
            }
        }

        private fun openIsoDep(tag: Tag, timeoutMs: Int): Rev22Link? {
            val isoDep = IsoDep.get(tag) ?: return null
            return try {
                if (!isoDep.isConnected) isoDep.connect()
                isoDep.timeout = timeoutMs
                Log.i(TAG, "Using IsoDep (timeout=${timeoutMs}ms)")
                IsoDepLink(isoDep)
            } catch (e: Exception) {
                Log.w(TAG, "IsoDep connect failed", e)
                try {
                    if (isoDep.isConnected) isoDep.close()
                } catch (_: Exception) {
                }
                null
            }
        }

        private fun openNfcA(tag: Tag, timeoutMs: Int): Rev22Link {
            val nfcA = NfcA.get(tag) ?: throw IOException("NfcA unavailable on tag")
            if (!nfcA.isConnected) nfcA.connect()
            nfcA.timeout = timeoutMs
            Log.i(TAG, "Using NfcA (timeout=${timeoutMs}ms)")
            return NfcALink(nfcA)
        }
    }
}

private class IsoDepLink(private val isoDep: IsoDep) : Rev22Link {
    override val name = "IsoDep"
    override var timeout: Int
        get() = isoDep.timeout
        set(value) {
            isoDep.timeout = value
        }

    override fun transceive(data: ByteArray): ByteArray {
        Log.d(
            "Rev22Link",
            "IsoDep TX len=${data.size} ${data.take(10).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}",
        )
        return isoDep.transceive(data)
    }

    override fun close() {
        try {
            if (isoDep.isConnected) isoDep.close()
        } catch (_: Exception) {
        }
    }
}

private class NfcALink(private val nfcA: NfcA) : Rev22Link {
    override val name = "NfcA"
    override var timeout: Int
        get() = nfcA.timeout
        set(value) {
            nfcA.timeout = value
        }

    override fun transceive(data: ByteArray): ByteArray {
        Log.d(
            "Rev22Link",
            "NfcA TX len=${data.size} ${data.take(10).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}",
        )
        return nfcA.transceive(data)
    }

    override fun close() {
        try {
            if (nfcA.isConnected) nfcA.close()
        } catch (_: Exception) {
        }
    }
}
