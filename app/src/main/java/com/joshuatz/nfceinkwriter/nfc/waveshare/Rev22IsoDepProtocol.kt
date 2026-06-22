package com.joshuatz.nfceinkwriter.nfc.waveshare

import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * Waveshare Rev2.2 NFC e-ink protocol (0x74 frames over IsoDep).
 * Matches the official NFCTag app [activity.a.r] path for SDK type 6 (2.7").
 */
object Rev22IsoDepProtocol {

    private const val TAG = "Rev22IsoDep"
    private const val CHUNK_SIZE = 250
    private const val HANDSHAKE_TIMEOUT_MS = 1_700
    private const val IMAGE_TIMEOUT_MS = 30_000
    private const val REFRESH_START_DELAY_MS = 1_000L
    private const val REFRESH_POLL_MS = 250L
    private const val REFRESH_POLL_MAX_ATTEMPTS = 120
    /** Keep powering the tag after refresh ack — e-ink update continues without RF. */
    private const val POST_REFRESH_HOLD_MS = 5_000L
    /** Frida: `74 B1 00 00 08 00 11 22 33 44 55 66 77` — 7-byte key, not 8. */
    private val AUTH_PAYLOAD = byteArrayOf(
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
    )
    /** Official type-6 poll frame — 5 bytes, not 6. */
    private val REFRESH_POLL_CMD = byteArrayOf(0x74, 0x9B.toByte(), 0x00, 0x0F, 0x01)

    fun transfer(
        link: Rev22Link,
        password: String,
        image: Rev22EncodedImage,
        progress: (Int) -> Unit,
    ) {
        link.timeout = HANDSHAKE_TIMEOUT_MS
        Log.i(TAG, "${link.name} connected, starting handshake (timeout=${HANDSHAKE_TIMEOUT_MS}ms)")

        Thread.sleep(10)
        sendPassword(link, password)
        Thread.sleep(10)
        transceive(link, byteArrayOf(0x74, 0xB1.toByte(), 0x00, 0x00, 0x08, 0x00) + AUTH_PAYLOAD, "auth")
        progress(1)
        transceive(link, byteArrayOf(0x74, 0x97.toByte(), 0x01, 0x08, 0x00), "session1")
        Thread.sleep(50)
        transceive(link, byteArrayOf(0x74, 0x97.toByte(), 0x00, 0x08, 0x00), "session2")
        Thread.sleep(50)
        transceive(link, byteArrayOf(0x74, 0x97.toByte(), 0x01, 0x08, 0x00), "session3")

        Rev22Type6PanelConfig.apply(link, ::transceive)
        progress(4)
        Rev22Type6PreImageConfig.apply(link, ::transceive)
        progress(5)

        link.timeout = IMAGE_TIMEOUT_MS
        Log.i(TAG, "Image phase timeout=${IMAGE_TIMEOUT_MS}ms · bw=${image.blackWhite.size}")

        // Type 6: writeReg(0x24) then raw 74 9A 0xFA chunks (23×250 + final remainder).
        writeReg(link, 0x24)
        sendRawImagePlane(link, image.blackWhite) { planeProgress ->
            progress(5 + (planeProgress * 70 / 100))
        }

        triggerPanelRefresh(link, progress)
        Log.i(TAG, "Transfer complete · ${image.blackWhite.size} bytes")
    }

    /**
     * Official type-6 refresh: writeReg(0x22) + writeData(0xC7) + writeReg(0x20),
     * sleep 1s, poll 74 9B until response[0] != 0.
     */
    private fun triggerPanelRefresh(link: Rev22Link, progress: (Int) -> Unit) {
        link.timeout = IMAGE_TIMEOUT_MS + REFRESH_START_DELAY_MS.toInt() +
            REFRESH_POLL_MAX_ATTEMPTS * REFRESH_POLL_MS.toInt()
        writeReg(link, 0x22)
        writeData(link, 0xC7)
        writeReg(link, 0x20)

        Log.i(TAG, "Refresh started — holding field ${REFRESH_START_DELAY_MS}ms before polling")
        progress(78)
        Thread.sleep(REFRESH_START_DELAY_MS)

        for (attempt in 1..REFRESH_POLL_MAX_ATTEMPTS) {
            val response = link.transceive(REFRESH_POLL_CMD)
            val status = if (response.isNotEmpty()) response[0].toInt() and 0xFF else -1
            Log.d(TAG, "Refresh poll attempt=$attempt status=0x${status.toString(16)}")
            // Official r() type 6: refresh done when poll response[0] == 0 (not != 0).
            if (status == 0) {
                progress(95)
                Log.i(
                    TAG,
                    "Panel refresh ack (poll attempt=$attempt status=0x${status.toString(16)}) — " +
                        "holding field ${POST_REFRESH_HOLD_MS}ms",
                )
                holdFieldAfterRefresh(link)
                progress(100)
                return
            }
            progress(78 + ((attempt * 21) / REFRESH_POLL_MAX_ATTEMPTS).coerceAtMost(21))
            Thread.sleep(REFRESH_POLL_MS)
        }
        Log.w(TAG, "Refresh poll timed out — panel may not have updated")
        throw Rev22TransferException(
            "Panel refresh timed out — keep holding the phone on the module and try again.",
        )
    }

    /** Passive e-ink refresh needs RF power briefly after the refresh command ack. */
    private fun holdFieldAfterRefresh(link: Rev22Link) {
        val endMs = System.currentTimeMillis() + POST_REFRESH_HOLD_MS
        var keepalive = 0
        while (System.currentTimeMillis() < endMs) {
            Thread.sleep(500)
            try {
                link.transceive(REFRESH_POLL_CMD)
                keepalive++
            } catch (e: Exception) {
                Log.w(TAG, "Post-refresh keepalive failed (${e.message}) — field may have dropped")
                break
            }
        }
        Log.i(TAG, "Post-refresh hold complete ($keepalive keepalives)")
    }

    private fun sendRawImagePlane(
        link: Rev22Link,
        plane: ByteArray,
        progress: (Int) -> Unit,
    ) {
        val totalChunks = (plane.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        for (i in 0 until totalChunks) {
            writeImageChunk(link, plane, i * CHUNK_SIZE)
            progress(((i + 1) * 100 / totalChunks).coerceIn(0, 100))
            Log.d(TAG, "image chunk ${i + 1}/$totalChunks")
        }
    }

    private fun sendPassword(link: Rev22Link, password: String) {
        val pwd = password.toByteArray(StandardCharsets.US_ASCII)
        require(pwd.isNotEmpty() && pwd.size <= 32) { "Password must be 1–32 ASCII characters" }
        val frame = ByteArray(6 + pwd.size)
        frame[0] = 0x74
        frame[1] = 0xB3.toByte()
        frame[2] = 0x00
        frame[3] = 0x00
        frame[4] = (1 + pwd.size).toByte()
        frame[5] = pwd.size.toByte()
        System.arraycopy(pwd, 0, frame, 6, pwd.size)
        val startMs = System.currentTimeMillis()
        val response = link.transceive(frame)
        val elapsed = System.currentTimeMillis() - startMs
        Log.i(
            TAG,
            "Password TX=${frame.toHex()} RX=${response.toHex()} (${response.size} bytes, ${elapsed}ms)",
        )
    }

    private fun writeReg(link: Rev22Link, register: Int) {
        val cmd = byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D, 0x01, register.toByte())
        transceive(link, cmd, "writeReg 0x${register.toString(16)}")
    }

    private fun writeData(link: Rev22Link, vararg bytes: Int) {
        val payload = ByteArray(bytes.size) { bytes[it].toByte() }
        val cmd = byteArrayOf(0x74, 0x9A.toByte(), 0x00, 0x0E, payload.size.toByte()) + payload
        transceive(link, cmd, "writeData(${payload.size})")
    }

    /** Official type-6 path: 257-byte frames — 0xFA + 250 bytes, final chunk 0x3A + remainder. */
    private fun writeImageChunk(link: Rev22Link, plane: ByteArray, offset: Int) {
        val remaining = plane.size - offset
        val payloadLen = remaining.coerceAtMost(CHUNK_SIZE)
        val lengthMarker = if (remaining <= CHUNK_SIZE) remaining else 0xFA

        val cmd = ByteArray(257)
        cmd[0] = 0x74
        cmd[1] = 0x9A.toByte()
        cmd[2] = 0x00
        cmd[3] = 0x0E
        cmd[4] = lengthMarker.toByte()
        System.arraycopy(plane, offset, cmd, 5, payloadLen)

        transceive(
            link,
            cmd,
            "imageChunk@$offset marker=0x${lengthMarker.toString(16)} len=$payloadLen",
        )
    }

    private fun transceive(link: Rev22Link, command: ByteArray, label: String = "") {
        val response = link.transceive(command)
        if (response.size < 2) {
            Log.e(TAG, "$label FAILED short RX=${response.toHex()} TX=${command.take(16).toByteArray().toHex()}")
            throw Rev22TransferException(
                "$label: short response (${response.size} bytes): ${response.toHex()}",
            )
        }
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        if (sw1 != 0x90 || sw2 != 0x00) {
            val cmdPreview = command.take(16).toByteArray().toHex()
            Log.e(TAG, "$label FAILED SW=${swHex(sw1, sw2)} RX=${response.toHex()} TX=$cmdPreview")
            throw Rev22TransferException(
                "$label: SW=${swHex(sw1, sw2)} RX=${response.toHex()} cmd=$cmdPreview",
                sw1,
                sw2,
            )
        }
        if (label.isNotEmpty()) {
            Log.d(TAG, "$label OK")
        }
    }

    private fun swHex(sw1: Int, sw2: Int): String =
        "${sw1.toString(16).uppercase().padStart(2, '0')}${sw2.toString(16).uppercase().padStart(2, '0')}"

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    class Rev22TransferException(
        message: String,
        val sw1: Int = -1,
        val sw2: Int = -1,
    ) : Exception(message)
}
